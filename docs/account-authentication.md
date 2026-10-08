# Accounts and Authentication (Phase 18)

This is the authoritative description of CraftMind's existing account architecture and its Phase 18 hardening: the
SQLite service, app/service contracts, guest identity, email verification, password recovery and change, remote session
management, production configuration, security boundaries, and features that remain unavailable. It extends Phases
16–17; it does not replace their canonical account/session model.

Related documents: [`premium-ui-ux-architecture.md`](premium-ui-ux-architecture.md) §9 (the Phase 16 account foundation)
and the repository [`README.md`](../README.md).

Scope note: Phase 18 implements only normal-user authentication and account security; that remains the contract of this document. Phase 19 later adds a separate developer identity/control plane documented in [`developer-control-plane.md`](developer-control-plane.md). Ordinary account sessions still grant no developer/admin authority. Memberships, credits, gifts, bans/full moderation, subscriptions, payments, marketplace, public user dashboards, Minecraft permissions, and moving BYOK keys to the service are **NOT IMPLEMENTED**.

---

## 1. Backend architecture

One technology, one process, no frameworks: a small **Node.js** service using only the standard library.

| Piece | File | Responsibility |
| --- | --- | --- |
| Configuration | `backend/src/config.js` | Validates `AUTH_SECRET`, persistent SQLite, email provider and HTTPS webhook, public origin, trusted TLS proxy, CORS origins, body limits, token TTLs, and per-operation rate limits |
| Errors | `backend/src/errors.js` | Closed safe error vocabulary, HTTP status mapping, and redacted service exceptions |
| Passwords | `backend/src/passwords.js` | scrypt hashing and verification (`N=16384, r=8, p=1`, 64-byte key, per-password salt), password policy, and constant-work unknown-account handling |
| Identifiers and tokens | `backend/src/ids.js` | Opaque `usr_`/`ses_` identifiers, 256-bit random one-time/session tokens, purpose-separated HMAC-SHA256 digests, constant-time comparison, and validation |
| Storage | `backend/src/db.js` | SQLite (`node:sqlite`) with WAL/foreign keys and transactional, versioned migrations; the Phase 18 account changes were v1→v2 and Phase 19 adds separate developer-control-plane schema v3 |
| Account logic | `backend/src/accounts.js` | Registration, verification, login, refresh/revoke, password recovery/change, safe session metadata, and guest linking |
| Email delivery | `backend/src/email-delivery.js` | Provider-neutral delivery, honest in-memory development sink, and production HTTPS JSON webhook adapter |
| Rate limiting | `backend/src/rate-limiter.js` | Bounded in-memory fixed-window limits with independent IP and keyed-email buckets; raw email/IP rate-limit keys are never retained or logged |
| HTTP | `backend/src/server.js` | Hardened routing, bounded JSON bodies, content type, CORS/HTTPS-proxy checks, security headers, throttles, and secret-free request logs |
| Entry point | `backend/src/index.js` | Configuration → database → listen → graceful shutdown |

**Storage schema (migrations v1–v2).** Three original account-domain tables are extended in place, two purpose-specific token tables are added, and `schema_migrations` records applied versions:

* `users` — original identifiers/email/hash/status/timestamps remain; v2 adds nullable `email_verified_at`. Existing `ACTIVE`, `SUSPENDED`, and `DELETED` values are not rewritten. Phase 17 accounts had no email-verification proof, so migrated accounts remain unverified and must use resend/verification before a new sign-in; their rows are not dropped or silently marked verified.
* `sessions` — original digest-only access/refresh tokens, expiry, revocation, user and guest link remain; v2 adds `last_used_at` and `device_label` with backward-safe defaults, then backfills `last_used_at` from `issued_at`. Existing session rows remain in SQLite, but the service refuses them until their account is verified.
* `guest_identities` — original anonymous identity/link data is preserved.
* `email_verification_tokens` and `password_recovery_tokens` — only purpose-separated HMAC-SHA256 token digests, user IDs, issue/expiry/consumption timestamps. Clear tokens are never stored in SQLite.

Every migration runs transactionally and is idempotently tracked. Existing users, sessions, guest identities, and account statuses are preserved. Phase 18 migration v2 contains only account/verification/recovery tables; Phase 19 migration v3 adds the separate developer/control-plane tables documented in [`developer-control-plane.md`](developer-control-plane.md). There are still no subscription, payment, credit, gift, ban/moderation, or marketplace tables.

