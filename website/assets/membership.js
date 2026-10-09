/**
 * Membership page controller (Phase 21, extended by Phase 22).
 *
 * The plan cards are product UI. No price is defined, no billing exists, and no subscription can be started from this
 * site, so there is no fabricated activation, no invented limit, and no billing date the page cannot justify. The
 * monthly/yearly control is real interface state that changes the label of every plan's price row and nothing else.
 *
 * Phase 22 adds the part that is now real: when this deployment is pointed at an account service and the visitor has an
 * active session, the panel below reads that account's plan, status, entitlements, and authoritative credit balance
 * from the server. The server decides all of it — the browser renders what it is told and computes nothing. With no
 * service configured, no session, or a failed read, the page keeps the same honest unavailable states it shipped with.
 */

import { createAdapters, RESULT } from "./adapters.js";
import { keyValueMarkup, metricsMarkup } from "./components.js";
import { STATE, announce, ensureLiveRegion, escapeText, formatTimestamp, orDash, renderState } from "./state.js";

/** Plan identifiers are the product vocabulary; the *prices* deliberately do not exist in this phase. */
export const PLANS = Object.freeze([
  {
    id: "FREE",
    name: "Free",
    summary: "The CraftMind experience that exists today: bring your own AI key, plan locally, review every build.",
    availability: "AVAILABLE_TODAY",
    availabilityLabel: "Available today — no charge",
    priceState: "No price applies",
    perks: [
      { text: "Core CraftMind experience with your own AI provider key", state: "today" },
      { text: "Local build history on your device", state: "today" },
      { text: "One supported visual reference per request", state: "today" },
      { text: "Account sign-in against a configured account service", state: "today" },
    ],
  },
  {
    id: "PRO",
    name: "Pro",
    summary: "For builders who want more room: higher generation limits and advanced AI building tools.",
    availability: "PLANNED",
    availabilityLabel: "Planned — not purchasable",
    priceState: "Not priced yet",
    perks: [
      { text: "Higher AI generation limits", state: "planned" },
      { text: "Advanced AI building and refinement", state: "planned" },
      { text: "Premium planning features", state: "planned" },
    ],
  },
  {
    id: "CREATOR",
    name: "Creator",
    summary: "For builders who publish: creator tools, marketplace listings, and analytics.",
    availability: "PLANNED",
    availabilityLabel: "Planned — not purchasable",
    priceState: "Not priced yet",
    perks: [
      { text: "Creator studio and listing tools", state: "planned" },
      { text: "Marketplace listing and publishing", state: "planned" },
      { text: "Creator analytics and reviews", state: "planned" },
    ],
  },
  {
    id: "SERVER",
    name: "Server",
    summary: "For servers and teams: server-oriented features and shared workspace capabilities.",
    availability: "PLANNED",
    availabilityLabel: "Planned — not purchasable",
    priceState: "Not priced yet",
    perks: [
      { text: "Server-oriented planning and build workflows", state: "planned" },
      { text: "Team and shared server capabilities", state: "planned" },
      { text: "Administration for multiple operators", state: "planned" },
    ],
  },
]);

const COMPARISON = Object.freeze([
  ["AI build planning with your own key", "Included", "Planned", "Planned", "Planned"],
  ["Local build history", "Included", "Planned", "Planned", "Planned"],
  ["Higher generation limits", "—", "Planned", "Planned", "Planned"],
  ["Advanced AI building tools", "—", "Planned", "Planned", "Planned"],
  ["Creator studio and listings", "—", "—", "Planned", "Planned"],
  ["Marketplace selling and analytics", "—", "—", "Planned", "Planned"],
  ["Server and team features", "—", "—", "—", "Planned"],
  ["Billing, invoicing, and renewals", "Not applicable", "Not implemented", "Not implemented", "Not implemented"],
]);

const PERIODS = Object.freeze([
  { id: "monthly", label: "Monthly", note: "No monthly price is defined. Billing is not implemented, so no charge can be created for either period." },
  { id: "yearly", label: "Yearly", note: "An annual option is planned. No annual price or discount is defined, and no annual billing exists." },
]);

