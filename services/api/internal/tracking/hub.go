// Package tracking coordinates persisted authorization and ephemeral live state.
// The single-instance gate also spans actual position writes, so committed REST
// revocations cannot be followed by delivery of an older queued coordinate.
package tracking

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/trips"
)

type tripState struct {
	state          State
	sequence       uint64
	generations    map[string]uint64
	nextGeneration uint64
}
type Hub struct {
	auth        Authorizer
	positions   PositionStore
	broadcaster Broadcaster
	limits      Limits
	instance    string
	gate        chan struct{}
	states      map[string]*tripState
	now         func() time.Time
	cancel      context.CancelFunc
	done        chan struct{}
	closed      bool
}

func New(auth Authorizer, limits Limits) (*Hub, error) {
	return NewWithStores(auth, limits, NewMemoryPositions(limits.MaxPositions), NewMemoryBroadcaster())
}
func NewWithStores(auth Authorizer, limits Limits, positions PositionStore, broadcaster Broadcaster) (*Hub, error) {
	if err := limits.Validate(); err != nil {
		return nil, err
	}
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	id := hex.EncodeToString(b)
	id = id[:8] + "-" + id[8:12] + "-" + id[12:16] + "-" + id[16:20] + "-" + id[20:]
	ctx, cancel := context.WithCancel(context.Background())
	h := &Hub{auth: auth, positions: positions, broadcaster: broadcaster, limits: limits, instance: id, gate: make(chan struct{}, 1), states: map[string]*tripState{}, now: func() time.Time { return time.Now().UTC() }, cancel: cancel, done: make(chan struct{})}
	go h.run(ctx)
	return h, nil
}
func (h *Hub) Limits() Limits { return h.limits }
func (h *Hub) lock(ctx context.Context) error {
	select {
	case h.gate <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}
func (h *Hub) unlock() { <-h.gate }
func (h *Hub) Close()  { h.cancel(); <-h.done }
func (h *Hub) run(ctx context.Context) {
	defer close(h.done)
	ticker := time.NewTicker(h.limits.SweepInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			cleanup, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			if h.lock(cleanup) == nil {
				h.closed = true
				for trip := range h.states {
					h.dropTrip(trip, "server_shutdown")
				}
				h.unlock()
			}
			cancel()
			return
		case <-ticker.C:
			tick, cancel := context.WithTimeout(ctx, 5*time.Second)
			_ = h.Sweep(tick)
			cancel()
		}
	}
}
func (h *Hub) stop(s *Subscription, reason string) {
	if !s.active {
		return
	}
	s.active = false
	h.broadcaster.Remove(s)
	s.stopped <- reason
}
func (h *Hub) dropTrip(trip, reason string) {
	for _, s := range h.broadcaster.ForTrip(trip) {
		h.stop(s, reason)
	}
	h.positions.RemoveTrip(trip)
	delete(h.states, trip)
}
func (h *Hub) event(trip, kind string, payload any) Event {
	state := h.states[trip]
	state.sequence++
	return Event{Type: kind, Version: 1, InstanceID: h.instance, Sequence: state.sequence, Payload: payload}
}
func (h *Hub) broadcast(trip string, d Delivery) {
	for _, s := range h.broadcaster.Enqueue(trip, d) {
		h.stop(s, "slow_consumer")
	}
}

