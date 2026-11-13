package httpapi

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/golang-jwt/jwt/v5"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/tracking"
	"projectdrive/services/api/internal/trips"
)

type streamPeer struct {
	conn   *websocket.Conn
	events chan map[string]any
	done   chan struct{}
}

func (p *streamPeer) close() { _ = p.conn.CloseNow(); <-p.done }
func (p *streamPeer) next(t *testing.T, kind string) map[string]any {
	t.Helper()
	deadline := time.After(3 * time.Second)
	for {
		select {
		case e := <-p.events:
			if e["type"] == kind {
				return e
			}
		case <-p.done:
			select {
			case e := <-p.events:
				if e["type"] == kind {
					return e
				}
			default:
			}
			t.Fatalf("stream closed while waiting for %s", kind)
			return nil
		case <-deadline:
			t.Fatalf("no %s event", kind)
			return nil
		}
	}
}
func (p *streamPeer) send(t *testing.T, raw string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := p.conn.Write(ctx, websocket.MessageText, []byte(raw)); err != nil {
		t.Fatal("socket write failed")
	}
}
func (p *streamPeer) closed(t *testing.T) {
	t.Helper()
	select {
	case <-p.done:
	case <-time.After(3 * time.Second):
		t.Fatal("revoked/invalid stream stayed open")
	}
}
func (p *streamPeer) noPosition(t *testing.T, duration time.Duration) {
	t.Helper()
	timer := time.NewTimer(duration)
	defer timer.Stop()
	for {
		select {
		case e := <-p.events:
			if e["type"] == "participant.position" {
				t.Fatal("unexpected position delivered")
			}
		case <-p.done:
			return
		case <-timer.C:
			return
		}
	}
}
func wirePosition(at time.Time) string {
	return fmt.Sprintf(`{"type":"position.update","version":1,"payload":{"latitude":0,"longitude":36,"accuracy_meters":8,"speed_mps":3,"heading_degrees":90,"recorded_at":%q}}`, at.UTC().Format(time.RFC3339Nano))
}
func TestStreamIntegration(t *testing.T) {
	db := integrationDB(t)
	pub, key, _ := ed25519.GenerateKey(rand.Reader)
	user := func(n int) string { return fmt.Sprintf("10000000-0000-4000-8000-%012d", n) }
	tokens := map[string]string{}
	for i := 1; i <= 4; i++ {
		raw, err := identity.Sign(key, identity.User{ID: user(i), Name: "Synthetic WS user"}, time.Now())
		if err != nil {
			t.Fatal(err)
		}
		tokens[user(i)] = raw
	}
	var logs bytes.Buffer
	logWriter := &lockedWriter{w: &logs}
	logger := slog.New(slog.NewTextHandler(logWriter, nil))
	limits := tracking.DefaultLimits()
	limits.PingInterval = 200 * time.Millisecond
	limits.PongTimeout = 150 * time.Millisecond
	limits.SweepInterval = 100 * time.Millisecond
	makeServer := func() (*httptest.Server, *tracking.Hub) {
		h, err := tracking.New(tracking.PostgresAuthorizer{DB: db}, limits)
		if err != nil {
			t.Fatal(err)
		}
		coordinator := tracking.Coordinator{Hub: h, Base: trips.Store{DB: db}}
		server := httptest.NewUnstartedServer(NewHandler(db, logger, Options{Trips: TripOptions{Service: coordinator, PublicKey: pub}, Tracking: h}))
		// Upgraded sockets must survive HTTP request deadlines (the runtime sets these).
		server.Config.ReadTimeout = 500 * time.Millisecond
		server.Config.WriteTimeout = 750 * time.Millisecond
		server.Start()
		t.Cleanup(func() { h.Close(); server.Close() })
		return server, h
	}
	server, hub := makeServer()
	base := server.URL
	rest := func(actor, method, path string, body any, want int) map[string]any {
		t.Helper()
		var b io.Reader
		if body != nil {
			raw, _ := json.Marshal(body)
			b = bytes.NewReader(raw)
		}
		r, err := http.NewRequest(method, base+path, b)
		if err != nil {
			t.Fatal(err)
		}
		r.Header.Set("Authorization", "Bearer "+tokens[actor])
		r.Header.Set("Idempotency-Key", fmt.Sprintf("20000000-0000-4000-8000-%012d", time.Now().UnixNano()%1000000000000))
		response, err := server.Client().Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		var result map[string]any
		_ = json.NewDecoder(response.Body).Decode(&result)
		if response.StatusCode != want {
			t.Fatalf("%s trip operation: got %d want %d code=%v", method, response.StatusCode, want, result["code"])
		}
		return result
	}
	dial := func(actor, trip string, want int, extra http.Header, suffix string) *streamPeer {
		t.Helper()
		headers := http.Header{}
		if actor != "" {
			headers.Set("Authorization", "Bearer "+tokens[actor])
		}
		for k, values := range extra {
			headers[k] = values
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		conn, response, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(base, "http")+"/v1/trips/"+trip+"/stream"+suffix, &websocket.DialOptions{HTTPHeader: headers, Subprotocols: []string{"project-drive.v1"}})
		if want != 101 {
			if conn != nil {
				_ = conn.CloseNow()
			}
			if response == nil || response.StatusCode != want {
				t.Fatalf("handshake got response=%v expected %d", response != nil, want)
			}
			if response.Body != nil {
				_ = response.Body.Close()
			}
			return nil
		}
		if err != nil {
			t.Fatal("authorized handshake failed")
		}
		conn.SetReadLimit(64 * 1024)
		peer := &streamPeer{conn: conn, events: make(chan map[string]any, 128), done: make(chan struct{})}
		go func() {
			defer close(peer.done)
			for {
				_, raw, err := conn.Read(context.Background())
				if err != nil {
					return
				}
				var event map[string]any
				if json.Unmarshal(raw, &event) != nil {
					return
				}
				select {
				case peer.events <- event:
				default:
					return
				}
			}
		}()
		t.Cleanup(func() { _ = conn.CloseNow() })
		return peer
	}
	owner, member, outsider, leaver := user(1), user(2), user(3), user(4)
	create := func(name string) (string, string) {
		t.Helper()
		trip := rest(owner, "POST", "/v1/trips", map[string]any{"name": name}, 200)["id"].(string)
		invite := rest(owner, "POST", "/v1/trips/"+trip+"/invites", map[string]any{"expires_in_s": 600, "max_uses": 10}, 200)["token"].(string)
		return trip, invite
	}
	join := func(actor, token string, want int) {
		rest(actor, "POST", "/v1/trips/join", map[string]any{"token": token}, want)
	}
	share := func(actor, trip string, enabled bool, want int) {
		rest(actor, "PUT", "/v1/trips/"+trip+"/sharing", map[string]any{"enabled": enabled}, want)
	}
	trip, invite := create("Stream lifecycle")
	path := "/v1/trips/" + trip
	join(member, invite, 200)
	join(leaver, invite, 200)
	share(owner, trip, true, 409)
	dial(owner, trip, 409, nil, "")
	rest(owner, "POST", path+"/start", nil, 200)
	share(owner, trip, true, 200)
	share(member, trip, true, 200)
	dial("", trip, 401, nil, "")
	dial(outsider, trip, 404, nil, "")
	dial(leaver, trip, 403, nil, "")
	dial(owner, trip, 400, nil, "?token=not-accepted")
	dial(owner, trip, 403, http.Header{"Origin": []string{"https://untrusted.invalid"}}, "")
	a := dial(owner, trip, 101, nil, "")
	b := dial(member, trip, 101, nil, "")
	snapshot := a.next(t, "trip.snapshot")
	b.next(t, "trip.snapshot")
	instance := snapshot["server_instance_id"].(string)
	if !identity.UUID(instance) {
		t.Fatal("server instance UUID missing")
	}
	now := time.Now().UTC()
	a.send(t, wirePosition(now))
	a.next(t, "participant.position")
	position := b.next(t, "participant.position")
	if position["payload"].(map[string]any)["user_id"] != owner {
		t.Fatal("identity routing mismatch")
	}
	b.send(t, wirePosition(now.Add(time.Millisecond)))
	b.next(t, "participant.position")
	a.next(t, "participant.position")
	a.send(t, wirePosition(now.Add(-time.Millisecond)))
	b.noPosition(t, 120*time.Millisecond)
	b.close()
	b = dial(member, trip, 101, nil, "")
	snapshot = b.next(t, "trip.snapshot")
	if len(snapshot["payload"].(map[string]any)["participants"].([]any)) != 2 {
		t.Fatal("reconnect did not resync current positions")
	}
	share(member, trip, false, 200)
	b.closed(t)
	a.next(t, "participant.left")
	dial(member, trip, 403, nil, "")
	share(member, trip, true, 200)
	b = dial(member, trip, 101, nil, "")
	snapshot = b.next(t, "trip.snapshot")
	participants := snapshot["payload"].(map[string]any)["participants"].([]any)
	if len(participants) != 1 {
		t.Fatal("share-on resurrected old coordinates")
	}
	rest(leaver, "DELETE", path+"/members/"+member, nil, 403)
	rest(owner, "DELETE", path+"/members/"+owner, nil, 409)
	rest(owner, "DELETE", path+"/members/"+member, nil, 200)
	b.closed(t)
	rest(member, "GET", path, nil, 404)
	join(member, invite, 400)
	share(leaver, trip, true, 200)
	c := dial(leaver, trip, 101, nil, "")
	c.next(t, "trip.snapshot")
	c.send(t, wirePosition(time.Now()))
	c.next(t, "participant.position")
	a.next(t, "participant.position")
	rest(leaver, "POST", path+"/leave", nil, 200)
	c.closed(t)
	dial(leaver, trip, 404, nil, "")
	join(leaver, invite, 200)
	dial(leaver, trip, 403, nil, "")
	share(leaver, trip, true, 200)
	c = dial(leaver, trip, 101, nil, "")
	c.next(t, "trip.snapshot")
	rest(owner, "POST", path+"/end", nil, 200)
	a.closed(t)
	c.closed(t)
	dial(owner, trip, 409, nil, "")
	// Cancellation has the same atomic revocation behavior as completion.
	cancelTrip, token := create("Cancellation")
	join(leaver, token, 200)
	rest(owner, "POST", "/v1/trips/"+cancelTrip+"/start", nil, 200)
	share(owner, cancelTrip, true, 200)
	share(leaver, cancelTrip, true, 200)
	ca := dial(owner, cancelTrip, 101, nil, "")
	cb := dial(leaver, cancelTrip, 101, nil, "")
	ca.next(t, "trip.snapshot")
	cb.next(t, "trip.snapshot")
	ca.send(t, wirePosition(time.Now()))
	cb.next(t, "participant.position")
	rest(owner, "POST", "/v1/trips/"+cancelTrip+"/cancel", nil, 200)
	ca.closed(t)
	cb.closed(t)
	// Server restart retains consent but never resurrects ephemeral coordinates.
	restartTrip, restartInvite := create("Restart")
	rest(owner, "POST", "/v1/trips/"+restartTrip+"/start", nil, 200)
	share(owner, restartTrip, true, 200)
	old := dial(owner, restartTrip, 101, nil, "")
	old.next(t, "trip.snapshot")
	old.send(t, wirePosition(time.Now()))
	old.next(t, "participant.position")
	hub.Close()
	old.closed(t)
	server, hub = makeServer()
	base = server.URL
	fresh := dial(owner, restartTrip, 101, nil, "")
	snapshot = fresh.next(t, "trip.snapshot")
	if snapshot["server_instance_id"] == instance || len(snapshot["payload"].(map[string]any)["participants"].([]any)) != 0 {
		t.Fatal("restart restored live position or reused instance")
	}
	fresh.close()
	// Invalid/spoofed/oversized messages close the offending connection.
	for _, raw := range []string{`{"type":"position.update","version":1,"payload":{"user_id":"spoof"}}`, `not-json`, strings.Repeat("x", 5000)} {
		p := dial(owner, restartTrip, 101, nil, "")
		p.next(t, "trip.snapshot")
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		_ = p.conn.Write(ctx, websocket.MessageText, []byte(raw))
		cancel()
		p.closed(t)
	}
	// Expiring bearer sessions are closed without waiting for another client update.
	claims := identity.Claims{Name: "Synthetic expiry", RegisteredClaims: jwt.RegisteredClaims{Issuer: identity.Issuer, Subject: owner, Audience: jwt.ClaimStrings{identity.Audience}, IssuedAt: jwt.NewNumericDate(time.Now()), ExpiresAt: jwt.NewNumericDate(time.Now().Add(2 * time.Second))}}
	short, err := jwt.NewWithClaims(jwt.SigningMethodEdDSA, claims).SignedString(key)
	if err != nil {
		t.Fatal(err)
	}
	original := tokens[owner]
	tokens[owner] = short
	expires := dial(owner, restartTrip, 101, nil, "")
	expires.next(t, "trip.snapshot")
	expires.closed(t)
	tokens[owner] = original
	// A client that does not read cannot answer Ping; it is disconnected.
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	unread, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(base, "http")+"/v1/trips/"+restartTrip+"/stream", &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer " + tokens[owner]}}})
	cancel()
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(600 * time.Millisecond)
	count, err := hub.ConnectionCount(context.Background())
	if err != nil || count != 0 {
		t.Fatal("unresponsive heartbeat client remained connected")
	}
	_ = unread.CloseNow()
	// Large bursts are rate-limited; no unbounded receive or send queue is allocated.
	burst := dial(owner, restartTrip, 101, nil, "")
	burst.next(t, "trip.snapshot")
	for i := 0; i < 10; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		_ = burst.conn.Write(ctx, websocket.MessageText, []byte(wirePosition(time.Now().Add(time.Duration(i)*time.Microsecond))))
		cancel()
	}
	burst.closed(t)
	// Exercise the real expiry sweeper and stale snapshots with scaled test durations.
	hub.Close()
	limits.PositionTTL = 2 * time.Second
	limits.StaleAfter = 500 * time.Millisecond
	limits.MaxAge = time.Second
	server, hub = makeServer()
	base = server.URL
	join(member, restartInvite, 200)
	share(member, restartTrip, true, 200)
	ttlA := dial(owner, restartTrip, 101, nil, "")
	ttlB := dial(member, restartTrip, 101, nil, "")
	ttlA.next(t, "trip.snapshot")
	ttlB.next(t, "trip.snapshot")
	ttlA.send(t, wirePosition(time.Now()))
	ttlA.next(t, "participant.position")
	ttlB.next(t, "participant.position")
	time.Sleep(650 * time.Millisecond)
	ttlB.close()
	ttlB = dial(member, restartTrip, 101, nil, "")
	staleSnapshot := ttlB.next(t, "trip.snapshot")["payload"].(map[string]any)["participants"].([]any)
	if len(staleSnapshot) != 1 || staleSnapshot[0].(map[string]any)["stale"] != true {
		t.Fatal("old position was not marked stale")
	}
	expired := ttlA.next(t, "participant.left")["payload"].(map[string]any)
	if expired["reason"] != "expired" {
		t.Fatal("position TTL did not expire")
	}
	ttlB.close()
	ttlB = dial(member, restartTrip, 101, nil, "")
	if len(ttlB.next(t, "trip.snapshot")["payload"].(map[string]any)["participants"].([]any)) != 0 {
		t.Fatal("expired coordinates present in snapshot")
	}
	ttlA.close()
	ttlB.close()
	hub.Close()
	logWriter.mu.Lock()
	logText := logs.String()
	logWriter.mu.Unlock()
	if strings.Contains(logText, "latitude") || strings.Contains(logText, tokens[owner]) || strings.Contains(logText, trip) {
		t.Fatal("sensitive data leaked to ordinary logs")
	}

	t.Log("M3 real WebSocket/PostGIS acceptance passed: two clients, private snapshots, sharing, removal, leave, terminal states, reconnect, ordering, expiry, restart and message limits")
}
