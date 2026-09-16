# Health Reporting — Phase 1

Module: `protection/health` — a single `HealthEngine` aggregates device + protection health
into one digestible report. 0 dedicated unit tests in Phase 1; the aggregation model is
covered by `core/model`'s `HealthReportTest` (8 tests).

## Model (`core/model/.../Health.kt`)

- `HealthStatus`: HEALTHY, DEGRADED, CRITICAL, UNKNOWN.
- `HealthComponent`: VPN, DNS, BLOCKLIST, BOOT, BATTERY, PERMISSION, NETWORK, COMMITMENT,
  UPDATE.
- `ComponentHealth`: component, status, message, `measuredAtEpochMs`, optional `detail` map.
- `HealthReport`: `overall`, sorted `components`, `measuredAtEpochMs`; `component()` lookup.

### Aggregation (`HealthReport.aggregate`)

Worst-of ordering — **CRITICAL > DEGRADED > UNKNOWN > HEALTHY**: any CRITICAL makes the whole
report CRITICAL, else any DEGRADED -> DEGRADED, else any UNKNOWN -> UNKNOWN, else HEALTHY.
Components are emitted in enum-ordinal order for stable presentation.

## Checks (`HealthEngine.measure`, `HealthEngine.kt`)

| Component | Signal | Status mapping |
|---|---|---|
| VPN | `VpnStateStore` runtime state | running/paused-by-schedule -> HEALTHY; revoked -> CRITICAL; not running -> DEGRADED; unknown -> UNKNOWN |
| DNS | `VpnStateStore` query counters | VPN off -> UNKNOWN; 0 queries -> DEGRADED; else HEALTHY with `{queriesHandled}/{queriesBlocked}` |
| BLOCKLIST | `BlocklistRepository` state/stats | index missing -> CRITICAL; else HEALTHY with v{version}, enabled rule count, digest-10 |
| NETWORK | `NetworkInfoProvider.isOnline` | validated internet capability present/absent |
| BATTERY | `OemInfoRepository.batteryStatus` | optimization exemption, device idle, charging |
| PERMISSION | notifications (SDK 33+), overlay, battery-optimization exemption | |
| COMMITMENT | `CommitmentEngine.active` | `canFinish`, durations surfaced |
| BOOT | static "receiver declared" | informational |
| UPDATE | `UpdateRepository.state` | FAILED -> CRITICAL; `NOT_CONFIGURED` -> informational |

## Consumers

- `feature:dashboard`: the health card on the home screen.
- `feature:diagnostics`: full per-component report + "Re-run checks" (`HealthEngine.measure()`)
  plus OEM guidance and VPN-conflict flags.

## Coverage

`HealthReportTest` (core/model, 8 tests) pins worst-of aggregation, per-component lookup,
sorting, and timestamps. `HealthEngine` itself has no unit tests in Phase 1 — the components
it reads from (`VpnStateStore`, `BlocklistStats`, etc.) are individually tested, and the
engine's Android/platform reads remain on-device verification (see limitations doc).