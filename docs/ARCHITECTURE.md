# Shield architecture notes

Working notes for the protection engine, written for whoever changes it next. This
covers the parts where the obvious implementation is wrong in a way that is not
visible from the code alone.

---

## The VPN engine, and why it currently routes exactly one address

Shield establishes a `VpnService` and routes a **single /32 address**, `198.18.0.1`,
which is the DNS resolver the tunnel answers for. Everything else on the device is
untouched by the tunnel.

That is deliberate, and it is the reason the more ambitious items below are switched
off rather than shipped.

### `VpnService.Builder` cannot select a port

The obvious way to enforce "no HTTPS except to allowed sites" is to route only TCP
port 443 into userspace and forward everything else to the real network. **The
platform does not expose that.** On API 37 `VpnService.Builder` offers only:

- `addRoute(IpPrefix)`
- `addRoute(String address, int prefixLength)`
- `excludeRoute(IpPrefix)`

There is no port selector. `addRoute` takes an address prefix and nothing else.

So "intercept only port 443" means **route all traffic and forward all of it**,
which for a VPN means implementing a full TCP/IP stack in userspace: TCP for IPv4 and
IPv6, UDP, and ICMP, plus the routing, MTU and fragmentation handling that goes with
them. Android gives a packet-capture interface, not a forwarding interface.

### What happens if you enable it without a forwarding stack

If Shield adds `0.0.0.0/0` and routes packets to itself without forwarding them, no
traffic leaves the device. Not degraded traffic — none. The phone appears to have
full signal and cannot load anything. The user has to know to turn the VPN off.

This is why `VpnConfig.ALL_ROUTES` exists as a constant but is not used, why
`sniInterceptionEnabled` defaults to `false`, and why `SniInterceptionRouteGuardTest`
asserts that the active route list is exactly one /32. That test is a tripwire: if
someone wires the route up without the forwarding stack, it fails.

### Current state of the SNI work

The pieces below the routing layer are built, tested and inert:

| Component | Module | State |
| --- | --- | --- |
| `TcpPacketCodec` — IPv4/IPv6 parse, mirrored RST synthesis | `:protection:dns` | Built, tested |
| `TlsSniParser` — ClientHello SNI extraction | `:protection:vpn` | Built, tested |
| `TlsSniInterceptor` — flow tracking, bounded reassembly, dispatch | `:protection:vpn` | Built, tested, **not routed** |
| TCP/IP forwarding stack | — | **Does not exist** |

The interceptor is wired into `ShieldVpnService`'s packet path and gated on the
setting, so the moment a forwarding stack exists the inspection path is reachable
without further plumbing. Until then it never sees a packet, because the tunnel only
carries DNS.

### QUIC

Chrome sends HTTPS over QUIC (UDP/443) by default. TCP-level SNI inspection does not
see it, so an HTTPS-only allowlist can be bypassed by leaving QUIC enabled.
`QuicFilter` exists to force the TCP fallback for blocked hosts. A forwarding stack
that does not handle UDP leaves QUIC uninspected.

---

## The uninstall guard

The guard is an `AccessibilityService`. It watches for a window change to a system
app-details or uninstall screen and raises a `TYPE_ACCESSIBILITY_OVERLAY` PIN
challenge.

Constraints that shape it:

- **No `SYSTEM_ALERT_WINDOW`.** An accessibility service can add overlay windows
  without it, which keeps the permission surface smaller than an Activity-based
  overlay would need.
- **The overlay window must be focusable.** With `FLAG_NOT_FOCUSABLE` the window
  cannot take input focus, so the PIN `EditText` never raises the keyboard and the
  prompt is visible but unanswerable.
- **It can never block an uninstall outright.** Shield does not own the system window.
  The prompt stands between the user and the tap; it is not a lock.
- **It is always dismissible.** A guardian feature that can strand someone on their
  own phone is worse than no guardian feature. A user who knows the PIN can disable
  the guard from inside the app.

### Detection matches on activity name, not package

`UninstallGuardDetector` matches the *activity class name*, not the package, because
OEM package names vary far more than the class that shows app details.

The trade-off, stated plainly: a third-party app with `Uninstall` or `AppDetail` in
its activity name can trip the guard. That is accepted, because the alternative — a
package allowlist — guarantees that vendors we have not enumerated get no protection
at all, and vendor updates are the case this guard exists to cover.
`UninstallGuardVendorCoverageTest` pins the known vendors so a refactor that narrows
matching fails loudly and names what it dropped.

### When the service is switched off

The platform gives **no callback** when an accessibility service is disabled: the
service is unbound and nothing distinguishes that from a process kill. The guard can
therefore go quiet while the settings screen still shows it as enabled.

`UninstallGuardFallbackReceiver` checks on `MY_PACKAGE_REPLACED` and boot — the
points after which it commonly happens — and posts a notice pointing at the system
Accessibility settings. It does not poll, and it does not nag: if the user turned the
guard off deliberately, that is their decision.

---

## Testing conventions

- **Robolectric needs an explicit `@Config(sdk = ...)`** in Android modules. The
  project compiles against SDK 37, which Robolectric refuses to emulate on Java 17,
  and `android.jar` does not expose `java.lang.management`, so anything from that
  package must be reached reflectively in a unit test.
- **`testDebugUnitTest` does not run the JVM modules.** `:core:accountability`,
  `:core:admin`, `:core:model`, `:core:release`, `:protection:dns`,
  `:protection:domain-engine` and `:tools:blocklist` need their plain `test` task.
  A green `testDebugUnitTest` is not a green build.
- **Stress tests use fixed iteration counts, not durations.** A duration-based test
  silently passes more often the slower the build agent is.