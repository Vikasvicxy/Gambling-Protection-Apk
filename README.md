<div align="center">

# Shield

**Gambling protection that runs entirely on your device.**

[![Latest Release](https://img.shields.io/github/v/release/Vikasvicxy/Gambling-Protection-Apk?label=Latest%20Release&color=34d399&style=flat-square)](https://github.com/Vikasvicxy/Gambling-Protection-Apk/releases/latest)
[![CI](https://github.com/Vikasvicxy/Gambling-Protection-Apk/actions/workflows/android.yml/badge.svg?branch=master)](https://github.com/Vikasvicxy/Gambling-Protection-Apk/actions/workflows/android.yml)
[![Privacy First](https://img.shields.io/badge/privacy--first-zero%20telemetry-34d399?style=flat-square)](#privacy)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Material 3](https://img.shields.io/badge/UI-Material%203-757575?style=flat-square&logo=materialdesign&logoColor=white)](https://m3.material.io)
[![AGP](https://img.shields.io/badge/AGP-9.4-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/build)
[![License](https://img.shields.io/badge/license-see%20repo-8A8A8A?style=flat-square)](#license)

Local DNS blocking · Urge cooling-off · Recovery tracking · AES-256 encrypted backups

[Privacy Policy](https://vikasvicxy.github.io/Gambling-Protection-Apk/) ·
[Store listing kit](docs/store/) · [Release notes](docs/store/release_notes.txt)

</div>

---

## What it does

Shield answers DNS queries **on the device** and refuses the ones that point at
gambling. A betting site never resolves, so it never loads, so nothing to block
with an extension and nothing to bypass with a private window.

- **On-device DNS blocking** via a loopback `VpnService`. No server in the path.
- **Gambling apps blocked too**, not just websites.
- **15-minute urge cooling-off** with a typed pledge. The friction is the point.
- **Fortress windows** — schedule lock-downs for the hours you know are risky.
- **Guardian PIN** — turning protection off takes a deliberate extra step.
- **Recovery tracking** — day count, money saved, craving journal, milestones.
- **AES-256-GCM backup and restore**, with a key only you hold.
- **Offline crisis directory** — the number you need is never behind a request.

> Shield is not a substitute for professional care, therapy, or medical advice.

---

## Architecture

A single Gradle project split into modules by responsibility, not by feature, so
the protection engine can be reasoned about without dragging in Compose.

```
shield/
├── app/                     Application, Hilt root, navigation, WorkManager
│
├── core/                    Pure logic, no Android UI, no I/O assumptions
│   ├── common/              Dispatchers, clock, logging, Result types
│   ├── model/               Domain models + kotlinx.serialization (JVM only)
│   ├── design-system/       Material 3 theme, ShieldButton, ShieldText
│   ├── database/            Room database, entities, DAOs
│   ├── security/            Guardian PIN PBKDF2, backup AES-256-GCM envelope
│   ├── release/             Version and build metadata
│   ├── integrity/           Device integrity attestation
│   ├── testing/             Shared fixtures, fakes, test dispatchers
│   ├── accountability/      Heartbeats, pairing tokens, event grouping
│   └── admin/               Audit log, moderation
│
├── protection/              The blocking engine. No UI lives here.
│   ├── domain-engine/       Domain normalisation and matching
│   ├── dns/                 Resolution, allow/deny decision
│   ├── vpn/                 The loopback VpnService itself
│   ├── health/              Protection health monitoring
│   ├── boot/                Re-arm protection after reboot
│   ├── oem/                 OEM-specific network quirks, VPN conflicts
│   └── tamper/              Tamper signals, evidence HMAC
│
├── data/                    Where bytes actually go
│   ├── blocklist/           Seed list and source management
│   ├── update/              Signed blocklist fetch, delta engine, sync worker
│   ├── preferences/         DataStore
│   ├── backup/              Encrypted export and two-phase atomic restore
│   ├── repository/          Repository impls, ProtectionGateHolder
│   └── accountability/      Backend client + its own Room database
│
├── feature/                 One module per user-facing surface
│   ├── onboarding/  setup/  dashboard/  reports/  diagnostics/
│   ├── support/     settings/  accountability/  parent/
│
└── tools/
    └── blocklist/           Blocklist build pipeline (fetches, signs, publishes)
```

**The rule that shapes this:** `:core:model` is a pure JVM module with no Android
dependency, so domain rules — streak maths, milestone thresholds, payload
validation — are testable on the JVM in milliseconds, and cannot accidentally
acquire a context.

### Restore is two-phase on purpose

`prepareRestore` decrypts and fully validates a file **without writing**, so the
confirmation screen can show what is actually inside before the user agrees to
lose what is on the device. `applyRestore` then writes the same validated object
the preview described. The database swap runs in one Room transaction, so a
failure part-way through rolls the whole thing back.

---

## Build from source

**Requirements:** JDK 17, Android SDK with platform 37.0 and build-tools 37.0.0.

```bash
git clone https://github.com/Vikasvicxy/Gambling-Protection-Apk.git
cd Gambling-Protection-Apk

./gradlew testDebugUnitTest        # 218 unit tests
./gradlew lintDebug                # static analysis
./gradlew assembleDebug            # debug APK
```

On Windows use `.\gradlew.bat` in place of `./gradlew`.

### Release signing

Release builds are **opt-in** and are not production-signed unless a keystore is
present. Keystores and passwords are never committed.

```bash
# macOS / Linux
./scripts/generate-keystore.sh

# Windows
.\scripts\generate-keystore.ps1
```

This writes a git-ignored `keystore.properties` at the repository root. Once it
exists, `assembleRelease` and `bundleRelease` sign with it automatically.

To fail the build rather than silently fall back to the debug key — which is what
CI does, because a debug-signed "release" can never be uploaded to Play:

```bash
./gradlew assembleRelease -PshieldRequireReleaseSigning=true
```

### Release tagging

```bash
git tag v1.0.0
git push origin v1.0.0
```

Pushing a `v*` tag runs the pipeline: lint and tests, then a full assembly, then
a **draft** GitHub Release with the APK and AAB attached. It is a draft on
purpose — a human reads the notes and confirms the signing state before it is
published. Nothing is announced automatically.

Before tagging, bump `versionCode` and `versionName` in `app/build.gradle.kts`.
Google Play requires `versionCode` to increase on every upload.

### Repository secrets for CI

Release signing in Actions is reconstructed from repository secrets. Set all four
under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `SHIELD_RELEASE_KEYSTORE_BASE64` | `base64 -w0 release-keys/shield-release.p12` |
| `SHIELD_KEYSTORE_PASSWORD` | keystore password |
| `SHIELD_KEY_ALIAS` | key alias |
| `SHIELD_KEY_PASSWORD` | key password |

Without them the build still succeeds, but the artifacts are marked
`UNSIGNED-BUILD.txt` and the release job **refuses** to draft a release from them.

---

## Privacy

No ads. No analytics. No crash reporters. No tracking. No fingerprinting.

Every dependency is Jetpack, AndroidX, kotlinx, Dagger, or first-party — there is
no Firebase, Crashlytics, ad network or attribution SDK in the graph. That is
what makes the claim checkable rather than aspirational, and it is checkable:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Scanned for `firebase|crashlytics|amplitude|mixpanel|facebook|appsflyer|branch|
segment|admob|ironsource|sentry|bugly` and the result is nothing. See
[OPEN_SOURCE_INVENTORY.md](OPEN_SOURCE_INVENTORY.md) for the full component list
and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for license texts.

DNS queries are resolved or refused **on the device**. No query, hostname, page
visited or traffic payload is ever uploaded. Streak, journal, Guardian PIN and
backups live in the private on-device database and never leave it.

Two things *do* cross the network, and the privacy policy says so plainly:

1. **Signed blocklist updates** from a static URL. No user data in the request.
   A seed list ships in the APK, so blocking works offline.
2. **Accountability pairing** — opt-in, off by default. If enabled, a
   pseudonymous device ID, app version, protection state and blocked-attempt
   events are relayed to the pairing service.

Full details: [docs/privacy-policy.md](docs/privacy-policy.md), rendered at
<https://vikasvicxy.github.io/Gambling-Protection-Apk/>.

The privacy site is a single self-contained page with **zero external requests**
— no font CDN, no analytics script, no consent banner.

---

## Toolchain

| | |
| --- | --- |
| Language | Kotlin 2.3 |
| UI | Jetpack Compose, Material 3 |
| Architecture | Hilt, unidirectional data flow |
| Persistence | Room 2.8, DataStore Preferences |
| Serialization | kotlinx.serialization |
| Build | AGP 9.4, Gradle 9.7, JDK 17 |
| Min / target SDK | 26 / 36, compiled against 37 |

---

## Documentation

| Path | What it is |
| --- | --- |
| [docs/privacy-policy.md](docs/privacy-policy.md) | The real privacy policy |
| [docs/store/](docs/store/) | Play Store listing kit and Data Safety answers |
| [PLAY_POLICY.md](PLAY_POLICY.md) | Play policy compliance statement |
| [OPEN_SOURCE_INVENTORY.md](OPEN_SOURCE_INVENTORY.md) | Third-party components and license families |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | License texts for those components |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | Domain index benchmarks and the signed release pipeline |
| [docs/phase1–4/](docs/) | Design docs: threat model, engine design, test strategy, policy review |
| [scripts/](scripts/) | Keystore generation, real-device test harness |

## License

Source is provided for review and contribution. The repository does not yet carry
a license file — **add one before distributing a build**, or the default
"all rights reserved" applies and nobody may legally reuse it.
