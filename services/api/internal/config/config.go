package config

import (
	"errors"
	"os"
)

type Config struct {
	Addr        string
	DatabaseURL string
	Environment string
}

func Load() (Config, error) {
	cfg := Config{
		Addr:        valueOr("API_ADDR", ":8080"),
		DatabaseURL: os.Getenv("DATABASE_URL"),
		Environment: valueOr("APP_ENV", "local"),
	}
	if cfg.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
	}
	if cfg.Environment != "local" && cfg.Environment != "test" && cfg.Environment != "homelab" && cfg.Environment != "production" {
		return Config{}, errors.New("APP_ENV must be local, test, homelab, or production")
	}
	return cfg, nil
}

func valueOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
