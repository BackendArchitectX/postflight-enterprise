# Local REST API reference

**Base:** `http://127.0.0.1:8080` · **Content-Type for mutations:** `application/json` · **Auth:** none in local mode · **Zernio:** disconnected.

| Method | Route | Description |
|---|---|---|
| GET | `/api/health` | Runtime and publishing status |
| GET | `/api/session` | Explicitly unauthenticated local principal |
| GET | `/api/bootstrap` | Application state without certificate secret |
| GET | `/api/metrics` | Computed totals from local repository |
| GET | `/api/drafts` | Persisted draft list |
| POST | `/api/drafts` | Create a draft (`title`, optional `body`, `campaign`, `platforms`) |
| PUT | `/api/drafts/{id}` | Update title/body/altText, optionally `expectedVersion` for optimistic conflict checks |
| POST | `/api/validations` | Run deterministic checks with `draftId`, `platform`, `altText` |
| GET | `/api/validations` | Validation history |
| POST | `/api/reviews/{draftId}/decision` | `decision: Approved` or `Changes requested`; approval requires current, non-blocking validation |
| POST | `/api/releases` | Issue a local HMAC evidence certificate with `draftId`; requires validation + review |
| GET | `/api/releases` | Issued local release certificates |
| GET | `/api/releases/{id}/verify` | Verify local HMAC evidence record |
| GET | `/api/audit` | Events, chain and `chainValid` flag |
| GET | `/api/policies` | Current base+custom policy version and rules |
| POST | `/api/policies/rules` | Add literal-phrase custom rule (`title`, `phrase`, `severity`) |
| POST | `/api/policies/simulate` | Illustrative version-change impact (`strictness` 1–5) |
| POST | `/api/workflows/test` | Simulated workflow trace (`risk` e.g. high) |
| GET | `/api/media` | Local media metadata |
| POST | `/api/media` | PNG/JPEG upload as base64 JSON (`name`,`mime`,`base64`,`altText`) max 700 KB |
| GET | `/api/media/{id}` | Base64 media bytes and metadata |
| PATCH | `/api/incidents/{id}` | Set `status` to Open, Investigating or Resolved |
| GET | `/api/integrations` | Provider capabilities (publishing explicitly disabled) |
| POST | `/api/admin/reset` | Destructive reset to example seed data (local only) |

## Examples

```powershell
# Verify health
Invoke-RestMethod http://127.0.0.1:8080/api/health

# Create draft
$draft = Invoke-RestMethod -Method POST -Uri http://127.0.0.1:8080/api/drafts `
  -ContentType 'application/json' `
  -Body '{"title":"Financial wellness campaign","body":"Small steps can improve your budgeting habits.","platforms":"LinkedIn"}'

# Validate
$body = @{ draftId = $draft.id; platform = 'LinkedIn'; altText = 'Budgeting illustration' } | ConvertTo-Json
Invoke-RestMethod -Method POST -Uri http://127.0.0.1:8080/api/validations `
  -ContentType 'application/json' -Body $body
```

`400` invalid input · `404` unknown resource · `409` stale version or blocked state · `413` request or asset too large · `415` unsupported content type.

### Validation behavior

This implementation provides representative local policy checks for absolute guarantees, investment-risk disclosures, simplified X character budgets, missing image alt-text, and Instagram caption link usability. It does **not** call live platform validation endpoints, and unsupported media/safe-area rules remain a design preview.

## Authenticated Clerk workspace APIs (private Java SE runtime)

When Clerk is configured, `/api/secure/config` stays public. `/api/secure/me`, `/api/secure/workspaces`, `/api/secure/workspaces/{id}/members`, `/api/secure/workspaces/{id}/invitations`, and `/api/secure/invitations/accept` require a verified `Authorization: Bearer <Clerk session JWT>` header. The separate legacy local demo endpoints are blocked in authenticated mode. Read [the setup guide](CLERK_AUTH.md).
