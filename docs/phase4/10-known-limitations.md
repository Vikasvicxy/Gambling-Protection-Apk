# Shield — Known Limitations (Phase 4 §10)

Honest, user-facing and engineering-facing limits. Never claim "fully blocks everything."

## 1. DNS-only VPN (architecture)
- Shield tunnels IPv4 DNS only. All block decisions happen at DNS resolution.
- **Encrypted DNS bypass:** DoH (e.g. Firefox TRR, OS-level DoH in Chrome/Edge/Android), DoT, and QUIC's inline TLS DNS are NOT intercepted — a client that speaks DoH/DoT/QUIC can resolve blocked domains without Shield's filters.
- Recommendation (follow-up): if needed, provide a "block known DoH/DoT endpoints" toggle or deploy a DoH-filtering mode; not in scope for Phase 4.
- **IPv6:** RDNSS/DHCPv6 resolvers are not filtered (IPv4 tun only). A device that prefers IPv6 DNS could bypass. Document in store copy; consider IPv6 tun as a future hardening.

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

## 6. Not-in-scope (honestly)
- Full-TUN/TCP inspection of non-DNS traffic; device-level MDM-like controls; real-time content filtering of HTTPS payloads; Windows/iOS versions.