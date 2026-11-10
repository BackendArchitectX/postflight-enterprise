# PostFlight: step-by-step guide

This guide tells you exactly **where to click**, **what to enter** and **what happens next**. It describes the current working `main` branch unless a section says *not available yet*.

**First:** Start the app with `.\run.ps1` in PowerShell, then visit **http://127.0.0.1:8080/**. The online demo is read-only, so save and edit workflows require the **local app**.

## A. Make a post and check it

### 1. Content Studio — Write your post

1. Click **Content Studio** in the left menu.
2. Click **New draft**.
3. Type a title, such as **My first finance post**, and create the draft.
4. Type your message in the editor. Example: `Plan your savings wisely. Investing involves risk.`
5. Choose the platform you want to check.
6. Click **Save draft** if needed. The app also saves edits locally.

**Result:** Your draft is saved on this computer. It is not shared online.

### 2. Validation Center — Find mistakes

1. Go back to **Content Studio**.
2. Click **Run preflight**. *Preflight* simply means “check before sharing.”
3. Open **Validation Center** to read what the app found.
4. If something says **BLOCKED**, read the reason.
5. Go back to **Content Studio**, fix the text and click **Run preflight** again.
6. Continue until there are no blocking problems.

**Example:** A claim like “Guaranteed 20% profit!” may be flagged. Use accurate language and add relevant risk information.

**Result:** You get a saved local check for the current draft version. These sample rules are **not official legal advice**.

### 3. Review Center — Ask for approval

1. First complete the **preflight check**. Blocked or out-of-date drafts cannot be approved.
2. Open **Review Center**.
3. Click **Request approval**.
4. Choose your draft and enter a **reviewer label** (for example, “QA Reviewer”). Add a short note.
5. Click **Request approval** to save the request.
6. Refresh the page to see the request in the local approval register.
7. Select the same draft from the review list.
8. Choose **Approve selected draft** or **Request changes**.

**Result:** The choice is stored in the local app. The reviewer label is **not a verified person**, and no email is sent. If you change the draft, its previous approval no longer covers that new version.

### 4. Release Center — Save proof of your check

1. Make sure your draft has a current non-blocked check and an approval.
2. Go to **Release Center**.
3. Click **Issue certificate**.
4. The app creates a local evidence record for the selected draft.
5. Select the new record to view its details. You can inspect its saved fingerprint through the API.

**Result:** You get proof of what the app checked. **Nothing is published or scheduled.** This is not a legal certificate or a signature from a real reviewer.

## B. Try the other tools

### 5. Mission Control — See the dashboard

1. Click **Mission Control**.
2. Look at the charts and summary cards.
3. Change the time range to explore the example data.
4. Use **New validation** to move into the checking workflow.

**Result:** You see a demo dashboard. Big numbers are examples, not live business metrics.

### 6. Workspaces — Look at example companies

1. Click **Workspaces**.
2. Browse the sample companies and projects.
3. Use the search box to find a sample workspace.
4. Notice that **New workspace** and **Invite member** are not working secure team features on the current `main` branch.

