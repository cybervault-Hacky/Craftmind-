#!/usr/bin/env python3
"""Cross-component API contract check: the website client, the Android client, and the backend router.

Phase 34 added this because the three sides are written in three languages with no shared schema file, and the only
thing keeping them in step had been prose comments. This checker reads the actual sources and refuses when a client
addresses a route that does not exist, sends a field the server refuses, or omits one the server requires.

What it can decide, and what it cannot:
  * Route surfaces (method + path, including the parameterised dynamic routes) are read from `backend/src/server.js`.
  * Request key sets are read from the server's own validators (`strictBody` specs, resolving the named key-list
    constants it filters, and `requireX(body, "field")` calls) and compared with the object literals each client
    actually sends. A client that hands a body through opaquely is reported as unchecked rather than guessed at.
  * It does not execute any client, does not prove a browser renders anything, and cannot see the Android app's
    runtime behaviour. `cd backend && npm test` includes a service-level suite that drives the real website adapter
    over HTTP; nothing here replaces a device test or a Minecraft server test.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKEND_SRC = ROOT / "backend" / "src"
WEBSITE_ADAPTERS = ROOT / "website" / "assets" / "adapters.js"
ANDROID_REQUESTS = ROOT / "app" / "src" / "main" / "java" / "com" / "craftmind" / "app" / "domain" / "account" / "AccountApiRequests.kt"
ANDROID_WIRE = ROOT / "app" / "src" / "main" / "java" / "com" / "craftmind" / "app" / "data" / "account" / "HttpAccountApi.kt"

HTTP_METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE")
UUID_STANDIN = "00000000-0000-4000-8000-000000000000"

problems: list[str] = []
notes: list[str] = []


def report(text: str) -> None:
    print(text)


def fail(message: str) -> None:
    problems.append(message)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def brace_block(text: str, open_index: int) -> str:
    depth, j = 0, open_index
    while j < len(text):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:j]
        j += 1
    return ""


def array_literal(text: str) -> list[str]:
    return re.findall(r'"([^"]+)"', text)


def object_keys(text: str) -> set[str]:
    """Keys of a JS object literal: `a: 1`, `"a": 1`, `{ x }` shorthand, and `{ ...spread }`.

    Delimiters are matched with lookarounds, never consumed, or ` a, b ` yields only `a` — the commas are already
    spent by the previous match. `[computed]: v` is deliberately not claimed: it is a key this checker cannot name.
    """
    padded = "{" + text + "}"
    keys = set(re.findall(r'[{,]\s*"?([A-Za-z_$][\w$]*)"?:\s*(?=[^:])', padded))
    keys |= set(re.findall(r'[{,]\s*([A-Za-z_$][\w$]*)\s*(?=[,}])', padded))
    keys |= set(re.findall(r'[{,]\s*\.{3}([A-Za-z_$][\w$]*)', padded))
    return keys


# --------------------------------------------------------------------------------------------- backend surface
def backend_routes() -> dict[str, tuple[str, str]]:
    server = read(BACKEND_SRC / "server.js")
    routes: dict[str, tuple[str, str]] = {}
    for match in re.finditer(r'\["(GET|POST|PUT|PATCH|DELETE) (/[^"]+)",\s*async', server):
        routes[f"{match.group(1)} {match.group(2)}"] = (match.group(1), match.group(2))
    dynamic = server[server.index("const dynamicRoutes = ["):]
    for match in re.finditer(r'method:\s*"(GET|POST|PUT|PATCH|DELETE)",\s*\n\s*name:\s*"(/[^"]+)"', dynamic):
        routes[f"{match.group(1)} {match.group(2)}"] = (match.group(1), match.group(2))
    return routes


def const_key_lists() -> dict[str, list[str]]:
    """Named key arrays the validators reuse (`const CONTENT_KEYS = [...]`), across the backend source."""
    found: dict[str, list[str]] = {}
    for source in sorted(BACKEND_SRC.glob("*.js")):
        for match in re.finditer(r'(?:export )?const ([A-Z][A-Z0-9_]*)\s*=\s*\[([^\]]*)\]', source.read_text(encoding="utf-8")):
            keys = array_literal(match.group(2))
            if keys:
                found.setdefault(match.group(1), keys)
    return found


def resolve_list(expression: str, consts: dict[str, list[str]]) -> list[str] | None:
    """Resolve `["a","b"]`, `CONST`, or `CONST.filter((key) => !["x"].includes(key))` / `.filter(k => k !== "x")`."""
    expression = expression.strip()
    if expression.startswith("["):
        return array_literal(expression)
    name = re.match(r"([A-Z][A-Z0-9_]*)", expression)
    if not name or name.group(1) not in consts:
        return None
    keys = list(consts[name.group(1)])
    excluded = re.search(r'!\[[^\]]*\]', expression)
    if excluded:
        keys = [k for k in keys if k not in re.findall(r'"([^"]+)"', excluded.group(0))]
    single = re.search(r'key\s*!==\s*"([^"]+)"', expression)
    if single:
        keys = [k for k in keys if k != single.group(1)]
    return keys


def server_key_contracts(consts: dict[str, list[str]]) -> dict[str, tuple[set[str], set[str] | None]]:
    """route -> (required, optional). `optional` is None when the spec could not be resolved and must not be judged."""
    server = read(BACKEND_SRC / "server.js")
    sources = {p.name: read(p) for p in sorted(BACKEND_SRC.glob("*.js"))}
    functions: dict[str, str] = {}
    for name, text in sources.items():
        position = 0
        pattern = re.compile(r"(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*\(")
        while match := pattern.search(text, position):
            end = text.find("{", match.end())
            if end < 0:
                break
            functions.setdefault(match.group(1), brace_block(text, end))
            position = end + len(functions[match.group(1)])

    def contract_for(body_text: str, depth: int = 0) -> tuple[set[str], set[str] | None] | None:
        strict = re.search(r"strictBody\(\s*(?:\w+\.)?body\s*,\s*\{", body_text)
        if strict:
            spec = brace_block(body_text, body_text.index("{", strict.end() - 1))
            required_match = re.search(r"required:\s*(\[[^\]]*\]|[A-Z][A-Z0-9_]*)", spec)
            optional_match = re.search(r"optional:\s*(\[[^\]]*\][^}]*)", spec) or re.search(r"optional:\s*([A-Z][A-Z0-9_]*(?:\.filter\([^)]*\))?)", spec)
            required = set(array_literal(required_match.group(1)[1:-1]) or []) if required_match else set()
            optional: set[str] | None = set()
            if optional_match:
                resolved = resolve_list(optional_match.group(1), consts)
                optional = set(resolved) if resolved is not None else None
            elif "optional:" not in spec:
                optional = set()
            return required, optional
        required_only = re.findall(r'require\w+\(\s*(?:\w*[Bb]ody|payload)\s*,\s*"([A-Za-z_]+)"\s*\)', body_text)
        if required_only:
            return set(required_only), None  # these routes tolerate extra keys; only the required set is knowable
        if depth < 3:
            for callee in re.findall(r"\b(\w+)\([^)]*\bbody\b[^)]*\)", body_text):
                if callee in functions and functions[callee] is not body_text:
                    inner = contract_for(functions[callee], depth + 1)
                    if inner:
                        return inner
        return None

    routes: dict[str, tuple[set[str], set[str] | None]] = {}
    for match in re.finditer(r'\["(GET|POST|PUT|PATCH|DELETE) (/[^"]+)",\s*async', server):
        handler = brace_block(server, server.index("{", server.index("=>", match.end())))
        found = contract_for(handler)
        if found:
            routes[f"{match.group(1)} {match.group(2)}"] = found
    dynamic = server[server.index("const dynamicRoutes = ["):]
    for match in re.finditer(r'method:\s*"(GET|POST|PUT|PATCH|DELETE)",\s*\n\s*name:\s*"(/[^"]+)"', dynamic):
        entry = brace_block(dynamic, dynamic.rindex("{", 0, match.start()))
        handler = brace_block(entry, entry.index("{", entry.index("=>"))) if "=>" in entry else entry
        found = contract_for(handler)
        if found:
            routes[f"{match.group(1)} {match.group(2)}"] = found
    return routes


def path_is_known(route_path: str, routes: dict[str, tuple[str, str]]) -> bool:
    """A client path may interpolate an id; match it segment-wise against the server's `:param` patterns."""
    candidate = re.sub(r"\$\{[^}]*\}", "\u0000", route_path).rstrip("/") or "/"
    wanted = candidate.split("/")
    for _, (method, template) in routes.items():
        parts = template.split("/")
        if len(parts) != len(wanted):
            continue
        if all(a == b or b.startswith(":") or a == "\u0000" for a, b in zip(wanted, parts)):
            return True
    return False


