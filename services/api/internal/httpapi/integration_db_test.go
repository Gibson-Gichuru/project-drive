package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"github.com/jackc/pgx/v5/pgxpool"
	"os"
	"path/filepath"
	"testing"
)

func integrationDB(t *testing.T) *pgxpool.Pool {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("run make integration for isolated PostGIS acceptance tests")
	}
	ctx := context.Background()
	admin, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(admin.Close)
	b := make([]byte, 8)
	if _, err = rand.Read(b); err != nil {
		t.Fatal(err)
	}
	schema := "phase0_test_" + hex.EncodeToString(b)
	if _, err = admin.Exec(ctx, `CREATE SCHEMA `+schema); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if _, err := admin.Exec(ctx, `DROP SCHEMA `+schema+` CASCADE`); err != nil {
			t.Error(err)
		}
	})
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema + ",public"
	cfg.MaxConns = 25
	db, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	files, err := filepath.Glob("../../migrations/*.sql")
	if err != nil || len(files) < 3 {
		t.Fatal("migrations missing")
	}
	for _, file := range files {
		sql, err := os.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		conn, err := db.Acquire(ctx)
		if err != nil {
			t.Fatal(err)
		}
		_, err = conn.Conn().PgConn().Exec(ctx, string(sql)).ReadAll()
		conn.Release()
		if err != nil {
			t.Fatal(err)
		}
	}
	return db
}
