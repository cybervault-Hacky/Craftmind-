# Autonomous security response (Phase 20)

Phase 20 adds a self-contained backend security layer to the CraftMind account service. It watches authentication,
session, rate-limit, authorization, and request patterns, and answers a small set of predefined, bounded threats
immediately — with no human, no AI provider, and no external service anywhere in the detection or response path.

Everything here is local to the account-service process and its SQLite database. No third-party security service is
contacted, no email/SMS/push is sent, and the AI boundary can never approve, request, or execute a security action.

## Event model

All security-relevant failures enter one normalized event model (`backend/src/security-events.js`):

- 15 registered event types (authentication failed/succeeded, session reuse and refresh failures, rate-limit
  violations, unauthorized and confirmation attempts, malformed security requests, suspicious account activity,
  high-impact action failures, request bursts) with 8 source categories and a validated result vocabulary.
- Strict normalization: an unregistered type, an oversized payload, or a malformed correlation id is rejected instead
  of stored (`SECURITY_EVENT_INVALID`). Metadata is JSON-capped at 2048 characters and credential-shaped values
  (passwords, tokens, `Authorization` headers, API-key fields, email addresses) are replaced before storage.
- No raw client addresses or account identifiers are stored. Sources are keyed through
  `securitySourceDigest` (HMAC-SHA256), unknown-account failures through `securityAccountDigest` (`ref_` + digest), and
  known subjects through their existing opaque ids (`usr_`, `ses_`, `dvl_`, `dvs_`).
- A short ingest deduplication window collapses a flood of identical signals into one stored row whose `signal_count`
  keeps the true volume, so detection stays accurate while storage stays bounded.

## Detection and risk

`backend/src/security-detection.js` counts stored signals per subject (account, session, or source) inside a bounded
window and compares the count with centralized thresholds. Rules are intentionally simple and explainable, so every
escalation is justified by the count that produced it ("25 authentication failures in 2 minutes").

| Rule | Window | Medium / High / Critical |
| --- | --- | --- |
| Brute force (user sign-in failures) | 120 s | 5 / 12 / 25 |
| Developer sign-in failures | 120 s | 3 / 6 / 12 |
| Session credential reuse | 300 s | 8 / 15 / 25 |
| Rate-limit violations | 600 s | 3 / 8 / 15 |
| Unauthorized-access attempts | 600 s | 5 / 10 / 20 |
| Invalid confirmation attempts | 600 s | 3 / 6 / 12 |
| Malformed security requests | 300 s | 5 / 10 / 20 |
| Request bursts (per source, 60 s) | 60 s | 120 / 240 / 400 |

Session reuse watches *sustained* activity because stale or revoked credentials are routinely retried by clients
after a logout, an app restart, or an expired access token.

`backend/src/security-risk.js` converts an assessment plus bounded context (concurrent signal families, recent
incidents, sensitive-route traffic) into a level and a 0–100 score with human-readable reasons. The threshold band is
the floor; two or more independent concurrent signal families raise the level by exactly one band. No AI output
participates in this calculation.

## Response policy

`backend/src/security-policy.js` is the only place that decides what the system may do, and the only issuer of
security-action authorization. Plans walk a progressive ladder:

- **MEDIUM** — tighten the subject the rule actually attributed the signal to (account, session, or source).
- **HIGH** — the same subject, tightened further, plus the subject-specific remedy (revoke the suspicious session).
- **CRITICAL** — the subject remedy, the stronger account protection where an account exists, a temporary rejection of
  the abusive source, and one developer alert.

Automated measures are always temporary and reversible: every duration is clamped to
`SECURITY_MAX_PROTECTION_SECONDS` (default 3600 s), a hard rejection (`DENY`) is only ever issued for a request
`SOURCE` (enforced by validation and by a database CHECK), and no plan can delete, ban, suspend, grant, or change
configuration. The registry is a closed set of eight tools — create/escalate an incident, apply/tighten a rate limit,
reject an abusive source, revoke a session, protect an account, alert the developer, release a protection, resolve an
incident — and every execution additionally requires an unexpired authorization envelope minted by the policy for that
exact tool (TTL ≤ 60 s).

## Enforcement

Active protections are checked before security-sensitive routes (`/auth/*`, `/developer/*`) run:

