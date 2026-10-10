/**
 * Phase 38: the web password-recovery screen, driven end to end.
 *
 * Read the honesty of the title first. This is a **DOM-level integration** suite, not browser E2E. It imports the
 * actual controller module the site loads (`website/assets/recovery.js`) and the actual page markup
 * (`website/recovery.html`), runs them over the deliberately small element model defined below, and sends their
 * requests to the **real account service** started from `backend/src/server.js` — real router, real scrypt hashing,
 * real SQLite, real one-time tokens read out of the development mail sink. Nothing about the service side is mocked.
 *
 * What that does and does not prove:
 *
 *   * it proves the page's markup hooks, the controller's wiring, the exact wire shape, the state transitions, the
 *     submit lock, and the absence of secret material in rendered output;
 *   * it does **not** prove CSS, layout, real event semantics, focus movement, screen-reader behaviour, or the
 *     navigation a browser performs when it follows a link. Those need a browser. This sandbox has none and cannot
 *     install one, so `website/e2e/` carries a Playwright spec that is **PREPARED, NOT RUN**, recorded as BLOCKED.
 *     Do not read the green below as that run.
 *
 * The element model is ~130 lines of test-only code because the project ships no front-end test dependency and
 * `backend/package.json` has no dependencies at all. It implements only what these two modules touch, and it throws
 * rather than pretending when asked for more (an unsupported selector is an error, not an empty match).
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { describe, test } from "node:test";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { oneTimeTokenDigest } from "../src/ids.js";
import { call, loginCall, registerVerified, startService, TEST_SECRET, VALID_PASSWORD } from "./helpers.js";

const REPO_ROOT = resolve(import.meta.dirname, "..", "..");
const RECOVERY_PAGE = resolve(REPO_ROOT, "website/recovery.html");
const RECOVERY_MODULE = pathToFileURL(resolve(REPO_ROOT, "website/assets/recovery.js")).href;
const CONFIRM_PATH = "/auth/password-reset/confirm";
const REQUEST_PATH = "/auth/password-reset/request";
const FRESH_PASSWORD = "Recovered Horse 9Battery";

const RELAXED = Object.freeze({
  RATE_LOGIN_MAX: "10000", RATE_REGISTER_MAX: "10000", RATE_VERIFICATION_MAX: "10000",
  RATE_RESET_REQUEST_MAX: "10000", RATE_RESET_CONFIRM_MAX: "10000",
});

// --------------------------------------------------------------------------------------- the test-only element model

const VOID_TAGS = new Set(["area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"]);
const TAG_RE = /<(\/)?([a-zA-Z][a-zA-Z0-9-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(\/)?>/g;
const ATTR_RE = /([^\s=/]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?/g;
const FORM_FIELDS = new Set(["input", "button", "select", "textarea"]);

class TextNode {
  constructor(data) { this.nodeType = 3; this.data = data; }
}

class Element {
  constructor(tagName, attributes = {}) {
    this.nodeType = 1;
    this.tagName = tagName;
    this.attributes = attributes;
    this.children = [];
    this.dataset = {};
    this.value = "";
    this.disabled = false;
    this._source = "";
    this._listeners = new Map();
  }

  static parse(html) {
    const roots = [];
    const stack = [{ children: roots }];
    let cursor = 0;
    const pushText = (text) => {
      if (!text || !text.trim()) return;
      stack[stack.length - 1].children.push(new TextNode(text));
    };
    for (const match of String(html).matchAll(TAG_RE)) {
      pushText(html.slice(cursor, match.index));
      cursor = match.index + match[0].length;
      const [, closing, rawName, attributeText, selfClosed] = match;
      const name = rawName.toLowerCase();
      if (closing) {
        for (let index = stack.length - 1; index > 0; index -= 1) {
          if (stack[index].tagName === name) { stack.length = index; break; }
        }
        continue;
      }
      const element = new Element(name, readAttributes(attributeText));
      stack[stack.length - 1].children.push(element);
      if (!selfClosed && !VOID_TAGS.has(name)) stack.push(element);
    }
    pushText(html.slice(cursor));
    return roots;
  }

  getAttribute(name) { return name in this.attributes ? this.attributes[name] : null; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  removeAttribute(name) { delete this.attributes[name]; }
  append(child) { this.children.push(child); }
  get id() { return this.getAttribute("id") ?? ""; }
  set id(value) { this.setAttribute("id", value); }
  get className() { return this.getAttribute("class") ?? ""; }
  set className(value) { this.setAttribute("class", value); }

  // The markup a browser would have produced. `value` is never reflected here, exactly as in a real form, which is
  // what makes the "no secret in the document" assertions meaningful rather than tautological.
  get innerHTML() { return this._source; }
  set innerHTML(html) {
    this._source = String(html);
    this.children = Element.parse(this._source);
  }

  get textContent() { return collectText(this); }
  set textContent(value) { this._source = String(value ?? ""); this.children = [new TextNode(this._source)]; }

  *descendants() {
    for (const child of this.children) {
      if (child.nodeType === 1) { yield child; yield* child.descendants(); }
    }
  }

  matches(selector) {
    const sel = String(selector).trim();
    if (sel.startsWith("[")) {
      const parsed = /^\[([^\]=]+)(?:=\s*["']?([^"'\]]*)["']?)?\]$/.exec(sel);
      if (!parsed) throw new Error(`the test element model does not support the selector ${selector}`);
      const [, name, value] = parsed;
      return value === undefined ? this.getAttribute(name) !== null : this.getAttribute(name) === value;
    }
    if (sel.startsWith("#")) return this.getAttribute("id") === sel.slice(1);
    if (sel.startsWith(".")) return this.className.split(/\s+/).includes(sel.slice(1));
    if (/[\s>]/.test(sel)) throw new Error(`the test element model does not support the selector ${selector}`);
    return this.tagName === sel.toLowerCase();
  }

  querySelectorAll(selector) { return [...this.descendants()].filter((node) => node.matches(selector)); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }

  /** `form.elements.<name>` — the only form API these modules use. */
  get elements() {
    if (this.tagName !== "form") return undefined;
    const named = {};
    for (const field of this.descendants()) {
      if (!FORM_FIELDS.has(field.tagName)) continue;
      for (const key of [field.getAttribute("name"), field.getAttribute("id")]) {
        if (key && !(key in named)) named[key] = field;
      }
    }
    return named;
  }

  addEventListener(type, handler) {
    if (!this._listeners.has(type)) this._listeners.set(type, []);
    this._listeners.get(type).push(handler);
  }

  /** Fires one listener set synchronously and hands back the event, so `preventDefault` is observable. */
  dispatch(type) {
    let prevented = false;
    const event = {
      type,
      target: this,
      preventDefault() { prevented = true; },
      get defaultPrevented() { return prevented; },
    };
    for (const handler of this._listeners.get(type) ?? []) handler.call(this, event);
    return event;
  }
}

