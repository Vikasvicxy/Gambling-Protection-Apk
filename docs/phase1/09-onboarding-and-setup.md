# Onboarding & Setup Flow — Phase 1

## Navigation architecture

Single-Activity Compose app (`MainActivity`, `app`), no external navigation library —
`NavHost` state is data-driven from Preferences and the Hilt-aware start destination
(`MainActivity` resolves `onboarding` vs `dashboard` from onboarding state).

`feature:onboarding`
- Welcome, digital-wellbeing framing, and three `onboarding` states:
  `NotStarted -> Started -> Completed` (DataStore `PreferencesKeys`).
- First-run bootstrapping reads `TimingAnchorRepository.ensureFirstRunEpochMs()`.

`feature:setup` — the post-onboarding consent + activation flow:
1. Education/EULA consent step.
2. **VPN permission handshake**: `VpnService.prepare(context)` returns null when the app is
   already VPN-confident; otherwise launcher pending-intent to the system consent sheet —
   the only OS-required permission the app asks for.
3. Schedule setup (`startTime`/`endTime`, default 00:00–23:59, `CustomEngine.scope` in
   `data:preferences`).
4. Commit-to-`SELF_PROTECTION` — uses `CommitmentEngine.create`.
5. `ProtectionEnforcer.enforceNow()` starts `ShieldVpnService`.

`feature:dashboard` — home: health card (`HealthEngine`), commitment timer/progress,
blocked-query stats, quick "protect now", and deep links into setup/settings/reports.

`feature:support` — "Why does Settings show no VPN?" (VPN-conflict detection,
`NetworkCapabilities`), DNS-after-encryption (DoH/DoT) disclosure, vendor battery whitelist
guidance (`OemInfoRepository.guidance`), how DNS blocking works, and the terms/privacy screen.
This is the main offline FAQ surface; no backend exists.

## Persistence of setup state

- `setup_completed` (DataStore) — terminal; the user enters via `dashboard` afterwards.
- `ProtectionMode.SELF_PROTECTION` row (Room `commitments`) — the protection contract.
- `ProtectionSchedule` (DataStore) — used by the VPN schedule monitor.

## Permissions summary

| Permission | Type | When |
|---|---|---|
| VPN consent | `VpnService.prepare` (user dialog) | Setup activation and every "protect now" after user-performed revoke-free stop |
| Notifications | Runtime (SDK 33+) | Health/Permission check; foregound service notification is required |
| POST_NOTIFICATIONS / system alert window | Runtime | Checked only, for the protection-status experience |
| Battery optimization | Doze exemption | Requested as guidance (health + OEM screens), never user-visible blocking |

No location, contacts, SMS, or storage permissions are declared.

## Edge behavior

- Returning user with onboarding `Started`: nav resumes at the step reached.
- `eula_accepted` + `setup_completed` fade into the dashboard as sole gate.
- Boot re-enforcement: if the OS killed the VPN (or lockdown revoked it), the user lands on a
  healthy-but-non-running dashboard with the health card DEGRADED and a one-tap restore —
  plus `ProtectionRecoveryWorker` for automatic BOOT restoration (see boot doc).