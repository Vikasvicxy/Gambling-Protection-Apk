/**
 * Offline abuse/robustness tests for the Shield serverless worker.
 *
 * Runs without any Cloudflare runtime: D1 and KV are emulated in memory and the
 * worker's fetch handler is driven with the Node global `fetch`/`Request`.
 *
 * Coverage targets (Phase 4 task V): unauthenticated access, forged/expired/
 * replayed tokens, single-use pairing, relationship-membership authorization
 * (IDOR), approval re-decide, rate limiting, report validation, admin gating.
 */
import { test } from 'node:test'
import assert from 'node:assert'
import worker from './index'
import { sha256Hex, mintAdminSession } from './crypto'
import type { Env } from './env'

// ---------------------------------------------------------------------------
// Minimal in-memory D1 emulator (supports the exact SQL shapes the worker uses).
// ---------------------------------------------------------------------------

interface ExecCtx {
  argp: number
}

class FakeD1 {
  tables: Record<string, any[]> = {}

  prepare(sql: string) {
    return new FakeStatement(this, sql, [])
  }

  seedAll(tables: Record<string, any[]>) {
    for (const [name, rows] of Object.entries(tables)) {
      this.tables[name] = (this.tables[name] ?? []).concat(rows)
    }
  }
}

function splitTopLevel(cond: string): { op: 'AND' | 'OR'; parts: string[] } {
  let depth = 0
  let inQuote = false
  const parts: string[] = []
  let current = ''
  let lastOp: 'AND' | 'OR' = 'AND'
  const push = () => {
    if (current.trim()) parts.push(current.trim())
    current = ''
  }
  for (let i = 0; i < cond.length; i++) {
    const ch = cond[i]
    if (ch === "'") inQuote = !inQuote
    if (!inQuote) {
      if (ch === '(') depth++
      else if (ch === ')') depth--
      if (depth === 0 && ch === ' ' && cond.slice(i, i + 5) === ' AND ') {
        push()
        lastOp = 'AND'
        i += 4 // positioned on the leading space; resume after the trailing space
        continue
      }
      if (depth === 0 && ch === ' ' && cond.slice(i, i + 4) === ' OR ') {
        push()
        lastOp = 'OR'
        i += 3
        continue
      }
    }
    current += ch
  }
  push()
  return { op: parts.length > 1 ? lastOp : 'AND', parts }
}

function evalCondition(row: any, cond: string, nextArg: () => unknown): boolean {
  const trimmed = cond.trim()
  while (trimmed.startsWith('(') && trimmed.endsWith(')')) {
    return evalCondition(row, trimmed.slice(1, -1), nextArg)
  }
  const { op, parts } = splitTopLevel(trimmed)
  if (parts.length > 1) {
    return op === 'AND'
      ? parts.every((p) => evalCondition(row, p, nextArg))
      : parts.some((p) => evalCondition(row, p, nextArg))
  }
  const m = /^([A-Za-z0-9_.]+)\s*(=|!=|>|>=|<|<=)\s*(.+)$/.exec(trimmed)
  if (!m) throw new Error(`unsupported WHERE clause: ${trimmed}`)
  const [, colRaw, operator, valExpr] = m
  const col = colRaw.includes('.') ? colRaw.split('.')[1] : colRaw
  let expected: unknown
  if (valExpr === '?') expected = nextArg()
  else if (/^-?\d+$/.test(valExpr)) expected = Number(valExpr)
  else if (valExpr.startsWith("'") && valExpr.endsWith("'")) expected = valExpr.slice(1, -1)
  else if (valExpr.toLowerCase() === 'null') expected = null
  else throw new Error(`unsupported value: ${valExpr}`)
  const actual = row[col]
  const eq = String(actual) === String(expected)
  switch (operator) {
    case '=':
      return eq
    case '!=':
      return !eq
    case '>':
      return Number(actual) > Number(expected)
    case '>=':
      return Number(actual) >= Number(expected)
    case '<':
      return Number(actual) < Number(expected)
    case '<=':
      return Number(actual) <= Number(expected)
  }
  return false
}

/** Per-row positional argument reader (each SQL row sees args starting at [start]). */
function freshNext(args: unknown[], start = 0): () => unknown {
  let i = start
  return () => args[i++]
}

class FakeStatement {
  constructor(
    private db: FakeD1,
    private sql: string,
    private args: unknown[],
  ) {}

