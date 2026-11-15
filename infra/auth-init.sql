-- Disposable local Auth database only: exercise a non-superuser migration role,
-- matching the homelab. psql reads the ephemeral password without command args.
\getenv auth_password POSTGRES_PASSWORD
CREATE ROLE supabase_auth_admin LOGIN PASSWORD :'auth_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE SCHEMA IF NOT EXISTS auth AUTHORIZATION supabase_auth_admin;
ALTER ROLE supabase_auth_admin SET search_path = auth, public;
