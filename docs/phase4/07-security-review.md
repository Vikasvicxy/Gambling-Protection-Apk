# Shield — Security Review & Attack-Testing Notes (Phase 4 §19–§21)

Scope: review findings + attack-test boundaries. Honest statuses throughout.

## 1. Secret-scan results (Phase 4)
- Full `git log -p` scan for high-entropy/password patterns: only node_modules type definitions (now untracked) — no secrets.
- Tracked-file scan: only intended `data/update/src/main/assets/update_signing_public_key.pem` (public key, part of the signed-update design).
- `wrangler.toml` contains `REPLACE_ME_WITH_*` placeholders (not secrets) — deployment requires filling real values outside VCS.
- No `keystore.properties`, no `*.jks`, no cloud creds in tree or history.

## 2. Signed-update integrity (existing protections, re-checked)
- Update fetch: HTTPS-only, pinned host rule, hard size caps, bounded redirects, `Use`-guarded streams. Re-confirmed ReleaseDownloader doesn't accept non-HTTPS; body never cached. TESTED.
- Verification: canonical signature, canary, last-known-good, delta integrity, critical allowlist, fail-closed apply: covered by 229 tests (Robolectric+JVM). TESTED.
- Key assumption: signed-update keys never embedded in the update endpoint; public key distributed in APK asset. TESTED (design review).

## 3. Attack surfaces
| Surface | Exposure | Control | Tested |
|---|---|---|---|
| Backend auth | session token (admin) / pairing | TTL'd sessions, header auth, server-side role | JVM tests on token expiry/reuse/wrong-user; live attack NOT TESTED |
| Backend endpoints | register/heartbeat/pairing/approve/report/token | D1 writes + KV storage; rate limits | Unit-tested; production NOT TESTED |
| Admin dashboard | browser session | header x-admin-token, textContent rendering (no HTML injection), no cookie CSRF surface | STATIC TESTED + local unit; live NOT TESTED |
| VPN parser | IP/UDP malformed frames | per-frame isolation, no buffer overruns (ByteBuffer bounds) | fuzz tests in `protection:vpn`; NOT TESTED on hostile device |
| Update channel | CDN compromise | signature-enforced, fail-closed | TESTED (tests) |
| Local storage | Room DB (pairing, config) | isolated app sandbox; no device ID written into obvious path | PARTIALLY TESTED |
| IPC / components | exported BootReceiver | permission-gated `RECEIVE_BOOT_COMPLETED`; all other DCC not exported | TESTED (manifest review) |

## 4. Hostile-input tests (new row for future matrix)
Add to the real-device matrix when possible: point app at a hostile sandbox update server returning garbage bytes/huge sizes → assert fail-closed behavior (no crash, no apply, error surfaced). Manual step documented here (not a new unit unless we ship a helper to point UpdateConfig at a local URL). DEFERRED until device and test harness.

## 5. Honest conclusion
**Build-time supply-chain note:** Kotlin 2.3.21 is affected by CVE-2026-53914 (build-cache metadata deserialization). Exploit requires a compromised build cache and only affects the build machine, not the shipped app. Mitigation: use local build caches only; CI should use clean checkouts; upgrade to Kotlin 2.4.20+ is tracked in `08-dependency-review.md` as DEFERRED until post-closed-testing to avoid KSP/plugin churn immediately before a release.