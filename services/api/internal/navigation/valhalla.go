package navigation

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type Valhalla struct {
	URL    string
	Client *http.Client
}

func (v Valhalla) Route(ctx context.Context, input Request) (Route, error) {
	if !input.Valid() {
		return Route{}, errors.New("invalid coordinates")
	}
	payload := map[string]any{
		"locations": []map[string]float64{
			{"lat": input.Origin.Latitude, "lon": input.Origin.Longitude},
			{"lat": input.Destination.Latitude, "lon": input.Destination.Longitude},
		},
		"costing":  "auto",
		"units":    "kilometers",
		"language": "en-US",
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return Route{}, errors.New("invalid route request")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimRight(v.URL, "/")+"/route", bytes.NewReader(body))
	if err != nil {
		return Route{}, errors.New("routing configuration unavailable")
	}
	req.Header.Set("Content-Type", "application/json")
	client := v.Client
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Second}
	}
	resp, err := client.Do(req)
	if err != nil {
		return Route{}, errors.New("routing service unavailable")
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusBadRequest {
		return Route{}, ErrNoRoute
	}
	if resp.StatusCode != http.StatusOK {
		return Route{}, errors.New("routing service unavailable")
	}
	var output struct {
		Trip struct {
			Status  int `json:"status"`
			Summary struct {
				Length float64 `json:"length"`
				Time   float64 `json:"time"`
			} `json:"summary"`
			Legs []struct {
				Shape     string `json:"shape"`
				Maneuvers []struct {
					Instruction string  `json:"instruction"`
					Begin       int     `json:"begin_shape_index"`
					End         int     `json:"end_shape_index"`
					Length      float64 `json:"length"`
					Time        float64 `json:"time"`
				} `json:"maneuvers"`
			} `json:"legs"`
		} `json:"trip"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 8<<20)).Decode(&output); err != nil {
		return Route{}, errors.New("invalid routing response")
	}
	trip := output.Trip
	if trip.Status != 0 || len(trip.Legs) != 1 {
		return Route{}, errors.New("invalid routing response")
	}
	geometry, err := decodePolyline6(trip.Legs[0].Shape)
	if err != nil || len(geometry) < 2 {
		return Route{}, errors.New("invalid route geometry")
	}
	if trip.Summary.Length < 0 || trip.Summary.Time < 0 {
		return Route{}, errors.New("invalid route summary")
	}
	route := Route{Geometry: geometry, DistanceM: trip.Summary.Length * 1000, DurationS: trip.Summary.Time}
	for _, m := range trip.Legs[0].Maneuvers {
		if m.Begin < 0 || m.End < m.Begin || m.End >= len(geometry) || m.Instruction == "" || m.Length < 0 || m.Time < 0 {
			return Route{}, errors.New("invalid route maneuver")
		}
		route.Maneuvers = append(route.Maneuvers, Maneuver{m.Instruction, m.Begin, m.End, m.Length * 1000, m.Time})
	}
	if len(route.Maneuvers) == 0 {
		return Route{}, errors.New("route has no guidance")
	}
	return route, nil
}

// Valhalla's default route shape is an encoded polyline with six decimal places.
func decodePolyline6(shape string) ([]Point, error) {
	points := make([]Point, 0)
	index, lat, lon := 0, int64(0), int64(0)
	next := func() (int64, error) {
		var result int64
		for shift := uint(0); shift <= 60 && index < len(shape); shift += 5 {
			b := int64(shape[index]) - 63
			index++
			if b < 0 || b > 63 {
				return 0, errors.New("invalid polyline character")
			}
			result |= (b & 31) << shift
			if b < 32 {
				if result&1 != 0 {
					return ^(result >> 1), nil
				}
				return result >> 1, nil
			}
		}
		return 0, errors.New("truncated polyline")
	}
	for index < len(shape) {
		dlat, err := next()
		if err != nil {
			return nil, err
		}
		dlon, err := next()
		if err != nil {
			return nil, err
		}
		lat += dlat
		lon += dlon
		point := Point{float64(lat) / 1e6, float64(lon) / 1e6}
		if !point.Valid() {
			return nil, fmt.Errorf("polyline coordinate out of range")
		}
		points = append(points, point)
		if len(points) > 100000 {
			return nil, errors.New("route too large")
		}
	}
	return points, nil
}
