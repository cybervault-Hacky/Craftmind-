# Browser end-to-end tests (Phase 38) — **PREPARED, NOT RUN**

One spec lives here: `password-recovery.spec.mjs`. It covers the web password-recovery journey in a real browser.
**No browser has ever executed it**, and this file states why rather than leaving the directory to imply that it runs
in CI somewhere. It has not been run even once, so treat the first execution as work still to do, not as a green suite
that lost its badge.

## Why it was not run here (measured, not assumed)

| Requirement | This environment |
| --- | --- |
| A browser binary | none — `chromium`, `chromium-browser`, `google-chrome`, `google-chrome-stable`, `firefox`, `msedge` all absent from `PATH` |
| The automation package | `require.resolve("playwright")` → `MODULE_NOT_FOUND`; no `~/.cache/ms-playwright`; `backend/node_modules` does not exist |
| Fetching one | Playwright's browsers come from a CDN outside this sandbox's allowlist, so `npx playwright install` cannot resolve |
| Installing it | `backend/package.json` declares **no** dependencies at all, and the repository's own audit records adding a browser framework as an owner decision (`docs/roadmap-audit.md`, Phase 38 dependencies) |
| A device/emulator alternative | no `/dev/kvm`, no `adb`, so no Android WebView path either |

So Phase 38 did the two things that were actually possible: it implemented the missing product surface (B4), and it
built the coverage that runs without a browser — `backend/test/website-recovery.test.js`, which mounts the real
`website/recovery.html` and the real `website/assets/recovery.js` against the real account service over HTTP. That
suite is **DOM-level integration, not browser E2E**, and is never described as browser E2E anywhere.

## What browser tests still prove that the DOM suite cannot

Computed style and visibility (is the error actually *shown*, or only present in the DOM), focus order and Tab
reachability, real click-driven navigation and the URL the address bar ends up holding, `localStorage` /
`sessionStorage` / `document.cookie` being genuinely empty, and the console a visitor's devtools would show.

## To run it (owner's machine)

```bash
npm i -D @playwright/test@1.56.1
npx playwright install chromium
npx playwright test website/e2e/password-recovery.spec.mjs
```

There is deliberately no `package.json` change and no lockfile in this directory: adding a dev dependency to a
zero-dependency project is the owner's call (B3), not a side effect of a phase that wanted a badge.

The spec needs no configuration, because it starts everything itself — a read-only static server for `website/`
(confined to that directory) and the real account service through `backend/test/helpers.js`'s `startService()`, with
its development mail sink supplying the one-time code in-process. No mailbox route is exposed, because the production
server deliberately has none.

Three things to know before the first run:

* **Expect to fix it.** The assertions encode what the site does today, but nobody has watched a browser do it. A
  selector may need tuning; the flow assertions should not.
* **The origin trick is the whole point of `page.route`.** `adapters.js` accepts only an absolute `https://` origin,
  mirroring the service's own HTTPS/CORS policy. A local run has no TLS, so the spec configures the page with
  `https://accounts.craftmind.test`, which does not exist, and forwards it to the plain-HTTP service. Do not "simplify"
  this by loosening `siteConfiguration()` — that rule is a security property, not a test obstacle.
* **Nothing here may touch a real deployment.** The service is in-memory SQLite with the non-delivering mail sink, the
  addresses are `@example.test`, and no message is ever sent. If a future change points this at a hosted backend, it
  stops being a test and starts being an incident.

## Coverage map (the phase's list, and where each half lands)

| Scenario | Browser spec (not run) | DOM-level suite (runs today) |
| --- | --- | --- |
| 1. Request screen → confirmation screen | first test, by clicking the in-page anchor | ✓ plus the sign-in and security-page links |
| 2. Required-field validation | second test, `aria-invalid` and the visible alert | ✓ including the truncated-paste, weak-password, and mismatch cases |
| 3. Valid synthetic token, success | third test, then a real login with the new password | ✓ same, plus the exact `{token,newPassword}` wire shape |
| 4. Invalid / expired / already-used token | fourth test | ✓ against the real service (a backdated row, a spent code, a wrong code) |
| 5. API failure and network error | fifth test, by repointing the route at a dead port | ✓ a stub for typed refusals and a closed port for transport failure |
| 6. Duplicate-submit prevention | third test, mid-flight disabled control | ✓ three dispatches, exactly one request, verified on the wire |
| 7. Return to sign-in | first test, by clicking it | ✓ the anchor exists and is the success state's action |
| 8. No token/password in logs or errors | third and sixth tests (console listener, storage, cookies, URL) | ✓ serialized document, captured console, and the server's own response body |

Not covered by either: CSS and layout, real keyboard semantics beyond what the DOM suite can imitate, screen-reader
output, and any second page of the site — the harness only drives recovery. The other journeys (sign-in, marketplace,
creator onboarding) still have **no browser coverage at all**; that remains open with B3.
