# Shield — Crash / ANR Validation Prep (Phase 4 §22)

## On-device crash/ANR walk-through (requires a device — add to the device-run checklist)
1. `adb logcat -c` before each walk-though step.
2. After every device matrix row in `01-real-device-test-matrix.md`, run:
   `adb logcat *:E ShShield:V` and `adb logcat -d -b crash`.
3. While driving each screen in turn (Settings → Health → blocklist list → custom rules → update check → pairing → guardian view):
   - Watch for "Activity ... ANR in ..." lines; if found, `adb shell am force-stop <pkg>` and file evidence.
   - Record any `android.os` exceptions/`OutOfMemoryError`/`TransactionTooLargeException`.

## Existing static protections (reviewed, TESTED by design)
- VPN tun loop isolates malformed-packet exceptions per packet (no full-process crash from hostile IP/UDP input) — covered by fuzz/integration tests.
- No uncaught network exceptions in the blocking path (all OkHttp/HttpURLConnection calls guarded by `use{}` + exception catches → `BackendResult.NetworkError`).
- WorkManager workers catch and reschedule; health engine handles permission revocation gracefully.
- Compose previews not shipped; no `Crashlytics` SDK → crash reporting is OS-level.

## Guardrails before public release (DEFERRED, honest)
- Add a `Thread.setDefaultUncaughtExceptionHandler` wrapper or enable the OS-level crash upload, and wire a debug-build-only crash button — **not implemented in Phase 4** (out of scope; note as follow-up).
- The current unsigned APK cannot be safety-net-validated for crashes at scale without Play or Crashlytics — record via Play Vitals in closed testing instead.

## No device → rows are NOT TESTED
Until a device is attached, the §01 matrix rows C1–C3 remain NOT TESTED and cannot be claimed.