`DATABASE_URL` is a SQLite file path (or `:memory:` in tests only). SQLite is chosen because it is embedded, has no
network, and needs no second service to run: the smallest thing that genuinely persists accounts.

---

## 2. API contracts

All bodies are JSON. Missing optional members are omitted, not `null`. Responses set `Cache-Control: no-store`, `Pragma: no-cache`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, a restrictive CSP, and `Permissions-Policy`; errors use `{"error":{"code":"…","message":"…","requestId":"…"}}` with a stable code and no stack/provider detail.

| Endpoint | Request | Success | Notes |
| --- | --- | --- | --- |
| `POST /auth/register` | `{email, password, displayName, guestIdentityId?, deviceLabel?}` | `201 {account, verificationRequired, deliveryStatus, guestLinked}` | No session is issued until email verification; status is `DEVELOPMENT_SINK`, `PROVIDER_ACCEPTED`, or `UNAVAILABLE`—none claims inbox delivery; duplicate email remains `ACCOUNT_ALREADY_EXISTS` |
| `POST /auth/verify-email` | `{token}` | `200 {account}` | Short-lived single-use code; consumes all active verification codes for that account |
| `POST /auth/resend-verification` | `{email}` | `202 {accepted, deliveryMode, message}` | Enumeration-neutral response; rate-limited |
| `POST /auth/login` | `{email, password, guestIdentityId?, deviceLabel?}` | `200 {account, session}` | Requires verified email. Unknown/wrong-password stay indistinguishable; unverified is a separate typed state |
| `POST /auth/refresh` | `{refreshToken}` | `200 {session}` | Atomically compares and rotates both tokens; a concurrent replay of the prior refresh digest loses the compare-and-rotate update |
| `POST /auth/logout` | `{refreshToken?, accessToken?}` | `200 {revoked, alreadyRevoked, revokedSessions}` | Idempotent |
| `GET /auth/me` | Bearer access token | `200 {account, session}` | Server is the only account/session authority |
| `POST /auth/password-reset/request` | `{email}` | `202 {accepted, deliveryMode, message}` | Enumeration-neutral; never returns a code/token |
| `POST /auth/password-reset/confirm` | `{token, newPassword}` | `200 {reset, revokedSessions}` | Single-use token; revokes all sessions |
| `POST /auth/password/change` | Bearer access token + `{currentPassword, newPassword}` | `200 {changed, currentSessionRetained, revokedOtherSessions}` | Current session remains; all others are revoked |
| `GET /auth/sessions` | Bearer access token | `200 {sessions:[…]}` | Safe metadata only: opaque session ID, timestamps, device label, current marker; no fingerprints or credential values |
| `POST /auth/sessions/revoke` | Bearer + `{sessionId}` | `200 {revoked, alreadyRevoked}` | Cannot revoke the current session through this endpoint |
| `POST /auth/sessions/revoke-all` | Bearer access token | `200 {revokedSessions, currentSessionRetained:true}` | Retains current session |
| `POST /auth/guest` | `{guestIdentityId}` | `201 {guest:{guestIdentityId, linked, createdAt}}` | Anonymous identity registration/linking |
| `GET /health` | — | `200 {status:"ok", service:"craftmind-auth"}` | Reveals no account data |
| `POST /auth/password-reset` | Legacy `{email}` | `202` neutral recovery receipt | Backward-compatible alias for the request flow; no longer a fake 501 |

`account` includes `userId`, `email`, `displayName`, `status`, `createdAt`, `updatedAt`, `emailVerified`, and `emailVerifiedAt`. `session` is the only response containing access/refresh tokens. Verification/recovery endpoints never return one-time token material. Session-list items never contain access/refresh tokens or their digests.

Stable typed errors include request/content-type/size/method/CORS/HTTPS/rate-limit codes; input and account codes; `EMAIL_NOT_VERIFIED`; invalid/expired/used verification and reset token codes; current-password/email-delivery errors; session/revocation errors; and the backend/network/unknown codes. The Android `AccountApiErrorCode` mirrors the service vocabulary. Unknown/wrong-password login responses remain indistinguishable; deleted accounts do too during password login.

Bodies default to a 16 KiB cap (configurable up to 1 MiB). Wrong content types, oversize bodies, unknown paths, and unsupported methods fail with safe typed errors. Sensitive routes have configurable fixed-window limits. Production requires HTTPS as observed at a trusted TLS terminator (`TRUST_PROXY_TLS=true` and `X-Forwarded-Proto: https`), exact HTTPS CORS origins (no wildcard), and a configured absolute `PUBLIC_ORIGIN`. These are deployment gates, not evidence that a service is deployed.

