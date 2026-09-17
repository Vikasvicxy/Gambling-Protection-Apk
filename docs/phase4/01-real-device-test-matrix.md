# Shield — Real-Device Test Matrix (Phase 4, §2–§7)

**Device required for every row below.** None connected as of this writing. Run these in order on the first physical Android device attached.

## Setup (once)
1. Attach device, enable USB debugging. Verify: `C:\Android\Sdk\platform-tools\adb.exe devices -l` shows the device.
2. Build + install the signed release candidate (not debug) when available:
   `.\gradlew.bat :app:assembleRelease` then `adb install -r app/build/outputs/apk/release/app-release-unsigned.apk` (or the signed APK).
   Alternative during development: `.\gradlew.bat :app:assembleDebug` + `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Confirm permission grants: VPN service, POST_NOTIFICATIONS, no background restriction.
4. Record: device model, Android version, OEM UI/build, Play Services version, network (Wi-Fi and mobile), app version.

## §2 — VPN / DNS end-to-end blocking
| ID | Test | Pass criteria | Result |
|---|---|---|---|
| V1 | Start VPN, open a blocked sample site in Chrome | Site does not resolve/connect; blocker notification shows a DNS-blocked log line | ☐ |
| V2 | Start VPN, visit a known-good site (e.g. example.org) | Resolves and loads normally | ☐ |
| V3 | Repeated rapid starts/stops 10× | Stable connect/disconnect; no crash/ANR; notifications consistent | ☐ |
| V4 | Toggle VPN while a download is in progress | No crash; flow pauses/resumes reasonably | ☐ |
| V5 | Rotate device while VPN active (portrait/landscape) | No state corruption; VPN stays up | ☐ |
| V6 | Send app + sensitive app to background, then resume | VPN remains active; app process not killed | ☐ |
| V7 | Terminate app from Recents, then relaunch | Recovery worker or re-connect restores VPN; status correct | ☐ |
| V8 | Change DNS resolver mid-session (Wi-Fi→LTE, LTE→Wi-Fi) | No resolution gap beyond a few seconds; `DnsUpstreamProvider` invalidation effective | ☐ |
| V9 | Network absent (airplane mode), VPN on | Graceful; no crash; clears when connectivity returns | ☐ |
| V10 | Blocked domain in a private-browsing window | Still blocked (VPN-level, not browser-level) | ☐ |

## §3 — Performance (on-device)
| ID | Test | Data to record |
|---|---|---|
| P1 | Time from "Start protection" tap to VPN ready | seconds |
| P2 | 60× load of a heavy site vs VPN off (p50/p95) | ms / page |
| P3 | Throughput up/down with VPN on vs off (speedtest, IPv4) | Mbps; note VPN covers DNS only |
| P4 | CPU% with VPN idle 5 min, app foreground + background | % |
| P5 | Memory (`adb shell dumpsys meminfo <pkg>`) idle | RSS |
| P6 | 1000-blocklist integration: millis per query growth with rule count | ms |
| P7 | Thermal: 15 min YouTube, VPN on | °C trend / throttling |
| P8 | Reboot with VPN enabled: auto-start reaches protected state | seconds, correctness |

Compare against `docs/PERFORMANCE.md` JVM numbers (lookup 45–234 ns, trie build 152 ms / 200k rules). Device results are expected to be slower but same order of magnitude; record raw numbers, never fake.

## §4 — Battery / background / lifecycle
| ID | Test | Pass criteria |
|---|---|---|
| B1 | Battery-saver on while VPN active | Protection keeps working or degrades gracefully with user-visible notice; no crash |
| B2 | Doze (screen off 30 min) | VPN stays up in RAM; on wake, protection still enforced |
| B3 | Force-stop app | Stop works; on relaunch protection restored by worker/user action |
| B4 | Device reboot → protection auto-starts (or recovery path engages) | Consistent with `BootReceiver` design |
| B5 | Battery-drain rate, VPN idle 2 h (%/h) | Record; no abnormal drain vs baseline |
| B6 | Low-storage edge (150 MB free) | No crash; update-check fails gracefully |

## §5 — Notifications / health continuity
| ID | Test | Pass criteria |
|---|---|---|
| N1 | VPN-connected notification present, low importance | Visible, not annoying category |
| N2 | App-health degraded state (revoke one permission behind settings) | Health check reports degraded status, detail shows exactly which permission |
| N3 | Notification service: local test event fired from test hook | Notification appears with correct title/body/channel |

## §6 — Accountability (no backend credential needed for UI smoke)
| ID | Test | Pass criteria |
|---|---|---|
| A1 | Create pairing invite → appears in invite list (or clear "offline" state without backend) | Honest error state; no crash |
| A2 | After backend deployed (§12 pack): register device → heartbeat → invitation accept → dashboard shows protected device | End-to-end happy path |
| A3 | Revoke/replace pairing | Old link severed; new link works |
| A4 | Wrong-role attempt (protected device tries to act as partner) | Denied |

## §7 — Crash / ANR / UI smoke
| ID | Test | Pass criteria |
|---|---|---|
| C1 | Guided walk-through of every screen in `14-crash-anr-validation.md` | No crash/ANR in `logcat`; respond to "Do you want to close?" (main-thread stall) |
| C2 | `adb logcat *:E` after each of V1–V9, P1–P8, B1–B6 | Only expected errors (none fatal) |
| C3 | Android vitals smoke: launch time on device | Record cold-launch ms |
| C4 | TalkBack on main screens (screen reader) | All interactive nodes labeled; toggle actions announced |
| C5 | Font scale 1.3 / smallest width 320dp | Layouts scroll instead of clipping |