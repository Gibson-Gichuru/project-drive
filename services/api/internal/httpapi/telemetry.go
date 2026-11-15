package httpapi

import (
	"context"
	"errors"
	"io"
	"net/http"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/telemetry"
	"time"
)

func registerTelemetry(mux *http.ServeMux, auth TripOptions, store *telemetry.Store) {
	for _, method := range []string{"POST", "GET"} {
		path := "/v1/trips/{trip}/telemetry"
		if method == "GET" {
			path = "/v1/trips/{trip}/summary"
		}
		mux.HandleFunc(method+" "+path, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Cache-Control", "no-store")
			if store == nil {
				unauthorized(w, r)
				return
			}
			session, err := auth.authenticate(r)
			if err != nil {
				authenticationError(w, r, err)
				return
			}
			user := session.User
			trip := r.PathValue("trip")
			if !identity.UUID(trip) || r.URL.RawQuery != "" {
				writeAPIError(w, r, 400, "invalid_request", "valid trip UUID and no query required")
				return
			}
			ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
			defer cancel()
			var result any
			status := 200
			if r.Method == "POST" {
				data, readErr := io.ReadAll(http.MaxBytesReader(w, r.Body, 65536))
				if readErr != nil {
					writeAPIError(w, r, 400, "invalid_request", "batch exceeds 64 KiB")
					return
				}
				b, decodeErr := telemetry.Decode(data, time.Now())
				if decodeErr != nil {
					writeAPIError(w, r, 400, "invalid_request", "invalid derived telemetry batch")
					return
				}
				err = store.Upload(ctx, user.ID, trip, b)
				status = 202
				result = map[string]string{"status": "accepted", "recording_id": b.ID}
			} else {
				var recordings []telemetry.Recording
				recordings, err = store.Summary(ctx, user.ID, trip)
				result = map[string]any{"recordings": recordings, "estimates": true}
			}
			if err != nil {
				var domain telemetry.Error
				if errors.As(err, &domain) {
					status = 409
					if domain == "not_found" {
						status = 404
					}
					writeAPIError(w, r, status, string(domain), "telemetry operation refused")
				} else {
					writeAPIError(w, r, 503, "unavailable", "telemetry unavailable; retry the same recording")
				}
				return
			}
			writeJSON(w, status, result)
		})
	}
}
