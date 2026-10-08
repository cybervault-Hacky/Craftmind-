/**
 * Account controllers (Phase 21).
 *
 * The website account UI is the browser twin of the Android account surfaces, and it can only talk to the account
 * service that already exists in `backend/`. With no service configured — the default in this repository — every page
 * renders an honest unavailable state that explains what the page will do and why it cannot do it yet. When a deployer
 * configures an origin, the same components render real account data: display name, email, verification state, safe
 * session metadata, and session revocation. No raw token, session id, or password hash is ever rendered, and nothing is
 * written to browser storage.
 */

import { ACCOUNT_ENDPOINTS, RESULT, createAdapters } from "./adapters.js";
import { dataTableMarkup, keyValueMarkup, metricsMarkup } from "./components.js";
import { STATE, announce, ensureLiveRegion, escapeText, formatTimestamp, orDash, renderState } from "./state.js";

function adapterIsUsable(adapter) {
  return Boolean(adapter && adapter.configured);
}

function unavailableState(region, { title, message, details = [] }) {
  renderState(region, { kind: STATE.UNAVAILABLE, title, message, details });
}

function accountSignInMarkup() {
  return `
  <div class="state-card state-card--unauthorized">
    <div class="state-head"><span class="badge badge--planned">Sign-in required</span><h3>Sign in to view this</h3></div>
    <p>This account surface needs an active session. Credentials are held in this page's memory only and cleared when you sign out or reload.</p>
    <form class="stack" data-account-signin novalidate>
      <div class="field">
        <label for="account-email">Email</label>
        <input class="input" id="account-email" name="email" type="email" autocomplete="username" required aria-describedby="account-email-hint">
        <span class="field-hint" id="account-email-hint">The address you registered with the account service.</span>
      </div>
      <div class="field">
        <label for="account-password">Password</label>
        <input class="input" id="account-password" name="password" type="password" autocomplete="current-password" required aria-describedby="account-password-hint">
        <span class="field-hint" id="account-password-hint">Sent only to the configured account service.</span>
      </div>
      <div class="state-actions">
        <button type="submit" class="button button-primary button-small">Sign in</button>
        <a class="button button-secondary button-small" href="security.html">Forgot your password?</a>
      </div>
    </form>
    <div data-account-feedback></div>
  </div>`;
}

function requireSession(region, adapter) {
  if (!adapterIsUsable(adapter)) {
    unavailableState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No account service is configured for this site",
      message: "This account page is part of the Phase 21 interface foundation. It reads from the existing CraftMind account service, and this deployment has not been pointed at one.",
      details: [
        "The Android app talks to a configured account service; this repository operates no production endpoint and no mail provider.",
        "A deployer can connect a service by defining CRAFTMIND_SITE_CONFIG.accountServiceOrigin before the site scripts load.",
        `Supported calls are limited to the implemented surface: ${ACCOUNT_ENDPOINTS.length} endpoints (sign-in, sessions, verification, password recovery).`,
      ],
    });
    return false;
  }
  if (!adapter.signedIn) {
    region.innerHTML = accountSignInMarkup();
    wireSignIn(region, adapter);
    return false;
  }
  return true;
}

function wireSignIn(region, adapter) {
  const form = region.querySelector("[data-account-signin]");
  const feedback = region.querySelector("[data-account-feedback]");
  form?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const email = form.elements.email?.value.trim() ?? "";
    const password = form.elements.password?.value ?? "";
    if (!email || !password) {
      feedback.innerHTML = '<p class="field-error" role="alert">Enter your email and password.</p>';
      return;
    }
    feedback.innerHTML = '<p class="field-hint" role="status">Signing in…</p>';
    const result = await adapter.signIn({ email, password });
    if (result.status === RESULT.OK) {
      announce("Signed in.");
      globalThis.location.reload();
      return;
    }
    feedback.innerHTML = `<p class="field-error" role="alert">${escapeText(result.message)}</p>`;
  });
}

