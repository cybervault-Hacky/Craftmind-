#!/usr/bin/env python3
"""Dependency-free integrity, navigation, accessibility, and honesty check for the static website.

Phase 15 checked eight pages. Phase 21 keeps every one of those assertions and extends them to the whole
marketplace / creator / membership / account interface foundation:

* every route in the information architecture exists, carries the expected controller key, and exposes the region
  hooks its controller drives;
* one shared navigation contract (nine public links in order, one download call to action, a footer link block, a
  mobile disclosure, and a contextual section navigation that marks the current sub-page);
* the developer control plane stays unreachable from public navigation, and the Android information architecture is
  untouched by a website-only phase;
* no fabricated artifact, purchase, subscription, statistic, review, listing, or price, and no payment provider,
  secret, browser storage, or hardcoded API origin anywhere in front-end source.

Run it from the repository root: `python3 scripts/check_website.py`.
"""

from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
import posixpath
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "website"

# --------------------------------------------------------------------------- information architecture

# The public navigation, in order. Every page carries exactly these nine links plus the download call to action.
PUBLIC_NAV = (
    ("index.html", "Home"),
    ("marketplace/index.html", "Marketplace"),
    ("membership/index.html", "Membership"),
    ("creators/index.html", "Creators"),
    ("how-it-works.html", "How it works"),
    ("features.html", "Features"),
    ("download.html", "Download"),
    ("about.html", "About"),
    ("faq.html", "FAQ"),
)

FOOTER_NAV = (
    "marketplace/index.html",
    "membership/index.html",
    "creator/index.html",
    "account/index.html",
    "privacy.html",
    "terms.html",
    "download.html",
    "faq.html",
    "https://github.com/cybervault-Hacky/Craftmind-",
)

# (page, controller key, section that owns the page in the public navigation, required region hooks)
ROUTES = (
    ("index.html", "index", "index.html", ()),
    ("how-it-works.html", "how-it-works", "how-it-works.html", ()),
    ("features.html", "features", "features.html", ()),
    ("download.html", "download", "download.html", ()),
    ("about.html", "about", "about.html", ()),
    ("faq.html", "faq", "faq.html", ()),
    ("privacy.html", "privacy", None, ()),
    ("terms.html", "terms", None, ()),
    # Phase 24: the dedicated account experience — sign-in/registration, role choice, and the two onboarding forms.
    # None: these pages sit outside the public navigation and the account section shell, and render no side navigation.
    ("signin.html", "signin", None, ("data-signin-region", "data-register-region")),
    ("onboarding/index.html", "onboarding", None, ("data-onboarding-state", "data-role-choice")),
    ("onboarding/buyer.html", "onboarding-buyer", None, ("data-buyer-status", "data-buyer-form")),
    ("onboarding/seller.html", "onboarding-seller", None, ("data-seller-status", "data-seller-form")),
    ("marketplace/index.html", "marketplace-home", "marketplace/index.html", ("data-catalog-region", "data-catalog-search", "data-catalog-status", "data-saved-region", "data-featured-region", "data-creators-region")),
    ("marketplace/build.html", "marketplace-build", "marketplace/index.html", ("data-build-region",)),
    ("membership/index.html", "membership", "membership/index.html", ("data-plan-grid", "data-membership-state", "data-plan-comparison", "data-period-toggle")),
    ("creators/index.html", "creators", "creators/index.html", ("data-creators-directory",)),
    ("creators/profile.html", "creator-profile", "creators/index.html", ("data-creator-profile", "data-creator-listings", "data-creator-reviews")),
    ("account/index.html", "account", None, ("data-account-region", "data-account-summary")),
    ("account/profile.html", "account-profile", None, ("data-account-region",)),
    ("account/security.html", "account-security", None, ("data-account-region", "data-security-capabilities")),
    ("account/sessions.html", "account-sessions", None, ("data-account-region",)),
    ("account/purchases.html", "account-purchases", None, ("data-account-region",)),
    ("account/saved.html", "account-saved", None, ("data-account-region",)),
    ("account/membership.html", "account-membership", None, ("data-account-region",)),
    ("creator/index.html", "creator-dashboard", None, ("data-creator-metrics", "data-creator-panels")),
    ("creator/listings/index.html", "creator-listings", None, ("data-listings-region",)),
    ("creator/listings/new.html", "creator-listing-new", None, ("data-wizard-steps", "data-wizard-panel", "data-wizard-progress", "data-wizard-next", "data-wizard-back")),
    ("creator/listings/edit.html", "creator-listing-edit", None, ("data-wizard-steps", "data-wizard-panel")),
    ("creator/orders/index.html", "creator-orders", None, ("data-orders-region",)),
    ("creator/earnings/index.html", "creator-earnings", None, ("data-earnings-region", "data-earnings-metrics")),
    ("creator/reviews/index.html", "creator-reviews", None, ("data-reviews-region",)),
    ("creator/analytics/index.html", "creator-analytics", None, ("data-analytics-region", "data-analytics-metrics")),
    ("creator/profile/index.html", "creator-profile-edit", None, ("data-creator-profile-form",)),
    ("creator/settings/index.html", "creator-settings", None, ()),
)

