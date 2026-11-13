package tracking

import (
	"bytes"
	"encoding/json"
	"io"
	"math"
	"sort"
	"time"
)

// Position is ephemeral. No repository stores these coordinates durably.
type Position struct {
	UserID     string    `json:"user_id"`
	Latitude   float64   `json:"latitude"`
	Longitude  float64   `json:"longitude"`
	Accuracy   float64   `json:"accuracy_meters"`
	Speed      *float64  `json:"speed_mps,omitempty"`
	Heading    *float64  `json:"heading_degrees,omitempty"`
	RecordedAt time.Time `json:"recorded_at"`
	ReceivedAt time.Time `json:"received_at"`
	ExpiresAt  time.Time `json:"expires_at"`
	Stale      bool      `json:"stale"`
}
type incoming struct {
	Type    string `json:"type"`
	Version *int   `json:"version"`
	Payload struct {
		Latitude   *float64   `json:"latitude"`
		Longitude  *float64   `json:"longitude"`
		Accuracy   *float64   `json:"accuracy_meters"`
		Speed      *float64   `json:"speed_mps"`
		Heading    *float64   `json:"heading_degrees"`
		RecordedAt *time.Time `json:"recorded_at"`
	} `json:"payload"`
}

func decodePosition(raw []byte, user string, now time.Time, l Limits) (Position, error) {
	var in incoming
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&in); err != nil {
		return Position{}, ErrInvalid
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return Position{}, ErrInvalid
	}
	p := in.Payload
	if in.Type != "position.update" || in.Version == nil || *in.Version != 1 || p.Latitude == nil || p.Longitude == nil || p.Accuracy == nil || p.RecordedAt == nil {
		return Position{}, ErrInvalid
	}
	finite := func(v float64) bool { return !math.IsNaN(v) && !math.IsInf(v, 0) }
	if !finite(*p.Latitude) || !finite(*p.Longitude) || !finite(*p.Accuracy) || *p.Latitude < -90 || *p.Latitude > 90 || *p.Longitude < -180 || *p.Longitude > 180 || *p.Accuracy <= 0 || *p.Accuracy > 1000 {
		return Position{}, ErrInvalid
	}
	if p.Speed != nil && (!finite(*p.Speed) || *p.Speed < 0 || *p.Speed > 100) {
		return Position{}, ErrInvalid
	}
	if p.Heading != nil && (!finite(*p.Heading) || *p.Heading < 0 || *p.Heading > 360) {
		return Position{}, ErrInvalid
	}
	recorded := p.RecordedAt.UTC()
	age := now.Sub(recorded)
	if age > l.MaxAge || age < -l.FutureSkew {
		return Position{}, ErrInvalid
	}
	effective := recorded
	if effective.After(now) {
		effective = now
	}
	return Position{UserID: user, Latitude: *p.Latitude, Longitude: *p.Longitude, Accuracy: *p.Accuracy, Speed: p.Speed, Heading: p.Heading, RecordedAt: recorded, ReceivedAt: now.UTC(), ExpiresAt: effective.Add(l.PositionTTL), Stale: now.Sub(effective) >= l.StaleAfter}, nil
}

type PositionKey struct{ TripID, UserID string }
type PositionStore interface {
	Put(string, Position) error
	Snapshot(string, time.Time, time.Duration) []Position
	Remove(string, string)
	RemoveTrip(string)
	Expire(time.Time) []PositionKey
	HasPositions(string) bool
	Users(string) []string
}
type positionEntry struct {
	position  *Position
	watermark time.Time
}
type MemoryPositions struct {
	entries map[PositionKey]positionEntry
	limit   int
}

func NewMemoryPositions(limit int) *MemoryPositions {
	return &MemoryPositions{entries: map[PositionKey]positionEntry{}, limit: limit}
}

// All methods are serialized by the owning Hub's gate.
func (m *MemoryPositions) Put(trip string, p Position) error {
	key := PositionKey{trip, p.UserID}
	entry, exists := m.entries[key]
	if exists && !p.RecordedAt.After(entry.watermark) {
		return ErrOlder
	}
	if !exists && len(m.entries) >= m.limit {
		return ErrCapacity
	}
	m.entries[key] = positionEntry{&p, p.RecordedAt}
	return nil
}
func (m *MemoryPositions) Snapshot(trip string, now time.Time, stale time.Duration) []Position {
	positions := []Position{}
	for key, e := range m.entries {
		if key.TripID != trip || e.position == nil || !now.Before(e.position.ExpiresAt) {
			continue
		}
		p := *e.position
		effective := p.RecordedAt
		if effective.After(p.ReceivedAt) {
			effective = p.ReceivedAt
		}
		p.Stale = now.Sub(effective) >= stale
		positions = append(positions, p)
	}
	sort.Slice(positions, func(i, j int) bool { return positions[i].UserID < positions[j].UserID })
	return positions
}
func (m *MemoryPositions) Remove(trip, user string) { delete(m.entries, PositionKey{trip, user}) }
func (m *MemoryPositions) RemoveTrip(trip string) {
	for key := range m.entries {
		if key.TripID == trip {
			delete(m.entries, key)
		}
	}
}
func (m *MemoryPositions) Expire(now time.Time) []PositionKey {
	expired := []PositionKey{}
	for key, e := range m.entries {
		if e.position != nil && !now.Before(e.position.ExpiresAt) {
			e.position = nil
			m.entries[key] = e
			expired = append(expired, key)
		}
	}
	return expired
}
func (m *MemoryPositions) HasPositions(trip string) bool {
	for key, e := range m.entries {
		if key.TripID == trip && e.position != nil {
			return true
		}
	}
	return false
}

func (m *MemoryPositions) Users(trip string) []string {
	users := []string{}
	for key := range m.entries {
		if key.TripID == trip {
			users = append(users, key.UserID)
		}
	}
	return users
}
