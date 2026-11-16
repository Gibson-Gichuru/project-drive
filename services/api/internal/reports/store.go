// Package reports owns authenticated community observations, not verified facts.
package reports

import (
	"context"
	"encoding/json"
	"errors"
	"math"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"projectdrive/services/api/internal/identity"
)

var (
	ErrInvalid   = errors.New("invalid report request")
	ErrMissing   = errors.New("report unavailable")
	ErrForbidden = errors.New("report operation forbidden")
	ErrConflict  = errors.New("idempotency key reused")
	ErrLimit     = errors.New("report rate limit")
)

type Point struct {
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}

func (p Point) Valid() bool {
	return !math.IsNaN(p.Latitude) && !math.IsNaN(p.Longitude) && !math.IsInf(p.Latitude, 0) && !math.IsInf(p.Longitude, 0) && p.Latitude >= -90 && p.Latitude <= 90 && p.Longitude >= -180 && p.Longitude <= 180
}
func (p *Point) UnmarshalJSON(data []byte) error {
	var v struct {
		Latitude  *float64 `json:"latitude"`
		Longitude *float64 `json:"longitude"`
	}
	d := json.NewDecoder(strings.NewReader(string(data)))
	d.DisallowUnknownFields()
	if d.Decode(&v) != nil || v.Latitude == nil || v.Longitude == nil {
		return ErrInvalid
	}
	*p = Point{*v.Latitude, *v.Longitude}
	if !p.Valid() {
		return ErrInvalid
	}
	return nil
}

type Input struct {
	Category   string    `json:"category"`
	Location   *Point    `json:"location"`
	Note       string    `json:"note"`
	ObservedAt time.Time `json:"observed_at"`
}
type Query struct {
	Center *Point  `json:"center"`
	Radius float64 `json:"radius_m"`
}
type Report struct {
	ID            string    `json:"id"`
	Category      string    `json:"category"`
	Location      Point     `json:"location"`
	Note          string    `json:"note"`
	ObservedAt    time.Time `json:"observed_at"`
	CreatedAt     time.Time `json:"created_at"`
	ExpiresAt     time.Time `json:"expires_at"`
	Status        string    `json:"status"`
	Mine          bool      `json:"mine"`
	Confirmations int       `json:"confirmations"`
	Dismissals    int       `json:"dismissals"`
	MyVote        int       `json:"my_vote"`
	Confidence    string    `json:"confidence"`
}

func Lifetime(category string) time.Duration {
	switch category {
	case "accident", "obstacle":
		return 2 * time.Hour
	case "breakdown":
		return time.Hour
	case "closure":
		return 6 * time.Hour
	case "camera":
		return 7 * 24 * time.Hour
	case "speed_bump":
		return 30 * 24 * time.Hour
	}
	return 0
}
func (in Input) Valid(now time.Time) bool {
	ttl := Lifetime(in.Category)
	return ttl > 0 && in.Location != nil && in.Location.Valid() && utf8.ValidString(in.Note) && utf8.RuneCountInString(in.Note) <= 240 && strings.IndexFunc(in.Note, unicode.IsControl) < 0 && !in.ObservedAt.IsZero() && !in.ObservedAt.After(now.Add(30*time.Second)) && !in.ObservedAt.Before(now.Add(-24*time.Hour)) && in.ObservedAt.Add(ttl).After(now)
}

type Store struct{ DB *pgxpool.Pool }

const columns = `r.id::text,r.category,ST_Y(r.location::geometry),ST_X(r.location::geometry),r.note,r.observed_at,r.created_at,r.expires_at,
 CASE WHEN r.status='active' AND r.expires_at<=now() THEN 'expired' ELSE r.status END,r.reporter_id=$1::uuid,
 (SELECT count(*) FROM report_votes v WHERE v.report_id=r.id AND v.vote=1),
 (SELECT count(*) FROM report_votes v WHERE v.report_id=r.id AND v.vote=-1),
 COALESCE((SELECT vote FROM report_votes v WHERE v.report_id=r.id AND v.user_id=$1::uuid),0)`

