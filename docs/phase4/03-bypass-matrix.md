# Shield — Android Bypass Test Matrix (Phase 4 §8)

**Honest classification:** DEFAULT = depends on device (VPN-level control); TESTED = verified by JVM/integration tests or static review here; NOT TESTED = requires device (none connected).

| # | Bypass attempt | Expectation | Class | Device result (record) |
|---|---|---|---|---|
| 8.1 | Change DNS to Google (8.8.8.8) / Cloudflare manually | VPN intercepts all DNS before the network stack → still blocked | DEFAULT — VPN-level DNS; TESTED by design (tun routing) | ☐ |
| 8.2 | Private browsing / incognito | System DNS unchanged → blocked | TESTED by design (VPN-level) | ☐ |
| 8.3 | DoH (firefox.network.trr)/DoT/QUIC | Bypasses DNS-only VPN: encrypted + inline QUIC resolves without intercept | **HONEST LIMIT** — see `10-known-limitations.md`; mitigation = block via DNS-level redirection if upstream blocked domains are also DoH hosts, else out of scope | ☐ |
| 8.4 | IPv6 DNS (AAAA / RDNSS) | Not filtered (IPv4-only tun); devices with IPv6-enabled resolvers can bypass | **HONEST LIMIT** } | ☐ |
| 8.5 | System-level Hosts file | Ignored under VPN (VpnService wins) | TESTED by design | ☐ |
| 8.6 | Proxy / SOCKS app (e.g. Psiphon) | Proxy still needs DNS resolution → blocked unless proxy resolves via bypass channel | DEFAULT | ☐ |
| 8.7 | DNS-client in a separate app/process using raw UDP to a public resolver IP | IP packet to the resolver is still routed through tun → dropped for blocked domains; allowed domains forwarded | TESTED by design | ☐ |
| 8.8 | Stop VPN service from user Settings | Device is disconnected network-wise; protection off; user action required to re-enable | TESTED — recording `VPN_DISCONNECT` in the HealthEngine | ☐ |
| 8.9 | Force-stop app | System kills VPN; `ShieldVpnService` + recovery cycle attempts restore; remains a known gap (system may keep it down) | **PARTIALLY TESTED** — recovery worker present; needs device run | ☐ |
| 8.10 | DoS the VPN with malformed IP frames | tun loop isolates per-packet exceptions; no crash; invalid frames dropped | TESTED by JVM fuzz tests | ☐ |
| 8.11 | Exfiltrate via `file://` / local resources | Not sent over network; no server-side upload of content | TESTED by design | ☐ |
| 8.12 | Exfiltrate blocked-site DNS queries via a second UDP socket to a nonstandard port | Only 10.147.2.1:53 is the block resolver; arbitrary UDP to external IP is not proxied to a block answer — but VPN DNS is the *only* resolver path for permitted flows; queries to an arbitrary hard-coded IP resolve via... | NOT TESTED on device; requires device run | ☐ |
| 8.13 | Alternate TLD / IDN trick (admarketing.xyz vs similar) | Blocklist matches by exact rule set; IDN/punycode variants in blocklist are applied by normalization; weak-by-design if blocklist lacks the variant | PARTIALLY TESTED | ☐ |

## Summary
- Enforcement core (DNS-level, tun-based) resisted bypasses 8.1–8.2, 8.5–8.8, 8.10–8.11 by architecture and unit tests.
- Honest limitations 8.3 (DoH/DoT/QUIC) and 8.4 (IPv6): the release notes + known-limitations document must disclose that encrypted/alternate-protocol DNS can bypass a DNS-only VPN. No false "100% protection" claim.