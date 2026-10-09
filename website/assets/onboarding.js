/**
 * Sign-in and onboarding controllers (Phase 24).
 *
 * This module drives the dedicated account-experience pages: sign-in and account creation (`signin.html`), the role
 * choice (`onboarding/index.html`), buyer onboarding (`onboarding/buyer.html`), and seller onboarding
 * (`onboarding/seller.html`). Every server interaction travels through `adapters.js` — the single fetch boundary —
 * and every value rendered from a response is escaped through the shared text helpers.
 *
 * Two rules shape everything here:
 *
 *   * the server decides. Ownership, completion, requirements, verification posture, and capability all arrive in
 *     the response; the page never computes "done" from a form being full, and never persists tokens or answers in
 *     browser storage — the in-memory adapter session dies with the tab, as it does everywhere else on this site;
 *   * unavailable is a real state. When no account service is configured, when a session is required, when phone
 *     verification does not exist, or when a plan grant is missing, the page says exactly that — with the server's
 *     own message — instead of imitating success.
 */

import { ACCOUNT_ENDPOINTS, RESULT, createAdapters } from "./adapters.js";
import { STATE, announce, escapeText, escapeAttribute, formatTimestamp, orDash, renderState } from "./state.js";

function adapterUsable(adapter) {
  return Boolean(adapter && adapter.configured);
}

/** The shared honest state for a deployment with no account service connected. */
function renderUnconfigured(region) {
  renderState(region, {
    kind: STATE.UNAVAILABLE,
    title: "No account service is configured for this site",
    message: "This page works only with a connected CraftMind account service. No deployment is pointed at one, so nothing is signed in, saved, or verified here.",
    details: [
      "A deployer connects the service by defining CRAFTMIND_SITE_CONFIG.accountServiceOrigin before the site scripts load.",
      `Supported calls are limited to the implemented surface: ${ACCOUNT_ENDPOINTS.length} endpoints.`,
    ],
  });
}

function renderSignInRequired(region, { message = "This page needs an active account session for the configured CraftMind account service." } = {}) {
  renderState(region, {
    kind: STATE.UNAUTHORIZED,
    title: "Sign in first",
    message,
    action: { label: "Go to sign in", href: "../signin.html" },
  });
}

function renderUnexpectedError(region, result, title) {
  renderState(region, {
    kind: STATE.ERROR,
    title,
    message: result.message || "The account service could not complete this request.",
  });
}

/** The typed error the server returned, rendered honestly — including a refusal's own explanation. */
function renderServerError(region, result, title) {
  const kind = result.status === RESULT.UNAUTHORIZED ? STATE.UNAUTHORIZED : STATE.ERROR;
  if (kind === STATE.UNAUTHORIZED) {
    renderSignInRequired(region, { message: result.payload?.error?.message ?? result.message });
    return;
  }
  renderState(region, {
    kind,
    title,
    message: result.payload?.error?.message ?? result.message,
    action: result.payload?.error?.code === "CREATOR_ENTITLEMENT_REQUIRED"
      ? { label: "View membership", href: "../account/membership.html" }
      : undefined,
  });
}

function statusChip(label, tone) {
  return `<span class="badge badge--${escapeAttribute(tone)}">${escapeText(label)}</span>`;
}

// ------------------------------------------------------------------------------- sign-in / create account

