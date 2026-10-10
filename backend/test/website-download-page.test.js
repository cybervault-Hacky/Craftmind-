/**
 * Final phase: the download page's control cannot be enabled by anything other than a verified asset.
 *
 * This is a module test with a hand-built DOM, not a browser test and not presented as one: there is no engine here,
 * so nothing about layout, focus, a real tap, or an actual install is observed (that gap stays recorded as NOT RUN in
 * docs/release-readiness.md). What it does prove is the controller's own safety property — for each answer the release
 * check can give, the page ends either with the disabled control standing or with the sole enabled thing being the
 * link GitHub actually returned. The controller is imported unmodified from `website/assets/download.js`; only
 * `document` is fabricated, and it is fabricated to behave like the DOM where this page could get hurt: assigning
 * `innerHTML` throws, and replacing an element's text really does remove its children.
 */
import assert from "node:assert/strict";
import test, { describe } from "node:test";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const REPO_ROOT = resolve(import.meta.dirname, "..", "..");
const WEBSITE_ROOT = resolve(REPO_ROOT, "website");
const SLUG = "cybervault-Hacky/Craftmind-";
const ASSET_URL = `https://github.com/${SLUG}/releases/download/v1.0.0/craftmind-v1.0.0.apk`;

/** The slice of element behaviour the controller touches, with the two sharp edges it must not lean on. */
function makeElement(tag, attributes = {}) {
  return {
    tagName: tag,
    attributes: { ...attributes },
    children: [],
    _text: "",
    className: attributes.class ?? "",
    hidden: false,
    disabled: false,
    href: "",
    rel: "",
    dataset: attributes["data-release-repository"] ? { releaseRepository: attributes["data-release-repository"] } : {},
    get textContent() { return this._text; },
    // Real DOM semantics, kept on purpose: a controller that rewrote the whole status paragraph this way would
    // destroy the status dot inside it, which is exactly the bug this stub exists to be able to show.
    set textContent(value) { this._text = String(value); this.children = []; },
    set innerHTML(_value) { throw new assert.AssertionError({ message: "response content must never be written as HTML" }); },
    get innerHTML() { throw new assert.AssertionError({ message: "the page's markup is not readable state" }); },
    setAttribute(name, value) { this.attributes[name] = String(value); },
    removeAttribute(name) { delete this.attributes[name]; },
    getAttribute(name) { return this.attributes[name] ?? null; },
    append(...nodes) { this.children.push(...nodes); },
    replaceChildren(...nodes) { this.children = [...nodes]; },
    querySelector(selector) { return findIn(this, selector); },
  };
}

function matches(element, selector) {
  if (selector.startsWith(".") && !selector.includes("[")) return element.className.split(" ").includes(selector.slice(1));
  const attribute = /^\[([\w-]+)\]$/.exec(selector);
  if (!attribute) return false;
  const name = attribute[1];
  if (name === "data-release-repository") return element.dataset?.releaseRepository !== undefined;
  return element.attributes[name] !== undefined;
}

function findIn(root, selector) {
  let found = null;
  const visit = (element) => {
    for (const child of element.children) {
      if (!found && matches(child, selector)) found = child;
      if (!found) visit(child);
    }
  };
  visit(root);
  return found;
}

/** Assembles the live-check region the way `website/download.html` declares it. */
function makePage({ repository = SLUG } = {}) {
  const statusText = makeElement("span", { "data-release-status-text": "" });
  statusText._text = " Not available — no release build has been published.";
  const status = makeElement("p", { id: "apk-status", class: "download-status" });
  status.children.push(makeElement("span", { class: "status-dot status-dot-muted" }), statusText);
  const panel = makeElement("div", {
    "data-release-channel": "",
    ...(repository === null ? {} : { "data-release-repository": repository }),
    class: "download-action",
  });
  const button = makeElement("button", { "data-release-cta": "", disabled: "", type: "button", class: "button button-disabled" });
  button.disabled = true;
  const region = makeElement("div", { "data-release-region": "", class: "release-live" });
  region.hidden = true;
  panel.children.push(button, status, region);
  const root = makeElement("body");
  root.children.push(panel);
  return { root, button, status, statusText, region };
}

