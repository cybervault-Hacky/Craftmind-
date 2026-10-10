# CraftMind static website

A dependency-free static multi-page site. Phase 15 built the eight-page product site; Phase 21 grew it into the
marketplace, membership, creator, and account interface foundation — **interface only**. There is no marketplace,
listing, subscription, billing, checkout, or payout service behind any of these pages, and every page says so.

## Page set

| Section | Pages |
| --- | --- |
| Public (Phase 15, on the shared Phase 21 shell) | `index.html`, `how-it-works.html`, `features.html`, `download.html`, `about.html`, `faq.html`, `privacy.html`, `terms.html` |
| Marketplace | `marketplace/index.html` (search, categories, edition filter, sort, featured, saved, creator highlights), `marketplace/build.html` (build detail: preview area, description, compatibility, instructions, creator, reviews, related, purchase state) |
| Membership | `membership/index.html` (FREE / PRO / CREATOR / SERVER, monthly–yearly toggle, comparison table, FAQ, billing explanation) |
| Creators | `creators/index.html` (directory), `creators/profile.html` (public creator profile) |
| Account | `account/index.html`, `account/profile.html`, `account/security.html`, `account/sessions.html`, `account/purchases.html`, `account/saved.html`, `account/membership.html` |
| Account access (Phase 24/38) | `signin.html` (sign in, create account), `onboarding/index.html`, `onboarding/buyer.html`, `onboarding/seller.html`, `recovery.html` (enter the one-time recovery code and a new password, or request a fresh code) |
| Creator studio | `creator/index.html` (dashboard), `creator/listings/index.html`, `creator/listings/new.html`, `creator/listings/edit.html`, `creator/orders/index.html`, `creator/earnings/index.html`, `creator/reviews/index.html`, `creator/analytics/index.html`, `creator/profile/index.html`, `creator/settings/index.html` |

Forty-two pages in total, and `python3 scripts/check_website.py` is the authority on that number — it fails if a page exists that the
information architecture does not declare, or the reverse. The table above names the Phase 15/21 foundation; later phases added the
hire, order, onboarding, sign-in, and recovery screens listed in its rows. The developer control plane is **not** part of this site: it lives at `backend/public/developer.html`,
is authorised and rate-limited by the backend, and is never linked from a public page.

## Shared contract

Every page carries the same header navigation — `Home / Marketplace / Membership / Creators / How it works / Features /
Download / About / FAQ` plus one `Get the app` call to action — and the same footer link block. The account and creator
sections add a contextual shell (`data-context-line` plus a section navigation whose current entry is marked without
JavaScript). Pages are `data-page`-keyed, and the body key selects the controller in `assets/site.js`.

`styles.css` holds the base design language plus a "Phase 21 — marketplace, creator, membership, and account
components" block. The modules under `assets/` are:

| Module | Responsibility |
| --- | --- |
| `site.js` | Entry point: enhanced navigation, contextual chrome, and controller dispatch for the current page. |
| `state.js` | The eight-state system (`loading`, `empty`, `populated`, `error`, `unauthorized`, `unavailable`, `disabled`, `success`), the polite live region, and escaping helpers. |
| `adapters.js` | The data boundary. Every marketplace, creator, order, review, analytics, membership, entitlement, and payment adapter is registered but unconfigured and answers with a typed `unavailable` result. The account adapter implements only the endpoints that already exist in `backend/`, and only after a deployer configures an HTTPS origin. |
| `compatibility.js` | Read-only mirror of the app's Minecraft compatibility domain (editions, loaders, release channels, the one registered and certified runtime profile, and BuildPlan limits). |
| `preview-catalog.js` | Clearly named sample records used **only** when `?preview=1` is requested. Nothing else renders them. |
| `components.js` | Shared markup builders: listing cards, placeholder covers, creator chips, metrics, data tables, price rows, media tiles, ratings. |
| `marketplace.js`, `membership.js`, `creator-studio.js`, `account.js` | The page controllers for their sections. |
| `download.js` | The download page's live release check: renders a verified release asset or keeps the control disabled. Performs no request of its own.
| `recovery.js` | Phase 38: the password-recovery screen — request a one-time code and consume it, signed out. Reads the code only from the form, never from the URL, and never from or into browser storage. |