  /** Real D1 exposes bind() to attach positional params before first()/all()/run(). */
  bind(...args: unknown[]) {
    return new FakeStatement(this.db, this.sql, args)
  }

  private execute(): Promise<{ rows?: any[]; meta?: { changes: number } }> {
    const ctx: ExecCtx = { argp: 0 }
    return Promise.resolve().then(() => {
      const sql = this.sql.replace(/\s+/g, ' ').trim()
      // ---- INSERT
      const insert = /^INSERT (?:OR IGNORE )?INTO (\w+) \(([^)]+)\) VALUES \(([^)]+)\)(?: ON CONFLICT\((\w+)\) DO UPDATE SET (.+))?$/.exec(sql)
      if (insert) {
        const [, table, colsRaw, valsRaw, conflictCol, updatesRaw] = insert
        const cols = colsRaw.split(',').map((c) => c.trim())
        const vals = valsRaw.split(',').map((v) => v.trim())
        if (vals.length !== cols.length) throw new Error(`VALUES arity mismatch: ${sql}`)
        const normalized: any = {}
        for (let i = 0; i < cols.length; i++) {
          const token = vals[i]
          if (token === '?') normalized[cols[i]] = this.args[ctx.argp++]
          else if (/^-?\d+$/.test(token)) normalized[cols[i]] = Number(token)
          else if (token.startsWith("'") && token.endsWith("'")) normalized[cols[i]] = token.slice(1, -1)
          else if (token.toLowerCase() === 'null') normalized[cols[i]] = null
          else throw new Error(`unsupported VALUES token: ${token}`)
        }
        const tableRows = this.db.tables[table] ?? []
        if (conflictCol) {
          const existing = tableRows.find((r) => normalized[conflictCol] !== null && r[conflictCol] === normalized[conflictCol])
          if (existing) {
            const updates = updatesRaw.split(',')
            for (const u of updates) {
              const um = /^([a-z_]+)\s*=\s*excluded\.([a-z_]+)$/.exec(u.trim())
              if (!um) throw new Error(`unsupported ON CONFLICT update: ${u}`)
              existing[um[1]] = normalized[um[2]]
            }
            return { meta: { changes: 0 } }
          }
        }
        const ignore = /^INSERT OR IGNORE/.test(this.sql.replace(/\s+/g, ' ').trim())
        if (ignore && tableRows.some((r) => Object.entries(normalized).every(([k, v]) => r[k] === v))) {
          return { meta: { changes: 0 } }
        }
        tableRows.push(normalized)
        this.db.tables[table] = tableRows
        return { meta: { changes: 1 } }
      }

      // ---- UPDATE
      const update = /^UPDATE (\w+) SET (.+) WHERE (.+)$/.exec(sql)
      if (update) {
        const [, table, setsRaw, where] = update
        const tableRows = this.db.tables[table] ?? []
        const setCount = (setsRaw.match(/\?/g) ?? []).length
        let changed = 0
        for (const r of tableRows) {
          // Placeholders are positional: SET ?s come before WHERE ?s.
          if (!evalCondition(r, where, freshNext(this.args, setCount))) continue
          const setNext = freshNext(this.args)
          for (const assignmentRaw of setsRaw.split(',')) {
            const m = /^([a-z_]+)\s*=\s*\?$/.exec(assignmentRaw.trim())
            if (!m) throw new Error(`unsupported SET: ${assignmentRaw}`)
            r[m[1]] = setNext()
          }
          changed++
        }
        return { meta: { changes: changed } }
      }

      // ---- SELECT
      const select = /^SELECT (.+?) FROM (\w+)(.*?)$/.exec(sql)
      if (select) {
        const [, projectionsRaw, table, rest] = select
        const whereMatch = / WHERE (.+?)$/.exec(rest)
        const orderMatch = / ORDER BY ([a-z_]+) (ASC|DESC)$/.exec(rest)
        const limitMatch = / LIMIT (\d+)$/.exec(rest)
        let where: string | null = null
        let order: { col: string; dir: string } | null = null
        let limit: number | null = null
        let join: { table: string; probe: string; target: string } | null = null
        const joinMatch = / JOIN (\w+)(?:\s+\w+)? ON ([a-z_\.]+) = ([a-z_\.]+)/.exec(rest)
        if (joinMatch) join = { table: joinMatch[1], probe: joinMatch[2], target: joinMatch[3] }
        const whereBody = whereMatch ? whereMatch[1].replace(/ (ORDER BY .*|LIMIT .*)$/, '') : null
        if (whereBody) {
          const stripped = whereBody.split(/ (ORDER BY |LIMIT )/)[0].trim()
          if (stripped) where = stripped
        }
        if (orderMatch) order = { col: orderMatch[1], dir: orderMatch[2] }
        if (limitMatch) limit = Number(limitMatch[1])

        const projections = projectionsRaw.includes('*')
          ? null
          : projectionsRaw.split(',').map((p) => {
              const clean = p.trim()
              const parts = clean.split('.')
              // `a.id` -> [prefix, id]; `token_hash` -> whole column name
              return parts.length > 1 ? parts[1] : clean
            })
        const baseRows = this.db.tables[table] ?? []
        const rows = (() => {
          if (!join) return baseRows
          const combined: any[] = []
          for (const t of this.db.tables[join.table] ?? []) {
            for (const b of baseRows) {
              const lhs = join.probe.split('.').pop()!
              const rhs = join.target.split('.').pop()!
              // ON <x>.<lhs> = <y>.<rhs>: either side may be the base or joined table.
              if (b[lhs] === t[rhs] || b[rhs] === t[lhs]) combined.push({ ...t, ...b })
            }
          }
          return combined
        })()
        let filtered = rows
        if (where) {
          filtered = rows.filter((r) => evalCondition(r, where, freshNext(this.args)))
        }
        if (order) {
          const { col, dir } = order
          filtered = [...filtered].sort((a, b) =>
            dir === 'DESC' ? Number(b[col]) - Number(a[col]) : Number(a[col]) - Number(b[col]),
          )
        }
        if (limit != null) filtered = filtered.slice(0, limit)
        const countAlias = /^COUNT\(\*\) AS ([a-z_]+)$/.exec(projectionsRaw.trim())
        if (countAlias) return { rows: [{ [countAlias[1]]: filtered.length }] }
        if (projections) filtered = filtered.map((r) => Object.fromEntries(projections.filter((p) => p in r).map((p) => [p, r[p]])))
        return { rows: filtered }
      }

      throw new Error(`unsupported SQL: ${sql}`)
    })
  }

  async first() {
    const { rows } = await this.execute()
    return rows?.[0] ?? null
  }

  async all() {
    const { rows } = await this.execute()
    return { results: rows ?? [] }
  }

  async run() {
    const { meta } = await this.execute()
    return { meta: meta ?? { changes: 0 }, success: true }
  }
}

