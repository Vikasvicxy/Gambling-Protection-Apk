# Phase 2 — Signed Update Pipeline

**Date:** 2026-09-17 · **Modules:** `core:release` (pure JVM: build/verify/Delta), `data:update` (device side), `tools:blocklist` (release tooling), `.github/workflows/blocklist-release.yml` (CI)

Addresses Phase 1 limitation "No server" and "Release signing is configured but no automated
distro": remote blocklist updates in PARSE → VERIFY → POLICY → APPLY order, end to end,
with a real automated release job.

## 1. End-to-end flow

```
upstream hosts lists
   │  fetch (tool consumes only ALLOWED_LICENSES sources)
   ▼
ReleasePipeline.process ─ normalize → dedupe → classify → confidence → allowlist-safety → sort
   ▼
ReleaseBuilder.build    ─ diff vs previous (DeltaEngine) → gzip NDJSON full + delta → ECDSA-P256 sign
   ▼
out/releases/  (full*.json.gz, delta-*.json.gz, manifest.json/latest.json)
   │
.github/workflows/blocklist-release.yml ─ canary = cron/2×day, stable = manual + env approval
   ▼ gh-pages (canary/ and stable/)
device: BlocklistUpdateEngine ─ fetch manifest → VERIFY SIG → VersionPolicy → hash-check →
        decode+validate → Room write (transactional) → UpdateState published
```

The exact same pure code runs on CI (build) and on the device (verify): `core:release`
deliberately has **zero Android dependencies**, so wire round-trips are byte-identical on
both sides.

## 2. Signed release format

- **Envelope** = `{ manifest: SignedReleaseManifest, signature: { algorithm, keyId, signatureBase64 } }`.
- Signature covers the **canonical JSON of the manifest only**; the manifest pins
  `sha256` + `sizeBytes` of `full` and `delta` artefacts → one signature authenticates
  everything.
- **Full payload:** gzip NDJSON, one `ReleaseDomainRecord` per line (7.2 B/record at
  200k rules measured in `docs/PERFORMANCE.md`).
- **Delta payload:** gzip NDJSON of `DeltaOp` (header + add/modify/remove). Forward-only,
  single-hop (`baseVersion → baseVersion+1`). Measured 200k-rule delta adding ~7.5%
  change ≈ 112 KB vs 1.4 MB full payload.
- Deterministic ordering (sorted adds/modifies/removes) → reproducible artefacts,
  diff-able with `gzip -d`.

## 3. Verification order (fail-closed, `BlocklistUpdateEngine`)

1. **Signature first** — `keyRing.verify(envelope)`; nothing is consulted before it holds.
2. **Version policy** — `VersionPolicy.evaluateUpgrade`: `minimumAppVersion` ≤ app
   versionCode, strictly forward, replay-safe via `maxObservedVersion`, signed emergency
   rollbacks only.
3. **Artifact hash/size** — `ReleaseValidator.verifyArtifact` against the SHA-256 pinned in
   the manifest.
4. **Decode + semantic validation** — hard decompressed-size cap (512 MiB), parse every
   NDJSON line, validate every record + delta count/dup invariants.
5. **Apply** — deltas re-apply into the canonical table and write the FULL resulting set
   (never partial writes); every failure leaves the last-known-good database untouched.

Fetching is HTTPS-only with hard size caps, bounded redirects/timeouts, and no caching of
responses (`ReleaseDownloader`). Key material is public-only on the device; see
`docs/phase2/06-key-management.md`.

## 4. Device side (`data:update`)

- `UpdateCheckWorker` → `BlocklistUpdateEngine.checkForUpdate(preferDelta)`.
- Delta-first when the manifest's `baseVersion == installedVersion`; falls back to full;
  logs failures, publishes `UpdateState` (`UP_TO_DATE` / `FAILED` / ...) via
  `UpdateStateRepository` + `UpdateRepository`.
- `UpdateState.installedVersion()` / `maxObservedVersion()` drive version/replay policy.
- Hilt wiring: `SigningKeySource` (public-key ring), `UpdateFetcher` (`ReleaseDownloader`),
  `UpdateConfig`, applier, repository — all bound in `di/UpdateModule.kt`.

## 5. Release tooling (`tools:blocklist`)

`Main.kt` commands: `fetch` (licensed sources only), `process` (→ `out/processed.ndjson.gz`),
`build` (→ signed `out/releases/`), `verify` (self-check newest release with the public key).
`generateDevSigningKeys` boots the local dev keypair (public → app assets, private →
git-ignored `local/`). The private key is never committed and never enters the APK.

`benchmark` (new, Phase 2) runs the JVM micro-benchmarks whose results are recorded in
`docs/PERFORMANCE.md`.

## 6. CI (`blocklist-release.yml`)

- **Canary:** cron every 12 h + manual; dev key from `RELEASE_SIGNING_PRIVATE_KEY`;
  publishes to `gh-pages/canary/manifest.json` (+ payloads).
- **Stable:** manual with channel=stable, `environment: stable-release` (approval gate);
  publishes to `gh-pages/stable/`.
- Both run fetch → process → build → verify against the committed public key before
  publishing (self-check fails the job on any mismatch).

## 7. Relation to Phase 1 roadmap

Addresses roadmap item 1 ("Blocklist updates: verify update authenticity before Room
ingest; expose UpdateState"). Remaining Phase 2/3: over-the-wire false-positive reporting
(roadmap 2), device-farm E2E (roadmap 4), HTTPSNI/HTTPS mitigation (roadmap 5).