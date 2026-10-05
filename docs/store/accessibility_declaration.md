# Play Console — Accessibility API Declaration

**App:** Shield (`dev.gamblock.shield`) · **Version:** 1.4.0 (versionCode 7) · **Owner:** Shield maintainers
**Status:** draft, ready to paste into the Play Console permissions declaration form.
**Companion documents:** [`accessibility_demo_script.md`](accessibility_demo_script.md) (required video), [`google_play_data_safety.md`](google_play_data_safety.md).

---

## 1. Honest summary of the risk, before the answers

Play's Accessibility API policy is not a disclosure problem. It is a **use-case eligibility** problem, and a
well-written declaration form will not fix it. The policy states that Accessibility API access is permitted
for apps whose core functionality depends on it because they are, in substance, an accessibility tool.

Shield is not an accessibility tool. Nobody installs Shield to be helped with a disability or impairment. It is
a behavioural addiction recovery tool, and it uses the Accessibility API for one narrow purpose: to receive a
callback at the instant the user opens the system screen that would uninstall the app.

The realistic outcomes, in order of likelihood:

| Outcome | Likelihood | What it means for the product |
| --- | --- | --- |
| Rejected outright, no appeal | **Moderate** | Uninstall Guard cannot ship on Play. Blocking, filtering and recovery are unaffected. |
| Rejected, appeal accepted | Low–moderate | The form below is the appeal. The `[DeviceAdminFallback]` path in §6 becomes unnecessary. |
| Approved with the declaration below | Low–moderate | Shipping as planned. |

**The product is not hostage to this decision.** Uninstall Guard is an opt-in, off-by-default feature. If Play
rejects it, the app still filters, blocks, records and recovers; it simply loses the anti-uninstall friction.
That is why this is a compliance project and not a product blocker, and why the fallback in §6 is a
*contingency* rather than a dependency.

We are submitting the declaration rather than removing the feature because the feature protects users during
exactly the moments they are least able to act on their own intentions, which is a harm-reduction argument
Play's reviewers can weigh. We accept the outcome either way.

---

## 2. Accessibility API usage declaration

**Is your app an accessibility tool whose core functionality depends on the Accessibility API?**
No.

**What is the core functionality of your app?**
Shield is a behavioural addiction recovery tool for gambling. Its core functionality is a local VPN-based DNS
filter that blocks gambling domains and reports progress toward recovery.

**Why does your app use the Accessibility API?**
To protect the filter from being removed. The service observes one class of event — the system screen that
shows app details and uninstall — and, when it appears, raises a PIN prompt. The user must enter a Guardian PIN
they set themselves before the screen is released. This is an anti-tamper lock, and it exists because the
moments a person opens an uninstall screen are the same moments their resolve is most likely to fail. It is
not a user-facing assistive capability and it does not benefit any disability.

**Which Accessibility API features does your app use?**
`AccessibilityService` with `typeWindowStateChanged` events only, `canRetrieveWindowContent="false"`,
`feedbackGeneric`, `flagDefault`. No accessibility gesture, no `AccessibilityNodeInfo` traversal, no content
retrieval, no text input, no automation, no global actions.

**What data does your app collect, transmit, or share through this API?**
None. The service reads only the foreground window's package name and class name from the event object. It
reads no screen content, no text, no user input, no clipboard, and no node tree. Nothing is collected, stored,
logged, or transmitted. The app has no analytics, no crash reporting and no network call associated with this
service.

**How does the service decide to act?**
It compares the window's package and class name against the names Android and OEM vendors use for app-details
and uninstall screens. On a match, it shows a full-screen prompt asking for the Guardian PIN. It cannot remove
the system Uninstall button, and it cannot proceed without the correct PIN.

**Can the user decline?**
Yes, at three separate points: the in-app prominent disclosure before the system prompt (Android > Settings >
Accessibility appears only if the user continues), the Android system permission screen itself, and the feature
is off by default. A user who knows their Guardian PIN can disable the feature from inside the app at any time,
and the prompt is always dismissible with "Not now".

**Is the feature enabled by default?**
No. It ships off and is never requested unless the user turns it on in Settings > Security.

---

## 3. Prominent disclosure, verbatim

This is the exact string the app displays in `ProminentDisclosureDialog` immediately before any redirect to
Android's accessibility settings, pinned by `AccessibilityDisclosureTextTest`:

> Shield uses the Accessibility Service API to detect when you attempt to uninstall the app or open App
> Settings during a moment of weakness. This allows Shield to lock the screen and ask for your Guardian PIN. No
> other screen content is read, and no data is collected or sent off your device.

The dialog states, immediately below that text, that declining changes nothing and that the guard is not
required for blocking, filtering or recovery. The Android system service's own label and description, shown on
the system consent screen, are:

