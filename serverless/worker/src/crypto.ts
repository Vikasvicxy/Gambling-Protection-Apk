import { Env } from './env'

/** Minimal SHA-256 hex util returning a promise for the digest, using Web Crypto. */
export async function sha256Hex(input: string | ArrayBuffer): Promise<string> {
  const data = typeof input === 'string' ? new TextEncoder().encode(input) : input
  const digest = await crypto.subtle.digest('SHA-256', data as BufferSource)
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

/** Constant-time string comparison to avoid leaking token info via timing. */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false
  let result = 0
  for (let i = 0; i < a.length; i++) result |= a.charCodeAt(i) ^ b.charCodeAt(i)
  return result === 0
}

/** High-entropy random part (128 bits) rendered base64url-ish. */
export function randomPart(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** Sliding-window rate limit with KV counters. */
export async function rateLimit(
  env: Env,
  key: string,
  limit: number,
  windowSeconds = 60,
): Promise<boolean> {
  const now = Math.floor(Date.now() / 1000)
  const bucketKey = `rl:${key}:${Math.floor(now / windowSeconds)}`
  const current = Number((await env.KV.get(bucketKey, 'json')) ?? 0)
  if (current >= limit) return false
  await env.KV.put(bucketKey, String(current + 1), { expirationTtl: windowSeconds + 60 })
  return true
}

/** Simple server-side session minting for admin tooling (passkey-ready placeholder). */
export async function mintAdminSession(env: Env, userId: string): Promise<string> {
  const sessionToken = randomPart() + randomPart()
  await env.KV.put(
    `admin-session:${sessionToken}`,
    JSON.stringify({ userId, createdAt: Date.now() }),
    { expirationTtl: Number(env.ADMIN_SESSION_TTL ?? '21600') },
  )
  return sessionToken
}

export async function validateAdminSession(env: Env, token: string | null): Promise<string | null> {
  if (!token) return null
  const record = await env.KV.get(`admin-session:${token}`, 'json')
  return record ? (record as any).userId ?? null : null
}