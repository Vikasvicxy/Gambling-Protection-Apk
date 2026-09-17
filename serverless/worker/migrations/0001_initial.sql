-- Shield serverless backend schema (Cloudflare D1)
-- Migrations are idempotent-friendly; run with: wrangler d1 migrations apply shield-db

CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS relationships (
  id TEXT PRIMARY KEY,
  protected_device_id TEXT NOT NULL,
  partner_device_id TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'PARTNER',
  status TEXT NOT NULL DEFAULT 'PENDING',
  capabilities TEXT NOT NULL DEFAULT '{}',
  created_at INTEGER NOT NULL,
  accepted_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_relationships_protected ON relationships(protected_device_id);
CREATE INDEX IF NOT EXISTS idx_relationships_partner ON relationships(partner_device_id);

CREATE TABLE IF NOT EXISTS heartbeats (
  device_id TEXT PRIMARY KEY,
  protection_state TEXT NOT NULL,
  health TEXT NOT NULL,
  last_check_in INTEGER NOT NULL,
  app_version TEXT,
  database_version INTEGER,
  heartbeat_sequence INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  type TEXT NOT NULL,
  severity TEXT NOT NULL,
  occurred_at INTEGER NOT NULL,
  category TEXT,
  detail TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_device ON events(device_id);

CREATE TABLE IF NOT EXISTS approvals (
  id TEXT PRIMARY KEY,
  relationship_id TEXT NOT NULL,
  requested_by TEXT NOT NULL,
  change TEXT NOT NULL,
  description TEXT,
  status TEXT NOT NULL DEFAULT 'PENDING',
  requested_at INTEGER NOT NULL,
  decided_by TEXT,
  decided_at INTEGER
);

CREATE TABLE IF NOT EXISTS reports (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL,
  type TEXT NOT NULL,
  domain TEXT NOT NULL,
  normalized_domain TEXT NOT NULL,
  note TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reports_created ON reports(created_at);

CREATE TABLE IF NOT EXISTS notification_tokens (
  device_id TEXT PRIMARY KEY,
  token TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS domain_candidates (
  id TEXT PRIMARY KEY,
  domain TEXT NOT NULL,
  normalized_domain TEXT NOT NULL,
  category TEXT NOT NULL,
  submitted_by TEXT,
  source TEXT,
  confidence TEXT NOT NULL DEFAULT 'UNKNOWN',
  status TEXT NOT NULL DEFAULT 'NEW_CANDIDATE',
  report_count INTEGER DEFAULT 0,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_candidates_status ON domain_candidates(status);

CREATE TABLE IF NOT EXISTS audit_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  actor TEXT NOT NULL,
  action TEXT NOT NULL,
  target_type TEXT,
  target_id TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);