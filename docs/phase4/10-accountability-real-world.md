# Shield — Accountability Real-World Validation Matrix (Phase 4 §11)

**Status:** JVM unit coverage exists (74 `core:accountability` tests). Real wire E2E is NOT TESTED until the backend is deployed (`12-backend-deployment-pack.md`) and a device is attached.

## Unit-verified (TESTED)
| Path | What's verified |
|---|---|
| Register device | device id + token → worker stores row; duplicate id returns existing; auth rejected |
| Heartbeat | health-state upsert; TTL governs staleness display |
| Create/accept pairing | invite code one-time; accept consumes; wrong-user rejected; expire/RRES paths rejected |
| Relay event | only linked partners receive; unlinked guarded (JVM) |
| Approval request + decide | request → parent decides allowed/denied; only allowed decision applies on child |
| Report upload | rate-limited; token-validated |
| Notification token | stored per device; invalid removed |
| Token expiry/reuse | expired/reused tokens denied (JVM) |
| Role privilege | client cannot claim ADMIN; parent vs supervised-child role separation JVM-tested |

## Wire-level matrix (to run when deployed — Section 12 pack)
| ID | Scenario | Expected | Result |
|---|---|---|---|
| W1 | Workflow: `POST /api/devices/register` (parent) → register (child) → `createPairing` → `acceptPairing` → child `heartbeat` → parent sees health + can decide approval | end-to-end success | ☐ |
| W2 | Register same device id twice | second call returns same row / no duplicate | ☐ |
| W3 | Accept with wrong/expired token | 403 + no state change | ☐ |
| W4 | Child tries `createPairing` (guard) | denied | ☐ |
| W5 | Revoke pairing then re-pair | old link severed; new works | ☐ |
| W6 | Admin session mint → protected route → TTL expiry → protected route | mint works; TTL expires access | ☐ |
| W7 | Report flood beyond rate limit | rate-limited | ☐ |

## Honest notes
- Token TTL 10 min (pairing), admin sessions 6 h — constants in the worker, adjust in deployment pack if product wants.
- Offline app behavior (queue) is designed but not yet E2E-tested on a real OS network flake; device matrix A2-A4 covers it once available.