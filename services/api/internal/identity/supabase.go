package identity

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

var ErrIdentity = errors.New("invalid identity")
var ErrProviderUnavailable = errors.New("identity provider unavailable")

type Authenticator interface {
	Authenticate(context.Context, string) (Session, error)
}

// Supabase uses the private Auth server's authenticated /user endpoint. In the
// pinned server this checks the JWT signature AND the live session/user rows.
// No service-role key, shared signing secret, or positive validation cache.
type Supabase struct {
	URL, Issuer    string
	PublishableKey string
	Client         *http.Client
}

func NewSupabase(address, issuer string) (*Supabase, error) {
	u, e := url.Parse(address)
	i, ie := url.Parse(issuer)
	if e != nil || ie != nil || u.Host == "" || i.Host == "" || u.User != nil || i.User != nil ||
		(u.Scheme != "http" && u.Scheme != "https") || (i.Scheme != "http" && i.Scheme != "https") ||
		u.RawQuery != "" || i.RawQuery != "" || u.Fragment != "" || i.Fragment != "" || (u.Path != "" && u.Path != "/auth/v1") {
		return nil, errors.New("invalid Supabase origin or issuer")
	}
	return &Supabase{URL: strings.TrimRight(address, "/"), Issuer: issuer, Client: &http.Client{Timeout: 2 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}

type supabaseClaims struct {
	Role      string `json:"role"`
	SessionID string `json:"session_id"`
	Anonymous bool   `json:"is_anonymous"`
	jwt.RegisteredClaims
}
type ProviderUser struct {
	ID               string     `json:"id"`
	Email            string     `json:"email"`
	EmailConfirmedAt *time.Time `json:"email_confirmed_at"`
	Anonymous        bool       `json:"is_anonymous"`
	UserMetadata     struct {
		Name       string `json:"display_name"`
		FullName   string `json:"full_name"`
		GoogleName string `json:"name"`
	} `json:"user_metadata"`
}

func (p *Supabase) Authenticate(ctx context.Context, raw string) (Session, error) {
	if len(raw) < 1 || len(raw) > 4090 || strings.ContainsAny(raw, "\r\n") {
		return Session{}, ErrIdentity
	}
	var c supabaseClaims
	// This first pass only bounds untrusted input. Trust is established by /user.
	_, _, err := jwt.NewParser().ParseUnverified(raw, &c)
	now := time.Now()
	if err != nil || c.Issuer != p.Issuer || len(c.Audience) != 1 || c.Audience[0] != "authenticated" ||
		c.Role != "authenticated" || c.Anonymous || !UUID(c.Subject) || !UUID(c.SessionID) ||
		c.ExpiresAt == nil || c.IssuedAt == nil || !c.ExpiresAt.After(now) || c.IssuedAt.After(now.Add(5*time.Second)) ||
		!c.ExpiresAt.After(c.IssuedAt.Time) || c.ExpiresAt.Sub(c.IssuedAt.Time) > time.Hour {
		return Session{}, ErrIdentity
	}
	status, data, err := p.Request(ctx, "GET", "/user", raw, nil)
	if err != nil {
		return Session{}, ErrProviderUnavailable
	}
	if status == 401 || status == 403 || status == 404 {
		return Session{}, ErrIdentity
	}
	if status != 200 {
		return Session{}, ErrProviderUnavailable
	}
	var u ProviderUser
	if json.Unmarshal(data, &u) != nil || u.ID != c.Subject || u.Anonymous || u.EmailConfirmedAt == nil {
		return Session{}, ErrIdentity
	}
	name := strings.TrimSpace(u.UserMetadata.Name)
	if name == "" {
		name = strings.TrimSpace(u.UserMetadata.FullName)
	}
	if name == "" {
		name = strings.TrimSpace(u.UserMetadata.GoogleName)
	}
	if !ValidName(name, 80) {
		name = "Driver"
	}
	return Session{User: User{ID: u.ID, Name: name}, ExpiresAt: c.ExpiresAt.Time.UTC(), SessionID: c.SessionID, Check: func(ctx context.Context) error {
		_, err := p.Authenticate(ctx, raw)
		return err
	}}, nil
}

// Request is used only with fixed internal endpoint paths by the gateway. It
// never follows redirects, forwards browser cookies, or returns unbounded data.
func (p *Supabase) Request(ctx context.Context, method, path, bearer string, body []byte) (int, []byte, error) {
	r, err := http.NewRequestWithContext(ctx, method, p.URL+path, bytes.NewReader(body))
	if err != nil {
		return 0, nil, ErrProviderUnavailable
	}
	if p.PublishableKey != "" {
		r.Header.Set("apikey", p.PublishableKey)
	}
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Cache-Control", "no-store")
	if bearer != "" {
		r.Header.Set("Authorization", "Bearer "+bearer)
	}
	response, err := p.Client.Do(r)
	if err != nil {
		return 0, nil, ErrProviderUnavailable
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 65537))
	if err != nil || len(data) > 65536 {
		return 0, nil, ErrProviderUnavailable
	}
	return response.StatusCode, data, nil
}
