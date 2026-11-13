package httpapi

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/trips"
)

// TestTripIntegration uses a disposable schema, never truncates application tables,
// and exercises the real HTTP boundary and PostgreSQL transactions concurrently.
func TestTripIntegration(t *testing.T) {
	ctx := context.Background()
	db := integrationDB(t)
	pub, key, _ := ed25519.GenerateKey(rand.Reader)
	tokens := map[string]string{}
	id := func(n int) string { return fmt.Sprintf("00000000-0000-4000-8000-%012d", n) }
	for n := 1; n <= 30; n++ {
		token, err := identity.Sign(key, identity.User{ID: id(n), Name: fmt.Sprintf("Synthetic user %d", n)}, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		tokens[id(n)] = token
	}
	var logs bytes.Buffer
	// Logging buffer is protected because the concurrency test calls ServeHTTP in parallel.
	logger := slog.New(slog.NewTextHandler(&lockedWriter{w: &logs}, nil))
	handler := NewHandler(db, logger, Options{Trips: TripOptions{Service: trips.Store{DB: db}, PublicKey: pub}})
	request := func(user, method, path, body, key string) (int, map[string]any) {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		if user != "" {
			r.Header.Set("Authorization", "Bearer "+tokens[user])
		}
		if key != "" {
			r.Header.Set("Idempotency-Key", key)
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		var result map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
			t.Errorf("non-JSON response status %d", w.Code)
		}
		if w.Header().Get("Cache-Control") != "no-store" {
			t.Error("missing private response cache policy")
		}
		return w.Code, result
	}
	check := func(user, method, path, body, key string, want int) map[string]any {
		t.Helper()
		status, value := request(user, method, path, body, key)
		if status != want {
			t.Fatalf("%s %s: status %d expected %d code=%v", method, path, status, want, value["code"])
		}
		return value
	}
	owner, member, outsider := id(1), id(2), id(3)
	check("", "POST", "/v1/trips", `{"name":"Trip"}`, id(101), 401)
	// The default/homelab handler has no authenticator, even if a forged bearer is supplied.
	r := httptest.NewRequest("POST", "/v1/trips", strings.NewReader(`{"name":"Trip"}`))
	r.Header.Set("Authorization", "Bearer "+tokens[owner])
	w := httptest.NewRecorder()
	NewHandler(db, slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal("disabled auth accepted bearer")
	}
	for _, body := range []string{`{"name":"Trip","owner_id":"` + outsider + `"}`, `{"name":""}`, `null`, `{"name":"Trip"} {}`, `{"name":"` + strings.Repeat("x", 5000) + `"}`} {
		check(owner, "POST", "/v1/trips", body, id(101), 400)
	}
	check(owner, "POST", "/v1/trips", `{"name":"Trip"}`, "", 400)
	created := check(owner, "POST", "/v1/trips", `{"name":"Trip"}`, id(101), 200)
	trip := created["id"].(string)
	path := "/v1/trips/" + trip
	if !strings.HasSuffix(created["created_at"].(string), "Z") {
		t.Fatal("timestamps must be UTC")
	}
	if created["owner_id"] != owner || created["status"] != "planned" {
		t.Fatal("incorrect created trip")
	}
	retry := check(owner, "POST", "/v1/trips", `{"name":"Trip"}`, id(101), 200)
	if retry["id"] != trip {
		t.Fatal("duplicate create")
	}
	check(owner, "POST", "/v1/trips", `{"name":"Different"}`, id(101), 409)
	check(outsider, "GET", path, "", "", 404)
	check(outsider, "GET", path+"/members", "", "", 404)
	check(owner, "POST", path+"/end", "", "", 409)
	check(owner, "POST", path+"/leave", "", "", 409)
	invite := check(owner, "POST", path+"/invites", `{"expires_in_s":600,"max_uses":1}`, id(102), 200)
	secret := invite["token"].(string)
	join := `{"token":"` + secret + `"}`
	repeatedInvite := check(owner, "POST", path+"/invites", `{"expires_in_s":600,"max_uses":1}`, id(102), 409)
	if repeatedInvite["invite_id"] != invite["id"] {
		t.Fatal("lost invitation cannot be revoked")
	}
	check(member, "POST", "/v1/trips/join", join, "", 200)
	check(member, "POST", "/v1/trips/join", join, "", 200)
	var used int
	var stored []byte
	if err := db.QueryRow(ctx, `SELECT use_count,token_hash FROM trip_invites WHERE id=$1`, invite["id"]).Scan(&used, &stored); err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256([]byte(secret))
	if used != 1 || !bytes.Equal(stored, hash[:]) {
		t.Fatal("hash or duplicate redemption invariant failed")
	}
	check(outsider, "POST", "/v1/trips/join", join, "", 400)
	members := check(member, "GET", path+"/members", "", "", 200)["members"].([]any)
	if len(members) != 2 {
		t.Fatal("expected two members")
	}
	for _, v := range members {
		if v.(map[string]any)["sharing_enabled"] != false {
			t.Fatal("sharing enabled by default")
		}
	}
	for _, action := range []string{"start", "end", "cancel"} {
		check(member, "POST", path+"/"+action, "", "", 403)
	}
	check(member, "POST", path+"/invites", `{"expires_in_s":600,"max_uses":1}`, id(103), 403)
	check(member, "DELETE", path+"/invites/"+invite["id"].(string), "", "", 403)
	check(owner, "POST", path+"/start", "", "", 200)
	check(owner, "POST", path+"/start", "", "", 200)
	check(member, "POST", path+"/leave", "", "", 200)
	check(member, "POST", path+"/leave", "", "", 200)
	check(member, "GET", path, "", "", 404)
	// Leaving and rejoining is explicit, consumes a new use and resets sharing.
	multi := check(owner, "POST", path+"/invites", `{"expires_in_s":600,"max_uses":3}`, id(104), 200)
	multiBody := `{"token":"` + multi["token"].(string) + `"}`
	check(member, "POST", "/v1/trips/join", multiBody, "", 200)
	check(member, "POST", path+"/leave", "", "", 200)
	check(member, "POST", "/v1/trips/join", multiBody, "", 200)
	check(owner, "DELETE", path+"/invites/"+multi["id"].(string), "", "", 200)
	check(owner, "DELETE", path+"/invites/"+multi["id"].(string), "", "", 200)
	check(outsider, "POST", "/v1/trips/join", multiBody, "", 400)
	expired := check(owner, "POST", path+"/invites", `{"expires_in_s":60,"max_uses":1}`, id(105), 200)
	if _, err := db.Exec(ctx, `UPDATE trip_invites SET expires_at=now()-interval '1 second' WHERE id=$1`, expired["id"]); err != nil {
		t.Fatal(err)
	}
	check(outsider, "POST", "/v1/trips/join", `{"token":"`+expired["token"].(string)+`"}`, "", 400)
	// Twenty simultaneous distinct users contend for one remaining invitation use.
	last := check(owner, "POST", path+"/invites", `{"expires_in_s":600,"max_uses":1}`, id(106), 200)
	statuses := make(chan int, 20)
	var wg sync.WaitGroup
	for n := 4; n < 24; n++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			status, _ := request(id(n), "POST", "/v1/trips/join", `{"token":"`+last["token"].(string)+`"}`, "")
			statuses <- status
		}(n)
	}
	wg.Wait()
	close(statuses)
	successes := 0
	for status := range statuses {
		if status == 200 {
			successes++
		} else if status != 400 {
			t.Fatalf("concurrent join status %d", status)
		}
	}
	if successes != 1 {
		t.Fatalf("concurrent use bound violated: %d", successes)
	}
	// Terminal transitions must clear existing consent and revoke outstanding invites.
	if _, err := db.Exec(ctx, `UPDATE trip_members SET sharing_enabled=true WHERE trip_id=$1 AND user_id=$2`, trip, member); err != nil {
		t.Fatal(err)
	}
	ended := check(owner, "POST", path+"/end", "", "", 200)
	if ended["status"] != "completed" || ended["started_at"] == nil || ended["ended_at"] == nil {
		t.Fatal("invalid terminal timestamps")
	}
	check(owner, "POST", path+"/end", "", "", 200)
	check(owner, "POST", path+"/start", "", "", 409)
	check(owner, "POST", path+"/cancel", "", "", 409)
	check(owner, "POST", path+"/invites", `{"expires_in_s":600,"max_uses":1}`, id(107), 409)
	check(outsider, "POST", "/v1/trips/join", join, "", 400)
	for i, active := range []bool{false, true} {
		value := check(owner, "POST", "/v1/trips", `{"name":"Cancel"}`, id(110+i), 200)
		p := "/v1/trips/" + value["id"].(string)
		if active {
			check(owner, "POST", p+"/start", "", "", 200)
		}
		cancelled := check(owner, "POST", p+"/cancel", "", "", 200)
		if cancelled["status"] != "cancelled" || cancelled["ended_at"] == nil {
			t.Fatal("invalid cancel")
		}
		check(owner, "POST", p+"/cancel", "", "", 200)
		check(owner, "POST", p+"/end", "", "", 409)
	}
	// Capacity is enforced independently of invitation use limits.
	full := check(owner, "POST", "/v1/trips", `{"name":"Capacity"}`, id(120), 200)
	fullPath := "/v1/trips/" + full["id"].(string)
	if _, err := db.Exec(ctx, `WITH added AS (INSERT INTO users(display_name) SELECT 'Synthetic capacity member' FROM generate_series(1,99) RETURNING id) INSERT INTO trip_members(trip_id,user_id,role) SELECT $1,id,'member' FROM added`, full["id"]); err != nil {
		t.Fatal(err)
	}
	fullInvite := check(owner, "POST", fullPath+"/invites", `{"expires_in_s":600,"max_uses":1}`, id(121), 200)
	check(outsider, "POST", "/v1/trips/join", `{"token":"`+fullInvite["token"].(string)+`"}`, "", 409)
	if err := db.QueryRow(ctx, `SELECT use_count FROM trip_invites WHERE id=$1`, fullInvite["id"]).Scan(&used); err != nil || used != 0 {
		t.Fatal("failed join consumed invite")
	}
	// Simultaneous create retries must produce one trip and one owner membership.
	var createdIDs sync.Map
	for n := 0; n < 10; n++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			status, v := request(owner, "POST", "/v1/trips", `{"name":"Concurrent create"}`, id(122))
			if status != 200 {
				t.Errorf("create retry status %d", status)
				return
			}
			createdIDs.Store(v["id"], true)
		}()
	}
	wg.Wait()
	distinct := 0
	createdIDs.Range(func(_, _ any) bool { distinct++; return true })
	if distinct != 1 {
		t.Fatal("concurrent create duplicated trip")
	}
	// Deleted identities fail closed; a signed token cannot recreate a deleted account.
	if _, err := db.Exec(ctx, `UPDATE users SET deleted_at=now() WHERE id=$1`, member); err != nil {
		t.Fatal(err)
	}
	check(member, "GET", path, "", "", 401)
	if strings.Contains(logs.String(), secret) || strings.Contains(logs.String(), tokens[owner]) || strings.Contains(logs.String(), trip) {
		t.Fatal("sensitive identifiers leaked to logs")
	}
	var leaked int
	if err := db.QueryRow(ctx, `SELECT count(*) FROM trip_members WHERE sharing_enabled`).Scan(&leaked); err != nil || leaked != 0 {
		t.Fatal("unexpected sharing state")
	}
	t.Log("M2 acceptance passed: two identities, private reads, lifecycle, idempotency, hashed/expired/revoked invites, duplicate and concurrent redemption")
}

type lockedWriter struct {
	mu sync.Mutex
	w  io.Writer
}

func (w *lockedWriter) Write(b []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.w.Write(b)
}
