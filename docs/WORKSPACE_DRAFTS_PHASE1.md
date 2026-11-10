# Workspace-scoped draft management — Phase 1

This patch adds secure, persisted draft CRUD to the existing Clerk-authenticated workspace API. It does not migrate or expose shared demo data.

Endpoints (with Clerk session Bearer token):
- `GET /api/secure/workspaces/{wsId}/drafts`
- `POST /api/secure/workspaces/{wsId}/drafts` body `{ "title": "...", "body": "..." }`
- `GET /api/secure/workspaces/{wsId}/drafts/{draftId}`
- `PUT /api/secure/workspaces/{wsId}/drafts/{draftId}` body `{ "title": "...", "body": "...", "version": 1 }`

All authenticated members can read. OWNER and ADMIN can create/update. REVIEWER and VIEWER cannot write (HTTP 403). Nonmembers get HTTP 404. Wrong draft ID in a workspace returns 404. Update uses optimistic version checks (409).

The **Secure workspaces → Workspace drafts** modal provides a simple create/read/edit UI. The legacy Content Studio remains demo-only while the rest of the domain model is upgraded.

Local data is stored under `data/workspace-drafts/`, separate from `data/postflight-data.json`; these records are not suitable for production multi-node storage. No migration of existing sample drafts, no email notifications or publishing, and no public Render writes.

This patch is NOT a production-ready multi-tenant platform. Full workspace-scoped validation, review, policy, incident, release and media services still need implementation and integration tests.
