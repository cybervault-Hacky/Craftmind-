#!/usr/bin/env node
/**
 * Entry point for the CraftMind account service.
 *
 * The service refuses to start without a valid configuration (see `config.js`), applies its migrations, and then serves
 * the account API over HTTP. In production it is expected to sit behind a TLS terminator, so the app always talks
 * HTTPS; the service never speaks plain HTTP to a client outside the loopback interface.
 *
 * Local development:
 *   cp .env.example .env          # then set AUTH_SECRET and DATABASE_URL
 *   set -a && . ./.env && set +a
 *   node --no-warnings=ExperimentalWarning src/index.js
 */

import { loadConfiguration, ConfigurationError } from "./config.js";
import { openDatabase, SCHEMA_VERSION } from "./db.js";
import { createAccountService } from "./server.js";

function main() {
  let configuration;
  try {
    configuration = loadConfiguration(process.env);
  } catch (error) {
    if (error instanceof ConfigurationError) {
      console.error(`CraftMind account service cannot start: ${error.message}`);
      process.exit(2);
    }
    throw error;
  }

  const database = openDatabase(configuration.databaseUrl);
  const server = createAccountService({ database, configuration });

  server.listen(configuration.port, configuration.host, () => {
    console.log(
      `CraftMind account service listening on http://${configuration.host}:${configuration.port} (schema v${SCHEMA_VERSION})`,
    );
  });

  const shutdown = (signal) => {
    console.log(`${signal} received; closing the account service`);
    server.close(() => {
      try {
        database.close();
      } finally {
        process.exit(0);
      }
    });
  };
  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
}

main();