- **Label:** `Shield uninstall guard`
- **Description:** `Notices when you open the system screen for removing Shield, so it can ask for your
  guardian PIN first. It does not read screen contents, collect anything, or send it anywhere.`

Play requires the disclosure to be prominent, in-app, and shown *before* the system prompt. To that end, the
settings screen cannot reach Android's accessibility settings except through this dialog: the redirect is
private to the view model and is only invoked by the accept handler.

---

## 4. The video you must submit

Play requires a screen recording demonstrating the core feature and the disclosure flow.
[`accessibility_demo_script.md`](accessibility_demo_script.md) contains a shot-by-shot 30-second script with
timings, on-screen captions and the exact tap sequence.

**Recording checklist (all of these are common rejection causes):**

- [ ] One continuous take, no cuts, no editing, no speed changes.
- [ ] Recorded on a physical device or a Play-accepted emulator profile, at native resolution.
- [ ] No narration, music or overlays added after recording.
- [ ] Shows the **in-app** disclosure, **not** only the Android system screen. This is the most common
      omission and it is treated as a missing disclosure.
- [ ] Shows the user explicitly tapping the consent button before the system screen appears.
- [ ] Shows the disclosure being **declined** at least once, demonstrating the decline path works.
- [ ] Shows the service running and the PIN prompt appearing, proving the described behaviour is real.
- [ ] Total length 30–60 seconds.
- [ ] Uploaded to a private YouTube link, unlisted, with commenting disabled, and the link is reachable by
      Google reviewers **without** a login and **without** a region restriction.

---

## 5. Reviewer objection handling, pre-answered

Reviewers will ask these. Answer in the appeal with specifics, not with sentiment.

**"This is not an accessibility tool. Remove the API."**
The API is not used to provide accessibility. It is used to detect an uninstall attempt, which is a
self-protection control for a harm-reduction tool. The capability requested is the narrowest that makes the
detection possible: one event type, no content retrieval, no nodes, no input. The alternative on-device
mechanism is a device administrator, and we have deliberately not shipped it for this purpose (see §6).

**"You could use `DevicePolicyManager` or `UsageStatsManager` instead."**
`UsageStatsManager` cannot identify the uninstall screen, only foreground app transitions, and it requires a
separate, broader permission. `DevicePolicyManager` cannot run a PIN prompt on the settings screen, cannot be
scoped to a single event, and carries its own Play declaration requirement for enterprise use. Neither
expresses "challenge this specific screen".

**"Users cannot disable this."**
They can, three ways, and we would rather say so than have a reviewer discover it. The prompt is always
dismissible. The feature is off by default. The Guardian PIN is set by the user, and anyone who knows it can
turn the feature off from inside the app.

**"Your competitor does this without an accessibility service."**
We cannot comment on other apps' implementations. Ours is a single-event observer with no content access, and
we are submitting it for judgement on that basis.

---

## 6. `[DeviceAdminFallback]` — the contingency if this fails permanently

If Play rejects the accessibility approach and the appeal fails, Shield has a second mechanism already built
and shipped dormant: `ShieldDeviceAdmin`, a `DeviceAdminReceiver` declaring `force-lock`.

**Mechanism.** A device administrator holding at least one active policy cannot be uninstalled by the ordinary
Settings path. Android blocks the removal and requires the administrator to be deactivated first.

**Honest limits, which belong in the declaration if we ever switch:**

- It stops the impulsive path, which is the entire premise of the guard. It does not stop a determined user:
  `adb shell dpm device-admin remove`, a factory reset, or deactivation with the device PIN all defeat it.
- It cannot show the Guardian PIN prompt, because it has no callback when an uninstall screen opens. It is a
  stiffer wall with no courtesy dialog behind it — worse UX, better wall.
- It is **not** a compliance escape route. Play governs the Device Administrator API separately, requiring its
  own declaration and reserving it for enterprise and remote-management use. Switching would trade one review
  risk for another, and we would have to argue the case again.

**Current state, deliberately:** the receiver is registered, declares only `force-lock`, requests no wipe or
password policy, is inactive out of the box, and has no UI entry point. Nothing in the app requests
administrator rights on the user's behalf. `ShieldDeviceAdminTest` fails the build if that changes.

**If we ever activate it, the declaration must additionally state:** that the app is requesting device
administrator rights solely to prevent its own removal; that it declares no wipe, password-reset, camera or
location policy; and that the rights are revocable by the user at any time from Android settings.

---

## 7. Recommendation

Submit the declaration in §2 and the video in §4, and expect either approval or a rejection that can be
appealed. Do not remove the feature pre-emptively: it is off by default, harms nobody who declines, and
blocking, filtering and recovery do not depend on it. If the appeal fails, the correct product decision is to
ship 1.4.0 without the Uninstall Guard, keep `ShieldDeviceAdmin` dormant, and re-evaluate the anti-tamper
mechanism against Play's enterprise-admin policy as a separate decision with its own submission.
