package httpapi

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/tracking"
	"projectdrive/services/api/internal/trips"
)

// Opt-in baseline, not a throughput/load or real mobile-network benchmark.
// Real PostgreSQL authorization, HTTP handlers and loopback WebSockets are used.
func TestStreamLatencyIntegration(t *testing.T) {
	if os.Getenv("RUN_TRACKING_LATENCY") != "1" {
		t.Skip("run make latency for the 100-update, two-peer baseline")
	}
	db := integrationDB(t)
	pub, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	hub, err := tracking.New(tracking.PostgresAuthorizer{DB: db}, tracking.DefaultLimits())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(hub.Close)
	handler := NewHandler(db, slog.New(slog.NewTextHandler(io.Discard, nil)), Options{
		Trips: TripOptions{Service: tracking.Coordinator{Hub: hub, Base: trips.Store{DB: db}}, PublicKey: pub}, Tracking: hub,
	})
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	users := []identity.User{{ID: "90000000-0000-4000-8000-000000000001", Name: "Synthetic latency A"}, {ID: "90000000-0000-4000-8000-000000000002", Name: "Synthetic latency B"}}
	tokens := make([]string, 2)
	for i, u := range users {
		tokens[i], err = identity.Sign(key, u, time.Now())
		if err != nil {
			t.Fatal(err)
		}
	}
	requestNumber := 0
	rest := func(actor int, method, path, body string) map[string]any {
		t.Helper()
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Authorization", "Bearer "+tokens[actor])
		requestNumber++
		r.Header.Set("Idempotency-Key", fmt.Sprintf("91000000-0000-4000-8000-%012d", requestNumber))
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != 200 {
			t.Fatalf("synthetic latency setup failed: status=%d", w.Code)
		}
		var result map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		return result
	}
	trip := rest(0, "POST", "/v1/trips", `{"name":"Synthetic latency baseline"}`)["id"].(string)
	path := "/v1/trips/" + trip
	invite := rest(0, "POST", path+"/invites", `{"expires_in_s":600,"max_uses":2}`)["token"].(string)
	body, _ := json.Marshal(map[string]string{"token": invite})
	rest(1, "POST", "/v1/trips/join", string(body))
	rest(0, "POST", path+"/start", "")
	rest(0, "PUT", path+"/sharing", `{"enabled":true}`)
	rest(1, "PUT", path+"/sharing", `{"enabled":true}`)
	read := func(conn *websocket.Conn, kind string) map[string]any {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_, raw, err := conn.Read(ctx)
		if err != nil {
			t.Fatal("latency peer read failed")
		}
		var event map[string]any
		if json.Unmarshal(raw, &event) != nil || event["type"] != kind {
			t.Fatal("unexpected latency peer event")
		}
		return event
	}
	peers := make([]*websocket.Conn, 2)
	for i := range peers {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		peers[i], _, err = websocket.Dial(ctx, "ws"+strings.TrimPrefix(server.URL, "http")+path+"/stream", &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer " + tokens[i]}}, Subprotocols: []string{"project-drive.v1"}})
		cancel()
		if err != nil {
			t.Fatal("latency peer handshake failed")
		}
		defer peers[i].CloseNow()
		read(peers[i], "trip.snapshot")
	}
	const updates = 100
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for i := 0; i < updates; i++ {
		<-ticker.C
		sender := i % 2 // Each participant sends 1 Hz; default rate/queue limits stay unchanged.
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		err := peers[sender].Write(ctx, websocket.MessageText, []byte(wirePosition(time.Now())))
		cancel()
		if err != nil {
			t.Fatal("latency peer write failed")
		}
		for _, peer := range peers {
			e := read(peer, "participant.position")
			if e["payload"].(map[string]any)["user_id"] != users[sender].ID {
				t.Fatal("latency event sender mismatch")
			}
		}
	}
	// A client may read before the server's successful Write has returned.
	deadline := time.Now().Add(time.Second)
	for hub.PositionLatency().SuccessfulWrites < updates && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	s := hub.PositionLatency()
	t.Logf("server position latency: peers=2 updates=%d samples=%d failed_writes=%d p50_ms=%.3f p95_ms=%.3f max_ms=%.3f target_p95_ms=500; loopback+PostGIS, default limits, no client/mobile latency claim", updates, s.Samples, s.FailedWrites, float64(s.P50)/float64(time.Millisecond), float64(s.P95)/float64(time.Millisecond), float64(s.Max)/float64(time.Millisecond))
	if s.SuccessfulWrites != updates || s.Samples != updates || s.FailedWrites != 0 || s.P95 >= 500*time.Millisecond {
		t.Fatal("latency baseline target/sample integrity failed")
	}
}
