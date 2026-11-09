# Security boundaries and known limitations

This version is a **single-user, localhost-only developer build**. It must not be treated as security-accredited or production ready.

### Implemented safeguards

- Listens only on `127.0.0.1` in the Java SE runtime; sends no permissive CORS headers.
- Rejects cross-origin mutation requests with an `Origin` outside the local listener.
- Rejects non-JSON mutation bodies and requests larger than 1 MiB.
- Uses conservative input-length checks and specific allowed states for review, incidents, and policy severity.
- Serves only files inside the `web/` directory; prevents static path traversal.
- Does not accept, persist, or call Zernio credentials.
- Does not enable any publishing actions.
- Validates PNG/JPEG file signature, decodability and maximum pixel dimensions before storing binary files.
- Certificates use HMAC-SHA256 with a local randomly generated secret, compared using constant-time equality.

### Not implemented — blockers for external exposure

- **No authentication** and no real role or tenant authorization. Local users can call administrative reset, add policy rules and approve drafts.
- No signed/strongly authenticated approvals; reviewer names are only labels.
- No TLS, CSRF token framework, rate limiting or IP firewall beyond loopback binding.
- Local files are not a tamper-proof audit system and are not durable under machine loss; file writes are atomically renamed but not necessarily fsynced.
- Limited deterministic policy coverage, not official social-network capability certification; X character count is a simplified code-point estimate.
- Uploaded media is local, not virus-scanned or content-moderated. Media previews are partially illustrative.
- No business-continuity plan, backup, SOC2/ISO controls, GDPR/DPDP workflow or regulator-approved record retention.

**Recommendation:** Do not share the server on a LAN, reverse-proxy it, use real customer content, or enable production publishing until the above gaps are addressed. The optional Spring Boot adapter similarly has no auth/security configuration and must not be exposed.
