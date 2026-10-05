-- No IP, request log, user/device/install ID, user-agent or raw ping table.
CREATE TABLE daily_totals (
    day TEXT PRIMARY KEY NOT NULL,
    requests INTEGER NOT NULL CHECK(requests BETWEEN 0 AND 10000),
    daily_active INTEGER NOT NULL,
    first_in_week INTEGER NOT NULL,
    first_in_month INTEGER NOT NULL
) STRICT;
CREATE TABLE daily_dimensions (
    day TEXT NOT NULL REFERENCES daily_totals(day) ON DELETE CASCADE,
    dimension TEXT NOT NULL CHECK(dimension IN ('version','model','bit_perfect','model_bit_perfect','android','rom','recent_usb_audio')),
    value TEXT NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY(day, dimension, value)
) STRICT;
-- Distinct values per day and dimension, so forged pings cannot grow storage without bound.
CREATE TABLE daily_dimension_sizes (
    day TEXT NOT NULL REFERENCES daily_totals(day) ON DELETE CASCADE,
    dimension TEXT NOT NULL,
    distinct_values INTEGER NOT NULL,
    PRIMARY KEY(day, dimension)
) STRICT;
CREATE TABLE reports (
    id TEXT PRIMARY KEY NOT NULL,
    received_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    document TEXT NOT NULL CHECK(json_valid(document))
) STRICT;
CREATE INDEX reports_expiry ON reports(expires_at);
CREATE TABLE daily_report_counts (
    day TEXT PRIMARY KEY NOT NULL,
    count INTEGER NOT NULL CHECK(count BETWEEN 0 AND 100)
) STRICT;
