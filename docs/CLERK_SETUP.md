# Clerk sign-in for PostFlight (private test only)

**Clerk handles sign-in. PostFlight checks each signed session token before accessing a private workspace.**

The public [Render demo](https://postflight-enterprise.onrender.com/) remains **read-only**. Do not add a Clerk key or enable a private reverse proxy on that public demo.

## 1. Create two test accounts in Clerk

1. Visit [Clerk Dashboard](https://dashboard.clerk.com/) and sign in.
2. Choose **Create application**. Call it `PostFlight Test`.
3. Select **Email** sign-in (password or verification code, depending on your preference).
4. Open **API Keys**. Copy the **Publishable Key**, which begins with `pk_test_`.
5. **Never put the Secret Key (`sk_test_`) into PostFlight, source code, screenshots, or chat.** This Java integration does not need it.
6. Configure Clerk for local development at **http://127.0.0.1:8080**. Use the same address in your browser every time. If Clerk requests allowed redirect or origin URLs, add this exact URL; consult the domain settings in your Clerk dashboard.
7. Create two *test* users (for example, two different email addresses). You can create them through the sign-up screen after starting PostFlight.

## 2. Start PostFlight in IntelliJ (Windows)

1. Open **IntelliJ → Terminal** in the project folder.
2. Stop any previously running PostFlight instance using **Ctrl+C**.
3. Set the *public* Clerk publishable key **in this terminal only**:

```powershell
$env:POSTFLIGHT_CLERK_PUBLISHABLE_KEY = "pk_test_REPLACE_WITH_YOUR_PUBLISHABLE_KEY"
$env:POSTFLIGHT_PUBLIC_DEMO = "false"
.\run.ps1
```

4. Open **http://127.0.0.1:8080/** and click **Workspaces**.
5. Click **Secure workspaces → Sign in with Clerk**. Use your first test account.

**Tip:** To turn Clerk off again, stop PostFlight and run `Remove-Item Env:POSTFLIGHT_CLERK_PUBLISHABLE_KEY` in that terminal before `.\run.ps1`.

**Never** set `POSTFLIGHT_PRIVATE_PROXY=true` for localhost testing. The private server binds to 127.0.0.1 by default.

## 3. Create and invite

1. Sign in as User A and choose **New workspace**. Enter `Demo Team`.
2. Choose **Secure workspaces** to see the new team. The dialog displays your **Clerk user ID** (starts with `user_`).
3. Use a second browser profile/incognito session to sign in as User B and note their Clerk user ID.
4. Return to User A → **Invite member**. Enter User B's exact `user_...` ID and select **VIEWER**.
5. Copy the invitation ID and one-time token. They are displayed **only once**; share them privately with your test user. PostFlight does not send emails.
6. As User B, choose **Join invitation**, paste both values and accept.
7. Confirm User B can see the team but cannot invite a third user.
8. Try changing the invite token or using a different signed-in user: the server must refuse the request.

## 4. Check the private API

The public configuration endpoint only exposes non-secret identifiers:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/secure/config
```

Expect `enabled: True`, `provider: Clerk`, `publicReadOnly: False`. No passwords or secret keys should be present.

**Do not** paste a Clerk session token into chat, screenshots, logs or the README. The browser automatically sends a fresh short-lived signed token when using the Workspaces UI.

## 5. Run the full automated tests

Stop your local server, then run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\qa\run_complete_qa.ps1
```

The QA runner uses **disposable test servers** and generated RSA keys. It does not sign in to your real Clerk account. Use the manual steps above for the real sign-in test.

## 6. What is and isn't supported

- **Supported:** ClerkJS sign-in/sign-out in the Workspaces dialogs, RS256 Clerk session verification against the instance JWKS, exact browser-origin checks, private workspace creation, owner/admin invitations, one-time target-bound invite acceptance, server-side role checks.
- **Not supported:** automatic migration of existing Cognito UUID-based members, inviting by email or sending emails, organization-wide conversion of demo drafts/reviews into tenant-private objects, real social publishing, durable distributed database, account deletion/revocation propagation, production-grade availability and backups.
- Existing `data/secure-workspaces.json` from the Cognito prototype uses **different user IDs**. Back it up before experimentation. Do **not** rewrite old IDs by hand or delete real data. Start with a fresh isolated test directory if needed.
- With Clerk enabled, PostFlight intentionally blocks old shared/unscoped demo APIs. You can use the isolated Workspaces functions and stateless preflight, but the old editable sample workflows are **not** tenant-aware.
- An explicit `azp` browser-origin claim is required. This is stricter than Clerk's general optional-claim handling; sessions without it are rejected.
- No server-side Clerk secret key is used. The publishable key determines the trusted issuer/JWKS host; never accept a JWKS URL from a client request.

### Private HTTPS deployment (advanced, not needed for localhost)

Use a **private instance** behind a trusted TLS reverse proxy with:

```text
POSTFLIGHT_CLERK_PUBLISHABLE_KEY=pk_live_OR_TEST_PUBLISHABLE_KEY
POSTFLIGHT_PRIVATE_PROXY=true
POSTFLIGHT_APP_ORIGIN=https://your-private-test-domain.example
POSTFLIGHT_PUBLIC_DEMO=false
```

Do not expose this prototype publicly until its storage, data isolation, CSRF/origin boundaries, rate limits, and real Clerk integration have been independently reviewed.

**Official references:** [Clerk JavaScript Quickstart](https://clerk.com/docs/js-frontend/getting-started/quickstart) · [Manual JWT verification](https://clerk.com/docs/guides/sessions/manual-jwt-verification) · [CSP guidance](https://clerk.com/docs/guides/secure/best-practices/csp-headers).
