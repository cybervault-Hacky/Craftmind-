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
    artifact_suffixes = {".jks", ".keystore", ".jceks", ".p12", ".pfx", ".pem", ".key", ".p8", ".apk", ".aab"}
    signing_file_names = {"keystore.properties", "secrets.properties", "release-signing.properties", "signing.local.properties"}
    tracked_artifacts = [
        name for name in tracked
        if name and (Path(name).suffix.lower() in artifact_suffixes or Path(name).name in signing_file_names)
    ]
    if tracked_artifacts:
        fail("signing/build artifact(s) are tracked: " + ", ".join(tracked_artifacts))

    secret_patterns = (
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
        re.compile(r"\bAIza[0-9A-Za-z_-]{30,}\b"),
        re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b"),
    )
    for name in tracked:
        if not name:
            continue
        path = ROOT / name
        if not path.is_file() or path.suffix.lower() in artifact_suffixes:
            continue
        try:
            contents = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        if any(pattern.search(contents) for pattern in secret_patterns):
            fail(f"known credential/private-key pattern requires review in tracked file: {name}")

    ignore = GITIGNORE.read_text(encoding="utf-8")
    for pattern in ("*.jks", "*.keystore", "*.jceks", "*.p12", "*.pfx", "*.pem", "*.key", "*.p8", "*.apk", "*.aab", ".env.*"):
        if pattern not in ignore:
            fail(f".gitignore does not exclude {pattern}")

    print("PASS: Android release ID/version, external signed-release configuration, R8 protocol keep rule, and launcher icon are present.")
    print("PASS: cleartext traffic/backups are disabled; app source contains no cleartext URL or logging call.")
    print("PASS: tracked files contain no scanned key/keystore/APK artifacts or known credential/private-key patterns.")
    print("NOTE: static checks do not replace Gradle builds, APK signature verification, runtime testing, or a full security audit.")


if __name__ == "__main__":
    main()
