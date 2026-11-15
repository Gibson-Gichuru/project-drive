package identity

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/golang-jwt/jwt/v5"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestSupabaseChecksProviderSessionAndClaimBoundaries(t *testing.T) {
	issuer := "https://auth.example.test/v1/auth"
	user := "11111111-1111-4111-8111-111111111111"
	var revoked atomic.Bool
	var calls atomic.Int64
	c := supabaseClaims{Role: "authenticated", SessionID: "22222222-2222-4222-8222-222222222222", RegisteredClaims: jwt.RegisteredClaims{Subject: user, Issuer: issuer, Audience: jwt.ClaimStrings{"authenticated"}, IssuedAt: jwt.NewNumericDate(time.Now()), ExpiresAt: jwt.NewNumericDate(time.Now().Add(15 * time.Minute))}}
	sign := func(c supabaseClaims) string {
		v, e := jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString([]byte("synthetic-unit-test-only-signing-secret"))
		if e != nil {
			t.Fatal(e)
		}
		return v
	}
	raw := sign(c)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path != "/user" || r.Header.Get("Authorization") != "Bearer "+raw || revoked.Load() {
			w.WriteHeader(403)
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"id": user, "email_confirmed_at": time.Now(), "user_metadata": map[string]string{"display_name": "Synthetic"}})
	}))
	defer upstream.Close()
	p, e := NewSupabase(upstream.URL, issuer)
	if e != nil {
		t.Fatal(e)
	}
	session, e := p.Authenticate(context.Background(), raw)
	if e != nil || session.User.ID != user || session.Check == nil {
		t.Fatal("valid provider identity not accepted", e)
	}
	revoked.Store(true)
	if e := session.Check(context.Background()); !errors.Is(e, ErrIdentity) {
		t.Fatal("revoked session accepted", e)
	}
	revoked.Store(false)
	for _, change := range []func(*supabaseClaims){
		func(c *supabaseClaims) { c.Issuer = "https://other.test" }, func(c *supabaseClaims) { c.Role = "service_role" },
		func(c *supabaseClaims) { c.Anonymous = true }, func(c *supabaseClaims) { c.SessionID = "" }, func(c *supabaseClaims) { c.Subject = "invalid" },
		func(c *supabaseClaims) { c.ExpiresAt = jwt.NewNumericDate(time.Now().Add(-time.Second)) }, func(c *supabaseClaims) { c.ExpiresAt = jwt.NewNumericDate(time.Now().Add(2 * time.Hour)) },
		func(c *supabaseClaims) { c.Audience = jwt.ClaimStrings{"wrong"} },
	} {
		bad := c
		change(&bad)
		before := calls.Load()
		if _, e := p.Authenticate(context.Background(), sign(bad)); e == nil {
			t.Fatal("invalid claims accepted")
		}
		if calls.Load() != before {
			t.Fatal("invalid claims reached provider")
		}
	}
	// A syntactically valid forged signature is rejected by the trusted provider.
	if _, e := p.Authenticate(context.Background(), raw[:len(raw)-2]+"xx"); e == nil {
		t.Fatal("forged signature accepted")
	}
	upstream.Close()
	if _, e := p.Authenticate(context.Background(), raw); !errors.Is(e, ErrProviderUnavailable) {
		t.Fatal("provider failure did not fail closed", e)
	}
}

func TestSupabaseNeverFollowsRedirectOrAcceptsUnconfirmedUser(t *testing.T) {
	for _, tc := range []struct {
		status int
		body   string
	}{{302, ""}, {200, `{"id":"11111111-1111-4111-8111-111111111111"}`}, {200, `{"id":"22222222-2222-4222-8222-222222222222","email_confirmed_at":"2026-01-01T00:00:00Z"}`}} {
		s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Location", "https://untrusted.invalid")
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(tc.body))
		}))
		p, _ := NewSupabase(s.URL, "https://auth.test")
		raw, _ := jwt.NewWithClaims(jwt.SigningMethodHS256, supabaseClaims{Role: "authenticated", SessionID: "22222222-2222-4222-8222-222222222222", RegisteredClaims: jwt.RegisteredClaims{Issuer: p.Issuer, Subject: "11111111-1111-4111-8111-111111111111", Audience: jwt.ClaimStrings{"authenticated"}, IssuedAt: jwt.NewNumericDate(time.Now()), ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Minute))}}).SignedString([]byte("test"))
		if _, e := p.Authenticate(context.Background(), raw); e == nil {
			t.Fatal("untrusted user response accepted")
		}
		s.Close()
	}
}

func TestHostedSupabasePreservesAuthPrefixAndPublishesOnlyTheApiKey(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/auth/v1/user" || r.Header.Get("apikey") != "sb_publishable_synthetic" || r.Header.Get("Authorization") != "Bearer synthetic" {
			t.Error("hosted provider request lost its path or headers")
		}
		w.WriteHeader(401)
	}))
	defer upstream.Close()
	p, e := NewSupabase(upstream.URL+"/auth/v1", upstream.URL+"/auth/v1")
	if e != nil {
		t.Fatal(e)
	}
	p.PublishableKey = "sb_publishable_synthetic"
	if status, _, e := p.Request(context.Background(), "GET", "/user", "synthetic", nil); e != nil || status != 401 {
		t.Fatal("hosted transport failed")
	}
	for _, path := range []string{"/admin", "/auth/v1/../admin", "/auth/v1/"} {
		if _, e := NewSupabase(upstream.URL+path, upstream.URL+"/auth/v1"); e == nil {
			t.Fatal("arbitrary Auth path accepted")
		}
	}
}
