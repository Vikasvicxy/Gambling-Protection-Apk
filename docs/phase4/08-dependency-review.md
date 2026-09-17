# Shield — Dependency / CVE Review (Phase 4 §20)

Method: version catalog + module dependency trees (`deps-release.txt`, `deps-debug.txt`) + current-stable web checks at review time (2026-09-17). Statuses: OK (no published advisory affecting our usage), REVIEWED with mitigations, DEFERRED.

## Android / JVM
| Dependency | Version used | Status | Notes |
|---|---|---|---|
| Kotlin Stdlib / plugin | 2.3.21 | **REVIEWED (CVE-2026-53914)** | Build-cache metadata unsafe deserialization ⇒ code-execution risk only if a build cache is attacker-controlled. Build-time, not shipped to app. Mitiations: local caches only; CI clean checkouts. Upgrade to Kotlin 2.4.20+ DEFERRED (risk of KSP/AGP compatibility churn before closed testing). |
| okhttp | 5.5.0 | OK | Public SSRF-class advisories target ≤ 5.0.0-alpha.14 (parent-5.3.2); 5.5.0 is the fixed stable line. 2026-06 leaked-connection reuse (PR oracle/reuse, #9240 fixed in #9508) only triggers when a `Response` body is leaked; all our call sites `use{}`-guard bodies (OkHttpBackendClient post + ReleaseDownloader stream). Not affected. |
| kotlinx-serialization-json | 1.11.0 | OK | Current stable; 1.11.0 release focused on `exceptionsWithDebugInfo` (privacy default for error strings). Consider setting `exceptionsWithDebugInfo = false` in a future hardening pass to keep request payloads out of thrown exceptions/logs (PR optional). |
| kotlinx-coroutines | 1.11.0 | OK | No published advisory at review time. |
| androidx.room | 2.8.5 | OK | Current stable (2.8.4 shown in docs; ours newer). |
| androidx.work | 2.11.2 | OK | Current stable; 2.11.2 fixed background-network constraint + NetworkStateTracker security-exception handling. |
| hilt | 2.60.1 | OK | No published advisory. |
| compose BOM | 2026.09.00 | OK | Current stable BOM. |
| material3 | (BOM) | OK | Covered by compose BOM. |
| androidx.navigation / datastore / lifecycle / etc. | BOM-managed | OK | No published advisory at review time. |
| AGP 9.4.0 | build tool | OK | Deprecations noted (kotlin-android plugin warning) are lifecycle noise, not CVEs. |
| Gradle 9.7.1 wrapper | build tool | OK | "Deprecated features / Gradle 10" warning is a forward-compat note, not a CVE. |

## Serverless worker (node)
| Dependency | Version | Status |
|---|---|---|
| wrangler | ^3.100.0 (devDep) | Dev-only CLI. No runtime CVE. |
| typescript | ^5.7.0 (devDep) | Dev-only. |
| @cloudflare/workers-types | ^4.20250210.0 (devDep) | Dev-only types. |
| Runtime (no deps) | — | Worker is dependency-free host runtime (no npm runtime deps in package.json). package-lock offline review found no critical advisories. |

## Resolved decision log
- **No action taken** on Coroutine, OKHTTP, serialization, Room, WorkManager, Hilt, Compose: all current stable.
- **CVE-2026-53914 (Kotlin):** documented; DEFERRED upgrade to 2.4.20+ to be executed after Phase 4 closed-testing regression, with re-run of full suite + verify No-KSP incompatibilities in a followup phase.
- **`exceptionsWithDebugInfo`** on serialization: optional hardening change flagged (not required; JSON payloads already contain only device/pairing data, no user content).

## Recommendation for a followup step (NOT DONE in Phase 4)
- Enable **Dependency-Check / SCA CI** (e.g., `npx osv-scanner` or `gradle` dependency-analysis plugin) to catch future advisories. Provide the exact CI command in the deployment pack §12 if a maintainer requests it.