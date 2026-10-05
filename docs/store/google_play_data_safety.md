# Google Play Data Safety — Shield (`dev.gamblock.shield`) v1.4.0

Verified against the source in this repository on 2026-10-05 (v1.4.0). Not a legal
opinion, and not a substitute for your own review. If any answer here does not
match what the shipped binary does, the Data Safety form is wrong, not the app.

---

## CHANGES SINCE THE LAST REVIEW

* v1.4.0 added an **Accessibility API disclosure** and a **battery-optimisation
  justification** (section 8). Both are required by Play for this app's permission
  set and neither was documented before.
* v1.4.0 added a **boot and app-update broadcast receiver** that posts a local
  notification if the uninstall-guard accessibility service is found unbound. It
  reads the local settings flag and system Accessibility settings and transmits
  nothing.
* v1.4.0 added a **DNS-over-HTTPS** path (Quad9, POST with
  `application/dns-message`). It changes *where* the lookup goes, not what is
  collected: the hostname goes to Quad9 instead of the carrier. See section 8.
* The Accountability answers below are unchanged from v1.1.0 and remain the only
  user data that leaves the device.

---

## READ THIS BEFORE YOU FILL THE FORM

**Shield has no third-party SDKs.** The entire dependency graph is Jetpack,
AndroidX, kotlinx, and first-party modules. There is no Firebase, no Crashlytics,
no ad SDK, no analytics, no attribution SDK, no fingerprinting library. That part
is unambiguous and it is the core of the product claim.

**Shield is not strictly offline, and the answers below reflect that.** Two things
cross the network, and the form must say so:

1. **Blocklist updates.** The app fetches a signed, static blocklist over HTTPS
   from `gamblock.github.io`. The request contains no user data. A local seed
   list ships in the APK, so blocking works with no network at all.
2. **Accountability pairing (opt-in, off by default).** When a user pairs with a
   parent, partner or sponsor, the app registers a pseudonymous device ID and
   relays protection health and blocked-attempt events to
   `api.shield.gamblock.dev`. This is the only user data that ever leaves the
   device.

Everything else — streak, journal, cravings, milestones, money-saved figures,
fortress schedules, Guardian PIN, AES-256 backups — is created, stored and read
on the device only, in the private Room/SQLite database or in the encrypted
DataStore.

### The one setting that changes these answers

`AccountabilityEvent.exactDomain` is a nullable field documented in the code as
"Set when the sender opts into exact-domain sharing. **Not sent by default**."

* Exact-domain sharing **off** (the default): blocked domain names are never
  transmitted. Web browsing is **not** collected.
* Exact-domain sharing **on**: the specific blocked domain is transmitted to the
  paired support person. At that point web browsing history **is** collected and
  shared with a third party.

If you or a user turns that on, "Web browsing" and "App activity" in the form
must change to collected-and-shared. Do not submit "not collected" while that
setting is reachable and enabled.

---

## 1. Does your app collect or share any of the required user data types?

**Yes — but only for the opt-in Accountability feature, which is disabled by
default.**

If you intend to ship with Accountability permanently off, or behind a flag that
is off in the submitted build, you may answer "No" *and* you must then remove or
permanently disable the feature, because it ships in the APK. The honest answer
for the build as it stands today is **Yes**.

---

## 2. Data types

