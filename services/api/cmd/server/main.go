package main

import (
	"context"
	"crypto/ed25519"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"projectdrive/services/api/internal/config"
	"projectdrive/services/api/internal/httpapi"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/navigation"
	"projectdrive/services/api/internal/telemetry"
	"projectdrive/services/api/internal/tracking"
	"projectdrive/services/api/internal/trips"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	cfg, err := config.Load()
	if err != nil {
		logger.Error("invalid configuration", "error", err)
		os.Exit(2)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		logger.Error("database configuration failed", "error", err)
		os.Exit(1)
	}
	defer db.Close()

	var engine navigation.Engine
	if cfg.NavigationURL != "" {
		engine = navigation.Valhalla{URL: cfg.NavigationURL}
	}
	var publicKey ed25519.PublicKey
	if cfg.DevAuthPublicKey != "" {
		publicKey, err = identity.PublicKey(cfg.Environment, cfg.DevAuthPublicKey)
		if err != nil {
			logger.Error("identity configuration failed")
			os.Exit(2)
		}
	}
	hub, err := tracking.New(tracking.PostgresAuthorizer{DB: db}, cfg.Tracking)
	if err != nil {
		logger.Error("tracking configuration failed")
		os.Exit(2)
	}
	defer hub.Close()
	telemetryStore := &telemetry.Store{DB: db}
	go func() {
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for {
			purgeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			if err := telemetryStore.Purge(purgeCtx); err != nil && ctx.Err() == nil {
				logger.Warn("telemetry expiry cleanup unavailable")
			}
			cancel()
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
	coordinator := tracking.Coordinator{Hub: hub, Base: trips.Store{DB: db}}
	server := &http.Server{
		Addr:              cfg.Addr,
		Handler:           httpapi.NewHandler(db, logger, httpapi.Options{NavigationOptions: httpapi.NavigationOptions{Engine: engine, TileURL: cfg.TileURL}, Trips: httpapi.TripOptions{Service: coordinator, PublicKey: publicKey}, Tracking: hub, Telemetry: telemetryStore}),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       20 * time.Second,
		WriteTimeout:      30 * time.Second,
	}

	serveErr := make(chan error, 1)
	go func() {
		logger.Info("api listening", "addr", cfg.Addr, "environment", cfg.Environment)
		serveErr <- server.ListenAndServe()
	}()

	select {
	case err := <-serveErr:
		if !errors.Is(err, http.ErrServerClosed) {
			logger.Error("api stopped unexpectedly", "error", err)
			os.Exit(1)
		}
	case <-ctx.Done():
		hub.Close()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			logger.Error("graceful shutdown failed", "error", err)
			os.Exit(1)
		}
		logger.Info("api stopped")
	}
}