export function initAccountOverview(root = document.querySelector('[data-page="account"]')) {
  if (!root) return;
  const adapters = createAdapters();
  const region = root.querySelector("[data-account-region]");
  const summary = root.querySelector("[data-account-summary]");
  if (summary) {
    summary.innerHTML = metricsMarkup([
      { value: requestValue(adapters), label: "Account service", note: "Whether this deployment is connected." },
      { value: null, label: "Membership", note: "Read from the service on the membership page." },
      { value: null, label: "Build credits", note: "Credit balance unavailable until a session is active." },
      { value: null, label: "Purchases", note: "No marketplace exists." },
    ]);
  }
  if (!requireSession(region, adapters.account)) return;
  region.dataset.state = STATE.LOADING;
  adapters.account.loadAccount().then((result) => {
    if (result.status !== RESULT.OK) {
      renderState(region, { kind: result.status === RESULT.UNAUTHORIZED ? STATE.UNAUTHORIZED : STATE.ERROR, title: "Account details are unavailable", message: result.message });
      return;
    }
    const account = result.payload?.account ?? {};
    region.dataset.state = STATE.POPULATED;
    region.innerHTML = `<div class="panel"><div class="panel-head"><div><h3>Signed in</h3><p class="small muted">These values come from the configured account service.</p></div></div>
      ${keyValueMarkup([
        ["Display name", account.displayName],
        ["Email", account.email],
        ["Email verified", account.emailVerified === true ? "Yes" : account.emailVerified === false ? "No" : null],
        ["Account status", account.status],
        ["Created", account.createdAt ? formatTimestamp(account.createdAt) : null],
      ])}
      <p class="price-note">Internal account identifiers, session credentials, and password material are never rendered by this page.</p>
      </div>`;
  });
}

function requestValue(adapters) {
  return adapterIsUsable(adapters.account) ? "Connected" : "Not configured";
}

export function initAccountProfile(root = document.querySelector('[data-page="account-profile"]')) {
  if (!root) return;
  const adapters = createAdapters();
  const region = root.querySelector("[data-account-region]");
  if (!requireSession(region, adapters.account)) return;
  region.dataset.state = STATE.LOADING;
  adapters.account.loadAccount().then((result) => {
    if (result.status !== RESULT.OK) {
      renderState(region, { kind: STATE.ERROR, title: "Profile details are unavailable", message: result.message });
      return;
    }
    const account = result.payload?.account ?? {};
    region.dataset.state = STATE.POPULATED;
    region.innerHTML = `
      <div class="panel">
        <div class="panel-head"><div><h3>Profile</h3><p class="small muted">Display name, email, avatar, and preferences are stored by the account service.</p></div></div>
        <div class="row" style="gap:18px;align-items:flex-start">
          <span class="avatar avatar-xl" aria-hidden="true">${escapeText((account.displayName ?? "?").slice(0, 1).toUpperCase())}</span>
          <div style="flex:1;min-width:220px">
            ${keyValueMarkup([
              ["Display name", account.displayName],
              ["Email", account.email],
              ["Email verified", account.emailVerified === true ? "Yes" : "No"],
              ["Account status", account.status],
            ])}
          </div>
        </div>
        <p class="price-note">Profile photos are not implemented. No avatar upload exists, so only your initial is shown. Internal identifiers, session credentials, password hashes, and tokens are never displayed here.</p>
      </div>
      <div class="panel">
        <div class="panel-head"><div><h3>Preferences</h3><p class="small muted">Preferences are stored in the app, not on the account service.</p></div></div>
        <div class="state-card state-card--unavailable">
          <div class="state-head"><span class="badge badge--planned">Not available yet</span><h3>No profile preferences exist yet</h3></div>
          <p>The account service implements authentication and sessions only. Profile editing — display name, avatar, and preferences — is not implemented, so this page will not offer a control that cannot save.</p>
        </div>
      </div>`;
  });
}

