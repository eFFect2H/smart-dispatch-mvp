CREATE TABLE teams (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    capacity INTEGER NOT NULL CHECK (capacity > 0),
    start_latitude DOUBLE PRECISION NOT NULL CHECK (start_latitude BETWEEN -90 AND 90),
    start_longitude DOUBLE PRECISION NOT NULL CHECK (start_longitude BETWEEN -180 AND 180),
    shift_start_minute INTEGER NOT NULL CHECK (shift_start_minute BETWEEN 0 AND 1439),
    shift_end_minute INTEGER NOT NULL CHECK (shift_end_minute BETWEEN 1 AND 1440),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (shift_start_minute < shift_end_minute)
);

CREATE TABLE transport_requests (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    address VARCHAR(500) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    window_start_minute INTEGER NOT NULL CHECK (window_start_minute BETWEEN 0 AND 1439),
    window_end_minute INTEGER NOT NULL CHECK (window_end_minute BETWEEN 1 AND 1440),
    service_duration_minutes INTEGER NOT NULL CHECK (service_duration_minutes >= 0),
    demand INTEGER NOT NULL CHECK (demand > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    assigned_team_id BIGINT REFERENCES teams(id) ON DELETE SET NULL,
    planned_arrival_minute INTEGER,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (window_start_minute < window_end_minute)
);

CREATE INDEX idx_transport_requests_status ON transport_requests(status);
CREATE INDEX idx_transport_requests_assigned_team ON transport_requests(assigned_team_id);
