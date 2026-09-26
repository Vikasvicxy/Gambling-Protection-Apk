# Shield — Privacy Policy Draft (Phase 4 §17)

> **SUPERSEDED — DO NOT PUBLISH.**
> The live, reviewed policy is `docs/privacy-policy.md`, rendered at
> <https://vikasvicxy.github.io/Gambling-Protection-Apk/>. That is the version
> submitted to Google Play.
>
> This draft is kept for history only, and it contains two claims that are now
> known to be wrong:
>
> 1. "Crash/log data: default OS crash reporting." Shield ships no crash
>    reporter at all, and crashes are not transmitted.
> 2. Device/pairing data is described as if collection were normal behaviour.
>    It is opt-in and off by default.
>
> It also omits the blocklist update fetch, which does contact
> `gamblock.github.io` over HTTPS. A policy that does not mention it is
> incomplete.

> Status: SUPERSEDED by `docs/privacy-policy.md`.

**Effective date:** 2026-09-17 (update on release).

This privacy policy describes how the Shield app ("Shield") handles your data.

## 1. Data we collect and why
- **Shield runs locally.** Blocking, blocking decisions, allowed-site forwarding, and the blocklist all happen on your device (`INTERNET`, `ACCESS_NETWORK_STATE` for connectivity; no browsing history is collected or transmitted).
- **Device identity (opt-in pairing/account):** when you opt into pairing/accont features, Shield registers a device ID with our backend and transmits:
  - device ID and pairing identifiers,
  - aggregate event/heartbeat health states (e.g., "protection on"),
  - approval decisions,
  - optionally a notification-registration token (see §16-push).
  We never transmit URLs, browsing history, or content.
- **Crash/log data:** default OS crash reporting. No telemetry of browsing content is collected by Shield.

## 2. How we share data
- Only as needed to operate pairing/account endpoints (our serverless backend on Cloudflare). No third-party advertising, no analytics SDK, no data brokers.
- Note: pairing endpoints authenticate via token; data is transmitted over HTTPS.

## 3. Data retention
- Device registration/pairing rows are held until you unpair/delete your pairing (which removes the pairing; device registration rows are cleared by admin purge or on request).
- Backend is a denial-of-service-resistant stateless worker; session tokens are TTL'd; admin sessions expire.

## 4. Children's privacy
- Shield can be used by a parent to protect a child's device. If a child uses Shield on their own, the device/account data above applies; Shield does not collect age, location, or personal information beyond device IDs. If you believe a child under 13 (or applicable local age) provided data, contact the support email to request deletion.

## 5. Security
- HTTPS-only; signed blocklist updates (Ed25519-style via our signing key); no cleartext traffic permitted by network security config; credentials/keys never stored on disk in the app; sessions expire.

## 6. Your choices
- Turn the VPN off, unpair the pairing, disable notifications, or uninstall.
- Contact support (support email) for deletion or data questions.

## 7. Contact
- Support email (maintainer) for Shield reporting concerns.

---
Maintainer must fill: support email, privacy policy hosting URL, and (if child-directed) the COPPA/child-data statement.