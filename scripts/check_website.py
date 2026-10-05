#!/usr/bin/env python3
"""Small dependency-free integrity and honesty check for the static website."""

from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "website"
INDEX = SITE / "index.html"


class SiteInspector(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: set[str] = set()
        self.links: list[str] = []
        self.headings: dict[int, int] = {}
        self.buttons: list[tuple[set[str], str]] = []
        self._button_attrs: set[str] | None = None
        self._button_text: list[str] = []
        self.external_resources: list[str] = []
        self.has_viewport = False
        self.has_skip_link = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.add(str(values["id"]))
        if tag == "a" and values.get("href"):
            self.links.append(str(values["href"]))
            if "skip-link" in (values.get("class") or "") and values["href"] == "#main":
                self.has_skip_link = True
        if re.fullmatch(r"h[1-6]", tag):
            level = int(tag[1])
            self.headings[level] = self.headings.get(level, 0) + 1
        if tag == "button":
            self._button_attrs = set(values)
            self._button_text = []
        if tag == "meta" and values.get("name", "").lower() == "viewport":
            self.has_viewport = True
        if tag in {"script", "iframe"} and values.get("src"):
            self.external_resources.append(str(values["src"]))
        if tag == "link" and values.get("href"):
            self.external_resources.append(str(values["href"]))

    def handle_data(self, data: str) -> None:
        if self._button_attrs is not None:
            self._button_text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "button" and self._button_attrs is not None:
            self.buttons.append((self._button_attrs, " ".join(self._button_text).strip()))
            self._button_attrs = None
            self._button_text = []


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    if not INDEX.is_file():
        fail("website/index.html is missing")
    html = INDEX.read_text(encoding="utf-8")
    parser = SiteInspector()
    parser.feed(html)
    parser.close()

    required_copy = (
        "CRAFTMIND",
        "AI MINECRAFT BUILDER",
        "Describe it. Show it. Build it.",
        "DOWNLOAD APK",
        "HOW IT WORKS",
        "not yet been built, installed, or published",
        "com.craftmind.app",
        "0.92.2+1.20.1",
    )
    for phrase in required_copy:
        if phrase not in html:
            fail(f"required product/status copy is missing: {phrase}")

    if parser.headings.get(1, 0) != 1:
        fail("expected exactly one h1")
    if not parser.has_viewport:
        fail("responsive viewport metadata is missing")
    if not parser.has_skip_link:
        fail("keyboard skip link is missing")

    missing_anchors = sorted({link[1:] for link in parser.links if link.startswith("#") and link[1:] not in parser.ids})
    if missing_anchors:
        fail("unresolved in-page link(s): " + ", ".join(missing_anchors))

    download_buttons = [
        (attrs, text)
        for attrs, text in parser.buttons
        if "DOWNLOAD APK" in text.upper()
    ]
    if len(download_buttons) != 1 or "disabled" not in download_buttons[0][0]:
        fail("the only DOWNLOAD APK button must be disabled until a signed artifact exists")

    if re.search(r"github\.com/[^\s\"']+/releases/latest/download|href=[\"'][^\"']+\.apk(?:[\"']|$)", html, re.IGNORECASE):
        fail("a fabricated or unverified direct APK link was found")
    css = (SITE / "styles.css").read_text(encoding="utf-8")
    if "@media (max-width: 680px)" not in css:
        fail("mobile responsive styles are missing")
    if "prefers-reduced-motion: reduce" not in css:
        fail("reduced-motion support is missing")
    if re.search(r'''@import\s|url\(\s*["']?(?:https?:)?//''', css, re.IGNORECASE):
        fail("unexpected remote CSS dependency was found")

    for resource in parser.external_resources:
        if resource.startswith(("https://", "http://", "//")):
            fail(f"unexpected remote browser resource: {resource}")
        if not (SITE / resource).is_file():
            fail(f"local browser resource is missing: {resource}")

    for link in parser.links:
        if link.startswith(("https://", "http://", "mailto:", "tel:")) or link.startswith("#"):
            continue
        local_path = link.split("?", 1)[0].split("#", 1)[0]
        if local_path and not (SITE / local_path).is_file():
            fail(f"local link target is missing: {link}")

    print(
        "PASS: static website integrity, release-status copy, responsive/accessibility markers, "
        f"{len(parser.links)} links, and the disabled APK placeholder ({len(parser.ids)} section IDs)."
    )


if __name__ == "__main__":
    main()
