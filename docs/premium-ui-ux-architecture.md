# CraftMind premium UI/UX and website architecture (Phase 15)

This document is the reference for the interface work delivered in Phase 15: the design system, the Android
navigation model, every screen's structure, the accessibility and responsive rules, the website architecture, and the
rules later phases must follow when they add UI.

The design system described here is **permanent**. It is not a Phase 15 restyle that later phases may replace: the
tokens, components, states, and accessibility floors are the contract, and new work extends them instead of
introducing parallel styles. `README.md` and `docs/universal-minecraft-compatibility.md` remain the references for
the feature and certification behaviour; this document only covers presentation.

Scope note: nothing in Phase 15 changed what the app does. Providers, API keys, BuildPlan generation, image and URL
references, refinement, history, bridge pairing, runtime detection, adapter selection, compatibility, certification,
execution authorisation, build execution, cancellation, error surfacing, and theme persistence are the same
implementations as before, now presented through the design system.

---

## 1. UI architecture

Presentation is layered, and each layer may only depend on the ones below it.

| Layer | Location | Responsibility |
| --- | --- | --- |
| Tokens | `app/src/main/java/com/craftmind/app/designsystem/CraftMind*Tokens.kt`, `CraftMindContrast.kt` | Plain Kotlin values: colour roles, spacing, radius, elevation, border, icon size, type scale, motion, contrast maths. **No `androidx` import anywhere in this layer**, so the JVM test suite can verify it. |
| Theme | `designsystem/CraftMindTheme.kt` | The single place where tokens become Compose values: `CraftMindLayout` (Dp), `CraftMindShapes` (Shape), `CraftMindType` (TextStyle), `MaterialTheme.craftMindColors`, `MaterialTheme.craftMindMotion`. |
| Components | `designsystem/CraftMindSurfaces.kt`, `CraftMindButtons.kt`, `CraftMindBadges.kt`, `CraftMindStates.kt` | Shared building blocks that already enforce spacing, states, touch targets, and semantics. |
| Screens | `presentation/home`, `presentation/builds`, `presentation/minecraft`, `presentation/settings`, `presentation/about`, `presentation/app`, `MainActivity.kt` | Compose screens that arrange components and render **derived state**; they never style a widget themselves. |

Rules that make the system hold:

1. **No screen-local visual literals.** A screen cannot write `Color(0x…)` or `RoundedCornerShape(14.dp)`. This is
   enforced by `CraftMindDesignSystemSourceScanTest`, which scans every production Kotlin source outside
   `designsystem/` and fails the build if a raw colour or raw corner radius reappears.
2. **Material 3 is only a substrate.** Text scaling, semantics, focus handling, and platform behaviour come from
   Material 3, but its colour scheme, typography, and shape slots are overwritten wholesale by CraftMind tokens.
3. **State is derived, then rendered.** Screens receive a state object and a list of events. Anything that needs
   interpretation — a status sentence, a badge tone, an empty/error decision, an action's availability — lives in a
   pure function or a `data class` derivation that the JVM tests exercise
   (`BuildGenerationPhase`, `HomeMessages`, `BuildRecordSummary`, `MinecraftConnectionSummary`).
4. **Real state only.** No screen fabricates progress, counts, timings, or success. A status line is renderable only
   if the underlying value it describes exists.
5. **One primary action per screen.** Every screen has at most one filled, high-emphasis button; everything else is
   secondary, tertiary, or quiet.

---

## 2. Design system

### 2.1 Colour

`CraftMindColorTokens` declares 41 named roles (background/surface families, brand primary, sand secondary, teal
tertiary, outline/divider, and error/success/warning/info each with `on…` and `…Container`/`on…Container` pairs plus a
scrim) as ARGB `Long` values, in a light (`LightCraftMindColors`) and a dark (`DarkCraftMindColors`) palette.

Measured WCAG 2.1 contrast of the declared text pairings (verified by `CraftMindDesignTokensTest`, which recomputes
them from the tokens):

