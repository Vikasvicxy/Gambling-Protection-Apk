# Shield — Play Store Policy & Permission Checklist (Phase 4 §15–§16)

Use this to file the Play listing and the Data Safety form.

## Declared permissions (final, from merged manifests)
| Permission | Why | Rationale for Play form | Data Safety mapping |
|---|---|---|---|
| `INTERNET` | backend + updates | Network access | none shared from device except account events |
| `ACCESS_NETWORK_STATE` | monitor connectivity | Network access | none |
| `POST_NOTIFICATIONS` | user-facing alerts (local gateway) | Notifications | none |
| `RECEIVE_BOOT_COMPLETED` | restore protection after reboot | Boot functionality | none |
| `FOREGROUND_SERVICE` | VPN | VPN/service | none |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | attached-service VPN (`VpnService`) | VPN/service | none |
| `CHANGE_NETWORK_STATE` | consistent DNS switching | Network access | none |
| `BIND_VPN_SERVICE` | system binds `ShieldVpnService` (declared by service, not user-facing) | VPN/service | none |

**Not declared:** overlay, camera, microphone, SMS, contacts, location, storage, QUERY_ALL_PACKAGES, Accessibility, DeviceAdmin.

## Play Console listing inputs (draft copy in `11-store-and-rollout.md`)
- Listing language: English.
- **Data collection declared:** Device IDs/Android ID as pseudonymous identifiers when a pairing/account event is sent to the backend (opt-in pairing); VPN kill-switch alerts are local-only. Full mapping in `06-data-safety-draft.md`.
- **Data deletion:** user can unpair/delete pairing → server deletes pairing rows; device registration is a server row keyed by device id. Admin can purge pseudonymous ids (document in privacy policy).
- **Privacy policy URL:** must be a printable/public URL hosting `05-privacy-policy-draft.md` content before production listing is allowed.
- **Supplier:** a single developer/company identity + support contact.

## Closed-testing app signing
- Provide SHA-256 of the release signing certificate for App Signing by Google Play (prefer Play App Signing) — requires maintainer's keystore; we ship the signed candidate unsigned (Phase 4 final regression builds unsigned until a keystore is placed at `local/keystore.jks` + `keystore.properties`, both git-ignored).

## Parental-controls policy angle (children!)
- App can be used to protect a child's device. Role design (`PARENT_OR_GUARDIAN` vs `PARENT_SUPERVISED_CHILD`) avoids the app itself being a "parental control" tool barring COPPA/FTC "Children's Privacy" requirements — but if a child is the user, the Data Safety form must list targeting, and the privacy policy must cover children's data. **DEFERRED sign-off: maintainer must confirm legal stance in `15-parent-policy-review.md`.**

## Full policy-checklist (copy for the Room check)
- [ ] Privacy policy URL live and stable
- [ ] Data Safety form filled from `06-data-safety-draft.md`
- [ ] Ads: none → select "no ads" if offering paid; if unpaid select "none"
- [ ] Sensitive permissions: none beyond the list above
- [ ] Target audience field + child-directed if applicable
- [ ] Content rating questionnaire (violence zero, sexual content zero, profanity zero, realistic content zero — VPN/parental utility)
- [ ] Support email + external link to backend status page
- [ ] Release notes honest (no absolute claims; disclose DNS-only VPN bypass limits)
- [ ] US export controls self-certify ("no")