# Shield — Store Listing, Closed Testing & Staged Rollout (Phase 4 §24–§26)

## 1. Store assets (specs + draft copy)
### App name
- Candidates: **"Shield — Focused DNS Blocker"**, **"Shield Blocker"**, **"Shield: Block distraction"** — pick one for listing; short name ≤ 30 chars.

### Short description (≤ 80 chars)
> DNS-level blocker for distraction-free focus. Block sites you choose, right on your device.

### Full description (draft)
> Shield runs a local DNS VPN on your Android device to block the sites, apps, and domains you choose — no browsing history is read or sent anywhere. Add rules yourself or use the included blocklist.
> • DNS-only VPN: blocks at resolution; no content leaves the device.
> • Works across browsers and apps.
> • Signed, tamper-proof updates.
> • Optional pairing + guardian oversight (aggregate health only, never your browsing).
> • No ads, no analytics SDK, no account required to use blocking.
> Note: DoH/DoT/QUIC and IPv6 DNS can bypass DNS-only blocking (see «Known limitations» link/privacy URL).

### Feature graphic
- 1024×500 px, no art containing app UI overload; phishing-free; text limited to the product name + tagline. No Play-Playstore-vendor badges.

### Phone screenshots (min 4)
- Screenshots must come from a **real device** (device pending) — do not substitute wireframes for production-ready screenshots.

### App icon
- 512×512, safe-zone compressed, no transparency issues.

## 2. Closed-testing package
- APK: `app/build/outputs/apk/release/app-release-unsigned.apk` (2.1 MB, R8).
- Signing: maintainer must create `local/keystore.jks` + `keystore.properties` (git-ignored); build `:app:assembleRelease` again to produce a signed APK. Prefer Play App Signing.
- Release notes (honest):
  - "DNS-level blocking; update checks over HTTPS with signature verification."
  - "Not a firewall; DNS-only; DoH/DoT/QUIC and IPv6 can bypass."
  - "Requires Android 8.0+."
- Test instructions: point to `01-real-device-test-matrix.md`, feedback matrix, and OEM guidance.
- Known limitations list (`10-known-limitations.md`).

## 3. Staged rollout plan
1. **Internal testing** (Play Console): 10–30 testers, incl. ≥1 each of the closed-test device spread (Samsung, Pixel/stock, Xiaomi/OPPO, Android 13+).
2. **Closed testing:** invite current cohere batch. Gate on: no fatal crash (ANR-free rows C1–C3), no VPN kill-loop complaints.
3. **Production 5–10 %:** publish versionCode 2 slowly. Rollback triggers (any → stop and revert):
   - Crash/ANR tidal wave (Play Console + vitals)
   - VPN instability: devices dropping VPN / forced-close loops
   - Update-signature verification failure at scale (fail-closed, no partial apply)
   - Connectivity failure that leaks (users unable to browse)
   - Blocklist false-positive spike report
   - Battery-drain complaints (2× baseline)
4. **Wider rollout:** increase to 50 % then 100 % only after a no-regression window (14 days suggested).

## 4. Rollback procedure (see also `11-release-rollback-procedure.md`)
- Reduce rollout to 0 % immediately on any of the triggers; do NOT delete users' local rule sets; provide in-app retrieval of last-known-good update on next load (signed); release a hotfix as versionCode 3 if the failure is in app code.