| Pairing class | Light | Dark | Floor |
| --- | --- | --- | --- |
| Primary reading pairings (`onSurface`/`surface`, `onBackground`/`background`, `onSurfaceSubtle`, `onSurfaceVariant`/`surfaceVariant`, `onSurfaceMuted`/`surface`) | 5.86 – 17.66 | 7.57 – 14.97 | 4.5 (AA) |
| Accent fills (`onPrimary`/`primary`, `onSecondary`, `onTertiary`) | 6.49 – 8.67 | 7.80 – 8.18 | 4.5 |
| Status fills (`onError`/`error`, `onSuccess`, `onWarning`, `onInfo`) | 6.66 – 7.82 | 7.78 – 9.72 | 4.5 |
| Container/label pairs (`on…Container` on `…Container`) | 11.54 – 12.97 | 7.15 – 10.28 | 4.5 |
| `outline` against `surface` (non-text hairlines) | 3.91 | 5.54 | 3.0 |

`CraftMindTone` (`NEUTRAL`, `BRAND`, `POSITIVE`, `CAUTION`, `NEGATIVE`, `INFORMATIVE`) maps meaning to a triple of
fill / readable content / accent through `toneColors`. A tone is never decorative: `POSITIVE` is reserved for real
success, compatible, or certified state, `NEGATIVE` for real errors and blocked state, `CAUTION` for experimental or
partially supported state.

The palette keeps CraftMind's identity — a deep forest-green brand colour and a warm sand accent on a cool off-white
canvas — and reads as an AI product first. Minecraft influence appears only as structural geometry (radii, spacing,
hairlines), never as textures, pixel art, logos, fonts, or glow.

### 2.2 Typography

`CraftMindTypeScale` defines 12 roles and nothing else: `DISPLAY` 34/40, `HEADLINE` 26/33, `TITLE_LARGE` 20/27,
`TITLE_MEDIUM` 17/24, `TITLE_SMALL` 15/21, `BODY_LARGE` 16/25, `BODY_MEDIUM` 14/21, `BODY_SMALL` 13/19,
`LABEL_LARGE` 14/20, `LABEL_MEDIUM` 13/18, `LABEL_SMALL` 12/16, `EYEBROW` 12/16 uppercase with tracking. One system
sans-serif family is used throughout; hierarchy comes from size, weight, colour role, and spacing rather than from
many unrelated type sizes. `MINIMUM_READABLE_SP = 12f` is a hard floor — the EYEBROW role was raised to 12sp rather
than keeping a finer 11sp label.

### 2.3 Spacing, radius, elevation, borders, icons, touch targets

* **Spacing** (`CraftMindSpacing`): `XXS` 2, `XS` 4, `SM` 8, `MD` 12, `LG` 16, `XL` 24, `XXL` 32, `XXXL` 48, plus
  semantic values `CARD_INSET` 20, `SCREEN_MARGIN` 20, `SCREEN_MARGIN_WIDE` 32, `SCREEN_MARGIN_VERTICAL` 24.
* **Radius** (`CraftMindRadius`): 0, 6 (`XS`), 10 (`SM`), 14 (`MD`), 18 (`LG`), 24 (`XL`), and `PILL` 999 used only by
  status badges. The language is "soft rectangle": nothing is square-cut, nothing but a badge is a pill.
* **Elevation** (`CraftMindElevation`): 0, 1, 2, 3, 8. Surfaces are separated mainly by lightness and a hairline, so
  only overlays float.
* **Borders** (`CraftMindBorder`): 1dp hairline, 2dp emphasis.
* **Icons** (`CraftMindIconSize`): 14, 16, 20, 24, 32, 48 — so optical weight stays consistent. Only the Material
  icons core set is used; no custom pictograms, no emoji.
* **Touch targets** (`CraftMindSizing`): `MIN_TOUCH_TARGET` 48dp; `CONTROL_HEIGHT_MD` 48; `CONTROL_HEIGHT_LG` 56 for
  the single screen-level call to action; compact chips are 40dp visual with `COMPACT_TOUCH_PADDING` 4dp above and
  below, so their touch target is still ≥ 48dp.

### 2.4 Motion

`CraftMindMotionTokens`: `INSTANT` 0, `FAST` 120ms (selection, chip/toggle state, focus rings), `BASE` 200ms
(expand/collapse, card appearance), `SLOW` 280ms (dialog and sheet transitions), `MAXIMUM` 320ms, and
`INDETERMINATE_PERIOD` 1600ms for a genuinely in-flight request. Motion is a state-transition cue: it never
substitutes for progress, never loops, and never runs behind content. When the platform reports reduced motion,
`CraftMindMotionTokens.scaleFor(reducedMotion = true)` collapses every duration to `INSTANT` and the state change
still happens.

