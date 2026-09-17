# Shield — Release Rollback Procedure (Phase 4 §26)

## On-call runbook (production)
Trigger conditions (any = start rollback):
1. **Crash/ANR tidal wave** — Play Vitals + Crashlytics/OS crash drop of >2× baseline for a release cohort.
2. **VPN instability** — reports of VPN drop/forced-close loops or recovery-worker failure at scale.
3. **Update-signature failure** — signature-verification failures at scale (fail-closed; no partial apply, but users on blocked update cannot get rules).
4. **User-visible blocking breakage** — legitimate sites blocked at scale (false-positive spike).
5. **Battery drain** — 2× baseline drain reports from the new release.

## Steps
1. Play Console → Release → reduce rollout to **0 %** (staged rollouts allow this without unpublishing).
2. If the app is published at 100 %: pause rollout, then analyze. Do NOT unpublish/delete without a confirmed decision.
3. Use staged rollout's "rollback to previous track" if the prior version (versionCode 1/0.1.0 in closed) is still signed-compatible with user data (Room schema v1 — confirm migration is backward-compatible before choosing).
4. If schema migration incompatibility exists, ship **versionCode 3** hotfix instead of reverting.
5. Post-mortem: capture logs, reproduce on a device, add a regression test, then restart release pipeline from §11 staged rollout.

## Data safety during rollback
- We never delete users' local rule sets on rollback; updates are signed + fail-closed, so a rollback to an older blocklist version cannot silently widen the blocklist on production devices.
- Backend rollback: `wrangler versions list` + redeploy previous version (see `12-backend-deployment-pack.md`); D1 uses logical deletes only.

## Prepared artifacts (already in-repo)
- `docs/phase3/` and `docs/phase4/` matrix checklists
- Signed-update verification tests (re-run before any re-release of a prior version)
- Version tags (`phase1-complete`, `phase2-complete`, `phase3-complete`) allow bisecting which behavior regressed.