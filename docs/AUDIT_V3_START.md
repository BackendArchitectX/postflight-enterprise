# PostFlight V3 audit — starter implementation (not exhaustive)

This package implements a safe **initial portion** of the V3 mandate:

- Fixes the confirmed Workspaces heading interpolation defect.
- Adds a regression check for that defect.
- Creates a static DOM/API inventory; all entries remain **NOT_TESTED** until independently asserted.
- Runs the existing Java workspace and draft regressions and frontend JavaScript syntax checks.
- Runs the existing isolated complete QA using its own disposable storage when Docker is available.
- Writes a timestamped summary that explicitly lists untested categories.

**Not delivered yet:** complete reachable CTA coverage, automated live Clerk two-user browser flows, every API contract, production-configuration security review, 3-browser visual regressions, crash-consistency tests, performance benchmarks, WCAG manual evaluation, and full V3 release certification. Do not infer production readiness from a green run.

## Install safely from project root in IntelliJ PowerShell

Extract the ZIP from an already-downloaded location into your repository's parent folder or another temporary folder (not inside your tracked Git files). Example after extraction:

```powershell
py "C:\path\to\postflight_audit_v3_patch\apply_patch.py" --project .
```

The installer refuses to overwrite existing audit scripts, checks the feature branch and exact known UI defect, and backs up `app.js` **outside the repository**. It never touches saved local data or pushes code.

Run from a separate PowerShell terminal (it doesn't need the live Clerk session):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\qa\audit_everything.ps1 -DryRun
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\qa\audit_everything.ps1 -Mode Quick
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\qa\audit_everything.ps1 -Mode Complete
```

`Complete` uses the project's existing QA runner and requires Docker, Node, Python, Java and browser prerequisites. It refuses occupied QA ports and an existing reserved QA container instead of forcibly killing resources.

`Extended` is currently a deliberately **failing placeholder** for unimplemented advanced coverage. `LiveAuth` deliberately returns `BLOCKED`, rather than attempting real Clerk authentication without the user's supervised test profiles. `ReportOnly` reads the newest prior report. See `qa-reports/exhaustive-*/SUMMARY.md` for accurate scope.

Verify changes and keep PR #17 in draft during review:

```powershell
git diff --check
git status --short
git diff -- web/assets/app.js
```

Do not stage installer folders, backups, `data/`, QA reports or secrets. Do not merge or deploy solely on the basis of these starter tests.
