# PostFlight Enterprise — Local Working Application

**A light-theme content-release assurance workspace.** Create campaigns and drafts, validate social copy, review policy violations, add custom rules, upload media, record decisions, issue locally verifiable release certificates, and explore twelve application sections.

**Status:** functional **local-development build** (v0.1). **Not an enterprise production deployment.** This archive intentionally does **not** implement user authentication, tenant isolation, actual social publishing, cloud storage, external messaging, complete media/transcoding validation, or enterprise-grade retention/WORM evidence. Do not put it on a public-facing network.

## Quick start — Windows 10/11 (IntelliJ-friendly)

1. Extract the ZIP to `D:\PostFlight_Enterprise` or another folder of your choice. **Extract everything**, not just `index.html`.
2. Have **JDK 17 or later** installed, with `java` and `javac` on `PATH`. (JDK 21 or 25 is fine; compilation targets Java 17.)
3. Double-click **`run.bat`** in the extracted folder. Alternatively open PowerShell in the project root and run `powershell -ExecutionPolicy Bypass -File .\run.ps1`.
4. Visit **http://127.0.0.1:8080/** in Chrome, Brave or Edge. Keep the terminal open; press **Ctrl+C** to stop.

No Maven, Gradle, Node.js, database, cloud account, API key, Lovable credits or Docker is required for **this local mode**. Do not open the HTML file directly from the file system: the app's real features require the local API process.

## macOS / Linux / WSL

```bash
cd PostFlight_Enterprise
./run.sh
# Open http://127.0.0.1:8080/
```

Windows PowerShell example:

```powershell
cd D:\PostFlight_Enterprise
javac -version
.\run.bat
```

**If port 8080 is already in use:** On Linux/WSL run `./run.sh 8090`. For Windows run `java --add-modules jdk.httpserver -Dpostflight.root="D:\PostFlight_Enterprise" -cp build/classes com.postflight.local.Main 8090` after a successful compilation, or change the final `8080` parameter in `run.ps1`.

### IntelliJ IDEA

- **File → Open** the extracted `PostFlight_Enterprise` directory.
- In **Project Structure**, use **SDK 17+**.
- To run the dependency-free local backend, add a Java run configuration with main class `com.postflight.local.Main`, working directory set to the repository root, VM option `--add-modules jdk.httpserver`, and a module classpath containing `backend/src/main/java` after importing the sources into an IntelliJ Java module. The easiest path remains the built-in **`run.bat`** from IntelliJ's Terminal.
- The optional Maven/Spring Boot entry point is `com.postflight.spring.PostFlightApplication`; see below.

## What is implemented?

| Capability | Current behavior |
| --- | --- |
| 12-section premium light UI | **Working navigation** and designed module screens |
| Create, edit and select drafts | **Working server-side persistence** in a local JSON store |
| Autosave and draft versioning | **Working**, edits invalidate approval; API accepts `expectedVersion` for optimistic concurrency |
| Platform and financial claim checks | **Working deterministic local rule engine**; limited conservative sample checks |
| Policy Studio | **Working custom literal-phrase rules** and server-side simulated impact; other policy publishing controls are illustrative |
| Content review | **Working draft-level approval / changes requested** with blocking rule enforcement; workflow graph remains a simulator |
| Release certificates | **Working local HMAC-SHA256 evidence seal**; no external identity or regulatory signature |
| Audit trail | **Working hash-linked, persisted event log** with verification endpoint; **not tamper-proof/WORM** |
| Media Intelligence | **Working local PNG/JPEG uploads** up to 700 KB and dimensions/alt-text metadata; thumbnail/asset previews partly illustrative |
| Incidents | **Working status resolution** for seeded incidents; external escalation not enabled |
| Workflow Builder | **Working server-side route simulation**; canvas interactions illustrative; not an execution engine |
| Dashboard and visual analytics | **Illustrative seed metrics**, clearly distinguished from actual local operations |
| Integrations | **Actual local REST API**; Zernio, outbound webhooks and publishing **disconnected by design** |
| Team roles/workspaces | **UI demonstration only**; no enforced authorization or tenant separation |

