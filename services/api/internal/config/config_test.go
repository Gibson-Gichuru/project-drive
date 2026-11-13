package config

import "testing"

func TestNavigationSpikeConfiguration(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://test")
	for _, tc := range []struct {
		env, tile, route string
		valid            bool
	}{
		{"local", "", "", true}, {"homelab", "http://tiles:3000", "http://router:8002", true},
		{"production", "http://tiles:3000", "http://router:8002", false},
		{"homelab", "http://tiles:3000", "", false},
		{"homelab", "http://user:password@tiles:3000", "http://router:8002", false},
		{"homelab", "file:///maps", "http://router:8002", false},
	} {
		t.Setenv("APP_ENV", tc.env)
		t.Setenv("MAP_TILE_URL", tc.tile)
		t.Setenv("NAVIGATION_URL", tc.route)
		_, err := Load()
		if (err == nil) != tc.valid {
			t.Errorf("environment %s expected valid=%v got %v", tc.env, tc.valid, err)
		}
	}
}

func TestDevelopmentIdentityEnvironmentGuard(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://test")
	t.Setenv("MAP_TILE_URL", "")
	t.Setenv("NAVIGATION_URL", "")
	// A syntactically valid public key is enough to exercise configuration boundaries.
	t.Setenv("DEV_AUTH_PUBLIC_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
	for _, env := range []string{"", "production", "homelab", "invalid", "local", "test"} {
		t.Setenv("APP_ENV", env)
		_, err := Load()
		want := env == "local" || env == "test"
		if (err == nil) != want {
			t.Fatalf("environment %q: accepted=%v", env, err == nil)
		}
	}
	t.Setenv("APP_ENV", "local")
	t.Setenv("DEV_AUTH_PUBLIC_KEY", "bad")
	if _, err := Load(); err == nil {
		t.Fatal("invalid key accepted")
	}
}

func TestPlaceIndexRequiresMapStackAndAbsolutePath(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://test")
	t.Setenv("APP_ENV", "local")
	t.Setenv("DEV_AUTH_PUBLIC_KEY", "")
	for _, tc := range []struct {
		path, tile, route string
		valid             bool
	}{{"places.json", "http://tiles:3000", "http://router:8002", false}, {"/maps/places.json", "", "", false}, {"/maps/places.json", "http://tiles:3000", "http://router:8002", true}} {
		t.Setenv("PLACE_INDEX_PATH", tc.path)
		t.Setenv("MAP_TILE_URL", tc.tile)
		t.Setenv("NAVIGATION_URL", tc.route)
		_, err := Load()
		if (err == nil) != tc.valid {
			t.Fatalf("index config accepted=%v want=%v", err == nil, tc.valid)
		}
	}
}
