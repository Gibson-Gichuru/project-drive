package tracking

import (
	"fmt"
	"strconv"
	"time"
)

// Limits are per API instance. Multi-instance delivery is intentionally unsupported.
type Limits struct {
	MaxConnections, MaxPerUser, MaxTrips, QueueSize, MaxPositions int
	MessageBytes                                                  int64
	UpdatesPerSecond, Burst                                       int
	PositionTTL, StaleAfter, MaxAge, FutureSkew, SweepInterval    time.Duration
	WriteTimeout, PingInterval, PongTimeout                       time.Duration
}

func DefaultLimits() Limits {
	return Limits{MaxConnections: 64, MaxPerUser: 2, MaxTrips: 16, QueueSize: 16, MaxPositions: 1024, MessageBytes: 4096, UpdatesPerSecond: 2, Burst: 5, PositionTTL: 30 * time.Second, StaleAfter: 10 * time.Second, MaxAge: 15 * time.Second, FutureSkew: 5 * time.Second, SweepInterval: time.Second, WriteTimeout: time.Second, PingInterval: 15 * time.Second, PongTimeout: 5 * time.Second}
}
func LimitsFromEnv(get func(string) string) (Limits, error) {
	l := DefaultLimits()
	for name, target := range map[string]*int{"TRACK_MAX_CONNECTIONS": &l.MaxConnections, "TRACK_MAX_PER_USER": &l.MaxPerUser, "TRACK_MAX_TRIPS": &l.MaxTrips, "TRACK_QUEUE_SIZE": &l.QueueSize, "TRACK_MAX_POSITIONS": &l.MaxPositions, "TRACK_UPDATES_PER_SECOND": &l.UpdatesPerSecond, "TRACK_BURST": &l.Burst} {
		if raw := get(name); raw != "" {
			n, err := strconv.Atoi(raw)
			if err != nil {
				return l, fmt.Errorf("invalid %s", name)
			}
			*target = n
		}
	}
	if raw := get("TRACK_MESSAGE_BYTES"); raw != "" {
		n, err := strconv.ParseInt(raw, 10, 64)
		if err != nil {
			return l, fmt.Errorf("invalid TRACK_MESSAGE_BYTES")
		}
		l.MessageBytes = n
	}
	for name, target := range map[string]*time.Duration{"TRACK_POSITION_TTL": &l.PositionTTL, "TRACK_STALE_AFTER": &l.StaleAfter, "TRACK_MAX_AGE": &l.MaxAge, "TRACK_FUTURE_SKEW": &l.FutureSkew, "TRACK_SWEEP_INTERVAL": &l.SweepInterval, "TRACK_WRITE_TIMEOUT": &l.WriteTimeout, "TRACK_PING_INTERVAL": &l.PingInterval, "TRACK_PONG_TIMEOUT": &l.PongTimeout} {
		if raw := get(name); raw != "" {
			n, err := time.ParseDuration(raw)
			if err != nil {
				return l, fmt.Errorf("invalid %s", name)
			}
			*target = n
		}
	}
	return l, l.Validate()
}
func (l Limits) Validate() error {
	if l.MaxConnections < 1 || l.MaxConnections > 1024 || l.MaxPerUser < 1 || l.MaxPerUser > 4 || l.MaxTrips < 1 || l.MaxTrips > 128 || l.QueueSize < 1 || l.QueueSize > 64 || l.MaxPositions < 1 || l.MaxPositions > 12800 || l.MessageBytes < 256 || l.MessageBytes > 16384 || l.UpdatesPerSecond < 1 || l.UpdatesPerSecond > 10 || l.Burst < 1 || l.Burst > 20 {
		return fmt.Errorf("tracking count/size limits out of bounds")
	}
	if l.StaleAfter <= 0 || l.MaxAge <= 0 || l.PositionTTL <= l.StaleAfter || l.PositionTTL <= l.MaxAge || l.PositionTTL > time.Minute || l.FutureSkew < 0 || l.FutureSkew > 10*time.Second || l.SweepInterval <= 0 || l.SweepInterval > time.Second || l.WriteTimeout <= 0 || l.WriteTimeout > 2*time.Second || l.PingInterval <= 0 || l.PingInterval > 30*time.Second || l.PongTimeout <= 0 || l.PongTimeout > 10*time.Second {
		return fmt.Errorf("tracking duration limits out of bounds")
	}
	return nil
}
