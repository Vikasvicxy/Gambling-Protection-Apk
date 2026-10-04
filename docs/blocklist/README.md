# Blocklist CDN origin

This directory is the **source of truth published to the CDN**. `blocklist.json`
is the full release payload and `manifest.json` is the release descriptor that
pins its SHA-256 and byte length.

`build_preview.py` regenerates both from `DOMAINS` in that script. Output is
canonicalised (sorted keys, no incidental whitespace, domains sorted and
de-duplicated) because the manifest's `sha256` is computed over those exact
bytes — a reformat would change the hash and break every already-installed
client that pinned the old one.

## How this reaches devices

The app does **not** read `manifest.json` as-is. It fetches a *signed envelope*
from any of the configured origins in `UpdateConfig` and refuses to apply
anything that fails the checks in `BlocklistUpdateEngine`:

1. the ECDSA P-256 signature must verify against the public key embedded in the
   APK,
2. the downloaded artifact's SHA-256 must equal the hash pinned in that signed
   manifest, byte for byte,
3. the version must be strictly greater than the installed one (a signed
   emergency rollback is the only way to go backwards).

So the checked-in files here are a **preview/origin**, not something the app can
consume on its own. `.github/workflows/blocklist-release.yml` overwrites both
with pipeline-signed artifacts before publishing to `gh-pages`, and that signed
copy is what ships.

## Why the app does not fetch this directory directly

It is tempting to point the updater straight at
`raw.githubusercontent.com/Vikasvicxy/Gambling-Protection-Apk/master/docs/blocklist/manifest.json`
and skip the signature check. That would be a security regression, and the reason
is worth writing down:

- The file lives on a mutable branch. Anyone who can push to `master`, and anyone
  who compromises a token with write access, could change the block/allow rules
  that every device then enforces.
- `docs/` is served publicly by GitHub Pages, so a single bad merge or leaked
  credential becomes a content injection into a parental-control app.
- A blocklist is a security decision. Silently adding a domain blocks a user's
  access; the app should only ever act on rules a release key signed.

The signed pipeline exists so a compromised CDN can only ever *stop* an update.
It cannot forge one. Keep it.

## Availability is handled by mirrors, not by trust

`UpdateConfig.candidateBaseUrls()` lists several independent origins that publish
byte-identical releases. A dead host costs one extra round trip instead of the
whole update cycle, and because every mirror is behind the same signature check,
an origin we do not control can only cause a failed download.

When every origin fails — 404, timeout, hash mismatch — the engine leaves the
last-known-good database in place and protection carries on unchanged. It does
**not** fall back to `seed_blocklist_v1.json`: that seed is a small v1 list meant
for a fresh install, and replacing a current signed blocklist with it on a
transient network blip would leave the user with far weaker protection than they
had a second earlier. Keeping the good database is both safer and simpler.