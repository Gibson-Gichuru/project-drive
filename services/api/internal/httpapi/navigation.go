package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
	"strings"
	"time"

	"projectdrive/services/api/internal/navigation"
)

type NavigationOptions struct {
	Engine         navigation.Engine
	TileURL        string
	Places         navigation.PlaceSearcher
	PlaceIndexPath string
}

func registerNavigation(mux *http.ServeMux, options NavigationOptions) {
	registerPlaces(mux, options)
	mux.HandleFunc("POST /v1/navigation/route", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if options.Engine == nil {
			writeAPIError(w, r, 503, "navigation_unavailable", "Navigation is not configured.")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 4096)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		var input navigation.Request
		if decoder.Decode(&input) != nil || !input.Valid() || decoder.Decode(&struct{}{}) != io.EOF {
			writeAPIError(w, r, 400, "invalid_coordinates", "Provide origin and destination latitude/longitude.")
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
		defer cancel()
		route, err := options.Engine.Route(ctx, input)
		if errors.Is(err, navigation.ErrNoRoute) {
			writeAPIError(w, r, 422, "no_route", "No drivable route in the current regional data.")
			return
		}
		if err != nil {
			writeAPIError(w, r, 503, "navigation_unavailable", "Routing is unavailable. Retry when connected to the map server.")
			return
		}
		writeJSON(w, 200, route)
	})
	upstream, err := url.Parse(options.TileURL)
	if err != nil || upstream.Host == "" {
		return
	}
	proxy := httputil.NewSingleHostReverseProxy(upstream)
	proxy.Transport = &http.Transport{ResponseHeaderTimeout: 10 * time.Second, IdleConnTimeout: 60 * time.Second, MaxIdleConnsPerHost: 8}
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, _ error) {
		writeAPIError(w, r, 503, "map_unavailable", "Map service is unavailable.")
	}
	serve := func(w http.ResponseWriter, r *http.Request, path string) {
		r.URL.Path = path
		r.URL.RawPath = ""
		r.URL.RawQuery = ""
		r.Header.Del("Authorization")
		r.Header.Del("Cookie")
		proxy.ServeHTTP(w, r)
	}
	mux.HandleFunc("GET /maps/tiles/{z}/{x}/{y}", func(w http.ResponseWriter, r *http.Request) {
		z, e1 := strconv.Atoi(r.PathValue("z"))
		x, e2 := strconv.Atoi(r.PathValue("x"))
		y, e3 := strconv.Atoi(r.PathValue("y"))
		if e1 != nil || e2 != nil || e3 != nil || z < 0 || z > 14 || x < 0 || y < 0 || x >= 1<<uint(z) || y >= 1<<uint(z) {
			http.Error(w, "invalid tile", 400)
			return
		}
		serve(w, r, "/kenya/"+strconv.Itoa(z)+"/"+strconv.Itoa(x)+"/"+strconv.Itoa(y))
	})
	mux.HandleFunc("GET /maps/fonts/{font}/{range}", func(w http.ResponseWriter, r *http.Request) {
		parts := strings.Split(r.PathValue("range"), "-")
		if r.PathValue("font") != "Noto Sans Regular" || len(parts) != 2 {
			http.Error(w, "unknown glyph range", 400)
			return
		}
		start, e1 := strconv.Atoi(parts[0])
		end, e2 := strconv.Atoi(parts[1])
		if e1 != nil || e2 != nil || start < 0 || end > 65535 || start%256 != 0 || end != start+255 {
			http.Error(w, "invalid glyph range", 400)
			return
		}
		serve(w, r, "/font/Noto Sans Regular/"+strconv.Itoa(start)+"-"+strconv.Itoa(end))
	})
}

func writeAPIError(w http.ResponseWriter, r *http.Request, status int, code, message string) {
	id, _ := r.Context().Value(requestIDKey{}).(string)
	writeJSON(w, status, map[string]string{"code": code, "message": message, "request_id": id})
}