**Result:** This page shows examples only. Real sign-in, new team workspaces and invitations are being developed in [PR #5](https://github.com/BackendArchitectX/postflight-enterprise/pull/5).

### 7. Media Intelligence — Add a picture

1. Click **Media Intelligence**.
2. Click **Upload asset**.
3. Choose a **PNG or JPEG** image smaller than or equal to **700 KB**.
4. Add **alternative text**: a short description of what is in the picture.
5. Click **Upload image**.

**Result:** The picture and its details are saved on this computer, not sent to a social network.

### 8. Policy Studio — Make your own word-checking rule

1. Click **Policy Studio**.
2. Click **Add rule**.
3. Enter a title like **Avoid false promises**.
4. Enter the phrase to check, such as `guaranteed bonus`.
5. Pick how serious it is: Critical, High, Medium or Low.
6. Save the rule.
7. Reopen a draft containing that phrase and click **Run preflight**.

**Result:** The new rule is used in future checks. This is a simple phrase check, not an advanced AI compliance decision.

### 9. Policy Studio — Save a reviewed version

1. Stay in **Policy Studio**.
2. Click **Publish version**.
3. Enter a reviewer label and a short explanation of the changes.
4. Type the exact word `APPROVED`.
5. Click **Record policy publication**.
6. Open the **Version history** tab to see the saved snapshot.

**Result:** The app saves a **local snapshot** of the rules. The rules were already active when added. This button does **not** deploy rules to another service.

### 10. Workflow Builder — See how reviews could move

1. Click **Workflow Builder**.
2. Open the **Templates** tab.
3. Pick **Regulated marketing**, **Standard campaign** or **Emergency correction**.
4. Click **Preview template** and read the steps.
5. Close the preview and open **Workflow designer**.
6. Click a step such as **Legal review**.
7. Click **Edit workflow step**.
8. Change the reviewer label, response time (1–168 hours) and action for a failed step.
9. Enter a reason, then click **Save step revision**.
10. Refresh and select the step again to see the saved settings.

**Result:** Templates are previews; step settings are saved locally with a history. **Test workflow** is a simulation, not a real job runner.

### 11. Compliance & Incidents — Report a problem

1. Click **Compliance** (the **Compliance & Incidents** page).
2. Click **Create incident**.
3. Enter a short problem title, severity, owner and explanation.
4. Save the incident.
5. Select your new incident from the table.
6. To send it to another owner, click **Escalate**, give a reason and the next owner.
7. To finish it, click **Mark resolved**.

**Result:** The app keeps a local record and a history of escalations. It does not contact an external support team. You cannot escalate an already resolved incident.

### 12. Integrations — Try a profile or read-only Zernio key

**Option 1: Check a public profile link**

1. Click **Integrations**.
2. Paste a complete public HTTPS profile link (for example, LinkedIn).
3. Submit it or use the example button.
4. Read the result.

**Result:** Only the **link's format** is checked. It does not prove ownership or connect the account.

**Option 2: Inspect your own Zernio accounts**

1. In your own Zernio dashboard, obtain a **restricted read-only, profile-scoped API key**.
2. On **Integrations**, paste that key into the account inspection field.
3. Run the read-only check.
4. If you have no connected social profiles, the result may be empty.

**Important:** The key passes through PostFlight hosting and Zernio, even though PostFlight does not save it. Never use another person's key, your social password or an unrestricted publishing key. Real account inspection has not yet been verified against a connected social account.

### 13. Try PostFlight — Check text without saving it

1. Click **Try PostFlight**.
2. Choose a sample, or type your own message.
3. Choose **LinkedIn**, **Instagram**, **X** or another listed platform.
4. If you include an image, tick the image box and write a short image description.
5. Click **Run real Java preflight**.
6. Read the report. Fix anything marked **BLOCKED**, then check again.
7. Optional: Click **Export findings JSON** to save the report.

**Result:** The text is checked without being stored in PostFlight's draft database. **Simulate release checkpoint** is only a pretend handoff.

### 14. Zernio Live Monitor — See example or real read-only status

**Without a key:**

1. Click **Zernio Live Monitor**.
2. Read the label **SAMPLE SCENARIO — NOT LIVE**.
3. Click **Next sample event** to see the example steps.

**With your own restricted read-only key:**

1. Enter the key and accept the notice explaining where it will be sent.
2. Click **Load actual posts**.
3. Read the connected-account health and recent post status.
4. Optional: Select refresh every 60 seconds while the page is open.
5. Click **Disconnect and clear key** when done.

**Result:** You can inspect data returned by Zernio but **cannot send, edit or schedule posts** from PostFlight. Live results depend on whether your Zernio account is connected and the key has the correct read permissions.

### 15. Administration — See settings and reset the demo

1. Click **Administration**.
2. Look at the example members, roles and settings.
3. Use **Invite member** only when real authentication is implemented; it does not send email on `main`.
4. If you truly want to remove your local demo changes, choose **Reset demo data**.
5. Read the confirmation carefully before accepting.

**Warning:** Reset removes local demo changes. Do not use it to solve an unrelated error.

## C. For developers: run tests and see reports

### 16. Start the app in IntelliJ

1. Open the repository folder in **IntelliJ IDEA**.
2. Open **Terminal** at the bottom.
3. Check Java with `javac -version`. JDK 17 or later is required.
4. Run `.\run.ps1`.
5. Open **http://127.0.0.1:8080/**.
6. Stop the server with **Ctrl+C** when finished.

### 17. Test everything on Windows

1. Install Java JDK 17+, Python 3, Node.js/npm, Docker Desktop and a Chromium-based browser.
2. Start Docker Desktop.
3. Open the project folder in PowerShell.
4. Run:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\qa\run_complete_qa.ps1
   ```

5. Look for `SUCCESS: all executed local QA assertions passed`.
6. If anything fails, open `qa-reports/`, find the latest `complete-...` folder and inspect `SUMMARY.txt` and `browser-report.json`.
7. Fix the problem in a separate branch and rerun the same tests.

**Result:** Tests use temporary copies of data. They do not publish anything or change your real online accounts.

### 18. Check a pull request on GitHub

1. Open the [PostFlight repository](https://github.com/BackendArchitectX/postflight-enterprise).
2. Click **Pull requests** and open your PR.
3. Wait for **PostFlight Complete QA** and **PostFlight Product Workflows** to finish.
4. Click **Checks** to inspect the result. Open the failed job to read its error if needed.
5. Do **not** merge while tests fail.
6. Review the code and merge only after checks pass and required approvals are complete.
7. On your computer, run `git switch main` and then `git pull --ff-only origin main`.

**Result:** Your local `main` contains the approved changes.

### 19. Daily scheduler and automatic failure handling (not active yet)

These steps apply **only after [PR #6](https://github.com/BackendArchitectX/postflight-enterprise/pull/6) is merged**:

1. Open **GitHub → Actions → PostFlight Complete QA**.
2. Check for daily QA around **8:00 AM India time**. GitHub can start scheduled jobs late.
3. For a manual check, select **Run workflow** on the `main` branch.
4. If QA fails, the proposed **CI Guardian** should create an issue and retry failed jobs **once**.
5. If the retry fails too, inspect the issue, logs and screenshots and prepare a **reviewed fix PR**.
6. If the daily job does not start, the proposed **Scheduler Heartbeat** checks later and opens an issue.
7. For code changes, go back to step 18: review, test and merge only when safe.

**Result:** This can recover from a temporary test-machine/network problem. It **cannot automatically repair every code bug**. There is no automatic source-code rewrite, production deployment or social posting.

For the technical details and safety rules, see [the PR #6 runbook](https://github.com/BackendArchitectX/postflight-enterprise/blob/chore/ci-self-healing-scheduler/docs/CI_SELF_HEALING.md).

### 20. Secure teams and Cognito login (not active yet)

These instructions describe the **planned private test setup** in [PR #5](https://github.com/BackendArchitectX/postflight-enterprise/pull/5). **Do not use them to turn on the public site.**

1. Create a private Amazon Cognito user pool with at least two test users.
2. Configure a public app client using authorization code + PKCE (no client secret).
3. Configure the exact callback address and Cognito hosted login domain for a **private test app**.
4. Set the server's Cognito pool ID, app client ID and hosted domain using the private environment configuration in [Phase 3 setup](https://github.com/BackendArchitectX/postflight-enterprise/blob/feat/auth-workspaces-rbac/docs/PHASE3_WORKSPACES.md).
5. Start the private test app and sign in with Cognito.
6. Create a workspace as **OWNER**.
7. Invite a second test user's Cognito subject ID and choose a role.
8. Give that user the invitation ID and one-time token securely. No invitation email is sent.
9. Sign in as the second user and accept the invitation.
10. Verify that people from other workspaces cannot read these records.

**Result:** A private authentication test, **not** a production multi-user launch. Real Cognito integration and tenant-wide isolation still need review and verification.

## D. Important safety reminders

- The public Render demo is **read-only**.
- The editable local server is for your computer, not public hosting.
- Real Zernio publishing and scheduled social posts are **not built**.
- Some dashboards, people, companies and activity numbers are **examples**.
- “Approved” in this app means a local record, **not legal compliance approval**.
- If a feature is in an open PR, it is **not yet part of `main`**.

**Start with workflows 1–4.** Once you understand those, try the others one at a time.
