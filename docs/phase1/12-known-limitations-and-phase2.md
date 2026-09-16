# Known Limitations & Phase 2 Roadmap — Phase 1

## Known limitations

### Blocking scope

- **DNS-only, fail-open.** The app blocks at the DNS layer. Apps that bypass system DNS
  (embedded resolvers, DoH/DoT, hardcoded IPs) are not caught. Unparseable queries, upstream
  timeouts, and out-of-schedule time ALLOW (`DecisionEngine`). This is documented in the
  support screen.
- **TCP DNS is dropped.** The tun only processes UDP datagrams to port 53; DNS-over-TCP is
  not intercepted.

### Device/network coverage

- **No Android-16-emulator user-DNS/tun end-to-end validation in Phase 1.** On the Phase 1
  emulator (API 36) the emulated network does not expose a user-visible DNS setting the app
  can validate against, and the tun path is not fully exercised end-to-end. Real-device VPN +
  DNS end-to-end is therefore **NOT TESTED** in the Phase 1 report (see `docs`/report). This is
  a documented validation gap, not a code defect.
- **Upstream discovery uncertainty.** `DnsUpstreamProvider` reads the validated network's
  dnsServers; if none are reported, DNS upstream resolution may be unavailable to the tun.
- **No user-configurable DNS server** — DNS is hard-tethered to the tun address; power users
  can't choose a resolver.

### Protection model

- **Fail-open philosophy.** E.g. clock-tamper detection counts deviations but a corrupted
  wall-clock display field does not today fail protect.
- **Single schedule, no recurring sub-schedules.** Only one active window
  (00:00–23:59 default). No per-day exceptions or allow/override windows.
- **App-level tamper** (root, Xposed, modified APK) is assumed absent; the app does no
  attestation or signature pinning.

### Data & updates

- **No server.** Remote blocklist updates are `NOT_CONFIGURED` (`UpdateRepository`); the only
  blocklist source is the bundled seed.
- **No sync of stats/false-positive reports** off-device; all telemetry is on-device.
- **Room schema v1, no migrations** — schema bumps require clean installs or migration work
  in Phase 2.
- **No backup/restore** of settings or commitments (`allowBackup=false`); a fresh install
  starts over.
- **Release signing** is configured but the keystore lives outside VCS; no automated release
  distribution/rollout exists.

### Quality/test gaps (mirrors the test-strategy doc)

- No instrumentation/UI/E2E suite; `HealthEngine`, `DnsUpstreamProvider`,
  `TimingAnchorRepository`, `BootRecorder` have no dedicated unit tests.
- Stateless `feature:` modules and the VPN service runtime are verified on-device only.

## Phase 2 roadmap (RFC — to be triaged)

1. **Blocklist updates**: define `DomainBlocklistVersionedUpdate` network flow; verify update
   authenticity (signed bundles) before Room ingest; expose `UpdateState`.
2. **Failure taxonomy for blocked-DNS UX**: DNS interception ("blocked by Shield") landing
   page, false-positive flow over the wire, unified blocked-diagnostics in `feature:reports`.
3. **Instrumentation/E2E**: fake `VpnService` harness + `androidTest` suite for
   tun-to-decide-to-write; CI job (GitHub Actions) running `test testDebugUnitTest` under
   JDK 17.
4. **Real-device validation**: add a device farm smoke (BOOT recovery + behind-VPN DNS E2E)
   to close the limitation above.
5. **Encrypted/HTTPS mitigation**: HTTPSNI-based blocking or protocol-adaptive tunneling
   (out of current scope; design first).
6. **User-experience follow-ups**: per-day schedules, manual overrides, DoH guidance UX,
   GPU-visible confetti/stats, accessibility pass.
7. **Privacy telemetry**: optional opt-in, rate-limited, K-anonymous reporting pipeline with a
   **review hook by user** before any Phase 2 analytics ship.