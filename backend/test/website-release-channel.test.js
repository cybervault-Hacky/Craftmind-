/**
 * Final phase: the download page's live release check, exercised against the module that ships.
 *
 * This is not a browser test and is not presented as one. What it does is stronger than reading the file as text and
 * weaker than Playwright: it imports the *actual* module the site loads (`website/assets/adapters.js`), gives it a
 * stand-in `fetch`, and asserts what the download page is allowed to conclude from each answer the release service
 * can give. Rendering, focus, and the real click on a real phone remain unverified here — no browser exists in this
 * environment, and that gap is recorded as NOT RUN rather than closed by claiming a DOM is a browser.
 *
 * The properties that matter, and why each has a test: the page must be able to enable a download only from a
 * response that actually describes an Android package belonging to the repository the page named. So the adapter has
 * to refuse an asset served from somebody else's host, refuse plain HTTP, refuse a path that is not under that
 * repository's release directory, refuse a filename that disagrees with the URL it came from, and refuse anything
 * that is not an `.apk`. It also has to fail closed — an empty, rate-limited, unreachable, or unconfigured answer
 * must never look like a success, because the consequence of a false success on this page is a stranger's binary
 * installed on the owner's phone.
 */
import assert from "node:assert/strict";
import test, { describe } from "node:test";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const REPO_ROOT = resolve(import.meta.dirname, "..", "..");
const WEBSITE_ADAPTERS = pathToFileURL(resolve(REPO_ROOT, "website/assets/adapters.js")).href;
const SLUG = "cybervault-Hacky/Craftmind-";
const ASSET_URL = `https://github.com/${SLUG}/releases/download/v1.0.0/craftmind-v1.0.0.apk`;

/** Runs `body` with a stand-in fetch and restores the real one, whatever the outcome. */
async function withFetch(implementation, body) {
  const original = globalThis.fetch;
  globalThis.fetch = implementation;
  try {
    return await body();
  } finally {
    globalThis.fetch = original;
  }
}

const json = (payload, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => payload,
});

const releaseWith = (assets, extra = {}) => json({
  tag_name: "v1.0.0",
  name: "CraftMind 1.0.0",
  published_at: "2026-01-04T09:00:00Z",
  assets,
  ...extra,
});

const apkAsset = (overrides = {}) => ({
  name: "craftmind-v1.0.0.apk",
  size: 14_500_000,
  digest: "sha256:AAAA0000BBBB1111CCCC2222DDDD3333EEEE4444FFFF5555AAAA0000BBBB1111",
  browser_download_url: ASSET_URL,
  ...overrides,
});

describe("website release adapter: the request it is allowed to make", () => {
  test("names the pinned release API for the repository the page supplied, and nothing else", async () => {
    const seen = [];
    const adapter = await importAdapters();
    await withFetch(async (url, options) => {
      seen.push({ url: String(url), method: options?.method, accept: options?.headers?.Accept });
      return json({ assets: [] }, 404);
    }, () => adapter.latestArtifact());
    assert.deepEqual(seen, [{
      url: `https://api.github.com/repos/${SLUG}/releases/latest`,
      method: "GET",
      accept: "application/vnd.github+json",
    }], "the adapter must make exactly one read of public release metadata");
  });

  test("stays unconfigured without a repository, and never asks anybody else", async () => {
    const tripwire = async (url) => {
      throw new assert.AssertionError({ message: `an unconfigured adapter must not request ${url}` });
    };
    for (const repository of [null, undefined, "", "   ", "only-owner", "two/slashes/here", "../escape", "./x", "a b/c", `${SLUG}/extra`]) {
      const adapter = (await loadModule()).createReleaseAdapter({ repository });
      assert.equal(adapter.configured, false, `must refuse to be configured by ${JSON.stringify(repository)}`);
      const result = await withFetch(tripwire, () => adapter.latestArtifact());
      assert.equal(result.status, "unavailable");
      assert.equal(result.reason, "NO_SERVICE_CONFIGURED");
      assert.match(result.message, /does not name a source repository/);
    }
  });
});