export function initAccountSecurity(root = document.querySelector('[data-page="account-security"]')) {
  if (!root) return;
  const adapters = createAdapters();
  const region = root.querySelector("[data-account-region]");
  const capabilities = root.querySelector("[data-security-capabilities]");
  if (capabilities) {
    capabilities.innerHTML = `
      <div class="panel">
        <div class="panel-head"><div><h3>What the service implements</h3><p class="small muted">These flows exist in the CraftMind account service today.</p></div></div>
        ${keyValueMarkup([
          ["Email verification", "Implemented · verification token sent by the configured mail provider"],
          ["Password reset", "Implemented · request and confirm with a one-time token"],
          ["Password change", "Implemented · requires the current password"],
          ["Session revocation", "Implemented · one session or every other session"],
          ["Multi-factor authentication", null],
        ])}
        <p class="price-note">Multi-factor authentication is not implemented, so it is listed as unavailable rather than planned-in-UI.</p>
      </div>
      <div class="panel">
        <div class="panel-head"><div><h3>Password reset</h3><p class="small muted">Available without a session. The request endpoint always answers the same way, so it cannot reveal whether an address exists.</p></div></div>
        <form class="stack" data-reset-request novalidate>
          <div class="field">
            <label for="reset-email">Account email</label>
            <input class="input" id="reset-email" name="email" type="email" autocomplete="email" required aria-describedby="reset-email-hint">
            <span class="field-hint" id="reset-email-hint">A reset message is sent only if an account with this address exists.</span>
          </div>
          <div class="state-actions"><button type="submit" class="button button-secondary button-small">Request password reset</button></div>
          <div data-reset-feedback></div>
        </form>
      </div>`;
    wireResetRequest(capabilities, adapters.account);
  }
  if (!requireSession(region, adapters.account)) return;
  region.dataset.state = STATE.POPULATED;
  region.innerHTML = `<div class="panel"><div class="panel-head"><div><h3>Change password</h3><p class="small muted">Requires your current password. All other sessions are revoked by the service after a change.</p></div></div>
    <form class="stack" data-password-change novalidate>
      <div class="field"><label for="current-password">Current password</label><input class="input" id="current-password" name="currentPassword" type="password" autocomplete="current-password" required></div>
      <div class="field"><label for="new-password">New password</label><input class="input" id="new-password" name="newPassword" type="password" autocomplete="new-password" required aria-describedby="new-password-hint"><span class="field-hint" id="new-password-hint">Use a long, unique passphrase.</span></div>
      <div class="state-actions"><button type="submit" class="button button-primary button-small">Change password</button></div>
      <div data-password-feedback></div>
    </form>
    <p class="price-note">Nothing is stored in this page: the new password is sent to the configured account service and then discarded from memory.</p>
    </div>`;
  wirePasswordChange(region, adapters.account);
}

function wireResetRequest(container, adapter) {
  const form = container.querySelector("[data-reset-request]");
  const feedback = container.querySelector("[data-reset-feedback]");
  form?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const email = form.elements.email?.value.trim() ?? "";
    if (!email) { feedback.innerHTML = '<p class="field-error" role="alert">Enter the account email.</p>'; return; }
    if (!adapterIsUsable(adapter)) {
      feedback.innerHTML = '<p class="field-error" role="alert">No account service is configured for this site, so no reset can be requested.</p>';
      return;
    }
    const result = await adapter.requestPasswordReset({ email });
    feedback.innerHTML = result.status === RESULT.OK
      ? '<p class="field-hint" role="status">If that address matches an account, a reset message is on its way.</p>'
      : `<p class="field-error" role="alert">${escapeText(result.message)}</p>`;
  });
}

function wirePasswordChange(container, adapter) {
  const form = container.querySelector("[data-password-change]");
  const feedback = container.querySelector("[data-password-feedback]");
  form?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const currentPassword = form.elements.currentPassword?.value ?? "";
    const newPassword = form.elements.newPassword?.value ?? "";
    if (!currentPassword || !newPassword) {
      feedback.innerHTML = '<p class="field-error" role="alert">Fill in both password fields.</p>';
      return;
    }
    const result = await adapter.changePassword({ currentPassword, newPassword });
    feedback.innerHTML = result.status === RESULT.OK
      ? '<p class="field-hint" role="status">Password changed.</p>'
      : `<p class="field-error" role="alert">${escapeText(result.message)}</p>`;
  });
}