- `DENY` throws the existing typed `RATE_LIMITED` error (429 with `Retry-After`), never a bespoke response.
- `THROTTLE` consumes the tightened bound from the same per-process rate limiter, so the stricter limit governs and no
  privileged path is granted.
- Route classification is precise (`USER_AUTH`, `SESSION`, `DEVELOPER_AUTH`, `DEVELOPER_ADMIN`, `CONFIRMATION`,
  `REQUEST`, `ANY`), so a session protection cannot slow down unrelated routes such as email verification.
- A request rejected by an autonomous protection is **not** recorded as fresh abuse evidence; otherwise the defensive
  response would escalate itself into new incidents.

Incidents deduplicate on (category, subject): a sustained attack escalates one active incident (and its reasons) rather
than creating a series of duplicates. `backend/src/security-engine.js` also caps its in-memory tracking maps, resolves
quiet incidents, releases their protections, and expires stored history on a bounded schedule.

## Audit trail

Every automated action is written to the append-only `admin_audit_log` with `actor_kind = SYSTEM_SECURITY`,
`incident_id`, and a `NULL` developer id — an automated action is never attributed to a person. Benign declines to act
(duplicate incident, already-tightened protection, notification inside its cooldown) are recorded as `SUCCESS` skips;
genuine failures are `FAILURE`. Incidents carry their detection reasons, risk score, actions, related events, and the
audit records that reference them, all readable from the dashboard.

## Developer Security Center

The Phase 19 developer dashboard gains a Security Center panel backed by seven **read-only** registry tools
(`securityOverview`, `listSecurityIncidents`, `getSecurityIncident`, `listSecurityEvents`, `listSecurityActions`,
`listSecurityNotifications`). They require a developer session, are role-checked, have strict bounded schemas (page
sizes ≤ 100, events ≤ 200), and write one `DEVELOPER` audit record per call. The automated response tools are not
reachable from a developer session or from the AI: only the security policy can invoke them.

The optional AI summary (`POST /developer/ai/security-summary`) receives a bounded, sanitized brief (counts, statuses,
categories, reasons, registered tool names) and may return prose only — `MESSAGE_ONLY`, zero tools, no authorization.
A provider that proposes a tool call in this mode is refused (`DEVELOPER_AI_RESPONSE_INVALID`), and the summary is
audited as an AI action attributed to the developer who asked. When no provider is configured the endpoint reports
`DEVELOPER_AI_UNAVAILABLE` and detection and protection keep working unchanged.

## Retention and bounds

Events are retained for 30 days, unread/read notifications for 90 days, resolved incidents for 180 days, and released
protections for 7 days; cleanup runs hourly and at startup. Incident reasons are capped (≤ 6 per incident, ≤ 200
characters), metadata is capped at 2048 characters, query pages are bounded, and the deduplication/burst maps are
capped in memory.

## Deliberately not implemented

No permanent ban, suspension, account deletion, privilege or role change, configuration change, source modification,
credential display, arbitrary SQL/shell/source execution, third-party notification integration, or behavioural
tracking beyond the stored security records. The system never blocks an account from signing in through a hard
mechanism: account protections are temporary throttles, and only a request source can be temporarily rejected.

## Configuration

`backend/.env.example` documents every knob: the eight detection rules
(`SECURITY_<RULE>_WINDOW_MS`, `_MEDIUM_MAX`, `_HIGH_MAX`, `_CRITICAL_MAX`), protection bounds
(`SECURITY_DEFAULT_PROTECTION_SECONDS`, `SECURITY_MAX_PROTECTION_SECONDS`), and the tightened throttle
(`SECURITY_THROTTLE_MAX`, `SECURITY_THROTTLE_WINDOW_MS`). Thresholds must satisfy medium < high < critical or the
service refuses to start.

## Verification status

`cd backend && npm test` covers the event boundary, detection, escalation, deduplication, progressive response,
enforcement, reversibility, retention, the policy/tool authorization boundary, the read-only Security Center, and the
message-only AI contract — all local and deterministic, with no external system. `python3 scripts/check_website.py` and
`python3 scripts/check_release_config.py` remain green. No live attack simulation against a running deployment, no
external security review, and no multi-instance/shared-state testing are claimed.
