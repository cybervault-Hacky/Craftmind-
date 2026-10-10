/**
 * Password-recovery controller (Phase 38).
 *
 * Both halves of web recovery already existed and neither could reach the other: `account.js` could *request* a code
 * on the security page, `adapters.js` could *consume* one, and the service would honour it — but no screen asked for
 * the code, so a browser sign-up could never finish recovery that an Android sign-up could. This module is that
 * screen. It is deliberately reachable while signed out (recovery is what you do when you have no session) and it
 * lives outside the account shell for the same reason.
 *
 * Why the code is typed, not clicked. The delivery boundary in `backend/src/email-delivery.js` sends a one-time code
 * and its own copy tells the reader to "enter this one-time code"; there is no emailed link with a token embedded in
 * it, and none is added here. Accepting a credential from `?token=` would put it in the address bar, the browser
 * history, the navigation referrer, and any access log between here and the visitor — the exact places this project
 * keeps one-time credentials out of. So the code travels one way only: from the visitor's clipboard into a form field,
 * into one POST body, and nowhere else. Nothing is written to browser storage, nothing is logged, and no value is
 * echoed back into the page.
 *
 * The rules the rest of the site keeps, this module keeps: every request goes through the single `fetch` boundary in
 * `adapters.js`; the server decides what happened and the page only renders that answer; and an unavailable
 * dependency is stated as unavailable instead of imitated.
 */

import { ACCOUNT_ENDPOINTS, REASON, RESULT, createAdapters } from "./adapters.js";
import { STATE, announce, escapeText, renderState } from "./state.js";

/**
 * Sanity bounds mirrored from `findOneTimeToken` in `backend/src/accounts.js`, which rejects anything outside them
 * before it looks a code up. They exist so an obviously short paste (a truncated copy) gets an instant, local answer
 * instead of a round trip; they cannot refuse a code the service would have accepted, because the service enforces
 * the same window. `newToken()` issues 32 random bytes as base64url, i.e. 43 characters.
 */
const CODE_MINIMUM_LENGTH = 32;
const CODE_MAXIMUM_LENGTH = 128;

/** The service's own password rule, from `validatePassword` in `backend/src/passwords.js`. */
const PASSWORD_MINIMUM_LENGTH = 10;
const PASSWORD_MAXIMUM_LENGTH = 256;
const PASSWORD_CLASS_PATTERNS = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^A-Za-z0-9]/];

/**
 * The service never says whether an address exists, so neither does this page. Every branch of the request panel
 * answers with the same sentence; a different message for an unknown address would be an account oracle.
 */
const NEUTRAL_REQUEST_RECEIPT =
  "If that address matches an account, a recovery message is on its way. This page cannot confirm that an account exists or that a message was delivered.";

/** Recovery codes are single-use, so a second submission can only fail. The lock is the guard; the disabled attribute is the notice. */
function createSubmitLock() {
  let busy = false;
  return {
    get busy() {
      return busy;
    },
    /** Takes the lock, or refuses. Returns `false` when a submission is already in flight. */
    acquire(button) {
      if (busy) return false;
      busy = true;
      if (button) button.disabled = true;
      return true;
    },
    release(button) {
      busy = false;
      if (button) button.disabled = false;
    },
    /** Keeps the control locked after an operation that consumed the code. */
    seal(button) {
      busy = true;
      if (button) button.disabled = true;
    },
  };
}

function adapterIsUsable(adapter) {
  return Boolean(adapter && adapter.configured);
}

function renderUnconfigured(region, { title, message }) {
  if (!region) return;
  renderState(region, {
    kind: STATE.UNAVAILABLE,
    title,
    message,
    details: [
      "A deployer connects the service by defining CRAFTMIND_SITE_CONFIG.accountServiceOrigin before the site scripts load.",
      `Recovery uses two of the implemented endpoints: ${ACCOUNT_ENDPOINTS.length} endpoints exist in total, and this page calls only the request and confirm pair.`,
    ],
  });
}

