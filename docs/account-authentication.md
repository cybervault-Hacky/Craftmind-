# Accounts and Authentication (Phase 17)

This document is the authoritative description of **real user accounts in CraftMind**: the service that stores them,
the contracts between app and service, the guest identity a device carries before it has an account, the session
lifecycle, the security boundaries, how to run the service locally, and what is deliberately **not** implemented.

Related documents: [`premium-ui-ux-architecture.md`](premium-ui-ux-architecture.md) §9 (the Phase 16 account foundation
this phase connects to a real service) and the repository [`README.md`](../README.md).

Scope note: CraftMind has accounts, and only accounts. There is no developer account, no administrator account, no
dashboard, no gifting, no banning, no credits, no subscription, no payment, and no marketplace. Nothing in this phase
administers anything, and no AI system has an account.

---

## 1. Backend architecture

One technology, one process, no frameworks: a small **Node.js** service using only the standard library.

| Piece | File | Responsibility |
| --- | --- | --- |
| Configuration | `backend/src/config.js` | Reads and validates the environment. Requires an `AUTH_SECRET` of at least 32 characters, refuses an in-memory database outside tests, and only accepts `DATABASE_URL` values it understands |
| Errors | `backend/src/errors.js` | The closed error vocabulary, the HTTP status for each code, and the one exception type the server throws |
| Passwords | `backend/src/passwords.js` | scrypt hashing and verification (`N=16384, r=8, p=1`, 64-byte key, per-password salt), the password policy, and a dummy verification used to keep "unknown account" and "wrong password" indistinguishable in cost |
| Identifiers and tokens | `backend/src/ids.js` | `usr_`/`ses_` identifiers, 256-bit random tokens, HMAC-SHA256 digests, digest comparison, and the email/display-name/guest-identity validation rules |
| Storage | `backend/src/db.js` | SQLite (`node:sqlite`) with WAL, foreign keys, a busy timeout, and a migration for exactly three account-domain tables plus migration metadata |
| Account logic | `backend/src/accounts.js` | Registration, sign-in, refresh, sign-out, current account, guest registration, and guest linking |
| HTTP | `backend/src/server.js` | Routing, bounded request bodies, JSON parsing, authentication, `Cache-Control: no-store`, and request ids |
| Entry point | `backend/src/index.js` | Configuration → database → listen → graceful shutdown |

**Storage schema (migration v1).** Three account-domain tables, plus the standard `schema_migrations` version ledger:

* `users` — `user_id` (primary key), `email`, `email_canonical` (unique, case/space-insensitive), `display_name`,
  `password_hash`, `status` (`ACTIVE` / `SUSPENDED` / `DELETED`), `created_at`, `updated_at`.
* `sessions` — `session_id` (primary key), `user_id` (foreign key), `access_digest`, `refresh_digest`,
  `issued_at`, `access_expires_at`, `refresh_expires_at`, `revoked_at`, and nullable `guest_identity_id`. Indexed on both digests and on `user_id`.
* `guest_identities` — `guest_identity_id` (primary key), `linked_user_id` (nullable, set when the identity is linked),
  `created_at`, `last_seen_at`, `linked_at`. Indexed on `linked_user_id`.

There is no account-subscription, credit, entitlement, gift, ban, marketplace, or admin table, and the test suite fails
if one ever appears.

`DATABASE_URL` is a SQLite file path (or `:memory:` in tests only). SQLite is chosen because it is embedded, has no
network, and needs no second service to run: the smallest thing that genuinely persists accounts.

---

## 2. API contracts

Every endpoint is `POST /auth/…` except the two `GET`s. Request and response bodies are JSON with string members; a
missing optional member is omitted rather than sent as `null`; registration requires a non-empty `displayName`. Every response carries `Cache-Control: no-store`, and every
error is `{"error": {"code": "...", "message": "...", "requestId": "..."}}` with a stable `code`.

