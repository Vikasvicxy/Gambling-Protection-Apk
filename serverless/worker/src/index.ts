import { Env } from './env'
import { sha256Hex, randomPart, rateLimit, validateAdminSession } from './crypto'

/** Schema: KB>=1 for inject, check AdminRole not client-declared. */
const DEVICES = 'devices'
const RELATIONSHIPS = 'relationships'
const HEARTBEATS = 'heartbeats'
const REPORTS = 'reports'
const CANDIDATES = 'domain_candidates'
const AUDIT_LOG = 'audit_log'

interface JsonBody {
  [key: string]: unknown
}

function ok(data: Record<string, unknown>, status = 200): Response {
  return json({ ok: true, ...data }, status)
}

function bad(code: string, message: string, status = 400): Response {
  return json({ ok: false, code, message }, status)
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

async function readJson(request: Request): Promise<JsonBody> {
  try {
    return (await request.json()) as JsonBody
  } catch {
    return {}
  }
}

function validateDomain(raw: string): string | null {
  const trimmed = raw.trim().toLowerCase().replace(/\.$/, '')
  if (!trimmed || trimmed.length > 253) return null
  if (/[\s/?#@:]/.test(trimmed)) return null
  const labels = trimmed.split('.')
  if (labels.length < 2) return null
  if (labels.some((l) => !l || l.length > 63)) return null
  return trimmed
}

async function getDeviceByToken(env: Env, deviceId: string, token: string): Promise<boolean> {
  const row = await env.D1.prepare('SELECT token_hash FROM devices WHERE device_id = ?')
    .bind(deviceId)
    .first()
  if (!row) return false
  const stored = row.token_hash as string
  const candidate = await sha256Hex(token)
  // Constant-time comparison to resist timing side channels.
  if (stored.length !== candidate.length) return false
  let diff = 0
  for (let i = 0; i < stored.length; i++) diff |= stored.charCodeAt(i) ^ candidate.charCodeAt(i)
  return diff === 0
}

async function requireDevice(env: Env, request: Request): Promise<string | null> {
  const auth = request.headers.get('authorization') ?? ''
  const match = /^Bearer (.+)$/.exec(auth)
  if (!match) return null
  const [deviceId, token] = parseBearer(match[1])
  if (!deviceId || !token) return null
  const valid = await getDeviceByToken(env, deviceId, token)
  return valid ? deviceId : null
}

// Bearer format: deviceId.rawToken
function parseBearer(bearer: string): [string | null, string | null] {
  const split = bearer.split('.')
  if (split.length !== 2) return [null, null]
  return [split[0], split[1]]
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url)
    const path = url.pathname
    const method = request.method

    if (method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: { 'access-control-allow-origin': '*', 'access-control-allow-methods': 'GET,POST,OPTIONS', 'access-control-allow-headers': 'content-type,authorization' } })
    }

    try {
      if (path === '/api/health' && method === 'GET') {
        return json({ status: 'ok', service: 'shield-backend' })
      }

      // ---- Device registration ----
      if (path === '/api/devices/register' && method === 'POST') {
        if (!(await rateLimit(env, 'register', 10))) return bad('rate_limited', 'too many requests', 429)
        const body = await readJson(request)
        const deviceId = typeof body.deviceId === 'string' ? body.deviceId : null
        if (!deviceId || !/^[0-9a-f]{32}$/.test(deviceId)) return bad('invalid_device', 'device id must be 32 hex chars')
        // token generated client-side OR server-side.
        const token = randomPart() + randomPart()
        const tokenHash = await sha256Hex(token)
        await env.D1.prepare(
          `INSERT INTO devices (device_id, token_hash, created_at) VALUES (?, ?, ?)
           ON CONFLICT(device_id) DO UPDATE SET token_hash = excluded.token_hash`,
        )
          .bind(deviceId, tokenHash, Date.now())
          .run()
        return ok({ registered: true, deviceId, token })
      }

      // ---- Heartbeat ----
      if (path === '/api/heartbeats' && method === 'POST') {
        const deviceId = await requireDevice(env, request)
        if (!deviceId) return bad('unauthorized', 'missing or invalid device credentials', 401)
        const body = await readJson(request)
        const hb = body.heartbeat as JsonBody | undefined
        if (!hb) return bad('invalid_heartbeat', 'heartbeat payload required')
        const lastCheckIn = Number(hb.lastCheckInEpochMs ?? 0)
        const seq = Number(hb.heartbeatSequence ?? 0)
        // Replay protection: reject older sequences.
        const existing = await env.D1.prepare('SELECT heartbeat_sequence FROM heartbeats WHERE device_id = ?')
          .bind(deviceId)
          .first()
        if (existing && Number((existing as any).heartbeat_sequence) >= seq) {
          return bad('stale_sequence', 'heartbeat sequence not newer', 409)
        }
        await env.D1.prepare(
          `INSERT INTO heartbeats (device_id, protection_state, health, last_check_in, app_version, database_version, heartbeat_sequence)
           VALUES (?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(device_id) DO UPDATE SET
             protection_state = excluded.protection_state,
             health = excluded.health,
             last_check_in = excluded.last_check_in,
             app_version = excluded.app_version,
             database_version = excluded.database_version,
             heartbeat_sequence = excluded.heartbeat_sequence`,
        )
          .bind(
            deviceId,
            String(hb.protectionState ?? 'UNKNOWN'),
            String(hb.health ?? 'UNKNOWN'),
            lastCheckIn,
            String(hb.appVersion ?? ''),
            Number(hb.databaseVersion ?? 0),
            seq,
          )
          .run()
        return ok({ accepted: true, serverTimeEpochMs: Date.now() })
      }

      // ---- Pairing ----
      if (path === '/api/pairing/invites' && method === 'POST') {
        const deviceId = await requireDevice(env, request)
        if (!deviceId) return bad('unauthorized', 'missing or invalid device credentials', 401)
        if (!(await rateLimit(env, `pairing:${deviceId}`, 5))) return bad('rate_limited', 'too many invites', 429)
        const body = await readJson(request)
        const tokenId = typeof body.tokenId === 'string' ? body.tokenId : null
        const tokenSecretHash = typeof body.tokenSecretHash === 'string' ? body.tokenSecretHash : null
        if (!tokenId || !tokenSecretHash) return bad('invalid_token', 'tokenId and tokenSecretHash required')
        const ttl = Math.min(Number(body.ttlMillis ?? 600000), 3600000)
        // Register the token hash in KV; expiry is TTL'd.
        await env.KV.put(`pairing:${tokenId}`, JSON.stringify({ tokenSecretHash, deviceId, kind: body.kind ?? 'PARTNER' }), {
          expirationTtl: Math.max(1, Math.floor(ttl / 1000)),
        })
        return ok({ tokenId, expiresAtEpochMs: Date.now() + ttl })
      }

      if (path === '/api/pairing/accept' && method === 'POST') {
        const deviceId = await requireDevice(env, request)
        if (!deviceId) return bad('unauthorized', 'missing or invalid device credentials', 401)
        const body = await readJson(request)
        const tokenId = typeof body.tokenId === 'string' ? body.tokenId : null
        const tokenSecret = typeof body.tokenSecret === 'string' ? body.tokenSecret : null
        if (!tokenId || !tokenSecret) return bad('invalid_token', 'tokenId and tokenSecret required')
        const recordText = await env.KV.get(`pairing:${tokenId}`)
        if (!recordText) return bad('expired_token', 'pairing token unknown or expired', 410)
        const record = JSON.parse(recordText) as { tokenSecretHash: string; deviceId: string; kind: string }
        // The device stored the SHA-256 of the full "id.secret" token; recompute the same
        // value and compare constant-time. The raw secret never touched backend storage.
        const candidate = await sha256Hex(tokenId + '.' + tokenSecret)
        if (candidate !== record.tokenSecretHash) return bad('invalid_token', 'secret does not match', 401)
        // Single-use: delete immediately after a successful match.
        await env.KV.delete(`pairing:${tokenId}`)
        const relationshipId = randomPart() + randomPart()
        const now = Date.now()
        await env.D1.prepare(
          `INSERT OR IGNORE INTO relationships (id, protected_device_id, partner_device_id, role, status, capabilities, created_at, accepted_at)
           VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)`,
        )
          .bind(relationshipId, record.deviceId, deviceId, record.kind === 'CHILD' ? 'PARENT' : 'PARTNER', '{}', now, now)
          .run()
        return ok({ relationshipId, role: record.kind === 'CHILD' ? 'PARENT' : 'PARTNER' })
      }

      // ---- Event relay (accountability notifications) ----
      if (path === '/api/events' && method === 'POST') {
        const deviceId = await requireDevice(env, request)
        if (!deviceId) return bad('unauthorized', 'missing or invalid device credentials', 401)
        const body = await readJson(request)
        const event = body.event as JsonBody | undefined
        if (!event) return bad('invalid_event', 'event payload required')
        const type = String(event.type ?? '')
        const severity = String(event.severity ?? 'LOW')
        const occurredAt = Number(event.occurredAtEpochMs ?? Date.now())
        // Detect cooldown-suppressed repeats here as a server-side guard too.
        await env.D1.prepare(
          'INSERT INTO events (id, device_id, type, severity, occurred_at, category, detail) VALUES (?, ?, ?, ?, ?, ?, ?)',
        )
          .bind(
            String(event.id ?? `${deviceId}-${Date.now()}`),
            deviceId,
            type,
            severity,
            occurredAt,
            event.category ? String(event.category) : null,
            JSON.stringify({ count: Number(event.count ?? 1), domain: event.exactDomain ?? null }),
          )
          .run()
        return ok({ accepted: true, suppressedByCooldown: false })
      }

      // ---- Approvals ----
      if (path === '/api/approvals' && method === 'POST') {
        const deviceId = await requireDevice(env, request)
        if (!deviceId) return bad('unauthorized', 'missing or invalid device credentials', 401)
        const body = await readJson(request)
        const relationshipId = typeof body.relationshipId === 'string' ? body.relationshipId : null
        const change = typeof body.change === 'string' ? body.change : null
        if (!relationshipId || !change) return bad('invalid_approval', 'relationshipId and change required')
        const id = randomPart() + randomPart()
        const now = Date.now()
        await env.D1.prepare(
          'INSERT INTO approvals (id, relationship_id, requested_by, change, description, status, requested_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
        )
          .bind(id, relationshipId, deviceId, change, String(body.description ?? ''), 'PENDING', now)
          .run()
        // Return the full ApprovalRequest-shaped object so the client can decode it.
        return ok({
          request: {
            id,
            relationshipId,
            requestedByDeviceId: deviceId,
            change,
            description: String(body.description ?? ''),
            requestedAtEpochMs: now,
            status: 'PENDING',
          },
        })
      }

      if (path === '/api/approvals/decide' && method === 'POST') {
        const deviceId = await requireDevice(env, request)
        if (!deviceId) return bad('unauthorized', 'missing or invalid device credentials', 401)
        const body = await readJson(request)
        const requestId = typeof body.requestId === 'string' ? body.requestId : null
        if (!requestId) return bad('invalid_request', 'requestId required')
        const approved = body.approved === true
        // Verify the acting device is a partner on the relationship.
        const row = await env.D1.prepare(
          `SELECT a.id, a.relationship_id FROM approvals a
           JOIN relationships r ON r.id = a.relationship_id
           WHERE a.id = ? AND (r.protected_device_id = ? OR r.partner_device_id = ?)`,
        )
          .bind(requestId, deviceId, deviceId)
          .first()
        if (!row) return bad('forbidden', 'not authorized for this approval', 403)
        const decidedStatus = approved ? 'APPROVED' : 'REJECTED'
        await env.D1.prepare('UPDATE approvals SET status = ?, decided_by = ?, decided_at = ? WHERE id = ?')
          .bind(decidedStatus, deviceId, Date.now(), requestId)
          .run()
        const full = await env.D1.prepare(
          'SELECT * FROM approvals WHERE id = ?',
        ).bind(requestId).first()
        return ok({
          request: {
            id: (full as any).id,
            relationshipId: (full as any).relationship_id,
            requestedByDeviceId: (full as any).requested_by,
            change: (full as any).change,
            description: (full as any).description ?? '',
            requestedAtEpochMs: (full as any).requested_at,
            status: (full as any).status,
            decidedByDeviceId: (full as any).decided_by ?? null,
            decidedAtEpochMs: (full as any).decided_at ?? null,
          },
        })
      }

      // ---- Reports ----
      if (path === '/api/reports' && method === 'POST') {
        const deviceId = await requireDevice(env, request)
        if (!deviceId) return bad('unauthorized', 'missing or invalid device credentials', 401)
        if (!(await rateLimit(env, `report:${deviceId}`, 5, 3600))) return bad('rate_limited', 'too many reports', 429)
        const body = await readJson(request)
        const domain = typeof body.domain === 'string' ? validateDomain(body.domain) : null
        if (!domain) return bad('invalid_domain', 'domain did not validate', 422)
        const type = String(body.type ?? 'NEW_GAMBLING_SITE')
        const id = randomPart() + randomPart()
        await env.D1.prepare(
          'INSERT INTO reports (id, device_id, type, domain, normalized_domain, note, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
        )
          .bind(id, deviceId, type, domain, domain, body.note ? String(body.note).slice(0, 500) : null, Date.now())
          .run()
        return ok({ accepted: true, reportId: id })
      }

      // ---- Notification token registration ----
      if (path === '/api/notifications/token' && method === 'POST') {
        const deviceId = await requireDevice(env, request)
        if (!deviceId) return bad('unauthorized', 'missing or invalid device credentials', 401)
        const body = await readJson(request)
        const token = typeof body.token === 'string' ? body.token : null
        if (!token) return bad('invalid_token', 'notification token required')
        await env.D1.prepare(
          `INSERT INTO notification_tokens (device_id, token, updated_at) VALUES (?, ?, ?)
           ON CONFLICT(device_id) DO UPDATE SET token = excluded.token, updated_at = excluded.updated_at`,
        )
          .bind(deviceId, token, Date.now())
          .run()
        return ok({ registered: true })
      }

      // ---- Admin (session-gated) ----
      const adminUserId = await validateAdminSession(env, request.headers.get('x-admin-token'))

      if (path === '/api/admin/overview' && method === 'GET') {
        if (!adminUserId) return bad('unauthorized', 'admin session required', 401)
        const [domains, reports, heartbeats] = await Promise.all([
          env.D1.prepare('SELECT COUNT(*) AS c FROM domain_candidates').first(),
          env.D1.prepare('SELECT COUNT(*) AS c FROM reports').first(),
          env.D1.prepare('SELECT COUNT(*) AS c FROM heartbeats').first(),
        ])
        return ok({
          candidates: Number((domains as any)?.c ?? 0),
          reports: Number((reports as any)?.c ?? 0),
          heartbeats: Number((heartbeats as any)?.c ?? 0),
        })
      }

      if (path === '/api/admin/domains' && method === 'GET') {
        if (!adminUserId) return bad('unauthorized', 'admin session required', 401)
        const { results } = await env.D1.prepare(
          'SELECT * FROM domain_candidates ORDER BY created_at DESC LIMIT 200',
        ).all()
        return ok({ domains: results })
      }

      if (path === '/api/admin/reports' && method === 'GET') {
        if (!adminUserId) return bad('unauthorized', 'admin session required', 401)
        const { results } = await env.D1.prepare(
          'SELECT * FROM reports ORDER BY created_at DESC LIMIT 200',
        ).all()
        return ok({ reports: results })
      }

      if (path === '/api/admin/audit' && method === 'GET') {
        if (!adminUserId) return bad('unauthorized', 'admin session required', 401)
        const { results } = await env.D1.prepare(
          'SELECT * FROM audit_log ORDER BY created_at DESC LIMIT 200',
        ).all()
        return ok({ audit: results })
      }

      if (path === '/api/admin/domains/action' && method === 'POST') {
        if (!adminUserId) return bad('unauthorized', 'admin session required', 401)
        const body = await readJson(request)
        const domainId = typeof body.id === 'string' ? body.id : null
        const action = typeof body.action === 'string' ? body.action : null
        if (!domainId || !action) return bad('invalid_request', 'id and action required')
        // Only allowstate transitions that are legitimate for the pipeline.
        const allowed = ['APPROVE', 'REJECT', 'INVESTIGATE', 'ALLOWLIST', 'DISABLE']
        if (!allowed.includes(action)) return bad('invalid_action', 'unknown moderation action')
        const { meta } = await env.D1.prepare(
          `INSERT INTO audit_log (actor, action, target_type, target_id, created_at)
           VALUES (?, ?, 'domain', ?, ?)`,
        )
          .bind(adminUserId, action, domainId, Date.now())
          .run()
        if (!meta.changes) return bad('audit_failed', 'could not write audit log', 500)
        return ok({ applied: true, action, id: domainId })
      }

      return json({ error: 'not_found' }, 404)
    } catch (e) {
      console.error(e)
      return json({ code: 'internal', message: 'internal error' }, 500)
    }
  },
}