package identity

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"github.com/golang-jwt/jwt/v5"
	"testing"
	"time"
)

func TestIdentityValidation(t *testing.T) {
	pub, key, _ := ed25519.GenerateKey(rand.Reader)
	user := User{"11111111-1111-4111-8111-111111111111", "Owner"}
	token, err := Sign(key, user, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	got, err := Verify(pub, token)
	if err != nil || got != user {
		t.Fatal("valid identity rejected")
	}
	for _, tc := range []struct {
		name       string
		mutate     func(*Claims)
		method     jwt.SigningMethod
		signingKey any
	}{
		{"expired", func(c *Claims) {
			c.IssuedAt = jwt.NewNumericDate(time.Now().Add(-time.Hour))
			c.ExpiresAt = jwt.NewNumericDate(time.Now().Add(-time.Minute))
		}, jwt.SigningMethodEdDSA, key},
		{"no expiry", func(c *Claims) { c.ExpiresAt = nil }, jwt.SigningMethodEdDSA, key},
		{"no issued at", func(c *Claims) { c.IssuedAt = nil }, jwt.SigningMethodEdDSA, key},
		{"future", func(c *Claims) { c.IssuedAt = jwt.NewNumericDate(time.Now().Add(time.Hour)) }, jwt.SigningMethodEdDSA, key},
		{"long lived", func(c *Claims) { c.ExpiresAt = jwt.NewNumericDate(time.Now().Add(time.Hour)) }, jwt.SigningMethodEdDSA, key},
		{"wrong issuer", func(c *Claims) { c.Issuer = "other" }, jwt.SigningMethodEdDSA, key},
		{"wrong audience", func(c *Claims) { c.Audience = jwt.ClaimStrings{"other"} }, jwt.SigningMethodEdDSA, key},
		{"bad subject", func(c *Claims) { c.Subject = "not-uuid" }, jwt.SigningMethodEdDSA, key},
		{"blank name", func(c *Claims) { c.Name = " " }, jwt.SigningMethodEdDSA, key},
		{"algorithm confusion", func(c *Claims) {}, jwt.SigningMethodHS256, []byte(pub)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c := Claims{Name: user.Name, RegisteredClaims: jwt.RegisteredClaims{Issuer: Issuer, Subject: user.ID, Audience: jwt.ClaimStrings{Audience}, IssuedAt: jwt.NewNumericDate(time.Now()), ExpiresAt: jwt.NewNumericDate(time.Now().Add(MaxLifetime))}}
			tc.mutate(&c)
			raw, err := jwt.NewWithClaims(tc.method, c).SignedString(tc.signingKey)
			if err != nil {
				t.Fatal(err)
			}
			if _, err = Verify(pub, raw); err == nil {
				t.Fatal("invalid token accepted")
			}
		})
	}
	_, other, _ := ed25519.GenerateKey(rand.Reader)
	bad, _ := Sign(other, user, time.Now())
	if _, err = Verify(pub, bad); err == nil {
		t.Fatal("forged signature accepted")
	}
	encoded := base64.StdEncoding.EncodeToString(pub)
	for _, env := range []string{"", "homelab", "production", "LOCAL"} {
		if _, err := PublicKey(env, encoded); err == nil {
			t.Fatalf("auth enabled in %q", env)
		}
	}
	for _, env := range []string{"local", "test"} {
		if _, err := PublicKey(env, encoded); err != nil {
			t.Fatal(err)
		}
	}
}