export function initSignIn(root = document.querySelector('[data-page="signin"]')) {
  if (!root) return;
  const { account } = createAdapters();
  const region = root.querySelector("[data-signin-region]");
  const registerRegion = root.querySelector("[data-register-region]");
  if (!adapterUsable(account)) {
    renderUnconfigured(region);
    if (registerRegion) renderUnconfigured(registerRegion);
    return;
  }

  if (account.signedIn) {
    renderState(region, {
      kind: STATE.SUCCESS,
      title: "You are signed in",
      message: "This session belongs to the current page visit only; signing out or reloading clears it.",
      action: { label: "Continue to your account", href: "account/index.html" },
    });
    if (registerRegion) registerRegion.innerHTML = `
      <div class="panel"><div class="panel-head"><div><h3>Already have an account</h3>
      <p class="small muted">Continue with onboarding, or sign out from your account page first.</p></div></div>
      <div class="state-actions"><a class="button button-secondary" href="onboarding/index.html">Choose buyer or seller</a>
      <a class="button button-secondary" href="account/index.html">My account</a></div></div>`;
    return;
  }

  // Two panels, both live: sign in with an existing account, or create one. Registration returns no session until
  // the issued verification token is used, which is exactly what the panel below then asks for.
  region.innerHTML = `
    <div class="panel">
      <div class="panel-head"><div><h3>Sign in</h3><p class="small muted">Credentials go only to the configured account service on this page's server connection.</p></div></div>
      <form class="stack" data-signin-form novalidate>
        <div class="field">
          <label for="signin-email">Email</label>
          <input class="input" id="signin-email" name="email" type="email" autocomplete="username" required aria-describedby="signin-email-hint">
          <span class="field-hint" id="signin-email-hint">The address your account uses.</span>
        </div>
        <div class="field">
          <label for="signin-password">Password</label>
          <input class="input" id="signin-password" name="password" type="password" autocomplete="current-password" required aria-describedby="signin-password-hint">
          <span class="field-hint" id="signin-password-hint">Sent only to the configured account service.</span>
        </div>
        <div class="state-actions">
          <button type="submit" class="button button-primary">Sign in</button>
          <a class="button button-secondary" href="security.html">Forgot your password?</a>
        </div>
      </form>
      <div data-signin-feedback></div>
    </div>`;

  const form = region.querySelector("[data-signin-form]");
  const feedback = region.querySelector("[data-signin-feedback]");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const email = form.elements.email.value.trim();
    const password = form.elements.password.value;
    if (!email || !password) {
      feedback.innerHTML = `<p class="field-error" role="alert">${escapeText("Enter your email and password.")}</p>`;
      return;
    }
    feedback.innerHTML = `<p class="field-hint" role="status">${escapeText("Signing in…")}</p>`;
    const result = await account.signIn({ email, password });
    if (result.status === RESULT.OK) {
      announce("Signed in.");
      globalThis.location.href = "onboarding/index.html";
      return;
    }
    const serverMessage = result.payload?.error?.message;
    feedback.innerHTML = `<p class="field-error" role="alert">${escapeText(serverMessage ?? result.message)}</p>`;
  });

  if (registerRegion) initRegistration(registerRegion, account);
}

