package httpapi

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"projectdrive/services/api/internal/navigation"
	"strings"
	"testing"
)

type fakeEngine struct {
	err   error
	calls int
}

func (f *fakeEngine) Route(context.Context, navigation.Request) (navigation.Route, error) {
	f.calls++
	return navigation.Route{Geometry: []navigation.Point{{Latitude: -1, Longitude: 36}, {Latitude: -2, Longitude: 37}}}, f.err
}

func TestRouteValidationAndErrors(t *testing.T) {
	for _, tc := range []struct {
		body   string
		err    error
		status int
	}{
		{`{"origin":{"latitude":-1,"longitude":36},"destination":{"latitude":-2,"longitude":37}}`, nil, 200},
		{`{"origin":{"latitude":-1,"longitude":36},"destination":{"latitude":-2,"longitude":37}}`, navigation.ErrNoRoute, 422},
		{`{"origin":{"latitude":-1,"longitude":36},"destination":{"latitude":-2,"longitude":37}}`, errors.New("secret upstream error"), 503},
		{`{}`, nil, 400},
		{`{"origin":{"latitude":0},"destination":{"latitude":0,"longitude":37}}`, nil, 400}, {`{"origin":{"latitude":91,"longitude":36},"destination":{"latitude":0,"longitude":37}}`, nil, 400},
		{`{"origin":{"latitude":0,"longitude":36},"destination":{"latitude":0,"longitude":37},"costing":"multimodal"}`, nil, 400},
		{`{"origin":{"latitude":0,"longitude":36},"destination":{"latitude":0,"longitude":37}} {}`, nil, 400},
		{strings.Repeat(" ", 4097) + `{}`, nil, 400},
	} {
		var logs bytes.Buffer
		engine := &fakeEngine{err: tc.err}
		handler := NewHandler(fakePinger{}, slog.New(slog.NewTextHandler(&logs, nil)), Options{NavigationOptions: NavigationOptions{Engine: engine}})
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("POST", "/v1/navigation/route", strings.NewReader(tc.body)))
		if response.Code != tc.status {
			t.Errorf("status=%d expected=%d body=%s", response.Code, tc.status, response.Body.String())
		}
		if strings.Contains(logs.String(), "latitude") || strings.Contains(response.Body.String(), "secret upstream") {
			t.Error("sensitive request or upstream error leaked")
		}
		if tc.status == 400 && engine.calls != 0 {
			t.Error("invalid request reached routing engine")
		}
	}
}

func TestTileProxyAndViewportRedaction(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path != "/kenya/12/2460/2060" || r.URL.RawQuery != "" || r.Header.Get("Authorization") != "" {
			t.Errorf("unsafe proxy request: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/x-protobuf")
		_, _ = w.Write([]byte("tile"))
	}))
	defer upstream.Close()
	var logs bytes.Buffer
	handler := NewHandler(fakePinger{}, slog.New(slog.NewTextHandler(&logs, nil)), Options{NavigationOptions: NavigationOptions{TileURL: upstream.URL}})
	req := httptest.NewRequest("GET", "/maps/tiles/12/2460/2060?location=private", nil)
	req.Header.Set("Authorization", "private")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, req)
	if response.Code != 200 || response.Body.String() != "tile" || calls != 1 {
		t.Fatal("proxy failed")
	}
	if strings.Contains(logs.String(), "2460") || strings.Contains(logs.String(), "2060") || strings.Contains(logs.String(), "location") {
		t.Fatal("viewport leaked to logs")
	}
	for _, path := range []string{"/maps/tiles/15/1/1", "/maps/tiles/2/4/0", "/maps/tiles/2/-1/0", "/maps/fonts/Unknown/0-255", "/maps/fonts/Noto%20Sans%20Regular/0-300"} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, httptest.NewRequest("GET", path, nil))
		if response.Code != 400 {
			t.Errorf("accepted invalid asset: %s", path)
		}
	}
	if calls != 1 {
		t.Error("invalid asset reached upstream")
	}
}
