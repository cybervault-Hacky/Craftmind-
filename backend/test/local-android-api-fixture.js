// Test-only process fixture for AccountHttpIntegrationTest. The mailbox control below is stdin/stdout, never an HTTP route.
import { createInterface } from "node:readline";
import { loadConfiguration } from "../src/config.js";
import { openDatabase } from "../src/db.js";
import { DevelopmentEmailSink } from "../src/email-delivery.js";
import { createAccountService } from "../src/server.js";

const databaseUrl = process.env.CRAFTMIND_TEST_DATABASE;
if (!databaseUrl || databaseUrl === ":memory:") throw new Error("a file-backed test SQLite path is required");

const configuration = loadConfiguration({
  NODE_ENV: "test",
  DATABASE_URL: databaseUrl,
  AUTH_SECRET: "phase18-local-integration-secret-at-least-32-bytes",
  HOST: "127.0.0.1",
  PORT: "0",
});
const database = openDatabase(configuration.databaseUrl);
const emailDelivery = new DevelopmentEmailSink();
const quietLogger = { info() {}, warn() {}, error() {} };
const server = createAccountService({ database, configuration, emailDelivery, logger: quietLogger });
await new Promise((resolve, reject) => {
  server.once("error", reject);
  server.listen(0, "127.0.0.1", resolve);
});
process.stdout.write(`READY ${server.address().port}\n`);

const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
for await (const line of input) {
  let command;
  try {
    command = JSON.parse(line);
  } catch {
    process.stdout.write("{\"error\":\"invalid test control message\"}\n");
    continue;
  }
  if (command.action === "take-message") {
    const message = emailDelivery.takeMessage(command.kind, command.email);
    // This is an isolated test control channel; the production server exposes no mailbox/token-read route.
    process.stdout.write(`${JSON.stringify(message)}\n`);
  } else if (command.action === "stop") {
    await new Promise((resolve) => server.close(resolve));
    database.close();
    process.stdout.write("STOPPED\n");
    break;
  } else {
    process.stdout.write("{\"error\":\"unknown test control action\"}\n");
  }
}
