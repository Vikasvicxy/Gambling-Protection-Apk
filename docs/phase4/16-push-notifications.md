# Shield — Push Notifications (FCM) Boundary (Phase 4 §14)

## Current state
- **Abstraction:** `NotificationGateway` (core) with two implementations: `LocalNotificationGateway` (Android, graveless notifications) and `NoOpNotificationGateway` (tests-only). **No FCM sender exists** — `NotificationSender` is not implemented in this branch.
- **Server-side:** `notification_tokens` registration endpoint + `POST /api/notifications/token` implemented in the worker; schema ready for a device token column.
- **Local behavior:** notifications fire via local gateway when events occur in-app (permission + events module). Channel names user-readable; notification body never contains URLs or raw content.

## What push WOULD add (documented boundary, not built)
- Background wake-ups for parent-side "child requested approval", "child device offline", etc.
- Reliable delivery requires FCM (server → device) with gated, per-feature opt-in (Play policy: notification permission strictly opt-in from our side).

## Required steps to add (human-in-the-loop, no credentials exist)
1. Create a Firebase project, download `google-services.json` (git-ignored).
2. Add `google-services` Gradle plugin + `firebase-messaging` (or a local notification-conveying worker) — a NEW dependency; not in the reviewed CVE baseline, so decide explicitly.
3. Implement `NotificationSender` on the worker: read token from D1 `notification_tokens`, call `fcm.googleapis.com` v1 with OAuth key (secret in worker).
4. Add `FirebaseMessagingService.onMessageReceived` → route to `NotificationGateway` (local/remote) — never auto-open.
5. Set `RequestNotificationPermission` flow post-onboarding (opt-in, OS-gated).
6. End-to-end test on a device (not available now).

## Honest status
- **IMPLEMENTED:** local gateway + token-registration endpoint plumbing.
- **NOT END-TO-END TESTED:** no device, no Firebase project, no sender.
- **Not in Phase 4 scope** to fabricate alternative push; improvements deferred to a separate "push" phase using this doc as the spec.