| Play category | Collected? | Shared with third parties? | What exactly, and when |
| --- | --- | --- | --- |
| **Location** | No | No | Never read. No GPS, no network, no cell-tower. |
| **Personal info** (name, email, phone, address, IDs) | No | No | No account, no sign-up, no email field. The pseudonymous device ID is *not* a personal identifier: it is generated on device and not linked to a real-world identity by us. |
| **Financial info** | No | No | The weekly-spend figure is a recovery estimate you type in yourself, stored locally. No payment instrument is ever read or collected. |
| **Health and fitness** | **Yes, opt-in only** | No | Relayed only with Accountability on: protection state, health status, and recovery events such as "gambling attempt blocked", with severity and timestamp. Stays on device otherwise. |
| **Messages** | No | No | — |
| **Photos and videos** | No | No | — |
| **Audio files** | No | No | — |
| **Files and docs** | No | No | The app reads and writes only the single backup file the user explicitly picks via the system file picker. Its contents are the encrypted backup itself. |
| **Calendar** | No | No | — |
| **Contacts** | No | No | — |
| **App activity** | **Yes, opt-in only** | No | In-app events: blocked-attempt type, severity, timestamp, grouped count. |
| **Web browsing** | **No by default** | No | DNS queries are resolved on device. No history is built or transmitted. Becomes **Yes, shared** only if the user enables exact-domain sharing. See the warning above. |
| **App info and performance** | **Yes, opt-in only** | No | App version, database version, protection state, health status, last check-in timestamp. |
| **Device or other IDs** | **Yes, opt-in only** | No | One pseudonymous install ID generated on device, used to link a device to its pairing. Resettable by unpairing. |

**Not collected, and worth stating plainly:** no advertising ID, no
cross-app identifier, no device fingerprint, no behavioural profile for ad
targeting, no precise or coarse location, no contacts, no media, no keystores.

**The app risk scan (v1.1.0) does not change any answer above.** It reads
installed app labels and package names on device, matches them against a static
table shipped inside the APK, shows the result, and discards the list. Nothing
is stored, nothing is transmitted, and there is no server to look anything up
against. Play's "App info" covers data that leaves the device or is retained;
this neither does. It is worth knowing this is in the codebase, because a
reviewer reading `GamblingAppScanner` will find package-name handling and should
be able to confirm from the source that it is a local, read-only match.

---

## 3. Purposes

| Data | Purpose | Is it used for tracking or ads? |
| --- | --- | --- |
| Device ID, app version, protection/health state, blocked-attempt events | **App functionality** — the Accountability feature the user opted into, so a paired support person can see that protection is still on | **No** |
| Exact domain (only if the user opts in) | App functionality — telling a support person *what* was blocked, at the user's request | **No** |

* **Is any of this data used for tracking?** No. No data is used for tracking in
  the Play definition (it is never shared with a third party for ad or
  measurement purposes, and is not linked across apps).
* **Is any of this data used for ads or marketing?** No.
* **Is any of this data sold?** No.

---

## 4. Security and retention

* **Encrypted in transit?** Yes. The blocklist fetch and the accountability relay
  both use HTTPS/TLS. No cleartext traffic is permitted by the app's network
  security config.
* **Encrypted at rest?** Recovery backups are sealed with AES-256-GCM using a
  PBKDF2-HMAC-SHA256 key derived at 120,000 iterations. The on-device database is
  protected by Android's application sandbox and file-based encryption.
* **Can users request deletion?** **Yes.** Two routes, both real:
  1. Unpair in the app, which removes the pairing and the stored token locally.
  2. Email the support address below and ask for deletion of the device
     registration row. Backed by an admin purge on request.
* **Do you provide a privacy policy?** Yes, publicly hosted:
  `https://vikasvicxy.github.io/Gambling-Protection-Apk/` (source in `docs/`).
* **Data retention** — device registration and pairing rows are held until you
  unpair or request deletion. Session tokens are TTL'd. Admin sessions expire.
  Nothing is retained for advertising or analytics because nothing is collected
  for those purposes.

---

## 5. Target audience and content

* **Is your app directed at children?** No. Shield is a recovery tool for adults
  (18+) who want to stop gambling. It is not child-directed and is not marketed
  to children.
* **Does your app allow a parent or guardian to manage a child's device?** There
  is a parent/support pairing feature, but the intended user is an adult in
  recovery choosing to involve a trusted adult. It is not a parental-control
  product and collects no child data.
* **Is your app a "news" app?** No.
* **Is your app a "social networking" app?** No. There is no public feed, no
  messaging between users, and no user-generated content visible to others.
* **Does your app allow users to interact or exchange content with one another?**
  No. The only interaction is a private, pairwise pairing initiated by the user
  with a person they choose.
