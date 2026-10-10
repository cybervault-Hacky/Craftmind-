import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

// Regression coverage for the one file the release gate steps around, and for everything it must still catch.
//
// These tests deliberately drive the real checker script rather than a reimplementation of its rules: an allow-list
// that only works in a mock is exactly the kind of green that ships a leak. Python is already a hard requirement of
// this repository (scripts/check_*.py are release gates), so nothing new is introduced here; if python3 is missing
// the suite says so out loud instead of pretending it checked.

const REPOSITORY_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const CHECKER = join(REPOSITORY_ROOT, "scripts", "check_release_config.py");
const CORPUS = "backend/test/fixtures/security-guardian/rule-cases.corpus";
// Assembled rather than written out, following the convention the Guardian's own suite established: this file is
// tracked, so a contiguous marker in it would become the exact leak the gate looks for (and did, before this line was
// split). The strings handed to the checker still contain the real marker, because the concatenation happens before
// anything is written to disk.
const PEM_MARKER = "-----BEGIN " + "RSA PRIVATE KEY-----";
const SYNTHETIC_SECRET = "AKIA" + "DEADBEEFCAFEBABE"; // well-formed shape, never issued to anything

const python = (() => {
  const probe = spawnSync("python3", ["-c", "pass"], { encoding: "utf8" });
  return probe.error || probe.status !== 0 ? null : "python3";
})();
const SKIP = python ? false : "python3 is not available on this machine";

/** Write a flat { path: contents } map into a fresh temporary directory and return its root. */
function repository(files) {
  const root = mkdtempSync(join(tmpdir(), "craftmind-release-gate-"));
  for (const [relative, contents] of Object.entries(files)) {
    const target = join(root, relative);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, contents);
  }
  return root;
}

/**
 * Run one call of the checker's credential sweep inside an isolated process, with the exemption allow-list optionally
 * replaced, so the guards on the allow-list itself can be exercised without editing the shipped script.
 */
function sweep(root, tracked, { exemptions = null, prefix = null } = {}) {
  const script = [
    "import importlib.util, json, sys",
    "from pathlib import Path",
    "spec = importlib.util.spec_from_file_location('check_release_config', sys.argv[1])",
    "module = importlib.util.module_from_spec(spec)",
    "spec.loader.exec_module(module)",
    "payload = json.loads(sys.argv[2])",
    "if payload.get('exemptions') is not None:",
    "    module.SECRET_SWEEP_EXEMPTIONS = frozenset(payload['exemptions'])",
    "if payload.get('prefix') is not None:",
    "    module.SECRET_SWEEP_EXEMPTION_PREFIX = payload['prefix']",
    "try:",
    "    violations, exempted = module.find_secret_pattern_violations(Path(payload['root']), payload['tracked'])",
    "    print(json.dumps({'exit': 0, 'violations': violations, 'exempted': exempted, 'stderr': ''}))",
    "except SystemExit as error:",
    "    print(json.dumps({'exit': int(error.code or 1), 'violations': [], 'exempted': [], 'stderr': ''}))",
  ].join("\n");
  const result = spawnSync(python, ["-c", script, CHECKER, JSON.stringify({ root, tracked, exemptions, prefix })],
    { encoding: "utf8" });
  assert.equal(result.error, undefined, "python3 failed to start: " + result.error);
  // `fail()` writes to stderr; capture both streams by re-reading status plus the JSON payload.
  const payload = JSON.parse(result.stdout.trim().split("\n").pop());
  return { ...payload, combined: (result.stdout + result.stderr) };
}

/** Minimal repository that satisfies every other release check, so a run's verdict is about secrets alone. */
/** A corpus-shaped file with its adversarial example intact, as any real run of the gate expects to find it. */
function corpusCase(rule = "CRAFTMIND_PRIVATE_KEY_BLOCK") {
  return JSON.stringify([{ rule, path: "backend/src/x.js", bad: PEM_MARKER + "\\nMIIEowIBAAKCAQEAx7Vh2\\n",
    good: "-----BEGIN PUBLIC KEY-----\\nMIIBIjANBg\\n" }]) + "\n";
}

