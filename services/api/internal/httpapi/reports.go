package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/reports"
	"time"
)

type ReportService interface {
	Create(context.Context, identity.User, reports.Input, string) (reports.Report, error)
	Nearby(context.Context, identity.User, reports.Query) ([]reports.Report, error)
	Change(context.Context, identity.User, string, int, bool) (reports.Report, error)
}

func registerReports(mux *http.ServeMux, auth TripOptions, service ReportService) {
	for pattern, action := range map[string]string{"POST /v1/reports": "create", "POST /v1/reports/nearby": "nearby", "PUT /v1/reports/{report}/vote": "vote", "DELETE /v1/reports/{report}": "withdraw"} {
		mux.HandleFunc(pattern, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Cache-Control", "no-store")
			session, err := auth.authenticate(r)
			if err != nil {
				authenticationError(w, r, err)
				return
			}
			if service == nil {
				writeAPIError(w, r, 503, "reports_unavailable", "road reports unavailable")
				return
			}
			ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
			defer cancel()
			decode := func(v any) bool {
				d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096))
				d.DisallowUnknownFields()
				if d.Decode(v) != nil {
					return false
				}
				var extra any
				return d.Decode(&extra) == io.EOF
			}
			var value any
			switch action {
			case "create":
				var in reports.Input
				if !decode(&in) {
					err = reports.ErrInvalid
					break
				}
				value, err = service.Create(ctx, session.User, in, r.Header.Get("Idempotency-Key"))
			case "nearby":
				var q reports.Query
				if !decode(&q) {
					err = reports.ErrInvalid
					break
				}
				var list []reports.Report
				list, err = service.Nearby(ctx, session.User, q)
				value = map[string]any{"reports": list, "server_time": time.Now().UTC(), "limit": 100}
			case "vote":
				var in struct {
					Vote int `json:"vote"`
				}
				if !decode(&in) {
					err = reports.ErrInvalid
					break
				}
				value, err = service.Change(ctx, session.User, r.PathValue("report"), in.Vote, false)
			case "withdraw":
				b, e := io.ReadAll(io.LimitReader(r.Body, 1))
				if e != nil || len(b) > 0 {
					err = reports.ErrInvalid
					break
				}
				value, err = service.Change(ctx, session.User, r.PathValue("report"), 0, true)
			}
			switch {
			case err == nil:
				writeJSON(w, 200, value)
			case errors.Is(err, reports.ErrInvalid):
				writeAPIError(w, r, 400, "invalid_request", "check category, observation time, pin and search radius")
			case errors.Is(err, reports.ErrMissing):
				writeAPIError(w, r, 404, "report_unavailable", "report expired or unavailable")
			case errors.Is(err, reports.ErrForbidden):
				writeAPIError(w, r, 403, "forbidden", "this report operation is not allowed")
			case errors.Is(err, reports.ErrConflict):
				writeAPIError(w, r, 409, "idempotency_conflict", "use the same report when retrying")
			case errors.Is(err, reports.ErrLimit):
				w.Header().Set("Retry-After", "3600")
				writeAPIError(w, r, 429, "rate_limited", "too many report changes; retry later")
			default:
				writeAPIError(w, r, 503, "reports_unavailable", "road reports temporarily unavailable")
			}
		})
	}
}