function fieldErrorMarkup(message) {
  return `<p class="field-error" role="alert">${escapeText(message)}</p>`;
}

function statusMarkup(message) {
  return `<p class="field-hint" role="status">${escapeText(message)}</p>`;
}

/** Validates the confirm form locally. The service re-checks everything and remains the authority. */
function readConfirmForm(form) {
  const code = (form.elements.code?.value ?? "").trim();
  const newPassword = form.elements.newPassword?.value ?? "";
  const repeated = form.elements.confirmPassword?.value ?? "";
  const problems = {};
  if (!code) problems.code = "Paste the recovery code from the CraftMind message.";
  else if (code.length < CODE_MINIMUM_LENGTH) problems.code = `That code looks cut short — the one in the message is 43 characters long.`;
  else if (code.length > CODE_MAXIMUM_LENGTH) problems.code = "That is longer than a CraftMind recovery code can be. Paste only the code.";
  if (!newPassword) problems.newPassword = "Choose a new password.";
  else if (newPassword.length < PASSWORD_MINIMUM_LENGTH) problems.newPassword = `Use at least ${PASSWORD_MINIMUM_LENGTH} characters.`;
  else if (newPassword.length > PASSWORD_MAXIMUM_LENGTH) problems.newPassword = `That password is longer than ${PASSWORD_MAXIMUM_LENGTH} characters, which the service will not accept.`;
  else if (PASSWORD_CLASS_PATTERNS.filter((pattern) => pattern.test(newPassword)).length < 2) {
    problems.newPassword = "Mix at least two kinds of character — letters, digits, or symbols.";
  }
  if (!repeated) problems.confirmPassword = "Repeat the new password.";
  else if (!problems.newPassword && repeated !== newPassword) problems.confirmPassword = "The two passwords do not match.";
  return { code, newPassword, problems };
}

/** Marks each rejected field for assistive technology as well as for the eye. */
function applyFieldValidity(form, problems) {
  for (const name of ["code", "newPassword", "confirmPassword"]) {
    const field = form.elements[name];
    if (!field) continue;
    if (problems[name]) field.setAttribute("aria-invalid", "true");
    else field.removeAttribute("aria-invalid");
  }
}

function confirmPanelMarkup() {
  return `
    <div class="panel">
      <div class="panel-head"><div><h3>Enter your recovery code</h3>
      <p class="small muted">Paste the one-time code from the CraftMind recovery message and choose a new password. The service sets the password and revokes every existing session, including the one that requested this code.</p></div></div>
      <form class="stack" data-recovery-form novalidate>
        <div class="field">
          <label for="recovery-code">Recovery code</label>
          <input class="input" id="recovery-code" name="code" type="text" autocomplete="one-time-code" spellcheck="false" required aria-describedby="recovery-code-hint recovery-feedback">
          <span class="field-hint" id="recovery-code-hint">The 43-character code from the message. It works once and expires at the time that message states.</span>
        </div>
        <div class="field">
          <label for="recovery-password">New password</label>
          <input class="input" id="recovery-password" name="newPassword" type="password" autocomplete="new-password" required aria-describedby="recovery-password-hint recovery-feedback">
          <span class="field-hint" id="recovery-password-hint">At least ${PASSWORD_MINIMUM_LENGTH} characters using two kinds of character. The service applies this same rule.</span>
        </div>
        <div class="field">
          <label for="recovery-password-confirm">Repeat new password</label>
          <input class="input" id="recovery-password-confirm" name="confirmPassword" type="password" autocomplete="new-password" required aria-describedby="recovery-password-confirm-hint recovery-feedback">
          <span class="field-hint" id="recovery-password-confirm-hint">Checked here only to catch a typing slip; the service never sees this field.</span>
        </div>
        <div class="state-actions">
          <button type="submit" class="button button-primary" data-recovery-submit>Change password</button>
          <a class="button button-secondary" href="signin.html">Back to sign in</a>
        </div>
        <div id="recovery-feedback" data-recovery-feedback></div>
      </form>
    </div>
    <p class="price-note">Nothing is stored by this page. The code and the new password are sent once to the configured account service and dropped when the request completes: they are never written to the address bar, browser storage, or a log, and no CraftMind page reads a recovery code from a URL parameter.</p>`;
}