function collectText(node) {
  if (node.nodeType === 3) return node.data;
  return node.children.map(collectText).join(" ");
}

function readAttributes(text) {
  const attributes = {};
  for (const match of String(text ?? "").matchAll(ATTR_RE)) {
    const [, name, quoted, single, bare] = match;
    attributes[name.toLowerCase()] = quoted ?? single ?? bare ?? "";
  }
  return attributes;
}

/** The smallest `document` these modules need: a root walk, one lookup by id, one append target. */
function createDocument(html) {
  const roots = Element.parse(html);
  const documentElement = roots.find((node) => node.nodeType === 1 && node.tagName === "html")
    ?? roots.find((node) => node.nodeType === 1);
  if (!documentElement) throw new Error("the recovery page did not parse into an element tree");
  const body = documentElement.querySelector("body") ?? documentElement;
  return {
    documentElement,
    body,
    createElement(tagName) { return new Element(String(tagName).toLowerCase()); },
    querySelector(selector) { return documentElement.querySelector(selector); },
    querySelectorAll(selector) { return documentElement.querySelectorAll(selector); },
    getElementById(id) {
      if (documentElement.getAttribute("id") === id) return documentElement;
      return [...documentElement.descendants()].find((node) => node.getAttribute("id") === id) ?? null;
    },
  };
}

