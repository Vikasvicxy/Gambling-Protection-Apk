# VPN Protection Path — Phase 1

Module: `protection/vpn` — the local **DNS-only** `VpnService`. 8 unit tests
(`VpnStateStoreTest`).

## Design contract

A **local DNS-only VPN**: it neither terminates HTTPS nor tunnels user traffic. Device DNS is
pulled into the tun; blocked names get a synthesized NXDOMAIN; allowed names are forwarded
fail-open to the carrier resolver (see `ShieldVpnService` module docs). DNS upstream discovery
runs outside the tunnel to avoid self-referencing.

## Components

- `VpnConfig.kt` — tun address `10.147.2.1`, prefix 32, MTU 1400, DNS port 53.
- `ShieldVpnService.kt` — the `@AndroidEntryPoint VpnService`; foreground service
  (`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`), `START_STICKY`.
- `VpnStateStore.kt` — process-wide `MutableStateFlow<VpnRuntimeState>`:
  `markConnecting` -> `markConnected` -> `markStopped(reason?, userStopped?)`; query counters
  (`recordQuery(allowed)`, `resetCounters`). `VpnFailure` enum: AUTH_NOT_GRANTED,
  ESTABLISH_FAILED, STARTUP_ERROR, REVOKED, IN_SCHEDULE_PAUSE.
- `DnsUpstreamProvider.kt` — auto-discovery of carrier resolvers from the validated internet
  network's `LinkProperties.dnsServers` (round-robin, refreshed ~30 s).
- `BlockEventRecorder.kt` — fire-and-forget recorder interface (implemented by
  `BlockEventRepository`; the tun thread never blocks on Room).

## Lifecycle

1. `ProtectionEnforcer.enforceNow()` (`data/repository`) calls `ShieldVpnService.launch(context)`
   — the UI path is gated on `ProtectionMode.SELF_PROTECTION`.
2. `establish()` builds the `VpnService.Builder`: session "Shield - DNS protection", address
   `10.147.2.1/32`, MTU 1400, `addDnsServer(TUN_ADDR)`. Failure -> `markStopped(ESTABLISH_FAILED)`
   + `stopSelf()`.
3. `runTunLoop()` runs on a dedicated `shield-tun` thread using `Os.poll` (non-blocking-fd
   workaround on some builds); a single malformed packet never kills the loop.
4. `launchScheduleMonitor()` polls the protection schedule every ~15 s; when the window ends,
   `markStopped(IN_SCHEDULE_PAUSE)` and stop.
5. `onRevoke()` records `REVOKED`.

## Per-packet path (`handlePacket`)

1. `parseUdp`; drop anything that isn't UDP DNS to port 53.
2. Parse the DNS question; unparseable -> write REFUSED.
3. Schedule gate; then `blocker.decide(host, scheduleActive)`.
4. **BLOCK**: `stateStore.recordQuery(false)` + `recorder.recordBlocked(...)` + write
   `DnsResponseFactory.blocked(question)` (NXDOMAIN) straight into the tun.
5. **ALLOW**: `forwardQuery` — round-robin upstream over a `protect()`-ed socket, 4 s timeout,
   2 KiB max reply, `Semaphore(32)` concurrency cap; on upstream failure write fail-open
   REFUSED.

`writeResponse()` crafts the IP+UDP packet via `IpPacketCodec.craftUdpResponse` and writes it
synchronously to the tun fd.

## State and UI

`VpnStateStore` feeds the dashboard and `HealthEngine.checkVpn/checkDns` (running,
paused-by-schedule = healthy, revoked = critical, not-running = degraded).

## Coverage and gaps

- `VpnStateStoreTest` (8): state transitions, failure/revoke/schedule-pause semantics,
  counter accumulation, reset-preserves-runtime-fields.
- Not unit-tested: `ShieldVpnService` packet routing, `DnsUpstreamProvider`, and the
  forward/block socket path — these are exercised indirectly through the `protection:dns`
  codec tests and remain an instrumentation/on-device gap (see the limitations doc).