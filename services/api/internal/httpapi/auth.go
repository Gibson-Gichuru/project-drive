package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/mail"
	"strings"
	"sync"
	"time"

	"projectdrive/services/api/internal/identity"
)

type AuthOptions struct {
	Provider      *identity.Supabase
	EmailEnabled  bool
	RevokeSession func(context.Context, string) error
}

func (o TripOptions) authenticate(r *http.Request) (identity.Session, error) {
	value := r.Header.Get("Authorization")
	if !strings.HasPrefix(value, "Bearer ") || len(value) > 4096 {
		return identity.Session{}, identity.ErrIdentity
	}
	raw := strings.TrimPrefix(value, "Bearer ")
	if o.Authenticator != nil {
		return o.Authenticator.Authenticate(r.Context(), raw)
	}
	if len(o.PublicKey) != 32 {
		return identity.Session{}, identity.ErrIdentity
	}
	return identity.Authenticate(o.PublicKey, raw)
}
func authenticationError(w http.ResponseWriter, r *http.Request, err error) {
	if errors.Is(err, identity.ErrProviderUnavailable) {
		writeAPIError(w, r, 503, "identity_unavailable", "sign-in service unavailable; retry later")
		return
	}
	unauthorized(w, r)
}

// A fixed-memory process budget complements the Auth server's own rate limits.
// Remote-address/X-Forwarded-For spoofing cannot allocate per-IP entries or bypass it.
type authBudget struct {
	sync.Mutex
	start time.Time
	count int
}

func (b *authBudget) take() bool {
	b.Lock()
	defer b.Unlock()
	if time.Since(b.start) >= time.Minute {
		b.start = time.Now()
		b.count = 0
	}
	if b.count >= 30 {
		return false
	}
	b.count++
	return true
}

