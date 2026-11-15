package httpapi

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/coder/websocket"
	"io"
	"net/http"
	"os"
	"projectdrive/services/api/internal/identity"
	"projectdrive/services/api/internal/telemetry"
	"regexp"
	"strings"
	"testing"
	"time"
)

// Opt-in real Supabase + PostGIS + captured SMTP acceptance. Only disposable
// infra/auth.compose.yaml fixtures are allowed; no production admin key needed.
func TestSupabaseEndToEnd(t *testing.T) {
	base := os.Getenv("SUPABASE_ACCEPTANCE_URL")
	mailbase := os.Getenv("MAILPIT_ACCEPTANCE_URL")
	if base == "" || mailbase == "" {
		t.Skip("start disposable Auth acceptance Compose stack")
	}
	if base != "http://127.0.0.1:18080" || mailbase != "http://127.0.0.1:18025" {
		t.Fatal("acceptance requires explicit disposable loopback stack")
	}
	client := &http.Client{Timeout: 10 * time.Second}
	call := func(method, path, token string, body any, want int) map[string]any {
		t.Helper()
		var data []byte
		if body != nil {
			data, _ = json.Marshal(body)
		}
		r, e := http.NewRequest(method, base+path, bytes.NewReader(data))
		if e != nil {
			t.Fatal("request creation failed")
		}
		r.Header.Set("Content-Type", "application/json")
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		if method == "POST" && (path == "/v1/trips" || strings.HasSuffix(path, "/invites")) {
			b := make([]byte, 16)
			_, _ = rand.Read(b)
			x := hex.EncodeToString(b)
			r.Header.Set("Idempotency-Key", x[:8]+"-"+x[8:12]+"-4"+x[13:16]+"-8"+x[17:20]+"-"+x[20:])
		}
		response, e := client.Do(r)
		if e != nil {
			t.Fatal("HTTP transport failed")
		}
		defer response.Body.Close()
		if response.StatusCode != want {
			t.Fatalf("%s %s: got %d want %d", method, path, response.StatusCode, want)
		}
		if response.Header.Get("Cache-Control") != "no-store" {
			t.Fatal("private response cache policy missing")
		}
		var result map[string]any
		if json.NewDecoder(io.LimitReader(response.Body, 65536)).Decode(&result) != nil {
			t.Fatal("invalid JSON response")
		}
		return result
	}
	codeFor := func(email string) string {
		t.Helper()
		deadline := time.Now().Add(12 * time.Second)
		for time.Now().Before(deadline) {
			response, e := client.Get(mailbase + "/api/v1/messages")
			if e != nil {
				t.Fatal("captured mail service unavailable")
			}
			var list struct {
				Messages []struct {
					ID string
					To []struct{ Address string }
				}
			}
			_ = json.NewDecoder(response.Body).Decode(&list)
			response.Body.Close()
			for _, message := range list.Messages {
				for _, to := range message.To {
					if to.Address == email {
						response, e := client.Get(mailbase + "/api/v1/message/" + message.ID)
						if e != nil {
							t.Fatal("captured message unavailable")
						}
						var content struct{ HTML, Text string }
						_ = json.NewDecoder(response.Body).Decode(&content)
						response.Body.Close()
						found := regexp.MustCompile(`\b[0-9]{6}\b`).FindString(content.Text + " " + content.HTML)
						if found != "" {
							return found
						}
					}
				}
			}
			time.Sleep(100 * time.Millisecond)
		}
		t.Fatal("no verification code in captured synthetic email")
		return ""
	}
	suffix := fmt.Sprint(time.Now().UnixNano())
	password := "synthetic-password-" + suffix
	tokens := make([]string, 3)
	emails := make([]string, 3)
	users := make([]string, 3)
	for n := range tokens {
		emails[n] = fmt.Sprintf("outset-test-%s-%d@example.test", suffix, n)
		call("POST", "/v1/auth/sign-up", "", map[string]string{"email": emails[n], "password": password, "display_name": fmt.Sprintf("Synthetic %d", n)}, 200)
		if n == 0 {
			call("POST", "/v1/auth/sign-in", "", map[string]string{"email": emails[n], "password": password}, 400)
		}
		result := call("POST", "/v1/auth/verify", "", map[string]string{"email": emails[n], "token": codeFor(emails[n]), "type": "signup"}, 200)
		tokens[n] = result["access_token"].(string)
		users[n] = result["user"].(map[string]any)["id"].(string)
		if !identity.UUID(users[n]) {
			t.Fatal("provider returned invalid subject")
		}
	}
	trip := call("POST", "/v1/trips", tokens[0], map[string]any{"name": "Synthetic Auth acceptance"}, 200)["id"].(string)
	path := "/v1/trips/" + trip
	invite := call("POST", path+"/invites", tokens[0], map[string]int{"expires_in_s": 900, "max_uses": 3}, 200)["token"].(string)
	call("POST", "/v1/trips/join", tokens[1], map[string]string{"token": invite}, 200)
	for _, p := range []string{path, path + "/members", path + "/summary"} {
		call("GET", p, tokens[2], nil, 404)
	}
	call("GET", path, "", nil, 401)
	call("GET", path, tokens[0][:len(tokens[0])-3]+"xxx", nil, 401)
	call("POST", path+"/start", tokens[0], nil, 200)
	now := time.Now().UTC()
	batch := telemetry.Batch{Version: 1, ID: "99999999-9999-4999-8999-999999999999", Summary: telemetry.Summary{StartedAt: now.Add(-time.Minute), EndedAt: now, Duration: 60, Covered: 60, Distance: 300, MeanSpeed: 5, MaxSpeed: 10, SpeedSamples: 3, GPSSamples: 3, MeanAccuracy: 12, Source: "phone_gps_imu_v1", Orientation: "unavailable", Calibration: "uncalibrated", Confidence: .35}, Events: []telemetry.Event{}}
	call("POST", path+"/telemetry", tokens[2], batch, 404)
	call("POST", path+"/telemetry", tokens[0], batch, 202)
	if len(call("GET", path+"/summary", tokens[1], nil, 200)["recordings"].([]any)) != 0 {
		t.Fatal("another member's telemetry was disclosed")
	}
	for n := 0; n < 2; n++ {
		call("PUT", path+"/sharing", tokens[n], map[string]bool{"enabled": true}, 200)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	dial := func(token string) *websocket.Conn {
		t.Helper()
		headers := http.Header{}
		headers.Set("Authorization", "Bearer "+token)
		c, _, e := websocket.Dial(ctx, base+path+"/stream", &websocket.DialOptions{HTTPHeader: headers})
		if e != nil {
			t.Fatal("authorized WebSocket denied")
		}
		return c
	}
	a, b := dial(tokens[0]), dial(tokens[1])
	defer a.CloseNow()
	defer b.CloseNow()
	_, _, e := a.Read(ctx)
	if e != nil {
		t.Fatal("owner snapshot unavailable")
	}
	_, _, e = b.Read(ctx)
	if e != nil {
		t.Fatal("member snapshot unavailable")
	}
	headers := http.Header{}
	headers.Set("Authorization", "Bearer "+tokens[2])
	foreign, response, denied := websocket.Dial(ctx, base+path+"/stream", &websocket.DialOptions{HTTPHeader: headers})
	if foreign != nil {
		foreign.CloseNow()
	}
	if denied == nil || response == nil || response.StatusCode != 404 {
		t.Fatal("non-member WebSocket was not refused")
	}
	position, _ := json.Marshal(map[string]any{"type": "position.update", "version": 1, "payload": map[string]any{"latitude": 0, "longitude": 36, "accuracy_meters": 8, "recorded_at": time.Now().UTC()}})
	if e := a.Write(ctx, websocket.MessageText, position); e != nil {
		t.Fatal("authorized synthetic update failed")
	}
	for _, connection := range []*websocket.Conn{a, b} {
		_, data, e := connection.Read(ctx)
		if e != nil {
			t.Fatal("synthetic position broadcast unavailable")
		}
		var envelope struct {
			Type    string
			Payload struct {
				UserID string `json:"user_id"`
			}
		}
		if json.Unmarshal(data, &envelope) != nil || envelope.Type != "participant.position" || envelope.Payload.UserID != users[0] {
			t.Fatal("unexpected private broadcast")
		}
	}
	call("POST", "/v1/auth/sign-out", tokens[0], nil, 200)
	call("GET", path, tokens[0], nil, 401)
	// The actual upgraded socket closes following logout, without waiting for exp.
	closed := false
	for i := 0; i < 5; i++ {
		_, data, e := a.Read(ctx)
		if e != nil {
			closed = true
			break
		}
		var message map[string]any
		_ = json.Unmarshal(data, &message)
		if message["type"] == "participant.position" {
			t.Fatal("position delivered to logged-out socket")
		}
	}
	if !closed {
		t.Fatal("logged-out socket stayed open")
	}
	session := call("POST", "/v1/auth/sign-in", "", map[string]string{"email": emails[1], "password": password}, 200)
	refreshed := call("POST", "/v1/auth/refresh", "", map[string]string{"refresh_token": session["refresh_token"].(string)}, 200)
	if refreshed["refresh_token"] == session["refresh_token"] {
		t.Fatal("refresh token did not rotate")
	}
	if refreshed["user"].(map[string]any)["id"] != users[1] {
		t.Fatal("refresh changed account")
	}
	call("POST", "/v1/auth/recover", "", map[string]string{"email": emails[2]}, 200)
	recovered := call("POST", "/v1/auth/verify", "", map[string]string{"email": emails[2], "token": codeFor(emails[2]), "type": "recovery"}, 200)
	call("POST", "/v1/auth/password", recovered["access_token"].(string), map[string]string{"password": password + "-changed"}, 200)
	call("POST", "/v1/auth/sign-in", "", map[string]string{"email": emails[2], "password": password}, 400)
	call("POST", "/v1/auth/sign-in", "", map[string]string{"email": emails[2], "password": password + "-changed"}, 200)
	t.Log("real Supabase/PostGIS/captured-email signup, confirmation, password recovery, refresh rotation, private membership and live logout passed")
}
