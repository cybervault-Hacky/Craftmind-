# Production operations: startup, runtime bounds, observability, database recovery

Phase 33. This document covers how the backend process starts, what it refuses to start with, how it stops, what it
says while running, and how its single persistent store is backed up and restored. Product-surface contracts live in
the other phase documents; the account/authentication configuration table stays canonical in
[`docs/account-authentication.md`](account-authentication.md) and is only extended here.

**No hosting target is configured by this repository.** There is no container file, no orchestrator manifest, no
reverse-proxy configuration and no deployment credential anywhere in the tree — verified by inspection, not assumed.
Everything below is therefore infrastructure-independent and locally testable; the sections at the end name exactly
what still belongs to the deployment.

## Startup: five stages, one owner each

`backend/src/index.js` runs these in order, and each stage either completes or ends the process:

| Stage | What it proves | On failure |
| --- | --- | --- |
| 1. runtime preflight | Node.js ≥ **22.5.0** and `node:sqlite` actually importable | one line to stderr, exit **2** |
| 2. configuration | every key validated — presence, type, format, range, and mutual dependencies | `ConfigurationError` message, exit **2** |
| 3. database | file opened, pragmas applied, migrations applied | handle closed first, then exit **2** |
| 4. service assembly | routes, security engine, rate limiter constructed over that handle | **database handle closed**, then exit **2** |
| 5. bind | `listen()` succeeded on `HOST:PORT` | server + handle unwound, exit **2** |

The preflight is a separate stage because `db.js` imports `node:sqlite` at module scope: checked in that order, an
operator on Node 20 gets *"Node.js 22.5.0 or newer is required for the built-in SQLite driver (this runtime is
v20.x.y)"* instead of `ERR_UNKNOWN_BUILTIN_MODULE` from inside the dependency graph. `MINIMUM_NODE_VERSION` in
`config.js`, `engines.node` in `backend/package.json`, and the floor stated in `docs/account-authentication.md` are
one fact, and `scripts/check_deployment_readiness.py` fails if the three ever disagree.

Failure output is deliberately narrow. Startup errors name the **stage and the reason class**, never a value: no
secret, no token, no database path. Exit code **2** distinguishes "refused to start" from a runtime crash.

### What configuration validation refuses

Beyond the pre-existing rules (required `DATABASE_URL`, no `:memory:` outside tests, ≥32-char `AUTH_SECRET`,
production `EMAIL_PROVIDER=webhook` with an HTTPS URL and bearer token, exact HTTPS CORS origins with no wildcard,
`TRUST_PROXY_TLS=true` in production, bootstrap email+secret as a pair, developer-AI three keys as a triple), the
service now also refuses:

- `HEADERS_TIMEOUT_MS` longer than `REQUEST_TIMEOUT_MS`, or shorter than `KEEP_ALIVE_TIMEOUT_MS` — Node runs these
  timers against the same socket, and a keep-alive gap that outlasts the request timer makes a healthy idle
  connection look like a slow client.
- `ACCESS_TOKEN_TTL_SECONDS` ≥ `REFRESH_TOKEN_TTL_SECONDS` (and the developer pair), i.e. an access token that would
  outlive the refresh window meant to rotate it.
- `MAX_BODY_BYTES` below 1024 — the largest legitimate request this API accepts would be permanently rejected.
- `SECURITY_DEFAULT_PROTECTION_SECONDS` > `SECURITY_MAX_PROTECTION_SECONDS`, which would either clamp silently or
  hand a route a duration its own policy forbids.
- `HOST` that is not an IPv4 address, an IPv6 address (bracketed form accepted and unwrapped) or a hostname. The
  common mistake — `HOST=0.0.0.0:8787`, a port in the host field — now fails at startup with that sentence rather
  than inside the listen callback after the database has already been opened and migrated.

Error messages never contain the offending value. `positiveInteger` reports "`X` must be a positive integer within its
supported range", which is actionable for an operator and safe to paste into a ticket.

## Runtime bounds

| Key | Default | Ceiling | Effect |
| --- | --- | --- | --- |
| `REQUEST_TIMEOUT_MS` | 30000 | 600000 | `server.requestTimeout` **and** `server.timeout`. Node's own defaults (300 s for both) only help someone hold sockets open on a 16 KiB-capped API. Measured on Node 22: `requestTimeout` bounds receiving the *head* and does not reap a client that sends a head, declares a large body, and then stalls once the request has been dispatched — `server.timeout`, the socket-inactivity bound, is what closes that case, so both are set from this one value. |
| `HEADERS_TIMEOUT_MS` | 10000 | 120000 | `server.headersTimeout`. |
| `KEEP_ALIVE_TIMEOUT_MS` | 5000 | 300000 | `server.keepAliveTimeout`; short enough that idle clients do not accumulate, long enough that a page's sequential calls reuse one connection. |
| `SHUTDOWN_GRACE_MS` | 10000 | 300000 | how long in-flight requests get before connections are force-closed. |