# Every page that belongs to a contextual section navigation (account shell, creator studio shell).
SECTION_SHELLS = {
    "account": ("account/", "Account navigation"),
    "creator": ("creator/", "Creator studio navigation"),
}

REQUIRED_HOME_COPY = (
    "CRAFTMIND",
    "AI MINECRAFT BUILDER",
    "Describe it. Show it. Build it.",
    "DOWNLOAD APK",
    "HOW IT WORKS",
    "not yet been built, installed, or published",
    "com.craftmind.app",
    "0.92.2+1.20.1",
)

FORBIDDEN_FABRICATED_LINK = re.compile(
    r"github\.com/[^\s\"']+/releases/latest/download|href=[\"'][^\"']+\.apk(?:[\"']|$)",
    re.IGNORECASE,
)

# Honesty is a code property: the interface must keep saying what is not implemented.
HONESTY_REQUIREMENTS = (
    ("assets/marketplace.js", "Purchases are coming soon"),
    ("assets/marketplace.js", "The marketplace backend is not implemented yet."),
    ("assets/marketplace.js", "not implemented"),
    ("assets/creator-studio.js", "Marketplace publishing will be available soon"),
    ("assets/creator-studio.js", "Your sales data will appear here once marketplace selling is available."),
    ("assets/creator-studio.js", "No commission rate exists in this phase"),
    ("assets/creator-studio.js", "arbitrary illustration of the layout, not the CraftMind fee"),
    ("assets/creator-studio.js", "Nothing was uploaded, nothing was stored"),
    ("assets/creator-studio.js", "Analytics are not collected"),
    ("assets/membership.js", "Not priced yet"),
    ("assets/membership.js", "Not implemented"),
    ("assets/membership.js", "Billing is not implemented"),
    ("assets/state.js", "Not available yet"),
    ("assets/components.js", "No ratings yet"),
    ("assets/account.js", "No account service is configured for this site"),
    ("assets/adapters.js", "unavailable"),
    ("assets/preview-catalog.js", "Sample build"),
    # Phase 24: the onboarding controller keeps stating what does not exist, even in success paths.
    ("assets/onboarding.js", "No account service is configured for this site"),
    ("assets/onboarding.js", "Phone verification does not exist in CraftMind yet"),
    ("assets/onboarding.js", "not identity verification"),
    ("assets/onboarding.js", "does not publish anything"),
    ("assets/onboarding.js", "No plan is purchasable in this phase"),
    ("assets/onboarding.js", "No payout, price, or financial detail was collected"),
)

# Sample data must be reachable only through an explicit, labelled preview mode.
PREVIEW_GATED_MODULES = ("assets/marketplace.js", "assets/creator-studio.js")

STATE_KINDS = ("loading", "empty", "populated", "error", "unauthorized", "unavailable", "disabled", "success")

