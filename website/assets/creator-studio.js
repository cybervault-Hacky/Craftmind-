/**
 * Creator studio controllers (Phase 21).
 *
 * Everything a creator sees here is a real piece of interface over an *absent* backend: the dashboard metrics are
 * dashes, the listing tabs render empty states, orders/earnings/reviews/analytics state plainly that no service exists,
 * and the create-listing wizard is a fully working local form whose publish step is honest about there being nowhere to
 * publish to. Draft text lives in memory for the current page only — nothing is uploaded and nothing is stored.
 */

import { createAdapters } from "./adapters.js";
import { BUILD_PLAN_LIMITS, RELEASE_CHANNELS, describeCompatibility, compatibilityOptions, loaderBelongsToEdition } from "./compatibility.js";
import { dataTableMarkup, listingCardMarkup, metricsMarkup } from "./components.js";
import { BUILD_TYPES, DEMO_LISTINGS, DEMO_ORDERS, DEMO_REVIEWS, DEMO_ANALYTICS, DEMO_MEDIA, DIFFICULTIES, CATEGORIES, previewRequested } from "./preview-catalog.js";
import { STATE, announce, ensureLiveRegion, escapeAttribute, escapeText, orDash, renderState } from "./state.js";

const PLACEHOLDER_FEE_PERCENT = 10;
const PLACEHOLDER_FEE_DISCLOSURE =
  "The percentage in this example is an arbitrary illustration of the layout, not the CraftMind fee. CraftMind has not " +
  "defined marketplace economics, so no real commission rate exists in this phase and nothing here is used for a payout.";

function previewNote() {
  return `
  <div class="banner banner--preview banner--section" role="note">
    <span class="banner-mark">PREVIEW DATA</span>
    <p><strong>Creator data is sample data.</strong> No creator backend, no listing storage, no orders, no earnings, and no
    analytics exist. These rows exist to design the layout; the numbers, orders, and reviews are not real.</p>
  </div>`;
}

/* ------------------------------------------------------------------ dashboard */

export function initCreatorDashboard(root = document.querySelector('[data-page="creator-dashboard"]')) {
  ensureLiveRegion();
  if (!root) return;
  const preview = previewRequested();
  const banner = root.querySelector("[data-preview-banner]");
  if (banner) banner.innerHTML = preview ? previewNote() : "";
  const metrics = root.querySelector("[data-creator-metrics]");
  if (metrics) {
    metrics.innerHTML = metricsMarkup([
      { value: 0, label: "Listings", note: "No listing storage exists, so no listing can be counted." },
      { value: 0, label: "Drafts", note: "Drafts live in this page only while you type." },
      { value: 0, label: "Published", note: "Publishing is not implemented." },
      { value: 0, label: "Sales", note: "The marketplace cannot sell anything yet." },
      { value: null, label: "Earnings", note: "No payout or ledger service exists." },
      { value: 0, label: "Orders", note: "No order service exists." },
      { value: 0, label: "Reviews", note: "No review service exists." },
      { value: null, label: "Analytics", note: "No analytics are collected." },
    ]);
  }
  const panels = root.querySelector("[data-creator-panels]");
  if (panels) {
    panels.innerHTML = [
      ["Listings and drafts", "Your listings will appear here once creator listings can be stored and published.", "Open the listings layout", "listings/index.html"],
      ["Sales and orders", "Your sales data will appear here once marketplace selling is available.", "Open the orders layout", "orders/index.html"],
      ["Earnings and payouts", "Earnings appear here only after a real payout service can settle real sales.", "Open the earnings layout", "earnings/index.html"],
      ["Reviews", "Reviews from buyers appear here once the marketplace can serve them.", "Open the reviews layout", "reviews/index.html"],
      ["Analytics", "Views, saves, conversion, and traffic are not measured. Nothing here is estimated.", "Open the analytics layout", "analytics/index.html"],
    ].map(([title, message, actionLabel, href]) => `
      <article class="panel">
        <div class="panel-head"><div><h3>${escapeText(title)}</h3><p class="small muted">${escapeText(message)}</p></div>
        <span class="badge badge--planned">Not available yet</span></div>
        <a class="text-link" href="${escapeAttribute(href)}">${escapeText(actionLabel)} <span aria-hidden="true">→</span></a>
      </article>`).join("");
  }
}

/* ------------------------------------------------------------------ listings */