function planCardMarkup(plan, { period }) {
  const availabilityClass = plan.availability === "AVAILABLE_TODAY" ? "badge--current" : "badge--planned";
  return `
  <article class="plan-card${plan.id === "PRO" ? " plan-card--featured" : ""}" data-plan="${escapeText(plan.id)}" data-period="${escapeText(period)}">
    <div class="plan-head">
      <h3 class="plan-name">${escapeText(plan.name)}</h3>
      <span class="badge ${availabilityClass}">${escapeText(plan.availabilityLabel)}</span>
    </div>
    <div class="plan-price">
      <span class="plan-price-value" data-plan-price="true">${escapeText(plan.priceState)}</span>
      <span class="plan-price-period" data-plan-period-label="true">${escapeText(period === "yearly" ? "per year · period not priced" : "per month · period not priced")}</span>
    </div>
    <p class="small muted">${escapeText(plan.summary)}</p>
    <ul class="plan-perks">
      ${plan.perks.map((perk) => `<li${perk.state === "planned" ? ' data-state="planned"' : ""}>${escapeText(perk.text)}${perk.state === "planned" ? " (planned)" : ""}</li>`).join("")}
    </ul>
    <div class="plan-cta">
      ${plan.availability === "AVAILABLE_TODAY"
        ? '<button type="button" class="button button-secondary button-small" disabled>Current free experience</button>'
        : `<button type="button" class="button button-secondary button-small" data-plan-action="${escapeText(plan.id)}" disabled>Upgrade · not available yet</button>`}
      <p class="price-note" data-plan-note="true"></p>
    </div>
  </article>`;
}

function renderPlans(root, period) {
  const grid = root.querySelector("[data-plan-grid]");
  if (grid) {
    grid.dataset.state = STATE.DISABLED;
    grid.className = "plan-grid";
    grid.innerHTML = PLANS.map((plan) => planCardMarkup(plan, { period })).join("");
  }
  const comparison = root.querySelector("[data-plan-comparison]");
  if (comparison) {
    comparison.innerHTML = `
      <table class="compare-table">
        <caption>Planned capability comparison. “Planned” means the capability is not implemented; it is not a promise of a release date.</caption>
        <thead><tr><th scope="col">Capability</th>${PLANS.map((plan) => `<th scope="col">${escapeText(plan.name)}</th>`).join("")}</tr></thead>
        <tbody>${COMPARISON.map((row) => `<tr><th scope="row">${escapeText(row[0])}</th>${row.slice(1).map((cell) => `<td>${escapeText(cell)}</td>`).join("")}</tr>`).join("")}</tbody>
      </table>`;
  }
  const note = root.querySelector("[data-period-note]");
  if (note) note.textContent = PERIODS.find((candidate) => candidate.id === period)?.note ?? "";
}

/**
 * Renders the visitor's own membership state.
 *
 * Every value comes from the account service. When a value is missing — no session, no configured service, a failed
 * read — the panel says so instead of guessing, and the credit line in particular never invents a number: an unreachable
 * balance is reported as unavailable.
 */
