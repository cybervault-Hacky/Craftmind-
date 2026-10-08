/**
 * The website state system (Phase 21).
 *
 * Every data region on the marketplace, creator, membership, and account surfaces renders through exactly one of the
 * states below. The rule from the Phase 15 design contract is unchanged: a region either shows real data or shows an
 * honest state explaining why it cannot — never a blank box, never a fabricated row.
 *
 * This module is dependency-free, performs no network request, and stores nothing. It only builds DOM.
 */

/** The closed set of region states. "success" is reserved for an operation that really completed. */
export const STATE = Object.freeze({
  LOADING: "loading",
  EMPTY: "empty",
  POPULATED: "populated",
  ERROR: "error",
  UNAUTHORIZED: "unauthorized",
  UNAVAILABLE: "unavailable",
  DISABLED: "disabled",
  SUCCESS: "success",
});

const STATE_BADGES = Object.freeze({
  [STATE.LOADING]: { label: "Loading", tone: "muted" },
  [STATE.EMPTY]: { label: "Nothing yet", tone: "muted" },
  [STATE.ERROR]: { label: "Error", tone: "muted" },
  [STATE.UNAUTHORIZED]: { label: "Sign-in required", tone: "planned" },
  [STATE.UNAVAILABLE]: { label: "Not available yet", tone: "planned" },
  [STATE.DISABLED]: { label: "Planned", tone: "planned" },
  [STATE.SUCCESS]: { label: "Done", tone: "current" },
});

/** Escapes text for safe insertion into markup produced by this module. Never used for attribute values. */
export function escapeText(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

/** Escapes a value meant for a quoted attribute. */
export function escapeAttribute(value) {
  return escapeText(value).replaceAll('"', "&quot;").replaceAll("'", "&#39;");
}

function badge(kind) {
  const badgeConfig = STATE_BADGES[kind];
  if (!badgeConfig) return "";
  return `<span class="badge badge--${badgeConfig.tone}">${escapeText(badgeConfig.label)}</span>`;
}

/**
 * Builds the markup for one state card.
 *
 * @param {object} config
 * @param {string} config.kind one of `STATE`
 * @param {string} config.title the plain-language summary shown as the heading
 * @param {string} config.message what this region is for, and why it is in this state
 * @param {string[]} [config.details] optional list of what will appear here once the capability exists
 * @param {{label: string, href?: string, disabled?: boolean}} [config.action] optional honest call to action
 */
export function stateMarkup({ kind, title, message, details = [], action = null }) {
  const classes = kind === STATE.POPULATED ? "state-card state-card--success" : `state-card state-card--${kind}`;
  const parts = [`<div class="${classes}">`, `<div class="state-head">${badge(kind)}<h3>${escapeText(title)}</h3></div>`];
  if (message) parts.push(`<p>${escapeText(message)}</p>`);
  if (details.length > 0) {
    parts.push(`<ul class="state-list">${details.map((item) => `<li>${escapeText(item)}</li>`).join("")}</ul>`);
  }
  if (action?.label) {
    // A disabled action renders as a real, visibly unavailable control instead of a dead link or a fake success path.
    const disabled = action.disabled ? ' aria-disabled="true" role="link"' : "";
    const href = action.href && !action.disabled ? ` href="${escapeAttribute(action.href)}"` : "";
    const tag = action.href ? "a" : "button";
    const type = tag === "button" ? ' type="button"' : "";
    parts.push(
      `<div class="state-actions"><${tag}${href}${type} class="button button-secondary button-small"${disabled}>` +
      `${escapeText(action.label)}</${tag}></div>`,
    );
  }
  parts.push("</div>");
  return parts.join("");
}

/** Replaces a region's content with one state card. */
export function renderState(region, config) {
  if (!region) return;
  region.dataset.state = config.kind;
  region.innerHTML = stateMarkup(config);
}

/** Renders a loading state built from the shared skeleton component. */
export function renderLoading(region, { rows = 3, title = "Loading" } = {}) {
  if (!region) return;
  region.dataset.state = STATE.LOADING;
  const lines = Array.from({ length: rows }, (_, index) => `<div class="skeleton${index === 0 ? " skeleton-lg" : ""}"></div>`).join("");
  region.innerHTML = `<div class="state-card" role="status" aria-live="polite"><div class="state-head">${badge(STATE.LOADING)}<h3>${escapeText(title)}</h3></div>${lines}</div>`;
}

/** The single polite live region used to announce state changes to screen readers. */
function liveRegion() {
  let region = document.getElementById("site-live-region");
  if (!region) {
    region = document.createElement("div");
    region.id = "site-live-region";
    region.className = "visually-hidden";
    region.setAttribute("role", "status");
    region.setAttribute("aria-live", "polite");
    document.body.append(region);
  }
  return region;
}

/** Announces a short message (state change result) without moving focus. */
/**
 * Guarantees the polite live region exists from first paint, so assistive technology is already watching the page
 * before any controller announces a result.
 */
export function ensureLiveRegion() {
  return liveRegion();
}

export function announce(message) {
  liveRegion().textContent = "";
  liveRegion().textContent = String(message ?? "");
}

/**
 * Lazily resolves a browser `Intl` formatter so a locale-specific date/time rendering never falls back to an invented
 * value: an unparsable timestamp renders as the literal em dash instead.
 */
export function formatTimestamp(value) {
  const parsed = Date.parse(value ?? "");
  if (!Number.isFinite(parsed)) return "—";
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(parsed));
}

/** Renders a value that may legitimately be absent. Absent values show an em dash, never a zero or a guess. */
export function orDash(value) {
  if (value === null || value === undefined || value === "" || (typeof value === "number" && !Number.isFinite(value))) return "—";
  return value;
}
