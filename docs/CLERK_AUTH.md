# PostFlight Enterprise — Cognito → Clerk migration

For `BackendArchitectX/postflight-enterprise` as inspected on 10 November 2026. This is a **local patch kit**, not a GitHub commit. Designed for the **Java 17 local server** started by `run.ps1`, not the optional Spring adapter. No npm, Maven, Docker or Cognito is needed for the migration.

## What this patch changes

- `CognitoAccessVerifier.java` is backed up and removed; new `ClerkSessionVerifier.java` verifies trusted Clerk RS256 session JWT signatures via Clerk's instance JWKS URL, issuer, expiration/not-before, authorized party (when present), user ID (`user_...`), session ID (`sess_...`) and pending status.
- `WorkspaceGateway.java` preserves original OWNER/ADMIN/REVIEWER/VIEWER permissions, invite and membership rules, and now uses verified Clerk user IDs.
- `WorkspaceRegistry.java` updates its invitation target validation from Cognito UUIDs to Clerk `user_...` IDs.
- `web/assets/clerk-auth.js` (new) loads Clerk's official vanilla JS SDK from the instance Frontend API host.
- `web/assets/secure-workspaces.js` switches the sign-in/sign-up UI from Cognito OAuth+PKCE to Clerk popover. Session tokens are requested from Clerk for each protected HTTP request, not saved to browser local/session storage.
- `LocalServer.java` uses Clerk environment configuration, updated CSP sources and fail-closed legacy API handling. Read-only Render demo behavior remains unaffected when kept in public demo mode.
- Updates browser and Java regression tests, `README.md`, security/API docs and historical Cognito references.

## STEP 1 — Prepare the project (IntelliJ or PowerShell)

Stop the running app with Ctrl+C. In PowerShell:

```powershell
cd C:\Users\user\Downloads\PostFlight_Enterprise_v0.1_Java17\PostFlight_Enterprise

git switch main
git pull --ff-only origin main
git status

git switch -c feat/clerk-auth-migration
```

The branch name is local. Run this patch **only when `git status` is clean**, and only in a trusted local clone. If the project directory differs, substitute your actual path.

## STEP 2 — Apply the patch

Extract the ZIP **inside your repository folder**, so `PostFlight_Clerk_Migration` is a child of the folder containing `run.ps1`. Then:

```powershell
py .\PostFlight_Clerk_Migration\apply_migration.py --project .
```

If `py` is not available, use `python` instead. The patch tool uses Python 3 standard library only and performs an up-front check for source markers. It creates a `.postflight-clerk-backup-YYYYMMDD-HHMMSS/` folder containing the original files. It refuses to overwrite existing Clerk source files or apply the change a second time.

Verify:

```powershell
git status --short
git diff --stat
git diff --check
```

## STEP 3 — Create a Clerk application

1. Open https://dashboard.clerk.com/ and create an application called `PostFlight Enterprise`.
2. Configure **Email** as an allowed sign-up/sign-in method. For the first test, avoid custom social OAuth provider configuration.
3. In **API keys**, copy the **Publishable Key** (starts with `pk_test_`). This is a public browser identifier, not a secret key.
4. Find the **Frontend API URL** for this Clerk instance. For a development instance, it commonly resembles `https://your-instance.clerk.accounts.dev`.
5. Use only the exact URL for **your own instance**. The issuer is normally the Frontend API origin for Clerk session JWTs. Inspect `iss` in a genuine session token if the API URL differs.
6. Configure the instance's allowed origins/redirects so that `http://127.0.0.1:8080` is permitted. Do not mix this with `http://localhost:8080` while testing.

## STEP 4 — Configure PowerShell environment variables

Use the SAME PowerShell terminal that will run PostFlight:

```powershell
$env:POSTFLIGHT_CLERK_PUBLISHABLE_KEY = 'pk_test_REPLACE_WITH_YOUR_PUBLIC_KEY'
$env:POSTFLIGHT_CLERK_ISSUER = 'https://REPLACE_WITH_YOUR_FRONTEND_API_HOST'
$env:POSTFLIGHT_APP_ORIGIN = 'http://127.0.0.1:8080'
$env:POSTFLIGHT_PUBLIC_DEMO = 'false'

# Clean up old Cognito settings in this PowerShell process.
Remove-Item Env:POSTFLIGHT_COGNITO_USER_POOL_ID -ErrorAction SilentlyContinue
Remove-Item Env:POSTFLIGHT_COGNITO_CLIENT_ID -ErrorAction SilentlyContinue
Remove-Item Env:POSTFLIGHT_COGNITO_DOMAIN -ErrorAction SilentlyContinue

# Localhost use only. Do NOT expose an HTTP private proxy.
Remove-Item Env:POSTFLIGHT_PRIVATE_PROXY -ErrorAction SilentlyContinue

.\run.ps1
```

**Never put a `sk_test_...` or `sk_live_...` Clerk Secret Key in HTML, JavaScript, or the repository.** This migration does not need a Clerk backend API secret because the Java backend verifies session JWTs using public signing keys.

