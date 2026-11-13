package navigation

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func testIndex(t *testing.T, data string) *Gazetteer {
	t.Helper()
	p := filepath.Join(t.TempDir(), "index.json")
	if err := os.WriteFile(p, []byte(data), 0600); err != nil {
		t.Fatal(err)
	}
	g, err := LoadGazetteer(p)
	if err != nil {
		t.Fatal(err)
	}
	return g
}

const indexFixture = `{"version":1,"region":"Kenya","places":[{"id":"node/1","name":"Nakuru","kind":"city","locality":"Kenya","aliases":"Nax","latitude":-0.28,"longitude":36.07},{"id":"node/2","name":"Nakuru Museum","kind":"museum","locality":"Nakuru","latitude":-0.29,"longitude":36.08},{"id":"way/3","name":"Nakuru Museum","kind":"museum","locality":"Nakuru","latitude":-0.30,"longitude":36.09}]}`

func TestSearchRankingAliasesEmptyAndDeduplication(t *testing.T) {
	g := testIndex(t, indexFixture)
	for _, tc := range []struct {
		query, name string
		count       int
	}{{"NAKURU", "Nakuru", 2}, {"nax", "Nakuru", 1}, {"museum nakuru", "Nakuru Museum", 1}, {"missing landmark", "", 0}} {
		results, err := g.Search(context.Background(), tc.query)
		if err != nil || len(results) != tc.count {
			t.Fatalf("search %q: count=%d err=%v", tc.query, len(results), err)
		}
		if tc.count > 0 && results[0].Name != tc.name {
			t.Fatal("ranking or aliases incorrect")
		}
	}
}
func TestIndexValidationAndCancellation(t *testing.T) {
	for _, data := range []string{`{}`, `{"version":2,"region":"Kenya","places":[]}`, `{"version":1,"region":"Kenya","places":[{"id":"x","name":"Bad","latitude":91}]}`, indexFixture + ` {}`} {
		p := filepath.Join(t.TempDir(), "bad.json")
		os.WriteFile(p, []byte(data), 0600)
		if _, err := LoadGazetteer(p); err == nil {
			t.Fatal("invalid index accepted")
		}
	}
	g := testIndex(t, indexFixture)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := g.Search(ctx, "Nakuru"); err == nil {
		t.Fatal("cancellation ignored")
	}
	for i := 0; i < 4; i++ {
		g.slots <- struct{}{}
	}
	if _, err := g.Search(context.Background(), "Nakuru"); err != ErrSearchBusy {
		t.Fatal("concurrency bound ignored")
	}
}

// Opt-in acceptance against the actual regional data release, never a user's location.
func TestRegionalIndexAcceptance(t *testing.T) {
	path := os.Getenv("PLACE_INDEX_FIXTURE")
	if path == "" {
		t.Skip("regional index not supplied")
	}
	g, err := LoadGazetteer(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"Nakuru", "Nairobi", "Mombasa", "Naivasha", "Uhuru Park"} {
		result, err := g.Search(context.Background(), name)
		if err != nil || len(result) == 0 {
			t.Fatalf("regional name %s missing or unavailable", name)
		}
	}
	t.Logf("validated regional index with %d named entries", len(g.places))
}