WIZARD_STEPS = ("details", "media", "compatibility", "pricing", "instructions", "preview", "publish")

FORBIDDEN_SOURCE_PATTERNS = (
    (re.compile(r"\blocalStorage\b"), "browser localStorage"),
    (re.compile(r"\bsessionStorage\b"), "browser sessionStorage"),
    (re.compile(r"\bindexedDB\b"), "browser IndexedDB"),
    (re.compile(r"document\.cookie"), "cookie access"),
    (re.compile(r"razorpay|stripe|paypal|braintree|adyen|squareup|instamojo", re.IGNORECASE), "a payment provider"),
    (re.compile(r"client[_-]?secret|api[_-]?key|secret[_-]?key|private[_-]?key|bearer\s+[A-Za-z0-9._-]{16,}", re.IGNORECASE), "secret material"),
    (re.compile(r"https?://(?:api\.|localhost|127\.0\.0\.1)"), "a hardcoded API origin"),
    (re.compile(r"new\s+XMLHttpRequest|navigator\.sendBeacon\s*\("), "a second network API"),
)

FORBIDDEN_PAGE_PATTERNS = (
    (re.compile(r"(?:razorpay|stripe|paypal|braintree|adyen|instamojo)", re.IGNORECASE), "a payment provider"),
    (re.compile(r"href=[\"'][^\"']*developer(?:\.html|/)[\"']", re.IGNORECASE), "a public link to the developer control plane"),
)