* **Does your app share the user's current location?** No.
* **Is your app a financial app?** No — it does not link a bank account, hold
  funds, or move money. The weekly-spend field is a self-declared estimate used
  to calculate money saved, and is never transmitted.

---

## 6. Government or health authority access

None requested. Shield does not hold your identity, so it cannot voluntarily
provide information about a specific person. Respond to valid legal process
through the support address below.

---

## 8. Special API and sensitive-permission disclosures

These are Play's separate review questions, not Data Safety questions. They are
recorded here because both were undocumented before v1.4.0 and both are the kind of
thing a reviewer rejects the build over.

### Accessibility API (`AccessibilityService`)

**What it does.** `ShieldAccessibilityGuard` observes *window-change* events and
compares the activity class name against a list meaning "app details or uninstall
screen". On a match it shows an optional guardian-PIN prompt over that screen.

**What it does not do, explicitly.**

* It does **not** read screen content. It requests no `canRetrieveWindowContent`
  capability and inspects no node text. It reads the event's package name and class
  name only.
* It does **not** log, store, or transmit anything it observes.
* It does **not** act on other apps, and it does not automate anything. It never
  clicks, scrolls, or dismisses anything on the user's behalf.
* It is **off by default** (`uninstallGuardEnabled = false`) and requires the user to
  enable it in system Accessibility settings.
* It **only raises a prompt.** It cannot block an uninstall: Shield does not own the
  system window. The prompt is always dismissible, including without the PIN.

**Where the user sees it.** Settings → Security, next to the system prompt granting
the service. `docs/store/full_description.txt` and the in-app screen both state the
above.

**Play risk, stated honestly.** Play permits the Accessibility API for accessibility
tools. Shield is an opt-in guardian feature for a user in recovery, not an
accessibility tool, so this **is a plausible rejection reason** and should be
declared as an accessibility tool in the declaration form with the justification
above. If it is rejected, the correct response is to remove the service, not to
misdescribe it.

### Battery optimisation exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)

**Why it is core functionality.** A VPN is killed by the OS when the device is idle
or the app is swiped away. For Shield, that failure mode is indistinguishable from
success to the user — the protection indicator is gone, so is the protection. The
health engine reports the VPN component as **degraded** when the app is not exempt,
and Diagnostics offers a one-tap fix.

**How it is requested.** Through `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
with a `package:` URI (`DiagnosticsViewModel.fixIntent`), which is the only form Play
permits. On OEM builds Shield first tries the vendor's own autostart screen and only
falls back to the platform dialog.

**Honest note.** A persistent VPN is exempt from battery optimisation on Android for
most OEMs anyway, so this request is a belt-and-braces measure. It is safe to describe
as such in the declaration form.

### DNS-over-HTTPS (no new permission, disclosed for completeness)

When the user enables DoH, DNS queries are sent to Quad9 as RFC 8484 POSTs over TLS.
This does not add a permission and does not create new *collection* — the hostname
already had to be looked up to be blocked. What changes is the recipient: the lookup
goes to Quad9 rather than the carrier, which is the point of the feature.

It fails closed. If Quad9 is unreachable Shield reports the failure; it does **not**
fall back to a plaintext lookup, because a silent downgrade would hand the name to the
carrier while the UI claimed protection.

---

## 9. Contact

* **Support / privacy / deletion / security requests:**
  https://github.com/Vikasvicxy/Gambling-Protection-Apk/issues
* **Repository:** https://github.com/Vikasvicxy/Gambling-Protection-Apk

> The contact channel is the public GitHub issue tracker; there is no separate
> monitored mailbox. An issue URL satisfies the Data Safety contact field. If a
> reviewer insists on an email address, add a real monitored mailbox here and
> update `docs/privacy-policy.md` and `docs/index.html` in the same change, so
> the three never disagree.
>
> The tracker is public. Askers should open an issue stating only that a
> request exists, then move personal data to a private channel.