export function initAccountSessions(root = document.querySelector('[data-page="account-sessions"]')) {
  if (!root) return;
  const adapters = createAdapters();
  const region = root.querySelector("[data-account-region]");
  if (!requireSession(region, adapters.account)) return;
  region.dataset.state = STATE.LOADING;
  adapters.account.loadSessions().then((result) => {
    if (result.status !== RESULT.OK) {
      renderState(region, { kind: result.status === RESULT.UNAUTHORIZED ? STATE.UNAUTHORIZED : STATE.ERROR, title: "Sessions are unavailable", message: result.message });
      return;
    }
    const sessions = result.payload?.sessions ?? [];
    if (sessions.length === 0) {
      renderState(region, { kind: STATE.EMPTY, title: "No sessions", message: "The account service reported no active sessions for this account." });
      return;
    }
    region.dataset.state = STATE.POPULATED;
    region.innerHTML = dataTableMarkup({
      caption: "Safe session metadata only. Session credentials, identifiers, and locations are never shown — the service does not provide a location.",
      columns: [{ label: "Device", rowHeader: true }, { label: "Last active" }, { label: "Current" }, { label: "Actions" }],
      rows: sessions.map((session) => [
        escapeText(orDash(session.deviceLabel)),
        escapeText(session.lastUsedAt ? formatTimestamp(session.lastUsedAt) : null),
        session.current === true ? '<span class="badge badge--current">This session</span>' : '<span class="badge badge--muted">Other</span>',
        session.current === true ? "" : '<button type="button" class="button button-secondary button-small" data-revoke-session>Revoke</button>',
      ]),
      htmlColumns: [2, 3],
    });
    for (const button of region.querySelectorAll("[data-revoke-session]")) {
      button.addEventListener("click", async () => {
        button.disabled = true;
        const outcome = await adapters.account.revokeOtherSessions();
        announce(outcome.status === RESULT.OK ? "Other sessions revoked." : outcome.message);
        button.disabled = false;
      });
    }
  });
}

export function initAccountPurchases(root = document.querySelector('[data-page="account-purchases"]')) {
  if (!root) return;
  const region = root.querySelector("[data-account-region]");
  if (!region) return;
  unavailableState(region, {
    title: "No purchases",
    message: "Purchases arrive with the marketplace and payment services, neither of which is implemented. Nothing can be bought on CraftMind yet, so this account has no purchase history.",
    details: [
      "When buying exists, each row will show the build, the creator, the date, the settled amount, the order status, and whether the build is available to you.",
      "No receipt, invoice, or payment reference can exist before a payment service does.",
    ],
  });
}

export function initAccountSaved(root = document.querySelector('[data-page="account-saved"]')) {
  if (!root) return;
  const region = root.querySelector("[data-account-region]");
  if (!region) return;
  unavailableState(region, {
    title: "Nothing saved",
    message: "Saved builds, followed creators, and wishlists need the marketplace service to store them against your account. On a marketplace page, the save control keeps an item in that page for the current visit only.",
    details: [
      "Saving is not sent anywhere, is never written to browser storage, and disappears when you close the page.",
      "Once the marketplace exists, this page will list saved builds and creators for your account.",
    ],
    action: { label: "Browse the marketplace layout", href: "../marketplace/index.html" },
  });
}

/**
 * The membership section of the account area.
 *
 * With a configured service and an active session this reads the account's own membership, entitlements, and credit
 * balance from the server, and says plainly which plan is in effect. Without one, it keeps the Phase 21 honest state:
 * nothing is claimed, no billing date is invented, and no balance is shown that the page cannot substantiate.
 */