### What the client is never allowed to send

No endpoint accepts a client-supplied `userId`, `status`, or `authenticated` flag. Signing in means presenting a session
token; the service decides who that is. A request body that claims an identity is a request body that is ignored.

---

## 3. Authentication flows

**Registration and verification.** The app validates the required display name, address, password, and confirmation, then sends `POST /auth/register`. The service normalizes email, hashes the password, creates an `ACTIVE` but unverified account, links a supplied unlinked guest identity, and requests delivery of a one-time code. It returns no session. The app enters `VerificationRequired` and persists no credential. Codes are generated by a cryptographic random source, scoped to their purpose, expire (default 24 hours), and are stored only as HMAC digests. Verification consumes the code once; only then may the user sign in. A webhook's successful HTTP response is reported as `PROVIDER_ACCEPTED`, not delivered; it confirms only that the provider accepted the request. Provider failure is `UNAVAILABLE`, and the development sink explicitly says no email was sent.

**Sign-in.** `POST /auth/login` uses address, password and guest identity. The service requires a verified address before issuing a session. Unknown addresses and wrong passwords share one code/message; `EMAIL_NOT_VERIFIED` is separate. An unreachable service is `NETWORK_UNAVAILABLE`, not a credential failure.

**Password recovery.** `POST /auth/password-reset/request` gives the same response whether or not the address has an eligible account. The one-time handoff is started without waiting for the provider, and a 150 ms minimum response floor reduces the ordinary timing difference; this is not a formal constant-time guarantee under overloaded storage. The HTTP response and logs do not reveal account existence or provider failure. The in-memory development sink is reported honestly. Email handoff is best-effort and there is no durable mail queue. `POST /auth/password-reset/confirm` consumes the single-use code, updates the scrypt hash, and revokes every session. The user signs in again.

**Authenticated password change.** `POST /auth/password/change` requires the current password and a live access token. On success the current session remains active and every other session is revoked. The Android session manager remains the sole owner of the canonical credential.

**Session management.** `GET /auth/sessions` lists non-expired sessions with safe metadata only (device label, timestamps, opaque ID and current marker). It never returns credentials, digests, IPs, user agents, or device fingerprints. A user can revoke an individual *other* session or all other sessions; the current session cannot be revoked by these routes. To end the current session, use sign-out.

**Sign-out and restoration.** The app clears its local session first, then best-effort revokes remotely; local builds/history/settings/provider keys/Minecraft pairing remain untouched. On launch it loads the encrypted credential and asks `GET /auth/me`; expired credentials may refresh and rotate. An unreachable service keeps the existing local session for later retry; a server rejection clears it.

**Account switching.** Switching signs out first, then signs in. A failed switch cannot leave an unconfirmed identity adopted.

---

## 4. Guest identity behaviour

A device that has never signed in is a **guest**, which is a complete way to use CraftMind, not a limited trial.

* The identity is generated **by the app**, on the device, from 24 bytes of `SecureRandom`, encoded as base64url. It
  contains no Android ID, no advertising id, no serial, no build fingerprint, no model name, no phone number, no email
  hash, and no device-derived value of any kind. A test asserts the alphabet and the absence of device markers.
* It is stored in its own app-private file (`filesDir/account_identity/guest_identity.tag`), written atomically, and
  deliberately **not** encrypted and **not** in the account session store: it is not a credential, and keeping it apart
  from the session keeps the two from being confused.
* It survives normal use: restarts, updates, sign-outs, and session expiry all keep the same identity.
* The service records it in `guest_identities`, which lets the service distinguish an unregistered device from a
  registered account without knowing anything about the device.
* **Clearing app data deletes it**, and that is intentional: clearing local data is not a way to reset anything for free,
  because a new identity is a new unlinked guest and any account the device had stays exactly where it was.
* Registering links the permitted guest identity to the new account exactly once: the link is set in the same
  transaction that creates the account, a second registration cannot consume an already-linked identity, and a refused
  registration never consumes it. Local data is not touched, and no usage record is duplicated.

Introducing the identity to the service is best effort: if the service is unreachable, the device is still simply a
guest, and nothing is blocked or retried behind the user's back.

---

## 5. Session lifecycle

