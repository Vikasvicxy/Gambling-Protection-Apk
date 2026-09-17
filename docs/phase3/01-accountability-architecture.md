# Shield — Accountability, Parent Mode & Serverless Backend

**Phase 3 · implementation notes**

## 1. Goals
Enable a genuine **accountability partner** and a genuine **Parent / Guardian** supervision
experience on top of the Phase 1/2 blocking core, without putting any normal browsing traffic
through a backend and without ever exposing full browsing history to a partner.

## 2. Modules
```
core:model             Phase 3 domain models (Accountability/Pairing/Heartbeat/Parent/Replacement/Notification/Admin/Backend)
core:accountability    Pure-JVM rules: pairing tokens, relationships, heartbeats, event grouping, notification policy,
                       parent authorization, replacement service, device identity   (74 tests)
core:admin             Pure-JVM moderation engine + immutable audit log               (16 tests)
data:accountability    Room persistence + repository + OkHttp backend client + notification gateway + Hilt module
feature:accountability Partner self-mode UI (invite / accept / partners)
feature:parent         Parent / Guardian dashboard (child pairing, approvals)
serverless/            Cloudflare Worker + D1 + KV backend and admin dashboard (reference implementation)
```

## 3. Pairing token scheme (device ↔ server)
- The device `PairingTokenService` mints a `StoredPairingToken(fullToken = "id.secret")`.
- `tokenSecretHash` that travels to the backend is **SHA-256 of the full `"id.secret"` string**
  — the raw secret never leaves the device.
- The Worker `POST /api/pairing/accept` recomputes `sha256(tokenId + "." + tokenSecret)`
  and compares constant-time against the stored hash, then deletes the KV record (single-use).
- Both sides must agree on the scheme: server-side `crypto.ts::sha256Hex` and device-side
  `PairingTokenService.hash` are the two halves.

## 4. Serverless design (free-tier, out-of-band)
- **Never in the normal browsing path.** Blocking is local (DNS + VPN tunnels); the Worker only
  handles heartbeat relay, pairing handshake, approval relay, event relay metadata, reports and
  notification-token registration.
- Cloudflare **D1** = relational store (devices, relationships, heartbeats, events, approvals,
  reports, notification_tokens, domain_candidates, audit_log). **KV** = short-TTL pairing tokens,
  admin sessions, rate-limit counters.
- Bearer auth uses the installed device token; replay protection via monotonically increasing
  `heartbeat_sequence`; registration is rate-limited server-side as a second line of defense.
- Admin endpoints (`/api/admin/*`) are gated by a server-minted session token
  (`mintAdminSession`, KV-TTL 6 h). The dashboard at `serverless/admin/index.html` consumes them.
- Deployment requires a Cloudflare account: fill `database_id` / KV `id` in `wrangler.toml`,
  apply `migrations/0001_initial.sql`, then `npm run deploy`.

## 5. Notification policies (single source of truth in core)
- Severity: single attempt `LOW` · repeated `MEDIUM` · VPN disabled `HIGH` · heartbeat lost `CRITICAL`.
- Mandated bodies: `DEFAULT_BLOCK_MESSAGE` and `REPEATED_BLOCK_MESSAGE` live as
  `NotificationDefaults` constants in `core/model/Notification.kt` and are asserted in tests.
- Heartbeat-lost copy communicates "Protection status could not be confirmed."
- Exact-domain sharing is opt-in (default off); category aggregates are on by default.

## 6. Privacy & Play-policy stance
- Partners/parents see **minimal aggregate protection events only** (blocked attempt, protection
  active/unconfirmed, heartbeat lost) — never history, messages, contacts, passwords or location.
- Parent mode is genuine parental control of a supervised child device; adult self-use stays
  non-accountability by default. See `PLAY_POLICY.md`.

## 7. Threat notes
- Raw pairing secrets are never stored server-side (hash-only), tokens are single-use + TTL'd.
- Approximation of constant-time comparisons on both device and server.
- Device token rotation possible via `POST /api/devices/register` upsert; heartbeat sequence
  guards stale replays; rate limiting guards abuse.
- Admin sessions are single-purpose, KV-backed, and audit-logged (immutable append-only).