| Endpoint | Request | Success | Notes |
| --- | --- | --- | --- |
| `POST /auth/register` | `{email, password, displayName, guestIdentityId?}` | `201 {account, session, guestLinked}` | Rejects a duplicate address with `ACCOUNT_ALREADY_EXISTS` (case- and space-insensitive). A guest identity is linked when one is supplied |
| `POST /auth/login` | `{email, password, guestIdentityId?}` | `200 {account, session}` | The same account-plus-session core as registration. Unknown addresses and wrong passwords share one code and message. An unknown address and a wrong password produce the same code and message |
| `POST /auth/refresh` | `{refreshToken}` | `200 {session}` | Rotates **both** tokens; the previous pair stops working immediately |
| `POST /auth/logout` | `{refreshToken?, accessToken?}` | `200 {revoked, alreadyRevoked, revokedSessions}` | Idempotent: signing out twice is a success, and the answer says which case it was |
| `GET /auth/me` | `Authorization: Bearer <accessToken>` | `200 {account}` | The **only** source of account truth. The service answers with the account its own session belongs to |
| `POST /auth/guest` | `{guestIdentityId}` | `201 {guest: {guestIdentityId, linked, createdAt}}` | Records an anonymous identity so a later registration can be linked. Idempotent |
| `GET /health` | — | `200 {status: "ok", service: "craftmind-auth"}` | Availability probe. Reveals nothing about accounts |
| `POST /auth/password-reset` | `{email}` | `501 PASSWORD_RESET_NOT_IMPLEMENTED` | An explicit future contract |

Response shapes:

* `account` — `{userId, email, displayName, status, createdAt, updatedAt}`. `userId` is the opaque service identifier; it
  is never an internal database row id, and the app never renders it.
* `session` — `{accessToken, refreshToken, accessExpiresAt, refreshExpiresAt}`. The only place a token ever appears.

Error codes, all typed and stable: `INVALID_REQUEST`, `INVALID_EMAIL`, `INVALID_PASSWORD`, `INVALID_DISPLAY_NAME`,
`INVALID_CREDENTIALS`, `ACCOUNT_ALREADY_EXISTS`, `ACCOUNT_SUSPENDED`, `ACCOUNT_DELETED`, `SESSION_EXPIRED`,
`SESSION_INVALID`, `REFRESH_FAILED`, `NETWORK_ERROR`, `BACKEND_UNAVAILABLE`, `UNKNOWN_ERROR`, `ACCOUNT_NOT_FOUND`,
`AUTHENTICATION_REQUIRED`, `INVALID_GUEST_IDENTITY`, `GUEST_IDENTITY_ALREADY_LINKED`, `REQUEST_TOO_LARGE`,
`METHOD_NOT_ALLOWED`, and `PASSWORD_RESET_NOT_IMPLEMENTED`. Unknown and wrong-password sign-ins share one code and
message; deleted accounts do too during password login. `NETWORK_ERROR` is recognized as a client/service boundary code,
while an actual transport failure is represented locally and never forged into an HTTP response.

Limits: request bodies are capped at 16 KiB (`413`), unknown paths answer `400 INVALID_REQUEST`, and a known path with
the wrong method answers `405 METHOD_NOT_ALLOWED`. The Android client mirrors these codes one-to-one in
`AccountApiErrorCode`, and a contract test fails if the two vocabularies drift apart.

### What the client is never allowed to send

No endpoint accepts a client-supplied `userId`, `status`, or `authenticated` flag. Signing in means presenting a session
token; the service decides who that is. A request body that claims an identity is a request body that is ignored.

---

## 3. Authentication flows

**Registration.** The app validates the required display name, address, password, and confirmation locally, then sends
`POST /auth/register` with the typed password and this device's guest identity. The service validates again, normalises
the address, hashes the password with scrypt, creates the account, links the guest identity if one was supplied and is
unlinked, and returns the account plus a session. Duplicate emails are rechecked inside a SQLite write transaction so
two concurrent registrations still produce one account and one stable `ACCOUNT_ALREADY_EXISTS` error. The app stores only
the session credential, and only if the store accepted it.

**Sign-in.** `POST /auth/login` with the address, password, and guest identity. An unreachable service is reported as
`NETWORK_UNAVAILABLE` — not as a wrong password — so the user is never told their credentials failed when in fact
nothing was asked. A rejected credential produces `INVALID_CREDENTIALS` with one message for both "no such account" and
"wrong password".

**Sign-out.** The app clears the local session first and always, then asks the service to revoke remotely. The outcome
is reported as one of `REMOTE_REVOKED`, `REMOTE_REVOCATION_FAILED`, `REMOTE_REVOCATION_SKIPPED`, or `LOCAL_ONLY`.
Signing out touches nothing else: builds, history, settings, provider keys, and the Minecraft pairing are untouched.

**Restoration.** On launch the app loads the encrypted credential, calls `GET /auth/me`, and adopts the account the
service returns. If the access token is refused as expired, the refresh token is exchanged for a new pair and the new
pair is stored. If the service rejects the session outright, the local session is destroyed and the state becomes
`SessionExpired` with the service's reason. If the service cannot be reached, the session is **kept** and the state stays
authenticated: an offline device is not a signed-out device.