// reconcile is called under the gate after commits, before each publish/subscribe,
// and every sweep. Failure closes connections and deletes live state, fail-closed.
func (h *Hub) reconcile(ctx context.Context, trip string) error {
	state, err := h.auth.State(ctx, trip)
	if err != nil {
		h.dropTrip(trip, "authorization_unavailable")
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	live := h.states[trip]
	if live == nil {
		return nil
	}
	if !state.Active {
		h.dropTrip(trip, "trip_ended")
		return nil
	}
	revoked := map[string]bool{}
	for user := range live.state.Members {
		if live.state.authorize(user) == nil && state.authorize(user) != nil {
			delete(live.generations, user)
			revoked[user] = true
		}
	}
	var joined []string
	for user := range state.Members {
		if state.authorize(user) == nil && live.state.authorize(user) != nil {
			live.nextGeneration++
			live.generations[user] = live.nextGeneration
			joined = append(joined, user)
		}
	}
	live.state = state
	for _, sub := range h.broadcaster.ForTrip(trip) {
		if err := state.authorize(sub.UserID); err != nil {
			h.stop(sub, err.Error())
		}
	}
	for _, user := range h.positions.Users(trip) {
		if state.authorize(user) != nil {
			h.positions.Remove(trip, user)
			revoked[user] = true
		}
	}
	for user := range revoked {
		h.broadcast(trip, Delivery{Event: h.event(trip, "participant.left", map[string]string{"user_id": user, "reason": "access_revoked"})})
	}
	for _, user := range joined {
		h.broadcast(trip, Delivery{Event: h.event(trip, "participant.joined", map[string]string{"user_id": user})})
	}

	return nil
}
func (h *Hub) Subscribe(ctx context.Context, trip string, session identity.Session) (*Subscription, error) {
	if err := h.lock(ctx); err != nil {
		return nil, err
	}
	defer h.unlock()
	if h.closed || !h.now().Before(session.ExpiresAt) {
		return nil, ErrStopped
	}
	if err := h.reconcile(ctx, trip); err != nil {
		return nil, err
	}
	state, err := h.auth.State(ctx, trip)
	if err != nil {
		return nil, err
	}
	if err := state.authorize(session.User.ID); err != nil {
		return nil, err
	}
	if h.broadcaster.Count() >= h.limits.MaxConnections {
		return nil, ErrCapacity
	}
	count := 0
	for _, p := range h.broadcaster.ForTrip(trip) {
		if p.UserID == session.User.ID {
			count++
		}
	}
	if count >= h.limits.MaxPerUser {
		return nil, ErrCapacity
	}
	if h.states[trip] == nil {
		if len(h.states) >= h.limits.MaxTrips {
			return nil, ErrCapacity
		}
		live := &tripState{state: state, generations: map[string]uint64{}}
		for user := range state.Members {
			if state.authorize(user) == nil {
				live.nextGeneration++
				live.generations[user] = live.nextGeneration
			}
		}
		h.states[trip] = live
	}
	s := &Subscription{TripID: trip, UserID: session.User.ID, ExpiresAt: session.ExpiresAt, queue: make(chan Delivery, h.limits.QueueSize), stopped: make(chan string, 1), active: true, tokens: float64(h.limits.Burst), lastRefill: h.now()}
	h.broadcaster.Add(s)
	s.queue <- Delivery{Snapshot: true}
	return s, nil
}
func (h *Hub) Unsubscribe(s *Subscription) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if h.lock(ctx) != nil {
		return
	}
	defer h.unlock()
	h.stop(s, "disconnected")
}
func (h *Hub) Publish(ctx context.Context, s *Subscription, raw []byte) error {
	if err := h.lock(ctx); err != nil {
		return err
	}
	defer h.unlock()
	if !s.active || !h.now().Before(s.ExpiresAt) {
		h.stop(s, "expired_token")
		return ErrStopped
	}
	now := h.now()
	elapsed := now.Sub(s.lastRefill).Seconds()
	s.tokens += elapsed * float64(h.limits.UpdatesPerSecond)
	if s.tokens > float64(h.limits.Burst) {
		s.tokens = float64(h.limits.Burst)
	}
	s.lastRefill = now
	if s.tokens < 1 {
		h.stop(s, "rate_limited")
		return ErrRate
	}
	s.tokens--
	if int64(len(raw)) > h.limits.MessageBytes {
		h.stop(s, "message_too_large")
		return ErrInvalid
	}
	p, err := decodePosition(raw, s.UserID, now, h.limits)
	if err != nil {
		h.stop(s, "invalid_message")
		return err
	}
	if err := h.reconcile(ctx, s.TripID); err != nil {
		return err
	}
	if !s.active {
		return ErrStopped
	}
	if err := h.positions.Put(s.TripID, p); err != nil {
		if errors.Is(err, ErrOlder) {
			return err
		}
		h.stop(s, "tracking_capacity")
		return err
	}
	h.broadcast(s.TripID, Delivery{Event: h.event(s.TripID, "participant.position", p), Subject: s.UserID, ExpiresAt: p.ExpiresAt, Generation: h.states[s.TripID].generations[s.UserID]})
	return nil
}