export function initCreatorListings(root = document.querySelector('[data-page="creator-listings"]')) {
  ensureLiveRegion();
  if (!root) return;
  const preview = previewRequested();
  const banner = root.querySelector("[data-preview-banner]");
  if (banner) banner.innerHTML = preview ? previewNote() : "";
  const region = root.querySelector("[data-listings-region]");
  const tabs = [...root.querySelectorAll('[role="tab"]')];
  const rows = preview
    ? DEMO_LISTINGS.map((listing) => ({
      id: listing.id, title: listing.title, priceLabel: listing.priceLabel,
      status: "PREVIEW_ONLY", compatibility: `${listing.edition} · ${listing.minecraftVersion}`, updatedAt: listing.updatedAt,
    }))
    : [];

  const renderTab = (tabName) => {
    for (const tab of tabs) tab.setAttribute("aria-selected", String(tab.dataset.tab === tabName));
    const filtered = tabName === "all" ? rows : rows.filter((row) => row.status.toLowerCase() === tabName);
    if (filtered.length === 0) {
      renderState(region, {
        kind: STATE.EMPTY,
        title: tabName === "all" ? "No listings yet" : `No ${tabName} listings`,
        message: preview
          ? "Preview rows are labelled and only appear under All. Real drafts and published listings require listing storage, which is not implemented."
          : "Listing storage is not implemented, so this creator has no drafts, no published listings, and nothing to unpublish.",
        details: [
          "The create-listing flow is available to design and validate a listing, but it cannot publish yet.",
          "Edit, duplicate, unpublish, and delete are intentionally inert until listing storage exists.",
        ],
        action: { label: "Open the create-listing flow", href: "new.html" },
      });
      return;
    }
    region.dataset.state = STATE.POPULATED;
    region.innerHTML = dataTableMarkup({
      caption: "Preview rows only. Status, price, and update time are placeholders.",
      columns: [
        { label: "Listing", rowHeader: true },
        { label: "Status" }, { label: "Price" }, { label: "Compatibility" }, { label: "Updated" }, { label: "Actions" },
      ],
      rows: filtered.map((row) => [
        escapeText(row.title),
        '<span class="badge badge--preview">Preview</span>',
        escapeText(row.priceLabel),
        escapeText(row.compatibility),
        escapeText(orDash(row.updatedAt)),
        `<span class="table-actions">
           <button type="button" class="button button-secondary button-small" disabled>Edit</button>
           <button type="button" class="button button-secondary button-small" disabled>Unpublish</button>
           <button type="button" class="button button-secondary button-small" disabled>Duplicate</button>
         </span>`,
      ]),
      htmlColumns: [5],
    });
  };
  for (const tab of tabs) tab.addEventListener("click", () => renderTab(tab.dataset.tab));
  renderTab("all");
}

/* ------------------------------------------------------------------ orders / earnings / reviews / analytics */

export function initCreatorOrders(root = document.querySelector('[data-page="creator-orders"]')) {
  ensureLiveRegion();
  if (!root) return;
  const preview = previewRequested();
  const banner = root.querySelector("[data-preview-banner]");
  if (banner) banner.innerHTML = preview ? previewNote() : "";
  const region = root.querySelector("[data-orders-region]");
  if (!preview) {
    renderState(region, {
      kind: STATE.EMPTY,
      title: "No orders",
      message: "Order, settlement, and buyer records are part of the marketplace service, which is not implemented. Nothing can be sold yet, so no order can exist.",
      details: [
        "When selling is available, each row will show the listing, the date, the settled amount, and the order status.",
        "Buyer identity stays limited to what a seller needs: no payment details, addresses, or credentials are ever shown here.",
      ],
    });
    return;
  }
  region.dataset.state = STATE.POPULATED;
  region.innerHTML = dataTableMarkup({
    caption: "Preview rows only. Amounts and statuses are placeholders; no order exists.",
    columns: [
      { label: "Order", rowHeader: true }, { label: "Listing" }, { label: "Placed" }, { label: "Amount" }, { label: "Status" },
    ],
    rows: DEMO_ORDERS.map((order) => [
      escapeText(order.id),
      escapeText(DEMO_LISTINGS.find((listing) => listing.id === order.listingId)?.title ?? order.listingId),
      escapeText(orDash(order.placedAt)),
      escapeText(order.amountLabel),
      '<span class="badge badge--preview">Preview only</span>',
    ]),
    htmlColumns: [4],
  });
}

export function initCreatorEarnings(root = document.querySelector('[data-page="creator-earnings"]')) {
  ensureLiveRegion();
  if (!root) return;
  const adapters = createAdapters();
  const metrics = root.querySelector("[data-earnings-metrics]");
  if (metrics) {
    metrics.innerHTML = metricsMarkup([
      { value: null, label: "Available balance", note: "No ledger exists." },
      { value: null, label: "Pending balance", note: "No settlement exists." },
      { value: null, label: "Total earnings", note: "Nothing has been sold." },
      { value: null, label: "Next payout", note: "No payout schedule exists." },
    ]);
  }
  const region = root.querySelector("[data-earnings-region]");
  renderState(region, {
    kind: adapters.payments.configured ? STATE.UNAUTHORIZED : STATE.UNAVAILABLE,
    title: "Earnings and payouts are not available",
    message: "CraftMind cannot process payments, settle commissions, or pay creators yet. No balance, payout, or tax record exists, and this page will not display a number it cannot justify.",
    details: [
      "Payout history and payout settings are part of the planned payment integration.",
      "Marketplace economics — commission, fees, and currency — are not defined. Nothing on this page is a real rate.",
      "No payment provider (card, UPI, wallet, or bank transfer) is integrated in this phase.",
    ],
  });
}