function initRegistration(region, account) {
  region.innerHTML = `
    <div class="panel">
      <div class="panel-head"><div><h3>Create an account</h3><p class="small muted">Free to create. One account can later act as both buyer and seller — you choose the first role after signing in.</p></div></div>
      <form class="stack" data-register-form novalidate>
        <div class="field">
          <label for="register-name">Display name</label>
          <input class="input" id="register-name" name="displayName" type="text" autocomplete="name" maxlength="80" required aria-describedby="register-name-hint">
          <span class="field-hint" id="register-name-hint">Shown on your account; you can refine it later.</span>
        </div>
        <div class="field">
          <label for="register-email">Email</label>
          <input class="input" id="register-email" name="email" type="email" autocomplete="username" required>
        </div>
        <div class="field">
          <label for="register-password">Password</label>
          <input class="input" id="register-password" name="password" type="password" autocomplete="new-password" required aria-describedby="register-password-hint">
          <span class="field-hint" id="register-password-hint">A passphrase of at least 12 characters.</span>
        </div>
        <div class="state-actions"><button type="submit" class="button button-primary">Create account</button></div>
      </form>
      <div data-register-feedback></div>
    </div>`;

  const form = region.querySelector("[data-register-form]");
  const feedback = region.querySelector("[data-register-feedback]");
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const displayName = form.elements.displayName.value.trim();
    const email = form.elements.email.value.trim();
    const password = form.elements.password.value;
    if (!displayName || !email || password.length < 12) {
      feedback.innerHTML = `<p class="field-error" role="alert">${escapeText("Fill in the display name and email, and choose a password of at least 12 characters.")}</p>`;
      return;
    }
    feedback.innerHTML = `<p class="field-hint" role="status">${escapeText("Creating your account…")}</p>`;
    const result = await account.register({ email, password, displayName });
    if (result.status !== RESULT.OK) {
      feedback.innerHTML = `<p class="field-error" role="alert">${escapeText(result.payload?.error?.message ?? result.message)}</p>`;
      return;
    }
    // Honest about what registration did and did not do: no session yet, and delivery depends on the deployment.
    feedback.innerHTML = `
      <div class="stack" role="status">
        <p>Account created. The account service issues a verification link for <strong>${escapeText(email)}</strong>;
        whether a message reaches that inbox depends on this deployment's mail setup.</p>
        <p class="small muted">Have the verification token already? Paste it to finish — or open the link from the message.</p>
        <form class="stack" data-verify-form novalidate>
          <div class="field">
            <label for="verify-token">Verification token</label>
            <input class="input" id="verify-token" name="token" type="text" autocomplete="one-time-code" required aria-describedby="verify-token-hint">
            <span class="field-hint" id="verify-token-hint">The long token from the verification message or link.</span>
          </div>
          <div class="state-actions"><button type="submit" class="button button-secondary">Verify email</button></div>
        </form>
        <div data-verify-feedback></div>
      </div>`;
    const verifyForm = region.querySelector("[data-verify-form]");
    const verifyFeedback = region.querySelector("[data-verify-feedback]");
    verifyForm.addEventListener("submit", async (verifyEvent) => {
      verifyEvent.preventDefault();
      const token = verifyForm.elements.token.value.trim();
      if (!token) {
        verifyFeedback.innerHTML = `<p class="field-error" role="alert">${escapeText("Paste the verification token.")}</p>`;
        return;
      }
      verifyFeedback.innerHTML = `<p class="field-hint" role="status">${escapeText("Verifying…")}</p>`;
      const verified = await account.verifyEmail({ token });
      if (verified.status === RESULT.OK) {
        verifyFeedback.innerHTML = `<p class="field-hint" role="status">${escapeText("Email verified. You can sign in now.")}</p>`;
        announce("Email verified.");
        return;
      }
      verifyFeedback.innerHTML = `<p class="field-error" role="alert">${escapeText(verified.payload?.error?.message ?? verified.message)}</p>`;
    });
  });
}

// --------------------------------------------------------------------------------------- role choice hub

export function initOnboardingHub(root = document.querySelector('[data-page="onboarding"]')) {
  if (!root) return;
  const { account } = createAdapters();
  const region = root.querySelector("[data-onboarding-state]");
  if (!region) return;
  if (!adapterUsable(account)) {
    renderUnconfigured(region);
    return;
  }
  if (!account.signedIn) {
    renderSignInRequired(region);
    return;
  }
  region.innerHTML = `<div class="state state-loading" data-state="loading" aria-busy="true"><div class="skeleton-block skeleton-block--card"></div></div>`;
  account.loadOnboardingState().then((result) => {
    if (result.status !== RESULT.OK) {
      renderServerError(region, result, "Onboarding state is unavailable");
      return;
    }
    const payload = result.payload ?? {};
    const buyer = payload.onboarding?.buyer ?? {};
    const seller = payload.onboarding?.seller ?? {};
    const pendingSeller = [...(payload.requirements?.seller?.pending ?? [])];
    const nextSteps = [...(payload.nextSteps ?? [])];
    const choice = root.querySelector("[data-role-choice]");
    if (choice) choice.hidden = false;
    region.innerHTML = `
      <div class="panel">
        <div class="panel-head"><div><h3>Your roles</h3>
        <p class="small muted">One CraftMind account can hold both roles. Completing one never blocks the other, and neither role costs anything to activate.</p></div></div>
        <div class="card-grid two">
          <div class="panel">
            <div class="panel-head"><div><h4>Buyer</h4>
            <p class="small muted">Browse the marketplace and keep purchase history under your account. Digital goods only — no physical address is ever asked for.</p></div></div>
            <div class="state-actions">
              ${statusChip(buyer.completed ? "Complete" : "Not started", buyer.completed ? "current" : "planned")}
              <a class="button button-primary button-small" href="buyer.html">${buyer.completed ? "Review buyer answers" : "Set up as buyer"}</a>
            </div>
          </div>
          <div class="panel">
            <div class="panel-head"><div><h4>Seller</h4>
            <p class="small muted">Create your creator profile. Publishing listings and Trusted Seller status are separate, later steps — this page only prepares identity and answers.</p></div></div>
            <div class="state-actions">
              ${statusChip(seller.completed ? "Complete" : "Not started", seller.completed ? "current" : "planned")}
              <a class="button button-primary button-small" href="seller.html">${seller.completed ? "Review seller answers" : "Set up as seller"}</a>
            </div>
          </div>
        </div>
        <div class="stack">
          ${nextSteps.length > 0
            ? `<p class="small">Next steps from the account service: <strong>${escapeText(nextSteps.join(", "))}</strong>.</p>`
            : `<p class="small">Both roles are complete on the account service.</p>`}
          ${pendingSeller.length > 0
            ? `<p class="small muted">Seller requirements still pending: ${escapeText(pendingSeller.join(", "))}.</p>`
            : ""}
          ${nextSteps.includes("COMPLETE_BUYER_ONBOARDING")
            ? `<div class="state-actions"><a class="button button-secondary button-small" href="marketplace/index.html">Browse the marketplace</a></div>`
            : ""}
        </div>
      </div>`;
  });
}