### 2.5 Component inventory

| Component | File | Notes |
| --- | --- | --- |
| `CraftMindScreen`, `CraftMindScreenHeader`, `CraftMindEyebrow` | `CraftMindSurfaces.kt` | Screen scaffolding: margins, max width via breakpoints, title + purpose subtitle + eyebrow, consistent vertical rhythm. |
| `CraftMindCard`, `CraftMindInsetPanel`, `CraftMindSectionHeader`, `CraftMindKeyValueRow`, `CraftMindDivider`, `CraftMindBrandMark` | `CraftMindSurfaces.kt` | Surface language and label/value presentation. |
| `CraftMindPrimaryButton`, `CraftMindSecondaryButton`, `CraftMindTertiaryButton`, `CraftMindDestructiveButton`, `CraftMindIconButton` | `CraftMindButtons.kt` | The only button styles in the app; heights, radii, focus rings, disabled reasons, and loading state come from tokens. |
| `CraftMindStatusBadge`, `CraftMindMetaChip`, `CraftMindSelectableChip`, `CraftMindStatusDot` | `CraftMindBadges.kt` | Toned status and metadata; the dot is the only place a tone is used without text. |
| `CraftMindLoadingState`, `CraftMindEmptyState`, `CraftMindErrorState`, `CraftMindUnavailableState`, `CraftMindSuccessState`, `CraftMindNotice`, `CraftMindExpandableSection`, `CraftMindDetailLines` | `CraftMindStates.kt` | The standard non-happy states plus disclosure of technical detail. `CraftMindExpandableSection` is how raw detail (diagnostics, payloads, runtime details) is shown — collapsed by default, never as the primary presentation. |

Every interactive component implements the same state set: enabled, focused, pressed, disabled, loading, selected
where applicable, and error/advisory where applicable. Disabled state is always accompanied by a sentence saying why
the action is unavailable; a context-free greyed-out button is not allowed.

---

## 3. Android navigation

`MainDestination` is the whole navigation model: exactly four destinations, in a fixed order that
`MainDestinationTest` asserts.

| Order | Route | Label | Purpose (rendered as the screen subtitle) |
| --- | --- | --- | --- |
| 1 | `home` | Home | Describe a build, add one optional reference, and generate a validated plan. |
| 2 | `builds` | Builds | Every plan you have accepted on this device, with its saved versions. |
| 3 | `minecraft` | Minecraft | Pair a bridge, see the detected runtime, and find out whether building is available. |
| 4 | `settings` | Settings | Appearance, AI providers, Minecraft, data, and about CraftMind. |

* **Home is primary**: it is where the app opens, where Back returns, and the only destination with a filled
  action of its own.
* **Responsive shell**: `CraftMindApp` shows a bottom `NavigationBar` below 840dp of available width and a
  `NavigationRail` at or above `CraftMindLayout.mediumBreakpoint` (840dp). The destination list is identical in both.
* **Predictable Back**: a single ordered `BackHandler` in `CraftMindApp` closes transient overlays first (dialogs, the
  review sheet, the About overlay) and otherwise returns to Home; it never exits the app from a secondary
  destination, and never leaves an overlay stacked on an invisible screen.
* **No extra destinations.** About is an overlay opened from Settings (with Settings closed first so Back cannot
  return to a hidden screen). Privacy, terms, licences, and storage details live inside Settings. Later phases add
  screens *inside* these destinations rather than adding navigation entries, unless a phase brief says otherwise.
* **Nothing is placeholdered.** There are no "coming soon" destinations, no disabled nav items, and no fake
  marketplace or account entry.

---

## 4. Screen specifications

### 4.1 Home — the composer

Hierarchy, top to bottom: brand line (`CraftMind · AI Minecraft Builder`) → the question *"What do you want to
build?"* → the prompt field (`Describe your build…`) → two optional reference actions (`Image`, `Reference URL`) →
the single primary action (`Generate Build`).

* The composer exposes the app's existing capabilities and nothing else: text prompt, one image reference, one
  supported URL/reference. Advanced options are progressively disclosed — they appear only when they are relevant,
  and never as a wall of controls.
* Provider, key, and model configuration is **not** on Home; it stays in Settings, and Home shows only a compact
  setup status line when something is missing (`homeSetupStatus`), with the action that fixes it.
