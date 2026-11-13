package navigation

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestDecodePolyline6(t *testing.T) {
	points, err := decodePolyline6("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
	if err != nil || len(points) != 3 || points[0] != (Point{3.85, -12.02}) || points[2] != (Point{4.3252, -12.6453}) {
		t.Fatalf("unexpected decode: %v %v", points, err)
	}
	for _, shape := range []string{"_", "??_", "!", strings.Repeat("~", 30)} {
		if _, err := decodePolyline6(shape); err == nil {
			t.Errorf("accepted invalid shape %q", shape)
		}
	}
}

func TestValhallaAdapter(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != "/route" {
			t.Error("incorrect upstream method/path")
		}
		var request struct {
			Locations []struct{ Lat, Lon float64 }
			Costing   string
			Units     string
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil || len(request.Locations) != 2 || request.Locations[0].Lat != -1.28 || request.Costing != "auto" || request.Units != "kilometers" {
			t.Error("incorrect upstream request")
		}
		_, _ = w.Write([]byte("{\"trip\":{\"status\":0,\"summary\":{\"length\":3.2,\"time\":600},\"legs\":[{\"shape\":\"_p~iF~ps|U_ulLnnqC_mqNvxq`@\",\"maneuvers\":[{\"instruction\":\"Turn left\",\"begin_shape_index\":0,\"end_shape_index\":2,\"length\":3.2,\"time\":600}]}]}}"))
	}))
	defer server.Close()
	route, err := (Valhalla{URL: server.URL}).Route(context.Background(), Request{&Point{-1.28, 36.81}, &Point{-1.31, 36.82}})
	if err != nil || len(route.Geometry) != 3 || route.DistanceM != 3200 || route.DurationS != 600 || route.Maneuvers[0].EndIndex != 2 {
		t.Fatalf("unexpected route %+v %v", route, err)
	}
}

func TestValhallaFailureResponses(t *testing.T) {
	for _, tc := range []struct {
		status  int
		body    string
		noRoute bool
	}{
		{400, `{"error":"coordinates must never enter logs"}`, true},
		{503, `{}`, false}, {200, `invalid`, false}, {200, `{"trip":{"status":0,"legs":[]}}`, false},
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(tc.body))
		}))
		_, err := (Valhalla{URL: server.URL}).Route(context.Background(), Request{&Point{-1, 36}, &Point{-2, 37}})
		server.Close()
		if err == nil || errors.Is(err, ErrNoRoute) != tc.noRoute {
			t.Errorf("unexpected error %v", err)
		}
		if strings.Contains(err.Error(), "coordinates must") {
			t.Error("upstream body leaked")
		}
	}
}
