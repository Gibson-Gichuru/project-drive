package tracking

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/trips"
)

const testTrip = "11111111-1111-4111-8111-111111111111"
const userA = "22222222-2222-4222-8222-222222222222"
const userB = "33333333-3333-4333-8333-333333333333"

type fakeAuth struct {
	mu    sync.Mutex
	state State
	err   error
}

func (a *fakeAuth) State(context.Context, string) (State, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	s := State{Active: a.state.Active, Members: map[string]Member{}}
	for k, v := range a.state.Members {
		s.Members[k] = v
	}
	return s, a.err
}
func (a *fakeAuth) set(user string, sharing bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.state.Members[user] = Member{sharing}
}
func fixture(t *testing.T, change func(*Limits)) (*Hub, *fakeAuth) {
	t.Helper()
	l := DefaultLimits()
	if change != nil {
		change(&l)
	}
	a := &fakeAuth{state: State{Active: true, Members: map[string]Member{userA: {true}, userB: {true}}}}
	h, err := New(a, l)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(h.Close)
	return h, a
}
func subscribe(t *testing.T, h *Hub, user string) *Subscription {
	t.Helper()
	s, err := h.Subscribe(context.Background(), testTrip, identity.Session{User: identity.User{ID: user, Name: "Synthetic"}, ExpiresAt: time.Now().Add(time.Minute)})
	if err != nil {
		t.Fatal(err)
	}
	return s
}
func next(t *testing.T, s *Subscription) Delivery {
	t.Helper()
	select {
	case d := <-s.Events():
		return d
	case <-time.After(time.Second):
		t.Fatal("no queued event")
		return Delivery{}
	}
}
func update(at time.Time) []byte {
	return []byte(fmt.Sprintf(`{"type":"position.update","version":1,"payload":{"latitude":0,"longitude":36,"accuracy_meters":8,"recorded_at":%q}}`, at.UTC().Format(time.RFC3339Nano)))
}
func TestPositionValidationAndExpiry(t *testing.T) {
	now := time.Now().UTC()
	l := DefaultLimits()
	p, err := decodePosition(update(now), userA, now, l)
	if err != nil {
		t.Fatal(err)
	}
	var valid map[string]any
	if err := json.Unmarshal(update(now), &valid); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name   string
		change func(map[string]any)
	}{
		{"spoof user", func(m map[string]any) { m["payload"].(map[string]any)["user_id"] = userB }},
		{"missing accuracy", func(m map[string]any) { delete(m["payload"].(map[string]any), "accuracy_meters") }},
		{"null latitude", func(m map[string]any) { m["payload"].(map[string]any)["latitude"] = nil }},
		{"bad latitude", func(m map[string]any) { m["payload"].(map[string]any)["latitude"] = 91 }},
		{"zero accuracy", func(m map[string]any) { m["payload"].(map[string]any)["accuracy_meters"] = 0 }},
		{"negative speed", func(m map[string]any) { m["payload"].(map[string]any)["speed_mps"] = -1 }},
		{"heading overflow", func(m map[string]any) { m["payload"].(map[string]any)["heading_degrees"] = 361 }},
		{"old", func(m map[string]any) {
			m["payload"].(map[string]any)["recorded_at"] = now.Add(-20 * time.Second).Format(time.RFC3339Nano)
		}},
		{"future", func(m map[string]any) {
			m["payload"].(map[string]any)["recorded_at"] = now.Add(6 * time.Second).Format(time.RFC3339Nano)
		}},
		{"version", func(m map[string]any) { m["version"] = 2 }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var m map[string]any
			_ = json.Unmarshal(update(now), &m)
			tc.change(m)
			raw, _ := json.Marshal(m)
			if _, err := decodePosition(raw, userA, now, l); !errors.Is(err, ErrInvalid) {
				t.Fatal("invalid position accepted")
			}
		})
	}
	m := NewMemoryPositions(1)
	if err := m.Put(testTrip, p); err != nil {
		t.Fatal(err)
	}
	if !errors.Is(m.Put(testTrip, p), ErrOlder) {
		t.Fatal("duplicate accepted")
	}
	p2 := p
	p2.UserID = userB
	if !errors.Is(m.Put(testTrip, p2), ErrCapacity) {
		t.Fatal("position bound exceeded")
	}
	if !m.Snapshot(testTrip, now.Add(11*time.Second), l.StaleAfter)[0].Stale {
		t.Fatal("stale position labeled fresh")
	}
	expired := m.Expire(now.Add(31 * time.Second))
	if len(expired) != 1 || len(m.Snapshot(testTrip, now.Add(31*time.Second), l.StaleAfter)) != 0 {
		t.Fatal("expired position retained")
	}
	if m.entries[PositionKey{testTrip, userA}].position != nil {
		t.Fatal("expired coordinates not erased")
	}
	if !errors.Is(m.Put(testTrip, p), ErrOlder) {
		t.Fatal("ordering watermark lost on expiry")
	}
	m.RemoveTrip(testTrip)
	if len(m.entries) != 0 {
		t.Fatal("trip purge incomplete")
	}
}
func TestQueuedPositionCannotReturnAfterShareOffOn(t *testing.T) {
	h, a := fixture(t, nil)
	sa := subscribe(t, h, userA)
	sb := subscribe(t, h, userB)
	next(t, sa)
	next(t, sb)
	if err := h.Publish(context.Background(), sa, update(time.Now())); err != nil {
		t.Fatal(err)
	}
	old := next(t, sb)
	if old.Subject != userA {
		t.Fatal("missing subject")
	}
	a.set(userA, false)
	if err := h.Sweep(context.Background()); err != nil {
		t.Fatal(err)
	}
	a.set(userA, true)
	if err := h.Sweep(context.Background()); err != nil {
		t.Fatal(err)
	}
	wrote := false
	if err := h.Deliver(context.Background(), sb, old, func(Event) error { wrote = true; return nil }); err != nil {
		t.Fatal(err)
	}
	if wrote {
		t.Fatal("old coordinate resurrected after renewed consent")
	}
	if len(h.positions.Snapshot(testTrip, time.Now(), h.limits.StaleAfter)) != 0 {
		t.Fatal("revoked position survived")
	}
	if err := h.Publish(context.Background(), sa, update(time.Now())); !errors.Is(err, ErrStopped) {
		t.Fatal("revoked connection published")
	}
}
func TestSnapshotIsRebuiltAndTerminalTripPurges(t *testing.T) {
	h, a := fixture(t, nil)
	sa := subscribe(t, h, userA)
	sb := subscribe(t, h, userB)
	next(t, sa)
	snapshot := next(t, sb)
	if err := h.Publish(context.Background(), sa, update(time.Now())); err != nil {
		t.Fatal(err)
	}
	a.set(userA, false)
	_ = h.Sweep(context.Background())
	var count int
	if err := h.Deliver(context.Background(), sb, snapshot, func(e Event) error { count = len(e.Payload.(map[string]any)["participants"].([]Position)); return nil }); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatal("queued snapshot leaked revoked position")
	}
	a.mu.Lock()
	a.state.Active = false
	a.mu.Unlock()
	_ = h.Sweep(context.Background())
	if err := h.Deliver(context.Background(), sb, snapshot, func(Event) error { t.Fatal("terminal trip delivered"); return nil }); !errors.Is(err, ErrStopped) {
		t.Fatal("terminal subscription survived")
	}
	if len(h.states) != 0 || len(h.positions.Users(testTrip)) != 0 {
		t.Fatal("terminal live state survived")
	}
}

