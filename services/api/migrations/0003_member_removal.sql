-- Removal is a trip-scoped ban in the Phase 0 prototype. Ordinary leave is not.
ALTER TABLE trip_members ADD COLUMN removed_at TIMESTAMPTZ;
ALTER TABLE trip_members ADD CONSTRAINT removed_member_inactive CHECK (removed_at IS NULL OR left_at IS NOT NULL);
