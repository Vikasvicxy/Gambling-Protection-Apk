# Architecture Overview — Phase 1

## Purpose

Shield (package `dev.gamblock.shield`) is a local, on-device gambling website **DNS-blocking**
Android app. Phase 1 ships a **DNS-only VPN filter**: device DNS is routed through a local
`VpnService` tunnel, matching domains receive a synthesized NXDOMAIN response, and all other
traffic is forwarded fail-open to the carrier resolver. No traffic other than DNS is
inspected, and nothing leaves the device.

## Module layout

Declared in `settings.gradle.kts`. All Android modules share `compileSdk = 37`,
`minSdk = 26`, `targetSdk = 36` (app), and JVM target 17.

| Module | Responsibility |
|---|---|
| `app` | Application shell: Hilt bootstrap, WorkManager/HiltWorkerFactory wiring, single-Activity Compose nav graph. |
| `core:common` | Logging seams (`ShieldLogger`, `AndroidLogger`, `Logs`), clocks (`WallClock`, `MonotonicClock`), `DispatchersProvider` (dedicated DNS-upstream pool), global Hilt bindings (`CommonModule`). |
| `core:model` | Pure-JVM domain models, enums, DTOs (`Blocklist.kt`, `Commitment.kt`, `Health.kt`, `Events.kt`, `Schedule.kt`, `Oem.kt`, `Diagnostics.kt`, `Update.kt`, `Misc.kt`) + `util/Hashing.kt`. |
| `core:database` | Room database `shield.db` (schema version 1, schema exported): `domain_rules`, `commitments`, `block_attempt_groups`, `activity_events`, `false_positive_reports`, `meta`. Six DAOs. |
| `core:design-system` | Material 3 theme (`ShieldTheme`) and reusable Compose components. |
| `core:security` | `KeystoreInstallId` — Android KeyStore (AES-256-GCM) anonymous installation identity. |
| `core:testing` | Shared unit-test fakes (`FakeWallClock`, `FakeMonotonicClock`, `TestDispatchersProvider`, `NoOpLogger`). |
| `data:blocklist` | Canonical blocklist owner: seeds Room from the bundled asset, compiles the in-memory trie, serves block decisions (`BlocklistRepository` implements `DomainBlocker`). |
| `data:preferences` | DataStore-backed user settings, onboarding state, and the persistent timing anchor. |
| `data:repository` | Use-case layer: `ProtectionEnforcer`, `CommitmentEngine`, `BootRecorder`, `BlockEventRepository`, `ActivityEventRepository`, `FalsePositiveReportRepository`, `UpdateRepository`/`UpdateCheckWorker`. |
| `protection:dns` | Pure-JVM DNS/IP wire codec (`DnsCodec.kt`, `IpPacketCodec.kt`) + `InternetChecksum`. |
| `protection:domain-engine` | Pure-JVM domain normalization, trie index compilation and decision making (`DomainNormalizer`, `DomainTrieIndex`, `DomainIndexCompiler`, `DecisionEngine`, `DomainBlocker`). |
| `protection:vpn` | The local DNS-only `VpnService` (`ShieldVpnService`), `VpnStateStore`, `VpnConfig`, `DnsUpstreamProvider`, `BlockEventRecorder`. |
| `protection:boot` | Boot/update recovery: `BootReceiver`, `ProtectionRecoveryReceiver`, `ProtectionRecoveryWorker` (`@HiltWorker`). |
| `protection:health` | `HealthEngine` — aggregates a 9-component health report. |
| `protection:oem` | Device/OEM facts + battery/power guidance + VPN-conflict detection via `NetworkCapabilities`. |
| `feature:onboarding`, `feature:setup`, `feature:dashboard`, `feature:reports`, `feature:diagnostics`, `feature:support`, `feature:settings` | Compose feature screens + ViewModels. |

## Dependency Injection

- Hilt 2.60.1 with KSP2 (`ksp.useKSP2=true`). All components are `@InstallIn(SingletonComponent)`.
- `ShieldApplication` (`app/src/main/kotlin/dev/gamblock/shield/ShieldApplication.kt:22`) is
  `@HiltAndroidApp` and implements `androidx.work.Configuration.Provider`, returning a
  `Configuration.Builder().setWorkerFactory(workerFactory)` where `workerFactory` is the
  injected `HiltWorkerFactory`. This is required for `ProtectionRecoveryWorker`.
- App-scoped bindings in `app/.../di/AppModule.kt`: `DomainBlocker -> BlocklistRepository`,
  `BlockEventRecorder -> BlockEventRepository`.
- Database provided by `DatabaseModule` (`core/database/.../di/DatabaseModule.kt`);
  security by `SecurityModule` (`core/security/.../di/SecurityModule.kt`).

## Startup sequence

1. Process starts; the App Startup `InitializationProvider` runs but **the default
   `androidx.work.WorkManagerInitializer` is removed** in the manifest
   (`app/src/main/AndroidManifest.xml`), so WorkManager initializes **on demand** with the
   `Configuration.Provider` config (i.e. `HiltWorkerFactory`).
2. `ShieldApplication.onCreate` launches on `dispatchers.io`:
   `timingAnchorRepository.ensureFirstRunEpochMs()` -> `blocklistRepository.initialize()` ->
   log `shield initialized: blocklist+timing ready`.
3. `MainActivity` (single-task launcher) resolves the nav start destination from onboarding
   state: `onboarding` or `dashboard`. It also owns the VPN-permission handshake
   (`VpnService.prepare()` -> `ProtectionEnforcer.enforceNow()`).

## Data flow (block path)

```
SeedBlocklistLoader (assets/seed_blocklist_v1.json)
  -> DomainRecord list
    -> DomainDao.upsertAll() (Room domain_rules)
      -> DomainDao.enabledRules()  (status ACTIVE + ALLOWLISTED)
        -> DomainIndexCompiler.compile()
          -> DomainTrieIndex (exact HashMaps + reversed-label trie)
            -> DecisionEngine.decide(host, scheduleActive)
              -> BlockDecision (BLOCK/ALLOW)
                -> ShieldVpnService handles DNS query in tun
                  -> NXDOMAIN (block) | forwarded upstream (allow)
                  -> BlockEventRepository.recordBlocked()
```

## Build environment

- **JDK 17** required (`sourceCompability`/`jvmTarget` = 17 everywhere).
- `GRADLE_USER_HOME=C:\grhome`, `maven.repo.local=C:\grhome-m2` (space-free paths; Robolectric
  resolves `android-all-instrumented` jars there).
- `gradle.properties`: parallel + caching on, config cache off, `-Xmx6g`.
- Root `build.gradle.kts` `subprojects {}` configures every `Test` task with
  `systemProperty("maven.repo.local", "C:\\grhome-m2")` and
  `jvmArgs("--enable-native-access=ALL-UNNAMED")`.