# Domain Engine — Phase 1

Module: `protection/domain-engine` — pure Kotlin, no Android dependencies. 47 unit tests
across 4 files.

## Purpose

Given a normalized hostname and the protection-schedule state, produce a `BlockDecision`
(BLOCK or ALLOW) with a stable signature, using an in-memory compiled index. The engine is
deliberately hot-path-safe: **matching never touches I/O or SQLite** (`DomainBlocker`
contract, `DecisionEngine.decide`).

## Normalization (`DomainNormalizer.kt`)

- `normalize(host)`: lowercase + trim, strips scheme/path/query/fragment/userinfo/port,
  converts IDN to punycode (STD-3), rejects IP literals and invalid labels, understands `*.`
  wildcard prefixes. Returns null for unparseable input (the caller then ALLOWs — fail-open
  on malformed hosts).
- `superdomains(domain)` yields parent domains longest-first for suffix iteration.

## Compiled index (`DomainIndexCompiler.kt`, `DomainIndex.kt`)

`CompiledIndex` = `DomainIndex` + SHA-256 `digest` over sorted normalized domains (corruption
detection) + `enabledCount`/`allowlistCount`/`sourceVersion`.

`compile(records, sourceVersion)` keeps only **ACTIVE (block)** and **ALLOWLISTED (allow)**
records — every other status is ignored during compilation.

`DomainTrieIndex` is a hybrid structure (`DomainIndex.kt:36-116`):
- two exact-match `HashMap<String, RuleHit>`s — `exactBlocked`, `exactAllowed` — for O(1)
  exact hits, plus
- two **reversed-label trie** trees (`blockedRoot`, `allowRoot`) for suffix/subdomain matching:
  `example.com` in the trie also matches `www.example.com` without materializing subdomains.

### Lookup priority (allowlist wins)

1. `exactAllowed` hit -> ALLOWED (allowlist beats a blocklist entry on the same name).
2. `exactBlocked` hit -> BLOCKED.
3. Longest-suffix walk through `allowRoot` and `blockRoot` in parallel, most-specific rule
   wins; an allow match at any depth beats a block match on the same query.

Tests pin the exact-match-beats-suffix-parent ordering and allowlist-over-blocklist tie break
(`DomainIndexTest`).

## Decision engine (`DecisionEngine.kt`)

`decide(host, scheduleActive)`:
1. Normalize; null -> ALLOW ("malformed host").
2. Schedule gate: `!scheduleActive` -> ALLOW ("outside active protection schedule").
3. Index lookup; map BLOCKED -> `DecisionKind.BLOCK`, ALLOWED -> `DecisionKind.ALLOW`.
4. Attach `signature = stableHash(...)` for block-event deduplication/collapse.

## Wiring

`data/blocklist`'s `BlocklistRepository` implements `DomainBlocker` and swaps in a brand-new
`DecisionEngine` on every `rebuildIndex()`; `app/.../di/AppModule.kt` binds
`DomainBlocker -> BlocklistRepository`. The VPN hot path calls
`blocker.decide(host, scheduleActive)` per DNS question.

## Test coverage

`DomainNormalizerTest` (18): IDN/punycode, IP rejection, wildcard, superdomains.
`DomainIndexTest` (13): suffix matches, most-specific wins, allow-vs-block tie breaks,
exact-vs-suffix order. `DomainIndexCompilerTest` (5): status filtering, empty index,
deterministic digest. `DecisionEngineTest` (11): schedule gate, malformed hosts, signature
stability. End-to-end coverage of the compiled 12+4 seed through the full
`load -> compile -> decide` path in `data/blocklist`'s `SeedBlocklistIntegrationTest`.