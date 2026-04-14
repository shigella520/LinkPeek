CREATE TABLE IF NOT EXISTS stats_link (
    preview_key TEXT PRIMARY KEY,
    provider_id TEXT,
    canonical_url TEXT NOT NULL,
    title TEXT NOT NULL,
    site_name TEXT NOT NULL,
    first_seen_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS stats_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    preview_key TEXT,
    provider_id TEXT,
    http_status INTEGER NOT NULL,
    cache_hit INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    client_type TEXT NOT NULL,
    error_code TEXT
);

CREATE INDEX IF NOT EXISTS idx_stats_event_occurred_at ON stats_event (occurred_at);
CREATE INDEX IF NOT EXISTS idx_stats_event_type_occurred_at ON stats_event (event_type, occurred_at);
CREATE INDEX IF NOT EXISTS idx_stats_event_preview_key ON stats_event (preview_key);
CREATE INDEX IF NOT EXISTS idx_stats_link_last_seen_at ON stats_link (last_seen_at);
