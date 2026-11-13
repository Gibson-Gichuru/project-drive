// Package telemetry stores bounded, client-derived estimates, never raw tracks.
package telemetry

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"io"
	"math"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"projectdrive/services/api/internal/identity"
)

const MaxEvents = 100
const MaxRecordings = 20

type Error string

func (e Error) Error() string { return string(e) }

type Summary struct {
	MeanAccuracy    float64   `json:"mean_accuracy_meters"`
	RejectedGPS     int       `json:"rejected_gps_samples"`
	StartedAt       time.Time `json:"started_at"`
	EndedAt         time.Time `json:"ended_at"`
	Duration        float64   `json:"duration_s"`
	Covered         float64   `json:"covered_s"`
	Distance        float64   `json:"distance_m"`
	MeanSpeed       float64   `json:"mean_speed_mps"`
	MaxSpeed        float64   `json:"max_speed_mps"`
	SpeedSamples    int       `json:"speed_samples"`
	GPSSamples      int       `json:"gps_samples"`
	IMUSamples      int       `json:"imu_samples"`
	RotationSamples int       `json:"rotation_samples"`
	Acceleration    int       `json:"acceleration_candidates"`
	Braking         int       `json:"braking_candidates"`
	Lateral         int       `json:"lateral_candidates"`
	Dropped         int       `json:"dropped_events"`
	Interrupted     bool      `json:"interrupted"`
	Source          string    `json:"source"`
	Orientation     string    `json:"orientation"`
	Calibration     string    `json:"calibration"`
	Confidence      float64   `json:"confidence"`
}
type Event struct {
	Type        string    `json:"type"`
	Magnitude   float64   `json:"magnitude"`
	Confidence  float64   `json:"confidence"`
	Source      string    `json:"source"`
	Accuracy    float64   `json:"accuracy_meters"`
	Elapsed     int64     `json:"elapsed_ms"`
	OccurredAt  time.Time `json:"occurred_at"`
	Orientation string    `json:"orientation"`
	Calibration string    `json:"calibration"`
}
type Batch struct {
	Version int     `json:"version"`
	ID      string  `json:"recording_id"`
	Summary Summary `json:"summary"`
	Events  []Event `json:"events"`
}

func bounded(v, lo, hi float64) bool {
	return !math.IsNaN(v) && !math.IsInf(v, 0) && v >= lo && v <= hi
}
func fields(data []byte, required []string) bool {
	var v map[string]json.RawMessage
	if json.Unmarshal(data, &v) != nil || v == nil {
		return false
	}
	for _, key := range required {
		if value, ok := v[key]; !ok || bytes.Equal(value, []byte("null")) {
			return false
		}
	}
	return true
}
func Decode(data []byte, now time.Time) (Batch, error) {
	var b Batch
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if d.Decode(&b) != nil || d.Decode(new(any)) != io.EOF {
		return b, Error("invalid_request")
	}
	var raw map[string]json.RawMessage
	_ = json.Unmarshal(data, &raw)
	if !fields(data, []string{"version", "recording_id", "summary", "events"}) || !fields(raw["summary"], []string{"started_at", "ended_at", "duration_s", "covered_s", "distance_m", "mean_speed_mps", "max_speed_mps", "speed_samples", "gps_samples", "rejected_gps_samples", "mean_accuracy_meters", "imu_samples", "rotation_samples", "acceleration_candidates", "braking_candidates", "lateral_candidates", "dropped_events", "interrupted", "source", "orientation", "calibration", "confidence"}) {
		return b, Error("invalid_request")
	}
	var events []json.RawMessage
	_ = json.Unmarshal(raw["events"], &events)
	for _, event := range events {
		if !fields(event, []string{"type", "magnitude", "confidence", "source", "accuracy_meters", "elapsed_ms", "occurred_at", "orientation", "calibration"}) {
			return b, Error("invalid_request")
		}
	}
	return b, b.Validate(now)
}
func (b Batch) Validate(now time.Time) error {
	bad := Error("invalid_request")
	s := b.Summary
	if b.Version != 1 || !identity.UUID(b.ID) || b.Events == nil || len(b.Events) > MaxEvents || s.StartedAt.IsZero() || s.EndedAt.Before(s.StartedAt) || s.EndedAt.After(now.Add(5*time.Minute)) || s.EndedAt.Before(now.Add(-24*time.Hour)) || !bounded(s.Duration, 0, 14400) || math.Abs(s.EndedAt.Sub(s.StartedAt).Seconds()-s.Duration) > 5 || !bounded(s.Covered, 0, s.Duration+0.001) || !bounded(s.Distance, 0, 100*s.Covered+1) || !bounded(s.MeanSpeed, 0, 100) || !bounded(s.MaxSpeed, s.MeanSpeed, 100) || !bounded(s.MeanAccuracy, 0, 50) || !bounded(s.Confidence, 0, 0.6) || s.Source != "phone_gps_imu_v1" || s.Calibration != "uncalibrated" || (s.Orientation != "available" && s.Orientation != "unavailable") {
		return bad
	}
	for _, n := range []int{s.SpeedSamples, s.GPSSamples, s.RejectedGPS, s.IMUSamples, s.RotationSamples, s.Acceleration, s.Braking, s.Lateral, s.Dropped} {
		if n < 0 || n > 2_000_000 {
			return bad
		}
	}
	if s.SpeedSamples > s.GPSSamples || (s.SpeedSamples == 0 && (s.MeanSpeed != 0 || s.MaxSpeed != 0)) || s.Acceleration+s.Braking+s.Lateral != len(b.Events)+s.Dropped {
		return bad
	}
	counts := map[string]int{}
	var previous int64 = -1
	for _, e := range b.Events {
		if (e.Type != "acceleration_candidate" && e.Type != "braking_candidate" && e.Type != "lateral_candidate") || !bounded(e.Magnitude, 0, 30) || !bounded(e.Confidence, 0, 0.6) || !bounded(e.Accuracy, 0.001, 50) || e.Source != "gps_delta" || e.Calibration != "uncalibrated" || (e.Orientation != "available" && e.Orientation != "unavailable") || e.Elapsed < previous || e.Elapsed < 0 || float64(e.Elapsed) > s.Duration*1000+1 || math.Abs(e.OccurredAt.Sub(s.StartedAt).Seconds()-float64(e.Elapsed)/1000) > 5 {
			return bad
		}
		previous = e.Elapsed
		counts[e.Type]++
	}
	if counts["acceleration_candidate"] > s.Acceleration || counts["braking_candidate"] > s.Braking || counts["lateral_candidate"] > s.Lateral {
		return bad
	}
	return nil
}

