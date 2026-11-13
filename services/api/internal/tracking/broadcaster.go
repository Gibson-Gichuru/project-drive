package tracking

import "time"

type Event struct {
	Type       string `json:"type"`
	Version    int    `json:"version"`
	InstanceID string `json:"server_instance_id"`
	Sequence   uint64 `json:"sequence"`
	Payload    any    `json:"payload"`
}

// Subject and expiry are authorization metadata, never client-provided routing keys.
type Delivery struct {
	Event      Event
	Subject    string
	ExpiresAt  time.Time
	Snapshot   bool
	Generation uint64
}
type Subscription struct {
	TripID, UserID string
	ExpiresAt      time.Time
	queue          chan Delivery
	stopped        chan string
	active         bool
	tokens         float64
	lastRefill     time.Time
}

func (s *Subscription) Events() <-chan Delivery { return s.queue }
func (s *Subscription) Stopped() <-chan string  { return s.stopped }

// Broadcaster is replaceable, but adapters must preserve gate ordering and bounds.
// Redis/distributed invalidation is intentionally not implemented in Phase 0.
type Broadcaster interface {
	Add(*Subscription)
	Remove(*Subscription)
	ForTrip(string) []*Subscription
	Count() int
	Enqueue(string, Delivery) []*Subscription
}
type MemoryBroadcaster struct{ subscribers map[*Subscription]struct{} }

func NewMemoryBroadcaster() *MemoryBroadcaster {
	return &MemoryBroadcaster{subscribers: map[*Subscription]struct{}{}}
}
func (b *MemoryBroadcaster) Add(s *Subscription)    { b.subscribers[s] = struct{}{} }
func (b *MemoryBroadcaster) Remove(s *Subscription) { delete(b.subscribers, s) }
func (b *MemoryBroadcaster) Count() int             { return len(b.subscribers) }
func (b *MemoryBroadcaster) ForTrip(trip string) []*Subscription {
	out := []*Subscription{}
	for s := range b.subscribers {
		if s.TripID == trip {
			out = append(out, s)
		}
	}
	return out
}
func (b *MemoryBroadcaster) Enqueue(trip string, d Delivery) []*Subscription {
	slow := []*Subscription{}
	for _, s := range b.ForTrip(trip) {
		select {
		case s.queue <- d:
		default:
			slow = append(slow, s)
		}
	}
	return slow
}