## STEP 5 — Verify in browser

Open `http://127.0.0.1:8080/` (not the Render read-only demo). Go to **Workspaces → New workspace** and choose **Sign in / Sign up**. Authenticate with Clerk, then create a workspace. Go to **Secure workspaces** and verify that it appears.

To test invitations, create a second Clerk user (use another browser profile), copy the exact `user_...` ID from Clerk Dashboard → Users, and invite that ID from your first user's workspace. The invitation ID and one-time token must be shared manually; this app does not send invitation email. The second user accepts via **Join invitation**.

## STEP 6 — Verify secure API behavior

In a second PowerShell tab while the server runs:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/health
Invoke-RestMethod http://127.0.0.1:8080/api/secure/config

try {
  Invoke-WebRequest http://127.0.0.1:8080/api/secure/me -ErrorAction Stop
} catch { Write-Host $_.Exception.Response.StatusCode }
```

The config response should say `enabled: true`, `provider: Clerk`; `/api/secure/me` should respond `401 Unauthorized` with no bearer token. The browser's authenticated workspace requests should return `200` and include no credentials in logs or localStorage.

The previous unscoped demo APIs are intentionally denied when Clerk is enabled: `/api/bootstrap`, `/api/drafts`, admin reset and the independently authenticated Zernio inspection are NOT secure tenant APIs. The public anonymous preflight preview and `/api/health` remain available.

## STEP 7 — Run regression tests

Use a *new PowerShell terminal without the Clerk environment variables* for the existing QA runner. It starts disposable local servers and expects no configured identity provider:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\qa\run_complete_qa.ps1
```

The modified Java regression test checks forged, expired, mismatched-issuer, mismatched-origin, pending and invalid-subject JWTs against a locally generated RSA key. Full QA also needs the project's existing Python/Node/Docker/browser tools; the normal `run.ps1` requires only JDK 17+.

## STEP 8 — Commit when tested

```powershell
git add README.md .gitignore backend web scripts docs
git commit -m "feat(auth): replace Cognito with Clerk session authentication"
git push -u origin feat/clerk-auth-migration
```

Create a PR on GitHub and merge only after both browser integration and regression tests pass.

## Important boundaries

- This migration authenticates **the existing separate workspace domain only**. It does not automatically make demo drafts, review approvals, policy rules, incidents, or releases multi-tenant. Those workflows need separate tenant-scoped persistence and authorization before hosting for real teams.
- `run.ps1` still uses Java SE's built-in HTTP server. The separate optional Spring Boot adapter is NOT retrofitted with Clerk security by this kit; do not expose it externally with its existing unprotected APIs.
- A previously saved Cognito UUID-based membership will **not** automatically map to a Clerk `user_...` ID. Do not rewrite user IDs blindly. For preexisting real data, plan an explicit verified identity-linking migration.
- JWT signature verification cannot always detect immediate account suspension or revocation. For production, design revocation strategy, session invalidation, rate limiting, managed durable database storage, HTTPS/TLS, secure secret handling, audit and tenancy across all domain objects.
- Keep `POSTFLIGHT_PUBLIC_DEMO=true` on Render. Never turn the public demo into a private writable environment by only setting Clerk variables.

## Troubleshooting

| Symptom | Action |
|---|---|
| `Clerk is not configured` | Ensure **all three** variables are set in the SAME terminal as `run.ps1`. Check `/api/secure/config` (not secret keys). |
| `Clerk is not ready` | Open browser DevTools Console and Network. Check the Frontend API host and CSP blocks. |
| `401 Wrong Clerk issuer` | Use exact Clerk session JWT `iss`; it must match your configured Frontend API origin. |
| `401 Unauthorized JWT origin` | The request's `azp` origin must match `POSTFLIGHT_APP_ORIGIN` exactly, including port and `localhost` vs `127.0.0.1`. |
| `401 Bearer Clerk session token required` | Sign in through the Clerk popover; calls to secure APIs must include a token from `Clerk.session.getToken()`. |
| `503 Clerk signing keys unavailable` | Check internet access and the instance's `/.well-known/jwks.json`; only the configured HTTPS host is used. |
| `403 Legacy demo API disabled` | Expected in authenticated mode. Use `/api/secure/*`; do not re-enable shared unscoped storage. |
| Invitation target rejected | Use an exact existing Clerk `user_...` ID, not a Cognito UUID, email, name or session ID. |
| Port 8080 already in use | Stop prior `run.ps1` process with Ctrl+C, then restart. |

### Revert

The safest rollback is to discard the migration branch or restore the original files from `.postflight-clerk-backup-...`. Existing workspace data under `data/` is never changed by this patch. Before restoring, preserve any local work made after the migration.

Official references:
- https://clerk.com/docs/js-frontend/getting-started/quickstart
- https://clerk.com/docs/guides/sessions/manual-jwt-verification
- https://clerk.com/docs/guides/secure/best-practices/csp-headers