async function renderMembershipState(root) {
  const region = root.querySelector("[data-membership-state]");
  if (!region) return;
  const adapters = createAdapters();
  const membership = adapters.membership;
  if (!membership.configured) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No plan is connected to this site",
      message: "This deployment is not pointed at a CraftMind account service, so there is no plan of yours to read here. The service itself exists — membership, entitlements, and build credits are implemented server-side.",
      details: [
        "CraftMind is free to use today: no paid tier exists and nothing here charges you.",
        "Pro, Creator, and Server are defined but not purchasable. No payment provider is connected to this site or the service.",
        "Connected to a service, this panel shows the account's real plan, status, end date, entitlements, and credit balance.",
      ],
    });
    return;
  }
  if (!membership.signedIn) {
    renderState(region, {
      kind: STATE.UNAUTHORIZED,
      title: "Sign in to see your plan",
      message: "This deployment is connected to a CraftMind account service, and the plan belongs to an account. Sign in on the account page and this panel reads your membership and credit balance from the server.",
      action: { label: "Go to account sign-in", href: "../account/index.html" },
    });
    return;
  }
  renderState(region, {
    kind: STATE.LOADING,
    title: "Reading your plan",
    message: "Requesting your membership state and credit balance from the account service.",
  });
  const [membershipResult, creditsResult] = await Promise.all([
    membership.loadMembership(),
    membership.loadCredits(),
  ]);
  if (membershipResult.status !== RESULT.OK || creditsResult.status !== RESULT.OK) {
    const unauthorized = membershipResult.status === RESULT.UNAUTHORIZED || creditsResult.status === RESULT.UNAUTHORIZED;
    renderState(region, {
      kind: unauthorized ? STATE.UNAUTHORIZED : STATE.ERROR,
      title: unauthorized ? "Sign in again to read your plan" : "Your plan could not be read",
      message: unauthorized
        ? "The account session is no longer valid, so no plan or balance can be shown."
        : (membershipResult.message ?? creditsResult.message ?? "The account service did not answer."),
    });
    return;
  }
  const plan = membershipResult.payload?.membership ?? {};
  const catalogue = membershipResult.payload?.plan ?? {};
  const credits = creditsResult.payload?.credits ?? {};
  const balance = Number.isFinite(credits.available) ? `${credits.available} credits` : null;
  region.dataset.state = STATE.POPULATED;
  region.innerHTML = `
    <div class="panel">
      <div class="panel-head">
        <div>
          <h3>${escapeText(plan.planName ?? plan.plan ?? "Your plan")}</h3>
          <p class="small muted">Read from the account service for the signed-in account. Only the server decides a plan, an entitlement, or a balance.</p>
        </div>
        <span class="badge ${plan.plan === "FREE" ? "badge--current" : "badge--planned"}">${escapeText(plan.status ?? "UNKNOWN")}</span>
      </div>
      ${keyValueMarkup([
        ["Plan", plan.plan],
        ["Plan status", plan.status],
        ["Plan ends", plan.endsAt ? formatTimestamp(plan.endsAt) : "No end date — the plan does not expire"],
        ["Availability", catalogue.availabilityLabel],
        ["Purchasable", catalogue.purchasable === true ? "Yes" : "No — no payment is implemented"],
        ["Credit balance", balance ?? "Credit balance unavailable"],
        ["Credits expiring", Number.isFinite(credits.expiring) ? `${credits.expiring} within ${orDash(credits.expiringInDays)} days` : null],
      ])}
      <p class="price-note">Entitlements are resolved by the server for every request; this page never decides what the account may do. Pro, Creator, and Server remain not purchasable.</p>
    </div>`;
  const metrics = root.querySelector("[data-membership-metrics]");
  if (metrics) {
    metrics.innerHTML = metricsMarkup([
      { value: plan.plan ?? null, label: "Current plan", note: "Reported by the account service." },
      { value: plan.endsAt ? formatTimestamp(plan.endsAt) : "No end date", label: "Plan end", note: "Expiry is applied by the server." },
      { value: balance ?? null, label: "Build credits", note: "Authoritative balance, read only for display." },
      { value: null, label: "Invoices", note: "No invoicing exists." },
    ]);
  }
}

export function initMembership() {
  ensureLiveRegion();
  const root = document.querySelector('[data-page="membership"]');
  if (!root) return;
  let period = "monthly";
  const toggle = root.querySelector("[data-period-toggle]");
  const buttons = [...(toggle?.querySelectorAll("button") ?? [])];
  const apply = (next) => {
    period = next;
    for (const button of buttons) button.setAttribute("aria-pressed", String(button.dataset.period === period));
    renderPlans(root, period);
    announce(`${period === "yearly" ? "Yearly" : "Monthly"} view selected. No price is defined for either period.`);

    // Keep the plan-state panel refreshed alongside the plan grid.
    void renderMembershipState(root);
  };
  for (const button of buttons) button.addEventListener("click", () => apply(button.dataset.period));
  for (const action of root.querySelectorAll("[data-plan-action]")) {
    action.disabled = true;
  }
  const metrics = root.querySelector("[data-membership-metrics]");
  if (metrics) {
    // The baseline render is honest about this deployment; a connected and signed-in session replaces it with the
    // server's own figures (see renderMembershipState).
    metrics.innerHTML = metricsMarkup([
      { value: "Free", label: "Current CraftMind cost", note: "No paid tier exists yet." },
      { value: null, label: "Renewal date", note: "Nothing to renew — no subscription exists." },
      { value: null, label: "Build credits", note: "Credit balance unavailable until an account service and a session are available." },
      { value: null, label: "Invoices", note: "No invoicing exists." },
    ]);
  }
  apply("monthly");
}