func scan(row pgx.Row) (Report, error) {
	var r Report
	err := row.Scan(&r.ID, &r.Category, &r.Location.Latitude, &r.Location.Longitude, &r.Note, &r.ObservedAt, &r.CreatedAt, &r.ExpiresAt, &r.Status, &r.Mine, &r.Confirmations, &r.Dismissals, &r.MyVote)
	r.Confidence = "unverified"
	if r.Confirmations >= 2 {
		r.Confidence = "supported"
	}
	if r.Dismissals >= 3 && r.Dismissals > r.Confirmations {
		r.Confidence = "disputed"
	}
	return r, err
}
func begin(ctx context.Context, db *pgxpool.Pool, u identity.User) (pgx.Tx, error) {
	tx, err := db.Begin(ctx)
	if err != nil {
		return nil, err
	}
	if _, err = tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended('reports:' || $1,0))`, u.ID); err == nil {
		_, err = tx.Exec(ctx, `INSERT INTO users(id,display_name) VALUES($1,$2) ON CONFLICT(id) DO NOTHING`, u.ID, u.Name)
	}
	if err != nil {
		_ = tx.Rollback(ctx)
		return nil, err
	}
	return tx, nil
}
func (s Store) Create(ctx context.Context, u identity.User, in Input, key string) (Report, error) {
	if !in.Valid(time.Now()) || !identity.UUID(key) {
		return Report{}, ErrInvalid
	}
	tx, err := begin(ctx, s.DB, u)
	if err != nil {
		return Report{}, err
	}
	defer tx.Rollback(ctx)
	var id string
	err = tx.QueryRow(ctx, `SELECT id::text FROM road_reports WHERE reporter_id=$1 AND request_key=$2`, u.ID, key).Scan(&id)
	if err == nil {
		r, e := scan(tx.QueryRow(ctx, `SELECT `+columns+` FROM road_reports r WHERE id=$2::uuid`, u.ID, id))
		if e != nil {
			return r, e
		}
		if r.Category != in.Category || r.Location != *in.Location || r.Note != in.Note || !r.ObservedAt.Equal(in.ObservedAt.UTC().Truncate(time.Microsecond)) {
			return Report{}, ErrConflict
		}
		return r, tx.Commit(ctx)
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return Report{}, err
	}
	var count int
	if err = tx.QueryRow(ctx, `SELECT count(*) FROM road_reports WHERE reporter_id=$1 AND created_at>now()-interval '1 hour'`, u.ID).Scan(&count); err != nil {
		return Report{}, err
	}
	if count >= 10 {
		return Report{}, ErrLimit
	}
	err = tx.QueryRow(ctx, `INSERT INTO road_reports(reporter_id,request_key,category,location,note,observed_at,expires_at) VALUES($1,$2,$3,ST_SetSRID(ST_MakePoint($4,$5),4326)::geography,$6,$7,$8) RETURNING id::text`, u.ID, key, in.Category, in.Location.Longitude, in.Location.Latitude, in.Note, in.ObservedAt.UTC(), in.ObservedAt.Add(Lifetime(in.Category)).UTC()).Scan(&id)
	if err != nil {
		return Report{}, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO report_audit(report_id,actor_id,action) VALUES($1,$2,'create')`, id, u.ID); err != nil {
		return Report{}, err
	}
	r, err := scan(tx.QueryRow(ctx, `SELECT `+columns+` FROM road_reports r WHERE id=$2::uuid`, u.ID, id))
	if err != nil {
		return r, err
	}
	return r, tx.Commit(ctx)
}
func (s Store) Nearby(ctx context.Context, u identity.User, q Query) ([]Report, error) {
	if q.Center == nil || !q.Center.Valid() || math.IsNaN(q.Radius) || q.Radius < 100 || q.Radius > 20000 {
		return nil, ErrInvalid
	}
	rows, err := s.DB.Query(ctx, `SELECT `+columns+` FROM road_reports r WHERE r.status='active' AND r.expires_at>now() AND ST_DWithin(r.location,ST_SetSRID(ST_MakePoint($2,$3),4326)::geography,$4) ORDER BY ST_Distance(r.location,ST_SetSRID(ST_MakePoint($2,$3),4326)::geography),r.id LIMIT 100`, u.ID, q.Center.Longitude, q.Center.Latitude, q.Radius)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Report, 0)
	for rows.Next() {
		r, err := scan(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}
func (s Store) Change(ctx context.Context, u identity.User, id string, vote int, withdraw bool) (Report, error) {
	if !identity.UUID(id) || (!withdraw && vote != 1 && vote != -1) {
		return Report{}, ErrInvalid
	}
	tx, err := begin(ctx, s.DB, u)
	if err != nil {
		return Report{}, err
	}
	defer tx.Rollback(ctx)
	var owner, status string
	var expires time.Time
	err = tx.QueryRow(ctx, `SELECT reporter_id::text,status,expires_at FROM road_reports WHERE id=$1 FOR UPDATE`, id).Scan(&owner, &status, &expires)
	if errors.Is(err, pgx.ErrNoRows) {
		return Report{}, ErrMissing
	}
	if err != nil {
		return Report{}, err
	}
	if withdraw && owner != u.ID || !withdraw && owner == u.ID {
		return Report{}, ErrForbidden
	}
	if status != "active" || !expires.After(time.Now()) {
		return Report{}, ErrMissing
	}
	action := "withdraw"
	if withdraw {
		_, err = tx.Exec(ctx, `UPDATE road_reports SET status='withdrawn',note='',expires_at=GREATEST(observed_at+interval '1 microsecond',LEAST(expires_at,now())) WHERE id=$1`, id)
	} else {
		action = "confirm"
		if vote == -1 {
			action = "dismiss"
		}
		var old int
		e := tx.QueryRow(ctx, `SELECT vote FROM report_votes WHERE report_id=$1 AND user_id=$2`, id, u.ID).Scan(&old)
		if e != nil && !errors.Is(e, pgx.ErrNoRows) {
			return Report{}, e
		}
		if old == vote {
			r, e := scan(tx.QueryRow(ctx, `SELECT `+columns+` FROM road_reports r WHERE id=$2::uuid`, u.ID, id))
			if e != nil {
				return r, e
			}
			return r, tx.Commit(ctx)
		}
		var count int
		if err = tx.QueryRow(ctx, `SELECT count(*) FROM report_audit WHERE actor_id=$1 AND created_at>now()-interval '1 hour' AND action IN ('confirm','dismiss')`, u.ID).Scan(&count); err != nil {
			return Report{}, err
		}
		if count >= 60 {
			return Report{}, ErrLimit
		}
		_, err = tx.Exec(ctx, `INSERT INTO report_votes(report_id,user_id,vote) VALUES($1,$2,$3) ON CONFLICT(report_id,user_id) DO UPDATE SET vote=EXCLUDED.vote,updated_at=now()`, id, u.ID, vote)
	}
	if err != nil {
		return Report{}, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO report_audit(report_id,actor_id,action) VALUES($1,$2,$3)`, id, u.ID, action); err != nil {
		return Report{}, err
	}
	r, err := scan(tx.QueryRow(ctx, `SELECT `+columns+` FROM road_reports r WHERE id=$2::uuid`, u.ID, id))
	if err != nil {
		return r, err
	}
	return r, tx.Commit(ctx)
}
func (s Store) Purge(ctx context.Context) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM road_reports WHERE expires_at<now()-interval '7 days'`)
	return err
}
