package navigation

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"math"
)

var ErrNoRoute = errors.New("no route in the regional graph")

type Point struct {
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
}

func (p *Point) UnmarshalJSON(data []byte) error {
	var fields struct {
		Latitude  *float64 `json:"latitude"`
		Longitude *float64 `json:"longitude"`
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&fields); err != nil || fields.Latitude == nil || fields.Longitude == nil {
		return errors.New("latitude and longitude are required")
	}
	p.Latitude, p.Longitude = *fields.Latitude, *fields.Longitude
	if !p.Valid() {
		return errors.New("invalid coordinates")
	}
	return nil
}

func (p Point) Valid() bool {
	return !math.IsNaN(p.Latitude) && !math.IsNaN(p.Longitude) &&
		!math.IsInf(p.Latitude, 0) && !math.IsInf(p.Longitude, 0) &&
		p.Latitude >= -90 && p.Latitude <= 90 && p.Longitude >= -180 && p.Longitude <= 180
}

type Request struct {
	Origin      *Point `json:"origin"`
	Destination *Point `json:"destination"`
}

func (r Request) Valid() bool {
	return r.Origin != nil && r.Destination != nil && r.Origin.Valid() && r.Destination.Valid()
}

type Maneuver struct {
	Instruction string  `json:"instruction"`
	StartIndex  int     `json:"start_index"`
	EndIndex    int     `json:"end_index"`
	DistanceM   float64 `json:"distance_m"`
	DurationS   float64 `json:"duration_s"`
}

type Route struct {
	Geometry  []Point    `json:"geometry"`
	DistanceM float64    `json:"distance_m"`
	DurationS float64    `json:"duration_s"`
	Maneuvers []Maneuver `json:"maneuvers"`
}

// Engine prevents provider-specific routing responses from entering client contracts.
type Engine interface {
	Route(context.Context, Request) (Route, error)
}