class PageInspector(HTMLParser):
    """Collects the structural facts every assertion below needs from a single page."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: set[str] = set()
        self.hooks: set[str] = set()
        self.links: list[str] = []
        self.headings: dict[int, int] = {level: 0 for level in range(1, 7)}
        self.buttons: list[tuple[dict[str, str | None], str]] = []
        self.main_nav: list[tuple[str, str, str | None]] = []
        self.footer_nav: list[str] = []
        self.side_navs: dict[str, list[tuple[str, str | None]]] = {}
        self.scripts: list[tuple[str, str | None]] = []
        self.external_resources: list[str] = []
        self.body_attributes: dict[str, str | None] = {}
        self.has_viewport = False
        self.has_skip_link = False
        self.main_ids: list[str] = []
        self.title = ""
        self.description = ""
        self.language = ""
        self._button_attrs: dict[str, str | None] | None = None
        self._button_text: list[str] = []
        self._nav_stack: list[str] = []
        self._side_nav_label: str | None = None
        self._in_title = False
        self._in_body = False

    # -- helpers -------------------------------------------------------------------------------------
    def _record_nav_link(self, href: str, css_class: str, current: str | None) -> None:
        scope = self._nav_stack[-1] if self._nav_stack else None
        if scope == "main":
            self.main_nav.append((href, css_class, current))
        elif scope == "footer":
            self.footer_nav.append(href)
        elif scope and scope.startswith("side:"):
            label = scope[len("side:"):]
            self.side_navs.setdefault(label, []).append((href, current))

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.add(str(values["id"]))
        for name in values:
            if name.startswith("data-"):
                self.hooks.add(name)
        if tag == "html" and values.get("lang"):
            self.language = str(values["lang"])
        if tag == "body":
            self._in_body = True
            self.body_attributes = values
        if tag == "title":
            self._in_title = True
        if tag == "a" and values.get("href"):
            href = str(values["href"])
            self.links.append(href)
            if "skip-link" in (values.get("class") or "") and href == "#main":
                self.has_skip_link = True
            self._record_nav_link(href, values.get("class") or "", values.get("aria-current"))
        if tag == "main" and values.get("id"):
            self.main_ids.append(str(values["id"]))
        if re.fullmatch(r"h[1-6]", tag):
            self.headings[int(tag[1])] += 1
        if tag == "button":
            self._button_attrs = values
            self._button_text = []
        if tag == "meta":
            name = (values.get("name") or "").lower()
            if name == "viewport":
                self.has_viewport = True
            if name == "description" and values.get("content"):
                self.description = str(values["content"])
        if tag in {"script", "iframe"} and values.get("src"):
            self.external_resources.append(str(values["src"]))
            if tag == "script":
                self.scripts.append((str(values["src"]), values.get("type")))
        if tag == "link" and values.get("href"):
            self.external_resources.append(str(values["href"]))
        if tag == "nav":
            label = values.get("aria-label") or ""
            if label == "Main navigation":
                self._nav_stack.append("main")
            elif label == "Footer":
                self._nav_stack.append("footer")
            else:
                self._nav_stack.append(f"side:{label}")

    def handle_endtag(self, tag: str) -> None:
        if tag == "button" and self._button_attrs is not None:
            self.buttons.append((self._button_attrs, " ".join(self._button_text).strip()))
            self._button_attrs = None
            self._button_text = []
        if tag == "nav" and self._nav_stack:
            self._nav_stack.pop()
        if tag == "title":
            self._in_title = False
        if tag == "body":
            self._in_body = False

    def handle_data(self, data: str) -> None:
        if self._button_attrs is not None:
            self._button_text.append(data)
        if self._in_title:
            self.title += data


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def inspect(relative: str) -> tuple[str, PageInspector]:
    path = SITE / relative
    if not path.is_file():
        fail(f"website/{relative} is missing")
    html = path.read_text(encoding="utf-8")
    parser = PageInspector()
    parser.feed(html)
    parser.close()
    return html, parser


def resolve(page: str, href: str) -> str:
    """Resolves a link as a browser would, giving a path relative to `website/`."""
    target = href.split("?", 1)[0].split("#", 1)[0]
    if not target:
        return page
    return posixpath.normpath(posixpath.join(posixpath.dirname(page), target))


def check_page(relative: str, page_key: str, nav_section: str | None, hooks: tuple[str, ...]) -> tuple[str, PageInspector]:
    html, parser = inspect(relative)

    # -- document basics ----------------------------------------------------------------------------
    if parser.language != "en":
        fail(f"website/{relative}: the document language must be declared as en")
    if not parser.title.strip() or "CraftMind" not in parser.title:
        fail(f"website/{relative}: the title must name the product")
    if len(parser.description.strip()) < 40:
        fail(f"website/{relative}: a descriptive meta description is required")
    if not parser.has_viewport:
        fail(f"website/{relative}: responsive viewport metadata is missing")
    if parser.headings[1] != 1:
        fail(f"website/{relative}: expected exactly one h1, found {parser.headings[1]}")
    if not parser.has_skip_link or "main" not in parser.main_ids:
        fail(f"website/{relative}: keyboard skip link must target the main landmark")
    if str(parser.body_attributes.get("data-page")) != page_key:
        fail(f'website/{relative}: body data-page must be "{page_key}" to select its controller')

    missing_hooks = sorted(hook for hook in hooks if hook not in parser.hooks)
    if missing_hooks:
        fail(f"website/{relative}: region hook(s) missing: {', '.join(missing_hooks)}")

    # -- resources and links ------------------------------------------------------------------------
    for resource in parser.external_resources:
        if resource.startswith(("https://", "http://", "//")):
            fail(f"website/{relative}: unexpected remote browser resource: {resource}")
        if not (SITE / resolve(relative, resource)).is_file():
            fail(f"website/{relative}: local browser resource is missing: {resource}")

    scripts = [(resolve(relative, src), kind) for src, kind in parser.scripts]
    if ("assets/site.js", "module") not in scripts:
        fail(f"website/{relative}: the shared module entry point assets/site.js is missing or not a module")

    missing_anchors = sorted({link[1:] for link in parser.links if link.startswith("#") and link[1:] not in parser.ids})
    if missing_anchors:
        fail(f"website/{relative}: unresolved in-page link(s): " + ", ".join(missing_anchors))

    for link in parser.links:
        if link.startswith(("https://", "http://", "mailto:", "tel:", "#")):
            continue
        target = resolve(relative, link)
        if not (SITE / target).exists():
            fail(f"website/{relative}: local link target is missing: {link}")

    # -- navigation contract ------------------------------------------------------------------------
    expected_nav = [target for target, _label in PUBLIC_NAV]
    entries = [(href, css, current) for href, css, current in parser.main_nav if "nav-cta" not in css]
    actual_nav = [resolve(relative, href) for href, _css, _current in entries]
    if actual_nav != expected_nav:
        fail(f"website/{relative}: primary navigation must be exactly " + ", ".join(label for _t, label in PUBLIC_NAV))

    current = sorted({resolve(relative, href) for href, _css, aria in entries if aria == "page"})
    expected_current = [nav_section] if nav_section else []
    if current != expected_current:
        fail(
            f"website/{relative}: exactly {expected_current or 'no navigation entry'} must carry aria-current=\"page\""
            f" (found {current})",
        )

    cta = [resolve(relative, href) for href, css, _current in parser.main_nav if "nav-cta" in css]
    if cta != ["download.html"]:
        fail(f"website/{relative}: the download call to action must lead to download.html")

    toggles = [attrs for attrs, _text in parser.buttons if "data-nav-toggle" in attrs]
    if len(toggles) != 1:
        fail(f"website/{relative}: expected exactly one navigation disclosure button, found {len(toggles)}")
    if toggles[0].get("aria-controls") != "primary-nav" or toggles[0].get("aria-expanded") != "false":
        fail(f"website/{relative}: the navigation disclosure must control #primary-nav and start collapsed")
    if "primary-nav" not in parser.ids:
        fail(f"website/{relative}: the navigation target #primary-nav is missing")

    footer_expected = [target for target in FOOTER_NAV if not target.startswith("http")]
    footer_actual = [resolve(relative, href) for href in parser.footer_nav if not href.startswith("http")]
    if footer_actual != footer_expected:
        fail(f"website/{relative}: the footer link block must be exactly " + ", ".join(footer_expected))

    # -- contextual section shell -------------------------------------------------------------------
    section = next((name for name, (directory, _label) in SECTION_SHELLS.items() if relative.startswith(directory)), None)
    if section:
        directory, label = SECTION_SHELLS[section]
        entries = parser.side_navs.get(label)
        if not entries:
            fail(f"website/{relative}: the {label.lower()} is missing")
        marked = [href for href, aria in entries if aria == "page"]
        if len(marked) != 1:
            fail(f"website/{relative}: exactly one entry in the {label.lower()} must be marked as current")
        targets = [resolve(relative, href) for href, _aria in entries]
        if len(set(targets)) != len(targets) or not all((SITE / target).is_file() for target in targets):
            fail(f"website/{relative}: every {label.lower()} entry must resolve to a distinct page")
    elif parser.side_navs:
        fail(f"website/{relative}: a page outside a section shell must not render a section navigation")

    return html, parser


def read(relative: str) -> str:
    path = SITE / relative
    if not path.is_file():
        fail(f"website/{relative} is missing")
    return path.read_text(encoding="utf-8")


def check_front_end_sources() -> None:
    """Honesty, privacy, and integration-boundary invariants that no page can satisfy on its own."""
    modules = sorted(path for path in (SITE / "assets").glob("*.js"))
    if len(modules) < 8:
        fail("the interface foundation must ship a shared {state, adapters, components, site} module set")

    network_calls: list[tuple[str, str]] = []
    for module in modules:
        content = module.read_text(encoding="utf-8")
        name = f"assets/{module.name}"
        for pattern, description in FORBIDDEN_SOURCE_PATTERNS:
            match = pattern.search(content)
            if match:
                fail(f"website/{name}: {description} must not appear in front-end source (found {match.group(0)!r})")
        if re.search(r"\bfetch\s*\(", content):
            network_calls.append((name, content))

    if [name for name, _content in network_calls] != ["assets/adapters.js"]:
        fail(f"network requests must live only in the adapter module, found: {[name for name, _ in network_calls]}")

    adapters = read("assets/adapters.js")
    for marker in ("unconfigured", "contract", "CRAFTMIND_SITE_CONFIG", "https:"):
        if marker not in adapters:
            fail(f"website/assets/adapters.js must declare the integration boundary: {marker}")

    state = read("assets/state.js")
    for kind in STATE_KINDS:
        if kind not in state:
            fail(f"website/assets/state.js must define the {kind} state")

    studio = read("assets/creator-studio.js")
    order = [studio.index(f'id: "{step}"') for step in WIZARD_STEPS]
    if order != sorted(order):
        fail("the create-listing wizard must run details → media → compatibility → pricing → instructions → preview → publish")

    for relative, phrase in HONESTY_REQUIREMENTS:
        if phrase not in read(relative):
            fail(f"website/{relative} must keep the honest statement: {phrase}")

    for relative in PREVIEW_GATED_MODULES:
        content = read(relative)
        if "previewRequested(" not in content:
            fail(f"website/{relative} must gate every sample record behind an explicit preview request")
        if not re.search(r"preview.*(DEMO_|PREVIEW_|SAMPLE_)", content, re.IGNORECASE | re.DOTALL):
            fail(f"website/{relative}: sample records must be guarded by the preview check")

    marketplace = read("assets/marketplace.js")
    for attrs, text in re.findall(r"<button([^>]*)>([^<]*)</button>", marketplace):
        if re.search(r"\b(buy|purchase|checkout|pay)\b", text, re.IGNORECASE) and "disabled" not in attrs:
            fail(f"website/assets/marketplace.js: the '{text.strip()}' control must stay disabled until checkout exists")

    membership = read("assets/membership.js")
    for text in re.findall(r"<button[^>]*>([^<]*)</button>", membership):
        if "Upgrade" in text and "not available" not in text:
            fail(f"website/assets/membership.js: the plan action '{text.strip()}' must state that it is not available")

    account = read("assets/account.js")
    for secret in ("token:", "sessionId", "userId", "password:", "idToken"):
        if re.search(rf"\b{secret}", account):
            fail(f"website/assets/account.js must never render internal identifiers or credentials ({secret})")

    # The developer control plane is authorised by the backend, never by a public link.
    if not (ROOT / "backend" / "public" / "developer.html").is_file():
        fail("backend/public/developer.html is missing: the protected developer area must be preserved")
    control_plane = re.compile(r"developer\.html|/developer/|control plane", re.IGNORECASE)
    for relative, _key, _section, _hooks in ROUTES:
        if control_plane.search(read(relative)):
            fail(f"website/{relative}: the developer control plane must not be referenced from public pages")

    # Phase 21 scope: the marketplace interface belongs to the website, so the app's four destinations are untouched.
    destination = ROOT / "app/src/main/java/com/craftmind/app/presentation/navigation/MainDestination.kt"
    if destination.is_file():
        kotlin = destination.read_text(encoding="utf-8")
        entries = re.findall(r"^\s{4}([A-Z]+)\(\s*$", kotlin, re.MULTILINE)
        if entries != ["HOME", "BUILDS", "MINECRAFT", "SETTINGS"]:
            fail(f"the Android information architecture changed: found {entries}")


def check_pages() -> dict[str, tuple[str, PageInspector]]:
    site_pages = {str(path.relative_to(SITE)) for path in SITE.rglob("*.html")}
    declared = {relative for relative, _key, _section, _hooks in ROUTES}
    if site_pages != declared:
        unexpected = sorted(site_pages - declared)
        missing = sorted(declared - site_pages)
        fail(f"the page set must match the information architecture exactly (unexpected: {unexpected}, missing: {missing})")

    inspected: dict[str, tuple[str, PageInspector]] = {}
    for relative, page_key, nav_section, hooks in ROUTES:
        html, parser = check_page(relative, page_key, nav_section, hooks)
        for pattern, description in FORBIDDEN_PAGE_PATTERNS:
            if pattern.search(html):
                fail(f"website/{relative}: found {description}")
        inspected[relative] = (html, parser)
    return inspected


def check_css() -> None:
    css = read("styles.css")
    for marker in (
        "@media (max-width: 680px)",
        "@media (max-width: 940px)",
        "@media (max-width: 390px)",
        "prefers-reduced-motion: reduce",
        ".nav-toggle",
        ".context-strip",
        ".listing-card",
        ".plan-card",
        ".wizard-step",
        ".app-shell",
        '.side-nav',
        '.data-table',
        'attr(data-label)',
    ):
        if marker not in css:
            fail(f"website/styles.css is missing the Phase 21 marker: {marker}")
    if re.search(r"""@import\s|url\(\s*["']?(?:https?:)?//""", css, re.IGNORECASE):
        fail("unexpected remote CSS dependency was found")


def check_shared_copy(inspected: dict[str, tuple[str, PageInspector]]) -> None:
    home_html = inspected["index.html"][0]
    for phrase in REQUIRED_HOME_COPY:
        if phrase not in home_html:
            fail(f"required product/status copy is missing from website/index.html: {phrase}")

    download_html, download_parser = inspected["download.html"]
    for phrase in (
        "not yet been built, installed, or published",
        "com.craftmind.app",
        "1.0.0",
        "10000",
        "Android 8.0+",
        "SHA-256",
    ):
        if phrase not in download_html:
            fail(f"website/download.html must state: {phrase}")

    site_buttons = [
        (page, attrs, text)
        for page, (_, parser) in inspected.items()
        for attrs, text in parser.buttons
        if "DOWNLOAD APK" in text.upper()
    ]
    if len(site_buttons) != 1:
        fail(f"expected exactly one DOWNLOAD APK button across the site, found {len(site_buttons)}")
    page, attrs, _text = site_buttons[0]
    if "disabled" not in attrs:
        fail(f"website/{page}: the DOWNLOAD APK button must be disabled until a signed artifact exists")
    if page != "download.html":
        fail("the disabled DOWNLOAD APK button must live on the download page")

    for page, (html, _parser) in inspected.items():
        if FORBIDDEN_FABRICATED_LINK.search(html):
            fail(f"website/{page}: a fabricated or unverified direct APK link was found")

    if "final legal review" not in inspected["terms.html"][0]:
        fail("website/terms.html must be marked as requiring final legal review")

    for phrase in ("Keystore", "AES-GCM", "private-LAN"):
        if phrase not in inspected["privacy.html"][0]:
            fail(f"website/privacy.html must describe the implemented guarantee: {phrase}")

    about_html = inspected["about.html"][0]
    for phrase in ("Sarthak Bharambe", "no company registration", "no team", "no office", "no investors", "no awards"):
        if phrase not in about_html:
            fail(f"website/about.html must state the honest attribution/limits copy: {phrase}")

    if "marketplace" not in inspected["features.html"][0]:
        fail("website/features.html must state that no marketplace is implemented")

    # The purchase surface must stay disabled on the build-detail page itself.
    build_html, build_parser = inspected["marketplace/build.html"]
    for attrs, text in build_parser.buttons:
        if re.search(r"\b(buy|purchase|download)\b", text, re.IGNORECASE) and "disabled" not in attrs:
            fail(f"website/marketplace/build.html: the '{text.strip()}' control must stay disabled")
    if "Purchases are coming soon" not in build_html and "Purchases are coming soon" not in read("assets/marketplace.js"):
        fail("website/marketplace/build.html must state that purchases are coming soon")


def main() -> None:
    inspected = check_pages()
    check_shared_copy(inspected)
    check_front_end_sources()
    check_css()

    print(
        "PASS: {pages} pages across the public, marketplace, membership, creator, and account sections; shared "
        "navigation and section shells; {links} links; one disabled APK placeholder; honest preview/commission/"
        "purchase copy; responsive, reduced-motion, and state-system markers; no remote resource, payment provider, "
        "browser storage, secret, or hardcoded API origin.".format(
            pages=len(ROUTES),
            links=sum(len(parser.links) for _, parser in inspected.values()),
        )
    )


if __name__ == "__main__":
    main()
