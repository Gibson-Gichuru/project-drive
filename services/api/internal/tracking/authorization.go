package tracking

import (
	"context"
	"errors"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Member struct{ Sharing bool }
type State struct {
	Active  bool
	Members map[string]Member
}
type Authorizer interface {
	State(context.Context, string) (State, error)
}
type PostgresAuthorizer struct{ DB *pgxpool.Pool }

func (a PostgresAuthorizer) State(ctx context.Context, trip string) (State, error) {
	rows, err := a.DB.Query(ctx, `SELECT t.status,m.user_id::text,m.sharing_enabled FROM trips t LEFT JOIN (trip_members m JOIN users u ON u.id=m.user_id AND u.deleted_at IS NULL) ON m.trip_id=t.id AND m.left_at IS NULL AND m.removed_at IS NULL WHERE t.id=$1 LIMIT 101`, trip)
	if err != nil {
		return State{}, err
	}
	defer rows.Close()
	state := State{Members: map[string]Member{}}
	found := false
	for rows.Next() {
		found = true
		var status string
		var user *string
		var sharing *bool
		if err := rows.Scan(&status, &user, &sharing); err != nil {
			return State{}, err
		}
		state.Active = status == "active"
		if user != nil && sharing != nil {
			state.Members[*user] = Member{Sharing: *sharing}
			if len(state.Members) > 100 {
				return State{}, ErrCapacity
			}
		}
	}
	if err := rows.Err(); err != nil {
		return State{}, err
	}
	if !found {
		return State{}, pgx.ErrNoRows
	}
	return state, nil
}

var ErrNotFound = errors.New("not_found")
var ErrNotActive = errors.New("trip_not_active")
var ErrNotSharing = errors.New("sharing_disabled")
var ErrCapacity = errors.New("tracking_capacity")
var ErrStopped = errors.New("access_revoked")
var ErrOlder = errors.New("older_update")
var ErrRate = errors.New("rate_limited")
var ErrInvalid = errors.New("invalid_message")

func (s State) authorize(user string) error {
	m, ok := s.Members[user]
	if !ok {
		return ErrNotFound
	}
	if !s.Active {
		return ErrNotActive
	}
	if !m.Sharing {
		return ErrNotSharing
	}
	return nil
}
