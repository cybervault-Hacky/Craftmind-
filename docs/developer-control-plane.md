# Developer Control Plane and AI Foundation (Phase 19)

Phase 19 adds a separate, server-authorized developer identity and protected administrative surface alongside—not inside or in place of—the normal CraftMind account system. Ordinary user access/refresh credentials, Android state, request headers, and client-selected role values never grant developer authority. The Android app's four destinations remain Home, Builds, Minecraft, Settings; the public site remains the existing eight pages and has no link to this surface.

The service is implemented in `backend/` and serves its separate sign-in/dashboard shell at `/developer`. That shell is only an entry point: all data/action APIs require a valid developer bearer token. This is not a public website page. The repository does not configure or deploy a production service.

## Initial owner bootstrap

The only initial-owner creation path is `POST /developer/auth/bootstrap`. Before starting the service, set both environment variables:

* `CRAFTMIND_DEV_BOOTSTRAP_EMAIL` — the email of the first developer identity.
* `CRAFTMIND_DEV_BOOTSTRAP_SECRET` — a one-time random base64url value of at least 32 random bytes (43 characters).

Generate the secret out of band, for example with a cryptographically secure 32-byte random source encoded as unpadded base64url. Inject it through the deployment's secret manager, never Git, the Android build, website assets, or a shared shell history. In production, use the configured HTTPS front door and trusted proxy boundary. Submit the one-time secret and the new owner-chosen password at `/developer`; the service uses the existing scrypt password-hashing policy. There is no environment password, and normal login uses only the developer email/password.

On success, the service atomically creates the first `OWNER` and a durable consumed marker. Concurrent attempts serialize; later bootstrap attempts fail. The secret is neither stored nor returned/logged. The environment variable is **not automatically erased**: remove it from the deployment environment/secret injection immediately after successful bootstrap. Once consumed, removing it does not affect normal login. There is no public developer-registration or additional-developer provisioning endpoint. A lost owner password has no in-app reset/break-glass flow in this phase; do not treat a fresh environment bootstrap secret as a normal login or recovery password.

Bootstrap fails closed if the paired configuration is absent/malformed, if the supplied authorization is wrong, or if the persistent state is already consumed. The bootstrap rate limiter is separate from developer login and protected API limits.

## Developer authentication and sessions

Routes under `/developer/auth/` are separate from `/auth/`:

| Route | Contract |
| --- | --- |
| `POST /developer/auth/bootstrap` | One-time environment-authorized creation; accepts only `secret` and `password`. |
| `POST /developer/auth/login` | Accepts only `email` and `password`; returns safe developer identity and an access/refresh pair. |
| `POST /developer/auth/refresh` | Rotates both opaque credentials using the refresh token; replay of the old refresh credential fails. |
| `GET /developer/auth/me` | Requires a developer access bearer; returns safe identity and current-session metadata. |
| `POST /developer/auth/logout` | Requires the current developer access bearer and revokes that session. |
| `GET /developer/auth/sessions` | Lists the current developer's active sessions using safe metadata only. |
| `POST /developer/auth/sessions/revoke` | Revokes one other developer session; a session cannot revoke itself through this route. |

Access and refresh credentials are random opaque values, stored only as purpose-separated HMAC-SHA256 digests, rotated atomically on refresh, and checked against server-side expiry/revocation and active developer status. Access TTL defaults to 15 minutes (configurable up to one hour); refresh TTL defaults to seven days (up to 30 days). The normal user-token digest namespace/table is not accepted by these handlers. Developer session listing never returns token digests, raw credentials, password hashes, or request fingerprints. Successful developer logout and other-session revocation append audit events.

Developer roles are enforced by the server: the bootstrapped owner can use all registered tools and configuration status; an `ADMIN` can use the administrative tools except owner-only configuration status; a `DEVELOPER` is read-only. There is no endpoint that trusts or accepts a caller-selected role. Role checks occur both when an action is prepared and again at confirmation.

## Registered administrative tools

The server exposes only a closed registry in `backend/src/admin-tools.js`; the AI adapter and dashboard never receive a database handle or executable function. Inputs use strict object schemas with bounded values. There is no generic SQL, shell, source-code execution, arbitrary DB access, credential reset, mass email, or Minecraft control tool.

| Tool | Access/effect |
| --- | --- |
| `overview` | Safe aggregate counts for users and active sessions. |
| `inspectUser` | Exact-email lookup; safe account metadata only. |
| `listUserSessions` | Bounded active-session metadata; no raw session IDs/tokens, addresses, or fingerprints. |
| `revokeUserSessions` | High-impact; prepares a one-time confirmation before revoking all sessions for one user. |
| `suspendUser` | High-impact; prepares confirmation, then suspends one user and revokes all active sessions in the same audited transaction. |
| `restoreUser` | High-impact; confirmation is required; previously revoked sessions remain revoked and the user must sign in again. |
| `listEntitlements` | Reads metadata for the independent preview-grant foundation. |
| `grantEntitlement` / `revokeEntitlement` | High-impact; confirmation required. Only `BETA_ACCESS`, `PREVIEW_ACCESS`, or `PROMOTIONAL_ACCESS` grants are allowed, with bounded expiry. These records do not activate a feature or implement subscriptions, credits, payments, or monetization. |
| `listAuditLog` | Reads bounded safe audit events. |
| `configurationStatus` | Owner-only non-secret runtime/bootstrap/provider-availability metadata. Secret values are never returned. |