// ------------------------------------------------------------------------------------------- buyer page

const REFERRAL_LABELS = Object.freeze({
  YOUTUBE: "YouTube", INSTAGRAM: "Instagram", GOOGLE: "Google / Search", REDDIT: "Reddit",
  DISCORD: "Discord", FRIEND_REFERRAL: "Friend or referral", MINECRAFT_COMMUNITY: "Minecraft community",
  OTHER: "Other",
});

function referralOptionsMarkup(selected) {
  return Object.entries(REFERRAL_LABELS)
    .map(([value, label]) => `<option value="${escapeAttribute(value)}"${value === selected ? " selected" : ""}>${escapeText(label)}</option>`)
    .join("");
}

export function initBuyerOnboarding(root = document.querySelector('[data-page="onboarding-buyer"]')) {
  if (!root) return;
  const { account } = createAdapters();
  const status = root.querySelector("[data-buyer-status]");
  const formRegion = root.querySelector("[data-buyer-form]");
  if (!adapterUsable(account)) {
    renderUnconfigured(status);
    if (formRegion) formRegion.hidden = true;
    return;
  }
  if (!account.signedIn) {
    renderSignInRequired(status);
    if (formRegion) formRegion.hidden = true;
    return;
  }
  renderState(status, { kind: STATE.LOADING, title: "Loading your buyer answers", message: "Reading from the account service…" });
  Promise.all([account.loadOnboardingState(), account.loadBuyerOnboarding()]).then(([stateResult, buyerResult]) => {
    if (stateResult.status !== RESULT.OK) {
      renderServerError(status, stateResult, "Onboarding state is unavailable");
      if (formRegion) formRegion.hidden = true;
      return;
    }
    if (buyerResult.status !== RESULT.OK) {
      renderServerError(status, buyerResult, "Your buyer answers are unavailable");
      if (formRegion) formRegion.hidden = true;
      return;
    }
    const state = stateResult.payload ?? {};
    const buyer = buyerResult.payload?.buyer ?? {};
    const email = state.verification?.email ?? {};
    const phone = state.verification?.phone ?? {};
    status.innerHTML = `
      <div class="panel">
        <div class="panel-head"><div><h3>Buyer profile</h3>
        <p class="small muted">Answers are saved by the account service and shown here exactly as stored.</p></div></div>
        <div class="state-actions">
          ${statusChip(buyer.completed ? "Complete" : "Not started", buyer.completed ? "current" : "planned")}
          ${statusChip(`Email: ${email.verified ? "Verified" : "Not verified"}`, email.verified ? "current" : "planned")}
        </div>
        <p class="small muted">${escapeText(phone.reason ?? "Phone verification does not exist in CraftMind yet, so no phone number is collected.")}</p>
        ${buyer.completed ? `<p class="small">Completed ${escapeText(formatTimestamp(buyer.completedAt) ?? orDash(buyer.completedAt))}.</p>` : ""}
      </div>`;
    if (formRegion) {
      formRegion.hidden = false;
      const form = formRegion.querySelector("[data-buyer-form-element]");
      form.elements.fullName.value = buyer.fullName ?? "";
    // The referral list is the server's, not this page's: rebuild the select from the response so a vocabulary
    // change (or a new server version) can never diverge from what the backend actually accepts.
    const referralSelect = form.elements.referralSource;
    const selectedReferral = referralSelect.value;
    if (Array.isArray(state.options?.referralSources) && state.options.referralSources.length > 0) {
      referralSelect.innerHTML = referralOptionsMarkup(selectedReferral || null);
    }
      form.elements.referralSource.value = buyer.referralSource ?? "";
      form.elements.referralDetail.value = buyer.referralDetail ?? "";
      wireBuyerForm(formRegion, account, state);
    }
  });
}

