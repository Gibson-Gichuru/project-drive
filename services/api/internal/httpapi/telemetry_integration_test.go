package httpapi

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http/httptest"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/telemetry"
	"projectdrive/services/api/internal/trips"
	"strings"
	"testing"
	"time"
)

func TestTelemetryIntegration(t *testing.T) {
	db := integrationDB(t)
	pub, key, _ := ed25519.GenerateKey(rand.Reader)
	now := time.Now().UTC().Truncate(time.Millisecond)
	id := func(n int) string { return fmt.Sprintf("00000000-0000-4000-8000-%012d", n) }
	tokens := map[int]string{}
	for n := 1; n <= 3; n++ {
		tokens[n], _ = identity.Sign(key, identity.User{ID: id(n), Name: fmt.Sprintf("Telemetry test %d", n)}, time.Now())
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	store := &telemetry.Store{DB: db}
	handler := NewHandler(db, logger, Options{Trips: TripOptions{Service: trips.Store{DB: db}, PublicKey: pub}, Telemetry: store})
	request := func(user int, method, path string, body any, want int) map[string]any {
		t.Helper()
		data := []byte{}
		if body != nil {
			data, _ = json.Marshal(body)
		}
		r := httptest.NewRequest(method, path, strings.NewReader(string(data)))
		if user != 0 {
			r.Header.Set("Authorization", "Bearer "+tokens[user])
		}
		r.Header.Set("Idempotency-Key", id(100))
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		var response map[string]any
		_ = json.Unmarshal(w.Body.Bytes(), &response)
		if w.Code != want {
			t.Fatalf("%s status %d wanted %d code=%v", method, w.Code, want, response["code"])
		}
		if w.Header().Get("Cache-Control") != "no-store" {
			t.Fatal("private cache policy missing")
		}
		return response
	}
	trip := request(1, "POST", "/v1/trips", map[string]any{"name": "Telemetry"}, 200)["id"].(string)
	path := "/v1/trips/" + trip
	invitation := request(1, "POST", path+"/invites", map[string]int{"expires_in_s": 600, "max_uses": 10}, 200)["token"]
	request(2, "POST", "/v1/trips/join", map[string]any{"token": invitation}, 200)
	batch := func(n int) telemetry.Batch {
		return telemetry.Batch{Version: 1, ID: id(n), Summary: telemetry.Summary{StartedAt: now.Add(-time.Minute), EndedAt: now, Duration: 60, Covered: 60, Distance: 300, MeanSpeed: 5, MaxSpeed: 10, SpeedSamples: 3, GPSSamples: 3, MeanAccuracy: 12, Acceleration: 1, Source: "phone_gps_imu_v1", Orientation: "unavailable", Calibration: "uncalibrated", Confidence: .35}, Events: []telemetry.Event{{Type: "acceleration_candidate", Magnitude: 4, Confidence: .35, Source: "gps_delta", Accuracy: 12, Elapsed: 10000, OccurredAt: now.Add(-50 * time.Second), Orientation: "unavailable", Calibration: "uncalibrated"}}}
	}
	b := batch(200)
	request(0, "POST", path+"/telemetry", b, 401)
	request(3, "POST", path+"/telemetry", b, 404)
	request(1, "POST", path+"/telemetry", b, 409)
	request(1, "POST", path+"/start", nil, 200)
	request(1, "POST", path+"/telemetry", b, 202)
	request(1, "POST", path+"/telemetry", b, 202)
	b.Summary.Distance = 301
	request(1, "POST", path+"/telemetry", b, 409)
	own := request(1, "GET", path+"/summary", nil, 200)["recordings"].([]any)
	if len(own) != 1 {
		t.Fatal("replayed telemetry duplicated")
	}
	if len(request(2, "GET", path+"/summary", nil, 200)["recordings"].([]any)) != 0 {
		t.Fatal("member saw another user's telemetry")
	}
	request(3, "GET", path+"/summary", nil, 404)
	request(2, "POST", path+"/telemetry", batch(200), 409)
	request(2, "POST", path+"/telemetry", batch(201), 202)
	request(2, "POST", path+"/leave", nil, 200)
	request(2, "GET", path+"/summary", nil, 404)
	request(2, "POST", path+"/telemetry", batch(202), 404)
	for n := 1; n < 20; n++ {
		request(1, "POST", path+"/telemetry", batch(300+n), 202)
	}
	request(1, "POST", path+"/telemetry", batch(400), 409)
	_, err := db.Exec(context.Background(), `UPDATE telemetry_recordings SET expires_at=now()-interval '1 second' WHERE trip_id=$1`, trip)
	if err != nil {
		t.Fatal(err)
	}
	if len(request(1, "GET", path+"/summary", nil, 200)["recordings"].([]any)) != 0 {
		t.Fatal("expired telemetry returned")
	}
	if err = store.Purge(context.Background()); err != nil {
		t.Fatal(err)
	}
	var count int
	if err = db.QueryRow(context.Background(), `SELECT count(*) FROM telemetry_events WHERE trip_id=$1`, trip).Scan(&count); err != nil || count != 0 {
		t.Fatal("expired event cascade failed")
	}
	request(1, "POST", path+"/end", nil, 200)
	request(1, "POST", path+"/telemetry", batch(500), 202)
	var coordinates int
	_ = db.QueryRow(context.Background(), `SELECT count(*) FROM telemetry_events WHERE location IS NOT NULL`).Scan(&coordinates)
	if coordinates != 0 {
		t.Fatal("raw position persisted")
	}
	invalid := map[string]any{"version": 1, "recording_id": id(600), "latitude": -1.2}
	request(1, "POST", path+"/telemetry", invalid, 400)
	// Disabled authentication remains closed even for a locally valid bearer.
	w := httptest.NewRecorder()
	r := httptest.NewRequest("GET", path+"/summary", nil)
	r.Header.Set("Authorization", "Bearer "+tokens[1])
	NewHandler(db, logger, Options{Telemetry: store}).ServeHTTP(w, r)
	if w.Code != 401 {
		t.Fatal("disabled identity accepted")
	}
	t.Log("M5 PostGIS acceptance: own-only summaries, explicit bounded uploads, idempotency, membership, limits, expiry and no raw coordinates")
}
