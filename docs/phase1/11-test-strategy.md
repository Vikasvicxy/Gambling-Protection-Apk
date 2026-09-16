# Test Strategy & Quality Gates — Phase 1

## Inventory (verified, all green)

**120 tests across 13 files**, all passing under JDK 17. Command:
`.\gradlew.bat test testDebugUnitTest` (`test` for pure-JVM host modules, `testDebugUnitTest`
for Android modules; root config supplies Robolectric).

| Module | Tests | Files |
|---|---|---|
| `core/model` | 8 | `HealthReportTest` |
| `data/blocklist` | 12 | `BlocklistRepositoryTest` (5), `SeedBlocklistIntegrationTest` (7) |
| `data/repository` | 12 | `CommitmentEngineTest` |
| `protection/dns` | 33 | `DnsParserTest` (11), `DnsResponseFactoryTest` (8), `IpPacketCodecTest` (9), `InternetChecksumTest` (5) |
| `protection/domain-engine` | 47 | `DecisionEngineTest` (11), `DomainIndexCompilerTest` (5), `DomainIndexTest` (13), `DomainNormalizerTest` (18) |
| `protection/vpn` | 8 | `VpnStateStoreTest` |

Modules with no unit tests in Phase 1 (by design or gap): `core:common`, `core:database`,
`core:design-system`, `core:security`, `core:testing`, `data:preferences`, `app`,
`feature:*`, `protection:health`, `protection:boot`, `protection:oem`.

## Coverage highlights

- **Wire-level DNS**: IPv4/IPv6 packet parse/craft, header checksums (RFC vectors), DNS
  compression-pointer edge cases, NXDOMAIN/REFUSED responses.
- **Domain matching**: IDN/punycode, wildcards, exact vs suffix, most-specific wins, and the
  allowlist-beats-blocklist tie break — pinned by tests, not prose.
- **Seed integrity**: `SeedBlocklistIntegrationTest` loads the real bundled seed, asserts
  exactly 12 ACTIVE + 4 ALLOWLISTED, then runs `load -> compile -> decide` end-to-end.
- **Commitment anti-tamper**: monotonic accumulation, extend-only caps, finish gating
  (`CommitmentEngineTest`).
- **VPN state**: transitions, revocation, schedule pause, counters (`VpnStateStoreTest`).
- **Health aggregation**: worst-of ordering and per-component lookup (`HealthReportTest`).

## Robolectric setup (Android modules)

- Space-free local Maven repo: `maven.repo.local=C:\grhome-m2` (root `build.gradle.kts`
  applies it to every `Test` task alongside `--enable-native-access=ALL-UNNAMED`);
  `GRADLE_USER_HOME=C:\grhome`.
- **JDK 17 is mandatory** for Gradle; newer JDKs break Robolectric's native SQLite. Set
  `JAVA_HOME=C:\Program Files\Java\jdk-17` before every build/test run.
- When production code changes, the APK must be reinstalled on the emulator
  (`{package}.debug` = `dev.gamblock.shield.debug`) and the app relaunched before verifying
  on device.

## Quality gates (Phase 1 definition of done)

1. `.\gradlew.bat test testDebugUnitTest` green under JDK 17 — **must pass on every PR**.
2. `:app:assembleDebug` builds without warnings-as-errors failures.
3. On-device smoke: clean install -> `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` broadcast ->
   `ProtectionRecoveryWorker` runs and reports `Worker result SUCCESS` (verified:
   WorkManager DB state = SUCCEEDED; no FATAL/ANR; on-device `shield initialized` log).
4. No assertion ever weakened or deleted to make a test pass; a failing test implies a bug
   to fix, not a test to remove.

## Known gaps

- No instrumentation/UI tests (`androidTest`) in Phase 1 — the VPN tun loop, upstream
  discovery, and full `boot -> protect -> decide -> forward` chain are on-device verified,
  not CI-verified.
- No end-to-end real-device DNS validation yet (emulator limitation — see limitations doc).
- `HealthEngine`, `TimingAnchorRepository`, `BootRecorder`, `DnsUpstreamProvider` lack
  dedicated unit tests.
- Coverage is unit-completeness rather than CD (branch/line) targets; no JaCoCo gate in
  Phase 1.