function wireBuyerForm(formRegion, account, state) {
  const form = formRegion.querySelector("[data-buyer-form-element]");
  const feedback = formRegion.querySelector("[data-buyer-feedback]");
  const detail = form.elements.referralDetail;
  const detailRow = formRegion.querySelector("[data-detail-row]");
  const syncDetail = () => {
    const isOther = form.elements.referralSource.value === "OTHER";
    if (detailRow) detailRow.hidden = !isOther;
  };
  form.elements.referralSource.addEventListener("change", syncDetail);
  syncDetail();

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const fullName = form.elements.fullName.value.trim();
    const referralSource = form.elements.referralSource.value;
    const referralDetail = detail.value.trim();
    if (fullName.length < 2 || fullName.length > 120 || !referralSource) {
      feedback.innerHTML = `<p class="field-error" role="alert">${escapeText("Enter your full name (2–120 characters) and choose how you heard about us.")}</p>`;
      return;
    }
    const answers = { fullName, referralSource };
    if (referralDetail) answers.referralDetail = referralDetail;
    feedback.innerHTML = `<p class="field-hint" role="status">${escapeText("Saving…")}</p>`;
    const result = await account.saveBuyerOnboarding(answers);
    if (result.status !== RESULT.OK) {
      feedback.innerHTML = "";
      renderServerError(formRegion, result, "Buyer answers were not saved");
      return;
    }
    const saved = result.payload?.buyer ?? {};
    const email = result.payload?.verification?.email ?? {};
    renderState(formRegion, {
      kind: STATE.SUCCESS,
      title: "Buyer answers saved",
      message: `The account service stored your buyer profile${saved.completedAt ? ` at ${formatTimestamp(saved.completedAt)}` : ""}. Email verification: ${email.verified ? "verified" : "not verified"}.`,
      details: [
        `Referral: ${REFERRAL_LABELS[saved.referralSource] ?? orDash(saved.referralSource)}.`,
        "No phone number, address, date of birth, or identity document was collected — those mechanisms are not part of this phase.",
      ],
      action: { label: "Continue to the marketplace", href: "../marketplace/index.html" },
    });
    announce("Buyer answers saved.");
  });
}

// ------------------------------------------------------------------------------------------ seller page

function editionSectionMarkup(options, selectedEditions, selectedVersions, selectedLoaders) {
  // Each option is a wrapping <label class="row"> — no generated ids, so loader names containing spaces stay valid
  // HTML, and the input remains a normal keyboard-operable control.
  const lines = [];
  for (const edition of options) {
    const checked = selectedEditions.includes(edition.wire) ? " checked" : "";
    lines.push(`<label class="row"><input type="checkbox" name="editions" value="${escapeAttribute(edition.wire)}"${checked} data-edition-toggle> ${escapeText(edition.wire)}</label>`);
    for (const loader of edition.loaders) {
      const loaderChecked = selectedLoaders.includes(loader) ? " checked" : "";
      lines.push(`<label class="row"><input type="checkbox" name="loaders" value="${escapeAttribute(loader)}"${loaderChecked}> ${escapeText(loader)}</label>`);
    }
  }
  return `
    <fieldset class="fieldset">
      <legend>Editions</legend>
      <div class="stack">
        <span class="field-hint">Pick each edition you support. Loaders follow each edition — choose only the ones you actually build with.</span>
        ${lines.join("")}
        <span class="field-hint">Editions claimed: <strong data-edition-count>${selectedEditions.length}</strong> of ${options.length}.</span>
      </div>
    </fieldset>
    <div class="field">
      <label for="seller-versions">Minecraft versions</label>
      <input class="input" id="seller-versions" name="minecraftVersions" type="text" placeholder="${escapeAttribute(selectedVersions.join(", ") || "1.20.1, 1.21.0")}" value="${escapeAttribute(selectedVersions.join(", "))}" aria-describedby="seller-versions-hint">
      <span class="field-hint" id="seller-versions-hint">Comma-separated version numbers you support, up to 12 (for example 1.20.1, 1.21.0).</span>
    </div>`;
}