export function initCreatorReviews(root = document.querySelector('[data-page="creator-reviews"]')) {
  ensureLiveRegion();
  if (!root) return;
  const preview = previewRequested();
  const banner = root.querySelector("[data-preview-banner]");
  if (banner) banner.innerHTML = preview ? previewNote() : "";
  const summary = root.querySelector("[data-reviews-summary]");
  if (summary) {
    summary.innerHTML = metricsMarkup([
      { value: null, label: "Average rating", note: "No reviews exist, so no average is shown." },
      { value: 0, label: "Reviews", note: "No review service exists." },
      { value: 0, label: "Response rate", note: "There is nothing to respond to." },
    ]);
  }
  const region = root.querySelector("[data-reviews-region]");
  if (!preview) {
    renderState(region, {
      kind: STATE.EMPTY,
      title: "No reviews",
      message: "Reviews are served by the marketplace service, which is not implemented. Ratings, review text, and creator responses will appear here once buyers can leave them.",
    });
    return;
  }
  region.dataset.state = STATE.POPULATED;
  region.innerHTML = dataTableMarkup({
    caption: "Preview rows only. No rating value and no review text from a real buyer exists.",
    columns: [{ label: "Listing", rowHeader: true }, { label: "Rating" }, { label: "Review" }, { label: "Response" }],
    rows: DEMO_REVIEWS.map((review) => [
      escapeText(DEMO_LISTINGS.find((listing) => listing.id === review.listingId)?.title ?? review.listingId),
      '<span class="rating"><span class="rating-marks" aria-hidden="true">☆☆☆☆☆</span><span>No rating</span></span>',
      escapeText(review.body),
      '<button type="button" class="button button-secondary button-small" disabled>Respond</button>',
    ]),
    htmlColumns: [1, 3],
  });
}

export function initCreatorAnalytics(root = document.querySelector('[data-page="creator-analytics"]')) {
  ensureLiveRegion();
  if (!root) return;
  const metrics = root.querySelector("[data-analytics-metrics]");
  if (metrics) {
    metrics.innerHTML = metricsMarkup([
      { value: null, label: "Listing views", note: "Views are not counted." },
      { value: null, label: "Saves", note: "Saves are not stored." },
      { value: null, label: "Purchases", note: "Nothing can be purchased." },
      { value: null, label: "Conversion", note: "Conversion needs both views and purchases." },
      { value: null, label: "Revenue", note: "No revenue exists." },
      { value: null, label: "Traffic sources", note: "No traffic data is collected." },
    ]);
  }
  const region = root.querySelector("[data-analytics-region]");
  renderState(region, {
    kind: STATE.UNAVAILABLE,
    title: "Analytics are not collected",
    message: DEMO_ANALYTICS.note,
    details: [
      "Charts, funnels, and top-listing tables stay empty until an analytics service exists — this site adds no tracking of its own.",
      "No visit, view, save, or purchase is recorded anywhere for this page.",
    ],
  });
}

/* ------------------------------------------------------------------ create listing wizard */

const STEPS = Object.freeze([
  { id: "details", label: "Details" },
  { id: "media", label: "Media" },
  { id: "compatibility", label: "Minecraft compatibility" },
  { id: "pricing", label: "Pricing" },
  { id: "instructions", label: "Instructions" },
  { id: "preview", label: "Preview" },
  { id: "publish", label: "Publish" },
]);

const LIMITS = Object.freeze({
  title: { minimum: 4, maximum: 90 },
  shortDescription: { minimum: 20, maximum: 240 },
  fullDescription: { minimum: 40, maximum: 4000 },
  tags: { maximum: 10, tagMaximum: 24 },
  instructions: { maximum: 3000 },
  requirements: { maximum: 1200 },
  limitations: { maximum: 1200 },
  creatorNotes: { maximum: 1200 },
});

