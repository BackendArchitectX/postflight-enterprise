# PostFlight Enterprise

**Check a social media post before you share it.**

PostFlight is like a checklist for social media content. You can write a post, find risky words, fix mistakes, ask for a review and save a record of the decision.

> **This is a learning and local testing app, not a real publishing service.** It does not post to social media or give official legal approval.

**Live demo on Render:** https://postflight-enterprise.onrender.com/  
**[Step-by-step guide for every workflow](docs/WORKFLOW_GUIDE.md)**

The Render site is **read-only**: visitors can explore the app and try the safe preflight preview, but cannot save drafts, publish posts or change accounts.

## 1. Run it on your computer

You need **Java JDK 17 or newer**. IntelliJ IDEA is optional.

1. Download or clone this repository and open its folder.
2. Open **PowerShell** in the project folder (IntelliJ → **Terminal** works).
3. Run:

   ```powershell
   .\run.ps1
   ```

4. Open **http://127.0.0.1:8080/** in your browser.
5. Keep PowerShell open. Press **Ctrl+C** to stop the app.

**macOS / Linux:** run `./run.sh` instead.

No API key, Docker, Maven or cloud account is needed to **run the local app**. Do not double-click `index.html`; the app needs its Java server.

## 2. Try the main workflow

| Step | Where to go | What to do |
| --- | --- | --- |
| 1 | **Content Studio** | Click **New draft**, give your post a title and write it. |
| 2 | **Content Studio** | Click **Run preflight** (this means “check before sharing”). |
| 3 | **Validation Center** | Read the warnings. Return to your draft, fix the text and check again. |
| 4 | **Review Center** | Click **Request approval**, choose the checked draft and enter a reviewer label. |
| 5 | **Review Center** | Select the draft and click **Approve selected draft** or **Request changes**. |
| 6 | **Release Center** | Click **Issue certificate** to save proof of the checked version. |

**Remember:** A certificate is a *local proof record*, not permission to publish or a real digital signature from a company. Editing the draft means it must be checked and approved again.

## 3. Other things you can try

| Page | Easy steps |
| --- | --- |
| **Mission Control** | Open the dashboard → explore example numbers → start a validation. |
| **Workspaces** | Explore sample company cards. Real team accounts are **not available on main**. |
| **Media Intelligence** | Click **Upload asset** → choose a PNG/JPEG (700 KB maximum) → add image description → upload. |
| **Policy Studio** | Click **Add rule** → enter a rule name and blocked phrase → save → check a draft again. |
| **Policy versions** | Click **Publish version** → enter the reviewer, reason and `APPROVED` → save a **local snapshot**. |
| **Workflow Builder** | Open **Templates** → preview a route; use **Workflow designer** → choose a step → **Edit workflow step**. |
| **Compliance & Incidents** | Click **Create incident** → add details → save → select it → **Escalate** or **Mark resolved**. |
| **Integrations** | Paste a public profile URL to check its format, or inspect your **own** Zernio accounts using a restricted read-only key. |
| **Try PostFlight** | Choose a sample or type text → select a social platform → **Run real Java preflight**. This does not save your text. |
| **Zernio Live Monitor** | View the clearly marked sample events; optional real read-only checks need your own restricted Zernio key. |
| **Administration** | View sample settings and people. **Reset demo data** deletes local sample changes. |

For the exact buttons, what to enter, what happens next and how to fix common errors, use the **[step-by-step workflow guide](docs/WORKFLOW_GUIDE.md)**.

## 4. Check that everything works

**Simple app run:** JDK 17+ only.

**Full automatic tests:** You also need Python 3, Node.js/npm, Docker Desktop and Chrome/Edge/Chromium.

Run from the project folder:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\qa\run_complete_qa.ps1
```

The test script creates temporary test data, checks the Java API, security rules and all 14 pages, then saves reports inside `qa-reports/`. It does **not** publish posts or change your real accounts.

**GitHub Actions:** Open **Actions → PostFlight Complete QA** to see test runs. The daily 8:00 AM India-time schedule and one-retry failure guardian are now installed on **main**. GitHub may delay scheduled jobs. Follow the [scheduler guide](docs/CI_SELF_HEALING.md) to check and test them; real unattended recovery is **not yet verified end to end**.

## 5. What works, and what does not

**Works in the current `main` branch:** local drafts, checks, custom rules, review decisions, local approval requests, policy snapshots, workflow previews and step edits, image uploads, incidents, proof records, a read-only public demo and test scripts.

**Not ready for real users:** login, secure shared team workspaces, sending invitation emails, real publishing or scheduling, and production data storage.

- **[PR #5](https://github.com/BackendArchitectX/postflight-enterprise/pull/5):** work in progress — Cognito login, secure workspaces and roles. Real login is not tested with an AWS user pool yet.
- **Scheduled CI ([merged PR #6](https://github.com/BackendArchitectX/postflight-enterprise/pull/6)):** scheduled checks, bounded retries and issue reporting are installed. Their real-world trigger/recovery behavior still needs a controlled live test.
- **Real Zernio data:** possible read-only inspection using a restricted key, but live checks against a connected social account have not been verified.

**Safety:** The online demo cannot save changes. The editable local app is for one developer, not for public hosting. Use test content, never social-media passwords or unrestricted API keys.

## 6. More help

- **[Every workflow, explained simply](docs/WORKFLOW_GUIDE.md)**
- [Daily GitHub testing and failure recovery](docs/CI_SELF_HEALING.md)
- [How it is built](docs/ARCHITECTURE.md) · [API details](docs/API.md) · [Safety notes](docs/SECURITY.md)
- [Figma design](https://www.figma.com/design/vj7eawzLm6VzDWtF2niFdC)
- [GitHub issues](https://github.com/BackendArchitectX/postflight-enterprise/issues)

**In one sentence:** PostFlight helps you **write → check → fix → review → save proof**, without sending anything to social media.