export function initSellerOnboarding(root = document.querySelector('[data-page="onboarding-seller"]')) {
  if (!root) return;
  const { account } = createAdapters();
  const status = root.querySelector("[data-seller-status]");
  const formRegion = root.querySelector("[data-seller-form]");
  if (!adapterUsable(account)) {
    renderUnconfigured(status);
    if (formRegion) formRegion.hidden = true;
    return;
  }
  if (!account.signedIn) {
    renderSignInRequired(status);
    if (formRegion) formRegion.hidden = true;
    return;
  }
  renderState(status, { kind: STATE.LOADING, title: "Loading your seller profile", message: "Reading from the account service…" });
  Promise.all([account.loadOnboardingState(), account.loadSellerOnboarding()]).then(([stateResult, sellerResult]) => {
    if (stateResult.status !== RESULT.OK) {
      renderServerError(status, stateResult, "Onboarding state is unavailable");
      if (formRegion) formRegion.hidden = true;
      return;
    }
    if (sellerResult.status !== RESULT.OK) {
      renderServerError(status, sellerResult, "Your seller profile is unavailable");
      if (formRegion) formRegion.hidden = true;
      return;
    }
    const state = stateResult.payload ?? {};
    const seller = sellerResult.payload?.seller ?? {};
    const profile = sellerResult.payload?.profile ?? null;
    const entitled = sellerResult.payload?.entitled === true;
    const creator = state.verification?.creator ?? {};
    const publishEntry = (sellerResult.payload?.eligibility?.capabilities ?? [])
      .find((capability) => capability.key === "CREATOR_PUBLISH");
    status.innerHTML = `
      <div class="panel">
        <div class="panel-head"><div><h3>Seller profile</h3>
        <p class="small muted">Profile completion and verification are different facts — this page only completes the profile.</p></div></div>
        <div class="state-actions">
          ${statusChip(seller.completed ? "Complete" : "Not started", seller.completed ? "current" : "planned")}
          ${statusChip(profile ? `Profile: ${profile.handle}` : "No creator profile", profile ? "current" : "planned")}
          ${statusChip(`Verification: ${creator.status ?? "UNVERIFIED"}`, "planned")}
          ${statusChip("Publishing: not implemented", "planned")}
        </div>
        <p class="small muted">${escapeText(creator.note ?? "A developer-controlled marker, not identity or KYC verification.")}</p>
        <p class="small muted">Marketplace publishing is a separate capability and stays unavailable until a listing pipeline exists — completing this form never publishes anything.</p>
        ${!entitled && !profile
          ? `<p class="small"><strong>Note:</strong> creating a seller profile requires the Creator tools, which CraftMind grants through membership. No plan is purchasable in this phase — if you already hold the entitlement, the account service will accept your answers.</p>`
          : ""}
      </div>`;
    if (formRegion) {
      formRegion.hidden = false;
      const form = formRegion.querySelector("[data-seller-form-element]");
      form.elements.handle.value = profile?.handle ?? "";
      form.elements.displayName.value = profile?.displayName ?? "";
      form.elements.bio.value = profile?.bio ?? "";
      form.elements.avatarReference.value = profile?.avatarReference ?? "";
      form.elements.category.value = profile?.category ?? "";
      form.elements.referralSource.value = seller.referralSource ?? "";
      form.elements.referralDetail.value = seller.referralDetail ?? "";
      form.elements.minecraftVersions.value = (seller.minecraftVersions ?? []).join(", ");
      const options = state.options?.editions ?? [];
      const container = formRegion.querySelector("[data-editions-region]");
    // The referral list is the server's, not this page's: rebuild the select from the response so a vocabulary
    // change (or a new server version) can never diverge from what the backend actually accepts.
    const referralSelect = form.elements.referralSource;
    const selectedReferral = referralSelect.value;
    if (Array.isArray(state.options?.referralSources) && state.options.referralSources.length > 0) {
      referralSelect.innerHTML = referralOptionsMarkup(selectedReferral || null);
    }
      container.innerHTML = editionSectionMarkup(
        options,
        seller.editions ?? [],
        seller.minecraftVersions ?? [],
        seller.loaders ?? [],
      );
      const agreementVersion = state.options?.agreement?.version ?? "";
      form.elements.agreementVersion.value = agreementVersion;
      const agreementText = formRegion.querySelector("[data-agreement-version]");
      if (agreementText) agreementText.textContent = agreementVersion;
      wireSellerForm(formRegion, account, agreementVersion);
    }
  });
}

