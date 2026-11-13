// Package trips owns membership and lifecycle transactions. Every operation on an
// existing trip locks its row first, including invite redemption and member reads.
// Future live-state publishers must reauthorize against this persisted state.
package trips

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"projectdrive/services/api/internal/identity"
)

const MemberLimit = 100

type Error struct {
	Code     string
	InviteID string
}

func (e Error) Error() string   { return e.Code }
func problem(code string) error { return Error{Code: code} }

type Store struct{ DB *pgxpool.Pool }
type Command struct {
	Action, TripID, InviteID, Key, Name, Token string
	ExpiresIn, MaxUses                         int
}
type Trip struct {
	ID        string     `json:"id"`
	OwnerID   string     `json:"owner_id"`
	Name      string     `json:"name"`
	Status    string     `json:"status"`
	CreatedAt time.Time  `json:"created_at"`
	StartedAt *time.Time `json:"started_at"`
	EndedAt   *time.Time `json:"ended_at"`
}
type Member struct {
	UserID         string    `json:"user_id"`
	Name           string    `json:"display_name"`
	Role           string    `json:"role"`
	SharingEnabled bool      `json:"sharing_enabled"`
	JoinedAt       time.Time `json:"joined_at"`
}
type Invite struct {
	ID        string    `json:"id"`
	Token     string    `json:"token,omitempty"`
	ExpiresAt time.Time `json:"expires_at"`
	MaxUses   int       `json:"max_uses"`
}

const tripColumns = `id::text, owner_id::text, name, status, created_at, started_at, ended_at`

