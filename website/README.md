# CraftMind static website

This is a dependency-free static site: `index.html`, `styles.css`, and `favicon.svg`. It makes no browser-side requests, uses no hosted fonts or analytics, and needs no build step.

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

The `DOWNLOAD APK` hero link currently scrolls to a disabled button because no signed APK exists. Do not replace it with a fabricated or `latest/download` URL. After a verified `v1.0.0` GitHub release exists, link directly to its actual APK asset, publish the matching SHA-256, and rerun the static website check before manually deploying.