export async function initAccountMembership(root = document.querySelector('[data-page="account-membership"]')) {
  if (!root) return;
  const region = root.querySelector("[data-account-region]");
  const metrics = root.querySelector("[data-membership-account-metrics]");
  const adapters = createAdapters();
  if (metrics) {
    metrics.innerHTML = metricsMarkup([
      { value: "Free", label: "Baseline plan", note: "Every account starts on the free baseline." },
      { value: null, label: "Plan end", note: "Reported by the service when a plan has an end date." },
      { value: null, label: "Build credits", note: "Credit balance unavailable until a session is active." },
      { value: null, label: "Billing history", note: "No billing exists." },
    ]);
  }
  if (!region) return;
  if (!requireSession(region, adapters.account)) return;
  region.dataset.state = STATE.LOADING;
  const [membershipResult, entitlementsResult, creditsResult, capabilitiesResult] = await Promise.all([
    adapters.account.loadMembership(),
    adapters.account.loadEntitlements(),
    adapters.account.loadCredits(),
    // Phase 23: the account's creator identity, summarized. A supplementary read: if it fails, the membership panel
    // still reports the plan and balance it could verify, and the creator line simply says nothing was read.
    adapters.account.loadAccountCapabilities(),
  ]);
  if (membershipResult.status !== RESULT.OK || entitlementsResult.status !== RESULT.OK || creditsResult.status !== RESULT.OK) {
    const unauthorized = [membershipResult, entitlementsResult, creditsResult].some((result) => result.status === RESULT.UNAUTHORIZED);
    renderState(region, {
      kind: unauthorized ? STATE.UNAUTHORIZED : STATE.ERROR,
      title: unauthorized ? "Sign in again to read your plan" : "Your membership could not be read",
      message: unauthorized
        ? "The account session ended, so no plan, entitlement, or balance is shown."
        : (membershipResult.message ?? "The account service did not answer."),
    });
    return;
  }
  const plan = membershipResult.payload?.membership ?? {};
  const catalogue = membershipResult.payload?.plan ?? {};
  const entitlements = entitlementsResult.payload?.entitlements ?? [];
  const grants = entitlementsResult.payload?.administrativeGrants ?? [];
  const credits = creditsResult.payload?.credits ?? {};
  const creator = capabilitiesResult.status === RESULT.OK ? capabilitiesResult.payload?.creator ?? null : null;
  const creatorLine = creator
    ? (creator.exists
      ? `${creator.handle ? `@${creator.handle} · ` : ""}${creator.status ?? "unknown status"} · ${creator.verification ?? "unverified"}`
      : "No creator profile yet")
    : null;
  const balance = Number.isFinite(credits.available) ? `${credits.available} credits` : "Credit balance unavailable";
  region.dataset.state = STATE.POPULATED;
  region.innerHTML = `
    <div class="panel">
      <div class="panel-head">
        <div>
          <h3>${escapeText(plan.planName ?? plan.plan ?? "Your plan")}</h3>
          <p class="small muted">These values come from the configured account service for this account. Plans, entitlements, and balances are decided by the server.</p>
        </div>
        <span class="badge ${plan.plan === "FREE" ? "badge--current" : "badge--planned"}">${escapeText(plan.status ?? "UNKNOWN")}</span>
      </div>
      ${keyValueMarkup([
        ["Plan", plan.plan],
        ["Plan status", plan.status],
        ["Plan source", plan.source],
        ["Plan ends", plan.endsAt ? formatTimestamp(plan.endsAt) : "No end date — the plan does not expire"],
        ["Availability", catalogue.availabilityLabel],
        ["Purchasable", catalogue.purchasable === true ? "Yes" : "No — no payment is implemented"],
        ["Credit balance", balance],
        ["Creator identity", creatorLine],
        ["Entitlements", entitlements.map((entry) => entry.key).join(", ") || null],
        ["Administrative grants", grants.map((grant) => grant.entitlementKey).join(", ") || "None active"],
      ])}
      <p class="price-note">Nothing on this page is purchasable, and no billing date is shown because no billing exists. Upgrading, downgrading, and cancelling arrive with a payment phase, not before it.</p>
    </div>`;
  if (metrics) {
    metrics.innerHTML = metricsMarkup([
      { value: plan.plan ?? null, label: "Current plan", note: "Reported by the account service." },
      { value: plan.endsAt ? formatTimestamp(plan.endsAt) : "No end date", label: "Plan end", note: "Expiry is applied by the server." },
      { value: balance, label: "Build credits", note: "Read-only display of the authoritative balance." },
      { value: entitlements.length > 0 ? String(entitlements.length) : null, label: "Entitlements", note: "Resolved by the server for every request." },
    ]);
  }
}

/** Dispatches to the controller for whichever account page this document is. */
export function initAccount() {
  ensureLiveRegion();
  const page = document.body.dataset.page;
  if (!page || !page.startsWith("account")) return;
  const root = document.querySelector(`[data-page="${page}"]`);
  switch (page) {
    case "account": initAccountOverview(root); break;
    case "account-profile": initAccountProfile(root); break;
    case "account-security": initAccountSecurity(root); break;
    case "account-sessions": initAccountSessions(root); break;
    case "account-purchases": initAccountPurchases(root); break;
    case "account-saved": initAccountSaved(root); break;
    case "account-membership": initAccountMembership(root); break;
    default: break;
  }
}