func registerAuth(mux *http.ServeMux, options AuthOptions) {
	for _, kind := range []string{"confirmation", "recovery"} {
		mux.HandleFunc("GET /v1/auth/templates/"+kind, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store")
			_, _ = io.WriteString(w, `<!doctype html><html><body><h1>Your Outset email code</h1><p>Enter this code in Outset:</p><p style="font-size:28px;letter-spacing:6px"><strong>{{ .Token }}</strong></p><p>This code expires in 15 minutes. If you did not request it, ignore this email. Never share the code with anyone.</p></body></html>`)
		})
	}
	budget := &authBudget{}
	mux.HandleFunc("GET /v1/auth/config", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		writeJSON(w, 200, map[string]bool{"available": options.Provider != nil, "email_registration": options.Provider != nil && options.EmailEnabled})
	})
	for _, operation := range []string{"sign-in", "refresh", "sign-out", "sign-up", "verify", "recover", "password", "me"} {
		method := "POST"
		if operation == "me" {
			method = "GET"
		}
		mux.HandleFunc(method+" /v1/auth/"+operation, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Cache-Control", "no-store")
			if r.URL.RawQuery != "" {
				writeAPIError(w, r, 400, "invalid_request", "query parameters are not accepted")
				return
			}
			p := options.Provider
			if p == nil {
				writeAPIError(w, r, 503, "identity_unavailable", "sign-in service unavailable")
				return
			}
			if operation != "me" && operation != "sign-out" && !budget.take() {
				w.Header().Set("Retry-After", "60")
				writeAPIError(w, r, 429, "rate_limited", "please wait before retrying")
				return
			}
			if (operation == "sign-up" || operation == "verify" || operation == "recover") && !options.EmailEnabled {
				writeAPIError(w, r, 503, "email_unavailable", "email registration and recovery are not ready")
				return
			}
			var input struct{ Email, Password, RefreshToken, Token, Type, DisplayName string }
			if operation != "me" && operation != "sign-out" {
				// Explicit field lists: never proxy arbitrary provider/admin parameters.
				fields := map[string]*string{}
				switch operation {
				case "sign-in":
					fields = map[string]*string{"email": &input.Email, "password": &input.Password}
				case "sign-up":
					fields = map[string]*string{"email": &input.Email, "password": &input.Password, "display_name": &input.DisplayName}
				case "refresh":
					fields = map[string]*string{"refresh_token": &input.RefreshToken}
				case "verify":
					fields = map[string]*string{"email": &input.Email, "token": &input.Token, "type": &input.Type}
				case "recover":
					fields = map[string]*string{"email": &input.Email}
				case "password":
					fields = map[string]*string{"password": &input.Password}
				}
				d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192))
				var values map[string]string
				if d.Decode(&values) != nil {
					writeAPIError(w, r, 400, "invalid_request", "invalid authentication request")
					return
				}
				var extra any
				if d.Decode(&extra) != io.EOF {
					writeAPIError(w, r, 400, "invalid_request", "one object required")
					return
				}
				for key, value := range values {
					target, ok := fields[key]
					if !ok {
						writeAPIError(w, r, 400, "invalid_request", "unexpected field")
						return
					}
					*target = value
				}
			}
			if operation == "sign-in" || operation == "sign-up" || operation == "verify" || operation == "recover" {
				a, e := mail.ParseAddress(input.Email)
				if e != nil || a.Address != input.Email || len(input.Email) > 254 {
					writeAPIError(w, r, 400, "invalid_request", "valid email required")
					return
				}
			}
			if (operation == "sign-in" && (len(input.Password) < 1 || len(input.Password) > 1024)) || ((operation == "sign-up" || operation == "password") && (len(input.Password) < 12 || len(input.Password) > 1024)) || (operation == "sign-up" && !identity.ValidName(input.DisplayName, 80)) || (operation == "refresh" && (len(input.RefreshToken) < 1 || len(input.RefreshToken) > 4096 || strings.ContainsAny(input.RefreshToken, "\r\n"))) {
				writeAPIError(w, r, 400, "invalid_request", "check the name and password (at least 12 characters for a new password)")
				return
			}
			if operation == "verify" && ((input.Type != "signup" && input.Type != "recovery") || len(input.Token) != 6 || strings.Trim(input.Token, "0123456789") != "") {
				writeAPIError(w, r, 400, "invalid_request", "six-digit email code required")
				return
			}
			bearer := ""
			sessionID := ""
			if operation == "me" || operation == "sign-out" || operation == "password" {
				s, e := (TripOptions{Authenticator: p}).authenticate(r)
				if e != nil {
					authenticationError(w, r, e)
					return
				}
				if operation == "me" {
					writeJSON(w, 200, map[string]string{"id": s.User.ID, "display_name": s.User.Name})
					return
				}
				bearer = strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
				sessionID = s.SessionID
			}
			path, verb, payload := "", "POST", map[string]any{}
			switch operation {
			case "sign-in":
				path = "/token?grant_type=password"
				payload = map[string]any{"email": input.Email, "password": input.Password}
			case "refresh":
				path = "/token?grant_type=refresh_token"
				payload["refresh_token"] = input.RefreshToken
			case "sign-out":
				path = "/logout?scope=local"
			case "sign-up":
				path = "/signup"
				payload = map[string]any{"email": input.Email, "password": input.Password, "data": map[string]string{"display_name": input.DisplayName}}
			case "verify":
				path = "/verify"
				payload = map[string]any{"email": input.Email, "token": input.Token, "type": input.Type}
			case "recover":
				path = "/recover"
				payload["email"] = input.Email
			case "password":
				path = "/user"
				verb = "PUT"
				payload["password"] = input.Password
			}
			body, _ := json.Marshal(payload)
			status, data, e := p.Request(r.Context(), verb, path, bearer, body)
			if e != nil || status >= 500 {
				writeAPIError(w, r, 503, "identity_unavailable", "sign-in service unavailable; retry later")
				return
			}
			if status == 429 {
				w.Header().Set("Retry-After", "60")
				writeAPIError(w, r, 429, "rate_limited", "please wait before retrying")
				return
			}
			if status < 200 || status >= 300 {
				writeAPIError(w, r, 400, "authentication_refused", "check your credentials or email code")
				return
			}
			if operation == "sign-out" && options.RevokeSession != nil {
				if err := options.RevokeSession(r.Context(), sessionID); err != nil {
					writeAPIError(w, r, 503, "identity_unavailable", "server sign-out cleanup pending")
					return
				}
			}
			if operation == "sign-in" || operation == "refresh" || operation == "verify" {
				var token struct {
					Access  string `json:"access_token"`
					Refresh string `json:"refresh_token"`
					Expires int    `json:"expires_in"`
				}
				if json.Unmarshal(data, &token) != nil || token.Refresh == "" || len(token.Refresh) > 4096 {
					writeAPIError(w, r, 503, "identity_unavailable", "session could not be validated")
					return
				}
				s, e := p.Authenticate(r.Context(), token.Access)
				if e != nil {
					authenticationError(w, r, e)
					return
				}
				writeJSON(w, 200, map[string]any{"access_token": token.Access, "refresh_token": token.Refresh, "expires_at": s.ExpiresAt.Unix(), "user": map[string]string{"id": s.User.ID, "display_name": s.User.Name}})
				return
			}
			writeJSON(w, 200, map[string]string{"status": "accepted"})
		})
	}
}