function cleanReleaseShell(extraFiles = {}) {
  return repository({
    [CORPUS]: corpusCase(),
    "app/build.gradle.kts": [
      'android { id("com.android.application"); applicationId = "com.craftmind.app"',
      "    versionCode = 10000",
      '    versionName = "1.0.0"',
      "    buildTypes { release { isMinifyEnabled = true; isShrinkResources = true } }",
      '    val names = listOf("CRAFTMIND_RELEASE_STORE_FILE", "CRAFTMIND_RELEASE_STORE_PASSWORD",',
      '        "CRAFTMIND_RELEASE_KEY_ALIAS", "CRAFTMIND_RELEASE_KEY_PASSWORD")',
      "    if (storePath.startsWith(repositoryPath)) { error(\"in-checkout keystore\") }",
      '    if (name == "packageRelease") { sign() }',
      "}",
    ].join("\n"),
    "app/proguard-rules.pro": "-keep class com.craftmind.bridge.protocol.** { *; }\n",
    "app/src/main/AndroidManifest.xml": '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
      + '<application android:allowBackup="false" android:usesCleartextTraffic="false"'
      + ' android:icon="@mipmap/ic_launcher" /></manifest>\n',
    ".gitignore": ["*.jks", "*.keystore", "*.jceks", "*.p12", "*.pfx", "*.pem", "*.key", "*.p8", "*.apk", "*.aab",
      ".env.*", ""].join("\n"),
    ...extraFiles,
  });
}

function runCheckerEndToEnd(root) {
  const copy = join(root, "scripts");
  mkdirSync(copy, { recursive: true });
  writeFileSync(join(copy, "check_release_config.py"), readFileSync(CHECKER));
  // The gate asks git what is tracked, so a synthetic repository needs an index of its own — this is also what makes
  // the test prove the exemption against tracked-file enumeration rather than a directory listing.
  const git = (args) => spawnSync("git", ["-C", root, ...args], { encoding: "utf8" });
  assert.equal(git(["init", "-q"]).status, 0);
  assert.equal(git(["add", "-A"]).status, 0);
  return spawnSync(python, [join(copy, "check_release_config.py")], { encoding: "utf8" });
}

