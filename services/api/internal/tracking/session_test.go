package tracking

import (
	"context"
	"errors"
	"projectdrive/services/api/internal/identity"
	"sync/atomic"
	"testing"
	"time"
)

func TestProviderRevocationDropsQueuedCoordinatesAndSnapshots(t *testing.T) {
	h, _ := fixture(t, nil)
	var revoked atomic.Bool
	s, e := h.Subscribe(context.Background(), testTrip, identity.Session{User: identity.User{ID: userA, Name: "Synthetic"}, ExpiresAt: time.Now().Add(time.Minute), SessionID: testTrip, Check: func(context.Context) error {
		if revoked.Load() {
			return errors.New("revoked")
		}
		return nil
	}})
	if e != nil {
		t.Fatal(e)
	}
	next(t, s)
	peer := subscribe(t, h, userB)
	next(t, peer)
	if e := h.Publish(context.Background(), s, update(time.Now())); e != nil {
		t.Fatal(e)
	}
	pending := next(t, peer)
	revoked.Store(true)
	if e := h.Sweep(context.Background()); e != nil {
		t.Fatal(e)
	}
	called := false
	if e := h.Deliver(context.Background(), peer, pending, func(Event) error { called = true; return nil }); e != nil {
		t.Fatal(e)
	}
	if called {
		t.Fatal("queued revoked coordinate delivered")
	}
	select {
	case <-s.Stopped():
	default:
		t.Fatal("revoked subscriber remained connected")
	}
	if h.positions.HasPositions(testTrip) {
		t.Fatal("revoked position retained")
	}
	if e := h.Publish(context.Background(), s, update(time.Now())); !errors.Is(e, ErrStopped) {
		t.Fatal("revoked session published")
	}
}

func TestLogoutAcknowledgementInvalidatesQueuedCoordinates(t *testing.T) {
	h, _ := fixture(t, nil)
	s, e := h.Subscribe(context.Background(), testTrip, identity.Session{User: identity.User{ID: userA, Name: "Synthetic"}, ExpiresAt: time.Now().Add(time.Minute), SessionID: testTrip})
	if e != nil {
		t.Fatal(e)
	}
	next(t, s)
	peer := subscribe(t, h, userB)
	next(t, peer)
	if e := h.Publish(context.Background(), s, update(time.Now())); e != nil {
		t.Fatal(e)
	}
	queued := next(t, peer)
	if e := h.RevokeSession(context.Background(), testTrip); e != nil {
		t.Fatal(e)
	}
	delivered := false
	_ = h.Deliver(context.Background(), peer, queued, func(Event) error { delivered = true; return nil })
	if delivered {
		t.Fatal("coordinate delivered after logout acknowledgement")
	}
}
