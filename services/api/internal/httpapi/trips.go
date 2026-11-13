package httpapi

import (
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/trips"
)

type TripService interface {
	Execute(context.Context, identity.User, trips.Command) (any, error)
}
type TripOptions struct {
	Service   TripService
	PublicKey ed25519.PublicKey
}

func registerTrips(mux *http.ServeMux, options TripOptions) {
	for pattern, action := range map[string]string{
		"POST /v1/trips": "create", "POST /v1/trips/join": "join",
		"GET /v1/trips/{trip}": "get", "GET /v1/trips/{trip}/members": "members",
		"POST /v1/trips/{trip}/invites": "invite", "DELETE /v1/trips/{trip}/invites/{invite}": "revoke",
		"POST /v1/trips/{trip}/start": "start", "POST /v1/trips/{trip}/leave": "leave",
		"POST /v1/trips/{trip}/end": "end", "POST /v1/trips/{trip}/cancel": "cancel",
	} {
		mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Cache-Control", "no-store")
			auth := r.Header.Get("Authorization")
			if options.Service == nil || len(options.PublicKey) != ed25519.PublicKeySize || !strings.HasPrefix(auth, "Bearer ") || len(auth) > 4096 {
				unauthorized(w, r)
				return
			}
			user, err := identity.Verify(options.PublicKey, strings.TrimPrefix(auth, "Bearer "))
			if err != nil {
				unauthorized(w, r)
				return
			}
			c := trips.Command{Action: action, TripID: r.PathValue("trip"), InviteID: r.PathValue("invite")}
			if c.TripID != "" && !identity.UUID(c.TripID) || c.InviteID != "" && !identity.UUID(c.InviteID) {
				writeAPIError(w, r, 400, "invalid_request", "valid UUID required")
				return
			}
			if action == "create" || action == "invite" {
				c.Key = r.Header.Get("Idempotency-Key")
				if !identity.UUID(c.Key) {
					writeAPIError(w, r, 400, "invalid_request", "Idempotency-Key UUID required")
					return
				}
			}
			var body struct {
				Name      string `json:"name"`
				Token     string `json:"token"`
				ExpiresIn int    `json:"expires_in_s"`
				MaxUses   int    `json:"max_uses"`
			}
			// Separate schemas reject known-but-irrelevant fields as well as unknown ones.
			var target any
			switch action {
			case "create":
				target = &struct {
					Name *string `json:"name"`
				}{&body.Name}
			case "join":
				target = &struct {
					Token *string `json:"token"`
				}{&body.Token}
			case "invite":
				target = &struct {
					ExpiresIn *int `json:"expires_in_s"`
					MaxUses   *int `json:"max_uses"`
				}{&body.ExpiresIn, &body.MaxUses}
			}
			if target != nil {
				r.Body = http.MaxBytesReader(w, r.Body, 4096)
				decoder := json.NewDecoder(r.Body)
				decoder.DisallowUnknownFields()
				if err := decoder.Decode(target); err != nil {
					writeAPIError(w, r, 400, "invalid_request", "invalid JSON body")
					return
				}
				var extra any
				if decoder.Decode(&extra) != io.EOF {
					writeAPIError(w, r, 400, "invalid_request", "one JSON object required")
					return
				}
			} else if r.Body != nil {
				b, err := io.ReadAll(io.LimitReader(r.Body, 1))
				if err != nil || len(b) > 0 {
					writeAPIError(w, r, 400, "invalid_request", "body must be empty")
					return
				}
			}
			if action == "create" && !identity.ValidName(body.Name, 120) || action == "join" && !trips.ValidToken(body.Token) || action == "invite" && (body.ExpiresIn < 60 || body.ExpiresIn > 86400 || body.MaxUses < 1 || body.MaxUses > 100) {
				writeAPIError(w, r, 400, "invalid_request", "invalid name, invite token, expiry or use limit")
				return
			}
			c.Name = body.Name
			c.Token = body.Token
			c.ExpiresIn = body.ExpiresIn
			c.MaxUses = body.MaxUses
			ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
			defer cancel()
			result, err := options.Service.Execute(ctx, user, c)
			if err != nil {
				var domain trips.Error
				if errors.As(err, &domain) {
					status := 409
					switch domain.Code {
					case "not_found":
						status = 404
					case "forbidden":
						status = 403
					case "unauthorized":
						unauthorized(w, r)
						return
					case "invalid_invite":
						status = 400
					}
					if domain.InviteID != "" {
						id, _ := r.Context().Value(requestIDKey{}).(string)
						writeJSON(w, status, map[string]string{"code": domain.Code, "message": "invitation already created; revoke it if the secret was lost", "request_id": id, "invite_id": domain.InviteID})
					} else {
						writeAPIError(w, r, status, domain.Code, strings.ReplaceAll(domain.Code, "_", " "))
					}
					return
				}
				// Do not expose SQL, identity, invitation secrets or request bodies.
				writeAPIError(w, r, 503, "unavailable", "trip operation unavailable; retry with the same key")
				return
			}
			writeJSON(w, 200, result)
		})
	}
}
func unauthorized(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("WWW-Authenticate", `Bearer realm="project-drive"`)
	writeAPIError(w, r, 401, "unauthorized", "valid identity required")
}
