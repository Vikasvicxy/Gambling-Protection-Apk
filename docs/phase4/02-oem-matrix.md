# Shield — OEM Setup & Battery Guidance (Phase 4 §9)

`protection:oem` detects OEM brand + emits guidance. This matrix is authored from the framework review. All rows are **NOT TESTED** on hardware unless a row is marked TESTED — do not grade untested rows.

## Bypass/enforcement sensitivity by OEM
| OEM | Known behavior | Per-oem guidance (in-app by brand) | Tested |
|---|---|---|---|
| Samsung (One UI) | Aggressive app-sleep in "Adaptive battery"/Deep sleeping apps; VPN rarely killed but notifications may be delayed; battery "Optimize battery usage" off-by-default for new apps is unusual | Guide: disable "Deep sleeping apps" + "Put unused apps to sleep"; VpnService immune to most kill paths | NOT TESTED |
| Xiaomi / Redmi (HyperOS/MIUI) | Auto-start control + battery saver can kill services/notifications | Guide: enable autostart, pin in Recents, no MiUI battery saver for Shield | NOT TESTED |
| OPPO / OnePlus / Realme (ColorOS) | Auto-launch + background-freeze; notification channel visibility | Guide: enable autostart/background activity, allow notification launcher | NOT TESTED |
| Huawei (EMUI/HarmonyOS) | Aggressive process management + AppGallery lifecycle special cases | Guide: disable battery optimization, allow background activity | NOT TESTED |
| OnePlus (OxygenOS) | "Optimized battery" can restrict; startup manager | Guide: allow auto-launch | NOT TESTED |
| Vivo (OriginOS, Funtouch) | Auto-start restrictions + notification grouping | Guide: enable background/autostart | NOT TESTED |
| Google Pixel | Most stock; DERM scanner may offer "request ignore battery optimizations" | Guide: brief; no action expected | PARTIALLY TESTED (design intent) |
| Sony / Motorola / Nokia / Asus | Near-stock; minimal special-casing | Guide: none or minimal | NOT TESTED |

## Device coverage goal for closed testing
Author the closed-test matrix with a representative spread: at least 1 × Samsung One UI, 1 × Pixel/stock, 1 × Xiaomi or OPPO-adjacent, 1 × Android 13+, 1 × foldable if available. Record: model, Android version, OEM build, Play Services version, VPN-active duration before any kill, battery-saver behavior.