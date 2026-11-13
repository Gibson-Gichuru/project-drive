-- Additive: existing M0/M1 data remains valid. Requests are scoped to the owner.
ALTER TABLE trips ADD COLUMN creation_key UUID;
CREATE UNIQUE INDEX trips_creation_key_idx ON trips(owner_id, creation_key) WHERE creation_key IS NOT NULL;
ALTER TABLE trip_invites ADD COLUMN request_key UUID;
CREATE UNIQUE INDEX trip_invites_request_key_idx ON trip_invites(trip_id, request_key) WHERE request_key IS NOT NULL;
CREATE UNIQUE INDEX trip_members_one_owner_idx ON trip_members(trip_id) WHERE role = 'owner';
ALTER TABLE trip_members ADD CONSTRAINT owner_stays CHECK (role <> 'owner' OR left_at IS NULL);
ALTER TABLE trip_members ADD CONSTRAINT inactive_not_sharing CHECK (left_at IS NULL OR NOT sharing_enabled);
ALTER TABLE trip_invites ADD CONSTRAINT invite_hash_size CHECK (octet_length(token_hash) = 32);
