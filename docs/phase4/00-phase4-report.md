# Shield — Phase 4 Report (Production Hardening & Release Readiness)

**Date:** 2026-09-17 · **Baseline:** 319/319 tests green at start (Phase 3) · **Status:** HARDENED / RELEASE CANDIDATE BUILT — NOT production public-ready (see §30)

## Definitions
- **TESTED** — verified by executed unit tests and/or verified on-device.
- **PARTIALLY TESTED** — implemented but only partly verified (unit-covered; device/UI/backend not exercised).
- **NOT TESTED** — implemented or assumed, not verified.
- **DEFERRED** — intentionally left for a later phase or requires human-in-the-loop credentials/device.

---

## 1. Audit baseline (run before any change)
| Item | Rating | Evidence |
|---|---|---|
| Git state | **TESTED** | clean working tree on `master` at `d7c21a3`; tags `phase1-complete`, `phase2-complete`, `phase3-complete` present |
| Full automated suite (fresh re-run, `--rerun-tasks`) | **TESTED** | 319/319 passed, 0 failures/errors; 764 tasks executed |
| `assembleDebug` | **TESTED** | BUILD SUCCESSFUL; `app-debug.apk` (22.1 MB) |
| Phase 3 report / NOT TESTED items | **TESTED** | `docs/phase3/00-phase3-report.md` reviewed; residuals listed in §2 |
| Permissions declared | **TESTED** | INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, CHANGE_NETWORK_STATE (+ BIND_VPN_SERVICE / RECEIVE_BOOT_COMPLETED from modules). No overlay, SMS, location, contacts, storage, QUERY_ALL_PACKAGES, Accessibility, DeviceAdmin. |
| Production/release Gradle config | **TESTED** | minify+shrink release; signing was absent → Phase 4 added optional out-of-VCS signing (see §18) |
| Backend + notification abstractions | **TESTED** | `serverless/worker` + `core/model/Notification.kt` + `NotificationGateway` reviewed |

## 2. Phase 3 carry-forward NOT TESTED / deferred (still true unless verified here)
1. Physical Android VPN+DNS end-to-end traffic blocking — **NOT TESTED** (no physical device; §2 §6).
2. Production backend deployment — **NOT TESTED** (no Cloudflare credentials; pack prepared in `12-backend-deployment-pack.md`).
3. Real FCM push delivery — **DEFERRED** (`16-push-notifications.md`); local-only gateway today.
4. Final UI interaction matrix on representative devices — **NOT TESTED** (compile-only; §23 static review performed).
5. Broad OEM/bypass testing — **NOT TESTED** (matrix + guidance authored; `02-oem-matrix.md`, `03-bypass-matrix.md`).

## 3. Bugs found & fixed in Phase 4
| Bug | Severity | Fix | Verification |
|---|---|---|---|
| `HealthEngine.checkPermission` required an overlay grant the app never declares (`SYSTEM_ALERT_WINDOW`) → PERMISSION health permanently DEGRADED for every user | **real-world honesty bug** | removed overlay requirement; extracted pure `HealthPermissionAudit` | `HealthPermissionTest` (5) added; health module + app compile |
| `DnsUpstreamProvider` cached carrier resolvers up to 30 s across Wi-Fi→mobile transitions | **reliability** | registered default-network `NetworkCallback` that invalidates the resolver cache on `onAvailable`/`onLost`/`onCapabilitiesChanged` | compile + manual network-change check on device (pending §2) |
| `ParentViewModel.childScope` accepted any child id (linkage only asserted server-side) | **defense-in-depth** | UI now denies unless the child is in the observed parent relationships | compile verified |
| `serverless/worker/node_modules` (1,649 files) tracked in git + not ignored | **repo hygiene / supply-chain** | `node_modules/` added to `.gitignore`; untracked via `git rm -r --cached` (files retained locally; worker still type-checks) | `tsc --noEmit` exit 0 |
| Release variant had no signing path and `versionCode=1/0.1.0` | **release config** | optional `keystore.properties` (git-ignored) signing config; bumped `versionCode=2`, `versionName=0.2.0` | `assembleRelease` succeeds unsigned; signing activates only when keystore present |

## 4. Test totals after Phase 4 changes
| Item | Rating | Evidence |
|---|---|---|
| Full suite (fresh `--rerun-tasks`, after Phase 4 fixes) | **TESTED** | **324/324** (319 baseline + 5 new `HealthPermissionTest`) |
| New Phase 4 tests | **TESTED** | `protection:health` `HealthPermissionTest` 5/5 |
| Debug APK | **TESTED** | `assembleDebug` BUILD SUCCESSFUL |
| Release candidate (R8 + shrink) | **TESTED** | `assembleRelease` BUILD SUCCESSFUL; `app-release-unsigned.apk` 2,126,316 B |
| Worker typecheck | **TESTED** | `npx tsc --noEmit` exit 0 |

