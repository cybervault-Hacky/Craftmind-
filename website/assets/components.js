/**
 * Shared markup builders for the Phase 21 surfaces (marketplace, creator, membership, account).
 *
 * These functions only turn *given* values into DOM strings — they never invent a value. Anything absent renders as the
 * em dash from `state.js`, a "no data" label, or an honest empty state. The same builders are used by the marketplace,
 * the creator studio, and the publish preview so a listing looks identical everywhere it appears.
 */

import { escapeAttribute, escapeText, orDash } from "./state.js";

export const PREVIEW_BADGE = '<span class="badge badge--preview">Preview data</span>';
export const PLANNED_BADGE = '<span class="badge badge--planned">Planned</span>';

/** An abstract, obviously non-photographic cover used while no real media exists. */
export function placeholderCoverMarkup(seed = "cover") {
  const tone = String(seed).length % 2 === 0 ? "#b8e4c5" : "#e7bb78";
  return `
    <svg viewBox="0 0 320 180" role="presentation" focusable="false" aria-hidden="true">
      <path d="M40 132 160 66 284 133 161 178Z" fill="#1b2920" stroke="#48634f" stroke-width="1.5" />
      <path d="M78 96 160 50 240 95 161 140Z" fill="#2f5c43" />
      <path d="M78 96v42l83 47v-45Z" fill="#24493a" />
      <path d="M161 140v45l79-45v-45Z" fill="#8a6739" />
      <path d="M78 96 160 50 240 95 161 140Z" fill="none" stroke="${tone}" stroke-opacity=".6" stroke-width="1.6" />
      <path d="M120 118 160 96 200 118 161 141Z" fill="${tone}" fill-opacity=".22" />
      <circle cx="252" cy="52" r="15" fill="none" stroke="#688a70" stroke-width="1.4" />
      <path d="M245 52h14M252 45v14" stroke="#688a70" stroke-width="1.4" />
    </svg>`;
}

/** A creator chip: an initial avatar plus the displayed name. No follower, rating, or listing count is implied. */
export function creatorChipMarkup({ displayName, initials = "•", handle = null }) {
  const name = displayName ?? "Creator not available";
  const wrapper = handle
    ? `<a class="listing-creator" href="${escapeAttribute(handle)}">`
    : '<span class="listing-creator">';
  const closing = handle ? "</a>" : "</span>";
  return `${wrapper}<span class="avatar" aria-hidden="true">${escapeText(initials)}</span><span>${escapeText(name)}</span>${closing}`;
}

function compatibilityBadgesMarkup(compatibility) {
  if (!compatibility) return "";
  const badges = [`<span class="badge">${escapeText(compatibility.status)}</span>`];
  if (compatibility.buildable === false) badges.push('<span class="badge badge--planned">Not buildable</span>');
  return badges.join(" ");
}

function metaItemMarkup(label, value) {
  return `<li><span class="subtle">${escapeText(label)}</span> ${escapeText(orDash(value))}</li>`;
}

/**
 * The canonical listing card. Every field is optional; absent fields render as "—" or are omitted rather than guessed.
 *
 * @param {object} listing
 * @param {object} [options]
 * @param {boolean} [options.preview] renders the Preview badge and disables the save/purchase affordances
 * @param {boolean} [options.saved]
 * @param {string} [options.detailHref] where the title links to
 * @param {string} [options.creatorHref]
 * @param {object} [options.compatibility] the verdict from `describeCompatibility`
 * @param {string} [options.actionLabel] the card's primary affordance label
 */
export function listingCardMarkup(listing, options = {}) {
  const { preview = false, saved = false, detailHref = null, creatorHref = null, compatibility = null, actionLabel = "View details" } = options;
  const cover = listing.cover === "provided"
    ? '<div class="listing-cover"><span class="subtle small">Cover provided by the listing</span></div>'
    : `<div class="listing-cover listing-cover--empty" role="img" aria-label="Abstract placeholder cover. No screenshot has been provided for this listing.">${placeholderCoverMarkup(listing.id ?? listing.title ?? "cover")}<span class="listing-cover-badge">${preview ? PREVIEW_BADGE : '<span class="badge badge--muted">No cover yet</span>'}</span></div>`;
  const title = detailHref
    ? `<a class="listing-title" href="${escapeAttribute(detailHref)}">${escapeText(listing.title ?? "Untitled listing")}</a>`
    : `<h3 class="listing-title">${escapeText(listing.title ?? "Untitled listing")}</h3>`;
  const priceClass = listing.priceLabel && !/not set/i.test(listing.priceLabel) ? "listing-price" : "listing-price listing-price--unset";
  return `
  <article class="listing-card" data-listing-id="${escapeAttribute(listing.id ?? "")}">
    ${cover}
    <div class="listing-body">
      ${title}
      ${creatorChipMarkup({ displayName: listing.creatorDisplayName ?? listing.creator, initials: listing.creatorInitials ?? "•", handle: creatorHref })}
      <p class="listing-summary">${escapeText(listing.summary ?? "No description provided yet.")}</p>
      <ul class="listing-meta">
        ${metaItemMarkup("Edition", listing.edition)}
        ${metaItemMarkup("Version", listing.minecraftVersion)}
        ${metaItemMarkup("Loader", listing.loader)}
        ${metaItemMarkup("Category", listing.category)}
        ${metaItemMarkup("Difficulty", listing.difficulty)}
      </ul>
      ${(listing.tags ?? []).length > 0 ? `<ul class="tag-list">${listing.tags.map((tag) => `<li class="tag">${escapeText(tag)}</li>`).join("")}</ul>` : ""}
      ${compatibilityBadgesMarkup(compatibility)}
    </div>
    <div class="listing-foot">
      <span class="${priceClass}">${escapeText(orDash(listing.priceLabel))}</span>
      <span class="row">
        <button type="button" class="listing-save button-small" aria-pressed="${saved ? "true" : "false"}"
          data-save-listing="${escapeAttribute(listing.id ?? "")}"
          ${preview ? "" : ""} title="${saved ? "Remove from saved" : "Save this listing"}"><span aria-hidden="true">${saved ? "★" : "☆"}</span><span class="visually-hidden">${saved ? "Remove from saved items" : "Save this listing"}</span></button>
        ${detailHref ? `<a class="button button-secondary button-small" href="${escapeAttribute(detailHref)}">${escapeText(actionLabel)}</a>` : ""}
      </span>
    </div>
  </article>`;
}

