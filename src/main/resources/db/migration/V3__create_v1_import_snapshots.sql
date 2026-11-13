-- Stage A: storage for the organizer-facing API v1.
-- The legacy MVP tables (teams, transport_requests) remain unchanged.
-- Reference data and the travel matrix are stored as versioned JSONB snapshots:
-- the matrix contains N x N durations and distances and should not be expanded
-- into millions of relational rows.

CREATE TABLE v1_reference_snapshot (
    snapshot_id SMALLINT PRIMARY KEY CHECK (snapshot_id = 1),
    payload JSONB NOT NULL,
    service_center_count INTEGER NOT NULL CHECK (service_center_count >= 0),
    staff_count INTEGER NOT NULL CHECK (staff_count >= 0),
    access_node_count INTEGER NOT NULL CHECK (access_node_count >= 0),
    building_count INTEGER NOT NULL CHECK (building_count >= 0),
    work_type_count INTEGER NOT NULL CHECK (work_type_count >= 0),
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE v1_travel_matrix_snapshot (
    snapshot_id SMALLINT PRIMARY KEY CHECK (snapshot_id = 1),
    matrix_id VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    point_count INTEGER NOT NULL CHECK (point_count >= 2),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE v1_dispatch_days (
    day_date DATE PRIMARY KEY,
    model_now TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL,
    order_count INTEGER NOT NULL CHECK (order_count >= 0),
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_v1_dispatch_days_loaded_at
    ON v1_dispatch_days (loaded_at DESC);