describe("website release adapter: what may become a link", () => {
  test("accepts the release's Android package and reports GitHub's own checksum", async () => {
    const result = await ask({
      tag_name: "v1.0.0",
      published_at: "2026-01-04T09:00:00Z",
      assets: [apkAsset()],
    });
    assert.equal(result.status, "ok");
    assert.equal(result.payload.url, ASSET_URL);
    assert.equal(result.payload.fileName, "craftmind-v1.0.0.apk");
    assert.equal(result.payload.tag, "v1.0.0");
    assert.equal(result.payload.sizeBytes, 14_500_000);
    assert.equal(result.payload.sha256, "aaaa0000bbbb1111cccc2222dddd3333eeee4444ffff5555aaaa0000bbbb1111");
  });

  test("never invents a checksum the release did not publish", async () => {
    for (const digest of [undefined, "", "sha256:not-a-hash", "sha512:aaaa", "sha256:abc"]) {
      const result = await ask({ assets: [apkAsset({ digest })] });
      assert.equal(result.status, "ok", `a release with digest ${JSON.stringify(digest)} still has an APK to link`);
      assert.equal(result.payload.sha256, "", `digest ${JSON.stringify(digest)} must not be presented as a checksum`);
    }
  });

  test("refuses an asset that GitHub says lives anywhere but that release", async () => {
    const hostile = [
      apkAsset({ browser_download_url: "https://evil.example/" + SLUG + "/releases/download/v1.0.0/craftmind-v1.0.0.apk" }),
      apkAsset({ browser_download_url: "http://github.com/" + SLUG + "/releases/download/v1.0.0/craftmind-v1.0.0.apk" }),
      apkAsset({ browser_download_url: ASSET_URL.replace("/" + SLUG + "/", "/someone-else/else/") }),
      apkAsset({ browser_download_url: "https://objects.githubusercontent.com/" + SLUG + "/releases/download/v1.0.0/x.apk" }),
      apkAsset({ browser_download_url: "javascript:" + ASSET_URL }),
      apkAsset({ browser_download_url: "https://github.com/" + SLUG + "/releases/download/v1.0.0/other.apk" }),
      apkAsset({ browser_download_url: `https://github.com/${SLUG}/archive/refs/tags/v1.0.0.zip` }),
      apkAsset({ name: "not-the-apk.apk" }),
      apkAsset({ name: "craftmind-v1.0.0.exe" }),
      apkAsset({ browser_download_url: "https://github.com/" + SLUG + "/releases/download/v1.0.0/nested/../escape.apk" }),
    ];
    for (const asset of hostile) {
      const result = await ask({ assets: [asset, apkAsset()] });
      if (result.status === "ok") {
        assert.equal(result.payload.url, ASSET_URL, `only the verified release asset may be linked, got ${result.payload.url}`);
      } else {
        assert.equal(result.status, "empty", "an unusable asset is honestly 'nothing to download'");
      }
    }
    const only = await ask({ assets: [hostile[0]] });
    assert.equal(only.status, "empty");
  });

  test("refuses to pick one package when a release carries several", async () => {
    const result = await ask({
      assets: [
        apkAsset({ name: "app-debug.apk", browser_download_url: `https://github.com/${SLUG}/releases/download/v1.0.0/app-debug.apk` }),
        apkAsset({ name: "craftmind-v1.0.0.apk" }),
      ],
    });
    assert.equal(result.status, "ok");
    assert.equal(result.payload.ambiguous, true, "the caller must be told there is no single answer");
    assert.equal(result.payload.url, undefined, "no package may be singled out for the page to link");
    assert.deepEqual(result.payload.packages.map((item) => item.fileName), ["app-debug.apk", "craftmind-v1.0.0.apk"]);
    assert.equal(result.payload.packages[1].sha256.length, 64, "each candidate still carries its own verified checksum");
  });

  test("skips a non-APK asset to reach the package a release does ship", async () => {
    const result = await ask({
      assets: [
        { name: "sources.jar", size: 10, browser_download_url: `https://github.com/${SLUG}/releases/download/v1.0.0/sources.jar` },
        apkAsset(),
      ],
    });
    assert.equal(result.status, "ok");
    assert.equal(result.payload.fileName, "craftmind-v1.0.0.apk");
  });
});

describe("website release adapter: every failure leaves the control disabled", () => {
  test("no published release yet is an answer, not an error", async () => {
    const result = await ask({}, 404);
    assert.equal(result.status, "empty");
    assert.match(result.message, /has been published yet/);
  });

  test("a rate limit or a server fault is reported as 'nothing confirmed', with the page's own words", async () => {
    for (const status of [403, 429, 500, 503]) {
      const result = await ask({ message: "API rate limit exceeded" }, status);
      assert.equal(result.status, "error");
      assert.match(result.message, new RegExp(`answered ${status}`));
      assert.doesNotMatch(JSON.stringify(result), /rate limit exceeded/, "the response body must not be echoed");
    }
  });

  test("an unreachable service, a broken body, and a release with no assets all answer empty/error", async () => {
    const adapter = await importAdapters();
    assert.equal((await withFetch(async () => { throw new TypeError("network down"); }, () => adapter.latestArtifact())).status, "error");
    assert.equal((await withFetch(async () => ({ ok: true, status: 200, json: async () => { throw new SyntaxError("nope"); } }), () => adapter.latestArtifact())).status, "empty");
    assert.equal((await ask({ assets: [] })).status, "empty");
    assert.equal((await ask({})).status, "empty");
    assert.equal((await ask({ assets: [{ name: "x.apk" }] })).status, "empty");
  });

  test("the request is never repeated and never aimed at the asset URL", async () => {
    const urls = [];
    const adapter = await importAdapters();
    await withFetch(async (url) => {
      urls.push(String(url));
      return json({ assets: [apkAsset()], tag_name: "v1.0.0" });
    }, () => adapter.latestArtifact());
    await withFetch(async (url) => {
      urls.push(String(url));
      return json({ assets: [apkAsset()], tag_name: "v1.0.0" });
    }, () => adapter.latestArtifact());
    assert.equal(urls.length, 2, "one metadata read per call, and never a read of the asset itself");
    assert.ok(urls.every((url) => url.endsWith("/releases/latest")));
  });
});

/** Module state is shared across the suite on purpose: the adapter holds no session and caches nothing. */
let adapters = null;
async function loadModule() {
  if (!adapters) adapters = await import(`${WEBSITE_ADAPTERS}?suite=release-channel`);
  return adapters;
}

async function importAdapters() {
  const module = await loadModule();
  return module.createReleaseAdapter({ repository: SLUG });
}

async function ask(payload, status = 200) {
  const adapter = await importAdapters();
  return withFetch(async () => json(payload, status), () => adapter.latestArtifact());
}