## 5. Physical-device results
No physical device is connected (`adb devices` empty). All on-device rows below are **NOT TESTED** until a device is attached; the executable plan is `01-real-device-test-matrix.md`. No emulator evidence is substituted for physical verification.

## 6. VPN / DNS real-device result
**NOT TESTED.** Architecture: local DNS-only IPv4 tun; blocked domains → NXDOMAIN; allowed → forwarded to carrier resolver (thread-bounded, 32 semaphore, 4 s timeouts, poll-based idle wait — no busy spin). Known honest limits documented in `10-known-limitations.md`: DoH/DoT/QUIC and IPv6 DNS flows can bypass a DNS-only VPN.

## 7. Performance results (code-review + JVM benchmark evidence)
- `docs/PERFORMANCE.md` JVM numbers stand: trie build 200k rules 152 ms; lookup 45–234 ns; full verify 354 ms; delta verify+apply 89 ms. Expected on-device parity is reasonable but **NOT TESTED** on hardware.
- VPN loop: poll-based (no busy-wait), per-packet allocation bounded by READ_SIZE (9,000 B), no per-query network dependency in the block path. Full on-device latency/throughput deltas **NOT TESTED** (§3 of the matrix).

## 8. Battery / background results
**NOT TESTED** on hardware. Code posture: VPN notification IMPORTANCE_LOW, `START_STICKY`, no scheduled wakeups beyond `DnsUpstreamProvider` 30 s in-process timer, WorkManager `ProtectionRecoveryWorker` + periodic update check. Battery-saver/doze/reboot matrix is §4 of `01-real-device-test-matrix.md`.

## 9. OEM coverage
Framework reviewed (`protection:oem` detects brands + guidance). Runtime matrix and per-vendor setup guidance authored in `02-oem-matrix.md`. No physical OEM devices available → all rows **NOT TESTED**.

## 10. Bypass test matrix
Authored (`03-bypass-matrix.md`). All rows **NOT TESTED** on hardware; classified honestly (TESTED / PARTIALLY TESTED / NOT TESTED / NOT APPLICABLE).

## 11. Accountability real-world result
Pairing→heartbeat→event→approval logic unit-covered (74 `core:accountability` tests). Real wire E2E over the deployed worker + device **NOT TESTED** (backend undepoloyed; device absent). Token expiry/reuse/wrong-user paths covered in JVM tests; revocation + replacement flow module-tested. `10-accountability-real-world.md` matrix prepared.

## 12. Parent-mode validation
Role separation audited: `PartnerRelationshipManager` denies client-declared ADMIN; parent relationships are a distinct `PARENT_OR_GUARDIAN`/`PARENT_SUPERVISED_CHILD` role; parent dashboard is aggregate-only (no browsing history) — consistent with Play parental-controls policy. UI-layer linkage gate added in Phase 4. Full statement in `15-parent-policy-review.md`. **PARTIALLY TESTED** (JVM tests + static review; no device run).

## 13. Backend deployment state
**LOCAL TESTED / PRODUCTION DEPLOYMENT NOT TESTED.** Worker typechecks; D1 migration + KV plan documented in `12-backend-deployment-pack.md` with exact `wrangler` commands, env/secrets list, rate limits, auth, and rollback. Requires human-supplied Cloudflare credentials.

## 14. Push-delivery state
**IMPLEMENTED / NOT END-TO-END TESTED.** Notification abstraction + local gateway + server-side `notification_tokens` exist; no FCM sender. Boundary and exact user steps in `16-push-notifications.md`. No Firebase credentials exist or are committed.

## 15. Admin-dashboard security result
- Auth: header-based one-time admin session (`x-admin-token`), minted off path, KV-backed, TTL. **TESTED (static)**
- Role: server-side session validation; no device can claim ADMIN. **TESTED (JVM)**
- Rendering: all dynamic fields rendered via `textContent` (XSS-safe); no admin credentials in frontend code. **TESTED (static)**
- CSRF: header-based auth (no cookies) — CSRF surface minimal. **PARTIALLY TESTED**
- Rate limiting on admin login: **DEFERRED** (see `12-backend-deployment-pack.md` gap notes).
- Not deployed; live pentest **NOT TESTED**.

## 16. Permissions (final)
INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, CHANGE_NETWORK_STATE, BIND_VPN_SERVICE. Play forms + rationale per permission: `04-play-store-policy-checklist.md`.

## 17. Privacy findings
No browsing history collected or transmitted; blocklist is local; accountability transmits aggregate events + heartbeat health states and pairing device ids (not URLs); admin stores pseudonymous device ids. Draft privacy policy `05-privacy-policy-draft.md`; Data Safety map `06-data-safety-draft.md`.

