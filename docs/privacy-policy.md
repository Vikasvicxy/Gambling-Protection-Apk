# Shield — Privacy Policy

**Effective date:** 26 September 2026
**Applies to:** Shield 1.0.0 and later (`dev.gamblock.shield`)
**Published at:** https://vikasvicxy.github.io/Gambling-Protection-Apk/

> This is the source of truth for Shield's privacy policy. The rendered version
> at the URL above is what is submitted to Google Play.
>
> It supersedes `docs/phase4/05-privacy-policy-draft.md`, which was written
> before the Data Safety review and contains two claims that are no longer
> accurate: that Shield uses "default OS crash reporting", and that it collects
> data as a default behaviour rather than an opt-in one. Do not publish that
> draft.

---

## Read this first

Shield is **not** a completely offline app, and this policy will not pretend
otherwise. Two things cross the network:

1. **Blocklist updates.** Shield downloads a signed, static list of gambling
   domains over HTTPS from `gamblock.github.io`. That request contains nothing
   about you. A local seed list ships inside the APK, so blocking works with no
   network at all.
2. **Accountability pairing — only if the user turns it on.** It is off by
   default. When a user pairs with a parent, partner or sponsor, the app sends a
   pseudonymous device ID, the app version, whether protection is on, and
   blocked-attempt events. Nothing else.

Everything else — streak, journal, cravings, milestones, Fortress schedules,
Guardian PIN, encrypted backups — never leaves the device.

---

## 1. What Shield never does

- No advertising SDKs, and no ads in any form.
- No analytics, telemetry or usage-measurement SDK.
- No third-party crash reporter. Crashes are not sent to us.
- No advertising ID, no cross-app identifier, no device fingerprinting.
- No data broker, no attribution SDK, no session recording.
- No background upload of journal, notes or streak.
- No selling or sharing of data for money.

This is verifiable rather than a promise: every dependency is Jetpack, AndroidX,
kotlinx, or first-party. There is no Firebase, Crashlytics, ad network or
attribution library in the dependency graph, and the source is public.

## 2. How blocking works, and why browsing stays on the phone

Shield uses Android's `VpnService` in a local loopback configuration:

- Android routes traffic through one network interface per device. Shield becomes
  that interface, **on this device only**.
- When another app resolves a domain, the DNS query reaches Shield **on the
  device**. Shield answers from its own list, or refuses it.
- The web traffic itself is **not** sent to us and not inspected. We handle the
  name lookup; the connection goes from the app to the destination directly.

No browsing history is assembled, stored or transmitted. There is no server that
could be asked what you visited. **No DNS query, hostname, page visited, or
traffic payload is ever uploaded.**

## 3. What is stored on the device

| Data | Where | Why |
| --- | --- | --- |
| Clean streak, start date, money-saved estimate | On-device DB / preferences | Show progress. Self-declared spend, never transmitted. |
| Craving journal and triggers | On-device Room/SQLite DB | Spot patterns. Never uploaded. |
| Custom exceptions and blocklist | On-device DB | Your own allow/deny decisions. Local only. |
| Fortress window schedules | On-device DB | Lock down during your high-risk hours. |
| Guardian PIN | On-device, salted PBKDF2 hash | Gate your own settings changes. **Never in a backup.** |
| Encrypted backup file | Only where you choose, via the system file picker | AES-256-GCM sealed. Key is your passphrase, never stored. |

Backups are encrypted **before** touching storage, with `AES-256-GCM`, key
derived by `PBKDF2-HMAC-SHA256` at 120,000 iterations with a random per-file
salt. Every export uses a fresh nonce; any modification makes the file fail to
decrypt rather than decrypt to something wrong. **If you lose the passphrase, the
file cannot be opened by anyone, including us.** No recovery key, no escrow, no
back door.

Shield never picks a file path. It can only read or write the single file the
user selects through Android's system file picker, and that access is revocable
from system settings.

## 4. Data collected, only with consent

Accountability pairing, opt-in, off by default:

| Data | Purpose | Sent to |
| --- | --- | --- |
| Pseudonymous device ID | Link a device to the pairing you created | Our server, HTTPS |
| App version, database version | Diagnose protection health | Our server |
| Protection on/off, health status | Let a support person see you are protected | Our server |
| Blocked-attempt events (type, severity, time, count) | Accountability | Our server |

**One setting changes the answer.** `AccountabilityEvent.exactDomain` is
documented in the source as *"Not sent by default."* When a user enables
exact-domain sharing, the specific blocked site is sent to the paired support
person. At that point blocked domain names become shared browsing data, and this
policy says so plainly.

None of this is used for advertising, and none of it is tracking in Google Play's
sense: it is never shared with third parties for ad or measurement purposes, and
is not linked across apps.

## 5. Who can see your data

- **Nobody else.** Recovery data is not visible to us, to other Shield users, or
  to anyone with access to your unlocked phone.
- **Your paired support person** sees only what you chose to share, and only while
  the pairing exists.
- **We do not sell, rent or trade data.** There are no third-party analytics or
  advertising partners.
- **Legal process.** We do not hold your identity, so we cannot voluntarily
  provide information about a specific person.

## 6. Retention

- On-device data stays until you delete it, clear it, or uninstall.
- Accountability device and pairing records are kept until you unpair or request
  deletion.
- Session tokens and administrative sessions expire on a set lifetime.
- Nothing is retained for advertising or analytics, because nothing is collected
  for those purposes.

## 7. Your choices

- Turn the VPN off, or stop Shield, at any time.
- Unpair to remove the pairing and its stored token immediately.
- Export and delete a backup whenever you like.
- Ask us to delete accountability data at any time.
- Uninstall; on-device data goes with the app.

## 8. Security

- All network traffic uses HTTPS; cleartext is not permitted.
- Blocklist updates are cryptographically signed, so a tampered list is rejected.
- Backups use AES-256-GCM with a per-file salt and a fresh nonce per export.
- The Guardian PIN is stored as a salted, iterated PBKDF2 hash, never in plain
  text, and never in a backup.

## 9. Children's privacy

Shield is a recovery tool for adults 18+. It is not directed at children and is
not marketed to them. Accountability is designed for an adult in recovery
involving a trusted adult; it is not a parental-control product. We do not
knowingly collect personal information from children under 13. Contact us and we
will delete it.

## 10. Your rights

Depending on your location you may have rights to access, correct, export or
delete your data, or object to processing. Because almost all of your data never
leaves your device, you can exercise most of these in the app without contacting
us. For anything on our servers, email us.

## 11. Changes to this policy

Material changes update the effective date above and are noted in the app's
release notes on Google Play. Continuing to use Shield after a change means the
updated policy applies.

## 12. Contact

- **Privacy and data deletion:** privacy@gamblock.dev
- **Security reports:** security@gamblock.dev
- **Source and issues:** https://github.com/Vikasvicxy/Gambling-Protection-Apk

> Replace the addresses above with mailboxes you actually monitor before
> submitting to Google Play. An unmonitored contact address is a common reason
> for a Data Safety form to be rejected.

## 13. On the "no tracking" claim

Plenty of apps say this and mean only "we do not sell your data". The narrower,
more useful version: **if you never open Accountability, Shield makes no request
that contains anything about you, ever.** It fetches a static, signed list of
domains, and that is the entire conversation. You can read the source and check.