/** A metrics strip. Values are required: pass `null` to render the honest em dash, never a zero. */
export function metricsMarkup(metrics) {
  return `<div class="metric-grid">${metrics.map((metric) => `
    <div class="metric">
      <span class="metric-value"${metric.value === null || metric.value === undefined ? ' data-unavailable="true"' : ""}>${escapeText(orDash(metric.value))}</span>
      <span class="metric-label">${escapeText(metric.label)}</span>
      ${metric.note ? `<span class="metric-note">${escapeText(metric.note)}</span>` : ""}
    </div>`).join("")}</div>`;
}

/** A key/value block. Every row is rendered, so the reader sees which facts exist and which do not. */
export function keyValueMarkup(rows) {
  const body = rows.map(([term, value]) => `<dt>${escapeText(term)}</dt><dd>${escapeText(orDash(value))}</dd>`).join("");
  return `<dl class="kv">${body}</dl>`;
}

/**
 * A responsive data table. `columns` describe the header and the mobile label; `rows` are arrays of cell values.
 * An empty `rows` array is the caller's signal to render an empty state instead.
 */
export function dataTableMarkup({ caption, columns, rows }) {
  const head = columns.map((column) => `<th scope="col">${escapeText(column.label)}</th>`).join("");
  const body = rows.map((row) => `<tr>${row.map((cell, index) => {
    const column = columns[index] ?? {};
    const tag = column.rowHeader ? "th" : "td";
    const scope = column.rowHeader ? ' scope="row"' : "";
    const value = column.html ? cell : escapeText(orDash(cell));
    return `<${tag}${scope} data-label="${escapeAttribute(column.label)}">${value}</${tag}>`;
  }).join("")}</tr>`).join("");
  return `<div class="table-wrap"><table class="data-table">${caption ? `<caption>${escapeText(caption)}</caption>` : ""}
    <thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`;
}

/** A labelled progress track. The value must be a real 0–100 number; there is no indeterminate decoration. */
export function progressMarkup({ value, label }) {
  const bounded = Math.max(0, Math.min(100, Number(value) || 0));
  return `<div class="progress-track" role="progressbar" aria-label="${escapeAttribute(label)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${bounded}"><div class="progress-bar" style="width:${bounded}%"></div></div>`;
}

/** A price preview row: label plus a value that may legitimately be undefined. */
export function priceRowMarkup({ label, value, total = false }) {
  return `<div class="price-row${total ? " price-row--total" : ""}"><span>${escapeText(label)}</span><b>${escapeText(orDash(value))}</b></div>`;
}

/** A media slot tile for the create-listing media step. The state is stated in text, never by colour alone. */
export function mediaTileMarkup({ label, accepted, maximumBytes, state = "NOT_UPLOADED", count = null }) {
  const megabytes = Number.isFinite(maximumBytes) ? `${(maximumBytes / 1_048_576).toFixed(0)} MB maximum` : "size limit not set";
  const stateLabels = {
    NOT_UPLOADED: "Nothing uploaded",
    VALIDATION_ERROR: "Validation error",
    UNSUPPORTED_FORMAT: "Unsupported format",
    TOO_LARGE: "Too large",
    UPLOADING: "Uploading",
    REMOVED: "Removed",
  };
  return `
  <li class="media-tile" data-media-slot="${escapeAttribute(label.toLowerCase().replaceAll(" ", "-"))}">
    <span class="media-thumb" aria-hidden="true">${escapeText(count === null ? "—" : `${count} file(s)`)}</span>
    <span class="media-tile-name">${escapeText(label)}</span>
    <span class="badge badge--muted">${escapeText(stateLabels[state] ?? state)}</span>
    <span class="field-hint">${escapeText(accepted)} · ${escapeText(megabytes)}</span>
  </li>`;
}

/** A star rating row. With no rating data, it states that plainly instead of drawing empty or filled stars. */
export function ratingMarkup({ average = null, count = 0, label = "rating" }) {
  if (average === null || average === undefined || count === 0) {
    return `<span class="rating"><span class="rating-marks" aria-hidden="true">${"<span>☆</span>".repeat(5)}</span><span>No ratings yet</span></span>`;
  }
  const filled = Math.max(0, Math.min(5, Math.round(average)));
  const marks = Array.from({ length: 5 }, (_, index) => `<span data-filled="${index < filled}">${index < filled ? "★" : "☆"}</span>`).join("");
  return `<span class="rating" role="img" aria-label="${escapeAttribute(`${average.toFixed(1)} out of 5 from ${count} ${label}(s)`)}"><span class="rating-marks" aria-hidden="true">${marks}</span><span>${escapeText(average.toFixed(1))} · ${escapeText(String(count))}</span></span>`;
}
