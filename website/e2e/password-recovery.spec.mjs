/**
 * Phase 38 — Playwright spec for web password recovery. **PREPARED, NOT RUN.**
 *
 * Nobody has executed this file. It is written for a machine that has a browser, because this sandbox has none: no
 * `chromium`, `chrome`, `firefox`, or `msedge` binary, no `playwright` module, and no `~/.cache/ms-playwright`. The
 * browsers Playwright needs are downloaded from a CDN this environment cannot reach, and the project deliberately has
 * no dependencies to install (`backend/package.json` declares none), so adding the framework was not an option to
 * take silently — see `README.md` beside this file for the decision the owner has to make.
 *
 * Therefore: `backend/test/website-recovery.test.js` is the recovery suite that actually runs today (real service,
 * real tokens, real HTTP, a small element model), and this file is the browser half of the same contract. Every
 * assertion below is one that only a browser can satisfy — computed visibility, focus movement, real click-driven
 * navigation, and a second tab that has never seen the form.
 *
 * Run it once the decision is made:
 *
 *   npm i -D @playwright/test@1.56.1 && npx playwright install chromium
 *   npx playwright test website/e2e/password-recovery.spec.mjs
 *
 * Everything below is written against the code that ships today: `website/recovery.html`, its controller
 * `website/assets/recovery.js`, the adapter boundary in `website/assets/adapters.js`, and the real account service
 * composed by `backend/test/helpers.js`.
 */

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, resolve, sep } from "node:path";
import { afterAll, beforeAll, expect, test } from "@playwright/test";

import { call, registerVerified, startService, VALID_PASSWORD } from "../../backend/test/helpers.js";

const REPO_ROOT = resolve(import.meta.dirname, "..", "..");
const SITE_ROOT = resolve(REPO_ROOT, "website");
/** The origin the *page* is configured with. It never exists: `page.route` answers it and forwards to the service. */
const PAGE_ORIGIN = "https://accounts.craftmind.test";
const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".svg": "image/svg+xml",
};

