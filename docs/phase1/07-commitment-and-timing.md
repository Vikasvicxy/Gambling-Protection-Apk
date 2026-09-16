# Commitment & Timing Anchors — Phase 1

## What a commitment is

A user-declared protection goal: stay protected for at least `intendedDurationMs` of
**real elapsed time**. Model in `core/model/.../Commitment.kt`:

- `ProtectionMode`: `SELF_PROTECTION` (Phase 1); `ACCOUNTABILITY`/`PARENTAL` reserved.
- `ProtectionLevel`: `DNS_DOMAIN_BLOCKING`.
- `Commitment`: id, mode, level, state (`NONE`/`ACTIVE`/`COMPLETED`), wall-clock display
  fields (`createdAtEpochMs`, `startEpochMs`, `endEpochMs`), and — authoritative — monotonic
  accounting (`accumulatedElapsedMillis`, `lastAccountedElapsedMs`), plus
  `bootCountAtCreation`, `extensionCount`, `canFinish`.
- Duration options: 24h, 3d, 7d, 14d, 30d, 3mo, 6mo, 1y, custom; `MIN_DURATION_MILLIS = 1h`,
  `MAX_DURATION_MILLIS ≈ 2 years`.

## The anti-tamper core: monotonic time

Wall clock is **display-only**. The elapsed counter is derived from
`MonotonicClock` (`elapsedRealtime`) deltas, so winding the device clock back cannot reduce a
commitment's accumulated time.

`CommitmentEngine` (`data/repository/.../CommitmentEngine.kt`):
- `tick()` runs on every foreground entry (`MainActivity` observes
  `ProcessLifecycleOwner.onStart`): `delta = monotonic.now() - lastAccountedElapsedMs`,
  added to `accumulatedElapsedMillis`; `canFinish = accumulated >= intendedDurationMs`.
- `extendBy(extraMillis)`: grows `intendedDurationMs` and `endEpochMs` (never shrinks), capped
  at `MAX_DURATION_MILLIS`, increments `extensionCount`, resets `canFinish`.
- `finish()`: only when `state == ACTIVE && canFinish`; transitions to `COMPLETED`.
- `active` is exposed to `HealthEngine.checkCommitment` and the dashboard progress card.

## Persistent anchor (`TimingAnchorRepository`, DataStore `shield_timing`)

`TimingAnchor`: `firstRunEpochMs`, `lastResumeEpochMs`, `lastResumeElapsedMs`,
`lastKnownBootCount`, `elapsedAccumulatedMs`, `totalWallShiftsDetected`.

`onAppResume(elapsedMs, bootCount)`:
1. Compute `deltaElapsed` since the last resume.
2. **Wall-shift detection**: if
   `|(wallNow - lastResumeEpochMs) - deltaElapsed| > THRESHOLD_MS` (5 min) -> count a shift.
3. **Plausibility gate**: implausible deltas (> 12 h) are rejected.
4. On boot change the delta is zeroed (reboot-aware accumulation).
5. Persist and log the detection.

`ensureFirstRunEpochMs()` seeds `firstRunEpochMs` once, feeding first-run detection.

## Boot reconciliation

`BootRecorder` (`data/repository/.../BootRecorder.kt`) records every boot session in the Room
`meta` table (`boot_count`, `boot_last_recorded_epoch`). `ProtectionRecoveryWorker` calls
`recordBoot()` after each `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`, giving
the commitment engine the cross-reboot context it needs (boot count at creation vs now).

## Relationship to the schedule

The protection **schedule** (`ProtectionSchedule`, `core/model/.../Schedule.kt`) controls the
VPN filter window, not the commitment clock: the commitment always accrues real elapsed time
regardless of schedule. There is no "working-hours allowance" in Phase 1.

## Test coverage

`CommitmentEngineTest` (`data/repository`, **12 tests**): creation, tick accumulation,
canFinish thresholding (dynamic expectation), extend-only growth and caps, finish gating,
state transitions, monotonic-accounting sanity. `TimingAnchorRepository` and `BootRecorder`
have no dedicated unit tests yet (DataStore-backed).