/**
 * The server's own typed error, translated into what the visitor can act on. Each branch says what to do next; none
 * of them repeats back anything the visitor typed, because the field the error refers to is the code itself.
 */
function recoveryFeedbackMarkup(result) {
  if (result.reason === REASON.UNREACHABLE) {
    return fieldErrorMarkup("The account service could not be reached, so no password was changed. Your code is still unused — try again when the service is reachable.");
  }
  const code = result.payload?.error?.code ?? null;
  const serverMessage = result.payload?.error?.message ?? result.message;
  switch (code) {
    case "PASSWORD_RESET_TOKEN_EXPIRED":
      return fieldErrorMarkup("That recovery code has expired. Request a new one below and paste it here — this one can no longer change a password.");
    case "PASSWORD_RESET_TOKEN_USED":
      return fieldErrorMarkup("That recovery code has already been used. Each code works once, so request a new one if you still need to change your password.");
    case "PASSWORD_RESET_TOKEN_INVALID":
      return fieldErrorMarkup("That recovery code was not recognised. Check that all of it is pasted with no spaces or line breaks. If it is an old code, request a new one below.");
    case "INVALID_PASSWORD":
      return fieldErrorMarkup(`${serverMessage} Use at least ${PASSWORD_MINIMUM_LENGTH} characters with two kinds of character.`);
    default:
      return fieldErrorMarkup(serverMessage || "The account service refused the request, so no password was changed.");
  }
}

function wireConfirmPanel(region, account) {
  const form = region.querySelector("[data-recovery-form]");
  const feedback = region.querySelector("[data-recovery-feedback]");
  const submit = region.querySelector("[data-recovery-submit]");
  if (!form || !feedback) return;
  const lock = createSubmitLock();

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (lock.busy) return;
    const { code, newPassword, problems } = readConfirmForm(form);
    applyFieldValidity(form, problems);
    const firstProblem = Object.values(problems)[0];
    if (firstProblem) {
      feedback.innerHTML = fieldErrorMarkup(firstProblem);
      return;
    }
    if (!adapterIsUsable(account)) {
      feedback.innerHTML = fieldErrorMarkup("No account service is configured for this site, so no password can be changed here.");
      return;
    }
    if (!lock.acquire(submit)) return;
    feedback.innerHTML = statusMarkup("Changing your password…");
    // The body keys are the contract: `token` and `newPassword`, which is what confirmPasswordRecovery requires and
    // what a wrong pair used to make impossible (see defect A1). The form field is named `code` because that is what
    // the message calls it; the key the service reads is `token`.
    const result = await account.confirmPasswordReset({ token: code, newPassword });
    if (result.status !== RESULT.OK) {
      lock.release(submit);
      feedback.innerHTML = recoveryFeedbackMarkup(result);
      announce("The password was not changed.");
      return;
    }
    // Success replaces the whole panel, so the form, its values, and the submit control all leave the document.
    const revoked = result.payload?.revokedSessions;
    const reset = result.payload?.reset === true;
    lock.seal(submit);
    renderState(region, {
      kind: STATE.SUCCESS,
      title: "Password changed",
      message: reset
        ? typeof revoked === "number"
          ? `The account service set your new password and revoked ${revoked} existing session${revoked === 1 ? "" : "s"}. Sign in with the new password to continue.`
          : "The account service set your new password and revoked your existing sessions. Sign in with the new password to continue."
        : "The account service accepted the new password. Its response did not confirm the change, so sign in to be sure.",
      details: [
        "This recovery code is spent and cannot be used again.",
        "No copy of the code or the password was kept by this page.",
      ],
      action: { label: "Go to sign in", href: "signin.html" },
    });
    announce("Password changed. Sign in with your new password.");
  });
}

