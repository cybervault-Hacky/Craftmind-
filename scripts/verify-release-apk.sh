#!/usr/bin/env bash
set -euo pipefail

apk_path="${1:-app/build/outputs/apk/release/app-release.apk}"
if [[ ! -f "$apk_path" ]]; then
  printf 'APK not found: %s\n' "$apk_path" >&2
  exit 1
fi
for tool in aapt apksigner sha256sum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'Required tool not found on PATH: %s\n' "$tool" >&2
    exit 1
  fi
done

badging="$(aapt dump badging "$apk_path")"
if ! grep -F "package: name='com.craftmind.app' versionCode='10000' versionName='1.0.0'" <<<"$badging" >/dev/null; then
  printf 'APK package/version does not match the configured CraftMind 1.0.0 release.\n' >&2
  exit 1
fi

apksigner verify --verbose --print-certs "$apk_path"
printf '\nSHA-256: '
sha256sum "$apk_path"
