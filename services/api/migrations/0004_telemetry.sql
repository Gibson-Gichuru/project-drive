-- Only derived, explicitly uploaded telemetry. Raw GPS/IMU tracks are not accepted.
CREATE TABLE telemetry_recordings (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    user_id UUID NOT NULL,
    request_hash BYTEA NOT NULL CHECK (octet_length(request_hash)=32),
    summary_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '7 days'),
    FOREIGN KEY (trip_id,user_id) REFERENCES trip_members(trip_id,user_id) ON DELETE CASCADE
);
CREATE INDEX telemetry_recordings_owner_idx ON telemetry_recordings(trip_id,user_id,created_at);
CREATE INDEX telemetry_recordings_expiry_idx ON telemetry_recordings(expires_at);
ALTER TABLE telemetry_events ADD COLUMN recording_id UUID REFERENCES telemetry_recordings(id) ON DELETE CASCADE;
ALTER TABLE telemetry_events ADD COLUMN metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE INDEX telemetry_events_recording_idx ON telemetry_events(recording_id);