function wireSellerForm(formRegion, account, agreementVersion) {
  const form = formRegion.querySelector("[data-seller-form-element]");
  const feedback = formRegion.querySelector("[data-seller-feedback]");
  const count = formRegion.querySelector("[data-edition-count]");
  form.addEventListener("change", (event) => {
    if (count && event.target.matches("[data-edition-toggle]")) {
      count.textContent = String(form.querySelectorAll('input[name="editions"]:checked').length);
    }
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const checked = (name) => [...form.querySelectorAll(`input[name="${name}"]:checked`)].map((input) => input.value);
    const editions = checked("editions");
    const loaders = checked("loaders");
    const minecraftVersions = form.elements.minecraftVersions.value
      .split(",").map((value) => value.trim()).filter(Boolean);
    const handle = form.elements.handle.value.trim();
    const displayName = form.elements.displayName.value.trim();
    const referralSource = form.elements.referralSource.value;
    const agreementAccepted = form.elements.agreementAccepted.checked;
    if (!handle || !displayName || !referralSource || editions.length === 0 || minecraftVersions.length === 0 || loaders.length === 0) {
      feedback.innerHTML = `<p class="field-error" role="alert">${escapeText("Fill in the handle and display name, pick at least one edition, loader, and version, and choose a referral source.")}</p>`;
      return;
    }
    if (!agreementAccepted) {
      feedback.innerHTML = `<p class="field-error" role="alert">${escapeText("Accept the creator agreement to continue.")}</p>`;
      return;
    }
    const answers = {
      handle, displayName, referralSource,
      editions, minecraftVersions, loaders,
      agreementAccepted: true, agreementVersion,
    };
    const bio = form.elements.bio.value.trim();
    const avatarReference = form.elements.avatarReference.value.trim();
    const category = form.elements.category.value.trim();
    const referralDetail = form.elements.referralDetail.value.trim();
    if (bio) answers.bio = bio;
    if (avatarReference) answers.avatarReference = avatarReference;
    if (category) answers.category = category;
    if (referralDetail) answers.referralDetail = referralDetail;
    feedback.innerHTML = `<p class="field-hint" role="status">${escapeText("Saving…")}</p>`;
    const result = await account.saveSellerOnboarding(answers);
    if (result.status !== RESULT.OK) {
      feedback.innerHTML = "";
      renderServerError(formRegion, result, "Seller answers were not saved");
      return;
    }
    const saved = result.payload?.seller ?? {};
    const profile = result.payload?.profile ?? null;
    renderState(formRegion, {
      kind: STATE.SUCCESS,
      title: "Seller answers saved",
      message: `Your creator profile${profile?.handle ? ` “${profile.handle}”` : ""} is complete on the account service${saved.completedAt ? ` since ${formatTimestamp(saved.completedAt)}` : ""}.`,
      details: [
        `Verification status: ${profile?.verification ?? "UNVERIFIED"} — a developer-controlled marker, not identity verification.`,
        "Publishing listings is not implemented yet: a completed profile does not publish anything, and Trusted Seller status is separate.",
        "No payout, price, or financial detail was collected — no payment mechanism exists in this phase.",
      ],
      action: { label: "Continue to the creator studio", href: "../creator/index.html" },
    });
    announce("Seller answers saved.");
  });
}
