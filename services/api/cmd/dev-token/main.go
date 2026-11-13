// dev-token is an offline local/test utility. Never install its private key in the API.
package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"flag"
	"fmt"
	"os"
	"projectdrive/services/api/internal/identity"
	"time"
)

func main() {
	keygen := flag.String("generate-key", "", "write a new private seed to this new file (0600), print public key")
	keyfile := flag.String("key", "", "private seed file")
	user := flag.String("user", "", "user UUID")
	name := flag.String("name", "", "display name")
	flag.Parse()
	if os.Getenv("APP_ENV") != "local" && os.Getenv("APP_ENV") != "test" {
		fail("explicit APP_ENV=local or test required")
	}
	if *keygen != "" {
		public, private, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			fail("key generation failed")
		}
		f, err := os.OpenFile(*keygen, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
		if err != nil {
			fail("cannot create new key file")
		}
		_, err = f.WriteString(base64.StdEncoding.EncodeToString(private.Seed()))
		closeErr := f.Close()
		if err != nil || closeErr != nil {
			fail("cannot write key")
		}
		fmt.Println(base64.StdEncoding.EncodeToString(public))
		return
	}
	b, err := os.ReadFile(*keyfile)
	if err != nil {
		fail("cannot read key")
	}
	seed, err := base64.StdEncoding.DecodeString(string(b))
	if err != nil || len(seed) != ed25519.SeedSize {
		fail("invalid seed")
	}
	token, err := identity.Sign(ed25519.NewKeyFromSeed(seed), identity.User{ID: *user, Name: *name}, time.Now())
	if err != nil {
		fail(err.Error())
	}
	fmt.Println(token)
}
func fail(message string) { fmt.Fprintln(os.Stderr, message); os.Exit(1) }
