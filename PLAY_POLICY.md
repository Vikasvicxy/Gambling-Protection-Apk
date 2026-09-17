# Shield — Google Play Store Policy Statement

**App:** Shield (gambling-blocking protection for Android)
**Version:** Phase 3 (accountability + Parent/Guardian mode)
**Date:** 2026-09-17

## Purpose
Shield is a privacy-respecting gambling-and-gambling-site blocker. It uses a local
on-device blocklist plus optional DNS/VPN tunnelling to block access to gambling content,
and offers **accountability partner** and **Parent / Guardian** supervision built strictly as
**genuine parental controls / accountable supervision** — never as covert surveillance.

## What we DO
- Block gambling domains locally (per-app VPN tunnel + DNS policy) on the user's own device.
- (`Parent / Guardian mode`) Let a **verified parent/guardian** of a supervised **child device**
  see **minimal aggregate protection stats only** (e.g. "protected active", "blocked attempts",
  "protection not confirmed") for a device they genuinely supervise — **never browsing history,
  messages, contacts, passwords, or location.**
- (`Accountability mode`) Let an adult opt in to sharing the same *minimal aggregate* protection
  events with a trusted partner. No full history, no content, default-off exact-domain attributes.
- Offer device-integrity verdicts, tamper-evidence, and a serverless out-of-band backend used
  only for pairing/heartbeat/approval relay — **browsing traffic is never proxied through it.**

## What we NEVER DO
- No hidden/background collection of browsing content or keystrokes.
- No use of Accessibility to scrape the user interface.
- No covert GPS/location tracking.
- No behavior beyond "protect a device you own or supervise" personal/device usage.

## Sensitive-permission compliance (current and future)
Today the app does **not** request sensitive data permissions (SMS, call log, location, contacts).
Before any future change that adds a sensitive permission or changes data handling, this policy
will be re-reviewed against current Play data-safety and Families policies, and
`docs/phase3/00-phase3-report.md` residuals will be re-audited.

## Enforcement notes
- Child-device supervision requires genuine consent (the parent's device accepts the child's
  pairing invitation; the child device runs Shield under their own account).
- Live on-device behavior is **PARTIALLY TESTED** (see Phase 3 report). No production rollout
  of Parent mode occurs until on-device validation completes on real hardware.