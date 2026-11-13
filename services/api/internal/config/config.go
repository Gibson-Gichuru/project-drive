package config

import (
	"errors"
	"net/url"
	"os"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/tracking"
)

type Config struct {
	Addr             string
	DatabaseURL      string
	Environment      string
	TileURL          string
	NavigationURL    string
	DevAuthPublicKey string
	Tracking         tracking.Limits
}

func Load() (Config, error) {
	cfg := Config{
		Addr:             valueOr("API_ADDR", ":8080"),
		DatabaseURL:      os.Getenv("DATABASE_URL"),
		Environment:      valueOr("APP_ENV", "local"),
		TileURL:          os.Getenv("MAP_TILE_URL"),
		NavigationURL:    os.Getenv("NAVIGATION_URL"),
		DevAuthPublicKey: os.Getenv("DEV_AUTH_PUBLIC_KEY"),
	}
	var err error
	cfg.Tracking, err = tracking.LimitsFromEnv(os.Getenv)
	if err != nil {
		return Config{}, err
	}
	if cfg.DevAuthPublicKey != "" {
		// Require explicit intent: the default local environment is insufficient for auth.
		if _, err := identity.PublicKey(os.Getenv("APP_ENV"), cfg.DevAuthPublicKey); err != nil {
			return Config{}, err
		}
	}
	if cfg.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
	}
	if cfg.Environment != "local" && cfg.Environment != "test" && cfg.Environment != "homelab" && cfg.Environment != "production" {
		return Config{}, errors.New("APP_ENV must be local, test, homelab, or production")
	}
	if (cfg.TileURL == "") != (cfg.NavigationURL == "") {
		return Config{}, errors.New("MAP_TILE_URL and NAVIGATION_URL must be configured together")
	}
	if cfg.TileURL != "" {
		if cfg.Environment == "production" {
			return Config{}, errors.New("M1 navigation spike is restricted to local, test, or private homelab environments")
		}
		for _, address := range []string{cfg.TileURL, cfg.NavigationURL} {
			u, err := url.Parse(address)
			if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
				return Config{}, errors.New("map service URL must be an HTTP(S) origin without credentials, path, query or fragment")
			}
		}
	}
	return cfg, nil
}

func valueOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
