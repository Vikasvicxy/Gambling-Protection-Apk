# Play Console — Accessibility API Demo Video Script

**App:** Shield 1.4.0 (`dev.gamblock.shield`) · **Length:** 30 seconds · **Takes:** one continuous,
unedited take
**Companion document:** [`accessibility_declaration.md`](accessibility_declaration.md)

---

## Why the format is so constrained

Play's Accessibility API review is a video review. The most common rejection reason for this policy is not a
refusal of the use case — it is a **missing or incomplete video**: a recording that shows the Android system
permission screen but never shows the app's own prominent disclosure first, or that shows the feature working
but never shows the user declining it.

So the script below is built to make the three things a reviewer is looking for unmissable:

1. the app's disclosure, in the app, before the system prompt;
2. an explicit, visible user action to continue;
3. the feature actually working, plus a demonstrated decline.

## Recording rules

- **One continuous take.** No cuts, no trimming, no speed ramp, no zoom added in post. If a take goes wrong,
  discard it and restart the whole thing; a stitched recording is grounds for rejection on its own.
- **No narration and no post-production captions.** Use the device's own on-screen text. Do not add voiceover,
  music, arrows, or text overlays in an editor — they make the reviewer question whether the UI is real.
- **Native resolution, no scaling.** Portrait. Do not record a side-by-side or picture-in-picture layout.
- **No sensitive data on screen.** Use a test device with a clean home screen. A real recovery journal, real
  money-saved figures, or a real Guardian PIN visible in the recording is a privacy incident. Use a throwaway
  PIN of `1357` and a test profile.
- **Upload as unlisted YouTube**, commenting disabled, and verify the link opens **without a login** and
  **outside your home region** before you paste it into the form. A reviewer who hits a login wall or a geo
  block sees a broken submission, not a locked video.

## Prerequisites before you press record

- [ ] Uninstall Guard is **off**. Open Settings > Security, confirm the guard toggle is off, and confirm
      Android > Settings > Accessibility shows "Shield uninstall guard" **not** listed as enabled.
- [ ] A Guardian PIN is configured (the test PIN `1357`).
- [ ] App is at version 1.4.0 or later. The disclosure dialog does not exist before this.
- [ ] Screen recording is enabled and the notification shade is not showing.
- [ ] Device is on a clean home screen with no notifications.

---

## The script

Total runtime **30 seconds**, one take. Timestamps are pacing targets, not edit points.

At 30 seconds there is no room for everything worth showing, so the beats are ranked by what a reviewer
rejects on. The disclosure, the explicit in-app consent, the system screen and the working feature are all
non-negotiable — drop those four and the take fails. The dashboard context beat and the deactivation
close are the two that give way; both are noted below with how to get them back if your device is faster
than expected.

### 0:00–0:03 — Open the feature

**Action.** Launch Shield, tap **Settings**, open **"Device & Uninstall Protection"**. Stop on the
**"Uninstall Guard"** row with its toggle visibly **off**.

**On screen.** Settings list → category expanded → "Uninstall Guard", off.

> Three seconds, no lingering. The toggle being visibly off is the evidence for "opt-in", and it needs no
> narration. A reviewer who needs context on what kind of app this is will get it from the dashboard they
> just left; at 30 seconds, protecting the four mandatory beats matters more than that opening shot.

### 0:03–0:10 — The prominent disclosure (this is the shot that matters)

**Action.** Tap **"Turn on the uninstall guard"**.

**On screen.** `ProminentDisclosureDialog`, titled *"Before Android asks for Accessibility access"*, showing
the full disclosure text:

> Shield uses the Accessibility Service API to detect when you attempt to uninstall the app or open App
> Settings during a moment of weakness. This allows Shield to lock the screen and ask for your Guardian PIN.
> No other screen content is read, and no data is collected or sent off your device.

Immediately below it, the decline reassurance: *"Declining changes nothing…"*

**Hold this shot for a full three seconds.** This is the single frame a reviewer screenshots into their
decision. Do not tap through immediately. Seven seconds for the whole beat, three of them static.

### 0:10–0:15 — Decline, then consent

**Action, in this order, no pause to think:**

1. Tap **"Not now"**. The dialog dismisses and the app stays put.
2. Tap **"Turn on the uninstall guard"** again. The same disclosure reappears.
3. Tap **"I Understand, Continue to Android Settings"**.

**On screen.** Dialog dismissed, then re-shown, then dismissal into the Android settings screen.

> This beat is what separates a real consent flow from a "disclose once then nag" pattern. It costs five
> seconds and it is the cheapest possible insurance: it shows the decline path works, that the disclosure
> is re-shown rather than cached, and that reaching the system screen required a deliberate tap. If you
> are ever forced to cut something else, cut the dashboard context first, not this.

### 0:15–0:21 — The system consent screen

**Action.** On the Android accessibility screen, tap **"Shield uninstall guard"** and enable it. Accept the
system warning.

**On screen.** The Android "Allow Shield uninstall guard to observe your actions and perform actions on your
behalf" dialog, then the service listed as on.

> The system label and description must both be legible in frame. They are the second thing a reviewer reads,
> and they are the strings the declaration quotes.

### 0:21–0:30 — The feature working

**Action.** Navigate to Android **Settings → Apps → see all apps → Shield**. The PIN prompt should appear
over the app-details screen. Dismiss it with **"Not now"**.

**On screen.** The `UninstallGuardChallenge` overlay — *"Hold on a moment"*, four-digit entry, **Confirm**,
**"Not now"** — then dismissed by the user.

> Dismissing is not optional in spirit. A reviewer wants to know the challenge is escapable, and a
> determined user can always get past it. Showing the escape is the honest part of the demonstration and it
> strengthens rather than weakens the case.

---

## What was cut, and how to get it back

Nothing below is required for the four mandatory beats. Add these only if your take runs short.

- **Dashboard context (≈2s).** Launch Shield and pause on the dashboard before opening Settings. It
  establishes that this is a recovery app that happens to want an accessibility service, rather than a
  settings-modification tool. Without it the first frame is a settings screen, which is the least charitable
  opening available.
- **Deactivation close (≈4s).** Return to Shield, open Settings → Security, turn the Uninstall Guard off,
  confirm Android accessibility settings no longer list it as enabled. This answers the most common reviewer
  concern about accessibility-based anti-tamper — that it is irreversible. A 34-second video is fine; a
  stitched one is not, so this must stay in the same continuous take.

If the take lands at 34–36 seconds because navigation was slow, that is fine. Play has no 30-second
requirement of its own; the ceiling here is the reviewer's patience and the upload form, not a rule.

## Shot list for self-review

Before uploading, watch the take back and confirm all six. If any is missing, re-record.

- [ ] The app's own disclosure appears **before** any Android system screen (0:03).
- [ ] The disclosure is fully legible, not cut off or scrolled, and held long enough to read.
- [ ] The decline path is demonstrated, then the disclosure is re-shown rather than cached (0:10).
- [ ] The user visibly taps an in-app consent control to reach Android Settings (0:10).
- [ ] The Android system screen appears, with label and description readable (0:15).
- [ ] The feature demonstrably works — the PIN prompt appears over the app-details screen — and the user
      dismisses it (0:21).

## Upload

- Title: `Shield 1.4.0 — Accessibility API disclosure and uninstall guard`
- Visibility: **Unlisted**, comments and reactions off.
- Verify from a logged-out browser, ideally on a phone, and from a different network if you can.
- Paste the link into the Play Console declaration form next to the §2 answers in
  [`accessibility_declaration.md`](accessibility_declaration.md).
