# Phase 2 — Key Management (Release Signing)

**Date:** 2026-09-17 · **Module:** `core:release` (`ReleaseCrypto`, `TrustedKeyRing`) · **Tooling:** `:tools:blocklist:generateDevSigningKeys`

This file is referenced by `ReleaseBuilder.kt`. It documents how the blocklist-release signing
keys are generated, stored, shipped and rotated — and what is deliberately NOT stored where.

## 1. Scheme

- **Algorithm:** ECDSA P-256 (`secp256r1`) with SHA-256, JCA `SHA256withECDSA`.
- **Signed artefact:** the canonical JSON bytes of `SignedReleaseManifest` only
  (`CanonicalCodec.canonicalBytes`) — the manifest pins the SHA-256 + size of every payload
  artefact, so one signature authenticates the manifest **and** the full/delta payloads.
- **Key identifier:** `ReleaseCrypto.fingerprint(publicKey)` = first 16 hex chars of
  SHA-256 over the DER-encoded public key. Used by clients to confirm they verify with the
  exact key the signer intended, and to address keys during rotation.

Why ECDSA-P256 instead of Ed25519: Ed25519 is only reliably available on Android from API
33; the app targets minSdk 26. ECDSA P-256 is provided by the platform JCE on every
supported API level and by every JVM (CI), needs no bundled dependency, and the manifest
carries an explicit `SignatureMetadata.algorithm` field so a future scheme change (e.g.
Ed25519 once minSdk rises, or a second parallel key) does not break older clients.

## 2. Key lifecycle

### Where the keys live

| Material | Location | Committed? |
|---|---|---|
| Public key (shipped in the app) | `data/update/src/main/assets/update_signing_public_key.pem` | **Yes** — this is the verifier | 
| Dev private key | `local/dev-signing-private-key.pem` (git-ignored; `local/`) | **No** |
| Production private key | CI secret `RELEASE_SIGNING_PRIVATE_KEY` | **No** — never enters the repo, APK or logs |

### Generation (`generateDevSigningKeys`)

The Gradle task (`tools/blocklist/build.gradle.kts`) bootstraps a DEV keypair:

- public key → `data/update/src/main/assets/update_signing_public_key.pem`
- private key → `local/dev-signing-private-key.pem`

**The committed public key is authoritative and is never overwritten** — safe to re-run
(the task only refreshes a missing `local/` private key). This guarantees CI can never
silently diverge the shipped verifier from the signing key. Production signing uses the CI
secret; the `local/` dev key exists so maintainers can run the full
fetch→process→build→verify loop locally end-to-end.

### Devices verify, never sign

`SigningKeyProvider` (`data:update`) reads only the embedded public key asset and builds the
`TrustedKeyRing`. The APK contains no private key by construction; there is nothing on a
device that could be stolen to forge a release.

## 3. Rotation design (`TrustedKeyRing`)

- The ring holds an immutable list of trusted `current` and `next` (rotating) keys.
- `next` may verify a successor key's manifest **before** `current` is retired, so clients
  migrate to the new key without an app update.
- Rotation = ship a release signed by the new key, add `next` to the ring on a future app
  update that also keeps `current` until all clients roll over. `verifyWithKeyId` lets
  tooling and tests address a specific key.
- **Emergency revocation** = ship an app update that removes the compromised key. A key
  that can be revoked with app updates is a feature, not a weakness, because every client
  additionally enforces `SignedReleaseManifest.minimumAppVersion`.

## 4. Enforcement points

- **Client (`BlocklistUpdateEngine`):** signature is checked **first** against the ring
  (`keyRing.verify(envelope)`); nothing (not even the version policy) is consulted before a
  valid signature.
- **Version policy** (`VersionPolicy`): forward-only, replay-safe (tracks
  `maxObservedVersion`), emergency rollbacks only when signed.
- **CI (`blocklist-release.yml`):** the production private key is written into the
  git-ignored `local/` at job start from `secrets.RELEASE_SIGNING_PRIVATE_KEY`; the pipeline
  then runs fetch → process → build → verify (self-check with the public key) → publish to
  `gh-pages` (canary automatic, stable requires an approval-gated environment).

## 5. What security buys

A remote server cannot send a replaceable-by-the-wire update: every manifest the device
applies must carry a valid ECDSA signature from a key the device trusts, and every payload
byte must match the SHA-256 pinned inside that signed manifest. A takeover of the hosting
endpoint (GitHub Pages) is therefore insufficient to install modified rules on devices.