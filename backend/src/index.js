#!/usr/bin/env node
/**
 * Entry point for the CraftMind account service and separate developer control plane.
 *
 * Startup is a sequence of stages, each with exactly one owner:
 *
 *   1. runtime preflight      — is this Node version able to provide `node:sqlite` at all
 *   2. configuration          — every setting validated, with no value of any setting ever printed
 *   3. database               — open, apply pragmas, migrate (each version in its own transaction)
 *   4. service                — assemble the HTTP server over that handle
 *   5. bind                   — listen, and fail with a reason if the port or host is unusable
 *
 * A failure at stage 3, 4 or 5 closes whatever the earlier stages opened, so there is never a half-started process
 * holding a database handle, and the process leaves with code 2 plus one actionable line. Termination is the mirror
 * image: stop accepting, let in-flight requests finish inside the configured grace, then close the idle and then the
 * live connections, close the database exactly once, and exit. Repeated signals are ignored rather than re-run.
 *
 * Local development:
 *   cp .env.example .env          # then set AUTH_SECRET and DATABASE_URL
 *   set -a && . ./.env && set +a
 *   node --no-warnings=ExperimentalWarning src/index.js
 */

import { assertSupportedRuntime, ConfigurationError, loadConfiguration } from "./config.js";
import { createAccountService } from "./server.js";

/** One line to stderr, exit 2. A ConfigurationError message is operator-facing by construction and secret-free. */
function abortStartup(stage, error) {
  const message = error instanceof ConfigurationError
    ? error.message
    : `the ${stage} stage failed (${error?.name ?? "Error"})`;
  process.stderr.write(`CraftMind account service cannot start: ${message}\n`);
  process.exitCode = 2;
  process.exit(2);
}

function log(level, payload) {
  const line = JSON.stringify({ service: "craftmind-auth", ...payload });
  if (level === "error") process.stderr.write(`${line}\n`);
  else process.stdout.write(`${line}\n`);
}

/**
 * node:sqlite is the only reason this process cannot run on an older runtime, so the check is made *before* the
 * module graph pulls in the driver: otherwise the operator gets `ERR_UNKNOWN_BUILTIN_MODULE` from inside `db.js`
 * instead of a sentence that says what to install.
 */
async function preflight() {
  try {
    await assertSupportedRuntime();
  } catch (error) {
    abortStartup("runtime", error);
  }
}

async function main() {
  await preflight();

  let configuration;
  try {
    configuration = loadConfiguration(process.env);
  } catch (error) {
    if (!(error instanceof ConfigurationError)) throw error;
    abortStartup("configuration", error);
  }

  // Imported only after the preflight so an unsupported runtime never evaluates a module that requires it.
  const { openDatabase, SCHEMA_VERSION } = await import("./db.js");

  let database;
  try {
    database = openDatabase(configuration.databaseUrl);
  } catch (error) {
    // Nothing was opened on this path, so there is nothing to unwind: report the reason without the path, which is
    // operator information and not something to put in a log that may be collected.
    abortStartup("database", error);
  }

  let server;
  try {
    server = createAccountService({ database, configuration });
  } catch (error) {
    try { database.close(); } catch { /* already closed or never usable; the startup error is what matters */ }
    abortStartup("service", error);
  }

  // Declared here, above the first listener that can call it: a bind failure is delivered asynchronously, so on a
  // busy port `shutdown()` runs before the rest of startup has executed. A `let` further down would still be in its
  // temporal dead zone at that moment, and the process would die on a ReferenceError instead of refusing cleanly.
  let closing = null;

  // A listen failure (port in use, address not bindable) arrives as an `error` event rather than a throw, so it must
  // be observed explicitly or the process would sit there having announced nothing and bound nothing.
  server.on("error", (error) => {
    log("error", { event: "account_service_listen_failed", code: typeof error?.code === "string" ? error.code : "UNKNOWN", pid: process.pid });
    void shutdown("listen_error", 2);
  });

  // `listen()` reports failure asynchronously through `error`, so the two events are raced here rather than assuming
  // the call throws; a port in use must produce one clear line and an exit code, not a silent idle process.
  const bound = await new Promise((resolve) => {
    const onListenError = () => resolve(false);
    server.once("listening", () => {
      server.removeListener("error", onListenError);
      resolve(true);
    });
    server.once("error", onListenError);
    server.listen(configuration.port, configuration.host);
  });
  if (!bound) {
    await shutdown("listen_failed", 2);
    return;
  }

  const address = server.address();
  log("info", {
    event: "account_service_started",
    environment: configuration.nodeEnvironment,
    host: configuration.host,
    port: address?.port ?? configuration.port,
    schemaVersion: SCHEMA_VERSION,
    // Deliberately absent: the database path, the auth secret, and every other configured value stay in the
    // environment. A startup line is the first thing an operator pastes into a ticket.
  });

  /**
   * Idempotent by construction: a supervisor that sends SIGTERM twice, or SIGTERM after a failed bind, must not close
   * the same handle twice or run two competing shutdowns.
   */
  function shutdown(reason, exitCode = 0) {
    if (closing) return closing;
    closing = new Promise((resolve) => {
      const finish = () => {
        clearTimeout(forceTimer);
        try { database.close(); } catch { /* the handle may already be gone; shutdown must not fail on that */ }
        log("info", { event: "account_service_stopped", reason });
        resolve();
        process.exit(exitCode);
      };
      // Idle keep-alive sockets would otherwise keep `close()` waiting until the client grows bored, which is how a
      // rolling restart turns into a SIGKILL at the end of the grace period.
      server.closeIdleConnections?.();
      const forceTimer = setTimeout(() => {
        server.closeAllConnections?.();
        log("warn", { event: "account_service_shutdown_grace_expired", reason });
      }, configuration.server.shutdownGraceMs);
      forceTimer.unref?.();
      server.close(() => finish());
    });
    return closing;
  }

  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.on(signal, () => {
      log("info", { event: "account_service_stopping", signal });
      void shutdown(signal, 0);
    });
  }
}

main().catch((error) => abortStartup("startup", error));