type executorFunc func(context.Context, identity.User, trips.Command) (any, error)

func (f executorFunc) Execute(c context.Context, u identity.User, cmd trips.Command) (any, error) {
	return f(c, u, cmd)
}
func TestRevocationCommitWaitsForInFlightWrite(t *testing.T) {
	h, a := fixture(t, nil)
	sa := subscribe(t, h, userA)
	sb := subscribe(t, h, userB)
	next(t, sa)
	next(t, sb)
	if err := h.Publish(context.Background(), sa, update(time.Now())); err != nil {
		t.Fatal(err)
	}
	queued := next(t, sb)
	writing := make(chan struct{})
	release := make(chan struct{})
	delivered := make(chan error, 1)
	go func() {
		delivered <- h.Deliver(context.Background(), sb, queued, func(Event) error { close(writing); <-release; return nil })
	}()
	<-writing
	committed := make(chan struct{})
	finished := make(chan error, 1)
	c := Coordinator{Hub: h, Base: executorFunc(func(context.Context, identity.User, trips.Command) (any, error) {
		a.set(userA, false)
		close(committed)
		return map[string]bool{"sharing_enabled": false}, nil
	})}
	go func() {
		_, err := c.Execute(context.Background(), identity.User{ID: userA}, trips.Command{Action: "share", TripID: testTrip})
		finished <- err
	}()
	select {
	case <-committed:
		t.Fatal("revocation committed while coordinate write was in flight")
	case <-time.After(20 * time.Millisecond):
	}
	close(release)
	if err := <-delivered; err != nil {
		t.Fatal(err)
	}
	if err := <-finished; err != nil {
		t.Fatal(err)
	}
	wrote := false
	_ = h.Deliver(context.Background(), sb, queued, func(Event) error { wrote = true; return nil })
	if wrote {
		t.Fatal("post-commit coordinate delivery")
	}
}
func TestBoundsRateAndAuthorizationFailure(t *testing.T) {
	t.Run("queue", func(t *testing.T) {
		h, _ := fixture(t, func(l *Limits) { l.QueueSize = 1 })
		s := subscribe(t, h, userA)
		if err := h.Publish(context.Background(), s, update(time.Now())); err != nil {
			t.Fatal(err)
		}
		select {
		case reason := <-s.Stopped():
			if reason != "slow_consumer" {
				t.Fatal(reason)
			}
		case <-time.After(time.Second):
			t.Fatal("unbounded slow consumer")
		}
	})
	t.Run("rate", func(t *testing.T) {
		h, _ := fixture(t, func(l *Limits) { l.Burst = 2 })
		s := subscribe(t, h, userA)
		next(t, s)
		now := time.Now()
		for i := 0; i < 2; i++ {
			if err := h.Publish(context.Background(), s, update(now.Add(time.Duration(i)*time.Millisecond))); err != nil {
				t.Fatal(err)
			}
			next(t, s)
		}
		if !errors.Is(h.Publish(context.Background(), s, update(now.Add(2*time.Millisecond))), ErrRate) {
			t.Fatal("rate bound ignored")
		}
	})
	t.Run("capacity", func(t *testing.T) {
		h, _ := fixture(t, func(l *Limits) { l.MaxConnections = 1 })
		subscribe(t, h, userA)
		if _, err := h.Subscribe(context.Background(), testTrip, identity.Session{User: identity.User{ID: userB}, ExpiresAt: time.Now().Add(time.Minute)}); !errors.Is(err, ErrCapacity) {
			t.Fatal("connection bound ignored")
		}
	})
	t.Run("database failure", func(t *testing.T) {
		h, a := fixture(t, nil)
		s := subscribe(t, h, userA)
		next(t, s)
		_ = h.Publish(context.Background(), s, update(time.Now()))
		a.mu.Lock()
		a.err = errors.New("database unavailable")
		a.mu.Unlock()
		_ = h.Sweep(context.Background())
		if s.active || len(h.states) != 0 || len(h.positions.Users(testTrip)) != 0 {
			t.Fatal("authorization failure did not fail closed")
		}
	})
	t.Run("token expiry", func(t *testing.T) {
		h, _ := fixture(t, nil)
		s, err := h.Subscribe(context.Background(), testTrip, identity.Session{User: identity.User{ID: userA}, ExpiresAt: time.Now().Add(40 * time.Millisecond)})
		if err != nil {
			t.Fatal(err)
		}
		time.Sleep(50 * time.Millisecond)
		_ = h.Sweep(context.Background())
		if s.active {
			t.Fatal("expired token stayed active")
		}
	})
}
func TestLimitsRejectUnsafeConfiguration(t *testing.T) {
	for _, values := range []map[string]string{{"TRACK_QUEUE_SIZE": "0"}, {"TRACK_MESSAGE_BYTES": "999999999"}, {"TRACK_UPDATES_PER_SECOND": "invalid"}, {"TRACK_POSITION_TTL": "5s"}, {"TRACK_MAX_CONNECTIONS": "-1"}} {
		if _, err := LimitsFromEnv(func(k string) string { return values[k] }); err == nil {
			t.Fatal("unsafe limits accepted")
		}
	}
}