function textOf(element) {
  if (element.nodeType === 3) return element.textContent;
  return [element._text ?? "", ...element.children.map(textOf)].filter(Boolean).join("");
}

async function runController(fetchImplementation, page) {
  const previousDocument = globalThis.document;
  const previousFetch = globalThis.fetch;
  const live = makeElement("div");
  globalThis.document = {
    body: page.root,
    createElement: (tag) => makeElement(tag),
    createTextNode: (value) => ({ nodeType: 3, textContent: String(value) }),
    getElementById: () => live,
    querySelector: (selector) => findIn(page.root, selector),
  };
  globalThis.fetch = fetchImplementation;
  try {
    // A fresh import per test, so no module-level state can carry one outcome into the next.
    const controller = await import(`${pathToFileURL(resolve(WEBSITE_ROOT, "assets/download.js")).href}?test=${Math.random()}`);
    await controller.initDownload(page.root);
    // The verdict arrives in a promise continuation; let it settle before reading the page.
    for (let tick = 0; tick < 4; tick += 1) await new Promise((settle) => { setTimeout(settle, 0); });
  } finally {
    globalThis.fetch = previousFetch;
    if (previousDocument === undefined) delete globalThis.document;
    else globalThis.document = previousDocument;
  }
  return live;
}

const json = (payload, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => payload });
const apkAsset = (overrides = {}) => ({
  name: "craftmind-v1.0.0.apk",
  size: 14_500_000,
  digest: "sha256:aaaa0000bbbb1111cccc2222dddd3333eeee4444ffff5555aaaa0000bbbb1111",
  browser_download_url: ASSET_URL,
  ...overrides,
});