### Core end-to-end workflow

1. **Content Studio → New draft**; enter an example such as `Guaranteed returns on your investment!` and optional image alternative text.
2. **Run preflight**: the Java backend returns a critical claims finding and a high missing-risk-disclosure finding. Blocking rules cannot be ignored by an approval call.
3. **Edit the draft**, removing unsupported promises and adding relevant disclosure if needed; rerun preflight to obtain `PASS` or non-blocking `WARN`.
4. **Review Center**: select the same draft from the inbox and choose **Approve selected draft**. The server checks a current, matching validation run.
5. **Release Center → Issue certificate**: issues a local evidence record with content hash, draft version, policy version, timestamp and HMAC signature. It does **not** publish or schedule a post.
6. Return to the editor and edit the approved draft: the previous approval is invalidated. The historical certificate remains verifiable for the original immutable evidence version.

The local files are stored in `data/postflight-data.json` and `data/media/`. API changes persist across application restarts. Delete `data/postflight-data.json` while the server is stopped or use the application's reset control to restore seed content.

## Backend runtimes

### Option A — Verified standalone Java 17 local runtime (default)

- `backend/src/main/java/com/postflight/core/` contains domain logic, JSON encoding, rule evaluation, audit state and API routing.
- `backend/src/main/java/com/postflight/local/` is a loopback-only Java HTTP server backed by JDK `jdk.httpserver`.
- `web/` contains the approved frontend, with separate HTML, styles and JavaScript.
- `run.bat`, `run.ps1` and `run.sh` compile the Java code with `--release 17` and start the server.

### Option B — Spring Boot 4.1.2 adapter (source included; Maven build not run here)

When an internet connection and Maven 3.6.3+ are available, run from the repository root:

```bash
mvn -f backend/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="--postflight.seed-file=./docs/seed.json --postflight.data-directory=./data"
```

Spring Boot uses `backend/src/main/java/com/postflight/spring/` and delegates to the **same core implementation**. The UI is bundled under `backend/src/main/resources/static/`. If Maven executes the app with the module directory as the working directory, supply `../docs/seed.json` and `../data` instead, or set absolute paths in `application.properties`. **The Spring Boot adapter has not been compiled/tested here because Maven dependencies cannot be downloaded in this environment.** Use the verified Java SE runtime for a reliable immediate start.

## Tests

For macOS, Linux or WSL with Java 17+, Python 3 and Node 18+:

```bash
./scripts/run-tests.sh
```

Runs a clean temporary local server, plus API tests covering draft lifecycle, validation blocking, stale version prevention, release evidence, custom policy rules, image metadata validation, incident updates and audit integrity. The frontend's all-route navigation and core API-driven journey are exercised using a Node VM harness; the browser visual test is provided separately but is subject to browser availability and local security policy.

## Security and production hardening

The server listens on **127.0.0.1**, enforces JSON requests for mutations, refuses cross-origin writes, limits request size, checks image signatures, and exposes no user-provided Zernio credentials. **It has no login or actual multi-user security**. Never expose it to the internet or enter sensitive organizational content without assessing your device security. The sample financial rules are not legal or platform-policy guarantees.

Before commercial deployment, the essential work includes OIDC/OAuth2 authentication, enforced tenant and role boundaries, PostgreSQL and migrations, durable media storage, distributed audit retention, signed provider webhooks, outbox/idempotent jobs, contract tests against actual provider APIs, a React/TypeScript migration from the current light-theme frontend, observability, CI/CD, secrets management, vulnerability scanning, verified accessibility and production load tests.

See [Architecture](docs/ARCHITECTURE.md), [API Reference](docs/API.md), and [Security limitations](docs/SECURITY.md).

## Reference UI

Design source: [PostFlight Enterprise in Figma](https://www.figma.com/design/vj7eawzLm6VzDWtF2niFdC)

This is a user-owned local project delivered for continued development. No Lovable credits are required.