`maxRequestsPerSocket` is intentionally **not** exposed: per-client volume is already bounded by the rate limiter
(which keys on the socket address, `configuration.rateLimit.*`, and cannot be evaded by rotating connections), and a
second, differently-shaped ceiling on the same traffic would be a knob nobody could reason about.

### Keys that existed but were never written down

The rest of this table is documentation debt, not new behaviour: every one of these keys has existed since the phase
that added it, validated with the same range rules, and none is renamed or re-defaulted here. Defaults and ceilings are
taken from `src/config.js`; `scripts/check_deployment_readiness.py` fails if a key referenced in code appears in no
document, which is what caught this gap.

| Key | Default | Ceiling | Tuning |
| --- | --- | --- | --- |
| `SECURITY_BURST_COOLDOWN_MS` | 60000 | 3600000 | how long a request-burst source stays cooled down |
| `SECURITY_EVENT_RETENTION_SECONDS` | 2592000 | 31536000 | age after which security **events** are pruned (30 d) |
| `SECURITY_INCIDENT_RETENTION_SECONDS` | 15552000 | 63072000 | age after which resolved **incidents** are pruned (180 d) |
| `SECURITY_NOTIFICATION_RETENTION_SECONDS` | 7776000 | 31536000 | age after which notifications are pruned (90 d) |
| `SECURITY_PROTECTION_RETENTION_SECONDS` | 604800 | 7776000 | how long a **released** protection stays on record (7 d) |
| `SECURITY_INCIDENT_DEDUP_MS` | 900000 | 86400000 | window in which repeat findings fold into one incident |
| `SECURITY_NOTIFICATION_COOLDOWN_MS` | 300000 | 86400000 | minimum gap between notifications for one incident |

Rate limits are one pair per bucket — `<BUCKET>_MAX` requests per `<BUCKET>_WINDOW_MS`, each with a fixed 15-minute
window by default and an independent budget so a burst in one category cannot spend another's. Two of these budgets
are shared rather than dedicated, which is deliberate and already covered by tests: `RATE_TRUST_WRITE_MAX` is the
limit the referral routes borrow for their own `referral-write` bucket, and `RATE_CREATOR_READ_MAX` is what
`/marketing/referrals/me` and the analytics reads use. Separate buckets, same limits — so a referral burst cannot
spend the order or trust budget, and no new environment knob had to be invented to express it.

| Requests (count per window) | Default / ceiling | Window | Default / ceiling (ms) |
| --- | --- | --- | --- |
| `RATE_JOB_WRITE_MAX` | 30 / 10000 | `RATE_JOB_WRITE_WINDOW_MS` | 900000 / 86400000 |
| `RATE_PROPOSAL_WRITE_MAX` | 30 / 10000 | `RATE_PROPOSAL_WRITE_WINDOW_MS` | 900000 / 86400000 |
| `RATE_ORDER_CREATE_MAX` | 30 / 10000 | `RATE_ORDER_CREATE_WINDOW_MS` | 900000 / 86400000 |
| `RATE_ORDER_WRITE_MAX` | 60 / 10000 | `RATE_ORDER_WRITE_WINDOW_MS` | 900000 / 86400000 |
| `RATE_DELIVERY_WRITE_MAX` | 30 / 10000 | `RATE_DELIVERY_WRITE_WINDOW_MS` | 900000 / 86400000 |
| `RATE_REVISION_WRITE_MAX` | 30 / 10000 | `RATE_REVISION_WRITE_WINDOW_MS` | 900000 / 86400000 |
| `RATE_TRUST_WRITE_MAX` | 20 / 10000 | `RATE_TRUST_WRITE_WINDOW_MS` | 900000 / 86400000 |
| `RATE_DISPUTE_WRITE_MAX` | 30 / 10000 | `RATE_DISPUTE_WRITE_WINDOW_MS` | 900000 / 86400000 |
| `RATE_ONBOARDING_WRITE_MAX` | 30 / 10000 | `RATE_ONBOARDING_WRITE_WINDOW_MS` | 900000 / 86400000 |
| `RATE_ONBOARDING_READ_MAX` | 120 / 10000 | `RATE_ONBOARDING_READ_WINDOW_MS` | 900000 / 86400000 |

