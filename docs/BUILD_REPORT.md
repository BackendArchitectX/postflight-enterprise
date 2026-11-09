# Build and verification report — local application v0.1

- **Java source compatibility:** `javac --release 17` completed successfully using installed OpenJDK 21.
- **Java SE backend:** started and responded to HTTP requests on loopback interface.
- **Frontend:** external HTML/CSS/JS resources served successfully; `node --check` passed.
- **API tests:** full draft, validation, edit/approval, release, audit, policy, media and incident scenarios passed using a fresh temporary workspace.
- **Security-boundary tests:** cross-origin mutation rejection, JSON content type checks, payload size validation, missing-route responses and secret redaction passed.
- **JavaScript frontend VM tests:** all 12 route templates rendered; server-backed create→edit→validate→remediate→review→release scenario passed without runtime errors.
- **Visual/browser tests:** not completed in the container. Headless Chromium navigation is blocked by this environment's browser policy (`ERR_BLOCKED_BY_ADMINISTRATOR`). The reference light UI screenshots in this archive are visual examples from the prior design preview, not screenshots of the current live application. Users should review in their own browser.
- **Spring Boot:** source adapter and Maven 4.1.2 dependencies included; **not compiled** (no Maven installation or Maven Central network access in this container). Do not interpret the Java SE test results as Spring Boot runtime test results.
- **Zernio:** no live account connection or external publishing attempted. Integration explicitly disconnected.

## Local acceptance criteria

- Draft creation/edit persists across Java process restarts.
- A stale `expectedVersion` update returns HTTP 409.
- Critical/high validation findings prevent approval; stale validations also prevent approval or release.
- A clean draft with descriptive alternative text can be approved and issued a local HMAC evidence certificate.
- Changing the content or custom policy after validation invalidates new release eligibility.
- Locally issued evidence certificates verify even after later edits to the draft (historical version preserved).
- The audit chain verification endpoint reports validity under normal operation.
- PNG/JPEG metadata extraction rejects mismatched MIME signatures.
- All 12 UI templates render and core application events drive real API calls.
