/** Worker bindings declared in wrangler.toml. */
export interface Env {
  D1: D1Database
  KV: KVNamespace
  ADMIN_SESSION_TTL: string
}