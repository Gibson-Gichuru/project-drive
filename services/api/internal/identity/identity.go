// Package identity provides deliberately local/test-only development identities.
// There is no network token issuer. Operators mint short-lived tokens offline.
package identity

import (
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/golang-jwt/jwt/v5"
)

const Issuer = "project-drive-local"
const Audience = "project-drive-api"
const MaxLifetime = 15 * time.Minute

type User struct {
	ID   string
	Name string
}
type Claims struct {
	Name string `json:"name"`
	jwt.RegisteredClaims
}

func UUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, c := range s {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if c != '-' {
				return false
			}
			continue
		}
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return s != "00000000-0000-0000-0000-000000000000"
}
func ValidName(s string, max int) bool {
	return utf8.ValidString(s) && strings.TrimSpace(s) == s && utf8.RuneCountInString(s) >= 1 && utf8.RuneCountInString(s) <= max && !strings.ContainsAny(s, "\r\n\x00")
}

// PublicKey validates the environment at the authentication boundary as well as in config.
func PublicKey(environment, encoded string) (ed25519.PublicKey, error) {
	if environment != "local" && environment != "test" {
		return nil, errors.New("development identities require explicit APP_ENV=local or test")
	}
	b, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil || len(b) != ed25519.PublicKeySize {
		return nil, errors.New("DEV_AUTH_PUBLIC_KEY must be a base64 Ed25519 public key")
	}
	return ed25519.PublicKey(b), nil
}
func Verify(key ed25519.PublicKey, raw string) (User, error) {
	var c Claims
	token, err := jwt.ParseWithClaims(raw, &c, func(*jwt.Token) (any, error) { return key, nil }, jwt.WithValidMethods([]string{"EdDSA"}), jwt.WithIssuer(Issuer), jwt.WithAudience(Audience), jwt.WithExpirationRequired(), jwt.WithIssuedAt())
	if err != nil || token == nil || !token.Valid || token.Header["typ"] != "JWT" || c.IssuedAt == nil || c.ExpiresAt == nil || c.ExpiresAt.Sub(c.IssuedAt.Time) > MaxLifetime || !c.ExpiresAt.After(c.IssuedAt.Time) || !UUID(c.Subject) || !ValidName(c.Name, 80) {
		return User{}, errors.New("invalid identity")
	}
	return User{c.Subject, c.Name}, nil
}
func Sign(key ed25519.PrivateKey, user User, now time.Time) (string, error) {
	if !UUID(user.ID) || !ValidName(user.Name, 80) {
		return "", errors.New("valid UUID and display name required")
	}
	return jwt.NewWithClaims(jwt.SigningMethodEdDSA, Claims{user.Name, jwt.RegisteredClaims{Issuer: Issuer, Subject: user.ID, Audience: jwt.ClaimStrings{Audience}, IssuedAt: jwt.NewNumericDate(now), ExpiresAt: jwt.NewNumericDate(now.Add(MaxLifetime))}}).SignedString(key)
}