Reviewing the interface with sample data: add `?preview=1` to a marketplace, creators, or creator-studio address. Preview
mode is labelled in the interface, purchase and publish controls stay disabled, and sample records are never presented as
inventory.

## Local preview and check

From the repository root:

```bash
python3 scripts/check_website.py
python3 -m http.server 4173 --bind 0.0.0.0 --directory website
```

Open `http://localhost:4173` on the machine running the local server. In Arena, use the live preview started by the agent
instead. This local URL is not a public deployment.

## What the checker enforces

`scripts/check_website.py` checks the whole information architecture rather than a page list: the exact page set, one `h1`
and one main landmark per page, skip links, viewport metadata, `data-page` controller keys, the region hooks each
controller needs, the nine-link navigation and footer contract, the contextual section shells with exactly one current
entry, and resolution of every internal link, anchor, and local resource. It also enforces the honesty invariants — no
remote resource, no payment provider, no browser storage, no secret material, no hardcoded API origin, `fetch` only in
the adapter module, sample data gated behind an explicit preview request, disabled purchase and plan controls, an
undefined (never hardcoded) commission figure, and the phrases that state what is not implemented — plus exactly one
disabled `DOWNLOAD APK` control site-wide and no fabricated APK link.

## Hosting

Netlify is the deployment target, configured by `netlify.toml` at the repository root: publish directory `website`,
no build command, four response headers, no SPA fallback, no environment variables. `docs/production-operations.md`
("Netlify and the APK download path") is the procedure, including the phone-only steps. **Nothing is deployed and no
public URL is verified from here** — publishing needs the owner's Netlify account.

A manual-only GitHub Pages template also sits at `website/github-pages-workflow.yml.example`, still uninstalled: it
predates the Netlify decision and is kept because it is a working alternative for someone who would rather not open an
account. Neither workflow file can be installed by an agent in this workspace: GitHub refuses every workflow write
from its token (see the repository README), so installing any of them is an owner action.

`check_deployment_readiness.py` re-verifies the parts of this that are checkable statically: that `netlify.toml`
publishes `website` and nothing else, and that the file declares no build step and no environment value.

## The download control, and how it is allowed to become a link

`download.html` owns the **single** `DOWNLOAD APK` control in the site; the hero link on the home page scrolls to it. It
ships **disabled**, because no release exists yet: the repository has never published one, and no APK has been built in
any environment.

`assets/download.js` resolves the control at page load instead of encoding a URL in the markup. The page names the
source repository (`data-release-repository`), `assets/adapters.js` asks that repository's newest published release for
an Android package — from a host pinned in the adapter, never from anything a visitor can configure — and the control
becomes a link only when such an asset really exists, showing its name, size, publish time, and the SHA-256 published
with it. Every other answer re-disables it and states the reason: no release, a release with no APK, a rate limit, an unreachable host, a page naming no repository — and a release carrying **more than one** package, which the page lists by name without ever choosing one, because picking the first asset is how a debug build gets offered as "the release". So the button can never outlive the artifact: remove the release and
the page goes back to "not available" on the next load.

Do not shorten this with a hardcoded `releases/latest/download` link or a committed `.apk` path: `check_website.py`
fails the site on a fabricated APK link, on a release check that performs its own request, on an adapter that drops
its host pin, and on a control that can be enabled without a verified asset. It also still requires exactly one
`DOWNLOAD APK` button site-wide, disabled, on this page — the runtime link is an `<a>`, not that button.

A real browser has never run this page: there is no browser in the environment and no Playwright dependency to install
(blocker B3). What exists instead is `backend/test/website-download-page.test.js` — a module test against a fabricated
DOM that proves the state transitions, and `backend/test/website-release-channel.test.js`, which drives the real adapter
through every answer the release service can give. Neither is a browser test and neither is claimed as one.

## Adding a page later

Add the file, add it to the shared navigation and footer of every existing page through the generator in the phase that
owns it, add the route (page key, owning section, region hooks) to `scripts/check_website.py`, then rerun the check. New
topics get a new page — never an appendix on the home page, and never a placeholder advertising a feature that does not
exist. A page that cannot show real data must render an honest state from `state.js` instead of sample content.