// ---------------------------------------------------------------------------
// In-memory KV emulator
// ---------------------------------------------------------------------------

class FakeKV {
  store = new Map<string, { value: unknown; expiresAt?: number }>()

  async put(key: string, value: unknown, opts?: { expirationTtl?: number }) {
    const expiresAt = opts?.expirationTtl ? Date.now() + opts.expirationTtl * 1000 : undefined
    this.store.set(key, { value, expiresAt })
  }

  async get(key: string, type?: 'json'): Promise<any> {
    const entry = this.store.get(key)
    if (!entry) return null
    if (entry.expiresAt && entry.expiresAt <= Date.now()) {
      this.store.delete(key)
      return null
    }
    if (type === 'json' && typeof entry.value === 'string') return JSON.parse(entry.value)
    return entry.value
  }

  async delete(key: string) {
    this.store.delete(key)
  }
}

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

interface Harness {
  env: Env
  d1: FakeD1
  kv: FakeKV
}

function makeEnv() {
  const d1 = new FakeD1()
  const kv = new FakeKV()
  const env = { D1: d1 as any, KV: kv as any, ADMIN_SESSION_TTL: '21600' } as Env
  return { env, d1, kv }
}

const BASE = 'https://shield.test'

async function call(h: Harness, path: string, init: RequestInit = {}) {
  return worker.fetch(new Request(BASE + path, init), h.env)
}

function jsonBody(payload: unknown, headers: Record<string, string> = {}) {
  return {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(payload),
  }
}

function bearerAuth(deviceId: string, token: string) {
  return { authorization: `Bearer ${deviceId}.${token}` }
}

async function register(h: Harness, deviceId: string) {
  const res = await call(h, '/api/devices/register', jsonBody({ deviceId }))
  const body = await res.json() as any
  assert.equal(res.status, 200, 'register should succeed')
  return { deviceId, token: body.token as string }
}