A state-changing tool call only creates a short-lived challenge (default five minutes). The confirmation token is returned once to the authenticated operator and stored only as a keyed digest; the confirmation API accepts only that token, rechecks the actor and role, consumes the challenge, validates the resolved target/arguments again, and executes the operation plus its audit write in one transaction. AI output cannot confirm an action. Failed, denied, expired, replayed, and canceled challenges are recorded when there is an authenticated actor; a failed high-impact attempt consumes the challenge and does not apply the mutation.

User suspension and session revocation are transactional with their audit event. A suspended user cannot authenticate; restoration does not restore old sessions. Grant rows are independent administrative metadata and are not exposed by ordinary account APIs or used for product entitlements in this phase.

## Audit and persistence

SQLite migration version 3 adds:

* `developer_accounts` — separate identity/password hash/role/status.
* `developer_sessions` — opaque session references, token digests, expiry and revocation metadata.
* `developer_bootstrap_state` — durable single-use bootstrap marker.
* `admin_audit_log` — typed actor/action/optional target/time/outcome and bounded safe JSON metadata.
* `developer_action_confirmations` — actor-bound, short-lived challenges with digest-only tokens and server-resolved arguments.
* `developer_access_grants` — optional bounded, independent administrative preview-grant records.

Audit update, delete, and replacement triggers make records append-oriented through the application database connection. Mutations and success audit writes share transactions; failed operations roll back their changes and commit only safe failure audit metadata. Audit metadata deliberately excludes passwords/hashes, access/refresh/reset/verification/confirmation tokens, provider keys, Minecraft credentials, client IPs, user agents, and AI prompt text. AI tool name/result classification may be audited, but the prompt itself is not stored. This is application/database-level append protection, not a cryptographically tamper-evident log against an operator who can directly rewrite the SQLite file or alter its schema.

### Phase 20 additions

Schema v4 adds the isolated security tables (`security_incidents`, `security_events`, `security_actions`, `security_notifications`, `security_rate_limit_state`) and rebuilds `admin_audit_log` with an explicit `actor_kind` (`DEVELOPER`, `SYSTEM_SECURITY`, `AI`) and an `incident_id`, preserving every existing row and its append-only triggers. The dashboard gains a **read-only** Security Center backed by seven role-checked tools; the eight automated response tools are never reachable from a developer session or from the AI, and only the security policy can authorize them. Full contracts: [`autonomous-security-response.md`](autonomous-security-response.md).

## Provider-neutral Developer AI

`backend/src/developer-ai.js` defines an adapter boundary, not a bundled provider integration. The trusted service composition root may inject an adapter exposing `selectToolCall`. It receives only the operator's bounded prompt, role-filtered registered JSON schemas, and a one-tool-call limit—never DB handles, account/session rows, service configuration, credentials, or secret values. The response is validated, only one registered tool can be proposed, and the server repeats all authorization/input checks. High-impact calls stop at the same manual confirmation path as direct dashboard actions.

`GET /developer/ai/status` is a protected, rate-limited availability check that returns only whether an adapter is installed. `CRAFTMIND_DEVELOPER_AI_PROVIDER`, `CRAFTMIND_DEVELOPER_AI_MODEL`, and `CRAFTMIND_DEVELOPER_AI_API_KEY` are optional paired environment metadata, but configuring them alone **does not activate a provider**. No provider adapter or live integration ships in this phase. With no adapter, the AI turn route returns `DEVELOPER_AI_UNAVAILABLE`; status explicitly reports adapter unavailable. Backend tests use deterministic fake adapters. No real provider request or model integration is claimed.

## Service protections and limitations

* JSON bodies are bounded; developer endpoints have distinct bootstrap, login, session, admin, and AI rate limits. Buckets are HMAC-keyed and process-local; horizontal deployments need trusted edge/global limits. Process restart resets the buckets.
* Developer access APIs require authorization on every request; same-origin dashboard assets use no-store, a restrictive CSP, no cookies, and no local/session storage. Production HTTPS/TLS proxy and exact-origin CORS gates remain active.
* Logs use fixed route labels and request IDs and discard body/provider exception text. The browser holds credentials only in a tab's JavaScript closure and clears them on sign-out; UI output uses text rendering.
* The public website, Android source/APK, Minecraft/BuildPlan routes and four top-level Android destinations are unchanged by this phase.
* The account deletion path remains unavailable. There is no developer-password recovery, staff provisioning UI, distributed rate limiter, real AI provider, payment/subscription/credit/marketplace flow, full moderation platform, developer mobile app, or production deployment.

## Verification

`cd backend && npm test` includes deterministic HTTP/SQLite tests for bootstrap/replay/concurrency, secret removal before normal login, session digest/rotation/revocation, ordinary-user isolation, roles, explicit tools, confirmation/cancel/expiry/replay, suspension/restoration, grants, append-oriented audits, safe dashboard headers, and fake-provider proposals/errors. Phase 20 adds deterministic suites for the security event boundary, detection rules, risk scoring, autonomous response and its authorization boundary, the read-only Security Center, and the message-only AI security summary. Android build/device tests and live provider/network/deployment checks are separate and are not implied by backend test success.