**Expiry.** A session carries the expiry the service declared, and nothing else. When it passes, refresh is attempted if
the service supports it; the session is only discarded once the service has actually refused it.

**Account switching.** `switchAccount()` signs out first, then signs in. A failed switch cannot leave half of a previous
identity behind, because there is no code path that adopts a session before it has been confirmed.

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
| Passwords | Stored only as scrypt hashes with per-password salts; never logged, never returned, never included in an error; compared in constant time by digest for tokens | `backend/src/passwords.js`, `backend/src/ids.js` |
| Tokens | Random 256-bit values, stored only as HMAC-SHA256 digests keyed by `AUTH_SECRET`; rotated on every refresh; the client keeps them only in the encrypted store and clears its copies | `backend/src/ids.js`, `AccountSessionTokens` |
| Client state | No token, password, or internal identifier can reach the UI: `AccountSession` is metadata only, and the projected UI state has no field that could carry a secret | `AccountUiState`, `AccountUiStateTest`, `AccountSecurityBoundaryTest` |
| Logging | The service's access log records method, path, status, duration, and a request id — never a body, never a header, never an email. The Android account code contains no logging call at all | `backend/src/server.js`, `check_release_config.py` |
| Failure messages | Typed codes with user-facing wording generated by the app; no raw service message, stack trace, or exception text is ever shown | `AccountAuthErrorCode` → `AccountUiState` |
| Separation | The account session store and the AI provider key store keep distinct Keystore namespaces and never share a file; provider keys are never attached to an account | `AccountSecurityBoundaryTest` |

`AUTH_SECRET` is the one secret the service needs. It is read from the environment, must be at least 32 characters, and
is never defaulted, generated, or logged. `.env.example` contains placeholders only, and `.env` is ignored by Git.

---

## 7. Running it locally

```bash
cd backend
cp .env.example .env          # then set a real local AUTH_SECRET and DATABASE_URL
npm start                     # reads .env via Node's --env-file; never commit that file
npm test                      # node --no-warnings=ExperimentalWarning --test "test/*.test.js"
```

Requires Node.js 22.5 or newer (for the built-in `node:sqlite`); the sandbox this phase was verified in runs 22.22.3.
There are **no dependencies to install** — no framework, no ORM, no test runner to fetch. The suite is 35 tests across
the endpoint contract (`test/accounts.test.js`), the security properties (`test/security.test.js`), and the four named
data-clear/reinstall journeys (`test/scenarios.test.js`). Scenario C uses file-backed SQLite across a service restart.

Environment variables (`backend/.env.example`):

| Variable | Meaning |
| --- | --- |
| `DATABASE_URL` | SQLite file path, e.g. `./var/craftmind-auth.db`. In-memory databases are accepted only by the test harness |
| `AUTH_SECRET` | At least 32 characters; keys the token digests |
| `HOST`, `PORT` | Listen address and port (defaults `127.0.0.1`, `8787`) |
| `ACCESS_TOKEN_TTL_SECONDS` | Access-token lifetime (default 3600) |
| `REFRESH_TOKEN_TTL_SECONDS` | Refresh-token lifetime (default 2592000) |
| `MAX_BODY_BYTES` | Request body cap (default 16384) |
| `BACKEND_BASE_URL` | The public base URL, for deployment tooling. Not used by the Android client |

Pointing an Android build at it (the address must be HTTPS in a real build; a local service needs a TLS-terminating
proxy in front of it, because the client refuses plain HTTP by design):

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

Stated here so that nothing above can be read as a promise:

* **Email verification** — no verification email is sent, no verified flag exists, and the sign-up screen says so in
  those words.
* **Password reset** — `POST /auth/password-reset` answers `501 PASSWORD_RESET_NOT_IMPLEMENTED`. The endpoint exists so
  the contract is real and a future phase can implement it without a redesign; it does not pretend to send mail.
* **Account deletion** — no deletion workflow. The app's deletion request returns `NOT_IMPLEMENTED_BY_SERVICE`, and no
  "DELETE ACCOUNT" control exists anywhere.
* **Cloud sync, cross-device history, account-owned builds, creator profiles, marketplace, subscriptions, payments,
  credits, gifts, bans, entitlements, admin tooling, developer accounts, and any AI-facing account system** — not
  implemented, not referenced in the UI, and not modelled in the database.
* **OAuth / social sign-in** — the domain distinguishes sign-in methods and has an authorization-code request type, but
  no provider is implemented or configured.
