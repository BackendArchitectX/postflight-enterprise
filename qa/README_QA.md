# PostFlight Enterprise — automated QA pack (Windows)

## Recommended one-command test

Extract this pack **into your existing `PostFlight_Enterprise` project folder**, next to the existing `run.bat` and `web` folders. Keep your current `data` folder unchanged.

Double-click **`RUN_ALL_POSTFLIGHT_TESTS.bat`**. Alternatively run in PowerShell:

```powershell
.\RUN_ALL_POSTFLIGHT_TESTS.bat
```

Requires **JDK 17+ only**, which is already needed to run PostFlight. No Python, Node, Maven, Playwright, Docker or API credentials are needed for this core suite.

The test runner automatically:

1. Compiles the actual Java 17 backend and QA test class.
2. Copies sample `docs/seed.json` and frontend resources to a **separate temporary workspace**.
3. Starts a temporary, private `127.0.0.1` server on a free port (not your port 8080).
4. Runs **213 distinct API/invariant/static-frontend checks** on that isolated copy.
5. Deletes its temporary test data and saves reports in `qa-reports/`.
6. If your own PostFlight is already running on port 8080, makes **GET-only read-only checks** of your actual local data, including issued certificate verification and whether PF-2103's latest validation still matches its current version. It does **not** mutate, approve, reset or issue releases on the live server.
7. Opens `qa-reports/POSTFLIGHT_QA_RESULTS.html` in your default browser.

## Reports

- `qa-reports/POSTFLIGHT_QA_RESULTS.html`: clear pass/fail results for 213 automated Java checks.
- `qa-reports/POSTFLIGHT_QA_RESULTS.csv`: spreadsheet-compatible results.
- `qa-reports/POSTFLIGHT_QA_RESULTS.json`: machine-readable results.
- `qa-reports/POSTFLIGHT_LIVE_READONLY.json`: your *own* local data diagnostics, if port 8080 is running.
- `qa-reports/POSTFLIGHT_COVERAGE_MATRIX.html`: honest classification of the original 148-case checklist.

## What this does *not* prove

The original checklist includes 148 **UI** and **API** cases. **213 assertions do not mean 213 checklist cases or all 148 UI cases have passed.** Most backend behaviors are verified, but visual rendering, accessibility with screen readers, real platform handoff, desktop/browser compatibility, and demo-only buttons require separate UI checks or product implementation.

### Optional real browser tests

A separate optional Playwright script is available: `RUN_OPTIONAL_BROWSER_TESTS.bat`. This is *not required* for the main runner. It requires Python 3, the `playwright` module and a Playwright Chromium installation:

```powershell
python -m pip install playwright
python -m playwright install chromium
.\RUN_OPTIONAL_BROWSER_TESTS.bat
```

It starts its own isolated local server and checks real Chromium navigation, dialogs, input, tabs and workflows. **This optional suite was not validated on this execution environment, because the managed browser blocked `127.0.0.1`; results must be checked on your Windows machine.** Any failures are logged in `qa-reports/POSTFLIGHT_BROWSER_RESULTS.csv`. Do not mistake the inability to open localhost in the managed test browser for a defect in your local app.

## Safety and limitations

- No production data or external credentials are used.
- The main suite never calls `POST /api/admin/reset` on your running application.
- Java tests use temporary state and never write to your existing `data/postflight-data.json`.
- The local certificate uses demo HMAC and is **not** a legal or regulatory digital signature.
- PostFlight has **no authentication**; do not expose port 8080 to the internet.
- If your local draft PF-2103 has advanced from version 7 to version 8 without revalidation, the read-only live check will flag a stale validation. This is expected safety behavior, not a crash.
