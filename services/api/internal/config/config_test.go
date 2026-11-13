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
