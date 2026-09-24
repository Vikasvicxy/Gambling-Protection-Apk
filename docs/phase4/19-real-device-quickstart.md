# Shield — Real-Device Quick Start (Phase 4)

Once a physical Android device is connected, this is the shortest path from "phone in
hand" to a recorded Phase 4 device run. Full detail lives in `01-real-device-test-matrix.md`;
the human-gate list lives in `REQUIRES-VIKAS.md`.

## First command when the phone arrives
```powershell
C:\Android\Sdk\platform-tools\adb.exe devices -l
```
Expected: one connected row ending in `device` (not `unauthorized`, not `offline`).
If it says `unauthorized` or nothing, re-check USB debugging and the on-device prompt.

## 1. Build + install (pick one)
Release candidate (R8, unsigned — fine for on-device functional testing):
```powershell
.\gradlew.bat :app:assembleRelease
C:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\release\app-release-unsigned.apk
```
Or debug (larger, includes debug tooling):
```powershell
.\gradlew.bat :app:assembleDebug
C:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## 2. Device-side consent (required for VPN tests)
- Open Shield → Start protection → accept the system **VPN connection request**.
- Allow **notifications** (`POST_NOTIFICATIONS`).
- Optional for battery rows: disable battery optimization for Shield.

## 3. Run the guided matrix
```powershell
.\scripts\phase4-real-device-test.ps1 -Install -Apk app\build\outputs\apk\release\app-release-unsigned.apk
```
The script: verifies adb + device, records device facts in `out/device-test/session-*.json`,
installs the APK (`-Install`), then walks every row of `01-real-device-test-matrix.md`
answering P (pass) / S (skip) / F (fail + evidence) / Q (save-quit). Results →
`out/device-test/results-*.csv` and `results-*.json`.
Without a device it prints **WAITING FOR PHYSICAL DEVICE** and exits 0.

## 4. After the walk-through
- Run the logcat checks from `14-crash-anr-validation.md` (no crash/ANR lines).
- Record OEM/battery observations per `02-oem-matrix.md`.
- Populate the bypass rows in `03-bypass-matrix.md` honestly (DEFAULT rows still need a device outcome).
- Copy the results files + device model/version into the Phase 4 evidence folder and feed the
  numbers into `09-performance-battery-report.md` / `00-phase4-report.md` §5 module.

## Honest rules
- Do not mark any row **TESTED** without an actual device outcome.
- Do not substitute the emulator for the physical device.
- Do not claim FCM push or production backend deployment verified — those are separate
  human-gated items in `REQUIRES-VIKAS.md`.