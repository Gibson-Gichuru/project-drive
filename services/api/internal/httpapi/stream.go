package httpapi

import (
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/coder/websocket"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/tracking"
)

func registerStream(mux *http.ServeMux, options TripOptions, hub *tracking.Hub) {
	mux.HandleFunc("GET /v1/trips/{trip}/stream", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		raw := r.Header.Get("Authorization")
		if hub == nil || len(options.PublicKey) != ed25519.PublicKeySize || !strings.HasPrefix(raw, "Bearer ") || len(raw) > 4096 {
			unauthorized(w, r)
			return
		}
		session, err := identity.Authenticate(options.PublicKey, strings.TrimPrefix(raw, "Bearer "))
		if err != nil {
			unauthorized(w, r)
			return
		}
		if r.URL.RawQuery != "" {
			writeAPIError(w, r, 400, "invalid_request", "stream query credentials and parameters are not accepted")
			return
		}
		trip := r.PathValue("trip")
		if !identity.UUID(trip) {
			writeAPIError(w, r, 400, "invalid_request", "valid trip UUID required")
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		subscription, err := hub.Subscribe(ctx, trip, session)
		cancel()
		if err != nil {
			status, code := 503, "unavailable"
			switch {
			case errors.Is(err, tracking.ErrNotFound):
				status, code = 404, "not_found"
			case errors.Is(err, tracking.ErrNotSharing):
				status, code = 403, "sharing_disabled"
			case errors.Is(err, tracking.ErrNotActive):
				status, code = 409, "trip_not_active"
			case errors.Is(err, tracking.ErrCapacity):
				status, code = 429, "tracking_capacity"
			case errors.Is(err, tracking.ErrStopped):
				unauthorized(w, r)
				return
			}
			writeAPIError(w, r, status, code, strings.ReplaceAll(code, "_", " "))
			return
		}
		defer hub.Unsubscribe(subscription)
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{"project-drive.v1"}, CompressionMode: websocket.CompressionDisabled})
		if err != nil {
			return
		}
		defer conn.CloseNow()
		limits := hub.Limits()
		conn.SetReadLimit(limits.MessageBytes)
		streamCtx, stop := context.WithCancel(r.Context())
		defer stop()
		writerDone := make(chan struct{})
		go func() {
			defer close(writerDone)
			defer conn.CloseNow()
			writeStream(streamCtx, conn, hub, subscription)
		}()
		for {
			kind, data, err := conn.Read(streamCtx)
			if err != nil {
				break
			}
			if kind != websocket.MessageText {
				break
			}
			updateCtx, done := context.WithTimeout(streamCtx, 5*time.Second)
			err = hub.Publish(updateCtx, subscription, data)
			done()
			if err != nil && !errors.Is(err, tracking.ErrOlder) {
				break
			}
		}
		// Unsubscribe first: this invalidates queued coordinates before canceling I/O.
		hub.Unsubscribe(subscription)
		stop()
		_ = conn.CloseNow()
		<-writerDone
	})
}
func writeStream(ctx context.Context, conn *websocket.Conn, hub *tracking.Hub, s *tracking.Subscription) {
	limits := hub.Limits()
	ping := time.NewTicker(limits.PingInterval)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case reason := <-s.Stopped():
			kind := "error"
			if reason == "trip_ended" {
				kind = "trip.ended"
			}
			closeCtx, cancel := context.WithTimeout(ctx, limits.WriteTimeout)
			data, _ := json.Marshal(map[string]any{"type": kind, "version": 1, "payload": map[string]string{"code": reason}})
			_ = conn.Write(closeCtx, websocket.MessageText, data)
			cancel()
			return
		case delivery := <-s.Events():
			sendCtx, cancel := context.WithTimeout(ctx, limits.WriteTimeout)
			// Write uses this same bounded context, not the unbounded stream context.
			err := hub.Deliver(sendCtx, s, delivery, func(event tracking.Event) error {
				raw, err := json.Marshal(event)
				if err != nil {
					return err
				}
				if len(raw) > 64*1024 {
					return tracking.ErrCapacity
				}
				return conn.Write(sendCtx, websocket.MessageText, raw)
			})
			cancel()
			if err != nil {
				return
			}
		case <-ping.C:
			pingCtx, cancel := context.WithTimeout(ctx, limits.PongTimeout)
			err := conn.Ping(pingCtx)
			cancel()
			if err != nil {
				return
			}
		}
	}
}