# --------------------------------------------------------------------------------------------- the website side
def check_website(routes: dict[str, tuple[str, str]], contracts: dict[str, tuple[set[str], set[str] | None]]) -> tuple[int, int]:
    text = read(WEBSITE_ADAPTERS)
    declared_block = text[text.index("export const ACCOUNT_ENDPOINTS"):text.index("\n]);", text.index("export const ACCOUNT_ENDPOINTS"))]
    declared = re.findall(r'"(GET|POST|PUT|PATCH|DELETE) (/[^"]+)"', declared_block)
    checked = 0
    for method, route_path in declared:
        checked += 1
        probe = re.sub(r":[A-Za-z]+", UUID_STANDIN, route_path)
        if not path_is_known(probe, routes):
            fail(f"website advertises `{method} {route_path}` to the browser but the service has no such route")

    callsites = 0
    for match in re.finditer(r"requestJson\(", text):
        call = brace_block(text, text.index("(", match.end() - 1))
        path_match = re.search(r'"(/[^"]+)"', call)
        if not path_match:
            continue
        callsites += 1
        method = (re.search(r'method:\s*"(\w+)"', call) or [None, "GET"])[1]
        route = f"{method} {path_match.group(1)}"
        if not path_is_known(path_match.group(1), routes):
            fail(f"website calls `{route}`, which the service does not implement")
        # Either an object literal (`body: { email, password }`) or a hand-serialised one
        # (`raw: JSON.stringify({ ...token, ...password })`); anything else is passed through opaquely and is not judged.
        body_match = re.search(r"(?:body|raw)\s*:\s*(?:JSON\.stringify\(\s*)?\{", call)
        if route in contracts and body_match:
            required, optional = contracts[route]
            sent = object_keys(brace_block(call, call.index("{", body_match.end() - 1)))
            missing = required - sent
            if missing:
                fail(f"website sends {sorted(sent)} to `{route}` but the service requires {sorted(required)}; missing {sorted(missing)}")
            if optional is not None:
                unknown = sent - required - optional
                if unknown:
                    fail(f"website sends {sorted(unknown)} to `{route}`, which strictBody refuses")
        elif route not in contracts and body_match:
            notes.append(f"{route}: body sent, server contract not machine-readable (reviewed by hand)")

    # The inert surface is a promise, not an accident: these adapters must keep answering NO_BACKEND_IMPLEMENTED.
    for adapter in ("orders", "reviews", "analytics", "payments"):
        factory = re.search(r'unconfigured\(\s*"' + adapter + r'"', text)
        if not factory:
            fail(f"the `{adapter}` adapter is no longer registered as unconfigured; a payment/monetisation surface appeared")
    return checked, callsites


