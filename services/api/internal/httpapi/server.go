package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"
)

type Pinger interface {
	Ping(context.Context) error
}

func NewHandler(db Pinger, logger *slog.Logger, options ...NavigationOptions) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		if err := db.Ping(ctx); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "not_ready"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
	})
	if len(options) > 0 {
		registerNavigation(mux, options[0])
	}
	return requestID(logging(logger, mux))
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func requestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		idBytes := make([]byte, 12)
		if _, err := rand.Read(idBytes); err != nil {
			http.Error(w, "request initialization failed", http.StatusInternalServerError)
			return
		}
		id := hex.EncodeToString(idBytes)
		w.Header().Set("X-Request-ID", id)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), requestIDKey{}, id)))
	})
}

type requestIDKey struct{}

func logging(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		path := r.URL.Path
		next.ServeHTTP(w, r)
		id, _ := r.Context().Value(requestIDKey{}).(string)
		// Tile identifiers reveal the map viewport; log only the endpoint family.
		if len(path) >= 6 && path[:6] == "/maps/" {
			path = "/maps/{asset}"
		}
		logger.Info("http request", "method", r.Method, "path", path, "request_id", id, "duration_ms", time.Since(started).Milliseconds())
	})
}
