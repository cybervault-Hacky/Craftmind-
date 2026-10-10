#!/usr/bin/env python3
"""Static deployment-readiness checks for the CraftMind backend.

Every rule here is something that would otherwise be discovered by a failed deployment rather than by a test:
a documented runtime floor that disagrees with the code's own floor, a configuration key that exists in code but in
no example or doc, a dependency added without a lockfile, a `.env` committed by accident, or a backup tool that
quietly degraded into copying a live database file.

Read-only and dependency-free, so it can run in CI and on a laptop alike.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend"
CONFIG = BACKEND / "src" / "config.js"
PACKAGE = BACKEND / "package.json"
ENV_EXAMPLE = BACKEND / ".env.example"
RUNTIME_DOC = ROOT / "docs" / "account-authentication.md"
OPERATIONS_DOC = ROOT / "docs" / "production-operations.md"
BACKUP_TOOL = BACKEND / "scripts" / "backup-database.mjs"
RESTORE_TOOL = BACKEND / "scripts" / "restore-database.mjs"
MAINTENANCE = BACKEND / "src" / "db-maintenance.js"

failures: list[str] = []
notes: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def read(path: Path) -> str:
    if not path.exists():
        fail(f"missing required file: {path.relative_to(ROOT)}")
        return ""
    return path.read_text(encoding="utf-8")


# --- runtime floor: engines, the code's own guard, and the documentation must state one number -------------------
config_source = read(CONFIG)
package_text = read(PACKAGE)
try:
    package = json.loads(package_text) if package_text else {}
except json.JSONDecodeError as error:  # pragma: no cover - a broken manifest is a hard failure
    fail(f"backend/package.json is not valid JSON: {error}")
    package = {}

declared_engine = str(package.get("engines", {}).get("node", ""))
guard = re.search(r"MINIMUM_NODE_VERSION = Object\.freeze\(\{ major: (\d+), minor: (\d+), patch: (\d+) \}\)", config_source)
if not guard:
    fail("config.js does not declare MINIMUM_NODE_VERSION; the startup preflight would be unenforceable")
else:
    floor = f"{guard.group(1)}.{guard.group(2)}.{guard.group(3)}"
    match = re.search(r">=\s*(\d+\.\d+\.\d+)", declared_engine)
    if not match:
        fail("backend/package.json must declare engines.node as a >=X.Y.Z range")
    elif match.group(1) != floor:
        fail(f"engines.node says >={match.group(1)} but the startup guard requires >={floor}")
    doc_text = read(RUNTIME_DOC)
    if f"{guard.group(1)}.{guard.group(2)}" not in doc_text:
        fail(f"docs/account-authentication.md must state the Node.js {floor} floor it documents")

# --- configuration keys must be documented somewhere an operator will look --------------------------------------
referenced = set(re.findall(r"environment\.([A-Z][A-Z0-9_]{2,})", config_source))
referenced |= {m.group(1) for m in re.finditer(r'positiveInteger\(environment, "([A-Z][A-Z0-9_]+)"', config_source)}
referenced |= {m.group(1) for m in re.finditer(r'httpsUrl\(environment, "([A-Z][A-Z0-9_]+)"', config_source)}
referenced -= {"NODE_ENV"}  # standard, and always optional
documented = set(re.findall(r"`?([A-Z][A-Z0-9_]{2,})`?", read(ENV_EXAMPLE) + read(RUNTIME_DOC) + read(OPERATIONS_DOC)))
undocumented = sorted(key for key in referenced if key not in documented)
if undocumented:
    fail("configuration keys with no documentation in .env.example or the runtime docs: " + ", ".join(undocumented))
else:
    notes.append(f"{len(referenced)} configuration keys, all documented")

# --- secrets: none tracked, none defaulted, none in the example -------------------------------------------------
tracked = subprocess.run(
    ["git", "-C", str(ROOT), "ls-files"], capture_output=True, text=True, check=True
).stdout.splitlines()
for path in tracked:
    name = Path(path).name
    if name == ".env" or name.startswith(".env.") and not name.endswith(".example"):
        fail(f"a live environment file is tracked in git: {path}")
env_example = read(ENV_EXAMPLE)
for line in env_example.splitlines():
    if "=" not in line or line.strip().startswith("#"):
        continue
    key, _, value = line.partition("=")
    if len(value.strip()) >= 24 and not value.strip().startswith("./"):
        fail(f"{key.strip()} in .env.example carries a value that looks like a real secret; examples stay empty")

# --- dependencies and lockfiles ----------------------------------------------------------------------------------
dependencies = {**package.get("dependencies", {}), **package.get("devDependencies", {})}
if dependencies:
    if not (BACKEND / "package-lock.json").exists():
        fail(f"backend declares {len(dependencies)} dependencies but has no package-lock.json to make them reproducible")
else:
    notes.append("backend declares zero dependencies, so there is no lockfile to drift")
scripts = package.get("scripts", {})
for name in ("start", "test"):
    if name not in scripts:
        fail(f"backend/package.json has no `{name}` script, though the docs tell operators to run `npm {name}`")

# --- backup tooling must keep the properties that make it safe ---------------------------------------------------
maintenance = read(MAINTENANCE)
if "VACUUM INTO" not in maintenance:
    fail("db-maintenance.js no longer uses VACUUM INTO; a copied WAL database is not a consistent snapshot")
if re.search(r"copyFileSync\(\s*source\s*,\s*target\s*\)", maintenance):
    fail("db-maintenance.js copies the live database file directly; that is exactly what VACUUM INTO replaces")
for tool, label in ((BACKUP_TOOL, "backup"), (RESTORE_TOOL, "restore")):
    text = read(tool)
    if text and "readOnly" not in text and label == "backup":
        fail("backup-database.mjs must open the live database read-only so a snapshot can never migrate it")
    if text and "integrity_check" not in text and "inspectBackup" not in text:
        fail(f"{label}-database.mjs must verify the artifact rather than assume the file is whole")
for tool in (BACKUP_TOOL, RESTORE_TOOL):
    if tool.exists():
        check = subprocess.run(["node", "--check", str(tool)], capture_output=True, text=True)
        if check.returncode != 0:
            fail(f"{tool.name} does not parse: {check.stderr.strip().splitlines()[-1] if check.stderr else 'syntax error'}")

# --- the static site's hosting configuration must describe the site that actually exists --------------------------
# `netlify.toml` is the whole deployment contract for `website/`, so the properties that matter are checked here
# rather than trusted: publishing the right directory, building nothing that has no build, exposing no value, and
# adding no catch-all redirect that would hide a missing page behind the home page.
netlify_text = read(ROOT / "netlify.toml")
# Rules below are about directives, and a TOML comment is prose: `netlify.toml` explains at length why it sets no
# CSP and no environment value, and prose must not be able to satisfy or violate a config rule. So comments are
# stripped once, here, and every assertion reads the same stripped text.
netlify_directives = "\n".join(
    line.split("#", 1)[0] for line in netlify_text.splitlines() if not line.lstrip().startswith("#")
)
if netlify_text:
    if 'publish = "website"' not in netlify_directives:
        fail('netlify.toml must publish exactly "website"; publishing the repository root would serve backend source, '
             "scripts, and docs")
    if not re.search(r'^\s*command\s*=\s*""\s*$', netlify_directives, re.MULTILINE):
        fail('netlify.toml must declare an empty build command (command = ""); the site ships as static files and a '
             "guessed build step fails every deploy")
    if not (ROOT / "website" / "index.html").is_file():
        fail("netlify.toml publishes website/, but website/index.html does not exist")
    if re.search(r'^\s*from\s*=\s*"[^"]*\*"', netlify_directives, re.MULTILINE) or "[[redirects]]" in netlify_directives:
        fail("netlify.toml must not declare a redirect: with 42 real pages a catch-all fallback turns a missing page "
             "into a silent 200 on the home page")
    environment_block = re.search(r"^\[build\.environment\]\s*$(.*?)(?=^\[|\Z)", netlify_directives, re.MULTILINE | re.DOTALL)
    if environment_block and re.search(r"^\s*[A-Za-z_][A-Za-z0-9_]*\s*=", environment_block.group(1), re.MULTILINE):
        fail("netlify.toml must set no build environment values: this site has no build step to consume them, and any "
             "value placed here is a candidate for accidental publication")
    if re.search(r"(?i)^\s*(?:[a-z_]*)(?:token|secret|password|apikey|api_key|private_key)\s*=", netlify_directives, re.MULTILINE):
        fail("netlify.toml carries something that looks like a credential; hosting config for a static site never needs one")
    for required_header in ("X-Content-Type-Options", "Referrer-Policy", "Permissions-Policy"):
        if required_header not in netlify_directives:
            fail(f"netlify.toml must set {required_header} on the published site")
    if "Content-Security-Policy" in netlify_directives:
        fail("netlify.toml sets a Content-Security-Policy that no browser has verified here; add it only with a real "
             "browser pass and style-src allowance for the inline attributes the pages use")
    notes.append("netlify.toml publishes website/ with no build step, no environment values, and three response headers")

if failures:
    print("FAIL: deployment readiness")
    for item in failures:
        print(f"  - {item}")
    sys.exit(1)
print("PASS: deployment readiness (" + "; ".join(notes) + ")")
print("NOTE: static checks only. They do not deploy anything, contact any host, or replace a restore rehearsal.")