/** Bounded, auditable validation. Every message names the field and the rule; nothing is invented. */
export function validateStep(step, draft) {
  const errors = [];
  const text = (value) => (typeof value === "string" ? value.trim() : "");
  if (step === "details") {
    const title = text(draft.title);
    if (title.length < LIMITS.title.minimum || title.length > LIMITS.title.maximum) {
      errors.push({ field: "title", message: `Use between ${LIMITS.title.minimum} and ${LIMITS.title.maximum} characters for the title.` });
    }
    const short = text(draft.shortDescription);
    if (short.length < LIMITS.shortDescription.minimum || short.length > LIMITS.shortDescription.maximum) {
      errors.push({ field: "shortDescription", message: `Summarize the build in ${LIMITS.shortDescription.minimum}–${LIMITS.shortDescription.maximum} characters.` });
    }
    const full = text(draft.fullDescription);
    if (full.length < LIMITS.fullDescription.minimum || full.length > LIMITS.fullDescription.maximum) {
      errors.push({ field: "fullDescription", message: `Describe the build in ${LIMITS.fullDescription.minimum}–${LIMITS.fullDescription.maximum} characters.` });
    }
    if (!CATEGORIES.includes(draft.category)) errors.push({ field: "category", message: "Choose a category." });
    if (!text(draft.subcategory)) errors.push({ field: "subcategory", message: "Name the subcategory, for example Housing or Gardens." });
    if (!BUILD_TYPES.includes(draft.buildType)) errors.push({ field: "buildType", message: "Choose the build type." });
    if (!DIFFICULTIES.includes(draft.difficulty)) errors.push({ field: "difficulty", message: "Choose the difficulty." });
    const tags = (draft.tags ?? []).filter(Boolean);
    if (tags.length > LIMITS.tags.maximum) errors.push({ field: "tags", message: `Use at most ${LIMITS.tags.maximum} tags.` });
    for (const tag of tags) {
      if (tag.length > LIMITS.tags.tagMaximum) errors.push({ field: "tags", message: `Each tag must be ${LIMITS.tags.tagMaximum} characters or fewer.` });
    }
  }
  if (step === "media") {
    // Media cannot be uploaded in this phase. The step validates the *shape* of the requirement so the flow is usable.
    if (draft.acknowledgedMediaLimits !== true) {
      errors.push({ field: "mediaAcknowledgement", message: "Confirm that you understand uploads are not available and that nothing will be stored." });
    }
  }
  if (step === "compatibility") {
    if (!draft.edition) errors.push({ field: "edition", message: "Choose the Minecraft edition." });
    if (!text(draft.minecraftVersion)) errors.push({ field: "minecraftVersion", message: "State the Minecraft version." });
    if (draft.edition === "java") {
      if (!draft.loader) errors.push({ field: "loader", message: "Choose the loader for a Java build." });
      else if (!loaderBelongsToEdition(draft.loader, draft.edition)) errors.push({ field: "loader", message: "That loader does not belong to this edition." });
    }
    if (!RELEASE_CHANNELS.includes(draft.releaseChannel)) errors.push({ field: "releaseChannel", message: "Choose the release channel." });
  }
  if (step === "pricing") {
    if (!["FREE", "PAID"].includes(draft.pricingModel)) errors.push({ field: "pricingModel", message: "Choose free or paid." });
    if (draft.pricingModel === "PAID") {
      const price = Number(draft.price);
      if (!Number.isFinite(price) || price <= 0) errors.push({ field: "price", message: "Enter a price greater than zero." });
      if (draft.salePrice !== "" && draft.salePrice !== null && draft.salePrice !== undefined) {
        const sale = Number(draft.salePrice);
        if (!Number.isFinite(sale) || sale <= 0) errors.push({ field: "salePrice", message: "A sale price must be greater than zero." });
        else if (Number.isFinite(price) && sale >= price) errors.push({ field: "salePrice", message: "A sale price must be lower than the regular price." });
      }
    }
  }
  if (step === "instructions") {
    if (text(draft.usageInstructions).length === 0) errors.push({ field: "usageInstructions", message: "Describe how the build is meant to be used." });
    if (text(draft.usageInstructions).length > LIMITS.instructions.maximum) errors.push({ field: "usageInstructions", message: `Keep usage instructions within ${LIMITS.instructions.maximum} characters.` });
    if (text(draft.minecraftRequirements).length > LIMITS.requirements.maximum) errors.push({ field: "minecraftRequirements", message: `Keep requirements within ${LIMITS.requirements.maximum} characters.` });
    if (text(draft.knownLimitations).length > LIMITS.limitations.maximum) errors.push({ field: "knownLimitations", message: `Keep known limitations within ${LIMITS.limitations.maximum} characters.` });
    if (text(draft.creatorNotes).length > LIMITS.creatorNotes.maximum) errors.push({ field: "creatorNotes", message: `Keep creator notes within ${LIMITS.creatorNotes.maximum} characters.` });
  }
  return errors;
}

