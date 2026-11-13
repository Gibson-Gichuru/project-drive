CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name TEXT NOT NULL CHECK (length(trim(display_name)) BETWEEN 1 AND 80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE vehicles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    nickname TEXT NOT NULL CHECK (length(trim(nickname)) BETWEEN 1 AND 80),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX vehicles_user_id_idx ON vehicles(user_id);

CREATE TABLE trips (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id),
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 120),
    origin geography(Point, 4326),
    destination geography(Point, 4326),
    status TEXT NOT NULL DEFAULT 'planned' CHECK (status IN ('planned', 'active', 'completed', 'cancelled')),
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'active' AND started_at IS NOT NULL AND ended_at IS NULL)
        OR (status = 'completed' AND started_at IS NOT NULL AND ended_at IS NOT NULL)
        OR (status = 'cancelled' AND ended_at IS NOT NULL)
        OR (status = 'planned' AND started_at IS NULL AND ended_at IS NULL))
);
CREATE INDEX trips_owner_created_idx ON trips(owner_id, created_at DESC);
CREATE INDEX trips_origin_gix ON trips USING GIST(origin);
CREATE INDEX trips_destination_gix ON trips USING GIST(destination);

CREATE TABLE trip_members (
    trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('owner', 'member')),
    sharing_enabled BOOLEAN NOT NULL DEFAULT false,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ,
    PRIMARY KEY (trip_id, user_id)
);
CREATE INDEX trip_members_user_idx ON trip_members(user_id, joined_at DESC);

CREATE TABLE trip_invites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    max_uses INTEGER NOT NULL DEFAULT 1 CHECK (max_uses > 0),
    use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0 AND use_count <= max_uses),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX trip_invites_trip_idx ON trip_invites(trip_id);

CREATE TABLE telemetry_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    magnitude DOUBLE PRECISION,
    confidence DOUBLE PRECISION CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    source TEXT NOT NULL,
    location geography(Point, 4326),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX telemetry_events_trip_time_idx ON telemetry_events(trip_id, occurred_at);
CREATE INDEX telemetry_events_location_gix ON telemetry_events USING GIST(location);

CREATE TABLE trip_summaries (
    trip_id UUID PRIMARY KEY REFERENCES trips(id) ON DELETE CASCADE,
    distance_m DOUBLE PRECISION CHECK (distance_m IS NULL OR distance_m >= 0),
    duration_s BIGINT CHECK (duration_s IS NULL OR duration_s >= 0),
    stats_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