type Store struct{ DB *pgxpool.Pool }

func authorized(ctx context.Context, tx pgx.Tx, user, trip string) (string, error) {
	var status string
	err := tx.QueryRow(ctx, `SELECT status FROM trips WHERE id=$1 FOR UPDATE`, trip).Scan(&status)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", Error("not_found")
	}
	if err != nil {
		return "", err
	}
	var member bool
	err = tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM trip_members m JOIN users u ON u.id=m.user_id WHERE m.trip_id=$1 AND m.user_id=$2 AND m.left_at IS NULL AND m.removed_at IS NULL AND u.deleted_at IS NULL)`, trip, user).Scan(&member)
	if err != nil {
		return "", err
	}
	if !member {
		return "", Error("not_found")
	}
	return status, nil
}
func (s Store) Upload(ctx context.Context, user, trip string, b Batch) error {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(context.Background())
	status, err := authorized(ctx, tx, user, trip)
	if err != nil {
		return err
	}
	if status != "active" && status != "completed" {
		return Error("invalid_transition")
	}
	canonical, err := json.Marshal(b)
	if err != nil {
		return err
	}
	hash := sha256.Sum256(canonical)
	// Expired records are physically removed, including their derived events.
	if _, err = tx.Exec(ctx, `DELETE FROM telemetry_recordings WHERE trip_id=$1 AND expires_at<=now()`, trip); err != nil {
		return err
	}
	var prior []byte
	var owner, priorTrip string
	err = tx.QueryRow(ctx, `SELECT request_hash,user_id::text,trip_id::text FROM telemetry_recordings WHERE id=$1`, b.ID).Scan(&prior, &owner, &priorTrip)
	if err == nil {
		if owner != user || priorTrip != trip || !bytes.Equal(prior, hash[:]) {
			return Error("idempotency_conflict")
		}
		return tx.Commit(ctx)
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	var count int
	if err = tx.QueryRow(ctx, `SELECT count(*) FROM telemetry_recordings WHERE trip_id=$1 AND user_id=$2`, trip, user).Scan(&count); err != nil {
		return err
	}
	if count >= MaxRecordings {
		return Error("recording_limit")
	}
	summary, _ := json.Marshal(b.Summary)
	tag, err := tx.Exec(ctx, `INSERT INTO telemetry_recordings(id,trip_id,user_id,request_hash,summary_json) VALUES($1,$2,$3,$4,$5) ON CONFLICT(id) DO NOTHING`, b.ID, trip, user, hash[:], summary)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return Error("idempotency_conflict")
	}
	for _, e := range b.Events {
		metadata, _ := json.Marshal(e)
		if _, err = tx.Exec(ctx, `INSERT INTO telemetry_events(trip_id,user_id,recording_id,type,magnitude,confidence,source,occurred_at,metadata_json) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`, trip, user, b.ID, e.Type, e.Magnitude, e.Confidence, e.Source, e.OccurredAt, metadata); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

type Recording struct {
	ID        string          `json:"recording_id"`
	Summary   json.RawMessage `json:"summary"`
	ExpiresAt time.Time       `json:"expires_at"`
}

func (s Store) Summary(ctx context.Context, user, trip string) ([]Recording, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(context.Background())
	if _, err = authorized(ctx, tx, user, trip); err != nil {
		return nil, err
	}
	rows, err := tx.Query(ctx, `SELECT id::text,summary_json,expires_at FROM telemetry_recordings WHERE trip_id=$1 AND user_id=$2 AND expires_at>now() ORDER BY created_at,id LIMIT 20`, trip, user)
	if err != nil {
		return nil, err
	}
	result := []Recording{}
	for rows.Next() {
		var r Recording
		if err = rows.Scan(&r.ID, &r.Summary, &r.ExpiresAt); err != nil {
			rows.Close()
			return nil, err
		}
		result = append(result, r)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	return result, tx.Commit(ctx)
}
func (s Store) Purge(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM telemetry_recordings WHERE expires_at<=now()`)
	return err
}
