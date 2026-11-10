# PostFlight initial V3 audit — source review only

Source reviewed: `feat/workspace-scoped-workflows`, commit `336bc21e2a776cccafe883cf583bc9f015157dd6` (PR #17), 10 November 2026. The GitHub connector reported 81 source/assets/documentation files and successful PR workflows (PostFlight Complete QA and PostFlight Product Workflows). The PR is open and draft. **This is not a full V3 certification.**

| ID | Status | Finding | Evidence / follow-up |
|---|---|---|---|
| UI-001 | CONFIRMED SOURCE DEFECT | Workspace heading interpolates `${esc(state.workspace)}` literally because `panel('Projects in ${esc(state.workspace)}', ...)` uses single quotes. | `web/assets/app.js` `workspaces()`. Patch included with a narrow regression check. |
| TEST-001 | COVERAGE GAP | `WorkspaceDraftSecurityCheck` verifies role names and store isolation, but does not itself send authenticated VIEWER `POST`/`PUT` requests through `WorkspaceGateway` or HTTP. | Add a deterministic gateway JWT seam or dedicated HTTP-level integration tests; do not equate store assertions with end-to-end RBAC verification. |
| UI-002 | OBSERVED/ARCHITECTURAL | Authenticated mode blocks old unscoped `/api/bootstrap`; the main app's `hydrate()` catches failure as `Backend unavailable`. This is misleading when Clerk-backed workspace endpoints are healthy. | Inspect `LocalServer` and `web/assets/app.js`; separate an intentionally unavailable demo from a failed backend. Requires design decision beyond this narrow patch. |
| OPS-001 | OBSERVED/ARCHITECTURAL | `Main.java` prints the unconditional `PostFlightService.DEV_WARNING` stating no authentication or tenant isolation, even with Clerk configured. | Clarify only the legacy demo is unscoped; do not imply that full tenant isolation exists. |
| SEC-001 | REVIEW CANDIDATE, NOT CONFIRMED EXPLOIT | The custom JSON object parser uses `out.put(key, value())`, accepting duplicate keys with last-write-wins. This can create ambiguous security-sensitive input. | Add a duplicate-key rejection regression test and audit compatibility impact. |
| DATA-001 | DOCUMENTED LIMITATION | Workspace drafts are JSON files with process-local synchronized operations; no multi-process transaction or relational durability guarantee. | Do not run concurrent/multi-node production deployment. Test crash recovery on disposable data. |
| PRODUCT-001 | DOCUMENTED LIMITATION | Secure Workspace Drafts is separate from shared demo Content Studio and subsequent validation/review/release workflows. | Do not claim an end-to-end tenant-secure business lifecycle. Plan incremental migration. |
| TEST-002 | COVERAGE GAP | Previous browser audit examined 14 routes but recorded fewer control interactions than inventory instances; role-dependent Clerk UIs were not exhaustively asserted. | Build test fixtures and a state/role-aware Playwright suite. |

## PR #17 assessment

**CONDITIONAL / NOT YET CONFIRMED READY TO MERGE.** CI is green, Java compiles and local automated tests passed in previous user-provided logs. However, live two-user draft updates, refresh/restart persistence, authorization at actual HTTP endpoints and comprehensive security review remain incompletely evidenced. Keep draft until acceptance checks are documented. Do not conflate this with production readiness.

## The supplied patch covers only

A deterministic heading bug fix, its regression check, a conservative static source inventory and a PowerShell runner that reuses existing safe suites and honestly states what it has not verified. It does not modify GitHub or local user data and cannot execute inside the user's local IntelliJ by itself.
