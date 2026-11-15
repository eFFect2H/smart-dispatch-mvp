ALTER TABLE v1_dispatch_days
    ADD COLUMN plan_version INTEGER NOT NULL DEFAULT 0 CHECK (plan_version >= 0),
    ADD COLUMN plan_payload JSONB,
    ADD COLUMN plan_as_of TIMESTAMPTZ;