// Deliver serializes the final socket write with all domain commands. Snapshot
// content is built at delivery, never frozen while a revocation is pending.
func (h *Hub) Deliver(ctx context.Context, s *Subscription, d Delivery, write func(Event) error) error {
	if err := h.lock(ctx); err != nil {
		return err
	}
	defer h.unlock()
	if !s.active || !h.now().Before(s.ExpiresAt) {
		h.stop(s, "expired_token")
		return ErrStopped
	}
	live := h.states[s.TripID]
	if live == nil || live.state.authorize(s.UserID) != nil {
		return ErrStopped
	}
	if d.Snapshot {
		positions := h.positions.Snapshot(s.TripID, h.now(), h.limits.StaleAfter)
		filtered := positions[:0]
		for _, p := range positions {
			if live.state.authorize(p.UserID) == nil {
				filtered = append(filtered, p)
			}
		}
		d.Event = Event{Type: "trip.snapshot", Version: 1, InstanceID: h.instance, Sequence: live.sequence, Payload: map[string]any{"participants": filtered, "server_time": h.now(), "stale_after_ms": h.limits.StaleAfter.Milliseconds(), "position_ttl_ms": h.limits.PositionTTL.Milliseconds()}}
	} else if d.Subject != "" && (live.state.authorize(d.Subject) != nil || d.Generation != live.generations[d.Subject] || !h.now().Before(d.ExpiresAt)) {
		return nil
	}
	return write(d.Event)
}
func (h *Hub) Sweep(ctx context.Context) error {
	if err := h.lock(ctx); err != nil {
		return err
	}
	defer h.unlock()
	for trip := range h.states {
		if err := h.reconcile(ctx, trip); err != nil {
			continue
		}
		if h.states[trip] == nil {
			continue
		}
		for _, s := range h.broadcaster.ForTrip(trip) {
			if !h.now().Before(s.ExpiresAt) {
				h.stop(s, "expired_token")
			}
		}
	}
	for _, key := range h.positions.Expire(h.now()) {
		if h.states[key.TripID] != nil {
			h.broadcast(key.TripID, Delivery{Event: h.event(key.TripID, "participant.left", map[string]string{"user_id": key.UserID, "reason": "expired"})})
		}
	}
	for trip := range h.states {
		if len(h.broadcaster.ForTrip(trip)) == 0 && !h.positions.HasPositions(trip) {
			h.positions.RemoveTrip(trip)
			delete(h.states, trip)
		}
	}
	return nil
}

type Executor interface {
	Execute(context.Context, identity.User, trips.Command) (any, error)
}

// Coordinator must wrap every REST trip operation in this single API process.
type Coordinator struct {
	Hub  *Hub
	Base Executor
}

func (c Coordinator) Execute(ctx context.Context, user identity.User, command trips.Command) (any, error) {
	h := c.Hub
	if err := h.lock(ctx); err != nil {
		return nil, err
	}
	defer h.unlock()
	if h.closed {
		return nil, ErrStopped
	}
	result, err := c.Base.Execute(ctx, user, command)
	trip := command.TripID
	if t, ok := result.(trips.Trip); ok {
		trip = t.ID
	}
	if err != nil {
		var domain trips.Error
		if !errors.As(err, &domain) && trip != "" {
			h.dropTrip(trip, "authorization_unavailable")
		}
		return nil, err
	}
	if trip != "" && h.states[trip] != nil {
		_ = h.reconcile(ctx, trip)
	}
	return result, nil
}

// ConnectionCount is an aggregate operational check; it never reveals trip/user data.
func (h *Hub) ConnectionCount(ctx context.Context) (int, error) {
	if err := h.lock(ctx); err != nil {
		return 0, err
	}
	defer h.unlock()
	return h.broadcaster.Count(), nil
}
