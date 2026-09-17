# Shield — Parent / Guardian Policy Review (Phase 4 §12, §15)

## What the app does (design intent; verified in code)
- **Role separation:** `PartnerRelationshipManager` rejects client-declared ADMIN. Parent relationship is a distinct `PARENT_OR_GUARDIAN` ↔ `PARENT_SUPERVISED_CHILD` pair. A child/partly-supervised device cannot impersonate a parent.
- **UI guardrail (Phase 4 fix):** `ParentViewModel.childScope` refuses to authorize access to a child id unless it is linked in the observed relationships → defense-in-depth on top of server-side linkage.
- **Data scope:** enforcement happens on the child device (DNS blocklist = local). Parent dashboard shows **aggregate health + request/approval events only — no browsing history**, consistently with Play's parental-controls expectations.
- **Token/approval:** approval requests are one-way (decide allowed/denied), rate-limited upstream, TTL'd tokens.

## Play policy angle
- Play does **not** classify a VPN-based focus/production-care filter as a "device-management" or "parental-control" SDK solely by itself, but an app principally used to filter a child's device **is** reviewed under families/apps-performance expectations: no history capture, no ad ID for children, no adult content, Data Safety truthful.
- The Data Safety form (§06) must state "no browsing history collected/shared" and device ids are transient.
- Privacy policy must include a children's-data paragraph (§05 §4) with a request-for-deletion path.

## Honest gaps
| Item | Status |
|---|---|
| Parent dashboard E2E on a real device | NOT TESTED (device pending; matrix A2–A4) |
| Legal review of child-data stance | DEFERRED — maintainer must confirm COPPA/child-directed posture with their counsel before public listing |
| Enforcement-by-parent telemetry (who knows a child bypassed) | PARTIALLY TESTED — `report` endpoint exists; server-side unit tests OK; live NOT TESTED |

## Recommendation
Ship with aggregate-health only, no history (current design), disclose the DNS-only bypass limits in child-context copy, and get legal sign-off before production listing that targets families.