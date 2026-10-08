/**
 * Creator identity controller (Phase 23).
 *
 * Three regions, one honest rule each:
 *
 *   * **`[data-creator-status]`** (creator studio dashboard and public-profile page) reports the account's real creator
 *     state — whether a profile exists, its status, its verification marker, and which capabilities the server grants.
 *     With no service configured, no session, or a failed read it falls back to the Phase 21 unavailable and
 *     unauthorized states, and it never invents a handle, a status, or a verification badge.
 *   * **`[data-public-creator]`** (the public creator page) renders a real public profile when the address carries
 *     `?handle=`. Without one it keeps the layout explanation it shipped with, because a profile page with no profile
 *     named is a layout preview, not a creator.
 *   * **The profile form** stays disabled. The service implements profile creation and updates, but this page does not
 *     write through a browser form yet, so the copy says exactly that instead of pretending the fields save.
 *
 * Nothing here writes: every call is a read through `adapters.js`, the only module that performs a network request.
 */

import { createAdapters, RESULT } from "./adapters.js";
import { STATE, escapeText, formatTimestamp, orDash, renderState } from "./state.js";

function platformNote() {
  return [
    "Creator membership, entitlement, profile ownership, and verification markers are implemented and server-authoritative.",
    "Listings, orders, earnings, reviews, and analytics do not exist yet: those surfaces stay empty rather than estimated.",
    "No payment provider is connected to this site or the service, and no paid plan is purchasable.",
  ];
}

function capabilityList(capabilities) {
  if (!Array.isArray(capabilities) || capabilities.length === 0) return "";
  const rows = capabilities.map((capability) => {
    const available = capability.available === true;
    const label = escapeText(capability.label ?? capability.key ?? "Capability");
    const note = capability.state === "FUTURE"
      ? "Not implemented yet"
      : (available ? "Available" : escapeText(capability.reason ?? "Unavailable"));
    return `<tr><th scope="row">${label}</th><td>${available ? '<span class="badge badge--current">Available</span>' : `<span class="badge badge--planned">${escapeText(capability.state === "FUTURE" ? "Not yet" : "Unavailable")}</span>`}</td><td class="small muted">${note}</td></tr>`;
  }).join("");
  return `
    <div class="table-scroll">
      <table class="data-table">
        <caption>Capabilities the server reports for this account</caption>
        <thead><tr><th scope="col">Capability</th><th scope="col">State</th><th scope="col">What that means</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    </div>`;
}

function profileMarkup(profile, eligibility) {
  const state = profile ?? {};
  const verification = eligibility?.profile?.verificationLabel ?? state.verificationLabel ?? "Not verified";
  const statusLabel = eligibility?.profile?.statusLabel ?? state.statusLabel ?? state.status ?? "Unknown";
  return `
    <div class="panel">
      <div class="panel-head">
        <div>
          <h3>${escapeText(state.displayName ?? "Your creator profile")}</h3>
          <p class="small muted">Read from the account service for the signed-in account. Only the server decides a profile, its status, or its verification.</p>
        </div>
        <span class="badge ${state.status === "ACTIVE" ? "badge--current" : "badge--planned"}">${escapeText(statusLabel)}</span>
      </div>
      <dl class="kv">
        <dt>Handle</dt><dd>${escapeText(state.handle ? `@${state.handle}` : "—")}</dd>
        <dt>Verification</dt><dd>${escapeText(verification)}</dd>
        <dt>Category</dt><dd>${escapeText(state.category ?? "Not set")}</dd>
        <dt>Bio</dt><dd>${escapeText(state.bio && state.bio.length > 0 ? state.bio : "No bio yet")}</dd>
        <dt>Created</dt><dd>${escapeText(formatTimestamp(state.createdAt))}</dd>
        <dt>Updated</dt><dd>${escapeText(formatTimestamp(state.updatedAt) || orDash(state.updatedAt))}</dd>
      </dl>
      <p class="small muted">Verification here is an internal CraftMind marker. It is not identity, government, document, biometric, or payment verification, and no such check has been performed.</p>
      ${capabilityList(eligibility?.capabilities)}
    </div>`;
}

