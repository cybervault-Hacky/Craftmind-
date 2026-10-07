# CraftMind static website

This is a dependency-free static multi-page site. The Phase 15 page set is:

| Page | File |
| --- | --- |
| Home | `index.html` |
| How It Works | `how-it-works.html` |
| Features | `features.html` |
| Download | `download.html` |
| About | `about.html` |
| FAQ / Help | `faq.html` |
| Privacy | `privacy.html` |
| Terms | `terms.html` |

Shared assets are `styles.css` (base design language plus a "Phase 15 multi-page components" block) and
`favicon.svg`. Every page carries the same navigation (`Home / How it works / Features / Download / About / FAQ`) and
the same footer (`Privacy`, `Terms`, `Source`). The site makes no browser-side requests, uses no hosted fonts or
analytics, needs no build step, and has no remote or fabricated resources.

## Local preview and check

From the repository root:

```bash
python3 scripts/check_website.py
python3 -m http.server 4173 --bind 0.0.0.0 --directory website
```

Open `http://localhost:4173` on the machine running the local server. In Arena, use the live preview started by the agent instead. This local URL is not a public deployment.

## GitHub Pages deployment

A manual-only workflow template is at `website/github-pages-workflow.yml.example`. It is intentionally not installed under `.github/workflows` in this branch; publishing a GitHub Actions workflow requires repository workflow-write permission, which this session's GitHub connection did not have.

After release review, a maintainer with that permission can install and run it:

```bash
mkdir -p .github/workflows
cp website/github-pages-workflow.yml.example .github/workflows/publish-website.yml
```

Then configure **Settings → Pages → Build and deployment → Source: GitHub Actions** and manually run **Actions → Publish CraftMind website**. The workflow is manual-only and does not deploy on pushes. This branch has not enabled or deployed GitHub Pages.

If Pages is enabled for this repository, the expected project URL is:

`https://cybervault-hacky.github.io/Craftmind-/`

That URL is a deployment target, not a claim that Pages is live. No custom domain is configured. After a successful workflow run, use the URL reported by GitHub Actions.

## Download placeholder

`download.html` owns the **single** disabled `DOWNLOAD APK` control in the site; the hero link on the home page
scrolls to it. No signed APK exists yet, so the control stays visibly unavailable and the page says the release has
not yet been built, installed, or published.

Do not replace it with a fabricated APK or a `releases/latest/download` URL, and do not make GitHub the primary
download experience. After a verified `v1.0.0` release exists (signed, checked with `scripts/verify-release-apk.sh`),
publish the matching SHA-256 on the download page, enable the control, and rerun `python3 scripts/check_website.py`
before manually deploying.

## Adding a page later

Add the file, add it to the shared navigation and footer of every existing page, update the page list in
`scripts/check_website.py` (page set, navigation labels, and any phrase checks), then rerun the check. New topics get
a new page — never an appendix on the home page, and never a placeholder advertising a feature that does not exist.