## 18. Release-build security
- Signing: optional `keystore.properties` outside VCS; no passwords committed; unsigned release builds OK. **TESTED**
- `isMinifyEnabled` + `isShrinkResources` true. **TESTED** (`app-release-unsigned.apk`)
- No debug URLs/BuildConfig flags (`buildConfig=false`); cleartext disabled in network security config; `allowBackup=false` + data-extraction rules. **TESTED (static)**
- Exported components: `MainActivity` (launcher), `BootReceiver` (permission-guarded); VpnService `exported=false` + BIND_VPN_SERVICE; startup provider `exported=false`. **TESTED (manifest review)**
- No WebView in app. **TESTED**

## 19. Secret-scan findings
Current tree + full git history scanned. **No secrets found.** Only flagged artifact is the intended public blocklist signing key asset. `REPLACE_ME_WITH_*` ids in `wrangler.toml` are placeholders (not secrets). `node_modules` untracked (see §3).

## 20. Dependency / CVE findings
- **Kotlin 2.3.21 affected by CVE-2026-53914** (build-cache metadata deserialization; build-time, not app runtime). Mitigation: local caches only, CI clean checkouts; upgrade to Kotlin 2.4.20+ deferred (KSP/plugin compatibility risk before release).
- OkHttp 5.5.0: SSRF-class issues affect ≤ 5.0.0-alpha.14 only (not us); leaked-connection reuse bug requires leaked Response bodies — all call sites `.use{}`-guard. Not affected.
- kotlinx-serialization 1.11.0, coroutines 1.11.0, Room 2.8.5, WorkManager 2.11.2, Hilt 2.60.1, Compose BOM 2026.09.00 — current stable; no published CVEs found at review time. Details: `08-dependency-review.md`.

## 21. Security attack-testing
Existing signed-update/tamper/replay protections re-confirmed by 229 baseline tests (verify order, fail-closed apply, stale-sequence rejection, constant-time compares). New Phase 4 issues covered by 5 new health tests. Standalone security notes + matrix: `07-security-review.md`. Live/malicious-input E2E **NOT TESTED** (needs deployed backend).

## 22. Crash / ANR validation
Logcat walk-through checklist prepared (`14-crash-anr-validation.md`). No instrumentation runner available (no device). Static review found no obvious uncaught runtime paths; tun loop isolates malformed-packet exceptions.

## 23. UI production polish
Static review of every major screen; no debug placeholders, no fake statistics, no developer-language. Dark/light via Material3 theme; text scaling and TalkBack semantics not device-verified. Details `13-ui-polish-review.md`.

## 24. Store assets
Specs + copy drafted (`11-store-and-rollout.md`): name, short/full descriptions, feature graphic + screenshot requirements, icon, privacy-policy URL (required before production), support email, honest release notes (no absolute claims).

## 25. Closed-testing package
`versionCode=2`, `versionName=0.2.0`, unsigned release-APK + signing instructions, release notes, test instructions, known limitations, feedback checklist — `11-store-and-rollout.md` §Closed testing.

## 26. Staged rollout
Internal → closed → 5–10 % production → wider; rollback triggers (crash/ANR spike, VPN instability, update-signature failure, connectivity failure, false-positive spike, battery complaints) — `11-store-and-rollout.md` §Staged rollout.

## 27. Blocklist release safety
Re-confirmed intact: canonical signature, canary, last-known-good, delta integrity, critical allowlist, HTTPS-only fetch, fail-closed apply. No bypass added. `08` residual item stands (guard for Robolectric-in-unit-tests not structural).

## 28. Test coverage
All 319 baseline tests remain and pass untouched; 5 new Phase 4 tests added. No test weakened.

## 29. Final regression
- Fresh clean build + full test suite: see §4 (324/324).
- Release candidate built (unsigned). Debug/release smoke on hardware pending device handoff.

## 30. Honest release readiness
- **READY FOR CLOSED TESTING:** yes — once a device is connected and the §5 matrix is executed, and the release APK is signed by the maintainer.
- **READY FOR PUBLIC PRODUCTION:** NO. Blocking items: physical-device VPN/DNS + battery + bypass validation, backend deployment, FCM push E2E, Play Console listing + Data Safety form submission + privacy-policy URL + closed-testing approval.

---

## Residual NOT TESTED / DEFERRED after Phase 4
1. Real-device VPN/DNS/blocking/battery/OEM/bypass (blocked on hardware) — `01-real-device-test-matrix.md`.
2. Production backend deployment + live admin dashboard (blocked on Cloudflare credentials) — `12-backend-deployment-pack.md`.
3. FCM push end-to-end (blocked on Firebase config) — `16-push-notifications.md`.
4. Play Console submission, store listing, Data Safety form, privacy-policy URL (human-in-the-loop) — `04/05/06`, `11`.
5. Kotlin 2.4.20+ upgrade for CVE-2026-53914 (build-time) — deferred.
6. On-hardware performance/battery numbers and browser matrix (Chrome/Firefox/Edge/Brave/Samsung Internet); private-browsing + DoH/DoT/QUIC/IPv6 verification.