### Client aborts are not server faults

A request whose socket dies mid-flight (navigated away, tab closed, `curl` interrupted) is classified before it is
handled as an error: the access log records `event: "account_http_client_aborted"` with `status: 499` **for the log
only**, and the response is destroyed rather than written to. Three consequences are deliberate:

- the operator's error channel stays free of phantom internal failures (previously every abort logged
  `account_request_internal_failure` at error level);
- an abort is **not** fed to the security engine, so closing a tab cannot spend anyone's malformed-request budget;
- nothing attempts `writeHead` on a finished response, which is the usual source of `ERR_HTTP_HEADERS_SENT` noise
  during client-disconnect storms. `sendJson`, `sendNoContent` and `sendDeveloperAsset` all check the response first.

## Graceful shutdown

On `SIGINT` or `SIGTERM`:

1. stop accepting new connections (`server.close()`),
2. `closeIdleConnections()` immediately — an idle keep-alive socket would otherwise keep shutdown waiting until the
   client grew bored, which is how a rolling restart becomes a `SIGKILL` at the end of the grace period,
3. let in-flight requests finish, up to `SHUTDOWN_GRACE_MS`,
4. `closeAllConnections()` when the grace expires and log `account_service_shutdown_grace_expired`,
5. close the SQLite handle **exactly once**, log `account_service_stopped`, exit `0`.

The whole sequence is guarded by a single promise, so a second or third signal is a no-op that returns the same
in-progress shutdown; it cannot close the database twice or run two competing unwinds. A failed bind uses the same
path with exit code `2`. The hourly cleanup interval is `unref()`'d and cleared on `close`, so nothing here depends on
`process.exit` to stop a timer from keeping the loop alive — the exit is there to make the outcome deterministic for a
supervisor, not to paper over a leak.

Global `uncaughtException` / `unhandledRejection` handlers are deliberately **not** installed: swallowing them would
hide real defects and leave a process serving traffic in an undefined state. A supervisor should restart it.

## Observability

| Endpoint | Auth | Body | Use |
| --- | --- | --- | --- |
| `GET /live` | none | `{ status: "alive", service: "craftmind-auth" }` | **Liveness.** No database, no dependency, nothing that a transient storage problem can fail. An orchestrator that restarts a process because its disk blipped turns one fault into an outage. |
| `GET /health` | none | `{ status, service, schemaVersion, database, schema }`, `503` with `{ status: "unavailable", database, failure }` when not ready | **Readiness.** Runs `SELECT 1` on the real handle and compares the `schema_migrations` watermark with `SCHEMA_VERSION`, so a deployment that migrates separately gets a truthful answer instead of a 500 on the first user request. |

Neither endpoint reveals a filesystem path, a connection string, an environment value, a row count, a table name, an
error message or anything about an account. `schemaVersion` is a build fact an operator already reads today, so it
stays; the `failure` field is a **category** (`CLOSED`, `BUSY`, `UNAVAILABLE`) chosen by inspecting the error, never
the error's text. The 503 body intentionally uses this small health-specific shape rather than the API's
`{ error: { code, message, requestId } }` envelope, because a probe consumer must be able to act without parsing an
application error type — and both endpoints still carry the same `SECURITY_HEADERS`, `X-Request-Id` and exact-origin
CORS behaviour as every other route.

### Logging

One JSON object per line to stdout (`error`-level lines to stderr), through the `logger` the service already takes as
a parameter — no new logging dependency, no framework.

- **Per request, exactly one line:** `{ service, event: "account_http_request", method, route, status, durationMs, requestId }`.
  `route` is the router's **pattern**, never the concrete path, so a log file cannot become a list of every creator
  handle and workspace slug that was probed.
- **Never logged:** `Authorization` headers, bearer tokens, passwords, reset/verification tokens, API keys, signing
  secrets, request bodies, and response bodies. Only the status code and duration leave the handler.
- **Internal failures** log `errorType: error?.name` only. The message is discarded on purpose: it can contain a body
  fragment or a provider response. The correlating detail an operator needs is the `requestId`, which is also returned
  in the client-facing error envelope and never derived from the URL.
- **Newline injection:** the only request-derived string that reaches a log line is the route pattern, and it is
  produced by `safeRoutePath()` + the router's own literal patterns, so a `\n` in a URL can never split a log line.
  Values that could carry attacker text (slugs, handles) are not logged at all.
