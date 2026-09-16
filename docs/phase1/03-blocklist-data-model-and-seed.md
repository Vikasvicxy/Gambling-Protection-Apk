# Blocklist Data Model & Seed — Phase 1

## Source of truth: Room

`core/database` owns the schema. Database `shield.db`, version **1**, `exportSchema = true`,
no migrations in Phase 1 (`core/database/.../ShieldDatabase.kt:62-63`). WAL mode is Room's
default.

Tables:

| Table | Entity | Purpose |
|---|---|---|
| `domain_rules` | `DomainEntity` | Block/allow rules. |
| `commitments` | `CommitmentEntity` | Active/completed commitments. |
| `block_attempt_groups` | `BlockAttemptGroupEntity` | Per-domain blocked-count aggregates. |
| `activity_events` | `ActivityEventEntity` | Engagement events (onboarding, setup…). |
| `false_positive_reports` | `FalsePositiveReportEntity` | User-flagged false positives. |
| `meta` | `MetaEntity` | Key/value metadata (`blocklist_version`, `blocklist_digest`, `boot_count`, `boot_last_recorded_epoch`). |

Enums are persisted via `EnumsConverter` (`ShieldDatabase.kt:31-51`) with safe fallbacks for
blank values.

## Rule model (`core/model/.../Blocklist.kt`)

- `Category`: GAMBLING, CASINO, SPORTSBOOK, POKER, LOTTERY, CRYPTO_GAMBLING, AFFILIATE, UNKNOWN.
- `BlockStatus`: **ACTIVE** (block rule), **ALLOWLISTED** (user override), plus CANDIDATE,
  FALSE_POSITIVE, DISABLED.
- `Confidence`: HIGH/MEDIUM/LOW/UNKNOWN.
- `RiskLevel`: CRITICAL/HIGH/MEDIUM/LOW/UNKNOWN.
- `Operator`: GAMBLOCK_SEED, FUTURE_EXTERNAL, USER_ALLOWLIST, CORE_ALLOWLIST.
- `DomainRecord`: domain, `normalizedDomain`, category, status, confidence, riskLevel,
  `firstSeenEpochMs`, `lastVerifiedEpochMs`, source, operatorId, `databaseVersion`,
  `appliesToSubdomains`.

## The critical status semantics

`DomainDao.enabledRules()` (`core/database/.../dao/DomainDao.kt:19-20`) returns:

```sql
SELECT * FROM domain_rules WHERE status IN ('ACTIVE', 'ALLOWLISTED')
```

Both populations are required: the compiler splits them — **ACTIVE builds the block trie,
ALLOWLISTED builds the allow trie** (`DomainIndexCompiler.compile`). Without the
ALLOWLISTED clause, user allowlist overrides would silently vanish from the compiled index
(they could never override a block). `activeCount()`/`allowlistCount()` keep the two positions
separate. `observeEnabledRules()` exposes the same set as a `Flow` for future reactive rebuilds
(not consumed in Phase 1).

`addAllowlist(normalizedDomain)` (`BlocklistRepository.addAllowlist`) find-or-upserts an
ALLOWLISTED row with `operatorId = USER_ALLOWLIST`, then rebuilds the index.

## Seed

Bundled as an Android asset: `data/blocklist/src/main/assets/seed_blocklist_v1.json`.
Name/format constants in `SeedBlocklist.kt`; loader in `SeedBlocklistLoader.kt` (reads with
`context.assets.open`, deserializes with kotlinx.serialization, normalizes every domain).

Shape:

```
SeedBlocklistFile { schema: "gamblock-seed-blocklist", version: 1,
                    releasedAtEpochMs: Long epoch, entries: […] }
SeedEntry { domain, category (default GAMBLING), confidence (HIGH),
            riskLevel (MEDIUM), status (ACTIVE), source ("seed"),
            appliesToSubdomains (true) }
```

Phase 1 seed inventory: **12 ACTIVE + 4 ALLOWLISTED** rules, `releasedAtEpochMs = 0`.
Tests assert this exact composition.

## Loading and index build (`BlocklistRepository`)

1. `initialize()` (idempotent): if `meta.blocklist_version` is absent, `seedFromAssets()`
   bulk-upserts records and stamps `blocklist_version`/`blocklist_digest` in `meta`.
2. `rebuildIndex()`: `enabledRules()` -> map to `DomainRecord` ->
   `DomainIndexCompiler.compile(records, sourceVersion)` -> stashes the `CompiledIndex`,
   creates a fresh `DecisionEngine`, publishes `BlocklistSnapshot` (index + `BlocklistStats`)
   on a `StateFlow`, and logs `… blocked, … allowlisted` digest prefix.
3. Decisions never touch SQLite: `decide()` delegates straight to the in-memory engine
   (fail-open if the engine is null).

`BlocklistStats` (`core/model/.../Blocklist.kt`) carries `databaseVersion`, counts, load/update
epochs, `integrityDigest`, and `updateState` (`IDLE..NOT_CONFIGURED`).

## Hot-path guarantee

`DomainIndex.kt` doc contract: matching is O(1) exact + suffix-trie; Room is only the durable
source of truth, moved to RAM at rebuild time. See `05-domain-engine.md` for the matching
algorithm.