async function renderCreatorStatus(region) {
  const adapters = createAdapters();
  const creator = adapters.creatorProfile;
  if (!creator.configured) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No creator service is connected to this site",
      message: "This deployment is not pointed at a CraftMind account service, so there is no creator state to read here. The service itself exists: creator profiles, status, ownership, and verification are implemented server-side.",
      details: platformNote(),
    });
    return;
  }
  if (!creator.signedIn) {
    renderState(region, {
      kind: STATE.UNAUTHORIZED,
      title: "Sign in to see your creator state",
      message: "A creator profile belongs to an account. Sign in on the account page and this panel reads your profile, status, and capabilities from the server.",
      action: { label: "Go to account sign-in", href: "../../account/index.html" },
    });
    return;
  }
  renderState(region, {
    kind: STATE.LOADING,
    title: "Reading your creator state",
    message: "Requesting your creator profile and capabilities from the account service.",
  });
  const [profileResult, eligibilityResult] = await Promise.all([
    creator.loadProfile(),
    creator.loadEligibility(),
  ]);
  if (profileResult.status !== RESULT.OK || eligibilityResult.status !== RESULT.OK) {
    const unauthorized = profileResult.status === RESULT.UNAUTHORIZED || eligibilityResult.status === RESULT.UNAUTHORIZED;
    renderState(region, {
      kind: unauthorized ? STATE.UNAUTHORIZED : STATE.ERROR,
      title: unauthorized ? "Sign in again to read your creator state" : "Your creator state could not be read",
      message: unauthorized
        ? "The account session is no longer valid, so no creator state can be shown."
        : (profileResult.message ?? eligibilityResult.message ?? "The account service did not answer."),
    });
    return;
  }
  const profile = profileResult.payload?.profile ?? null;
  const eligibility = eligibilityResult.payload?.eligibility ?? null;
  region.dataset.state = STATE.POPULATED;
  if (!profile) {
    const entitled = eligibility?.entitlement?.granted === true;
    region.innerHTML = `
      <div class="panel">
        <div class="panel-head">
          <div>
            <h3>No creator profile yet</h3>
            <p class="small muted">Creator identity is owned by the account and stored by the server. Nothing has been created for this account.</p>
          </div>
          <span class="badge badge--planned">${entitled ? "Entitled" : "No creator entitlement"}</span>
        </div>
        <p class="small">${entitled
          ? "This account holds the Creator entitlement, so it may create a profile. Profile creation is implemented on the account service and is not yet performed from this page."
          : "This account does not hold the Creator entitlement, so no creator profile can be created. Creator membership is granted by an authorized developer in this phase; no plan is purchasable."}</p>
        <p class="small muted">Nothing is invented here: no handle, no status, and no verification badge is shown until the server reports one.</p>
      </div>`;
    return;
  }
  region.innerHTML = profileMarkup(profile, eligibility);
}

async function renderPublicCreator(region) {
  const adapters = createAdapters();
  const creator = adapters.creatorProfile;
  const handle = new URLSearchParams(globalThis.location?.search ?? "").get("handle");
  if (!creator.configured) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "Public creator profiles are not connected on this deployment",
      message: "This site is not pointed at a CraftMind account service, so no public creator profile can be read here.",
      details: platformNote(),
    });
    return;
  }
  if (!handle) {
    renderState(region, {
      kind: STATE.EMPTY,
      title: "Name a creator to see their public profile",
      message: "This page is the public profile layout. It reads a real profile when the address names one, for example ?handle=your-handle. Without a handle it shows no creator, so nothing here can be mistaken for a real person.",
      details: platformNote(),
    });
    return;
  }
  renderState(region, {
    kind: STATE.LOADING,
    title: "Reading the public profile",
    message: "Requesting the public creator projection from the account service.",
  });
  const result = await creator.loadPublicProfile(handle);
  if (result.status !== RESULT.OK) {
    renderState(region, {
      kind: result.status === RESULT.UNAVAILABLE ? STATE.UNAVAILABLE : STATE.ERROR,
      title: "That creator profile is not available",
      message: "No publicly readable profile matches this address. A profile that is not active is not published, and an unknown handle cannot be distinguished from one.",
      details: ["No suspension reason is published, and no account detail is exposed."],
    });
    return;
  }
  const profile = result.payload?.profile ?? {};
  region.dataset.state = STATE.POPULATED;
  region.innerHTML = `
    <div class="panel">
      <div class="panel-head">
        <div>
          <h3>${escapeText(profile.displayName ?? "Creator")}</h3>
          <p class="small muted">${escapeText(profile.handle ? `@${profile.handle}` : "")}</p>
        </div>
        <span class="badge ${profile.verified ? "badge--current" : "badge--planned"}">${profile.verified ? "Verified creator" : "Not verified"}</span>
      </div>
      <p>${escapeText(profile.bio && profile.bio.length > 0 ? profile.bio : "This creator has not written a bio yet.")}</p>
      <dl class="kv">
        <dt>Category</dt><dd>${escapeText(profile.category ?? "Not set")}</dd>
        <dt>On CraftMind since</dt><dd>${escapeText(formatTimestamp(profile.createdAt))}</dd>
      </dl>
      <p class="small muted">This is the public projection only: a display name, a handle, a bio, a category, and the verification marker. No email, account identifier, plan, credit balance, or moderation detail is published. Listings and ratings arrive with the marketplace phase.</p>
    </div>`;
}

export async function initCreatorProfile() {
  const status = document.querySelector("[data-creator-status]");
  const publicRegion = document.querySelector("[data-public-creator]");
  if (status) await renderCreatorStatus(status);
  if (publicRegion) await renderPublicCreator(publicRegion);
}

if (typeof document !== "undefined") {
  void initCreatorProfile();
}
