# Shield — Backend Deployment Pack (Phase 4 §13)

**Status: LOCAL TESTED / PRODUCTION DEPLOYMENT NOT TESTED.** Worker typechecks; wire format + D1 schema unit-tested. Requires human-supplied Cloudflare credentials.

## Repository layout
- `serverless/worker/` — Cloudflare Worker, dependency-free runtime
  - `wrangler.toml` — config with `REPLACE_ME_WITH_*` placeholders
  - `src/index.ts` — router (REST), auth, pairing, approvals, reports, admin
  - `src/env.ts` — typed bindings (D1, KV namespaces, secrets)
  - `src/crypto.ts` — token salt/HMAC helpers
  - `migrations/0001_initial.sql` — D1 schema (devices, pairings, approvals, reports, notification_tokens, admin_sessions)
- `serverless/admin/index.html` — admin dashboard (static, single file)

## Pre-requisites (human steps)
1. Cloudflare account; create a worker + D1 database.
2. Create D1: `npx wrangler d1 create shield-db` → copy `database_id` into `wrangler.toml` (`REPLACE_ME_WITH_D1_DATABASE_ID`).
3. Create KV namespace: `npx wrangler kv namespace create SHIELD_KV` → copy `id` into `wrangler.toml` (`REPLACE_ME_WITH_KV_NAMESPACE_ID`).
4. Apply migration: `npx wrangler d1 migrations apply shield-db --remote` (file is `migrations/0001_initial.sql`).
5. Secrets (never in VCS):
   - `npx wrangler secret put PAIRING_SECRET` (pairing token HMAC)
   - `npx wrangler secret put ADMIN_SALT` (admin-session salt)
   - `npx wrangler secret put ADMIN_BOOTSTRAP_TOKEN` (one-time admin session mint — rotate after first use)
   - Optionally `BYPASS_REPORT_TOKEN` for the report-rate-limit path
6. Env (wrangler.toml, not secret):
   - `ADMIN_SESSION_TTL_MS` = 21600000 (6 h)
   - `PAIRING_TOKEN_TTL_MS` = 600000 (10 min) — note: currently referenced in worker env; `REPORT_RATE_LIMIT` is defined in config but not consumed in code (dead config; document this gap)
   - `ALLOW_NEW_REGISTRATIONS` = true during closed testing

## Deploy
`npx wrangler deploy` from `serverless/worker/`. Verify with:
- `curl https://<worker>/healthz` → 200
- register → heartbeat → pairing happy-path smoke against `DEV` stage URL before switching the app's `BackendConfig.DEFAULT_BASE_URL`.

## Rollback
- `npx wrangler versions list` → redeploy previous stable version; D1 is append-only-ish (schema v1) — deletions are logical (status flags) not physical, per migration comments.

## Security gates included
- Auth header check on admin endpoints (session token, KV-backed, TTL'd); pairing token single-use; approvals one-way (deny by default unless allowed by a real parent role); rate limiting on reports (config), transport always HTTPS via worker default.

## Gap notes (report these honestly)
- **Admin login rate limit — DEFERRED** (worker is stateless; a KV-based limiter is a follow-up).
- `REPORT_RATE_LIMIT` env declared but unused in code — remove or wire it up (documented for the follow-up).
- Offline wire-format tests now exist (`src/worker.test.ts`, 19 tests; `npm test`) — see `17-hardening-addendum.md` §4. Live wire-format E2E against the deployed worker still requires the deployment steps above.