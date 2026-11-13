package main

import (
	"context"
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
	"projectdrive/services/api/internal/navigation"
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
	server := &http.Server{
		Addr:              cfg.Addr,
		Handler:           httpapi.NewHandler(db, logger, httpapi.NavigationOptions{Engine: engine, TileURL: cfg.TileURL}),
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
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			logger.Error("graceful shutdown failed", "error", err)
			os.Exit(1)
		}
		logger.Info("api stopped")
	}
}
