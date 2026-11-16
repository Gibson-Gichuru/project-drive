package httpapi

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http/httptest"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/reports"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestRoadReportsIntegration(t *testing.T) {
	db := integrationDB(t)
	pub, key, _ := ed25519.GenerateKey(rand.Reader)
	id := func(n int) string { return fmt.Sprintf("00000000-0000-4000-8000-%012d", n) }
	tokens := map[int]string{}
	for i := 1; i <= 15; i++ {
		tokens[i], _ = identity.Sign(key, identity.User{ID: id(i), Name: "Synthetic report tester"}, time.Now())
	}
	var logs bytes.Buffer
	h := NewHandler(db, slog.New(slog.NewTextHandler(&lockedWriter{w: &logs}, nil)), Options{Trips: TripOptions{PublicKey: pub}, Reports: reports.Store{DB: db}})
	req := func(user int, method, path, body, k string) (int, map[string]any) {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		if user > 0 {
			r.Header.Set("Authorization", "Bearer "+tokens[user])
		}
		r.Header.Set("Idempotency-Key", k)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		out := map[string]any{}
		if json.Unmarshal(w.Body.Bytes(), &out) != nil {
			t.Error("non JSON")
		}
		if w.Header().Get("Cache-Control") != "no-store" {
			t.Error("cached report response")
		}
		return w.Code, out
	}
	check := func(user int, method, path, body, k string, want int) map[string]any {
		t.Helper()
		status, out := req(user, method, path, body, k)
		if status != want {
			t.Fatalf("%s %s: %d want %d code=%v", method, path, status, want, out["code"])
		}
		return out
	}
	observed := time.Now().UTC().Truncate(time.Second).Format(time.RFC3339)
	body := `{"category":"obstacle","location":{"latitude":-1.28,"longitude":36.82},"note":"Synthetic public landmark test","observed_at":"` + observed + `"}`
	near := `{"center":{"latitude":-1.28,"longitude":36.82},"radius_m":2000}`
	check(0, "POST", "/v1/reports", body, id(101), 401)
	check(0, "POST", "/v1/reports/nearby", near, "", 401)
	for _, b := range []string{`null`, `{}`, body + `{}`, strings.Replace(body, `"category"`, `"reporter_id":"`+id(2)+`","category"`, 1), strings.Replace(body, `"longitude":36.82`, `"extra":2`, 1)} {
		check(1, "POST", "/v1/reports", b, id(101), 400)
	}
	check(1, "POST", "/v1/reports", body, "", 400)
	created := check(1, "POST", "/v1/reports", body, id(101), 200)
	report := created["id"].(string)
	path := "/v1/reports/" + report
	if created["reporter_id"] != nil || created["mine"] != true || created["confidence"] != "unverified" {
		t.Fatal("private identity leaked or false confidence")
	}
	if check(1, "POST", "/v1/reports", body, id(101), 200)["id"] != report {
		t.Fatal("retry duplicated")
	}
	check(1, "POST", "/v1/reports", strings.Replace(body, "obstacle", "accident", 1), id(101), 409)
	nearby := check(2, "POST", "/v1/reports/nearby", near, "", 200)["reports"].([]any)
	if len(nearby) != 1 {
		t.Fatal("nearby missing")
	}
	if nearby[0].(map[string]any)["mine"] != false {
		t.Fatal("incorrect ownership")
	}
	check(1, "PUT", path+"/vote", `{"vote":1}`, "", 403)
	check(2, "DELETE", path, "", "", 403)
	check(2, "PUT", path+"/vote", `{"vote":0}`, "", 400)
	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, _ := req(2, "PUT", path+"/vote", `{"vote":1}`, "")
			if status != 200 {
				t.Errorf("concurrent vote %d", status)
			}
		}()
	}
	wg.Wait()
	voted := check(2, "PUT", path+"/vote", `{"vote":1}`, "", 200)
	if voted["confirmations"] != float64(1) {
		t.Fatal("duplicate vote inflated confidence")
	}
	var audit int
	_ = db.QueryRow(context.Background(), `SELECT count(*) FROM report_audit WHERE report_id=$1 AND actor_id=$2`, report, id(2)).Scan(&audit)
	if audit != 1 {
		t.Fatal("idempotent votes inflated audit")
	}
	voted = check(2, "PUT", path+"/vote", `{"vote":-1}`, "", 200)
	if voted["confirmations"] != float64(0) || voted["dismissals"] != float64(1) {
		t.Fatal("vote replacement")
	}
	check(3, "PUT", path+"/vote", `{"vote":-1}`, "", 200)
	voted = check(4, "PUT", path+"/vote", `{"vote":-1}`, "", 200)
	if voted["confidence"] != "disputed" {
		t.Fatal("dismissal moderation")
	}
	far := strings.Replace(near, "36.82", "38.82", 1)
	if len(check(2, "POST", "/v1/reports/nearby", far, "", 200)["reports"].([]any)) != 0 {
		t.Fatal("proximity filter")
	}
	check(2, "POST", "/v1/reports/nearby", strings.Replace(near, "2000", "20001", 1), "", 400)
	withdrawn := check(1, "DELETE", path, "", "", 200)
	if withdrawn["status"] != "withdrawn" || withdrawn["note"] != "" {
		t.Fatal("withdrawal failed")
	}
	if len(check(2, "POST", "/v1/reports/nearby", near, "", 200)["reports"].([]any)) != 0 {
		t.Fatal("withdrawn warning retained")
	}
	expired := check(1, "POST", "/v1/reports", body, id(102), 200)["id"].(string)
	_, err := db.Exec(context.Background(), `UPDATE road_reports SET observed_at=now()-interval '3 hours',expires_at=now()-interval '1 hour' WHERE id=$1`, expired)
	if err != nil {
		t.Fatal(err)
	}
	if len(check(2, "POST", "/v1/reports/nearby", near, "", 200)["reports"].([]any)) != 0 {
		t.Fatal("expired report visible")
	}
	check(2, "PUT", "/v1/reports/"+expired+"/vote", `{"vote":1}`, "", 404)
	for i := 0; i < 8; i++ {
		check(1, "POST", "/v1/reports", body, id(200+i), 200)
	}
	check(1, "POST", "/v1/reports", body, id(300), 429)
	if strings.Contains(logs.String(), "36.82") || strings.Contains(logs.String(), report) || strings.Contains(logs.String(), "Synthetic public") {
		t.Fatal("sensitive report data in logs")
	}
	// Active, persisted votes and observations are purged together after the retention window.
	_, err = db.Exec(context.Background(), `UPDATE road_reports SET observed_at=now()-interval '10 days',expires_at=now()-interval '8 days'`)
	if err != nil {
		t.Fatal(err)
	}
	if err = (reports.Store{DB: db}).Purge(context.Background()); err != nil {
		t.Fatal(err)
	}
	var retained int
	_ = db.QueryRow(context.Background(), `SELECT count(*) FROM report_audit`).Scan(&retained)
	if retained != 0 {
		t.Fatal("audit retention cascade failed")
	}
}