/** Everything the page could have written down: markup sources, text nodes, and attribute values. */
function serializedDocument(root) {
  const parts = [];
  const walk = (node) => {
    parts.push(node._source ?? "");
    for (const value of Object.values(node.attributes)) parts.push(String(value));
    for (const child of node.children) {
      if (child.nodeType === 3) parts.push(child.data);
      else walk(child);
    }
  };
  walk(root);
  return parts.join("\n");
}

// ------------------------------------------------------------------------------------------------ the harness

const { initRecovery } = await import(RECOVERY_MODULE);

let service;
test.before(async () => { service = await startService({ ...RELAXED }); });
test.after(async () => { await service?.close(); });

async function waitFor(predicate, { timeoutMs = 5000, intervalMs = 5 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() > deadline) return false;
    await new Promise((resolvePromise) => setTimeout(resolvePromise, intervalMs));
  }
  return true;
}

/** A verified account holding one live session — the state a forgotten password actually leaves you in. */
async function verifiedAccount(email) {
  const created = await registerVerified(service, { email });
  assert.equal(created.status, 201, JSON.stringify(created.body));
  return created.body.session.accessToken;
}

async function requestRecoveryCode(email) {
  const response = await call(service.baseUrl, "POST", REQUEST_PATH, { body: { email } });
  assert.equal(response.status, 202, JSON.stringify(response.body));
  const message = service.emailDelivery.takeMessage("password-recovery", email);
  assert.ok(message?.token, "the development mail sink must hand over the recovery code");
  return message.token;
}

/**
 * Mounts the real page against a real (or deliberately fake) service.
 *
 * `poisonedSearch` exists for the URL test: the harness advertises a code in the query string so the assertion can
 * prove the controller ignores it. `origin: null` reproduces the state of the published static site.
 */
async function mount({ origin = service.baseUrl, poisonedSearch = "" } = {}) {
  const document = createDocument(readFileSync(RECOVERY_PAGE, "utf8"));
  const search = poisonedSearch;
  const previousDocument = globalThis.document;
  const previousLocation = globalThis.location;
  globalThis.document = document;
  globalThis.location = { pathname: "/recovery.html", search, href: `/recovery.html${search}` };
  const root = document.querySelector('[data-page="recovery"]');
  assert.ok(root, "the recovery page must be keyed for its controller");
  await initRecovery(root, { origin });
  return {
    document,
    root,
    panels: panelsOf(root),
    restore() {
      globalThis.document = previousDocument;
      globalThis.location = previousLocation;
    },
  };
}

function panelsOf(root) {
  const confirm = root.querySelector("[data-recovery-region]");
  const request = root.querySelector("[data-recovery-request-region]");
  return {
    confirm,
    request,
    form: confirm?.querySelector("[data-recovery-form]") ?? null,
    feedback: confirm?.querySelector("[data-recovery-feedback]") ?? null,
    submit: confirm?.querySelector("[data-recovery-submit]") ?? null,
    requestForm: request?.querySelector("[data-recovery-request-form]") ?? null,
    requestFeedback: request?.querySelector("[data-recovery-request-feedback]") ?? null,
    requestSubmit: request?.querySelector("[data-recovery-request-submit]") ?? null,
  };
}

function installFetchSpy({ holdPath = null } = {}) {
  const realFetch = globalThis.fetch;
  const calls = [];
  let release = null;
  globalThis.fetch = (input, init = {}) => {
    const record = { url: String(input), method: init.method ?? "GET", body: init.body ?? null, headers: init.headers ?? {}, response: null };
    calls.push(record);
    const forwarded = realFetch(input, init).then(async (response) => {
      try { record.response = await response.clone().json(); } catch { record.response = null; }
      return response;
    });
    if (holdPath && record.url.endsWith(holdPath)) {
      return new Promise((resolvePromise) => { release = () => resolvePromise(forwarded); });
    }
    return forwarded;
  };
  return {
    calls,
    to(pathSuffix) { return calls.filter((record) => record.url.endsWith(pathSuffix)); },
    /** The parsed JSON body of the first request to a path — the wire shape, as the server will read it. */
    bodyOf(pathSuffix) { const first = this.to(pathSuffix)[0]; return first?.body ? JSON.parse(first.body) : null; },
    async release() { const held = release; release = null; if (held) held(); },
    restore() { globalThis.fetch = realFetch; },
  };
}

