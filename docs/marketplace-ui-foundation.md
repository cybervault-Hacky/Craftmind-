# Marketplace, creator, membership, and account UI foundation (Phase 21)

Phase 21 builds the **interface and the state architecture** for the marketplace, creator studio, membership, and account
center before any service exists behind them. It adds no marketplace backend, no listing storage, no search index, no
checkout, no subscription, no commission, no payout, no entitlement, and no payment provider — not Razorpay, Stripe,
PayPal, or anything else. Nothing here can be bought, sold, published, subscribed to, or paid out, and every page says
so in plain language.

The design goal is the opposite of a demo: the interface should be complete enough to review, test, and extend, while
being incapable of pretending a service exists.

- [1. What is implemented](#1-what-is-implemented)
- [2. What is deliberately not implemented](#2-what-is-deliberately-not-implemented)
- [3. Information architecture](#3-information-architecture)
- [4. The state system](#4-the-state-system)
- [5. The data boundary (adapters)](#5-the-data-boundary-adapters)
- [6. Honesty rules](#6-honesty-rules)
- [7. Creator studio: the create-listing wizard](#7-creator-studio-the-create-listing-wizard)
- [8. Minecraft compatibility reuse](#8-minecraft-compatibility-reuse)
- [9. Pricing and the commission stance](#9-pricing-and-the-commission-stance)
- [10. Membership](#10-membership)
- [11. Account center](#11-account-center)
- [12. Accessibility, responsive behaviour, and motion](#12-accessibility-responsive-behaviour-and-motion)
- [13. Security and privacy boundaries](#13-security-and-privacy-boundaries)
- [14. Verification](#14-verification)
- [15. Extension points for later phases](#15-extension-points-for-later-phases)

---

## 1. What is implemented

| Area | Implemented in Phase 21 |
| --- | --- |
| Marketplace home | Search, category filter, edition filter, free-only filter, sorting, featured strip, saved-items panel, creator highlights, reset, status line with a live count, listing cards, empty/no-match states |
| Build detail | Title, creator chip, abstract placeholder cover and screenshot slots, edition/version/loader, category, tags, difficulty, build type, BuildPlan limits, description, instructions, creator notes, licence, reviews region, related builds, save control, and an honest purchase state |
| Creators | Directory layout, public creator profile layout (avatar, name, bio placeholder, stat rows, listings, reviews, rating state, categories, joined date slot, follow control) |
| Creator studio | Dashboard with zero-metrics and explanatory panels; listings tabs (All / Draft / Published / Unpublished) with UI-only actions; orders view that renders buyer-safe fields only; earnings and analytics placeholders; reviews with no fabricated rows; public-profile and settings layouts |
| Create-listing flow | Seven-step wizard (Details → Media → Minecraft compatibility → Pricing → Instructions → Preview → Publish) with bounded lengths, per-field errors, character counters, an error summary announced to assistive technology, a compatibility verdict, an earnings-preview layout, a publish preview, and an honest blocked publish step |
| Membership | FREE / PRO / CREATOR / SERVER cards, monthly–yearly segmented toggle, comparison table, billing explanation, FAQ, current-plan state, and cancellation placeholder |
| Account center | Overview, profile, security, sessions, purchases, saved items, and membership, reusing the Phase 17/18 account service contract and rendering safe session metadata only |
| Shared system | Eight-state renderer, live region, escaping helpers, component markup builders, shared stylesheet block, mobile navigation, contextual section shells |

The Android application is not part of this phase. Its navigation remains Home / Builds / Minecraft / Settings, asserted
by `MainDestinationTest`, and no Kotlin source changed. The marketplace interface belongs to the website.

## 2. What is deliberately not implemented

- No marketplace service, catalogue, search index, inventory, listing persistence, moderation, or media upload.
- No purchase, checkout, cart, order, invoice, tax, refund, or entitlement of any kind.
- No subscription, recurring billing, plan activation, plan change, or cancellation.
- No commission, fee schedule, payout, balance, or creator earning.
- No analytics collection, order history, sales figure, review, rating, follower count, or creator statistic.
- No permanent cloud storage for listings or media.
- No payment provider integration and no provider SDK anywhere in front-end source.

Consequently, no page can display a purchase, a payment confirmation, an activated subscription, a published listing, a
sale, a payout, a review, a rating, or a popularity figure. Where such data would appear, the interface renders an
explicit state, a dash, or an unavailable note instead.

## 3. Information architecture

| Section | Routes | Notes |
| --- | --- | --- |
| Public | `/`, `/marketplace`, `/membership`, `/creators`, `/how-it-works`, `/features`, `/download`, `/about`, `/faq`, `/privacy`, `/terms` | The nine-link navigation plus the `Get the app` call to action. Privacy and Terms are linked from the footer, not the primary navigation. |
| Account | `/account`, `/account/profile`, `/account/security`, `/account/sessions`, `/account/purchases`, `/account/saved`, `/account/membership` | Contextual shell with its own section navigation. The current entry is marked without JavaScript. |
| Creator studio | `/creator`, `/creator/listings`, `/creator/listings/new`, `/creator/listings/edit`, `/creator/orders`, `/creator/earnings`, `/creator/reviews`, `/creator/analytics`, `/creator/profile`, `/creator/settings` | Same shell pattern. The wizard lives at the two listing routes. |
| Developer | `/developer` (served by the backend) | Outside the website page set, backend-authorised and rate-limited, never linked from a public page. |

Navigation rules:

- The public navigation is identical on all thirty pages: nine links plus one call to action, with `aria-current="page"`
  on the owning section only.
- Account navigation appears contextually (the account shell and the footer), and creator navigation only where it is
  relevant to the reader.
- The developer surface is never exposed publicly. Hiding a link is not access control: authority is enforced by the
  backend, and the website simply does not advertise the route.

## 4. The state system

`assets/state.js` defines one vocabulary used by every controller:

| State | Meaning in this phase |
| --- | --- |
| `loading` | Work in progress. Rendered only when a request is genuinely in flight. |
| `empty` | The service answered, or would answer, with nothing to show. |
| `populated` | Real data is rendered. No page in this phase claims this for marketplace content. |
| `error` | Something failed and can be retried. |
| `unauthorized` | The reader is not signed in for an account-scoped surface. |
| `unavailable` | The capability does not exist yet. This is the default for every marketplace, creator, and membership surface. |
| `disabled` | The capability exists in the design but is turned off, with a stated reason. |
| `success` | A real action completed. No marketplace action can reach this state in this phase. |

Supporting helpers: `renderState`/`renderLoading`, `announce` with an always-present polite live region
(`#site-live-region`), `stateMarkup`, `escapeText`/`escapeAttribute`, `formatTimestamp` (em dash when unparsable), and
`orDash` (em dash for absent values). Tone is never the only signal: every state carries a text badge and an explanation,
and a success badge can never be produced by an unavailable render.

## 5. The data boundary (adapters)

`assets/adapters.js` is the only module in the site that performs a network request, and it does so only through the
account adapter, only against endpoints that already exist in `backend/`, and only after a deployer configures an HTTPS
origin. Everything else is registered and inert:

| Adapter | Methods registered (all return `unavailable` today) |
| --- | --- |
| `marketplace` | `searchListings`, `getListing`, `listCategories`, `listFeatured` |
| `creator` | `getCreatorProfile`, `listCreatorListings`, `saveProfile`, `follow` |
| `orders` | `listSalesOrders`, `listPurchases`, `getOrder` |
| `reviews` | `listReviews`, `getRatingSummary`, `submitReview` |
| `analytics` | `getCreatorAnalytics`, `getListingAnalytics` |
| `membership` | `getPlans`, `getCurrentMembership`, `requestPlanChange` |
| `entitlements` | `listEntitlements`, `hasEntitlement` |
| `payments` | `createCheckoutSession`, `getCheckoutStatus` |

Rules the code keeps:

- Every adapter returns a typed result (`ok`, `empty`, `unavailable`, `unauthorized`, `error`) with a stable, safe reason
  code. `unavailable` is the honest answer for an unimplemented service — never a fabricated success.
- No endpoint, base URL, key, or token is hardcoded. The account service origin is read from an optional
  `CRAFTMIND_SITE_CONFIG.accountServiceOrigin` and must be HTTPS; unconfigured means unconfigured.
- `describeIntegrationBoundary()` documents the boundary for reviewers, and `scripts/check_website.py` enforces the
  static half of it (no hardcoded origin, no secret, `fetch` only in this module, no second network API).

## 6. Honesty rules

These are enforced by the website checker and by the code, not by convention:

1. **No fabricated data.** No fake statistics, purchases, earnings, payment confirmations, reviews, listings,
   subscription activations, or APKs.
2. **Sample data is opt-in, named, and labelled.** `assets/preview-catalog.js` holds clearly named sample records
   (`DEMO_*`, `SAMPLE_*`), rendered only when the address carries `?preview=1`, always behind a "Preview data" badge and
   a banner stating that the catalogue is sample data, not inventory.
3. **Unavailable controls are disabled with a reason.** Purchase, download, follow, plan, publish, and save-draft
   controls cannot be activated, and each states why.
4. **Numbers that do not exist render as dashes or "Not defined yet".** Pricing, earnings, analytics, orders, reviews,
   and ratings never show a placeholder figure that could be mistaken for data.
5. **Preview mode never changes what a control claims to do.** A disabled control stays disabled in preview mode.
6. **Nothing is stored.** Saving a card keeps it in memory for the current page only. No `localStorage`,
   `sessionStorage`, `indexedDB`, cookie, or upload is used anywhere in front-end source.

## 7. Creator studio: the create-listing wizard

`assets/creator-studio.js` implements the flow:

| Step | Content | Validation |
| --- | --- | --- |
| 1. Details | Title, short description, full description, category, subcategory, tags, build type, difficulty | Bounded lengths (4–90, 20–240, 40–4000 characters), at most 10 tags of ≤24 characters, each selection from the declared vocabulary |
| 2. Media | Cover, screenshots, optional preview video slots with accepted formats, size limits, an upload adapter placeholder, and an explicit acknowledgement | The acknowledgement is required; nothing is uploaded and nothing is stored |
| 3. Minecraft compatibility | Edition, version, loader, loader version, release channel, compatibility notes | Edition/version/loader/release channel required; the loader list follows the edition; the verdict comes from the mirrored registry |
| 4. Pricing | Free or Paid, price, optional sale price, and the earnings preview | Paid requires a price greater than zero; a sale price must be lower than the price |
| 5. Instructions | Installation and use instructions, Minecraft requirements, known limitations, creator notes | Instructions required, bounded to 3000 characters; the optional sections are bounded to 1200 |
| 6. Preview | The listing exactly as it would appear, using the creator's own text, plus the compatibility verdict | None; the preview renders only what was entered |
| 7. Publish | An unavailable state explaining that publishing needs listing storage, media storage, moderation, and a marketplace service | Publish and save-draft are disabled; the step says the draft lives in the page only |

Design properties: a labelled progress element with `aria-valuenow`, a step list with `aria-current="step"`, character
counters bound to their fields, an error summary with `role="alert"` linking to each invalid field, `aria-invalid` on the
fields themselves, and no fake upload progress anywhere.

## 8. Minecraft compatibility reuse

`assets/compatibility.js` is a **read-only mirror** of the app's existing domain, so the creator form cannot offer a
value CraftMind does not recognize and the website cannot invent a second compatibility model:

- Editions: `java`, `bedrock`, `legacy` — with the status each one holds in the registry.
- Loaders: Fabric, Forge, NeoForge, Vanilla, Bedrock Native; the loader list is derived per edition.
- Release channels: `RELEASE`, `SNAPSHOT`, `BETA`, `ALPHA`, `LEGACY`, `UNKNOWN`.
- Registered profiles: one — `java-fabric-1.20.1` (Minecraft 1.20.1, Fabric 0.16.10, Fabric API 0.92.2+1.20.1, Java 17,
  bridge 1.2.0 / protocol 2, BuildPlan v2), `SUPPORTED` and `CERTIFIED`.
- BuildPlan limits: 4096 operations, 96×64×96 blocks.

A target inside the registry reads `SUPPORTED · CERTIFIED` with its exact runtime; anything else reads the registry's
real status (`EXPERIMENTAL`, `UNSUPPORTED`, `UNKNOWN`) with certification `NOT_PERFORMED` and a sentence explaining that
CraftMind builds only a registered, certified target.

## 9. Pricing and the commission stance

The pricing step renders the three figures a future creator needs — `YOUR PRICE`, `CRAFTMIND FEE`, `ESTIMATED EARNINGS` —
and then refuses to invent two of them:

- `YOUR PRICE` shows what the creator typed, or an em dash.
- `CRAFTMIND FEE` reads **"Not defined yet"**.
- `ESTIMATED EARNINGS` reads **"Unavailable"**, with the note that no commission rate exists in this phase.

A percentage figure appears only inside a collapsed `<details>` example, whose disclosure states that the percentage is
an arbitrary illustration of the layout, not the CraftMind fee, and that marketplace economics are undefined. No
production commission rate is hardcoded anywhere, and no payout logic exists.

## 10. Membership

`assets/membership.js` defines four plans — FREE, PRO, CREATOR, SERVER — with:

- FREE marked as the experience available today, and PRO / CREATOR / SERVER marked "Planned — not purchasable".
- Every price slot reading "Not priced yet" (or "No price applies" for FREE) for both periods, because no price list
  exists.
- A monthly–yearly segmented control that is real interface state (`aria-pressed`, live period note) and changes no
  price, because no annual price or discount is defined.
- A comparison table whose planned rows say "Planned" and whose billing row says "Not implemented".
- Every plan action disabled: "Current free experience" or "Upgrade · not available yet".
- A billing explanation and FAQ that state billing, invoicing, and renewals do not exist, and a cancellation placeholder
  that explains there is nothing to cancel.

## 11. Account center

The account pages reuse the Phase 17/18 architecture and the existing, already-implemented account service contract —
nothing new is invented:

- **Overview / profile** — signed-in state only from the adapter; no session id, internal user id, token, or password is
  ever rendered.
- **Security** — the capabilities the service actually implements (email verification, password reset request and
  confirm, password change) plus the states each real endpoint returns. When no service is configured, requesting a
  reset states that no reset can be requested.
- **Sessions** — safe metadata only: a device label, a last-used timestamp, and whether the session is the current one,
  with per-session revoke. No location, no raw token, no identifier.
- **Purchases** — an honest empty state: purchases arrive with the marketplace and payment services, neither of which is
  implemented, so no purchase history exists.
- **Saved** — items saved on the marketplace in this visit, in memory only.
- **Membership** — the membership state as it applies to an account, with dashes where a real plan value would be.

Every controller requires a usable adapter and renders `unauthorized` or `unavailable` otherwise; the pages never assume
the reader is signed in and never treat a hidden link as authorisation.

## 12. Accessibility, responsive behaviour, and motion

- **Structure** — one `h1` per page, one main landmark, a skip link on every page, labelled navigation regions,
  descriptive page titles, and semantic lists/tables/fieldsets.
- **Keyboard and focus** — every control is reachable, focus is never trapped, the mobile navigation disclosure is a real
  button with `aria-expanded`/`aria-controls`, and focus is not moved without a reason.
- **Forms** — labels bound to inputs, hints via `aria-describedby`, character counters, `role="alert"` error summaries
  that focus the first problem, `aria-invalid` on the offending field, and field-level messages rather than one generic
  complaint.
- **Status** — a polite live region announces results; state is conveyed by a badge and text as well as tone.
- **Responsive** — desktop, tablet, and mobile breakpoints at 1100 / 940 / 680 / 390 px; the creator shell collapses into
  a single column; data tables become labelled cards below 680 px using `data-label`; touch targets stay at least 44 px.
- **Motion** — `prefers-reduced-motion: reduce` collapses transitions to none while state changes still render.

## 13. Security and privacy boundaries

- Front-end source contains no secret, key, token, or credential of any kind, and the checker fails on secret-shaped
  patterns.
- No browser storage, no cookie, no analytics, no third-party script, font, or CDN request: the site is still
  dependency-free and offline-capable apart from the optional account service.
- All rendered values are escaped by the component builders; no untrusted string is injected as HTML.
- The account adapter only targets endpoints that exist, requires an explicit HTTPS origin, and keeps any credential in
  memory for the page's lifetime only.
- The Phase 19/20 developer control plane, security center, incidents, audit trail, and Developer AI boundaries are
  untouched; Phase 20's protections (authorisation, audit, sessions, rate limiting, secret redaction) are unchanged.

## 14. Verification

| Check | Result |
| --- | --- |
| `python3 scripts/check_website.py` — information architecture, navigation, section shells, region hooks, accessibility markers, honesty invariants, download policy | PASS (30 pages, 884 links) |
| Local jsdom harness (renders each page and drives the controllers: honest states, preview gating, search/filter/sort, save-in-memory, plan toggle, wizard validation and step order, escaping, and the disabled-control rules) | PASS (72/72 assertions) — harness is not committed; no browser is installed in this environment |
| Checker mutation test (10 deliberate breakages must be rejected, e.g. fabricated buy button, deleted route, hardcoded API origin, payment provider named, browser storage added) | PASS (10/10 rejected) |
| `cd backend && npm test` — the account service and the Phase 20 security layer are unaffected | PASS (98/98 across 10 suites) |
| `python3 scripts/check_release_config.py` | PASS |
| `node --check` on every `website/assets/*.js` module | PASS (10/10) |
| `git diff --check`, secret scan, no APK/keystore/build output tracked | PASS |
| Android build and `MainDestinationTest` | **NOT RUN** — no Android SDK or Gradle distribution is available in this environment. No Kotlin source changed in this phase, and the four destinations are unchanged in source. |

No live service, deployment, payment provider sandbox, or browser-based end-to-end test is claimed.

## 15. Extension points for later phases

- **Marketplace service** — implement `adapters.js` methods behind `configure()`; the pages switch from honest
  unavailable states to real content without markup changes.
- **Media storage** — the media step already models slots, accepted formats, size limits, and an upload adapter placeholder;
  a later phase supplies quarantine, validation, and permanent storage.
- **Commerce** — `marketplace`, `orders`, `membership`, `entitlements`, and `payments` adapters are named and inert so a
  future checkout, commission, and payout design lands in one boundary rather than across pages.
- **Creator service** — profiles, follows, and creator-scoped listings are registered but unconfigured; nothing in the
  interface assumes they exist.
- **Statistics** — analytics and earnings views are laid out with explicit dash placeholders so a real analytics service
  replaces values, not layouts.

The rule for every later phase: extend the adapters and the state vocabulary, and keep the honesty rules of §6 intact. A
page must never show a figure, a purchase, a rating, or a payout that a service did not actually return.