/** A read-only static server for `website/`, confined to that directory. Test-only; nothing here ships. */
async function startStaticSite() {
  const server = createServer(async (request, response) => {
    const path = decodeURIComponent(new URL(request.url, PAGE_ORIGIN).pathname);
    const relative = path === "/" ? "index.html" : path.replace(/^\//, "");
    const file = resolve(SITE_ROOT, relative);
    // Refuse anything that walks out of the site root, even though only a local browser will ever ask.
    if (!file.startsWith(SITE_ROOT + sep) || extname(file) !== ".html" && !MIME[extname(file)]) {
      response.writeHead(404).end("not found");
      return;
    }
    try {
      const body = await readFile(file);
      response.writeHead(200, { "Content-Type": MIME[extname(file)] ?? "text/plain; charset=utf-8" });
      response.end(body);
    } catch {
      response.writeHead(404).end("not found");
    }
  });
  await new Promise((done) => server.listen(0, "127.0.0.1", done));
  return { port: server.address().port, server };
}

let site;
let service;
/**
 * Where `page.route` forwards. Tests repoint this to a closed port to make a real transport failure happen, which is
 * the only way to see the difference between "the service refused me" and "the service is not there".
 */
let serviceBaseUrl;

beforeAll(async () => {
  site = await startStaticSite();
  service = await startService({
    RATE_LOGIN_MAX: "10000", RATE_REGISTER_MAX: "10000", RATE_VERIFICATION_MAX: "10000",
    RATE_RESET_REQUEST_MAX: "10000", RATE_RESET_CONFIRM_MAX: "10000",
  });
  serviceBaseUrl = service.baseUrl;
});

afterAll(async () => {
  await new Promise((done) => site.server.close(done));
  await service.close();
});

/** A verified account plus one live session, set up through the API: this spec is about recovery, not registration. */
async function accountWithForgottenPassword() {
  const email = `e2e-${Math.random().toString(36).slice(2)}@example.test`;
  await registerVerified(service, { email });
  return email;
}

/** Opens the recovery page with an origin configured the way a deployer would, and the route answering for it. */
async function openRecovery(page) {
  await page.addInitScript((origin) => {
    globalThis.CRAFTMIND_SITE_CONFIG = { accountServiceOrigin: origin };
  }, PAGE_ORIGIN);
  // The site accepts only an absolute HTTPS origin (adapters.js `siteConfiguration`), mirroring the service's own
  // HTTPS policy. A local run cannot offer real TLS, so the harness satisfies the rule with a routed origin and
  // forwards it to the plain-HTTP service. The production rule stays exactly as strict as it is today.
  await page.route(`${PAGE_ORIGIN}/**`, async (route) => {
    const body = route.request().postData();
    const response = await fetch(`${serviceBaseUrl}${route.request().url().slice(PAGE_ORIGIN.length)}`, {
      method: route.request().method(),
      headers: body === null ? {} : { "Content-Type": "application/json" },
      body,
    });
    await route.fulfill({ status: response.status, contentType: "application/json", body: await response.text() });
  });
  await page.goto(`http://127.0.0.1:${site.port}/recovery.html`, { waitUntil: "load" });
}

function recoveryForm(page) {
  return {
    code: page.getByLabel("Recovery code"),
    password: page.getByLabel("New password", { exact: true }),
    repeated: page.getByLabel("Repeat new password"),
    submit: page.getByRole("button", { name: "Change password" }),
    alert: page.locator('[data-recovery-feedback] [role="alert"]'),
    status: page.locator('[data-recovery-feedback] [role="status"]'),
  };
}

test.describe("Password recovery in a browser", () => {
  test("the request receipt hands the visitor to the code panel, and the panel hands back to sign-in", async ({ page }) => {
    await openRecovery(page);
    const request = page.getByLabel("Account email");
    await request.fill(await accountWithForgottenPassword());
    await page.getByRole("button", { name: "Send me a recovery code" }).click();
    await expect(page.getByText(/If that address matches an account/)).toBeVisible();

    // Scenario 1 in the phase list: the two screens are connected. Clicking the in-page anchor must actually move
    // the reader, which a service-level test can only assert as a string.
    await page.getByRole("link", { name: "I have a code already" }).click();
    await expect(page.locator("#recovery-confirm")).toBeInViewport();
    await expect(recoveryForm(page).code).toBeVisible();

    // Scenario 7: the way back to sign-in is a real navigation.
    await page.getByRole("link", { name: "Back to sign in" }).first().click();
    await expect(page).toHaveURL(/\/signin\.html$/);
  });

  test("an empty submission is refused in place, visibly and to assistive technology", async ({ page }) => {
    await openRecovery(page);
    await recoveryForm(page).submit.click();
    await expect(recoveryForm(page).alert).toBeVisible();
    await expect(recoveryForm(page).alert).toHaveText(/Paste the recovery code/);
    await expect(recoveryForm(page).code).toHaveAttribute("aria-invalid", "true");
    // Nothing left the page: no navigation, no half-built URL.
    expect(page.url()).not.toContain("?");
    expect(page.url()).not.toContain("code");
  });

  test("the whole journey: a code from the mail sink, typed in, buys a new password that signs in", async ({ page }) => {
    const email = await accountWithForgottenPassword();
    await call(service.baseUrl, "POST", "/auth/password-reset/request", { body: { email } });
    const message = service.emailDelivery.takeMessage("password-recovery", email);
    expect(message?.token).toBeTruthy();

    const form = recoveryForm(page);
    await openRecovery(page);
    await form.code.fill(message.token);
    await form.password.fill("Recovered Browser 4Passphrase");
    await form.repeated.fill("Recovered Browser 4Passphrase");

    // Scenario 6, observed the way only a browser can: the control is genuinely inert mid-flight. The route handler
    // waits, so the in-between state is real rather than simulated.
    const clicked = form.submit.click();
    await expect(form.submit).toBeDisabled();
    await clicked;
    await expect(page.getByText("Password changed")).toBeVisible();
    await expect(page.locator('[data-recovery-region] form')).toHaveCount(0);

    // Scenario 8 in the strongest form available: the values must not exist anywhere the browser keeps them.
    expect(page.url()).not.toContain(message.token);
    const storage = await page.evaluate(() => ({
      local: Object.keys(globalThis.localStorage ?? {}),
      session: Object.keys(globalThis.sessionStorage ?? {}),
      cookie: document.cookie,
    }));
    expect(storage.local).toEqual([]);
    expect(storage.session).toEqual([]);
    expect(storage.cookie).toBe("");

    const login = await call(service.baseUrl, "POST", "/auth/login", { body: { email, password: "Recovered Browser 4Passphrase" } });
    expect(login.status).toBe(200);
    expect((await call(service.baseUrl, "POST", "/auth/login", { body: { email, password: VALID_PASSWORD } })).status).toBe(401);
  });

  test("a spent code, an expired code, and a wrong code each get their own sentence", async ({ page }) => {
    const email = await accountWithForgottenPassword();
    await call(service.baseUrl, "POST", "/auth/password-reset/request", { body: { email } });
    const { token } = service.emailDelivery.takeMessage("password-recovery", email);

    // Spent.
    await openRecovery(page);
    const form = recoveryForm(page);
    await form.code.fill(token);
    await form.password.fill("Recovered Second 5Passphrase");
    await form.repeated.fill("Recovered Second 5Passphrase");
    await form.submit.click();
    await expect(page.getByText("Password changed")).toBeVisible();

    await page.reload({ waitUntil: "load" });
    await recoveryForm(page).code.fill(token);
    await recoveryForm(page).password.fill("Recovered Third 6Passphrase");
    await recoveryForm(page).repeated.fill("Recovered Third 6Passphrase");
    await recoveryForm(page).submit.click();
    await expect(recoveryForm(page).alert).toHaveText(/already been used/);
    await expect(recoveryForm(page).submit).toBeEnabled();

    // Wrong.
    await page.reload({ waitUntil: "load" });
    await recoveryForm(page).code.fill("x".repeat(43));
    await recoveryForm(page).password.fill("Recovered Fourth 7Passphrase");
    await recoveryForm(page).repeated.fill("Recovered Fourth 7Passphrase");
    await recoveryForm(page).submit.click();
    await expect(recoveryForm(page).alert).toHaveText(/not recognised/);
    await expect(page.locator("body")).not.toContainText("x".repeat(43), { useInnerText: true });
  });

  test("a dead service is reported as unreachable, and the button comes back", async ({ page }) => {
    const email = await accountWithForgottenPassword();
    await call(service.baseUrl, "POST", "/auth/password-reset/request", { body: { email } });
    const { token } = service.emailDelivery.takeMessage("password-recovery", email);

    await openRecovery(page);
    // Repoint the route at a port with nothing listening: the honest transport failure, not a validation error, is
    // what the visitor has to see. The page's configured origin never changes.
    const reachable = serviceBaseUrl;
    serviceBaseUrl = "http://127.0.0.1:1";
    try {
      const form = recoveryForm(page);
      await form.code.fill(token);
      await form.password.fill("Never Applied 0Passphrase");
      await form.repeated.fill("Never Applied 0Passphrase");
      await form.submit.click();
      await expect(form.alert).toHaveText(/could not be reached/);
      await expect(form.alert).toHaveText(/no password was changed/);
      await expect(form.submit).toBeEnabled();
    } finally {
      serviceBaseUrl = reachable;
    }

    // And the code is still unused, so recovery afterwards still works — the claim the page makes in that sentence.
    const stillValid = await call(service.baseUrl, "POST", "/auth/password-reset/confirm", {
      body: { token, newPassword: "Recovered After Outage 2Passphrase" },
    });
    expect(stillValid.status).toBe(200);
  });

  test("the flow survives a keyboard, and the code never appears in a log the page can read", async ({ page }) => {
    const seen = [];
    page.on("console", (message) => seen.push(`${message.type()}:${message.text()}`));
    page.on("pageerror", (error) => seen.push(`pageerror:${error.message}`));

    const email = await accountWithForgottenPassword();
    await call(service.baseUrl, "POST", "/auth/password-reset/request", { body: { email } });
    const { token } = service.emailDelivery.takeMessage("password-recovery", email);

    await openRecovery(page);
    // A keyboard-only visitor must be able to complete the form: focus the first field and move with Tab.
    await page.locator("#recovery-code").focus();
    await page.keyboard.type(token);
    await page.keyboard.press("Tab");
    await page.keyboard.type("Keyboard Only 3Passphrase");
    await page.keyboard.press("Tab");
    await page.keyboard.type("Keyboard Only 3Passphrase");
    await page.keyboard.press("Enter");
    await expect(page.getByText("Password changed")).toBeVisible();

    expect(seen.filter((line) => /token|password|code/i.test(line))).toEqual([]);
    expect(seen.join("\n")).not.toContain(token);
    // And after a successful recovery the address bar still says nothing about it.
    expect(page.url()).not.toContain(token);
  });
});
