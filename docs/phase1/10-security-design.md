# Security Design — Phase 1

## Principles

1. **No secrets in the repo.** Credentials, keys, and tokens are never committed. Release
   signing keys are deliberately **outside VCS** (documented; the repo ships no keystore).
2. **Nothing user-sensitive leaves the device.** Privacy (which domains were queried/blocked)
   is in-memory or on-device only.
3. **Logging is conservative.** `ShieldLogger` interface docs mandate a VAULT policy: never
   log credentials, tokens, or personal content; the `Logs.VAULT` tag is reserved and must
   remain empty.
4. **Fail-open by default, fail-critical on corruption.** Malformed packets/upstream failures
   ALLOW; a missing/corrupt blocklist index is CRITICAL, never silently accepted.

## Install identity (`KeystoreInstallId`, `core/security`)

- Anonymous, non-tracking device ID for Phase 1 false-positive flow.
- AES-256-GCM key `alias = shield_install_id_key` in the **AndroidKeyStore**; ciphertext
  persisted in DataStore (`install_identity`). On KeyStore re-init/decryption failure a fresh
  ID is generated (identity is disposable — no server depends on it in Phase 1).
- Bound to the keystore, not exported by backup (`allowBackup="false"`).

## Storage hardening

- Manifest: `allowBackup="false"` and `@xml/data_extraction_rules` (no cloud backup/device
  transfer of protection settings).
- Credential-free by construction; deterministic random session? *No* — networking is
  outbound-only (DNS upstream).

## VPN/sniffing surface

- `ShieldVpnService` runs as a foreground service (`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`),
  `START_STICKY`; tun established in `onStartCommand`.
- The device DNS is pinned to the tun (`addDnsServer(TUN_ADDR)`), so per-app DNS is at least
  observable to the tunnel (never re-encrypted in Phase 1).
- Socket `protect()` keeps DNS upstream traffic out of the tunnel to prevent loopback.

## App binary

- Release build: R8 full minification + resource shrinking (`release { minifyEnabled = true,
  shrinkResources = true }`), matching `proguard-rules.pro` keeps only framework
  (Google/WorkManager, Hilt, Robolectric-test) reflection surfaces. Signing config reads
  release keystore from environment (`releaseKeystore.properties`-style setup) — not committed.
- `networkSecurityConfig` restricts cleartext (DNS is UDP, not HTTP).
- Debug builds demand debug-keystore; no alternative debug signing.

## Tamper/schedule resistance

- Timing anti-wind-back: monotonic `elapsedRealtime` accounting + wall-shift detection
  (> 5 min) + implausibility gate (> 12 h rejected), in `TimingAnchorRepository` (see
  commitment doc).
- Boot/LOCKED_BOOT penetration of protection after device restart:
  `ProtectionRecoveryWorker` (WorkManager, HiltWorkerFactory; BOOT_COMPLETED/
  LOCKED_BOOT_COMPLETED receiver restored).
- `CommitmentEngine` never allows shrinking a duration; `canFinish` only via accumulated
  monotonic time.

## Openness / assessing security posture

- Health + diagnostics screens expose live `HealthReport` and OEM/battery guidance.
- No server: nothing to compromise externally in Phase 1.
- **Known gaps**: no over-the-air signature checking (no network updates); the blocking DNS
  engine is trivially bypassable by apps that pin an encrypted resolver (fail-open is a
  privacy cop-out, not an integrity box); debug `Logs` are verbose only in debug builds.