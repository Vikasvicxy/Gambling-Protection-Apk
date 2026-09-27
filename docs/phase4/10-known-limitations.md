# Shield — Known Limitations (Phase 4 §10)

Honest, user-facing and engineering-facing limits. Never claim "fully blocks everything."

## 1. DNS-only VPN (architecture)
- Shield tunnels IPv4 DNS only. All block decisions happen at DNS resolution.
- **Encrypted DNS bypass:** DoH (e.g. Firefox TRR, OS-level DoH in Chrome/Edge/Android), DoT, and QUIC's inline TLS DNS are NOT intercepted — a client that speaks DoH/DoT/QUIC can resolve blocked domains without Shield's filters.
- Recommendation (follow-up): if needed, provide a "block known DoH/DoT endpoints" toggle or deploy a DoH-filtering mode; not in scope for Phase 4.
- **IPv6:** AAAA queries are answered with an empty NOERROR, so clients fall back to IPv4 where Shield is in the path. No IPv6 route is added, because the tun forwards no packets and routing global unicast into it would black-hole IPv6. Residual: a hard-coded IPv6 literal, or AAAA supplied by a DoH resolver, still bypasses (see encrypted DNS above).

## 2. Blocklist scope
- Blocking matches against the canonical blocklist rules only. A domain not in the list is not blocked (allow by default). User may add custom rules.
- IDN/punycode and alternate-TLD variants are only blocked if present in the list (normalization helps but doesn't guarantee coverage).
- Fallback: if no resolver reached (network down), DNS service is unavailable — the tun does not route around the network failure (safe default: protection is enforceable only when the network itsself is reachable).

## 3. VPN state edge cases (mitigations)
- User can stop the VPN service from Settings/another app → protection off (by design; never force a VPN up against user intent). Recovery worker attempts restore on app relaunch/reboot where the system permits.
- Extreme OEM battery-killers can suspend the process (Samsung/Xiaomi/OPPO matrix in `02-oem-matrix.md`). Mitigations: foreground service + user guidance; cannot fight a force-stop.

## 4. Accountability signals
- Health status views are updates over time (heartbeats), not live real-time. "Protected now" means "last known state until next heartbeat", not a live guarantee.
- Pairing/approval flows require backend reachability; offline app shows honest offline states until connectivity returns.

## 5. To-verify (device pending) — do not claim before tested
- Doze wake-path for the recovery worker across all OEMs
- Battery-drain parity with the DNS-only design on representative devices
- Excellent-but-unproven parity between JVM perf and on-device perf (matrix §3)
- Talking to every OEM's notification policy channel

## 6. App risk scan (heuristic, advisory)
- The scan matches installed app labels and package names against a static, offline table of known operators and betting/casino wording. It is a **heuristic, not a classifier**: it has both false negatives and false positives.
- **It takes no automatic action.** Nothing is blocked, uninstalled, or exempted. A wrongly flagged app is a warning a parent can dismiss; an auto-deleted one is not recoverable, and no label heuristic is accurate enough to justify that.
- Matching is whole-word / per-token, so "better", "betray" and "Roubaix" are not matched by "bet" and "rou". A single ambiguous label phrase does not reach the reporting threshold.
- **Coverage is partial.** Android 11+ only reveals packages declared in the manifest `<queries>` element; `QUERY_ALL_PACKAGES` is deliberately not requested. A clean scan means "nothing matched in the N apps Shield could see", never "this phone is clear".
- The operator table will go stale: names get retired, forked and regionalised. This is expected and acceptable, because the scan only advises.
- The scan is fully local. No network, no telemetry, no reputation service - a static table shipped in the APK, so it cannot leak the installed-app list.

## 7. Safe Search
- Shield **offers** a route to each search engine's own SafeSearch endpoint; it does not enforce SafeSearch. DNS-level forcing cannot work under TLS: the client sends the original hostname in SNI and Host, so substituting an address leaves the engine seeing the original query.
- The UI says "offers" rather than "enforces" for this reason. Users should set SafeSearch in the engine's own settings, which is the only place it genuinely filters.

## 8. Not-in-scope (honestly)
- Full-TUN/TCP inspection of non-DNS traffic; device-level MDM-like controls; real-time content filtering of HTTPS payloads; Windows/iOS versions.