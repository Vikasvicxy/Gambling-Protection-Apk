# Shield — Performance & Battery Methodology (Phase 4 §7–§8)

No physical device attached as of this writing → **code-review + JVM numbers** are the evidence; on-device rows are in `01-real-device-test-matrix.md` §3–§4 and are NOT TESTED until a device is connected.

## What we can state now (TESTED via JVM)
From `docs/PERFORMANCE.md` (executed, not speculative):
- DNS-trie build of 200k rule set: **152 ms**
- Lookup: **45–234 ns** per query
- Full blocklist verify: **354 ms**; delta verify+apply: **89 ms**
- Updates are delta-based (one rule-change pushes a small bundle — no full re-download).

## VPN-loop design (static review; on-device numbers pending)
- Poll-based idle wait (sleep until next packet); no busy-spin → idle CPU near zero.
- Per-packet buffer bounded by READ_SIZE (9,000 B); no unbounded allocations.
- Blocked domains → immediate NXDOMAIN (no upstream fetch); allowed domains → carrier resolver with 4 s timeout, thread-bounded (32-way semaphore) — no connection pool explosion.
- `DnsUpstreamProvider` now invalidates resolver cache on any network change (Phase 4 fix) so failing-over to a new carrier network cannot stall on stale DNS.
- 30 s in-process DNS refresh timer; feature does not schedule out-of-process work.

## Battery posture (static review)
- VPN foreground service: `IMPORTANCE_LOW` notification, `START_STICKY`.
- No periodic background CPU except: WorkManager `ProtectionRecoveryWorker` (event-driven), periodic update check (server says delta/no-op when unchanged), phoneless idle.
- No location, no sensor polling; Doze-compatible (no `expedited` except recovery).
- Battery Optimization: exempt request path present in HealthEngine (`batteryOptimizationExempt`), surfaced in health detail.

## On-device measurement plan (to run when a device is attached)
See `01-real-device-test-matrix.md` §3 (perf) and §4 (battery). Required values:
- Start-to-VPN-ready latency
- p50/p95 page load with VPN on vs off (IPv4; note DNS-only)
- Idle CPU%, idle memory RSS
- Query-latency growth with rule count (1k/10k/200k)
- Battery %/h idle with VPN on (2 h window)
- Thermal trend on a 15 min video loop
- Reboot→protected-state latency

## Honest conclusions
- Code-level budget and JVM numbers give confidence.
- **No on-device number is claimed.** A "performance verified" banner must not appear in store copy until the matrix rows are populated.