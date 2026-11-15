package httpapi

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"projectdrive/services/api/internal/identity"
	"strings"
	"sync/atomic"
	"testing"
)

func TestAuthGatewayRejectsProxyExpansionAndDisablesUnreadyEmail(t *testing.T) {
	var calls atomic.Int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.WriteHeader(400)
		_, _ = w.Write([]byte(`{"error":"private provider details"}`))
	}))
	defer upstream.Close()
	p, _ := identity.NewSupabase(upstream.URL, "https://auth.test")
	h := NewHandler(fakePinger{}, slog.New(slog.NewTextHandler(io.Discard, nil)), Options{Auth: AuthOptions{Provider: p}})
	for _, tc := range []struct {
		path, body string
		want       int
	}{
		{"sign-in", `{"email":"synthetic@example.test","password":"test","role":"service_role"}`, 400},
		{"sign-in", `{"email":"synthetic@example.test","password":"test"} {}`, 400},
		{"sign-up", `{"email":"synthetic@example.test","password":"test"}`, 503},
		{"recover", `{"email":"synthetic@example.test"}`, 503},
		{"refresh", `{"refresh_token":""}`, 400},
		{"sign-in?redirect_to=https://untrusted.test", `{}`, 400},
	} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("POST", "/v1/auth/"+tc.path, strings.NewReader(tc.body)))
		if w.Code != tc.want || w.Header().Get("Cache-Control") != "no-store" {
			t.Fatal(tc.path, w.Code)
		}
	}
	if calls.Load() != 0 {
		t.Fatal("invalid request reached provider")
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest("POST", "/v1/auth/sign-in", strings.NewReader(`{"email":"synthetic@example.test","password":"test"}`)))
	if w.Code != 400 || strings.Contains(w.Body.String(), "private provider details") {
		t.Fatal("unsafe error response")
	}
}

func TestGoogleModeExposesOnlyPublicConfigAndNoPasswordProxy(t *testing.T) {
	var calls atomic.Int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.URL.Path == "/auth/v1/settings" {
			_, _ = w.Write([]byte(`{"external":{"google":true}}`))
			return
		}
		w.WriteHeader(401)
	}))
	defer upstream.Close()
	p, _ := identity.NewSupabase(upstream.URL+"/auth/v1", upstream.URL+"/auth/v1")
	p.PublishableKey = "sb_publishable_synthetic"
	h := NewHandler(fakePinger{}, slog.New(slog.NewTextHandler(io.Discard, nil)), Options{Auth: AuthOptions{Provider: p, GoogleEnabled: true}})
	for _, operation := range []string{"sign-in", "sign-up", "refresh", "verify", "password", "recover"} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("POST", "/v1/auth/"+operation, strings.NewReader(`{}`)))
		if w.Code != 404 {
			t.Fatalf("password proxy %s remains reachable: %d", operation, w.Code)
		}
	}
	if calls.Load() != 0 {
		t.Fatal("password requests reached provider")
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest("GET", "/v1/auth/config", nil))
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"google":true`) || !strings.Contains(w.Body.String(), `sb_publishable_synthetic`) || strings.Contains(w.Body.String(), `service_role`) {
		t.Fatal("invalid public config")
	}
}