function requestPanelMarkup() {
  return `
    <div class="panel">
      <div class="panel-head"><div><h3>Request a recovery code</h3>
      <p class="small muted">Only if you have not received a code, or the one you have has expired. Available without signing in.</p></div></div>
      <form class="stack" data-recovery-request-form novalidate>
        <div class="field">
          <label for="recovery-request-email">Account email</label>
          <input class="input" id="recovery-request-email" name="email" type="email" autocomplete="email" required aria-describedby="recovery-request-email-hint recovery-request-feedback">
          <span class="field-hint" id="recovery-request-email-hint">A message is sent only if an eligible account uses this address; the answer is the same either way.</span>
        </div>
        <div class="state-actions">
          <button type="submit" class="button button-secondary" data-recovery-request-submit>Send me a recovery code</button>
          <a class="button button-secondary" href="#recovery-confirm">I have a code already</a>
        </div>
        <div id="recovery-request-feedback" data-recovery-request-feedback></div>
      </form>
    </div>`;
}

function wireRequestPanel(region, account) {
  const form = region.querySelector("[data-recovery-request-form]");
  const feedback = region.querySelector("[data-recovery-request-feedback]");
  const submit = region.querySelector("[data-recovery-request-submit]");
  if (!form || !feedback) return;
  const lock = createSubmitLock();

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (lock.busy) return;
    const email = (form.elements.email?.value ?? "").trim();
    if (!email) {
      form.elements.email?.setAttribute("aria-invalid", "true");
      feedback.innerHTML = fieldErrorMarkup("Enter the account email address.");
      return;
    }
    form.elements.email?.removeAttribute("aria-invalid");
    if (!adapterIsUsable(account)) {
      feedback.innerHTML = fieldErrorMarkup("No account service is configured for this site, so no recovery code can be requested here.");
      return;
    }
    if (!lock.acquire(submit)) return;
    feedback.innerHTML = statusMarkup("Sending the request…");
    const result = await account.requestPasswordReset({ email });
    lock.release(submit);
    if (result.status !== RESULT.OK) {
      feedback.innerHTML = recoveryFeedbackMarkup(result);
      return;
    }
    // The service answers 202 without saying whether an account exists, and mail delivery is best-effort with no
    // durable queue, so this says "accepted for processing" — never "we emailed you".
    feedback.innerHTML = `<div role="status">${statusMarkup(NEUTRAL_REQUEST_RECEIPT)}</div>`;
    announce("Recovery request accepted for processing.");
  });
}

/**
 * Wires the recovery screen.
 *
 * @param {Element} [root] the page element the controllers render into
 * @param {{origin?: string|null}} [options] `origin` is the same programmatic override `createAdapters` already
 *   documents for the contract suite and a local preview. `site.js` never passes it, so a visitor's origin still has
 *   to come from `CRAFTMIND_SITE_CONFIG` and still has to satisfy the HTTPS rule — this seam widens what a test can
 *   do, not what a page can.
 */
export function initRecovery(root = document.querySelector('[data-page="recovery"]'), { origin = null } = {}) {
  if (!root) return;
  const { account } = createAdapters({ origin });
  const region = root.querySelector("[data-recovery-region]");
  const requestRegion = root.querySelector("[data-recovery-request-region]");
  if (!region) return;
  if (!adapterIsUsable(account)) {
    renderUnconfigured(region, {
      title: "No account service is configured for this site",
      message: "Recovery needs the CraftMind account service, and this deployment has not been pointed at one, so no code can be requested or consumed here. This page is not a fallback that pretends otherwise.",
    });
    renderUnconfigured(requestRegion, {
      title: "No code can be sent from this site",
      message: "The request endpoint belongs to the same unconfigured account service. Nothing was queued or sent.",
    });
    return;
  }
  region.innerHTML = confirmPanelMarkup();
  wireConfirmPanel(region, account);
  if (requestRegion) {
    requestRegion.innerHTML = requestPanelMarkup();
    wireRequestPanel(requestRegion, account);
  }
}
