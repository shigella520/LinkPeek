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
    ai_requested INTEGER NOT NULL DEFAULT 0,
    ai_succeeded INTEGER NOT NULL DEFAULT 0,
    source_url TEXT,
    requested_style TEXT,
    actual_style TEXT,
    ai_provider_names TEXT,
    ai_duration_ms INTEGER NOT NULL DEFAULT 0,
    crawl_duration_ms INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL,
    client_type TEXT NOT NULL,
    error_code TEXT
);

CREATE INDEX IF NOT EXISTS idx_stats_event_occurred_at ON stats_event (occurred_at);
CREATE INDEX IF NOT EXISTS idx_stats_event_type_occurred_at ON stats_event (event_type, occurred_at);
CREATE INDEX IF NOT EXISTS idx_stats_event_preview_key ON stats_event (preview_key);
CREATE INDEX IF NOT EXISTS idx_stats_link_last_seen_at ON stats_link (last_seen_at);

CREATE TABLE IF NOT EXISTS admin_prompt (
    style TEXT PRIMARY KEY,
    prompt TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS provider_config (
    provider_id TEXT NOT NULL,
    config_key TEXT NOT NULL,
    config_value TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (provider_id, config_key)
);

CREATE TABLE IF NOT EXISTS ai_provider (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    sort_order INTEGER NOT NULL,
    base_url TEXT NOT NULL,
    api_kind TEXT NOT NULL DEFAULT 'CHAT_COMPLETIONS',
    model TEXT NOT NULL,
    effort TEXT,
    request_timeout_seconds INTEGER NOT NULL DEFAULT 45,
    api_key TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_provider_enabled_sort ON ai_provider (enabled, sort_order, id);

CREATE TABLE IF NOT EXISTS share_summary_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    period_type TEXT NOT NULL,
    run_time TEXT NOT NULL,
    day_of_week INTEGER,
    prompt TEXT NOT NULL,
    max_links INTEGER NOT NULL,
    min_links INTEGER NOT NULL DEFAULT 1,
    deleted INTEGER NOT NULL DEFAULT 0,
    deleted_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_share_summary_task_enabled ON share_summary_task (enabled, deleted, period_type);

CREATE TABLE IF NOT EXISTS share_summary_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id INTEGER NOT NULL,
    task_name TEXT NOT NULL,
    trigger_type TEXT NOT NULL,
    period_type TEXT NOT NULL,
    window_start INTEGER NOT NULL,
    window_end INTEGER NOT NULL,
    status TEXT NOT NULL,
    link_count INTEGER NOT NULL DEFAULT 0,
    unique_link_count INTEGER NOT NULL DEFAULT 0,
    input_link_count INTEGER NOT NULL DEFAULT 0,
    prompt_snapshot TEXT NOT NULL,
    ai_provider_names TEXT,
    ai_duration_ms INTEGER NOT NULL DEFAULT 0,
    report TEXT,
    error_message TEXT,
    started_at INTEGER NOT NULL,
    finished_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_share_summary_run_task_window ON share_summary_run (task_id, window_start, window_end);
CREATE INDEX IF NOT EXISTS idx_share_summary_run_started_at ON share_summary_run (started_at);
CREATE INDEX IF NOT EXISTS idx_share_summary_run_status ON share_summary_run (status);

CREATE TABLE IF NOT EXISTS share_summary_image_config (
    id INTEGER PRIMARY KEY,
    enabled INTEGER NOT NULL DEFAULT 0,
    auto_generate INTEGER NOT NULL DEFAULT 0,
    provider_type TEXT NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
    base_url TEXT NOT NULL DEFAULT '',
    endpoint_path TEXT NOT NULL DEFAULT '/v1/images/generations',
    api_key TEXT NOT NULL DEFAULT '',
    model TEXT NOT NULL DEFAULT '',
    image_size TEXT NOT NULL DEFAULT 'auto',
    quality TEXT NOT NULL DEFAULT 'auto',
    output_format TEXT NOT NULL DEFAULT 'png',
    style_prompt TEXT NOT NULL DEFAULT '',
    request_timeout_seconds INTEGER NOT NULL DEFAULT 300,
    updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS share_summary_image (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id INTEGER NOT NULL,
    attempt_no INTEGER NOT NULL,
    status TEXT NOT NULL,
    provider_type TEXT NOT NULL,
    model TEXT NOT NULL,
    image_size TEXT NOT NULL,
    output_format TEXT NOT NULL,
    quality TEXT,
    style_prompt_snapshot TEXT,
    prompt_snapshot TEXT NOT NULL,
    storage_key TEXT,
    public_token TEXT NOT NULL,
    image_url TEXT,
    og_image_url TEXT,
    og_page_url TEXT,
    og_title TEXT NOT NULL,
    og_description TEXT NOT NULL,
    raw_response_snapshot TEXT,
    error_message TEXT,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    started_at INTEGER,
    finished_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_share_summary_image_run_id ON share_summary_image (run_id);
CREATE INDEX IF NOT EXISTS idx_share_summary_image_status ON share_summary_image (status);
CREATE INDEX IF NOT EXISTS idx_share_summary_image_created_at ON share_summary_image (created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_share_summary_image_public_token ON share_summary_image (public_token);

CREATE TABLE IF NOT EXISTS notification_channel (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    type TEXT NOT NULL,
    url TEXT NOT NULL,
    method TEXT NOT NULL,
    headers_json TEXT,
    secret TEXT,
    timeout_seconds INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notification_channel_enabled ON notification_channel (enabled, type);

CREATE TABLE IF NOT EXISTS notification_task (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    enabled INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    filters_json TEXT,
    template_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notification_task_event_enabled ON notification_task (event_type, enabled);

CREATE TABLE IF NOT EXISTS notification_task_channel (
    task_id INTEGER NOT NULL,
    channel_id INTEGER NOT NULL,
    PRIMARY KEY (task_id, channel_id)
);

CREATE TABLE IF NOT EXISTS notification_delivery (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT NOT NULL,
    event_key TEXT NOT NULL,
    notification_task_id INTEGER NOT NULL,
    notification_task_name TEXT NOT NULL,
    channel_id INTEGER NOT NULL,
    channel_name TEXT NOT NULL,
    status TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    request_url TEXT NOT NULL,
    request_body_snapshot TEXT,
    response_status INTEGER,
    response_body_snapshot TEXT,
    error_message TEXT,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    finished_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_notification_delivery_event_key ON notification_delivery (event_key);
CREATE INDEX IF NOT EXISTS idx_notification_delivery_status_created ON notification_delivery (status, created_at);
CREATE INDEX IF NOT EXISTS idx_notification_delivery_task_created ON notification_delivery (notification_task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_notification_delivery_channel_created ON notification_delivery (channel_id, created_at);