function captureConsole() {
  const originals = {};
  const lines = [];
  for (const level of ["log", "info", "warn", "error", "debug", "trace"]) {
    originals[level] = console[level];
    console[level] = (...args) => { lines.push(args.map((arg) => (typeof arg === "string" ? arg : JSON.stringify(arg))).join(" ")); };
  }
  return { lines, restore() { for (const [level, fn] of Object.entries(originals)) console[level] = fn; } };
}

/**
 * A stand-in for responses the real service will not produce from a valid request, so the page's translation of a
 * typed error code can be seen. Answers whatever `answer` currently holds, and records what it was asked.
 */
async function startStubService() {
  const state = { answer: { status: 400, body: {} }, requests: [] };
  const server = createServer((request, response) => {
    let raw = "";
    request.on("data", (chunk) => { raw += chunk; });
    request.on("end", () => {
      state.requests.push({ url: request.url, body: raw });
      response.writeHead(state.answer.status, { "Content-Type": "application/json" });
      response.end(JSON.stringify(state.answer.body));
    });
  });
  await new Promise((done) => server.listen(0, "127.0.0.1", done));
  const { port } = server.address();
  return {
    origin: `http://127.0.0.1:${port}`,
    state,
    setAnswer(status, body) { state.answer = { status, body }; },
    async close() { await new Promise((done) => server.close(done)); },
  };
}

let accountCounter = 0;
const nextEmail = () => `recovery-ui-${(accountCounter += 1)}@example.test`;

// ------------------------------------------------------------------------------------------- the eight scenarios

