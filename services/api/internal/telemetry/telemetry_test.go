package telemetry

import (
	"encoding/json"
	"math"
	"strings"
	"testing"
	"time"
)

func sample(now time.Time) Batch {
	s := Summary{StartedAt: now.Add(-time.Minute), EndedAt: now, Duration: 60, Covered: 60, Distance: 300, MeanSpeed: 5, MaxSpeed: 10, SpeedSamples: 3, GPSSamples: 3, MeanAccuracy: 12, IMUSamples: 100, RotationSamples: 100, Acceleration: 1, Source: "phone_gps_imu_v1", Orientation: "available", Calibration: "uncalibrated", Confidence: .35}
	return Batch{Version: 1, ID: "00000000-0000-4000-8000-000000000001", Summary: s, Events: []Event{{Type: "acceleration_candidate", Magnitude: 4, Confidence: .35, Source: "gps_delta", Accuracy: 12, Elapsed: 10000, OccurredAt: s.StartedAt.Add(10 * time.Second), Orientation: "available", Calibration: "uncalibrated"}}}
}
func TestValidation(t *testing.T) {
	now := time.Now().UTC()
	b := sample(now)
	data, _ := json.Marshal(b)
	if _, err := Decode(data, now); err != nil {
		t.Fatal(err)
	}
	for _, mutation := range []func(*Batch){
		func(b *Batch) { b.Summary.Distance = math.NaN() }, func(b *Batch) { b.Summary.Covered = 61 }, func(b *Batch) { b.Summary.MaxSpeed = 101 }, func(b *Batch) { b.Summary.Acceleration = 2 }, func(b *Batch) { b.Summary.Calibration = "calibrated" }, func(b *Batch) { b.Summary.EndedAt = now.Add(10 * time.Minute) }, func(b *Batch) { b.Events[0].Confidence = 1 }, func(b *Batch) { b.Events[0].Elapsed = -1 }, func(b *Batch) { b.Events[0].Source = "obd" }, func(b *Batch) { b.Events[0].Accuracy = 1000 }, func(b *Batch) { b.Events = make([]Event, 101) },
	} {
		v := sample(now)
		mutation(&v)
		if v.Validate(now) == nil {
			t.Fatal("invalid estimate accepted")
		}
	}
	for _, bad := range []string{`null`, strings.Replace(string(data), `"distance_m":300,`, "", 1), strings.Replace(string(data), `"version":1`, `"version":1,"latitude":-1.2`, 1), string(data) + ` {}`, strings.Replace(string(data), `"events":[`, `"events":[{"user_id":"x"},`, 1)} {
		if _, err := Decode([]byte(bad), now); err == nil {
			t.Fatal("invalid JSON accepted")
		}
	}
}