- **Private relationships and contents:** referral attributions, report notes and dispute statements are never logged
  or copied into diagnostics; the security engine stores opaque digests, not raw sources, and nothing here widens that.
- **No double-logging:** a failure is reported by the layer that can name it. The request handler owns the access line
  and the internal-failure line; the security-signal path logs one warn line if *its own* bookkeeping failed
  (`security_signal_failed`) and never re-throws.

**Lifecycle events**, all from `src/index.js`, so a restart is readable from logs alone:

| Event | Level | Fields | Meaning |
| --- | --- | --- | --- |
| `account_service_started` | info | `environment`, `host`, `port`, `schemaVersion` | every stage succeeded and the socket is bound. The database path, the secret, and every other configured value are deliberately absent. |
| `account_service_listen_failed` | error | `code`, `pid` | the process was ready but the port refused it (busy, or privileged without capability). Only the error `code` is logged, never the address or the cause string. |
| `account_service_stopping` | info | `signal` | a signal arrived and the drain has begun. |
| `account_service_shutdown_grace_expired` | warn | `reason` | `SHUTDOWN_GRACE_MS` ran out: idle connections were closed and remaining sockets destroyed. A deploy that shows this line dropped in-flight work. |
| `account_service_stopped` | info | `reason` | the database handle is closed and the process is about to exit `0`. |


## Database: migrations, integrity, backup, restore

Pragmas applied on every connection (`openDatabase`): `journal_mode = WAL`, `foreign_keys = ON`,
`recursive_triggers = ON`, `busy_timeout = 5000`. `synchronous` is left at SQLite's WAL-mode default (`NORMAL`);
raising it to `FULL` costs throughput on every commit and is a deployment decision about durability, so it is
documented here rather than changed silently.

**Migration atomicity.** Each version runs inside `BEGIN IMMEDIATE … COMMIT` together with its `schema_migrations`
row, and any statement failure issues `ROLLBACK` before rethrowing. Because SQLite DDL is transactional, a migration
that fails halfway leaves the previous schema and **no** watermark row — so the version can never advance past a
structure that does not exist. Startup on an already-migrated database is a no-op read of `schema_migrations`, and
`scripts/check_deployment_readiness.py` plus the test suites keep the watermark and the table set honest.

**Indexes:** none added in this phase. Every read path added by Phases 30–32 was checked against the existing
indexes and the two `referral_attributions` indexes cover the referrer and windowed reads; adding an index without a
measured query would cost write amplification on every claim for a gain nobody has observed.

### Backup

`backend/src/db-maintenance.js` + `backend/scripts/backup-database.mjs`:

```bash
cd backend
DATABASE_URL=./var/craftmind-auth.db \
  node --no-warnings=ExperimentalWarning scripts/backup-database.mjs --to /var/backups/craftmind
# {"event":"database_backup_completed","file":"craftmind-auth-backup-2026-10-10T02-15-00Z.db","bytes":794624,"schemaVersion":12,"integrity":"ok",…}
```

- Uses **`VACUUM INTO`** with a *bound parameter* (the destination is never concatenated into SQL text) — SQLite's own
  online backup path. Copying `craftmind-auth.db` while the service runs is not a snapshot: the newest committed
  transactions are sitting in the `-wal` sidecar, and a copied main file without a mergeable journal is a backup that
  silently lies.
- The live database is opened **read-only**, so a backup job can never migrate or modify it.
- The result is verified before the command succeeds: `PRAGMA integrity_check` must say `ok` and the snapshot's
  watermark must equal this build's `SCHEMA_VERSION`. A snapshot that fails either is **deleted** — an artifact that
  looks like a backup and cannot be restored is worse than no artifact.
- File mode `0600`, directory mode `0700`, and a destination inside a served website directory (`backend/public`) is
  **refused**: a backup is the whole database, and publishing one over HTTP is the single most likely way to leak it.
- No application secret is required, so it can run from a scheduler with a minimal credential surface. It prints no
  table names, row counts or values.
- `--name` accepts a file name only; `/`, `\` and `..` are rejected.

### Restore

```bash
cd backend
node --no-warnings=ExperimentalWarning scripts/restore-database.mjs \
  --from /var/backups/craftmind/craftmind-auth-backup-2026-10-10T02-15-00Z.db \
  --database ./var/craftmind-auth.db --dry-run      # verify only, writes nothing