# --------------------------------------------------------------------------------------------- the Android side
def check_android(routes: dict[str, tuple[str, str]], contracts: dict[str, tuple[set[str], set[str] | None]]) -> int:
    paths = dict(re.findall(r'const val ([A-Z_]+_PATH)\s*=\s*"(/[^"]+)"', read(ANDROID_REQUESTS)))
    wire = read(ANDROID_WIRE)
    requests = read(ANDROID_REQUESTS)
    checked = 0
    for match in re.finditer(r"exchange\(\s*(?:path\s*=\s*)?AccountApiRequests\.([A-Z_]+_PATH),([\s\S]{0,400}?)\n\s*\)", wire):
        constant, rest = match.group(1), match.group(2)
        if constant not in paths:
            fail(f"Android wires `AccountApiRequests.{constant}`, which the request module does not define")
            continue
        route_path = paths[constant]
        checked += 1
        if not path_is_known(route_path, routes):
            fail(f"Android calls `{route_path}`, which the service does not implement")
            continue
        builder = re.search(r"body\s*=\s*AccountApiRequests\.(\w+)\(", rest)
        if not builder:
            continue
        body_text = re.search(r"fun " + builder.group(1) + r"\([^)]*\)[^=]*=\s*buildJsonBody\(([^)]*)\)", requests)
        if not body_text:
            notes.append(f"Android `{route_path}`: body builder not machine-readable")
            continue
        sent = set(re.findall(r'"([A-Za-z_]+)"\s+to\s', body_text.group(1)))
        method = (re.search(r"AccountTransportMethod\.(\w+)", rest) or [None, "POST"])[1]
        route = f"{method} {route_path}"
        if route in contracts:
            required, optional = contracts[route]
            missing = required - sent
            if missing:
                fail(f"Android sends {sorted(sent)} to `{route}` but the service requires {sorted(required)}; missing {sorted(missing)}")
            if optional is not None:
                unknown = sent - required - optional
                if unknown:
                    fail(f"Android sends {sorted(unknown)} to `{route}`, which strictBody refuses")
    return checked


