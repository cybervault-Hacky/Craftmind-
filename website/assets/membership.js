/**
 * Membership page controller (Phase 21).
 *
 * Plans are product UI only. No price is final, no billing exists, and no subscription can be started from this site —
 * so there is no fabricated activation, no expiry date, and no invented limit. The monthly/yearly control is a real
 * piece of interface state that a future billing service will read; today it changes the label of every plan's price row
 * and nothing else. Plan availability is stated in words next to each plan.
 */

import { createAdapters } from "./adapters.js";
import { metricsMarkup } from "./components.js";
import { STATE, announce, ensureLiveRegion, escapeText, renderState } from "./state.js";

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

function renderMembershipState(root) {
  const region = root.querySelector("[data-membership-state]");
  if (!region) return;
  const adapters = createAdapters();
  if (!adapters.membership.configured) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No plan is connected to this site",
      message: "Membership, billing, and entitlements are not implemented. There is no plan to read, upgrade, downgrade, or cancel, and this page will not pretend otherwise.",
      details: [
        "CraftMind is free to use today: no paid tier exists and nothing here charges you.",
        "When a membership service exists, this panel will show your current plan, its status, and its renewal state.",
      ],
    });
    return;
  }
  renderState(region, {
    kind: STATE.UNAUTHORIZED,
    title: "Sign in to see your plan",
    message: "A membership service is configured for this deployment, but this browser has no active account session.",
  });
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
    renderMembershipState(root);
  };
  for (const button of buttons) button.addEventListener("click", () => apply(button.dataset.period));
  for (const action of root.querySelectorAll("[data-plan-action]")) {
    action.disabled = true;
  }
  const metrics = root.querySelector("[data-membership-metrics]");
  if (metrics) {
    metrics.innerHTML = metricsMarkup([
      { value: "Free", label: "Current CraftMind cost", note: "No paid tier exists yet." },
      { value: null, label: "Renewal date", note: "Nothing to renew — no subscription exists." },
      { value: null, label: "Credits", note: "No credit system is implemented." },
      { value: null, label: "Invoices", note: "No invoicing exists." },
    ]);
  }
  apply("monthly");
}