# stop the service first, then:
node --no-warnings=ExperimentalWarning scripts/restore-database.mjs --from … --database … --force
```

1. Verify the snapshot (integrity + schema watermark) and refuse a snapshot from a different schema version, with the
   two versions named in the message.
2. `--dry-run` ends here — the pre-window rehearsal mode.
3. Copy to `TARGET.restoring`, then `rename()` into place, so an interrupted copy leaves the previous file instead of
   leaving nothing. With `--force`, the stale `-wal`/`-shm` sidecars of the replaced database are removed, because a
   journal from the old file would otherwise be replayed into the restored one.
4. Re-verify the restored file the same way.

The service must be stopped: SQLite cannot swap a file out from under an open connection, and this tool makes no
attempt to detect a live process — `--force` is the operator's acknowledgement. Refusing to overwrite a target without
`--force` exists so an idle rehearsal cannot destroy a real database by accident. **Restore rehearsal on real
infrastructure has not been performed here**; no such infrastructure exists in this repository.

### Automation status

There is **no automated backup scheduler** in this repository, and this phase does not pretend otherwise: no cron
entry, no systemd timer, no object-storage upload, no rotation policy. Those depend on a host and a bucket that are
not configured. What is now real and tested is the primitive an operator schedules: one command that produces a
verified, permission-restricted snapshot with no secret in its environment. Rotation is the deployer's job until a
target exists (listed below).

## Deployment prerequisites (not configured, not claimed)

Belongs to the deployment, and cannot be verified from this repository:

1. **TLS termination and the network boundary.** The process speaks plain HTTP on `HOST`; `TRUST_PROXY_TLS=true` plus
   `X-Forwarded-Proto: https` is how the HTTPS gate is satisfied *behind* a terminator. The residual risk is stated
   plainly: `X-Forwarded-Proto` is trusted only because configuration says a trusted proxy sits in front, so
   direct HTTP access to the process must be impossible at the network layer. `X-Forwarded-For` and
   `X-Forwarded-Host` are **never** consulted — rate limits and security digests key on the socket address, so a
   spoofed client-IP header buys an attacker nothing.
2. **Origin registration.** The web client is same-origin with the API when served by `renderSite()`; a separately
   hosted frontend must be added to `CORS_ALLOWED_ORIGINS` as an exact HTTPS origin.
3. **Log transport, monitoring, alerting.** Structured lines exist; shipping, retention, dashboards and paging are
   external and are **not** configured or claimed.
4. **Backup scheduling, off-box copy, rotation, restore rehearsal.** The commands exist; the schedule does not.
5. **Migration strategy.** Migrations run automatically at startup, which suits a single-writer deployment that
   stops before upgrading. A blue/green rollout needs the new binary's migrations to be a separate, ahead-of-time
   step, because v12's immutability triggers and `UNIQUE` constraints are not downgrade-safe: **there is no
   downgrade path**, and rollback means restoring a pre-upgrade backup. That is the rollback plan, stated rather than
   invented.
6. **`/live` and `/health` wiring** into whatever probe mechanism the platform provides (2–10 s intervals are well
   inside the unauthenticated-read cost; the readiness check is one indexed `SELECT`).
7. **A CI runner.** `.github` does not exist in this repository. `ci-workflow.yml.example` at the root is an
   installable, dependency-free pipeline whose every step is a command this phase ran locally; installing it requires
   workflow-write permission and is an owner action. No workflow here deploys anything, because there is nothing to
   deploy to.

The static website's publishing path is unchanged and remains `website/github-pages-workflow.yml.example`, still
shipped as an example for an operator to install.

## Supported commands

```bash
cd backend && npm test                                                     # full suite
cd backend && node --no-warnings=ExperimentalWarning --test test/X.test.js  # one file
cd backend && node --check src/index.js                                    # any file; also `npm start`'s entry
python3 scripts/check_website.py                                           # website structure/links/CSP integrity
python3 scripts/check_release_config.py                                   # Android + tracked-secret review
python3 scripts/check_deployment_readiness.py                             # this phase's consistency rules
```

`backend/package.json` declares exactly two scripts — `start` (`node --env-file=.env … src/index.js`) and `test` —
and **zero dependencies**, so there is no lockfile to drift and no install step in CI. That invariant is itself
checked: if a dependency is ever declared without a lockfile, `check_deployment_readiness.py` fails.

`ANDROID_BUILD = NOT_RUN` in this environment: no Android SDK/JDK toolchain is present. No browser E2E, device test,
live deployment, restore rehearsal against production data, or external security review is performed or claimed.