* While generating, Home renders only the stages that correspond to real `AiGenerationStage` values
  (`Preparing` → `Analyzing` → `Designing` → `Validating` → `Ready for review` → `Building`, plus failed and
  cancelled). There are no percentages, no synthetic timers, and no animation that pretends work is happening.
* Failure states are specific and human-readable: provider error, invalid AI response, validation failure, network
  failure, missing API key, unsupported input, and unavailable runtime each carry their own message and, where
  possible, a retry. Cancellation is a first-class action while a request is in flight.
* The prompt and reference state is preserved across an error so nothing the user typed is lost.

### 4.2 Build Review

The review surface answers "what will actually be built?" in descending order of importance: title, summary,
dimensions, components, materials, floors/sections, compatibility, source/reference, warnings, and validation status.
Everything is presented as layout, not as raw JSON; the raw plan is available through
`CraftMindExpandableSection` for anyone who wants it.

Actions are shown only when they are genuinely available: **Refine**, **Build in Minecraft**, **Save**, and
**Discard**. When building is unavailable, the screen shows the real reason (no bridge paired, runtime not detected,
runtime incompatible, execution not authorised, limits exceeded, experimental target) instead of a disabled button
with no explanation, plus a single tertiary action pointing at Minecraft.

### 4.3 Builds / History

Real local history only — no sample or demo builds. Each card is derived from the stored record plus its saved
versions and executions (`BuildRecordSummary`): title, when it was saved, the request source type (text, image, URL),
current status, compatibility, version/refinement count, and the latest execution outcome. The empty state says
"No builds yet" and offers "Create your first build".

### 4.4 Minecraft

`MinecraftConnectionSummary` collapses the real bridge/gate state into a stage and a set of actions. The stages are
`Loading profile`, `Not paired`, `Connecting`, `Authentication failed`, `Disconnected`, `Session expired`,
`Detecting runtime`, `Runtime changed`, `Runtime unavailable`, `Compatible`, `Experimental`, and `Incompatible` —
derived from the detector, selector, resolver, and certification gate, never from local guesses.

The screen shows useful runtime information (edition, version, loader, bridge version, protocol, limits) and hides
technical detail behind expandable sections. A certified Java target reads, for example,
`Minecraft Java · 1.20.1 · Fabric · Compatible · Certified production target`; an experimental target reads
`Experimental · Runtime certification not performed · Building unavailable`. Actions offered are only the ones that
make sense now: pair, reconnect, refresh detection, disconnect.

### 4.5 Settings

Five labelled groups, in this order: **Appearance** (theme, reduced-motion awareness), **AI providers** (provider,
model, and key management), **Minecraft** (bridge pairing, session controls, runtime compatibility),
**Data** (what is stored locally, privacy and storage), and **About**. Secrets are always masked; an API key is never
displayed, copied to the UI, or logged. Keystore-backed credential storage is unchanged by Phase 15.

### 4.6 About

CraftMind · AI Minecraft Builder; developer **Sarthak Bharambe**; the product's four real steps; this build's
application ID (`com.craftmind.app`), version and versionCode, minimum Android (8.0 / API 26), target API (35),
certified target (Minecraft Java 1.20.1 · Fabric Loader 0.16.10), BuildPlan schema (2), and bridge protocol (2);
technology acknowledgements and open-source attribution; and entries for Privacy, Terms, and the website. Terms is
explicitly marked as a draft that requires final legal review. The screen states plainly what this build is *not*:
no company, team, office, history, customers, investors, partnerships, awards, funding, or certifications are
claimed.

---

## 5. Accessibility

Accessibility is part of the component contract, not a later pass:

* **Contrast**: every text pairing used by a component clears WCAG AA (measured values in §2.1); non-text hairlines
  clear 3.0.
* **Touch targets**: ≥ 48dp everywhere, including icon-only buttons and compact chips.
* **Semantic labels and content descriptions**: every icon-only control and decorative-vs-meaningful image is
  labelled; status is conveyed by text as well as by tone, never by colour alone.
* **Keyboard and focus**: focus rings come from the design system, focus order follows reading order, and every action
  reachable by pointer is reachable by keyboard/d-pad.
* **Reduced motion**: the real system animator scale is observed (`ReducedMotionPreference`), and all durations
  collapse to zero while state changes still render.
