# Shield — Play Data Safety Form (Phase 4 §17)

Map for the Play Console "Data safety" section. Fill each field exactly as below; do not overclaim.

## Does this app collect or share any of the required user data types?
| Data type | Collected? | Shared? | Transient? | Purpose |
|---|---|---|---|---|
| Location | No | No | — | — |
| Personal info (name, email, etc.) | No | No | — | — |
| Financial info | No | No | — | — |
| Health & fitness | No | No | — | — |
| Messages / Photos/videos / Audio | No | No | — | — |
| Contacts | No | No | — | — |
| App activity | No* | No | — | *no browsing history; only in-app feature toggles exist locally, none uploaded |
| Web browsing | No | No | — | explicitly not collected |
| App info & performance | No (no analytics SDK) | No | — | pure local-only logs only if a future telemetry is added |
| Device or other IDs | **Yes** | Yes (pairing backend only) | **Yes** (TTL'd) | account/pairing + notification registration |

### Device IDs — nuance (matches Play guidance: choose the most accurate)
Play distinguishes "Device or other identifiers" as a required type. Select:
- **Device or other IDs: Yes**
- Shared: **with our backend for those who opt into pairing** — describe as "Device identifiers are shared with our backend when pairing features are used, solely to operate pairing and relay health events."
- Deletion: **Yes, device pairing can be deleted by the user** (unpair/delete affects pairing rows; device registration purge on request).
- Encryption: **Transmitted over HTTPS; stored hashed/pseudonymous** (device IDs treated as pseudonymous).

> If the Data Safety form forces a binary "collected", answer Yes for device IDs and add the scoping sentence so reviewers see it.

## Advertising
- Ads present? **No.**
- Profiling? **No.**

## Compliance
- Select "Data can't be deleted" only if Google forces; otherwise "Yes, users can request deletion" (support email path).
- Security practices page: declare "data in transit encrypted" (HTTPS) + "data deleted" paths + "encryption of stored data" — we do not store user data locally beyond blocklist/rules and paired config (in encrypted local storage where applicable).

## Additional disclosures
- The app implements a DNS-level VPN to enforce block rules. Play may label the VPN feature; our Data Safety answers remain consistent: no browsing content leaves the device.
- If Shield is used to protect a child's device, answer the "geared toward children" questionnaire accordingly (title VI of the checklist §04). Confirm legal stance with maintainer.