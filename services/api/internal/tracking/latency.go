package tracking

import (
	"sort"
	"sync"
	"time"
)

const latencyWindow = 1024

// LatencySummary describes successful cross-user position writes. Durations use
// the server's monotonic clock, from Publish entry (after a complete WebSocket
// frame is read) through successful socket write return. It excludes self echoes,
// snapshots, rejected/expired/revoked deliveries and failed writes. It is not
// client receipt/render latency. No trip, user, coordinate or timestamp is kept.
type LatencySummary struct {
	SuccessfulWrites uint64
	FailedWrites     uint64
	Samples          int
	P50, P95, Max    time.Duration
}

type latencyStats struct {
	mu               sync.Mutex
	values           [latencyWindow]time.Duration
	next, size       int
	successes, fails uint64
}

func (s *latencyStats) observe(elapsed time.Duration, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err != nil {
		s.fails++
		return
	}
	if elapsed < 0 {
		return
	}
	s.successes++
	s.values[s.next] = elapsed
	s.next = (s.next + 1) % latencyWindow
	if s.size < latencyWindow {
		s.size++
	}
}

func (s *latencyStats) summary() LatencySummary {
	s.mu.Lock()
	values := append([]time.Duration(nil), s.values[:s.size]...)
	result := LatencySummary{SuccessfulWrites: s.successes, FailedWrites: s.fails, Samples: s.size}
	s.mu.Unlock()
	if len(values) == 0 {
		return result
	}
	sort.Slice(values, func(i, j int) bool { return values[i] < values[j] })
	// Nearest-rank percentiles; the bounded window contains the latest writes.
	result.P50 = values[(50*len(values)+99)/100-1]
	result.P95 = values[(95*len(values)+99)/100-1]
	result.Max = values[len(values)-1]
	return result
}

// PositionLatency returns only bounded process-local aggregate timing. No public
// HTTP endpoint or per-user metric labels are introduced.
func (h *Hub) PositionLatency() LatencySummary { return h.latency.summary() }