* **Text scaling**: no fixed-height text containers that clip at larger font scales; the type scale is expressed in
  sp and layout uses wrapping rows.
* **Understandable errors**: messages name the problem ("The provider rejected the API key", "This runtime is
  experimental — building is unavailable") instead of surfacing exception text. Technical details are available, but
  behind a disclosure, and stack traces are never shown.

---

## 6. Responsive layout and performance

* **Android**: single column on phones; from 840dp the shell uses a navigation rail and screens may use two columns;
  content is capped at `SCREEN_MAX_WIDTH` 1040dp with a readable measure of `CONTENT_MAX_WIDTH` 720dp; dialogs and
  the review surface are capped at `OVERLAY_MAX_WIDTH` 900dp. Portrait is the design target, with larger phones and
  tablets accommodated by the same rules rather than a separate layout.
* **Website**: mobile-first CSS with breakpoints at 940px and 680px; the same visual language (spacing rhythm,
  radius, type hierarchy, quiet borders) at desktop widths.
* **Performance**: no large bundled assets, no animated backgrounds, no decorative animation loops, no added network
  calls. Screens render from already-derived state, list content is composed from stable keys, and the website is
  dependency-free static HTML/CSS with no JavaScript, no remote fonts, no analytics, and no CDN requests.

---

## 7. Website architecture

The website is a static multi-page site under `website/`, published from this repository.

| Page | File | Content |
| --- | --- | --- |
| Home | `index.html` | Branding, tagline *Describe it. Show it. Build it.*, the `DOWNLOAD APK` call to action, product overview, feature highlights, and the AI → BuildPlan → Minecraft explanation. |
| How It Works | `how-it-works.html` | The six real steps from description to in-game build. |
| Features | `features.html` | Only implemented behaviour. No marketplace, accounts, or pricing. |
| Download | `download.html` | The single (disabled) `DOWNLOAD APK` control, version facts, supported Android, install guidance, security/integrity, and direct-from-website distribution. |
| About | `about.html` | Honest description, mission, philosophy, high-level technology, and links. |
| FAQ / Help | `faq.html` | Answers limited to implemented behaviour. |
| Privacy | `privacy.html` | The repository's existing privacy documentation is the source of truth. |
| Terms | `terms.html` | Marked as a draft that requires final legal review. |

Shared elements: a skip link, one `h1` per page, navigation `Home / How it works / Features / Download / About /
FAQ` with `aria-current="page"` on the current page, and a footer linking Privacy, Terms, and the source repository.
`styles.css` holds the base design language plus a "Phase 15 multi-page components" block; there is no framework, no
build step, and no remote resource.

`scripts/check_website.py` enforces the architecture: page count, one `h1` per page, viewport and skip-link presence,
resolution of every internal anchor/link/resource, the exact navigation set with correct `aria-current`, exactly one
disabled `DOWNLOAD APK` control site-wide and it must be on `download.html`, absence of `href="….apk"` and of
`releases/latest/download`, the honest-copy phrases (including "not yet been built, installed, or published"),
download facts (1.0.0, 10000, Android 8.0+, SHA-256), the "final legal review" marker in Terms, the attribution and
no-false-claims copy in About, responsive and reduced-motion markers in CSS, and the absence of remote or fabricated
resources.

GitHub Pages is configured as a manual workflow *template* (`website/github-pages-workflow.yml.example`) only; the
expected project URL is `https://cybervault-hacky.github.io/Craftmind-/`, and that URL is a deployment target, not a
claim that the site is live.

### Current download behaviour

No signed APK exists yet, so **"Download APK" is visibly unavailable site-wide**: the hero call to action scrolls to
the disabled control on `download.html`, which explains that the release has not yet been built, installed, or
published. No APK, AAB, keystore, or key material is committed to this repository, and GitHub is not the primary
download experience. When a real, signed release exists, the control becomes active only after the release is
verified, its SHA-256 is published on the download page, and `scripts/check_website.py` passes with the updated
state.

---

## 8. Rules for future phases (16–20)

Phases 16–20 (accounts, pricing, marketplace cards/grid/detail, creator dashboards, launch) must reuse this system.
The rules are deliberately strict so the interface does not drift:

1. **Reuse before you create.** A new screen composes existing components. If a genuinely new component is needed, it
   is added to `designsystem/` with tokens and states, and it becomes available to every other screen.
2. **Extend the tokens, never the literals.** If a value is missing, add it to the relevant token object (and its
   test), then to the Compose converter. Never write a raw colour, radius, spacing, elevation, or font size in a
   screen — `CraftMindDesignSystemSourceScanTest` fails the build when you do.
3. **One button language.** Do not introduce a new button style; use primary/secondary/tertiary/destructive/icon, one
   filled action per screen.
4. **One navigation system.** Four destinations stay four destinations. New surfaces open as overlays, sheets, or
   screens inside an existing destination; adding a fifth tab requires an explicit brief change and an update to
   `MainDestinationTest`.
5. **One card language, one dialog language.** No duplicate card implementations, no inconsistent dialogs, no
   unrelated typography.
6. **States are mandatory.** Every new screen ships loading, empty, error, unavailable, and disabled states built
   from `CraftMindStates`, with real messages — never a blank screen and never a fabricated one.
7. **Motion only from tokens**, and only to express a state change.
8. **Accessibility floors are non-negotiable**: AA contrast, 48dp targets, labels, keyboard reachability, reduced
   motion, honest errors.
9. **The website grows by page, not by scroll.** A new topic gets a page added to the shared navigation, footer, and
   `scripts/check_website.py` — never one giant page, never a placeholder for an unimplemented feature.
10. **No fake content anywhere**: no sample builds, no invented metrics, no "coming soon" features presented as
    available, no fabricated screenshots or runtime results.
11. **No marketplace placeholders.** Accounts, pricing, marketplace cards/grid/detail, creator dashboards, and launch
    arrive only when those phases actually begin. Until then nothing in the app or the website references them: no
    "Marketplace", "Pricing", "Creator", "Servers", or "Account" navigation entry, no empty card grid, no stub screen,
    and no website page. A placeholder advertises a product that does not exist, which is worse than its absence.

Explicitly forbidden in every later phase: random colours; competing primary buttons; a second card system; a second
typography system; unrelated dialog styles; a separate navigation system; redesigning an existing screen without a
brief requirement; and marketplace placeholders before marketplace implementation.

When Phase 18 does begin, the marketplace is a **distinct product area** — its own screens, its own information
architecture, its own data — but it is built *with* this system, not beside it: the same tokens, buttons, cards,
surfaces, dialogs, typography, navigation principles, states, and responsive behaviour, extended by adding components
to `designsystem/` where genuinely new affordances (for example a creator card or a price badge) are required.

---

## 9. What verifies these claims

| Claim | Verified by | Result |
| --- | --- | --- |
| Colour pairings clear their contrast floor in both palettes; spacing/radius/elevation/motion scales stay monotonic and bounded; every interactive height reaches 48dp; no palette token is accidentally transparent; type scale is monotonic with no role below the readable floor | `CraftMindDesignTokensTest` (13 tests) | PASS |
| Production sources outside `designsystem/` carry no raw colours or raw corner radii; the five token files stay androidx-free; `CraftMindTheme.kt` remains the token→Compose converter | `CraftMindDesignSystemSourceScanTest` (2 tests) | PASS |
| Four destinations, fixed order, Home primary, `fromRoute` fallback | `MainDestinationTest` (4 tests) | PASS |
| Generation stages correspond only to real AI stages; message copy per failure kind | `BuildGenerationPhaseTest` (7), `HomeMessagesTest` (9) | PASS |
| History cards derive every field from the stored record | `BuildRecordSummaryTest` (6) | PASS |
| Minecraft stages/actions/gates derive from real detector, selector, resolver, and certification state | `MinecraftConnectionSummaryTest` (17 tests) | PASS |
| Website architecture, navigation, download policy, honest copy, responsive + reduced-motion markers, no remote resources | `scripts/check_website.py` | PASS |
| No secrets, APKs, keystores, or build outputs are tracked | `scripts/check_release_config.py`, secret scan before commit | PASS |

Android compilation itself is **not** verified in this environment: no Android SDK, no Gradle distribution, and no
Maven access are available here, so `ANDROID_BUILD = NOT_RUN`. The Compose screens are exercised by the JVM-checkable
derivations above and by source-level scans; the instrumented Compose tests
(`PlanReviewScreenTest`, `SettingsScreenTest`) are present but cannot be executed here.
