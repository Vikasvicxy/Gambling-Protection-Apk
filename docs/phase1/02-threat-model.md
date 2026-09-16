# Threat Model — Phase 1

## Assets

| Asset | Where it lives | Why it matters |
|---|---|---|
| Blocklist integrity | Room `domain_rules` + in-memory `CompiledIndex.digest` (SHA-256) | A corrupted index silently under-/over-blocks. |
| User's protection settings & schedule | DataStore `shield_settings`, `shield_timing` | Attackers flipping these can disable protection. |
| Commitment accounting | DataStore `shield_timing` + Room `commitments` | Undoing elapsed time lets the user finish a commitment early. |
| Installation identity | Android KeyStore + DataStore `install_identity` | Used for false-positive reporting; must stay anonymous and non-tracking. |
| Device privacy (which domains the user visits) | In-memory only (`ShieldVpnService` counters), Room `block_attempt_groups` | Blocked/queries stats must never leave the device in Phase 1. |
| Credentials/keys | None stored in code or repo (release signing deliberately out-of-VCS) | Zero secrets in-repo is a hard requirement. |

## Adversaries

1. **The user themselves** — the primary threat: the person who *wants* to gamble and may try to
   bypass or short-circuit protection.
2. **Malicious/anonymous apps on the same device** — capablity to issue intents, observe
   notifications, or attempt to tamper with app storage.
3. **Network intermediary** — ISP/router/DNS operator between device and upstream resolver.
4. **Casual attacker with USB/adb access** on a debug build.
5. **Supply-chain** (not a Phase 1 threat surface — dependencies are pinned).

## Threats and mitigations

| # | Threat | Surface | Phase 1 mitigation |
|---|---|---|---|
| T1 | User disables protection | VPN toggle, app data | `ProtectionEnforcer` restores VPN on boot/update (`ProtectionRecoveryWorker`); health reporting surfaces revocation. |
| T2 | User or app sets device clock back to finish a commitment early | `TimingAnchorRepository` | The commitment clock is monotonic `elapsedRealtime`-derived, not wall clock. Wall-shifts > threshold (5 min) are detected and counted (`totalWallShiftsDetected`); implausible deltas (>12 h) rejected (see `TimingAnchorRepository.kt`). |
| T3 | User edits/restores preferences | DataStore file | `allowBackup="false"` in manifest; data-extraction rules in `@xml/data_extraction_rules`. |
| T4 | Corrupt blocklist index | seed → Room → trie | Seed is bundled inside the APK; Room is the source of truth; `CompiledIndex` carries a SHA-256 `integrityDigest` over normalized domains and the health check reports it (digest-10). |
| T5 | Allowlist override bypass | `domain_rules` | `addAllowlist()` upserts `ALLOWLISTED` rows and rebuilds the index; allowlist rules are compiled in and beat blocklist ties in the trie lookup. |
| T6 | DNS leakage outside the tunnel | device routing | `VpnService` establishes the tun with a hardcoded internal address (`10.147.2.1/32`) and `addDnsServer(TUN_ADDR)`; all device DNS is forced into the tunnel. TCP DNS on privileged port 53 is intentionally dropped. |
| T7 | Spoofed NXDOMAIN / on-path poisoning | DNS responses | Upstream responses are only used to copy RDATA; blocking decisions are made locally from the trie; queries are forwarded over a socket `protect()`-ed from the tunnel. |
| T8 | Secret leakage in logs | `ShieldLogger` | Interface docs mandate a "VAULT" policy: never log credentials, tokens, or personal content; `Logs.VAULT` tag is reserved and must stay empty in Phase 1. |
| T9 | Backup/restore cloning the install identity | KeyStore ciphertext | On decryption failure a fresh ID is generated; acceptable because Phase 1 has no server analytics. |
| T10 | Reverse engineering of seed/engine | APK | Release uses R8 minify + resource shrinking; `proguard-rules.pro` only keeps frameworks' reflection surfaces. |
| T11 | Notifications/overlay abuse | permissions | Health engine's `checkPermission` verifies notification + overlay state; routine guidance for OEM battery-police whitelisting (`OemInfoRepository.guidance`). |

## Explicitly OUT of scope for Phase 1 (accepted risk)

- **Holding HTTPS/DoH by design is a future commitment, not a Phase 1 control.** The app is a
  DNS blocker; apps using encrypted DNS (DoH/DoT) bypass it — documented in the support screen
  and the limitations doc.
- **Fail-open philosophy**: unparseable queries, upstream timeouts, and out-of-schedule time
  ALLOW rather than BLOCK (`DecisionEngine.decide`). Availability of the user's network beats
  strictness in Phase 1.
- Malicious co-installed apps can still read system-level DNS. Device is assumed non-rooted and
  not jailbroken.
- No server side exists; remote blocklist updates are `NOT_CONFIGURED` (`UpdateRepository`).