# DNS Protection Engine — Phase 1

Module: `protection/dns` — pure Kotlin, host-JVM-testable wire codecs. No Android
dependencies. 33 unit tests across 4 files.

## Responsibilities

Parse raw IP+UDP+DNS traffic read from the tun interface and craft identical-family IP/UDP
responses back into the tun, including:
- IPv4 packet parsing (`IpPacketCodec.parseIpv4`) and crafting with a correct IPv4 header
  checksum (`InternetChecksum`, one's complement).
- IPv6 packet parsing (extension-header walk, capped hops) and crafting with a mandatory UDP
  pseudo-header checksum (`ipv6UdpChecksum`).
- DNS question parsing (`DnsParser`) with compression-pointer expansion and a bounded loop
  guard.
- Synthesized responses: **NXDOMAIN** for blocked DNS names, **REFUSED** for unparseable or
  fail-open cases.

## Files

- `IpPacketCodec.kt` — `parseUdp` (IPv4+IPv6), `craftUdpResponse`, `craftIpv4`, `craftIpv6`;
  MTU enforcement (`mtuLimit`), UDP-only by design (TCP DNS to port 53 is dropped).
- `DnsCodec.kt` — `DnsConstants` (RR types, flags, RCODEs), `DnsQuestion`, `UdpPacket`,
  `DnsParser`, `DnsResponseFactory`, `InternetChecksum`.

## DNS wire details

- Header/ID handling: `headerId`, `questionCount`; the parsed question is echoed verbatim into
  the block response so it is a valid answer for the original query.
- `DnsResponseFactory.blocked(question)` returns RCODE 3 (NXDOMAIN) — the DNS signal a browser
  treats as "domain does not exist", which surfaces as a clean site not found rather than a
  connection stall.
- `DnsResponseFactory.refused()` returns RCODE 5 for anything the tunnel cannot parse — the
  fail-open error path that forces a client to fall back to its own resolution rather than hang.

## Interaction with the tunnel

`ShieldVpnService.handlePacket` (`protection/vpn/.../ShieldVpnService.kt`) routes each DNS
UDP datagram:
1. Parse IP+UDP; drop non-DNS/non-UDP.
2. Parse the DNS question; on parse failure reply REFUSED.
3. Apply the schedule gate; then `blocker.decide(host, scheduleActive)`.
4. BLOCK -> write `DnsResponseFactory.blocked(question)` into the tun (NXDOMAIN), record a
   blocked event.
5. ALLOW -> `forwardQuery` upstream over a protected socket (round-robin resolvers, 4 s
   timeout, 2 KiB reply cap), with fail-open REFUSED on upstream error.

Upstream DNS discovery (`DnsUpstreamProvider`) reads `LinkProperties.dnsServers` of the
validated internet network and must run *outside* the tunnel to avoid self-referencing.

## Known limits, encoded in code

- TCP DNS is dropped (local tun only handles UDP). See module docs.
- User-configurable DNS does not exist in Phase 1 — DNS is hard-tethered to the tun
  (`addDnsServer(TUN_ADDR)`).
- Fail-open is intentional: a stuck upstream or a malformed packet must not brick the user's
  network.

## Test coverage

`DnsParserTest` (11), `DnsResponseFactoryTest` (8), `IpPacketCodecTest` (9),
`InternetChecksumTest` (5): compression pointers, truncated names, header layout, NXDOMAIN
echo, REFUSED, IPv4 checksum RFC vectors, MTU/family validation, IPv4+IPv6 round-trips, and
the long-context (client query) integration path exercised from
`data/blocklist`'s `SeedBlocklistIntegrationTest`.