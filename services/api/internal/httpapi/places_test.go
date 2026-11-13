package httpapi

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http/httptest"
	"projectdrive/services/api/internal/navigation"
	"strings"
	"testing"
)

type fakePlaces struct {
	err   error
	calls int
}

func (p *fakePlaces) Search(context.Context, string) ([]navigation.Place, error) {
	p.calls++
	return []navigation.Place{{ID: "node/1", Name: "Nakuru"}}, p.err
}
func TestPlaceSearchInputAndPrivacyBoundary(t *testing.T) {
	for _, tc := range []struct {
		body   string
		err    error
		status int
	}{{`{"query":"Nakuru"}`, nil, 200}, {`{"query":"Nakuru"}`, navigation.ErrSearchBusy, 429}, {`{"query":"Nakuru"}`, errors.New("private upstream"), 503}, {`{"query":"x"}`, nil, 400}, {`{"query":"  "}`, nil, 400}, {`{"query":"??"}`, nil, 400}, {`{"query":"Nakuru","location":"private"}`, nil, 400}, {`{"query":"Nakuru"} {}`, nil, 400}, {strings.Repeat(" ", 1025) + `{}`, nil, 400}} {
		var logs bytes.Buffer
		p := &fakePlaces{err: tc.err}
		h := NewHandler(fakePinger{}, slog.New(slog.NewTextHandler(&logs, nil)), Options{NavigationOptions: NavigationOptions{Places: p}})
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("POST", "/v1/navigation/search", strings.NewReader(tc.body)))
		if w.Code != tc.status {
			t.Fatalf("status=%d want=%d", w.Code, tc.status)
		}
		if strings.Contains(logs.String(), "Nakuru") || strings.Contains(w.Body.String(), "private upstream") {
			t.Fatal("sensitive query or error leaked")
		}
		if tc.status == 400 && p.calls != 0 {
			t.Fatal("invalid query reached index")
		}
		if w.Header().Get("Cache-Control") != "no-store" {
			t.Fatal("search must not be cached")
		}
	}
	h := NewHandler(fakePinger{}, slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil)))
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest("POST", "/v1/navigation/search", strings.NewReader(`{"query":"Nakuru"}`)))
	if w.Code != 503 {
		t.Fatal("missing index not reported")
	}
}
