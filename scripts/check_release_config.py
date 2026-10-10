#!/usr/bin/env python3
"""Static release/security hygiene checks; this does not build or run the Android app."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP_BUILD = ROOT / "app/build.gradle.kts"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
PROGUARD = ROOT / "app/proguard-rules.pro"
GITIGNORE = ROOT / ".gitignore"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


artifact_suffixes = {".jks", ".keystore", ".jceks", ".p12", ".pfx", ".pem", ".key", ".p8", ".apk", ".aab"}
signing_file_names = {"keystore.properties", "secrets.properties", "release-signing.properties", "signing.local.properties"}

# Known credential and private-key markers. Each carries a label because the failure message names the rule, never the
# matched text: a run that fails must not print the secret it found.
SECRET_PATTERNS = (
    ("private-key marker", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
    ("AWS access key id", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{30,}\b")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b")),
)

# The only tracked file exempt from the credential sweep, named by exact repository-relative path.
#
# `rule-cases.corpus` is the Security Guardian's adversarial regression corpus: one deliberately unsafe example per
# scanner rule, kept as inert *data* whose sole purpose is to be read by the scanner under test, including a PEM
# marker. The gate exists to catch a key that shipped by accident; this is a key that shipped on purpose, and the
# scanner's suite is meaningless if its examples are softened. Fixing the false positive by deleting, encoding, or
# shortening the corpus would weaken the thing being tested instead of the test, so the sweep steps aside here.
#
# Scope: the credential sweep only. The file stays tracked and stays subject to the artifact-extension check, and
# every other tracked file remains scanned — including the rest of that directory, the other Guardian fixtures, the
# benign corpus, and the scanner's own test source.
SECRET_SWEEP_EXEMPTIONS = frozenset({"backend/test/fixtures/security-guardian/rule-cases.corpus"})

# An exemption may only ever point inside this prefix, so that widening the allow-list can never shelter application
# code, configuration, or documentation. That is the difference between an exception and a hole.
SECRET_SWEEP_EXEMPTION_PREFIX = "backend/test/fixtures/security-guardian/"


def find_secret_pattern_violations(root: Path, tracked: list[str]) -> tuple[list[tuple[str, str]], list[str]]:
    """Sweep tracked text files for credential markers, returning (violations, exempted) pairs of (path, rule).

    Exemptions are validated rather than trusted. A dangling or unused one is a failure on its own terms: if the
    corpus ever stops containing a marker, then either its adversarial examples were removed — which is the thing
    this file must never let happen quietly — or the exemption has outlived its reason and has to go.
    """
    for name in sorted(SECRET_SWEEP_EXEMPTIONS):
        if not name.startswith(SECRET_SWEEP_EXEMPTION_PREFIX):
            fail("credential-sweep exemption " + name + " points outside the permitted fixture prefix; the exemption "
                 "exists for the Security Guardian's adversarial corpus and never for application code")
        if name not in tracked:
            fail("credential-sweep exemption " + name + " is stale: the file is not tracked; remove the exemption")
        try:
            contents = (root / name).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError) as error:
            fail("credential-sweep exemption " + name + " cannot be read (" + str(error)
                 + "); resolve it rather than leaving an unverified exemption in place")
        if not any(pattern.search(contents) for _, pattern in SECRET_PATTERNS):
            fail("credential-sweep exemption " + name + " matches no secret pattern any more: either the "
                 "adversarial corpus was weakened, which must be reverted, or the exemption is dead and must be "
                 "removed")

    violations: list[tuple[str, str]] = []
    exempted: list[str] = []
    for name in tracked:
        if not name:
            continue
        path = root / name
        if not path.is_file() or path.suffix.lower() in artifact_suffixes:
            continue
        try:
            contents = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        matched = [label for label, pattern in SECRET_PATTERNS if pattern.search(contents)]
        if not matched:
            continue
        if name in SECRET_SWEEP_EXEMPTIONS:
            exempted.append(name)
            continue
        violations.extend((name, label) for label in matched)
    return violations, exempted


def main() -> None:
    build = APP_BUILD.read_text(encoding="utf-8")
    if not re.search(r'applicationId\s*=\s*"com\.craftmind\.app"', build):
        fail("application ID changed")
    if not re.search(r"versionCode\s*=\s*10000\b", build):
        fail("versionCode is not 10000")
    if not re.search(r'versionName\s*=\s*"1\.0\.0"', build):
        fail("versionName is not 1.0.0")
    if not re.search(r"release\s*\{(?:(?!\n\s*\}).)*isMinifyEnabled\s*=\s*true(?:(?!\n\s*\}).)*isShrinkResources\s*=\s*true", build, re.DOTALL):
        fail("release R8/resource shrinking is not explicitly enabled")
    required_signing_inputs = (
        "CRAFTMIND_RELEASE_STORE_FILE",
        "CRAFTMIND_RELEASE_STORE_PASSWORD",
        "CRAFTMIND_RELEASE_KEY_ALIAS",
        "CRAFTMIND_RELEASE_KEY_PASSWORD",
    )
    for name in required_signing_inputs:
        if name not in build:
            fail(f"release signing input is missing from Gradle configuration: {name}")
    if "storePath.startsWith(repositoryPath)" not in build or 'name == "packageRelease"' not in build:
        fail("external-keystore and signed-package guards are missing")
    if "-keep class com.craftmind.bridge.protocol.** { *; }" not in PROGUARD.read_text(encoding="utf-8"):
        fail("Gson wire protocol keep rule is missing")

    manifest = ET.parse(MANIFEST).getroot()
    application = manifest.find("application")
    if application is None:
        fail("application element is missing")
    if application.get(ANDROID + "usesCleartextTraffic") != "false":
        fail("manifest does not explicitly disable cleartext traffic")
    if application.get(ANDROID + "allowBackup") != "false":
        fail("manifest does not disable Android backups")
    if application.get(ANDROID + "icon") != "@mipmap/ic_launcher":
        fail("adaptive launcher icon is not configured")

    java_sources = list((ROOT / "app/src/main/java").rglob("*.kt")) + list((ROOT / "app/src/main/java").rglob("*.java"))
    logging_pattern = re.compile(r"\bLog\.(?:v|d|i|w|e)\s*\(|\bprintln\s*\(|printStackTrace\s*\(|System\.(?:out|err)")
    cleartext_pattern = re.compile(r"(?i)\bhttp://")
    for path in java_sources:
        source = path.read_text(encoding="utf-8")
        if logging_pattern.search(source):
            fail(f"application logging call requires review: {path.relative_to(ROOT)}")
        if cleartext_pattern.search(source):
            fail(f"cleartext HTTP URL requires review: {path.relative_to(ROOT)}")

    tracked = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "-z"],
        check=True,
        stdout=subprocess.PIPE,
    ).stdout.decode("utf-8", errors="replace").split("\0")
    tracked_artifacts = [
        name for name in tracked
        if name and (Path(name).suffix.lower() in artifact_suffixes or Path(name).name in signing_file_names)
    ]
    if tracked_artifacts:
        fail("signing/build artifact(s) are tracked: " + ", ".join(tracked_artifacts))

    violations, exempted = find_secret_pattern_violations(ROOT, tracked)
    if violations:
        name, label = violations[0]
        fail(f"known credential/private-key pattern requires review in tracked file: {name} ({label})")

    ignore = GITIGNORE.read_text(encoding="utf-8")
    for pattern in ("*.jks", "*.keystore", "*.jceks", "*.p12", "*.pfx", "*.pem", "*.key", "*.p8", "*.apk", "*.aab", ".env.*"):
        if pattern not in ignore:
            fail(f".gitignore does not exclude {pattern}")

    print("PASS: Android release ID/version, external signed-release configuration, R8 protocol keep rule, and launcher icon are present.")
    print("PASS: cleartext traffic/backups are disabled; app source contains no cleartext URL or logging call.")
    print("PASS: tracked files contain no scanned key/keystore/APK artifacts or known credential/private-key patterns.")
    if exempted:
        # Never silent: a run says which file it stepped around, so an exemption cannot be mistaken for a pass that
        # covered it.
        print("NOTE: credential sweep skipped " + str(len(exempted)) + " tracked file(s) by exact-path exemption: "
              + ", ".join(exempted))
    print("NOTE: static checks do not replace Gradle builds, APK signature verification, runtime testing, or a full security audit.")


if __name__ == "__main__":
    main()