func scanTrip(row pgx.Row) (t Trip, err error) {
	err = row.Scan(&t.ID, &t.OwnerID, &t.Name, &t.Status, &t.CreatedAt, &t.StartedAt, &t.EndedAt)
	t.CreatedAt = t.CreatedAt.UTC()
	if t.StartedAt != nil {
		v := t.StartedAt.UTC()
		t.StartedAt = &v
	}
	if t.EndedAt != nil {
		v := t.EndedAt.UTC()
		t.EndedAt = &v
	}
	return
}
func (s Store) Execute(ctx context.Context, user identity.User, c Command) (any, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(context.Background())
	result, err := execute(ctx, tx, user, c)
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return result, nil
}
func execute(ctx context.Context, tx pgx.Tx, user identity.User, c Command) (any, error) {
	// Only verified subjects reach here. Deleted identities cannot be resurrected.
	if _, err := tx.Exec(ctx, `INSERT INTO users(id,display_name) VALUES($1,$2) ON CONFLICT(id) DO NOTHING`, user.ID, user.Name); err != nil {
		return nil, err
	}
	var enabled bool
	if err := tx.QueryRow(ctx, `SELECT deleted_at IS NULL FROM users WHERE id=$1`, user.ID).Scan(&enabled); err != nil {
		return nil, err
	}
	if !enabled {
		return nil, problem("unauthorized")
	}
	if c.Action == "create" {
		// The unique index serializes retries even across separate API instances.
		_, err := tx.Exec(ctx, `INSERT INTO trips(owner_id,name,creation_key) VALUES($1,$2,$3) ON CONFLICT DO NOTHING`, user.ID, c.Name, c.Key)
		if err != nil {
			return nil, err
		}
		t, err := scanTrip(tx.QueryRow(ctx, `SELECT `+tripColumns+` FROM trips WHERE owner_id=$1 AND creation_key=$2 FOR UPDATE`, user.ID, c.Key))
		if err != nil {
			return nil, err
		}
		if t.Name != c.Name {
			return nil, problem("idempotency_conflict")
		}
		_, err = tx.Exec(ctx, `INSERT INTO trip_members(trip_id,user_id,role) VALUES($1,$2,'owner') ON CONFLICT DO NOTHING`, t.ID, user.ID)
		return t, err
	}
	var hash [32]byte
	if c.Action == "join" {
		hash = sha256.Sum256([]byte(c.Token))
		// Discovery does not lock the invite: preserve trip -> invite lock order.
		err := tx.QueryRow(ctx, `SELECT trip_id::text FROM trip_invites WHERE token_hash=$1`, hash[:]).Scan(&c.TripID)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, problem("invalid_invite")
		}
		if err != nil {
			return nil, err
		}
	}
	t, err := scanTrip(tx.QueryRow(ctx, `SELECT `+tripColumns+` FROM trips WHERE id=$1 FOR UPDATE`, c.TripID))
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, problem("not_found")
	}
	if err != nil {
		return nil, err
	}
	var role string
	var left *time.Time
	err = tx.QueryRow(ctx, `SELECT role,left_at FROM trip_members WHERE trip_id=$1 AND user_id=$2`, t.ID, user.ID).Scan(&role, &left)
	member := err == nil && left == nil
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}
	if c.Action == "join" {
		var inviteID string
		var valid bool
		var used, max int
		err = tx.QueryRow(ctx, `SELECT id::text, revoked_at IS NULL AND expires_at > clock_timestamp(),use_count,max_uses FROM trip_invites WHERE token_hash=$1 FOR UPDATE`, hash[:]).Scan(&inviteID, &valid, &used, &max)
		if err != nil {
			return nil, err
		}
		if !valid || (t.Status != "planned" && t.Status != "active") {
			return nil, problem("invalid_invite")
		}
		// A retry by an existing active member does not consume another use, even at capacity.
		if member {
			return t, nil
		}
		if used >= max {
			return nil, problem("invalid_invite")
		}
		var count int
		if err = tx.QueryRow(ctx, `SELECT count(*) FROM trip_members WHERE trip_id=$1 AND left_at IS NULL`, t.ID).Scan(&count); err != nil {
			return nil, err
		}
		if count >= MemberLimit {
			return nil, problem("trip_full")
		}
		_, err = tx.Exec(ctx, `INSERT INTO trip_members(trip_id,user_id,role) VALUES($1,$2,'member') ON CONFLICT(trip_id,user_id) DO UPDATE SET left_at=NULL,joined_at=now(),sharing_enabled=false`, t.ID, user.ID)
		if err != nil {
			return nil, err
		}
		_, err = tx.Exec(ctx, `UPDATE trip_invites SET use_count=use_count+1 WHERE id=$1`, inviteID)
		return t, err
	}
	if !member {
		if c.Action == "leave" && role == "member" {
			return map[string]string{"status": "left"}, nil
		}
		return nil, problem("not_found")
	}
	switch c.Action {
	case "get":
		return t, nil
	case "members":
		rows, err := tx.Query(ctx, `SELECT m.user_id::text,u.display_name,m.role,m.sharing_enabled,m.joined_at FROM trip_members m JOIN users u ON u.id=m.user_id WHERE m.trip_id=$1 AND m.left_at IS NULL ORDER BY m.joined_at,m.user_id LIMIT 100`, t.ID)
		if err != nil {
			return nil, err
		}
		defer rows.Close()
		members := []Member{}
		for rows.Next() {
			var m Member
			if err := rows.Scan(&m.UserID, &m.Name, &m.Role, &m.SharingEnabled, &m.JoinedAt); err != nil {
				return nil, err
			}
			m.JoinedAt = m.JoinedAt.UTC()
			members = append(members, m)
		}
		return map[string]any{"members": members}, rows.Err()
	case "leave":
		if role == "owner" {
			return nil, problem("owner_cannot_leave")
		}
		_, err := tx.Exec(ctx, `UPDATE trip_members SET left_at=now(),sharing_enabled=false WHERE trip_id=$1 AND user_id=$2`, t.ID, user.ID)
		return map[string]string{"status": "left"}, err
	}
	if role != "owner" {
		return nil, problem("forbidden")
	}
	switch c.Action {
	case "invite":
		if t.Status != "planned" && t.Status != "active" {
			return nil, problem("invalid_transition")
		}
		// Raw invite secrets are returned once and are never persisted, including retry records.
		var existingID string
		err := tx.QueryRow(ctx, `SELECT id::text FROM trip_invites WHERE trip_id=$1 AND request_key=$2`, t.ID, c.Key).Scan(&existingID)
		if err == nil {
			return nil, Error{Code: "invite_already_created", InviteID: existingID}
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return nil, err
		}

		secret := make([]byte, 32)
		if _, err := rand.Read(secret); err != nil {
			return nil, err
		}
		token := base64.RawURLEncoding.EncodeToString(secret)
		hash := sha256.Sum256([]byte(token))
		invite := Invite{Token: token, MaxUses: c.MaxUses}
		err = tx.QueryRow(ctx, `INSERT INTO trip_invites(trip_id,token_hash,expires_at,max_uses,request_key) VALUES($1,$2,clock_timestamp()+make_interval(secs=>$3),$4,$5) RETURNING id::text,expires_at`, t.ID, hash[:], c.ExpiresIn, c.MaxUses, c.Key).Scan(&invite.ID, &invite.ExpiresAt)
		invite.ExpiresAt = invite.ExpiresAt.UTC()
		return invite, err
	case "revoke":
		tag, err := tx.Exec(ctx, `UPDATE trip_invites SET revoked_at=COALESCE(revoked_at,now()) WHERE id=$1 AND trip_id=$2`, c.InviteID, t.ID)
		if err != nil {
			return nil, err
		}
		if tag.RowsAffected() == 0 {
			return nil, problem("not_found")
		}
		return map[string]string{"status": "revoked"}, nil
	case "start", "end", "cancel":
		next := map[string]string{"start": "active", "end": "completed", "cancel": "cancelled"}[c.Action]
		if t.Status == next {
			return t, nil
		}
		valid := c.Action == "start" && t.Status == "planned" || c.Action == "end" && t.Status == "active" || c.Action == "cancel" && (t.Status == "planned" || t.Status == "active")
		if !valid {
			return nil, problem("invalid_transition")
		}
		_, err := tx.Exec(ctx, `UPDATE trips SET status=$2,started_at=CASE WHEN $2='active' THEN now() ELSE started_at END,ended_at=CASE WHEN $2 IN ('completed','cancelled') THEN now() ELSE NULL END WHERE id=$1`, t.ID, next)
		if err != nil {
			return nil, err
		}
		if next == "completed" || next == "cancelled" {
			if _, err = tx.Exec(ctx, `UPDATE trip_members SET sharing_enabled=false WHERE trip_id=$1`, t.ID); err != nil {
				return nil, err
			}
			if _, err = tx.Exec(ctx, `UPDATE trip_invites SET revoked_at=COALESCE(revoked_at,now()) WHERE trip_id=$1`, t.ID); err != nil {
				return nil, err
			}
		}
		return scanTrip(tx.QueryRow(ctx, `SELECT `+tripColumns+` FROM trips WHERE id=$1`, t.ID))
	}
	return nil, problem("invalid_request")
}
func ValidToken(s string) bool {
	b, err := base64.RawURLEncoding.DecodeString(s)
	return err == nil && len(b) == 32 && base64.RawURLEncoding.EncodeToString(b) == s && !strings.Contains(s, "=")
}
