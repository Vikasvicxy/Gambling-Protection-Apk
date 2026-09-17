# Shield Performance — Domain Index & Signed Release Pipeline

**Date:** 2026-09-17 · **Source of truth:** `:tools:blocklist:benchmark` (`BenchmarkMain.kt`)

This document is the benchmark reasoning referenced by `DomainIndex.kt` ("see PERFORMANCE.md
for benchmark reasoning") and by the Phase 2 signed-update pipeline. It records the measured
behaviour of the hot paths, their design rationale, and their scaling envelope.

## 1. Breadboard

- Plain JVM (JDK 17.0.12), Windows, no Android runtime in the loop — a clean CPU/GC proxy
  for both the CI release job and the on-device background verify/apply path.
- Deterministic, seeded inputs (seed `0x5EED`): identical synthetic rule sets every run.
- Methodology: warm-up before every measured phase; JIT-stabilised MIN (best-of) is reported;
  lookups were self-checked (every probe matched the expected outcome).
- Re-run with: `.\gradlew.bat :tools:blocklist:benchmark --console=plain`

## 2. Release pipeline (process → build → sign → verify → delta apply)

| Benchmark | Input | Result |
|---|---|---|
| `release.pipeline.process` | 200,000 raw source lines (~30% duplicated across 2+ sources) | **358 ms** → 200,000 unique records (normalize, dedupe, classify, confidence, sort) |
| `release.build.full` | 200,000 records, first release, ECDSA P-256 sign | **336 ms** → full payload **1,445,099 bytes** (~7.2 B/record gzip NDJSON) |
| `release.verify.full` | signed 200,000-record release | **354 ms** (parse manifest → ECDSA verify → SHA-256 hash check → gunzip+decode → validate every record) |
| `release.build.delta` | base 200k → next 205k (+10,000 / −5,000 / −2,000) | **424 ms** → delta payload **111,630 bytes** (≈7.7% of the full payload) |
| `release.verify.apply.delta` | signed delta above | **89 ms** (signature+policy → decode+validate delta ops → apply onto 200k base map) |

Notes:

- The real gambling-only sources are far smaller than the synthetic maximum: StevenBlack
  gambling-only hosts (~52k lines), bigdargon hostsVN gambling extension (a few thousand),
  Sinfonietta gambling-hosts (hundreds). Real-world `process` is well under 150 ms.
- Delta carry: for a release that changes ~7.5% of 200k rules the delta is **~12x smaller**
  than re-downloading the full payload. Delta verify+apply on a 200k base completes in well
  under 100 ms on a JVM-class core after signature+hash checks.
- All figures include the mandatory fail-closed crypto checks (ECDSA-P256/SHA-256 verify,
  manifest-pinned artifact SHA-256, size caps, record-level semantic validation).

## 3. Domain index (build + lookup)

Design (see `DomainIndex.kt`): O(1) exact-match hash maps for the common path, plus a
reversed-label trie so a blocked apex (e.g. `example.com`) also matches `www.example.com` /
`m.example.com` / `cdn01.m.example.com` **without materialising any subdomain rows**.

| Benchmark | Input | Result |
|---|---|---|
| `domain.trie.build` | 200,000 rules (195k leaves + 5k apex roots + 1 allowlist) | **152 ms** |
| `domain.lookup.exact_hit` | 50,000 exact probes × 5 runs | **45 ns/op ≈ 22,400 k-op/s** |
| `domain.lookup.subdomain_hit` | 50,000 4-label probes under apex rules | **234 ns/op ≈ 4,300 k-op/s** |
| `domain.lookup.miss` | 50,000 absent domains × 5 runs | **128 ns/op ≈ 7,800 k-op/s** |
| `domain.compiler.compile+digest` | 200,000 records → index + SHA-256 integrity digest | **215 ms** |

Scaling envelope vs. the hot path:

- The DNS proxy serves a handful of lookups per DNS query at most. Even a 4-label suffix
  match (the slowest case) costs ~234 ns. At, say, 200 queries/s that is ≈0.005% of a
  single core — effectively free. Even at 10,000 queries/s it is ≈0.2%.
- Verbose subdomain materialisation (the rejected alternative) would store one row per
  observed subdomain and blow up both storage and build times; the trie keeps the table
  proportional to the **rules**, not the **lookups**.
- Index rebuild from Room payload (195k+ records incl. digest) is ~215 ms on JVM; on-device
  this happens once at blocklist (re)load on a background thread, not per query.

## 4. What is NOT measured here (honesty box)

- No Android/HW: values exclude AndroidKeyStore/AndroidOS overhead and device cpus; treat
  on-device numbers as same order of magnitude, not identical.
- No network (fetch) timings: on-device fetch is bounded by caps/timeouts in
  `ReleaseDownloader`; crypto+apply dominate the measured cost and are the numbers above.
- No HMAC/tamper-chain or integrity-classifier benches: those paths cost microseconds per
  event and are not the load-bearing cases this file documents.

## 5. Methodology & determinism

- Reproducible inputs from a fixed seed so regressions are visible in git diffs of the
  printed numbers.
- A sanity-asserting warm-up pass guards the lookups: if the probe set ever stops matching
  the index (e.g. a refactor changes rule semantics), the benchmark fails loudly instead of
  reporting a meaningless number.