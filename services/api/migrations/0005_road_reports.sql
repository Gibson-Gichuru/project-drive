CREATE TABLE road_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES users(id),
    request_key UUID NOT NULL,
    category TEXT NOT NULL CHECK (category IN ('accident','breakdown','camera','speed_bump','closure','obstacle')),
    location geography(Point,4326) NOT NULL,
    note TEXT NOT NULL DEFAULT '' CHECK (length(note) <= 240),
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > observed_at),
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','withdrawn')),
    UNIQUE (reporter_id,request_key)
);
CREATE INDEX road_reports_location_gix ON road_reports USING GIST(location) WHERE status='active';
CREATE INDEX road_reports_expiry_idx ON road_reports(expires_at);
CREATE INDEX road_reports_reporter_time_idx ON road_reports(reporter_id,created_at);
CREATE TABLE report_votes (
    report_id UUID NOT NULL REFERENCES road_reports(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    vote SMALLINT NOT NULL CHECK (vote IN (-1,1)),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (report_id,user_id)
);
CREATE TABLE report_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES road_reports(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES users(id),
    action TEXT NOT NULL CHECK (action IN ('create','confirm','dismiss','withdraw')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX report_audit_actor_time_idx ON report_audit(actor_id,created_at);