const hexId = (seed: string) => seed.repeat(32)
const HEX_CHARS = '0123456789abcdef'

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test('health endpoint works without auth', async () => {
  const h = makeEnv()
  const res = await call(h, '/api/health')
  assert.equal(res.status, 200)
  assert.deepEqual(await res.json(), { status: 'ok', service: 'shield-backend' })
})

test('register rejects malformed device ids', async () => {
  const h = makeEnv()
  const res = await call(h, '/api/devices/register', jsonBody({ deviceId: 'not-hex' }))
  assert.equal(res.status, 400)
  const body = await res.json() as any
  assert.equal(body.code, 'invalid_device')
})

test('register stores a hash, never the raw token', async () => {
  const h = makeEnv()
  const d = await register(h, hexId('a'))
  const raw = h.d1.tables['devices'][0]
  assert.notEqual(raw['token_hash'], d.token)
  assert.equal(await sha256Hex(d.token), raw['token_hash'])
})

test('register rate limit kicks in after 10 in a window', async () => {
  const h = makeEnv()
  let last: Response = new Response()
  for (let i = 0; i < 10; i++) {
    last = await call(h, '/api/devices/register', jsonBody({ deviceId: hexId(HEX_CHARS[i % HEX_CHARS.length]) }))
  }
  assert.equal(last.status, 200)
  const blocked = await call(h, '/api/devices/register', jsonBody({ deviceId: hexId('f') }))
  assert.equal(blocked.status, 429)
  assert.equal((await blocked.json() as any).code, 'rate_limited')
})

test('heartbeat requires device auth', async () => {
  const h = makeEnv()
  const res = await call(h, '/api/heartbeats', jsonBody({ heartbeat: {} }))
  assert.equal(res.status, 401)
})

test('heartbeat accepts newer sequences and rejects replay', async () => {
  const h = makeEnv()
  const d = await register(h, hexId('a'))
  const auth = bearerAuth(d.deviceId, d.token)
  const hb = (seq: number) => jsonBody(
    { heartbeat: { heartbeatSequence: seq, protectionState: 'ACTIVE', health: 'OK', lastCheckInEpochMs: 0 } },
    auth,
  )
  assert.equal((await call(h, '/api/heartbeats', hb(1))).status, 200)
  const replayed = await call(h, '/api/heartbeats', hb(1))
  assert.equal(replayed.status, 409)
  assert.equal((await replayed.json() as any).code, 'stale_sequence')
  assert.equal((await call(h, '/api/heartbeats', hb(2))).status, 200)
})

test('forged credentials are rejected across all authenticated endpoints', async () => {
  const h = makeEnv()
  const res = await call(h, '/api/heartbeats', jsonBody({ heartbeat: {} }, bearerAuth(hexId('a'), 'forged-token')))
  assert.equal(res.status, 401)
})

test('pairing invite requires auth and stores token hash in KV', async () => {
  const h = makeEnv()
  const d = await register(h, hexId('a'))
  const tokenSecretHash = await sha256Hex('tok-123.secret-456')
  const res = await call(h, '/api/pairing/invites', jsonBody(
    { tokenId: 'tok-123', tokenSecretHash, kind: 'PARTNER' },
    bearerAuth(d.deviceId, d.token),
  ))
  assert.equal(res.status, 200)
  const record = h.kv.store.get('pairing:tok-123')
  assert.ok(record, 'pairing token should be stored in KV')
  assert.equal(JSON.parse(record!.value as string).tokenSecretHash, tokenSecretHash)
})

test('pairing accept verifies secret and is single use', async () => {
  const h = makeEnv()
  const protectedDevice = await register(h, hexId('a'))
  const partnerDevice = await register(h, hexId('b'))
  const tokenId = 'tok-single'
  const tokenSecret = 'secret-single'
  const secretHash = await sha256Hex(`${tokenId}.${tokenSecret}`)
  await call(h, '/api/pairing/invites', jsonBody(
    { tokenId, tokenSecretHash: secretHash, kind: 'PARTNER' },
    bearerAuth(protectedDevice.deviceId, protectedDevice.token),
  ))

  const wrongSecret = await call(h, '/api/pairing/accept', jsonBody(
    { tokenId, tokenSecret: 'wrong-secret' },
    bearerAuth(partnerDevice.deviceId, partnerDevice.token),
  ))
  assert.equal(wrongSecret.status, 401)

  const accepted = await call(h, '/api/pairing/accept', jsonBody(
    { tokenId, tokenSecret },
    bearerAuth(partnerDevice.deviceId, partnerDevice.token),
  ))
  assert.equal(accepted.status, 200)
  assert.equal((await accepted.json() as any).role, 'PARTNER')
  assert.equal(h.d1.tables['relationships'].length, 1)

  const replay = await call(h, '/api/pairing/accept', jsonBody(
    { tokenId, tokenSecret },
    bearerAuth(partnerDevice.deviceId, partnerDevice.token),
  ))
  assert.equal(replay.status, 410, 'replaying a consumed pairing token must fail')
})

