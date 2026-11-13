package httpapi

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type fakePinger struct{ err error }

func (f fakePinger) Ping(context.Context) error { return f.err }

func TestHealthz(t *testing.T) {
	handler := NewHandler(fakePinger{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"status":"ok"`) {
		t.Fatalf("unexpected health response: status=%d body=%s", response.Code, response.Body.String())
	}
	if response.Header().Get("X-Request-ID") == "" {
		t.Fatal("health response has no request ID")
	}
}

func TestReadyzWhenDatabaseResponds(t *testing.T) {
	handler := NewHandler(fakePinger{}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/readyz", nil))
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"status":"ready"`) {
		t.Fatalf("unexpected readiness response: status=%d body=%s", response.Code, response.Body.String())
	}
}

func TestReadyzWhenDatabaseFails(t *testing.T) {
	handler := NewHandler(fakePinger{err: errors.New("unavailable")}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/readyz", nil))
	if response.Code != http.StatusServiceUnavailable || !strings.Contains(response.Body.String(), `"status":"not_ready"`) {
		t.Fatalf("unexpected readiness response: status=%d body=%s", response.Code, response.Body.String())
	}
}
