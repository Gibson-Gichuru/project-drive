package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"projectdrive/services/api/internal/navigation"
	"time"
)

func registerPlaces(mux *http.ServeMux, options NavigationOptions) {
	mux.HandleFunc("POST /v1/navigation/search", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		var input struct {
			Query string `json:"query"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 1024)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		if decoder.Decode(&input) != nil || !navigation.ValidPlaceQuery(input.Query) || decoder.Decode(&struct{}{}) != io.EOF {
			writeAPIError(w, r, 400, "invalid_query", "Enter a place name between 2 and 120 characters.")
			return
		}
		if options.Places == nil {
			writeAPIError(w, r, 503, "search_unavailable", "Place search is unavailable. You can still choose a map pin.")
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), time.Second)
		defer cancel()
		places, err := options.Places.Search(ctx, input.Query)
		if errors.Is(err, navigation.ErrSearchBusy) {
			writeAPIError(w, r, 429, "search_busy", "Place search is busy. Please retry shortly.")
			return
		}
		if err != nil {
			writeAPIError(w, r, 503, "search_unavailable", "Place search is temporarily unavailable.")
			return
		}
		if places == nil {
			places = []navigation.Place{}
		}
		writeJSON(w, 200, map[string]any{"places": places, "region": "Kenya", "attribution": "© OpenStreetMap contributors"})
	})
	mux.HandleFunc("GET /maps/places-index", func(w http.ResponseWriter, r *http.Request) {
		if options.PlaceIndexPath == "" {
			http.NotFound(w, r)
			return
		}
		// Public ODbL derived database, offered in machine-readable form. Static configured path only.
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Disposition", `attachment; filename="kenya-places.json"`)
		w.Header().Set("Cache-Control", "public, max-age=3600")
		http.ServeFile(w, r, options.PlaceIndexPath)
	})
}
