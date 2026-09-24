# Shield — Phase 4 Hardening Addendum (post-86a52f7)

Addendum covering the hardening work performed after the `86a52f7`
"Phase 4 software hardening" commit and before the real-device run. This is the
remaining Phase 4 documentation for the new unit tests, the offline worker
test harness, and the server-side authorization fixes. It does not duplicate
`00-phase4-report.md`, `07-security-review.md`, or `12-backend-deployment-pack.md`;
it extends them with the new evidence and cross-references them for context.

Status vocabulary matches `00-phase4-report.md`:
**TESTED** = executed by unit/offline tests; **NOT TESTED** = needs a physical device
or a deployed backend.

## 1. Server-side authorization fixes (serverless/worker)

Two client-trust holes were closed in `src/index.ts`; both are now covered by the
offline worker test harness (§4).

| Endpoint | Before | After | Evidence |
|---|---|---|---|
| `POST /api/approvals` | Any authenticated device could open an approval against any known `relationshipId` (membership was only asserted client-side) | The worker now verifies the requesting device is a member of the relationship (`protected_device_id = ? OR partner_device_id = ?`); non-members get `403 forbidden` | `approval creation is blocked for devices outside the relationship` (IDOR guard) |
| `POST /api/approvals/decide` | An already-decided approval could be re-decided, letting a device flip the outcome | The decide query now requires `a.status = 'PENDING'`; decided approvals return `403` and cannot be re-decided | `approval lifecycle: open by member, decide by partner, no re-decide` |

These are defense-in-depth on the server side and complement the UI linkage gate in
`ParentViewModel` (`15-parent-policy-review.md` §UI guardrail) and the existing
`PartnerRelationshipManager` role checks (`10-accountability-real-world.md`).
**TESTED** (offline harness) / live attack-testing against a deployed worker remains
**NOT TESTED** (blocked on Cloudflare credentials; see `12-backend-deployment-pack.md`).

## 2. Fail-closed blocklist apply (data:update)

`BlocklistApplier.apply` now refuses an empty verified release unless the apply is an
explicitly-flagged signed rollback:

```
if (records.isEmpty() && !rollback) throw IllegalArgumentException("refusing to apply
an empty blocklist ... requires an explicit signed rollback")
```

Rationale: a signed-but-empty release must never silently wipe a user's protection.
The only intended way to ship a reduced set is a deliberate signed rollback. This
extends the existing fail-closed apply guarantees documented in `07-security-review.md`
§2 and `11-release-rollback-procedure.md` §Data safety.

Test coverage (`BlocklistUpdateEngineTest`, `data:update` — 3 new tests):
- Corrupt delta + valid full artifact → engine falls back to the full artifact and applies it (`applied full v2`, `KEY_DELTA_LAST_APPLIED=false`).
- Corrupt delta + corrupt full → full verify fails, previous good v1 preserved, state `FAILED`, version stays `1`.
- Empty verified release → apply refused, state `FAILED`, no wipe, message mentions `empty blocklist`.

**TESTED** (JVM/Robolectric). The hostile-server manual row (§27 of the report and
`07-security-review.md` §4) remains deferred to the device run.

## 3. Timing and capability audits (data:repository, core:accountability)

- **Commitment wall-clock independence** (`CommitmentEngineTest`, `data:repository` — 2 new tests):
  accumulated elapsed uses the monotonic clock only; shifting the wall clock a year
  backwards or forwards does not change `accumulatedElapsedMillis` nor make an active
  commitment finishable. A new commitment can still start normally after a legitimate
  completion. **TESTED.**
- **Exact capability mapping** (`PairingTokenServiceTest`, `core:accountability` — 2 new
  tests): each approval-required `SensitiveChange` maps to exactly one `PartnerCapability`;
  a different capability (or an empty set) is denied; `DISABLE_PROTECTION` deliberately has
  no capability mapping, so self-protection can never be switched off through an
  accountability approval. **TESTED.**

## 4. Offline worker test harness (serverless/worker)

A dependency-free offline test suite for the Cloudflare Worker now exists at
`serverless/worker/src/worker.test.ts` (19 tests). It runs outside any Cloudflare runtime:
D1 and KV are emulated in memory (`FakeD1` supports the exact SQL shapes the worker emits:
INSERT/UPDATE/SELECT with JOIN/ORDER BY/LIMIT; `FakeKV` honours TTLs) and the worker's
`fetch` handler is driven with the Node global `fetch`/`Request`.

Run it from `serverless/worker/`:

```
npm test                 # tsc -p tsconfig.test.json && node --test dist-test/worker.test.js
npm run typecheck        # tsc --noEmit (the production tsconfig excludes *.test.ts)
```

Coverage: unauthenticated access, forged/expired/replayed tokens, single-use pairing,
relationship-membership authorization (IDOR), approval no-re-decide, rate limits, report
validation, notification-token registration, admin session-gating, admin action allowlist,
unknown-route 404. **TESTED** — 19/19 locally. This closes (offline) the "no deployment
automation/test fixture beyond local unit tests" gap in `12-backend-deployment-pack.md` §3;
live wire E2E against the deployed worker is still **NOT TESTED**.

## 5. Log-noise reduction (protection:vpn)

`ShieldVpnService` per-query `BLOCK ...` / `ALLOW ...` DNS lines moved from info to debug
level so a release build's logcat is not spammed on every query. `protection:vpn` test task
still passes (8 tests). **TESTED** (compile + unit).

## 6. Test inventory delta

| Area | Module | New tests | Path |
|---|---|---|---|
| Capability mapping / non-approvable disable | `core:accountability` | +2 | `PairingTokenServiceTest.kt` |
| Commitment wall-clock independence | `data:repository` | +2 | `CommitmentEngineTest.kt` |
| Empty-release fail-closed + delta fallback | `data:update` | +3 | `BlocklistUpdateEngineTest.kt` |
| Worker authz/robustness | `serverless/worker` | +19 (offline) | `src/worker.test.ts` |

Full-suite totals after these additions are recorded in `00-phase4-report.md` §4 (final
regression run). Nothing here weakens or removes existing tests.