package config

import (
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/tracking"
)

type Config struct {
	Addr             string
	DatabaseURL      string
	Environment      string
	TileURL          string
	NavigationURL    string
	PlaceIndexPath   string
	DevAuthPublicKey string
	SupabaseURL      string
	SupabaseIssuer   string
	AuthEmailEnabled bool
	Tracking         tracking.Limits
}

func Load() (Config, error) {
	cfg := Config{
		Addr:             valueOr("API_ADDR", ":8080"),
		DatabaseURL:      os.Getenv("DATABASE_URL"),
		Environment:      valueOr("APP_ENV", "local"),
		TileURL:          os.Getenv("MAP_TILE_URL"),
		NavigationURL:    os.Getenv("NAVIGATION_URL"),
		PlaceIndexPath:   os.Getenv("PLACE_INDEX_PATH"),
		DevAuthPublicKey: os.Getenv("DEV_AUTH_PUBLIC_KEY"),
		SupabaseURL:      os.Getenv("SUPABASE_AUTH_URL"),
		SupabaseIssuer:   os.Getenv("SUPABASE_AUTH_ISSUER"),
		AuthEmailEnabled: os.Getenv("AUTH_EMAIL_ENABLED") == "true",
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
	if cfg.SupabaseURL != "" || cfg.SupabaseIssuer != "" {
		if cfg.DevAuthPublicKey != "" {
			return Config{}, errors.New("development and production identity modes are mutually exclusive")
		}
		if _, err := identity.NewSupabase(cfg.SupabaseURL, cfg.SupabaseIssuer); err != nil {
			return Config{}, err
		}
		issuer, _ := url.Parse(cfg.SupabaseIssuer)
		if (cfg.Environment == "homelab" || cfg.Environment == "production") && issuer.Scheme != "https" {
			return Config{}, errors.New("public authentication issuer must use HTTPS")
		}
	}
	if os.Getenv("AUTH_EMAIL_ENABLED") != "" && os.Getenv("AUTH_EMAIL_ENABLED") != "true" && os.Getenv("AUTH_EMAIL_ENABLED") != "false" {
		return Config{}, errors.New("AUTH_EMAIL_ENABLED must be true or false")
	}
	if cfg.AuthEmailEnabled && cfg.SupabaseURL == "" {
		return Config{}, errors.New("email authentication requires Supabase")
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
	if cfg.PlaceIndexPath != "" && (cfg.NavigationURL == "" || !filepath.IsAbs(cfg.PlaceIndexPath)) {
		return Config{}, errors.New("PLACE_INDEX_PATH requires the map stack and an absolute index path")
	}
	if cfg.TileURL != "" {
		if cfg.Environment == "production" {
			return Config{}, errors.New("M1 navigation spike is restricted to local, test, or prototype homelab environments")
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
