# PostFlight CI self-healing scheduler — operating runbook

## Schedule and guarantees

- **Daily at 08:00 IST (02:30 UTC):** PostFlight Complete QA runs on the latest default-branch `main` commit.
- **Every PR targeting `main` and every `main` merge:** Complete QA runs immediately after GitHub queues the event. Product Workflows runs on PRs and pushes to `main`.
- **09:10 and 12:10 IST:** a separate heartbeat checks for the daily scheduled Complete QA run. If its latest scheduled start was more than 25 hours ago, it creates or updates a deduplicated GitHub issue. It closes that issue when scheduled QA resumes.
- **Every failed internal-repository CI run:** the CI Guardian creates or updates a GitHub issue with the workflow, commit, failed job/step and run link. It retries **failed jobs once**, then stops. After a successful subsequent run it closes the issue with a recovery comment. For repeat failures the issue remains open.
- **Each Monday at 08:30 IST:** Dependabot proposes updates to SHA-pinned GitHub Actions for human review.

Schedules use GitHub Actions and **run in GitHub's cloud**, not on the user's Windows desktop. GitHub can delay or drop scheduled events; the heartbeat adds coverage but is also a GitHub scheduled workflow, not an independent always-on monitor. A separate external watchdog is necessary for hard delivery SLOs.

The workflow files only become active for scheduled and `workflow_run` events after they are **merged into the repository default branch**. Adding them to a draft PR does not start unattended scheduling.

## Auto-recovery policy

| Problem | Automated behavior | Needed human action |
|---|---|---|
| Temporary runner, browser download or network failure | Record incident, rerun failed jobs **once** | Review if recurring |
| Flaky test that passes on retry | Issue closes with a note about possible flakiness | Fix flaky test separately |
| Compile/test/security regression | Incident + one bounded retry, then remain failed | Create and review repair PR; rerun tests |
| QA did not run on schedule | Heartbeat issue after missed expected run | Check Actions status and dispatch manually |
| Security/authentication or publishing logic fails | Fail closed; never disable checks or rewrite access rules | Explicit review and remediation |
| GitHub token cannot create issue or rerun | Guardian job fails and retains its logs | Restore repository Actions/Issues permissions |
| GitHub Actions itself is down or scheduled events disabled | No guaranteed reaction | Independent monitoring required |

**No unattended source-code fixes or merges.** Even an AI agent cannot guarantee the correct repair for arbitrary failures. This design performs immediate bounded *operational recovery* and *escalation* while preserving code review and security boundaries.

The privileged `workflow_run` observer uses **GitHub metadata only** and checks out **no triggering pull-request code**, downloads no untrusted artifacts and executes no input from logs. It does not auto-retry CI originating from external forks. Its workflow token has scoped `actions: write` and `issues: write` only so it can retry GitHub jobs and record incidents. Public issues contain links to diagnostics but no raw log dumps or credentials.

## First activation / recommended GitHub settings

1. Merge the scheduler PR after all CI checks pass; verify `Actions > PostFlight Complete QA` shows the schedule and that `Actions > PostFlight CI Guardian` exists.
2. In GitHub **Settings > Actions**, allow GitHub Actions to create issues and rerun jobs using the repository `GITHUB_TOKEN` permissions specified in each workflow. Some organization policies can override these settings.
3. Configure `main` branch protection or a ruleset with required **PostFlight Complete QA** and **PostFlight Product Workflows** checks, and require human pull-request review. Do not enable automatic production deployment or unsupervised auto-merge.
4. Watch the repository or subscribe to GitHub issue notifications for prompt failure alerts. For hard real-time alerts, integrate an approved external incident service (not configured in this PR).
5. Use `workflow_dispatch` for a controlled initial smoke run after merge; verify the Guardian on an intentionally failing **non-production** test branch and ensure it creates one issue, retries at most once and does not write to source.
6. Review the `postflight-complete-qa` artifact, retained for 14 days, when investigating failures.

### Routine acceptance criteria

- QA and all security assertions remain unchanged: no skipped validations, disabled authorization gates, weakened Playwright checks or ignored CI exit codes.
- A failing run is never reported as green merely because a retry passed: the issue retains the original run link and records potential flakiness.
- Failure re-run requests never exceed one automated retry; the Guardian does not recurse into its own workflow.
- An issue remains open for a persistent CI failure until a later successful run.
- Branch protection and review apply to all repairs, including Dependabot PRs.
- Render remains public read-only. Live Cognito, Zernio publishing, external accounts and production credentials are not exercised by these tests.

## Developer checks

```bash
python3 scripts/qa/check_scheduler_security.py
bash scripts/qa/run_complete_qa_ci.sh
```

The Complete QA CI job automatically invokes the offline scheduler safety test before the Java/browser integration suite. Windows retains its existing complete QA launcher.

### Authoritative references

- [GitHub Actions schedule and workflow_run](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)
- [GitHub re-running failed jobs](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs)
- [GitHub Actions secure use](https://docs.github.com/en/actions/reference/security/secure-use)
- [Playwright CI](https://playwright.dev/docs/ci)