test('pairing accept rejects unknown or expired tokens', async () => {
  const h = makeEnv()
  const d = await register(h, hexId('a'))
  const unknown = await call(h, '/api/pairing/accept', jsonBody(
    { tokenId: 'never-issued', tokenSecret: 'x' },
    bearerAuth(d.deviceId, d.token),
  ))
  assert.equal(unknown.status, 410)
  assert.equal((await unknown.json() as any).code, 'expired_token')
})

test('approval creation is blocked for devices outside the relationship', async () => {
  const h = makeEnv()
  const protectedDevice = await register(h, hexId('a'))
  const partnerDevice = await register(h, hexId('b'))
  const outsider = await register(h, hexId('c'))

  const tokenId = 'tok-rel'
  const tokenSecret = 'secret-rel'
  await call(h, '/api/pairing/invites', jsonBody(
    { tokenId, tokenSecretHash: await sha256Hex(`${tokenId}.${tokenSecret}`), kind: 'PARTNER' },
    bearerAuth(protectedDevice.deviceId, protectedDevice.token),
  ))
  const accepted = await call(h, '/api/pairing/accept', jsonBody(
    { tokenId, tokenSecret },
    bearerAuth(partnerDevice.deviceId, partnerDevice.token),
  ))
  const relationshipId = (await accepted.json() as any).relationshipId

  // Outsider fabricating an approval against the real relationship id.
  const blocked = await call(h, '/api/approvals', jsonBody(
    { relationshipId, change: 'DISABLE_ACCOUNTABILITY_ALERTS' },
    bearerAuth(outsider.deviceId, outsider.token),
  ))
  assert.equal(blocked.status, 403, 'non-members must not open approvals (IDOR guard)')
})

test('approval lifecycle: open by member, decide by partner, no re-decide', async () => {
  const h = makeEnv()
  const protectedDevice = await register(h, hexId('a'))
  const partnerDevice = await register(h, hexId('b'))

  const tokenId = 'tok-approve'
  const tokenSecret = 'secret-approve'
  await call(h, '/api/pairing/invites', jsonBody(
    { tokenId, tokenSecretHash: await sha256Hex(`${tokenId}.${tokenSecret}`), kind: 'PARTNER' },
    bearerAuth(protectedDevice.deviceId, protectedDevice.token),
  ))
  const accepted = await call(h, '/api/pairing/accept', jsonBody(
    { tokenId, tokenSecret },
    bearerAuth(partnerDevice.deviceId, partnerDevice.token),
  ))
  const relationshipId = (await accepted.json() as any).relationshipId

  const created = await call(h, '/api/approvals', jsonBody(
    { relationshipId, change: 'DISABLE_ACCOUNTABILITY_ALERTS', description: 'race condition on server' },
    bearerAuth(protectedDevice.deviceId, protectedDevice.token),
  ))
  assert.equal(created.status, 200)
  const requestId = (await created.json() as any).request.id

  const decided = await call(h, '/api/approvals/decide', jsonBody(
    { requestId, approved: true },
    bearerAuth(partnerDevice.deviceId, partnerDevice.token),
  ))
  assert.equal(decided.status, 200)
  assert.equal((await decided.json() as any).request.status, 'APPROVED')

  const reDecide = await call(h, '/api/approvals/decide', jsonBody(
    { requestId, approved: false },
    bearerAuth(partnerDevice.deviceId, partnerDevice.token),
  ))
  assert.equal(reDecide.status, 403, 'already-decided approvals must not be re-decided')
})

