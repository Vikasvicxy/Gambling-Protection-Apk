# Shield — REQUIRES-VIKAS (Human-in-the-loop gates before production)

Everything left in Phase 4 requires physical hardware, live credentials, or a human
decision with a human operator (VIKAS). Nothing below can be completed from code alone.
Statuses are honest at the time of writing (2026-09-18): **everything that can be done
without a physical device has been done** and is documented in `00-phase4-report.md`.

Current global status: **WAITING FOR PHYSICAL DEVICE.**

## 1. Physical Android device (blocking — do first)
- [ ] Connect a phone/tablet, enable **USB debugging** (Settings → Developer options → USB debugging).
- [ ] Accept the RSA/USB debugging prompt on the device.
- [ ] Verify detection: `C:\Android\Sdk\platform-tools\adb.exe devices -l` shows `device` (not `unauthorized`/`offline`).
- [ ] Grant POST_NOTIFICATIONS + VpnService consent + disable battery optimization for Shield.
- [ ] Make sure the release candidate is signed or use the debug APK (both build paths documented in `19-real-device-quickstart.md`).

Then run:
```powershell
.\scripts\phase4-real-device-test.ps1 -Install -Apk app\build\outputs\apk\release\app-release-unsigned.apk
```
Record results with the script (writes `out/device-test/*.csv|json`), then fill the
checklists in `01-real-device-test-matrix.md` (VPN/DNS §2, performance §3, battery §4,
notifications §5, accountability §6, crash/ANR §7). Check `14-crash-anr-validation.md`
logcat steps after each section. Results stay **NOT TESTED** until a device outcome exists.

## 2. Backend production deployment (Cloudflare credentials — human)
No Cloudflare account/API token exists in this workspace. Until the operator runs:
```powershell
cd serverless\worker
npx wrangler d1 create shield-db          # fill REPLACE_ME_WITH_D1_DATABASE_ID
npx wrangler kv namespace create SHIELD_KV # fill REPLACE_ME_WITH_KV_NAMESPACE_ID
npx wrangler d1 migrations apply shield-db --remote
npx wrangler secret put PAIRING_SECRET
npx wrangler secret put ADMIN_SALT
npx wrangler secret put ADMIN_BOOTSTRAP_TOKEN
npx wrangler deploy
```
...the backend remains **LOCAL-TESTED / NOT DEPLOYED**. Offline wire-format tests pass
locally (`npm test`, 19/19) — see `12-backend-deployment-pack.md` and
`17-hardening-addendum.md` §4. Live E2E requires the deployment + a device.

## 3. FCM push (Firebase console — human)
No Firebase project or `google-services.json` exists. Until the steps in
`16-push-notifications.md` are executed by the operator, push delivery is
**IMPLEMENTED / NOT END-TO-END TESTED**. Do not claim push works end-to-end.

## 4. Play Console listing (human)
- Store assets + copy: `11-store-and-rollout.md`.
- Permissions/policy: `04-play-store-policy-checklist.md`.
- Privacy policy URL must be hosted from `05-privacy-policy-draft.md` before listing.
- Data Safety form from `06-data-safety-draft.md`.
- App signing: operator places `local/keystore.jks` + `local/keystore.properties`
  (git-ignored) or uses Play App Signing; re-run `:app:assembleRelease` for a signed APK.
- Closed-testing package: unsigned `app-release-unsigned.apk` ready; signed build is a human step.

## 5. Legal / policy sign-off (human)
- Children's-data posture (COPPA/targeting) — `15-parent-policy-review.md`, `04` §Parental-controls.
- Final privacy-policy wording + support email — `05-privacy-policy-draft.md`.

## 6. Deferred technical follow-ups (human decision, not blocking device run)
- Kotlin 2.4.20+ upgrade for CVE-2026-53914 (build-time) — `08-dependency-review.md`.
- Admin login rate limit + `REPORT_RATE_LIMIT` wiring — `12-backend-deployment-pack.md`.
- Future SCA CI in the deployment pipeline.

## Checklist gate before public production
- [ ] Device rows V1–V10, P1–P8, B1–B6, N1–N3, A1–A4, C1–C5 populated from a real device (no skips on blocking rows).
- [ ] Backend deployed + smoke-tested (W1–W7 in `10-accountability-real-world.md`).
- [ ] FCM E2E delivered on a device (`16-push-notifications.md`).
- [ ] Play listing + Data Safety + privacy URL + closed testing submitted/approved.
- [ ] Release APK signed by the operator.

Until those are done, public production is **NOT READY** (see `00-phase4-report.md` §30).