```
launch ─▶ load encrypted credential ─▶ GET /auth/me ─┬─ 200 ACTIVE ────▶ Authenticated (RESTORED_ON_DEVICE)
                                                     ├─ 401 expired ──▶ POST /auth/refresh ─┬─ 200 ─▶ Authenticated (rotated pair stored)
                                                     │                                        └─ 401 ─▶ SessionExpired + local record cleared
                                                     ├─ 403 suspended ─▶ SessionExpired(ACCOUNT_SUSPENDED) + local record cleared
                                                     └─ unreachable ──▶ Authenticated (record kept; retried on the next launch)
```

Sign-in is the same shape: `POST /auth/login` → `200` → the session credential is stored → `Authenticated`
(`LIVE_SIGN_IN`). A session is adopted **only** after the encrypted store accepted it; a storage failure leaves the
previous identity in place and reports `SESSION_STORAGE_FAILURE` rather than pretending to be signed in.

The credential itself is one opaque value in one place: the Phase 16 Keystore store (alias
`com.craftmind.account.session.aesgcm.v1`, directory `account_session`, AES-GCM, `noBackupFilesDir`), holding the
`v1|access|refresh` form described in §6. No token is written to preferences, serialised to disk, rendered in the UI,
included in an error message, or logged.

---

## 6. Security model

| Boundary | Rule | Enforced by |
| --- | --- | --- |
| Transport | Absolute HTTPS only. Plain HTTP is refused before the first request is built, and the manifest keeps cleartext traffic disabled | `AccountServiceConfiguration` (`of` / `fromBuildValue`) and `check_release_config.py` |
| Service address | Supplied per build (`-PcraftmindAccountBaseUrl` / `CRAFTMIND_ACCOUNT_BASE_URL`), never a literal in the source, never committed | `app/build.gradle.kts` → `BuildConfig.CRAFTMIND_ACCOUNT_BASE_URL`; `AccountSecurityBoundaryTest` |
| Authority | The service is the only source of account truth. `GET /auth/me` decides who is signed in; no local flag can overrule it | `AccountSessionManager` + the `AccountApi` contract |
| Passwords | scrypt hashes with per-password salts; never logged/returned; token digest comparisons are constant-time | `backend/src/passwords.js`, `backend/src/ids.js` |
| Tokens | Session and purpose-specific one-time tokens use cryptographic randomness and keyed HMAC-SHA256 digests; only digests persist; refresh rotates; Android session credentials stay in the Keystore store | `backend/src/ids.js`, `backend/src/accounts.js`, `AccountSessionTokens` |
| Session metadata | Lists reveal only server-generated labels/timestamps/current flag and an opaque revocation ID; no fingerprint, address, user agent, secret or digest | `backend/src/accounts.js`, `AccountRemoteSession` |
| Client state | No password or token is held in account state or rendered. The session manager remains the only canonical session truth; temporary input arrays are cleared after use | `AccountSessionManager`, `AccountUiState`, `AccountSecurityBoundaryTest` |
| Logging | Request logs record method/fixed-safe-route-label/status/duration/request ID only; attacker-controlled paths, query strings, email/token/provider exceptions, and headers are excluded. Android account code contains no logging call | `backend/src/server.js`, source-boundary tests |
| Failure messages | Typed codes with user-facing wording generated by the app; no raw service message, stack trace, or exception text is ever shown | `AccountAuthErrorCode` → `AccountUiState` |
| Separation | The account session store and the AI provider key store keep distinct Keystore namespaces and never share a file; provider keys are never attached to an account | `AccountSecurityBoundaryTest` |

`AUTH_SECRET` must be at least 32 characters, is read only from the environment, and is never generated, defaulted, or logged. Production also requires an HTTPS webhook URL, provider bearer token (at least 24 characters), sender address, HTTPS `PUBLIC_ORIGIN`, `TRUST_PROXY_TLS=true`, and a persistent SQLite file. `.env.example` contains placeholders only; never place production secrets in Git.

---

## 7. Running it locally

```bash
cd backend
cp .env.example .env          # then set a real local AUTH_SECRET and DATABASE_URL
npm start                     # reads .env via Node's --env-file; never commit that file
npm test                      # node --no-warnings=ExperimentalWarning --test "test/*.test.js"
```

Requires Node.js 22.5 or newer for built-in `node:sqlite`; runtime dependencies are zero. `npm test` runs the endpoint, security-hardening, and persistent-SQLite journey suites. Tests use a deterministic local development mail sink; its private mailbox is available only to test code, never by HTTP. It does not deliver mail.

