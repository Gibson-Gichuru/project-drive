package tracking

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestLatencyWindowAndFailures(t *testing.T) {
	var stats latencyStats
	if stats.summary().Samples != 0 {
		t.Fatal("empty timing window contains samples")
	}
	for i := 1; i <= latencyWindow+10; i++ {
		stats.observe(time.Duration(i)*time.Millisecond, nil)
	}
	stats.observe(time.Second, errors.New("write failed"))
	stats.observe(-time.Second, nil)
	s := stats.summary()
	if s.SuccessfulWrites != latencyWindow+10 || s.FailedWrites != 1 || s.Samples != latencyWindow || s.P50 != 522*time.Millisecond || s.P95 != 983*time.Millisecond || s.Max != 1034*time.Millisecond {
		t.Fatalf("incorrect bounded timing summary: %+v", s)
	}
}

func TestLatencyOnlyMeasuresSuccessfulAuthorizedPeerWrites(t *testing.T) {
	h, auth := fixture(t, nil)
	a, b := subscribe(t, h, userA), subscribe(t, h, userB)
	ctx := context.Background()
	write := func(Event) error { return nil }
	if err := h.Deliver(ctx, b, next(t, b), write); err != nil {
		t.Fatal(err)
	}
	next(t, a) // Snapshot is not a position sample.
	if err := h.Publish(ctx, a, update(time.Now())); err != nil {
		t.Fatal(err)
	}
	if err := h.Deliver(ctx, a, next(t, a), write); err != nil {
		t.Fatal(err)
	}
	if h.PositionLatency().Samples != 0 {
		t.Fatal("self echo/snapshot counted")
	}
	if err := h.Deliver(ctx, b, next(t, b), write); err != nil {
		t.Fatal(err)
	}
	if s := h.PositionLatency(); s.Samples != 1 || s.P95 <= 0 {
		t.Fatal("peer write not measured")
	}
	if err := h.Publish(ctx, a, update(time.Now())); err != nil {
		t.Fatal(err)
	}
	next(t, a)
	failure := errors.New("socket failure")
	if err := h.Deliver(ctx, b, next(t, b), func(Event) error { return failure }); !errors.Is(err, failure) {
		t.Fatal("write error changed")
	}
	if s := h.PositionLatency(); s.Samples != 1 || s.FailedWrites != 1 {
		t.Fatal("failed write counted as success")
	}
	if err := h.Publish(ctx, a, update(time.Now())); err != nil {
		t.Fatal(err)
	}
	next(t, a)
	queued := next(t, b)
	auth.set(userA, false)
	if err := h.Sweep(ctx); err != nil {
		t.Fatal(err)
	}
	_ = h.Deliver(ctx, b, queued, func(Event) error { t.Fatal("revoked coordinate delivered"); return nil })
	if s := h.PositionLatency(); s.Samples != 1 || s.FailedWrites != 1 {
		t.Fatal("revocation changed latency measurements")
	}
}