func TestAuthorizationGenerationsStayBoundedDuringMembershipChurn(t *testing.T) {
	h, a := fixture(t, nil)
	s := subscribe(t, h, userB)
	next(t, s)
	prior := userA
	for i := 0; i < 250; i++ {
		user := fmt.Sprintf("40000000-0000-4000-8000-%012d", i)
		a.mu.Lock()
		delete(a.state.Members, prior)
		a.state.Members[user] = Member{true}
		a.mu.Unlock()
		if err := h.Sweep(context.Background()); err != nil {
			t.Fatal(err)
		}
		for len(s.queue) > 0 {
			next(t, s)
		}
		if err := h.lock(context.Background()); err != nil {
			t.Fatal(err)
		}
		count := len(h.states[testTrip].generations)
		h.unlock()
		if count > 2 {
			t.Fatal("authorization tombstones grew beyond active membership")
		}
		prior = user
	}
}

func TestPerUserAndTripAdmissionBounds(t *testing.T) {
	t.Run("per user", func(t *testing.T) {
		h, _ := fixture(t, func(l *Limits) { l.MaxPerUser = 1 })
		subscribe(t, h, userA)
		_, err := h.Subscribe(context.Background(), testTrip, identity.Session{User: identity.User{ID: userA}, ExpiresAt: time.Now().Add(time.Minute)})
		if !errors.Is(err, ErrCapacity) {
			t.Fatal("per-user connection limit bypassed")
		}
	})
	t.Run("trips", func(t *testing.T) {
		h, _ := fixture(t, func(l *Limits) { l.MaxTrips = 1 })
		subscribe(t, h, userA)
		_, err := h.Subscribe(context.Background(), "55555555-5555-4555-8555-555555555555", identity.Session{User: identity.User{ID: userB}, ExpiresAt: time.Now().Add(time.Minute)})
		if !errors.Is(err, ErrCapacity) {
			t.Fatal("live trip limit bypassed")
		}
	})
	t.Run("closed", func(t *testing.T) {
		h, _ := fixture(t, nil)
		h.Close()
		_, err := h.Subscribe(context.Background(), testTrip, identity.Session{User: identity.User{ID: userA}, ExpiresAt: time.Now().Add(time.Minute)})
		if !errors.Is(err, ErrStopped) {
			t.Fatal("shutdown hub admitted a connection")
		}
	})
}