Important environment variables (`backend/.env.example`):

| Variable | Meaning |
| --- | --- |
| `NODE_ENV` | `development` by default; `production` enables strict provider/HTTPS startup gates |
| `DATABASE_URL` | Persistent SQLite file. `:memory:` is allowed only when an explicit test option is passed |
| `AUTH_SECRET` | At least 32 characters; keys all session and one-time token digests and limiter subjects |
| `HOST`, `PORT` | Listen address/port; use `HOST=0.0.0.0` only behind an appropriate network/TLS boundary |
| `EMAIL_PROVIDER` | Local development defaults to `memory`; production requires `webhook` |
| `EMAIL_WEBHOOK_URL`, `EMAIL_WEBHOOK_TOKEN`, `EMAIL_FROM` | Production HTTPS JSON webhook URL, bearer token (≥24 characters), validated sender |
| `PUBLIC_ORIGIN`, `TRUST_PROXY_TLS` | Production HTTPS origin and trusted TLS-terminator setting; requests must arrive with forwarded proto `https` |
| `CORS_ALLOWED_ORIGINS` | Optional comma-separated exact HTTPS origins; wildcards/paths are rejected |
| `ACCESS_TOKEN_TTL_SECONDS`, `REFRESH_TOKEN_TTL_SECONDS` | Session token lifetimes; defaults 3600 and 2592000 seconds |
| `EMAIL_VERIFICATION_TTL_SECONDS`, `PASSWORD_RESET_TTL_SECONDS` | One-time code TTLs; defaults 86400 and 3600 seconds |
| `MAX_BODY_BYTES` | JSON body limit; defaults to 16 KiB, upper bound 1 MiB |
| `RATE_*` | Per-route maximums and windows for login/register/verification/resend/recovery/session operations |

The fixed-window limiter is process-local and bounded. Login/resend/recovery also use an independent HMAC-keyed email bucket in addition to the client-IP bucket; the bounded cache fails closed rather than evicting still-active buckets. A production deployment must either run one service instance or enforce equivalent/global limits at a trusted edge before horizontal scaling; process restart resets the windows. This implementation does not claim a deployment exists.

Pointing an Android build at it (the app accepts HTTPS only; local HTTP requires a TLS-terminating proxy, and the development service must never be exposed directly):
```bash
./gradlew :app:assembleDebug -PcraftmindAccountBaseUrl=https://accounts.example.invalid
# or: CRAFTMIND_ACCOUNT_BASE_URL=https://accounts.example.invalid ./gradlew :app:assembleDebug
```

A build with no value keeps working exactly as before: the app reports "no account service configured" and offers no
sign-in, and every local feature is unaffected.

---

## 8. Data clear and reinstall behaviour

| Scenario | What happens |
| --- | --- |
| A — register, sign out, sign in again | The same server account. Sign-out revoked the session; the account row was never touched |
| B — sign in, close the app, reopen | The encrypted session is loaded and confirmed with `GET /auth/me`; the device is signed in without typing anything |
| C — sign in, clear app data, reinstall, sign in | The same server account and display name after the file-backed SQLite service is restarted. The new installation gets a new guest identity; it does not create a new account |
| D — use as a guest, then create an account | The guest identity is linked to the new account exactly once; it is not consumed by a refused registration, and no local data is lost |

These four are covered as real integration tests against the service (`backend/test/accounts.test.js`) and as state-machine
tests on the app side.

---

## 9. Deliberately not implemented

* **Account deletion** remains unavailable. The service/authenticator returns `NOT_IMPLEMENTED_BY_SERVICE`; no deletion is faked, no local data is removed by the request, and users must be told the service did not implement it.
* **Production email delivery is not deployed/configured by this repository.** The provider-neutral HTTPS webhook adapter exists, and production startup requires it, but no provider account, endpoint, credential, or deployment is included. The development sink is private, in-memory, test-only, and sends no email.
* **Ordinary user sign-in never grants developer/admin authority or Minecraft permissions.** A separate developer identity/control plane exists in Phase 19; see [`developer-control-plane.md`](developer-control-plane.md). Memberships, credits, gifts, bans/full moderation, subscriptions, payments, marketplace, public user dashboard, OAuth/social sign-in, sync, cross-device history, and account-owned builds remain **NOT IMPLEMENTED**.