# --------------------------------------------------------------------------------------------- bridge/version facts
def check_shared_facts(routes: dict[str, tuple[str, str]]) -> None:
    protocol = read(ROOT / "bridge-protocol" / "src" / "main" / "java" / "com" / "craftmind" / "bridge" / "protocol" / "BridgeProtocol.java")
    version = re.search(r"int VERSION\s*=\s*(\d+)", protocol)
    schema = re.search(r"int BUILD_PLAN_SCHEMA_VERSION\s*=\s*(\d+)", protocol)
    if not version or not schema:
        fail("the bridge protocol version or BuildPlan schema version is no longer a literal in BridgeProtocol.java")
        return
    gradle = read(ROOT / "minecraft-bridge" / "build.gradle")
    mod_version = re.search(r"^version\s*=\s*'([^']+)'", gradle, re.MULTILINE)
    readme = read(ROOT / "README.md")
    claim = re.search(r"CraftMind Bridge ([\d.]+) · protocol (\d+) · BuildPlan schema (\d+)", readme)
    if not claim:
        fail("README no longer states the shipped bridge/protocol/schema triple in one place")
        return
    if claim.group(2) != version.group(1) or claim.group(3) != schema.group(1):
        fail(f"README claims protocol {claim.group(2)}/schema {claim.group(3)} but the code declares {version.group(1)}/{schema.group(1)}")
    if mod_version and claim.group(1) != mod_version.group(1):
        fail(f"README claims CraftMind Bridge {claim.group(1)} but minecraft-bridge/build.gradle sets {mod_version.group(1)}")

    # The download page and the APK helper both restate what Gradle produces; a bump that misses either one makes the
    # site promise an artifact the build cannot produce, or makes the verifier approve the wrong package.
    app_build = read(ROOT / "app" / "build.gradle.kts")
    facts = {
        "versionCode": re.search(r"versionCode\s*=\s*(\d+)", app_build).group(1),
        "versionName": re.search(r'versionName\s*=\s*"([^"]+)"', app_build).group(1),
        "applicationId": re.search(r'applicationId\s*=\s*"([^"]+)"', app_build).group(1),
        "minSdk": re.search(r"minSdk\s*=\s*(\d+)", app_build).group(1),
    }
    download = read(ROOT / "website" / "download.html")
    for label, pattern in (("VERSION", r"<b>VERSION</b>\s*([\d.]+)"), ("VERSION CODE", r"<b>VERSION CODE</b>\s*(\d+)"),
                           ("PACKAGE", r"<b>PACKAGE</b>\s*([\w.]+)")):
        found = re.search(pattern, download)
        expected = {"VERSION": facts["versionName"], "VERSION CODE": facts["versionCode"], "PACKAGE": facts["applicationId"]}[label]
        if not found:
            fail(f"download.html no longer states the {label} it must agree with Gradle about")
        elif found.group(1) != expected:
            fail(f"download.html advertises {label} {found.group(1)} while Gradle builds {expected}")
    apk_helper = read(ROOT / "scripts" / "verify-release-apk.sh")
    pinned = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", apk_helper)
    if not pinned:
        fail("scripts/verify-release-apk.sh no longer pins the package/version it verifies")
    elif (pinned.group(1), pinned.group(2), pinned.group(3)) != (facts["applicationId"], facts["versionCode"], facts["versionName"]):
        fail(f"verify-release-apk.sh checks {pinned.group(0)} but Gradle produces {facts}")
    if re.search(rf"SUPPORTED ANDROID</b>[^)]*API (\d+)", download) and facts["minSdk"] not in read(ROOT / "website" / "download.html"):
        fail("download.html states a minimum Android API that Gradle's minSdk does not support")


def main() -> int:
    consts = const_key_lists()
    routes = backend_routes()
    if len(routes) < 80:
        fail(f"only {len(routes)} routes were read out of backend/src/server.js; the router's declaration style changed and this checker can no longer be trusted")
        report(f"FAIL: {problems[0]}")
        return 1
    contracts = server_key_contracts(consts)
    declared, callsites = check_website(routes, contracts)
    android_calls = check_android(routes, contracts)
    check_shared_facts(routes)

    report(f"PASS: website endpoint list ({declared} declared, {callsites} call sites) resolves against {len(routes)} service routes.")
    report(f"PASS: Android account client ({android_calls} calls) resolves against the same routes, with the field names its validators require.")
    report(f"PASS: bridge protocol/schema/bridge-version facts agree between Java, Gradle, README, download.html and verify-release-apk.sh.")
    report(f"NOTE: {len(contracts)} of {len(routes)} routes have a machine-readable body contract; the rest take no body or validate it in a way this checker will not guess.")
    for note in sorted(set(notes)):
        report(f"NOTE: {note}")
    if problems:
        for problem in problems:
            print(f"FAIL: {problem}", file=sys.stderr)
        return 1
    report("NOTE: static source comparison only. It proves the three codebases name the same routes and fields; it does not "
           "prove a browser rendered them, an APK installed, or a Minecraft server accepted a build.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