describe("Phase 38 website recovery: the screen speaks the service's contract", () => {
  test("1. the page renders both halves and connects request → confirm → sign-in", async () => {
    const { root, panels, restore } = await mount();
    try {
      const { confirm, request, form, submit, requestForm } = panels;
      assert.ok(confirm && request, "both regions the checker declares must exist on the page");
      assert.ok(form, "the controller must render the code-entry form into the confirm region");
      assert.ok(requestForm, "and the request form into the request region");
      assert.equal(submit.textContent.trim(), "Change password");
      // Navigation, as far as markup can prove it (a browser would also have to move focus and scroll).
      assert.ok(requestForm.querySelector('[href="#recovery-confirm"]'), "the request panel must offer the code-entry panel");
      assert.equal(confirm.querySelector('[href="#recovery-confirm"]'), null, "the confirm panel must not point at itself");
      assert.ok(root.querySelector('[href="signin.html"]'), "the screen must carry a way back to sign in");
      assert.equal(root.getAttribute("data-page"), "recovery");
      const page = readFileSync(RECOVERY_PAGE, "utf8");
      assert.ok(page.includes('id="recovery-confirm"'), "the anchor target must be a real id, not a dead link");

      const account = readFileSync(resolve(REPO_ROOT, "website/assets/account.js"), "utf8");
      const signin = readFileSync(resolve(REPO_ROOT, "website/assets/onboarding.js"), "utf8");
      assert.ok(account.includes('href="../recovery.html"'), "the security page's request receipt must lead to this screen");
      assert.ok(signin.includes('href="recovery.html"'), "the sign-in page's forgotten-password control must lead here");
      assert.equal(signin.includes('href="security.html">Forgot'), false, "the old relative link resolved to a page that never existed");
    } finally { restore(); }
  });

  test("2a. required fields are refused locally, with no request sent and the code left unspent", async () => {
    const { panels, restore } = await mount();
    const spy = installFetchSpy();
    try {
      const { form, feedback, submit } = panels;
      const event = form.dispatch("submit");
      assert.equal(event.defaultPrevented, true, "the controller must own the submit, not leave it to the browser");
      assert.deepEqual(spy.calls, [], "an empty form must not reach the network");
      assert.match(feedback.textContent, /Paste the recovery code/);
      assert.equal(form.elements.code.getAttribute("aria-invalid"), "true", "the failing field is announced, not just coloured");
      assert.equal(form.elements.code.getAttribute("aria-describedby"), "recovery-code-hint recovery-feedback", "and it points at the message that explains why");
      assert.equal(form.elements.confirmPassword.getAttribute("aria-describedby"), "recovery-password-confirm-hint recovery-feedback");
      assert.equal(submit.disabled, false, "a refused submission is not in flight, so the control stays usable");

      // A truncated paste gets its own message, because "not recognised" would send the visitor hunting for a new code.
      form.elements.code.value = "short";
      form.elements.newPassword.value = FRESH_PASSWORD;
      form.elements.confirmPassword.value = FRESH_PASSWORD;
      form.dispatch("submit");
      assert.match(feedback.textContent, /cut short/);
      assert.equal(spy.calls.length, 0);

      // The password rule is stated locally so a typo never costs a one-time code.
      form.elements.code.value = "z".repeat(43);
      form.elements.newPassword.value = "Ab9";
      form.elements.confirmPassword.value = "Ab9";
      form.dispatch("submit");
      assert.match(feedback.textContent, /at least 10 characters/);
      assert.equal(spy.calls.length, 0);

      form.elements.newPassword.value = "aaaaaaaaaaaa";   // long enough, one character class
      form.elements.confirmPassword.value = "aaaaaaaaaaaa";
      form.dispatch("submit");
      assert.match(feedback.textContent, /two kinds of character/);

      form.elements.newPassword.value = FRESH_PASSWORD;
      form.elements.confirmPassword.value = "Different Horse 9Battery";
      form.dispatch("submit");
      assert.match(feedback.textContent, /do not match/);
      assert.equal(spy.calls.length, 0, "a mismatch must not spend the one-time code");
      assert.equal(form.elements.code.getAttribute("aria-invalid"), null, "the code is valid, so its flag clears");
    } finally { spy.restore(); restore(); }
  });

  test("2b. a code advertised in the URL is ignored, never read back out of the address", async () => {
    const stolen = "q".repeat(43);
    const { root, panels, restore } = await mount({ poisonedSearch: `?code=${stolen}&token=${stolen}` });
    try {
      const { form } = panels;
      for (const name of ["code", "newPassword", "confirmPassword"]) {
        assert.equal(form.elements[name].value, "", `${name} starts empty whatever the address bar claims`);
      }
      assert.equal(serializedDocument(root).includes(stolen), false, "a URL code must never reach the rendered page");
      const source = readFileSync(resolve(REPO_ROOT, "website/assets/recovery.js"), "utf8");
      assert.match(source, /no CraftMind page reads a recovery code from a URL parameter/);
      assert.equal(/location\.search|URLSearchParams|location\.hash/.test(source), false, "the controller must not parse the address at all");
    } finally { restore(); }
  });

  test("3. a valid code changes the password, revokes the session, and leaves no secret behind", async () => {
    const email = nextEmail();
    await verifiedAccount(email);
    const code = await requestRecoveryCode(email);
    const { root, panels, restore } = await mount();
    const spy = installFetchSpy({ holdPath: CONFIRM_PATH });
    const logged = captureConsole();
    try {
      const { confirm, form, feedback, submit } = panels;
      form.elements.code.value = `  ${code}\n `;   // pasted codes arrive with whitespace; the service would refuse it
      form.elements.newPassword.value = FRESH_PASSWORD;
      form.elements.confirmPassword.value = FRESH_PASSWORD;

      form.dispatch("submit");
      assert.equal(await waitFor(() => spy.to(CONFIRM_PATH).length === 1), true, "the form must issue exactly one request");
      // The loading state, observed while the request is genuinely in flight rather than asserted after the fact.
      assert.equal(feedback.textContent.trim(), "Changing your password…");
      assert.ok(feedback.querySelector('[role="status"]'), "and it is announced, not just written");
      assert.equal(submit.disabled, true, "the control is locked while the request is outstanding");

      form.dispatch("submit");   // 6. duplicate submissions, attempted while the first is still open
      form.dispatch("submit");
      assert.equal(spy.to(CONFIRM_PATH).length, 1, "a duplicate submit must not reach the service");

      await spy.release();
      assert.equal(await waitFor(() => confirm.dataset.state === "success"), true, `expected success, saw: ${feedback.textContent}`);

      assert.deepEqual(spy.bodyOf(CONFIRM_PATH), { token: code, newPassword: FRESH_PASSWORD }, "the body is exactly what confirmPasswordRecovery reads");
      assert.equal(spy.to(CONFIRM_PATH)[0].headers["Content-Type"], "application/json");
      assert.equal(spy.to(CONFIRM_PATH)[0].headers.Authorization, undefined, "recovery happens signed out, so no credential travels with it");
      assert.equal(spy.to(CONFIRM_PATH)[0].response?.reset, true);
      assert.equal(spy.to(CONFIRM_PATH)[0].response?.revokedSessions, 1, "the session that registered is revoked by the reset");

      const rendered = serializedDocument(root);
      assert.match(confirm.textContent, /Password changed/);
      assert.match(confirm.textContent, /revoked 1 existing session\./, "the count is the server's, in the page's own words");
      assert.ok(confirm.querySelector('[href="signin.html"]'), "7. success must offer the way back to sign in");
      assert.equal(confirm.querySelector("[data-recovery-form]"), null, "the form is replaced, so there is nothing left to resubmit");
      assert.equal(rendered.includes(code), false, "the spent code must not linger in the document");
      assert.equal(rendered.includes(FRESH_PASSWORD), false, "the new password must never appear in the document");
      assert.equal(logged.lines.length, 0, "8. the controller writes nothing to the console");
      const live = globalThis.document.getElementById("site-live-region");
      assert.equal(live.textContent, "Password changed. Sign in with your new password.");
    } finally { spy.restore(); logged.restore(); restore(); }

    // The service, not the page, is the authority: the old password is dead and the new one works.
    const stale = await loginCall(service.baseUrl, { email, password: VALID_PASSWORD });
    assert.equal(stale.status, 401, JSON.stringify(stale.body));
    const fresh = await loginCall(service.baseUrl, { email, password: FRESH_PASSWORD });
    assert.equal(fresh.status, 200, JSON.stringify(fresh.body));
  });

  test("4. a spent code is refused on its second use, and the page does not pretend", async () => {
    const email = nextEmail();
    await verifiedAccount(email);
    const code = await requestRecoveryCode(email);

    const first = await mount();
    try {
      const { form, feedback, submit } = first.panels;
      form.elements.code.value = code;
      form.elements.newPassword.value = FRESH_PASSWORD;
      form.elements.confirmPassword.value = FRESH_PASSWORD;
      form.dispatch("submit");
      assert.equal(await waitFor(() => first.panels.confirm.dataset.state === "success"), true, feedback.textContent);
      assert.equal(submit.disabled, true, "a consumed code leaves the control sealed, not re-armed");
    } finally { first.restore(); }

    const second = await mount();   // what a visitor does next: paste the same code again
    try {
      const { form, feedback, confirm } = second.panels;
      form.elements.code.value = code;
      form.elements.newPassword.value = FRESH_PASSWORD;
      form.elements.confirmPassword.value = FRESH_PASSWORD;
      form.dispatch("submit");
      assert.equal(await waitFor(() => second.panels.submit.disabled === false), true, "the request must settle before the panel is read");
      assert.match(feedback.textContent, /already been used/);
      assert.equal(confirm.dataset.state === "success", false, "a refusal must not render as a success");
      assert.equal(second.panels.submit.disabled, false, "the fields stay usable after a refusal");
      assert.equal(serializedDocument(second.root).includes(code), false);
    } finally { second.restore(); }
  });

  test("5a. an expired and an unrecognised code are refused with the next step, and the password survives", async () => {
    const email = nextEmail();
    await verifiedAccount(email);
    const code = await requestRecoveryCode(email);
    // Backdate the row the way the service's own expiry test does: a real expiry, not an assertion about one.
    const digest = oneTimeTokenDigest(TEST_SECRET, "password-recovery-v1", code);
    service.database.prepare("UPDATE password_recovery_tokens SET expires_at = ? WHERE token_digest = ?")
      .run("2000-01-01T00:00:00.000Z", digest);

    const expired = await mount();
    try {
      const { form, feedback } = expired.panels;
      form.elements.code.value = code;
      form.elements.newPassword.value = FRESH_PASSWORD;
      form.elements.confirmPassword.value = FRESH_PASSWORD;
      form.dispatch("submit");
      assert.equal(await waitFor(() => feedback.textContent.includes("expired")), true, feedback.textContent);
      assert.match(feedback.textContent, /Request a new one below/);
      assert.equal(serializedDocument(expired.root).includes(code), false, "an expired code is named, never repeated");
    } finally { expired.restore(); }

    const garbage = await mount();
    try {
      const { form, feedback } = garbage.panels;
      form.elements.code.value = "x".repeat(43);
      form.elements.newPassword.value = FRESH_PASSWORD;
      form.elements.confirmPassword.value = FRESH_PASSWORD;
      form.dispatch("submit");
      assert.equal(await waitFor(() => feedback.textContent.includes("not recognised")), true, feedback.textContent);
      assert.equal(serializedDocument(garbage.root).includes("x".repeat(43)), false, "the refused code is not echoed back");
    } finally { garbage.restore(); }

    assert.equal((await loginCall(service.baseUrl, { email, password: VALID_PASSWORD })).status, 200, "a refused recovery must not change the password");
  });

  test("5b. a server refusal and a dead transport are told apart, and neither fakes success", async () => {
    // The real service has no way to answer these from a request this form can build (the client mirrors its rules),
    // so a stub speaks the codes directly. This is the honest shape of "mocked": only the refusal text, never the flow.
    const stub = await startStubService();
    try {
      const invalid = await mount({ origin: stub.origin });
      try {
        stub.setAnswer(400, { error: { code: "INVALID_PASSWORD", message: "That password does not meet CraftMind's password requirements." } });
        const { form, feedback } = invalid.panels;
        form.elements.code.value = "w".repeat(43);
        form.elements.newPassword.value = FRESH_PASSWORD;
        form.elements.confirmPassword.value = FRESH_PASSWORD;
        form.dispatch("submit");
        assert.equal(await waitFor(() => feedback.textContent.includes("password requirements")), true, feedback.textContent);
        assert.match(feedback.textContent, /Use at least 10 characters with two kinds of character/);
        assert.equal(invalid.panels.confirm.dataset.state === "success", false);
        assert.equal(stub.state.requests.length, 1);
        assert.deepEqual(JSON.parse(stub.state.requests[0].body), { token: "w".repeat(43), newPassword: FRESH_PASSWORD });
        assert.equal(stub.state.requests[0].url, CONFIRM_PATH, "and only that route is called");
      } finally { invalid.restore(); }

      const throttled = await mount({ origin: stub.origin });
      try {
        stub.setAnswer(429, { error: { code: "RATE_LIMITED", message: "Too many attempts. Wait before trying again." } });
        const { form, feedback, submit } = throttled.panels;
        form.elements.code.value = "w".repeat(43);
        form.elements.newPassword.value = FRESH_PASSWORD;
        form.elements.confirmPassword.value = FRESH_PASSWORD;
        form.dispatch("submit");
        assert.equal(await waitFor(() => throttled.panels.submit.disabled === false), true);
        assert.match(feedback.textContent, /Too many attempts/);
        assert.equal(submit.disabled, false, "a refusal returns the control to the visitor");
        // An unrecognised code still surfaces the server's own words rather than a generic failure.
        assert.equal(serializedDocument(throttled.root).includes("w".repeat(43)), false);
      } finally { throttled.restore(); }
    } finally { await stub.close(); }

    // And the transport failure: a port with nothing listening behind it.
    const orphan = await startService({ ...RELAXED });
    const unreachableOrigin = orphan.baseUrl;
    await orphan.close();
    const offline = await mount({ origin: unreachableOrigin });
    const logged = captureConsole();
    try {
      const { form, feedback, submit, confirm } = offline.panels;
      form.elements.code.value = "y".repeat(43);
      form.elements.newPassword.value = FRESH_PASSWORD;
      form.elements.confirmPassword.value = FRESH_PASSWORD;
      form.dispatch("submit");
      assert.equal(await waitFor(() => offline.panels.submit.disabled === false && feedback.textContent.includes("could not be reached")), true, feedback.textContent);
      assert.match(feedback.textContent, /no password was changed/);
      assert.match(feedback.textContent, /Your code is still unused/);
      assert.equal(submit.disabled, false, "a network failure must not strand the visitor with a dead button");
      assert.equal(confirm.dataset.state === "success", false);
      assert.equal(logged.lines.length, 0, "5c. a transport error is not printed with the form values in hand");
    } finally { logged.restore(); offline.restore(); }
  });

  test("6. the request panel answers once, neutrally, and never claims a delivery", async () => {
    const unknownEmail = nextEmail();
    const unknown = await mount();
    const spy = installFetchSpy();
    let unknownReceipt = "";
    try {
      const { requestForm, requestFeedback, requestSubmit } = unknown.panels;
      requestForm.dispatch("submit");
      assert.match(requestFeedback.textContent, /Enter the account email address/);
      assert.equal(spy.to(REQUEST_PATH).length, 0, "an empty request must not be sent");

      requestForm.elements.email.value = unknownEmail;
      requestForm.dispatch("submit");
      requestForm.dispatch("submit");   // the same double-tap, on the half of the page that sends mail
      assert.equal(await waitFor(() => spy.to(REQUEST_PATH).length === 1), true);
      assert.equal(await waitFor(() => requestSubmit.disabled === false), true, "the request must settle before its receipt is read");
      assert.equal(spy.to(REQUEST_PATH).length, 1, "a double-tapped request must not queue a second code");
      assert.deepEqual(spy.bodyOf(REQUEST_PATH), { email: unknownEmail });
      unknownReceipt = requestFeedback.textContent;
      assert.match(unknownReceipt, /If that address matches an account/);
      assert.match(unknownReceipt, /cannot confirm that an account exists/);
      assert.equal(unknownReceipt.toLowerCase().includes("has been sent"), false, "no delivery is claimed");
    } finally { spy.restore(); unknown.restore(); }

    const knownEmail = nextEmail();
    await verifiedAccount(knownEmail);
    const known = await mount();
    try {
      const { requestForm, requestFeedback, requestSubmit } = known.panels;
      requestForm.elements.email.value = knownEmail;
      requestForm.dispatch("submit");
      assert.equal(await waitFor(() => requestSubmit.disabled === false), true);
      assert.equal(requestFeedback.textContent, unknownReceipt, "an eligible account and an unknown address read identically");
      const mail = service.emailDelivery.takeMessage("password-recovery", knownEmail);
      assert.ok(mail?.token, "the code went to the mail boundary, not to the page");
      assert.equal(requestFeedback.textContent.includes(mail.token), false, "the receipt never carries the code");
      assert.equal(serializedDocument(known.root).includes(mail.token), false, "nor does anywhere else on the page");
    } finally { known.restore(); }
  });

  test("8. with no account service configured the screen states that instead of imitating a form", async () => {
    const { panels, restore } = await mount({ origin: null });
    try {
      const { confirm, request, form } = panels;
      assert.equal(form, null, "no form may be offered against a service that cannot answer");
      assert.equal(confirm.dataset.state, "unavailable");
      assert.equal(request.dataset.state, "unavailable");
      assert.match(confirm.textContent, /No account service is configured for this site/);
      assert.match(request.textContent, /Nothing was queued or sent/);
    } finally { restore(); }
  });
});
