package reports

import (
	"encoding/json"
	"math"
	"testing"
	"time"
)

func TestObservationPolicy(t *testing.T) {
	now := time.Now()
	p := Point{-1.28, 36.82}
	base := Input{Category: "obstacle", Location: &p, ObservedAt: now}
	if !base.Valid(now) {
		t.Fatal("fresh observation rejected")
	}
	for _, category := range []string{"accident", "breakdown", "camera", "speed_bump", "closure", "obstacle"} {
		if Lifetime(category) <= 0 {
			t.Fatal(category)
		}
	}
	cases := []Input{base, base, base, base, base, base}
	cases[0].Category = "unknown"
	cases[1].Location = nil
	cases[2].Note = "bad\ncontrol"
	cases[3].ObservedAt = now.Add(time.Minute)
	cases[4].ObservedAt = now.Add(-3 * time.Hour)
	cases[5].Location = &Point{math.NaN(), 0}
	for _, in := range cases {
		if in.Valid(now) {
			t.Fatal("invalid observation accepted")
		}
	}
	for _, s := range []string{`{}`, `null`, `{"latitude":1}`, `{"latitude":1,"longitude":2,"extra":3}`, `{"latitude":91,"longitude":2}`} {
		var point Point
		if json.Unmarshal([]byte(s), &point) == nil {
			t.Fatal("invalid point", s)
		}
	}
}