export function initListingWizard(root = document.querySelector('[data-page="creator-listing-new"]')) {
  ensureLiveRegion();
  if (!root) return;
  const draft = {
    title: "", shortDescription: "", fullDescription: "", category: "", subcategory: "", tags: [], buildType: "", difficulty: "",
    acknowledgedMediaLimits: false, edition: "", minecraftVersion: "", loader: "", loaderVersion: "", releaseChannel: "",
    compatibilityNotes: "", pricingModel: "FREE", price: "", salePrice: "",
    usageInstructions: "", minecraftRequirements: "", knownLimitations: "", creatorNotes: "",
  };
  let currentStep = 0;

  const stepsList = root.querySelector("[data-wizard-steps]");
  const panel = root.querySelector("[data-wizard-panel]");
  const versionNote = root.querySelector("[data-compatibility-note]");

  function renderSteps() {
    stepsList.innerHTML = STEPS.map((step, index) => `
      <li class="wizard-step" ${index === currentStep ? 'aria-current="step"' : ""} data-complete="${index < currentStep}">
        <span class="wizard-step-index">${String(index + 1).padStart(2, "0")}</span>
        <span>${escapeText(step.label)}</span>
      </li>`).join("");
    const progress = root.querySelector("[data-wizard-progress]");
    if (progress) {
      progress.setAttribute("aria-valuenow", String(Math.round(((currentStep + 1) / STEPS.length) * 100)));
      progress.querySelector(".progress-bar").style.width = `${Math.round(((currentStep + 1) / STEPS.length) * 100)}%`;
    }
  }

  function field(name, { label, type = "text", hint = "", value = "", required = true, maximum = null }) {
    const hintId = `${name}-hint`;
    return `
      <div class="field">
        <label for="${name}">${escapeText(label)}${required ? "" : ' <span class="optional">(optional)</span>'}</label>
        <input class="input" id="${name}" name="${name}" type="${type}" value="${escapeAttribute(value)}"
          ${required ? "required" : ""} ${maximum ? `maxlength="${maximum}"` : ""} aria-describedby="${hintId}" data-draft-field="${name}">
        <span class="field-hint" id="${hintId}">${escapeText(hint)}</span>
      </div>`;
  }

  function textareaField(name, { label, hint, value = "", maximum, required = false }) {
    return `
      <div class="field">
        <label for="${name}">${escapeText(label)}${required ? "" : ' <span class="optional">(optional)</span>'}</label>
        <textarea class="textarea" id="${name}" name="${name}" data-draft-field="${name}" aria-describedby="${name}-hint"
          ${required ? "required" : ""} maxlength="${maximum}">${escapeText(value)}</textarea>
        <span class="field-hint" id="${name}-hint">${escapeText(hint)}</span>
        <span class="char-count" data-char-count="${name}">0 / ${maximum}</span>
      </div>`;
  }

  function selectField(name, { label, options, value = "", hint = "" }) {
    return `
      <div class="field">
        <label for="${name}">${escapeText(label)}</label>
        <select class="select" id="${name}" name="${name}" data-draft-field="${name}" aria-describedby="${name}-hint">
          <option value="">Choose…</option>
          ${options.map((option) => {
            const optionValue = typeof option === "string" ? option : option.value;
            const optionLabel = typeof option === "string" ? option : option.label;
            return `<option value="${escapeAttribute(optionValue)}"${optionValue === value ? " selected" : ""}>${escapeText(optionLabel)}</option>`;
          }).join("")}
        </select>
        <span class="field-hint" id="${name}-hint">${escapeText(hint)}</span>
      </div>`;
  }

  function panelMarkup() {
    const compatibility = compatibilityOptions();
    switch (STEPS[currentStep].id) {
      case "details":
        return `
          <h2 id="wizard-title">Listing details</h2>
          <p class="muted">Describe the build. Every field has a bounded length, and validation runs before you continue.</p>
          <div class="stack-lg">
            ${field("title", { label: "Listing title", hint: `Required · ${LIMITS.title.minimum}–${LIMITS.title.maximum} characters`, value: draft.title, maximum: LIMITS.title.maximum })}
            ${textareaField("shortDescription", { label: "Short description", hint: `Shown on marketplace cards · ${LIMITS.shortDescription.minimum}–${LIMITS.shortDescription.maximum} characters`, value: draft.shortDescription, maximum: LIMITS.shortDescription.maximum, required: true })}
            ${textareaField("fullDescription", { label: "Full description", hint: `Shown on the listing page · ${LIMITS.fullDescription.minimum}–${LIMITS.fullDescription.maximum} characters`, value: draft.fullDescription, maximum: LIMITS.fullDescription.maximum, required: true })}
            <div class="field-row">
              ${selectField("category", { label: "Category", options: CATEGORIES, value: draft.category, hint: "Required" })}
              ${field("subcategory", { label: "Subcategory", hint: "Required · for example Housing or Gardens", value: draft.subcategory, maximum: 40 })}
            </div>
            <div class="field-row">
              ${selectField("buildType", { label: "Build type", options: BUILD_TYPES, value: draft.buildType, hint: "How this build was made" })}
              ${selectField("difficulty", { label: "Difficulty", options: DIFFICULTIES, value: draft.difficulty, hint: "How hard the build is to place and finish" })}
            </div>
            ${field("tags", { label: "Tags", hint: `Comma separated · up to ${LIMITS.tags.maximum} tags, ${LIMITS.tags.tagMaximum} characters each`, value: (draft.tags ?? []).join(", "), required: false, maximum: 260 })}
          </div>`;
      case "media":
        return `
          <h2 id="wizard-title">Media</h2>
          <p class="muted">Uploads need storage, virus scanning, and moderation, none of which exists yet. This step shows the exact interface that will be used, and it does not accept a file.</p>
          <div class="media-drop">
            <h3>Uploads are not available yet</h3>
            <p>No file is read, uploaded, or stored in this phase. Choose files only after a media service exists; the limits below are the planned contract.</p>
            <button type="button" class="button button-secondary" disabled>Choose files · not available</button>
          </div>
          <ul class="media-grid">${DEMO_MEDIA.map((slot) => `
            <li class="media-tile">
              <span class="media-thumb" aria-hidden="true">No file</span>
              <span class="media-tile-name">${escapeText(slot.label)}</span>
              <span class="badge badge--muted">Nothing uploaded</span>
              <span class="field-hint">${escapeText(slot.accepted)} · ${(slot.maximumBytes / 1_048_576).toFixed(0)} MB maximum${slot.maximumCount ? ` · up to ${slot.maximumCount} files` : ""}</span>
            </li>`).join("")}</ul>
          <div class="field" style="margin-top:18px">
            <label class="choice" for="mediaAcknowledgement">
              <input type="checkbox" id="mediaAcknowledgement" data-draft-field="acknowledgedMediaLimits" ${draft.acknowledgedMediaLimits ? "checked" : ""}>
              <span>I understand that no media can be uploaded yet, and that this listing cannot be published with images.<small>Required to continue.</small></span>
            </label>
          </div>`;
      case "compatibility":
        return `
          <h2 id="wizard-title">Minecraft compatibility</h2>
          <p class="muted">These fields reuse CraftMind's existing compatibility vocabulary. A listing may support any edition, but only a registered, certified runtime can actually be built by the app.</p>
          <div class="stack-lg">
            <div class="field-row">
              ${selectField("edition", { label: "Minecraft edition", options: compatibility.editions.map((edition) => ({ value: edition.wire, label: `${edition.label} — ${edition.status}` })), value: draft.edition, hint: "Required" })}
              ${field("minecraftVersion", { label: "Minecraft version", hint: "Required · for example 1.20.1", value: draft.minecraftVersion, maximum: 20 })}
            </div>
            <div class="field-row">
              ${selectField("loader", { label: "Loader", options: draft.edition ? (compatibility.loadersByEdition[draft.edition] ?? []) : [], value: draft.loader, hint: draft.edition ? "Required for Java Edition builds" : "Choose an edition first" })}
              ${field("loaderVersion", { label: "Loader version", hint: "Optional · must match the profile the build targets", value: draft.loaderVersion, required: false, maximum: 20 })}
            </div>
            ${selectField("releaseChannel", { label: "Release channel", options: compatibility.releaseChannels, value: draft.releaseChannel, hint: "Required" })}
            ${textareaField("compatibilityNotes", { label: "Compatibility notes", hint: "Optional · what a buyer must know about running this build", value: draft.compatibilityNotes, maximum: 1200 })}
          </div>
          <div class="banner banner--planned" style="margin-top:18px" role="note" id="compatibility-verdict"></div>`;
      case "pricing":
        return `
          <h2 id="wizard-title">Pricing</h2>
          <p class="muted">Whether the listing is free or paid, and — for paid listings — the price a buyer would see. CraftMind has not defined its marketplace economics, so the fee and the earnings figure cannot be calculated.</p>
          <div class="pricing-layout">
            <div class="stack-lg">
              <fieldset class="fieldset">
                <legend>Listing price</legend>
                <div class="choice-row">
                  <label class="choice" for="pricingFree"><input type="radio" id="pricingFree" name="pricingModel" value="FREE" data-draft-field="pricingModel" ${draft.pricingModel === "FREE" ? "checked" : ""}><span>Free<small>Anyone can use the build at no cost.</small></span></label>
                  <label class="choice" for="pricingPaid"><input type="radio" id="pricingPaid" name="pricingModel" value="PAID" data-draft-field="pricingModel" ${draft.pricingModel === "PAID" ? "checked" : ""}><span>Paid<small>Requires a marketplace, checkout, and payout service.</small></span></label>
                </div>
              </fieldset>
              <div class="field-row" ${draft.pricingModel === "PAID" ? "" : "hidden"}>
                ${field("price", { label: "Price", type: "number", hint: "Greater than zero · no currency is defined yet", value: draft.price, required: draft.pricingModel === "PAID" })}
                ${field("salePrice", { label: "Sale price", type: "number", hint: "Optional · must be lower than the price", value: draft.salePrice, required: false })}
              </div>
              <div class="banner banner--planned" role="note">
                <span class="banner-mark">PLACEHOLDER</span>
                <p>Selling is not implemented. Even with a price entered, this listing cannot be purchased, and no payment provider is integrated.</p>
              </div>
            </div>
            <aside class="price-summary">
              <h3>Earnings preview</h3>
              <p class="small muted">Marketplace economics are defined in a later phase, so two of the three figures cannot exist yet.</p>
              <div class="price-rows">
                <div class="price-row"><span>Your price</span><b>${escapeText(draft.pricingModel === "PAID" && draft.price ? draft.price : orDash(null))}</b></div>
                <div class="price-row"><span>CraftMind fee</span><b>Not defined yet</b></div>
                <div class="price-row price-row--total"><span>Estimated earnings</span><b>Unavailable</b></div>
              </div>
              <p class="price-note">No commission rate exists in this phase, so no earnings figure is calculated.</p>
              <details class="disclosure">
                <summary>Show a layout example with an arbitrary fee</summary>
                <div>
                  <p class="price-note">${escapeText(PLACEHOLDER_FEE_DISCLOSURE)}</p>
                  <div class="price-rows">
                    <div class="price-row"><span>Example price</span><b>100</b></div>
                    <div class="price-row"><span>Arbitrary example fee</span><b>${PLACEHOLDER_FEE_PERCENT}%</b></div>
                    <div class="price-row price-row--total"><span>Example remainder</span><b>${100 - PLACEHOLDER_FEE_PERCENT}</b></div>
                  </div>
                </div>
              </details>
            </aside>
          </div>`;
      case "instructions":
        return `
          <h2 id="wizard-title">Instructions</h2>
          <p class="muted">Tell a buyer exactly how to use the build. These fields are stored with the listing and shown on its page.</p>
          <div class="stack-lg">
            ${textareaField("usageInstructions", { label: "Installation and use instructions", hint: `Required · up to ${LIMITS.instructions.maximum} characters`, value: draft.usageInstructions, maximum: LIMITS.instructions.maximum, required: true })}
            ${textareaField("minecraftRequirements", { label: "Minecraft requirements", hint: "Optional · version, loader, and server requirements", value: draft.minecraftRequirements, maximum: LIMITS.requirements.maximum })}
            ${textareaField("knownLimitations", { label: "Known limitations", hint: "Optional · what the build does not do", value: draft.knownLimitations, maximum: LIMITS.limitations.maximum })}
            ${textareaField("creatorNotes", { label: "Creator notes", hint: "Optional · anything else a buyer should know", value: draft.creatorNotes, maximum: LIMITS.creatorNotes.maximum })}
          </div>`;
      case "preview": {
        const compatibility = describeCompatibility({ edition: draft.edition, minecraftVersion: draft.minecraftVersion, loader: draft.loader, loaderVersion: draft.loaderVersion });
        return `
          <h2 id="wizard-title">Preview</h2>
          <p class="muted">This is exactly how the listing would appear in the marketplace. It renders your own input — nothing is filled in for you.</p>
          <div class="preview-frame">
            ${listingCardMarkup({
              id: "draft-preview", title: draft.title || "Untitled listing",
              creatorDisplayName: "Your creator profile", creatorInitials: "ME",
              summary: draft.shortDescription || "No short description yet.",
              edition: draft.edition, minecraftVersion: draft.minecraftVersion, loader: draft.loader,
              category: draft.category, difficulty: draft.difficulty, tags: draft.tags,
              priceLabel: draft.pricingModel === "PAID" ? (draft.price ? `${draft.price} · currency not defined` : "Price missing") : (draft.pricingModel === "FREE" ? "Free" : "Not set"),
            }, { preview: false, compatibility, actionLabel: "View details" })}
          </div>
          <h3 class="small" style="margin-top:24px">Compatibility verdict</h3>
          <p class="small muted">${escapeText(compatibility.summary)}</p>
          <h3 class="small">Instructions a buyer would see</h3>
          <p class="small muted">${escapeText(draft.usageInstructions || "No instructions yet.")}</p>
          <h3 class="small">Description</h3>
          <p class="small muted">${escapeText(draft.fullDescription || "No description yet.")}</p>`;
      }
      default:
        return `
          <h2 id="wizard-title">Publish</h2>
          <p class="muted">Publishing writes a real listing into a marketplace that does not exist yet. CraftMind will not create a listing it cannot store, sell, or serve.</p>
          <div class="state-card state-card--unavailable" role="note">
            <div class="state-head"><span class="badge badge--planned">Not available yet</span><h3>Marketplace publishing will be available soon</h3></div>
            <p>Your draft stays in this page until you leave it. Nothing was uploaded, nothing was stored, and no listing was created.</p>
            <ul class="state-list">
              <li>Publishing needs listing storage, media storage, moderation, and the marketplace service.</li>
              <li>Selling needs checkout, commission, and payout services — none of which exist in this phase.</li>
              <li>Keep this draft text somewhere safe before leaving the page; refreshing clears it.</li>
            </ul>
            <div class="state-actions">
              <button type="button" class="button button-primary" disabled>Publish listing · not available</button>
              <button type="button" class="button button-secondary" disabled>Save draft · not available</button>
            </div>
          </div>`;
    }
  }

  function collectDraft() {
    for (const element of panel.querySelectorAll("[data-draft-field]")) {
      const name = element.dataset.draftField;
      if (element.type === "checkbox") draft[name] = element.checked;
      else if (element.type === "radio") { if (element.checked) draft[name] = element.value; }
      else if (name === "tags") draft.tags = element.value.split(",").map((tag) => tag.trim()).filter(Boolean);
      else draft[name] = element.value;
    }
  }

  function renderErrors(errors) {
    const previous = panel.querySelector("[data-wizard-errors]");
    if (previous) previous.remove();
    for (const element of panel.querySelectorAll("[aria-invalid]")) element.removeAttribute("aria-invalid");
    if (errors.length === 0) return;
    const summary = document.createElement("div");
    summary.className = "state-card state-card--error";
    summary.dataset.wizardErrors = "true";
    summary.setAttribute("role", "alert");
    summary.innerHTML = `<div class="state-head"><span class="badge">Error</span><h3>${errors.length} field(s) need attention</h3></div>
      <ul class="state-list">${errors.map((error) => `<li><a href="#${escapeAttribute(error.field)}">${escapeText(error.message)}</a></li>`).join("")}</ul>`;
    panel.prepend(summary);
    for (const error of errors) {
      const input = panel.querySelector(`#${error.field}`);
      if (input) input.setAttribute("aria-invalid", "true");
    }
    summary.querySelector("a")?.focus();
    announce(`${errors.length} field(s) need attention.`);
  }

  function updateCharCounts() {
    for (const counter of panel.querySelectorAll("[data-char-count]")) {
      const name = counter.dataset.charCount;
      const element = panel.querySelector(`#${name}`);
      const maximum = Number(element?.getAttribute("maxlength") ?? 0);
      const length = element?.value.length ?? 0;
      counter.textContent = `${length} / ${maximum}`;
      counter.dataset.state = length > maximum ? "over" : length > maximum * 0.9 ? "near" : "ok";
    }
  }

  function updateCompatibilityVerdict() {
    const verdict = panel.querySelector("#compatibility-verdict");
    if (!verdict) return;
    if (!draft.edition || !draft.minecraftVersion) {
      verdict.innerHTML = '<span class="banner-mark">COMPATIBILITY</span><p>Choose an edition and version to see how CraftMind classifies this target.</p>';
      return;
    }
    const compatibility = describeCompatibility({ edition: draft.edition, minecraftVersion: draft.minecraftVersion, loader: draft.loader, loaderVersion: draft.loaderVersion });
    // The registry states support and certification separately; the verdict states both so "buildable" is never implied.
    const label = compatibility.buildable ? `${compatibility.status} · ${compatibility.certification}` : compatibility.status;
    const certification = compatibility.buildable ? "" : `<p class="small muted">Certification: ${escapeText(compatibility.certification)}. CraftMind builds only a registered, certified target.</p>`;
    verdict.innerHTML = `<span class="banner-mark">${escapeText(label)}</span><p>${escapeText(compatibility.summary)}</p>${certification}`;
  }

  /** Rebuilds the loader list for the edition currently selected, without discarding the rest of the step. */
  function refreshLoaderSelect() {
    const select = panel.querySelector("#loader");
    if (!select) return;
    const loaders = draft.edition ? (compatibilityOptions().loadersByEdition[draft.edition] ?? []) : [];
    const previous = draft.loader;
    select.innerHTML = [
      '<option value="">Choose…</option>',
      ...loaders.map((loader) => `<option value="${escapeAttribute(loader)}">${escapeText(loader)}</option>`),
    ].join("");
    select.value = loaders.includes(previous) ? previous : "";
    draft.loader = select.value;
    const hint = panel.querySelector("#loader-hint");
    if (hint) hint.textContent = draft.edition ? "Required for Java Edition builds" : "Choose an edition first";
  }

  function bindPanelFields() {
    for (const element of panel.querySelectorAll("[data-draft-field]")) {
      const handler = () => {
        collectDraft();
        updateCharCounts();
        if (STEPS[currentStep].id === "compatibility") {
          if (element.name === "edition") refreshLoaderSelect();
          updateCompatibilityVerdict();
        }
        if (STEPS[currentStep].id === "pricing" && element.name === "pricingModel") {
          const priceRow = panel.querySelector("#price")?.closest(".field-row");
          if (priceRow) priceRow.hidden = draft.pricingModel !== "PAID";
        }
      };
      element.addEventListener("input", handler);
      element.addEventListener("change", handler);
    }
    updateCharCounts();
    updateCompatibilityVerdict();
  }

  function render() {
    renderSteps();
    panel.innerHTML = panelMarkup();
    bindPanelFields();
    const back = root.querySelector("[data-wizard-back]");
    const next = root.querySelector("[data-wizard-next]");
    back.disabled = currentStep === 0;
    back.setAttribute("aria-disabled", String(currentStep === 0));
    next.textContent = currentStep === STEPS.length - 1 ? "Publishing not available" : "Continue";
    next.disabled = currentStep === STEPS.length - 1;
    document.getElementById("wizard-title")?.focus?.();
  }

  root.querySelector("[data-wizard-back]").addEventListener("click", () => {
    if (currentStep > 0) { currentStep -= 1; render(); announce(`Step ${currentStep + 1} of ${STEPS.length}: ${STEPS[currentStep].label}.`); }
  });
  root.querySelector("[data-wizard-next]").addEventListener("click", () => {
    collectDraft();
    const errors = validateStep(STEPS[currentStep].id, draft);
    renderErrors(errors);
    if (errors.length > 0) return;
    if (currentStep < STEPS.length - 1) {
      currentStep += 1;
      render();
      announce(`Step ${currentStep + 1} of ${STEPS.length}: ${STEPS[currentStep].label}.`);
    }
  });
  const summary = root.querySelector("[data-draft-summary]");
  if (summary) {
    summary.textContent = `Draft lives in this page only. Nothing is uploaded, and nothing is written to your browser storage. BuildPlan limits for reference: ${BUILD_PLAN_LIMITS.maximumOperations} operations, ${BUILD_PLAN_LIMITS.maximumWidth}×${BUILD_PLAN_LIMITS.maximumHeight}×${BUILD_PLAN_LIMITS.maximumDepth} blocks.`;
  }
  render();
}

export { LIMITS as LISTING_LIMITS, STEPS as LISTING_STEPS, PLACEHOLDER_FEE_PERCENT };
