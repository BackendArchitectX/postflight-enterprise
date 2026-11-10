# Phase 3: authenticated workspace foundation (private alpha)

This branch adds a **separate, Cognito-authenticated workspace domain** to the existing Java 17 local server. It does **not** convert the shared PostFlight demo drafts, reviews, rules, incidents or social accounts into secure multi-tenant resources. No real user data should be placed in those legacy APIs.

## Scope

- Amazon Cognito **user pool access token** verification on the backend, including RS256 signature against Cognito's **trusted HTTPS JWKS endpoint**, signing key ID/rotation refresh, issuer, `client_id`, `token_use=access`, `sub`, `username` and time claims. ID tokens, unsigned tokens and machine-to-machine access tokens do not grant workspace access.
- OAuth 2.0 Authorization Code with PKCE on the browser. Access token stays **in tab memory** and is not saved to localStorage, sessionStorage, URL query or audit logs. Only the random PKCE verifier/state live briefly in sessionStorage during the redirect.
- New `/api/secure/*` APIs backed by separate `data/secure-workspaces.json`: create/list/get workspaces, list members (Owner/Admin), invite an existing Cognito subject, accept a target-bound invitation, list invitations without secrets, and read workspace audit history.
- Roles: OWNER (creator), ADMIN (may invite), REVIEWER and VIEWER (may see own workspace summary but cannot invite/view roster).
- Invitations are random 256-bit tokens hashed before persistence, displayed only on creation, tied to the target Cognito `sub`, expire in 24 hours, and cannot be accepted twice. They are **not emailed**. Share invitation ID + token out of band.
- The public Render demo remains read-only, and all secure routes **fail closed** when Cognito is unconfigured. Once Cognito is configured, the shared legacy demo APIs are denied; they are not made multi-tenant simply by adding login.

## Prerequisites for a private test instance

Create an Amazon Cognito **user pool** and an **OAuth app client without a client secret**, with the Authorization Code flow and Cognito managed login domain enabled. Configure the exact callback URI for your private test origin, e.g. `http://localhost:10000/` for localhost or `https://your-private-test-domain.example/` for HTTPS. Enable scopes `openid` and `profile`.

Set these **non-secret identifiers** in the Java server process environment:

```text
POSTFLIGHT_COGNITO_USER_POOL_ID=ap-south-1_EXAMPLE
POSTFLIGHT_COGNITO_CLIENT_ID=examplepublicclientid123
POSTFLIGHT_COGNITO_DOMAIN=your-domain.auth.ap-south-1.amazoncognito.com
POSTFLIGHT_PUBLIC_DEMO=false
# Required only when serving the private app behind an HTTPS reverse proxy:
POSTFLIGHT_PRIVATE_PROXY=true
POSTFLIGHT_APP_ORIGIN=https://your-private-test-domain.example
```

Do **not** add a client secret to this browser app. Use a **private instance**, never the shared read-only Render deployment. No credentials or user-pool resources are provisioned by this repository.

For local development at `http://localhost:<port>/`, leave `POSTFLIGHT_PRIVATE_PROXY` unset: the Java server stays bound to loopback and the existing localhost same-origin guard applies. To serve a **private HTTPS test environment**, set `POSTFLIGHT_PRIVATE_PROXY=true` and the exact externally served HTTPS origin in `POSTFLIGHT_APP_ORIGIN` (hostname only; no path or port). The server refuses to expose a private proxy listener without valid Cognito configuration and an explicit HTTPS origin. Route TLS through a trusted HTTPS reverse proxy; limit access to the private test environment. Do not turn this on for the public Render demo.

Visit the Workspaces page. The **New workspace**, **Invite member**, **Secure workspaces**, and **Join invitation** controls start the authenticated flow. Cognito must have at least two real user accounts for an invite/accept test, and the inviter needs the intended member's exact Cognito `sub`. The current code does not look up a user's email, check that the target `sub` exists before invitation, or send invitation emails.

The backend's `/api/secure/config` is unauthenticated and returns public OAuth metadata only; it does not contain secrets. `GET /api/secure/me` requires a verified bearer access token.

## Security boundary / what's not production ready

**Private alpha, not yet enterprise-production-ready.** Remaining work includes managed database transactions, isolation of each content domain object and row, horizontal scaling, enterprise secret storage, verified email-based invitation, real notification delivery, user deletion/disable and revocation strategy, durable replicated storage/backups, audit archival, rate limiting, observability and penetration review. JWT signature checks alone cannot immediately detect token revocation; use appropriately short access-token lifetimes and a token revocation design before production. The Java SE local server implements the secure routes; any Spring adapter or API gateway needs a separate authentication integration and must not bypass these guards.

Do not set Cognito variables on the public Render demo; preserve `POSTFLIGHT_PUBLIC_DEMO=true` there. Never treat shared demo records as customer tenant data.

## QA / testing

The normal QA runners build isolated disposable Java 17 servers, verify public denial and anonymous denial, exercise the WorkspaceRegistry in an isolated temporary directory, and verify Cognito-compatible RS256 signatures/claims with test-generated RSA keys. The suite does **not** use real Cognito accounts, hosted UI or external publishing and cannot assert those integrations are working.

Windows:
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\qa\run_complete_qa.ps1
```

GitHub CI runs `bash scripts/qa/run_complete_qa_ci.sh`.

Official AWS references:
- https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-tokens-verifying-a-jwt.html
- https://docs.aws.amazon.com/cognito/latest/developerguide/authorization-endpoint.html
- https://docs.aws.amazon.com/cognito/latest/developerguide/token-endpoint.html