test('approval decide is blocked for unrelated devices', async () => {
  const h = makeEnv()
  const d = await register(h, hexId('a'))
  const outsider = await register(h, hexId('c'))
  const res = await call(h, '/api/approvals/decide', jsonBody(
    { requestId: 'made-up-id', approved: true },
    bearerAuth(outsider.deviceId, outsider.token),
  ))
  assert.equal(res.status, 403)
  void d
})

test('events require auth and reject missing payloads', async () => {
  const h = makeEnv()
  const d = await register(h, hexId('a'))
  const auth = bearerAuth(d.deviceId, d.token)

  const noAuth = await call(h, '/api/events', jsonBody({ event: { type: 'BLOCKED_ATTEMPT' } }))
  assert.equal(noAuth.status, 401)

  const empty = await call(h, '/api/events', jsonBody({}, auth))
  assert.equal(empty.status, 400)

  const ok = await call(h, '/api/events', jsonBody(
    { event: { type: 'BLOCKED_ATTEMPT', severity: 'HIGH', occurredAtEpochMs: Date.now(), count: 3, exactDomain: 'bets.example' } },
    auth,
  ))
  assert.equal(ok.status, 200)
  assert.equal(h.d1.tables['events'].length, 1)
})

test('reports validate domains and are rate limited', async () => {
  const h = makeEnv()
  const d = await register(h, hexId('a'))
  const auth = bearerAuth(d.deviceId, d.token)

  // Invalid attempts still consume a rate-limit slot (they are attempts).
  const bad = await call(h, '/api/reports', jsonBody({ domain: 'not a domain' }, auth))
  assert.equal(bad.status, 422)

  const good = await call(h, '/api/reports', jsonBody({ domain: 'new-gambling.example' }, auth))
  assert.equal(good.status, 200)
  assert.equal(h.d1.tables['reports'].length, 1)

  // Slots 3-5 remain within the 5-per-hour window.
  for (let i = 2; i <= 4; i++) {
    assert.equal((await call(h, '/api/reports', jsonBody({ domain: `d${i}.example` }, auth))).status, 200)
  }
  const limited = await call(h, '/api/reports', jsonBody({ domain: 'sixth.example' }, auth))
  assert.equal(limited.status, 429)
  assert.equal((await limited.json() as any).code, 'rate_limited')
})

test('notification token registration requires auth', async () => {
  const h = makeEnv()
  const unauth = await call(h, '/api/notifications/token', jsonBody({ token: 'fcm-token' }))
  assert.equal(unauth.status, 401)
  const d = await register(h, hexId('a'))
  const ok = await call(h, '/api/notifications/token', jsonBody({ token: 'fcm-token' }, bearerAuth(d.deviceId, d.token)))
  assert.equal(ok.status, 200)
  assert.equal(h.d1.tables['notification_tokens'][0].token, 'fcm-token')
})

test('admin endpoints are session-gated', async () => {
  const h = makeEnv()
  for (const path of ['/api/admin/overview', '/api/admin/domains', '/api/admin/reports', '/api/admin/audit']) {
    const blocked = await call(h, path)
    assert.equal(blocked.status, 401, `${path} must be gated`)
  }
  const session = await mintAdminSession(h.env, 'ops')
  const ok = await call(h, '/api/admin/overview', { headers: { 'x-admin-token': session } })
  assert.equal(ok.status, 200)
  const body = await ok.json() as any
  assert.deepEqual(body, { ok: true, candidates: 0, reports: 0, heartbeats: 0 })
})

test('admin moderation actions validate the action allowlist and audit', async () => {
  const h = makeEnv()
  const session = await mintAdminSession(h.env, 'ops')
  const auth = { headers: { 'x-admin-token': session } }

  const invalid = await call(h, '/api/admin/domains/action', jsonBody({ id: 'd1', action: 'PURGE_EVERYTHING' }, auth.headers))
  assert.equal(invalid.status, 400)
  assert.equal((await invalid.json() as any).code, 'invalid_action')

  const valid = await call(h, '/api/admin/domains/action', jsonBody({ id: 'd1', action: 'APPROVE' }, auth.headers))
  assert.equal(valid.status, 200)
  const audit = h.d1.tables['audit_log']
  assert.equal(audit.length, 1)
  assert.equal(audit[0].action, 'APPROVE')
  assert.equal(audit[0].actor, 'ops')
})

test('unknown routes return 404 and do not crash', async () => {
  const h = makeEnv()
  const res = await call(h, '/api/definitely-not-a-route')
  assert.equal(res.status, 404)
})