describe("release-config credential sweep: the adversarial-corpus exemption", { skip: SKIP }, () => {
  it("keeps the corpus tracked and exempts that one path and nothing else", () => {
    const tracked = execFileSync("git", ["-C", REPOSITORY_ROOT, "ls-files"], { encoding: "utf8" }).split("\n");
    assert.ok(tracked.includes(CORPUS), "the adversarial corpus must stay tracked, not be untracked to dodge the gate");
    const { exit: code, violations, exempted } = sweep(REPOSITORY_ROOT, tracked);
    assert.equal(code, 0, "a real tracked tree must pass once the corpus is accounted for");
    assert.deepEqual(violations, [], "no other tracked file may be skipped: " + JSON.stringify(violations));
    assert.deepEqual(exempted, [CORPUS], "exactly one path is exempted, by exact name");
  });

  it("still fails on a private-key marker in application code", () => {
    const root = cleanReleaseShell({ "backend/src/leak.js": "export const KEY = \"" + PEM_MARKER + "\\n\";\n" });
    const files = [CORPUS, ".gitignore", "app/build.gradle.kts", "app/proguard-rules.pro",
      "app/src/main/AndroidManifest.xml", "backend/src/leak.js"];
    const { exit: code, violations } = sweep(root, files);
    assert.equal(code, 0, "the sweep itself must run to completion: " + JSON.stringify(violations));
    assert.deepEqual(violations, [["backend/src/leak.js", "private-key marker"]]);
  });

  it("still fails on the same marker in a different test fixture", () => {
    const root = repository({
      [CORPUS]: "{\"bad\": \"" + PEM_MARKER + "\"}\n",
      "backend/test/fixtures/marketplace/seed-data.js": "export const seed = \"" + PEM_MARKER + "\";\n",
    });
    const { exit: code, violations, exempted } = sweep(root, [CORPUS, "backend/test/fixtures/marketplace/seed-data.js"]);
    assert.equal(code, 0);
    assert.deepEqual(violations, [["backend/test/fixtures/marketplace/seed-data.js", "private-key marker"]],
      "being a test fixture is not a reason to skip a file; only the exact corpus path is exempt");
    assert.deepEqual(exempted, [CORPUS]);
  });

  it("exempts by exact path, so its neighbours and its own subdirectories stay scanned", () => {
    const directory = "backend/test/fixtures/security-guardian/";
    const files = [CORPUS, directory + "benign/extra.corpus", directory + "README.md", directory + "sibling.js"];
    const root = repository(Object.fromEntries(files.map((name) => [name, PEM_MARKER + "\n"])));
    const { exit: code, violations } = sweep(root, files);
    assert.equal(code, 0, "the exemption is honoured only for the exact corpus path, which is present here");
    assert.deepEqual(violations.map(([name]) => name), [directory + "benign/extra.corpus", directory + "README.md",
      directory + "sibling.js"], "the exemption must not have widened into a directory rule");
  });

  it("names the file and the rule without reproducing the value it found", () => {
    const leakedValue = PEM_MARKER + "\nMIIEowIBAAKCAQEAx7Vh2\n";
    const root = cleanReleaseShell({ "backend/src/leak.js": "const key = \"" + leakedValue + "\";\nconst id = \""
      + SYNTHETIC_SECRET + "\";\n" });
    const result = runCheckerEndToEnd(root);
    assert.equal(result.status, 1, "the gate must fail on a credential marker in application code");
    assert.match(result.stderr, /backend\/src\/leak\.js/);
    assert.match(result.stderr, /private-key marker/);
    assert.ok(!result.stderr.includes("MIIEowIBAAKCAQEAx7Vh2"), "the key material itself reached the diagnostics");
    assert.ok(!result.stdout.concat(result.stderr).includes(SYNTHETIC_SECRET),
      "a credential-shaped value must never be echoed by a failing run");
  });

  it("rejects an exemption that is stale, unreadable, unnecessary, or pointed at application code", () => {
    const root = repository({ [CORPUS]: corpusCase(), "backend/src/leak.js": PEM_MARKER + "\n" });
    const stale = sweep(root, [], { exemptions: [CORPUS] });
    assert.equal(stale.exit, 1, "an exemption for a file that is no longer tracked has to be removed, not kept");
    assert.match(stale.combined, /is stale/);

    const dead = sweep(repository({ [CORPUS]: "nothing secret here\n" }), [CORPUS], { exemptions: [CORPUS] });
    assert.equal(dead.exit, 1, "a corpus without its adversarial examples is the failure this exemption exists to catch");
    assert.match(dead.combined, /matches no secret pattern/);

    const sheltered = sweep(root, ["backend/src/leak.js", CORPUS], { exemptions: ["backend/src/leak.js"] });
    assert.equal(sheltered.exit, 1, "the allow-list may never be used to hide application code");
    assert.match(sheltered.combined, /outside the permitted fixture prefix/);

    // A path outside the permitted prefix is refused on sight, which is also what stops a directory or a parent
    // entry from being used as a broad exclusion.
    const widened = sweep(root, [CORPUS], { exemptions: ["backend/test/fixtures/security-guardian"] });
    assert.equal(widened.exit, 1, "a directory is not a file exemption");
    assert.match(widened.combined, /outside the permitted fixture prefix/);

    const directory = "backend/test/fixtures/security-guardian/an-empty-directory";
    mkdirSync(join(root, directory), { recursive: true });
    const unreadable = sweep(root, [CORPUS, directory], { exemptions: [directory] });
    assert.equal(unreadable.exit, 1, "an exemption that cannot be read must fail rather than silently apply");
    assert.match(unreadable.combined, /cannot be read/);
  });

  it("leaves the adversarial corpus byte-for-byte as the scanner expects to find it", () => {
    const onDisk = readFileSync(join(REPOSITORY_ROOT, CORPUS), "utf8");
    const cases = JSON.parse(onDisk);
    assert.equal(cases.length, 17, "the corpus carries one unsafe/benign pair per line-rule; shrinking it is not a fix");
    for (const entry of cases) {
      for (const field of ["bad", "good", "path", "rule"]) assert.ok(field in entry, entry.rule + " lost its " + field);
      assert.ok(entry.bad.length > 0 && entry.good.length > 0, entry.rule + " lost an example");
    }
    const keyCase = cases.find((entry) => entry.rule === "CRAFTMIND_PRIVATE_KEY_BLOCK");
    assert.ok(keyCase.bad.includes(PEM_MARKER), "the private-key example must stay a real marker, not an encoded stub");
    assert.ok(!/base64|atob|Buffer\.from/.test(onDisk), "the corpus was encoded to dodge the gate");
    const committed = execFileSync("git", ["-C", REPOSITORY_ROOT, "show", "HEAD:" + CORPUS], { encoding: "utf8" });
    assert.equal(onDisk, committed, "the corpus was rewritten; it is the scanner's test data, not the gate's problem");
    assert.equal(spawnSync("git", ["-C", REPOSITORY_ROOT, "check-ignore", "-q", "--", CORPUS]).status, 1,
      "ignoring the corpus would hide it from the scanner it exists to test");
  });

  it("keeps the whole gate green on this repository and says out loud that it skipped one file", () => {
    const result = spawnSync(python, [CHECKER], { cwd: REPOSITORY_ROOT, encoding: "utf8" });
    assert.equal(result.status, 0, "the release gate must pass on a clean tree: " + result.stderr);
    assert.match(result.stdout, /credential sweep skipped 1 tracked file\(s\) by exact-path exemption/);
    assert.match(result.stdout, new RegExp(CORPUS.replace(/[.\\/]/g, (c) => "\\" + c)));
  });
});