describe("download page: the control's state for each possible answer", () => {
  test("a verified asset becomes the only enabled thing, without disturbing the page's own chrome", async () => {
    const page = makePage();
    const live = await runController(async () => json({ tag_name: "v1.0.0", published_at: "2026-01-04T09:00:00Z", assets: [apkAsset()] }), page);

    assert.equal(page.button.hidden, true, "the disabled placeholder stands aside");
    assert.equal(page.region.hidden, false, "the live region renders");
    const link = page.region.children.find((child) => child.tagName === "a" && child.href === ASSET_URL);
    assert.ok(link, "the only link is exactly the asset the release returned");
    assert.equal(link.rel, "noopener noreferrer");
    const rendered = textOf(page.region);
    assert.match(rendered, /craftmind-v1\.0\.0\.apk/);
    assert.match(rendered, /SHA-256\s+aaaa0000/);
    assert.match(rendered, /14,500,000 bytes/);
    assert.equal(page.status.children.length, 2, "the paragraph keeps holding exactly its dot and its text slot");
    assert.equal(page.status.children[0].className, "status-dot status-dot-muted", "the status dot survives the update");
    assert.equal(page.status.children[1], page.statusText, "the controller wrote into the text slot, not the paragraph");
    assert.match(page.statusText.textContent, /A published release is available/);
    assert.match(live.textContent, /CraftMind v1\.0\.0 is available to download\./, "the change is announced once");
  });

  for (const [name, response] of [
    ["no release published yet", () => json({ message: "Not Found" }, 404)],
    ["a release with no Android package", () => json({ tag_name: "v1.0.0", assets: [{ name: "notes.txt", browser_download_url: `https://github.com/${SLUG}/releases/download/v1.0.0/notes.txt` }] })],
    ["a rate limit", () => json({ message: "API rate limit exceeded" }, 403)],
    ["an unreachable release service", async () => { throw new TypeError("network down"); }],
    ["a body that is not the documented shape", () => json({ assets: "not a list" })],
    ["a response with no assets array at all", () => json({ tag_name: "v1.0.0" })],
  ]) {
    test(`${name} keeps the control disabled, renders no link, and says why`, async () => {
      const page = makePage();
      const live = await runController(response, page);
      assert.equal(page.button.disabled, true);
      assert.equal(page.button.hidden, false);
      assert.equal(page.region.hidden, true, "nothing is offered as a download");
      assert.equal(page.region.children.length, 0);
      assert.ok(page.statusText.textContent.trim().length > 0, "the page explains itself instead of going blank");
      assert.doesNotMatch(textOf(page.region), /https?:\/\//, "no href can exist on an empty region");
      assert.equal(live.textContent, "", "nothing is announced as available when nothing was verified");
    });
  }

  test("a release with several packages lists them and enables no download", async () => {
    const page = makePage();
    const live = await runController(async () => json({
      tag_name: "v1.0.0",
      assets: [
        apkAsset({ name: "app-debug.apk", browser_download_url: `https://github.com/${SLUG}/releases/download/v1.0.0/app-debug.apk` }),
        apkAsset(),
      ],
    }), page);
    assert.equal(page.button.disabled, true, "nothing is installed through this page when the choice is ambiguous");
    assert.equal(page.button.hidden, false);
    assert.equal(page.region.hidden, false, "the page still explains what it found");
    const rendered = textOf(page.region);
    assert.match(rendered, /app-debug\.apk/);
    assert.match(rendered, /craftmind-v1\.0\.0\.apk/);
    assert.match(rendered, /does not pick one for you/);
    const apkLinks = page.region.children.filter((child) => child.tagName === "a").map((child) => child.href);
    assert.deepEqual(apkLinks, [`https://github.com/${SLUG}/releases/latest`], "only the release page is linked, never one package");
    assert.match(live.textContent, /several packages/, "the ambiguity is announced, not hidden");
  });

  test("a page that names no repository asks nobody", async () => {
    const page = makePage({ repository: null });
    let requests = 0;
    await runController(async () => { requests += 1; return json({}, 404); }, page);
    assert.equal(requests, 0);
    assert.equal(page.button.disabled, true);
    assert.match(page.statusText.textContent, /does not name a source repository/);
  });

  test("a page missing a hook leaves the static markup alone rather than half-updating", async () => {
    const page = makePage();
    delete page.region.attributes["data-release-region"];
    const requests = [];
    await runController(async (url) => { requests.push(String(url)); return json({ assets: [apkAsset()] }); }, page);
    assert.deepEqual(requests, [], "no hooks means no request at all");
    assert.equal(page.button.disabled, true);
    assert.match(page.statusText.textContent, /Not available — no release build has been published\./);
  });

  test("a hostile tag or file name is rendered as text, never as markup", async () => {
    const page = makePage();
    const hostile = {
      tag_name: "<img src=x onerror=alert(1)>",
      assets: [apkAsset({
        name: `<img src=1 onerror=alert(1)>.apk`,
        browser_download_url: `https://github.com/${SLUG}/releases/download/v1.0.0/${encodeURIComponent("<img src=1 onerror=alert(1)>.apk")}`,
      })],
    };
    await runController(async () => json(hostile), page);
    const rendered = textOf(page.region);
    assert.match(rendered, /<img src=1 onerror=alert\(1\)>\.apk/, "the name is shown as a name, verbatim");
    assert.ok(!rendered.includes("&lt;"), "the page does not double-escape either — it is text, not markup");
    const tags = new Set();
    const collect = (element) => { if (element.tagName) tags.add(element.tagName); (element.children ?? []).forEach(collect); };
    collect(page.region);
    assert.deepEqual([...tags].sort(), ["a", "b", "div", "p", "span"], "only the elements this module builds exist in the region");

    // A name that tries to escape into a second path segment is refused outright, so nothing is offered at all.
    const escaping = makePage();
    await runController(async () => json({
      tag_name: "v1.0.0",
      assets: [apkAsset({ name: "../x.apk", browser_download_url: `https://github.com/${SLUG}/releases/download/v1.0.0/${encodeURIComponent("../x.apk")}` })],
    }), escaping);
    assert.equal(escaping.region.children.length, 0, "a name containing a separator is not a file name");
    assert.equal(escaping.button.disabled, true);
  });
});
