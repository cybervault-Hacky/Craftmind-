#!/usr/bin/env python3
"""Dependency-free integrity, navigation, and honesty check for the static multi-page website.

Every assertion the single-page checker made is preserved here, extended to eight pages: the same required copy on
the home page, exactly one disabled DOWNLOAD APK button for the whole site, no fabricated APK or GitHub
"releases/latest" link, the same responsive/reduced-motion CSS markers, no remote browser resource, and every
in-page anchor and local link resolving to a real file.
"""

from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "website"

PAGES = [
    "index.html",
    "how-it-works.html",
    "features.html",
    "download.html",
    "about.html",
    "faq.html",
    "privacy.html",
    "terms.html",
]

NAV_LABELS = {
    "index.html": "Home",
    "how-it-works.html": "How it works",
    "features.html": "Features",
    "download.html": "Download",
    "about.html": "About",
    "faq.html": "FAQ",
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


class PageInspector(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: set[str] = set()
        self.links: list[str] = []
        self.headings: dict[int, int] = {level: 0 for level in range(1, 7)}
        self.buttons: list[tuple[set[str], str]] = []
        self.nav_links: list[tuple[str, str, str | None]] = []
        self.external_resources: list[str] = []
        self.has_viewport = False
        self.has_skip_link = False
        self._button_attrs: set[str] | None = None
        self._button_text: list[str] = []
        self._in_nav = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.add(str(values["id"]))
        if tag == "a" and values.get("href"):
            href = str(values["href"])
            self.links.append(href)
            if "skip-link" in (values.get("class") or "") and href == "#main":
                self.has_skip_link = True
            if self._in_nav:
                self.nav_links.append((href, values.get("class") or "", values.get("aria-current")))
        if re.fullmatch(r"h[1-6]", tag):
            level = int(tag[1])
            self.headings[level] += 1
        if tag == "button":
            self._button_attrs = set(values)
            self._button_text = []
        if tag == "meta" and values.get("name", "").lower() == "viewport":
            self.has_viewport = True
        if tag in {"script", "iframe"} and values.get("src"):
            self.external_resources.append(str(values["src"]))
        if tag == "link" and values.get("href"):
            self.external_resources.append(str(values["href"]))
        if tag == "nav":
            self._in_nav = values.get("aria-label") == "Main navigation"

    def handle_endtag(self, tag: str) -> None:
        if tag == "button" and self._button_attrs is not None:
            self.buttons.append((self._button_attrs, " ".join(self._button_text).strip()))
            self._button_attrs = None
            self._button_text = []
        if tag == "nav":
            self._in_nav = False

    def handle_data(self, data: str) -> None:
        if self._button_attrs is not None:
            self._button_text.append(data)


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def inspect(path: Path) -> tuple[str, PageInspector]:
    parser = PageInspector()
    html = path.read_text(encoding="utf-8")
    parser.feed(html)
    parser.close()
    return html, parser


def check_page(page: str) -> tuple[str, PageInspector]:
    path = SITE / page
    if not path.is_file():
        fail(f"website/{page} is missing")
    html, parser = inspect(path)

    if parser.headings[1] != 1:
        fail(f"website/{page}: expected exactly one h1, found {parser.headings[1]}")
    if not parser.has_viewport:
        fail(f"website/{page}: responsive viewport metadata is missing")
    if not parser.has_skip_link:
        fail(f"website/{page}: keyboard skip link is missing")

    missing_anchors = sorted({link[1:] for link in parser.links if link.startswith("#") and link[1:] not in parser.ids})
    if missing_anchors:
        fail(f"website/{page}: unresolved in-page link(s): " + ", ".join(missing_anchors))

    for resource in parser.external_resources:
        if resource.startswith(("https://", "http://", "//")):
            fail(f"website/{page}: unexpected remote browser resource: {resource}")
        if not (SITE / resource).is_file():
            fail(f"website/{page}: local browser resource is missing: {resource}")

    for link in parser.links:
        if link.startswith(("https://", "http://", "mailto:", "tel:", "#")):
            continue
        local_path = link.split("?", 1)[0].split("#", 1)[0]
        if local_path and not (SITE / local_path).is_file():
            fail(f"website/{page}: local link target is missing: {link}")

    expected_nav = list(NAV_LABELS.items())
    actual_nav = [
        (href, current)
        for href, css_class, current in parser.nav_links
        if href in NAV_LABELS and "nav-cta" not in css_class
    ]
    if [href for href, _ in actual_nav] != [href for href, _ in expected_nav]:
        fail(f"website/{page}: primary navigation must be exactly " + ", ".join(l for _, l in expected_nav))
    current = [href for href, is_current in actual_nav if is_current == "page"]
    if page in NAV_LABELS:
        if current != [page]:
            fail(f"website/{page}: exactly this page must carry aria-current=\"page\" (found {current})")
    elif current:
        fail(f"website/{page}: a page outside the primary navigation must not mark a nav entry as current")
    cta = [href for href, css_class, _ in parser.nav_links if "nav-cta" in css_class]
    if cta != ["download.html"]:
        fail(f"website/{page}: the navigation download call to action is missing")

    return html, parser


def check_css() -> None:
    css = (SITE / "styles.css").read_text(encoding="utf-8")
    if "@media (max-width: 680px)" not in css:
        fail("mobile responsive styles are missing")
    if "prefers-reduced-motion: reduce" not in css:
        fail("reduced-motion support is missing")
    if re.search(r"""@import\s|url\(\s*["']?(?:https?:)?//""", css, re.IGNORECASE):
        fail("unexpected remote CSS dependency was found")


def main() -> None:
    inspected = {page: check_page(page) for page in PAGES}

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

    terms_html = inspected["terms.html"][0]
    if "final legal review" not in terms_html:
        fail("website/terms.html must be marked as requiring final legal review")

    privacy_html = inspected["privacy.html"][0]
    for phrase in ("Keystore", "AES-GCM", "private-LAN"):
        if phrase not in privacy_html:
            fail(f"website/privacy.html must describe the implemented guarantee: {phrase}")

    about_html = inspected["about.html"][0]
    for phrase in (
        "Sarthak Bharambe",
        "no company registration",
        "no team",
        "no office",
        "no investors",
        "no awards",
    ):
        if phrase not in about_html:
            fail(f"website/about.html must state the honest attribution/limits copy: {phrase}")

    if "marketplace" not in inspected["features.html"][0]:
        fail("website/features.html must state that no marketplace is implemented")

    check_css()

    print(
        "PASS: {pages} pages, {links} links, one disabled APK placeholder, honest download/terms/attribution "
        "copy, responsive + reduced-motion markers, and no remote or fabricated resources.".format(
            pages=len(PAGES),
            links=sum(len(parser.links) for _, parser in inspected.values()),
        )
    )


if __name__ == "__main__":
    main()
