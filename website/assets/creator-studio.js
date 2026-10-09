/**
 * Creator studio controllers (Phase 21; listing storage and lifecycle since Phase 25).
 *
 * Two modes, never mixed:
 *
 *   * **Live** — an account service is configured: the dashboard counts your real listings, the listings table shows
 *     your persisted rows with their true lifecycle status, the create/edit wizard saves drafts through the service
 *     and publishes only after the server's own prerequisite check, and archive is a confirmed one-way transition.
 *     The server decides every state: the browser never claims ownership, never marks anything published, and shows
 *     the service's refusal message verbatim when publishing is blocked.
 *   * **Unconfigured / preview** — the Phase 21 foundation: dashes, honest empty states, sample rows behind
 *     `?preview=1`, and a fully working local form whose publish step says there is nowhere to publish to. Draft text
 *     lives in memory for the current page only — nothing is uploaded and nothing is stored.
 *
 * Orders, earnings, reviews, and analytics stay honest in both modes: none of those services exists, and no number
 * here is invented, estimated, or carried over from sample data.
 */

import { RESULT, createAdapters } from "./adapters.js";
import { BUILD_PLAN_LIMITS, RELEASE_CHANNELS, describeCompatibility, compatibilityOptions, loaderBelongsToEdition } from "./compatibility.js";
import { dataTableMarkup, listingCardMarkup, metricsMarkup } from "./components.js";
import { BUILD_TYPES, DEMO_LISTINGS, DEMO_ORDERS, DEMO_REVIEWS, DEMO_ANALYTICS, DEMO_MEDIA, DIFFICULTIES, CATEGORIES, previewRequested } from "./preview-catalog.js";
import { STATE, announce, ensureLiveRegion, escapeAttribute, escapeText, orDash, renderLoading, renderState } from "./state.js";

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
  const adapters = createAdapters();
  const preview = previewRequested();
  const banner = root.querySelector("[data-preview-banner]");
  if (banner) banner.innerHTML = preview ? previewNote() : "";
  const metrics = root.querySelector("[data-creator-metrics]");
  const dashMetrics = () => metricsMarkup([
    { value: 0, label: "Listings", note: "No listing storage exists, so no listing can be counted." },
    { value: 0, label: "Drafts", note: "Drafts live in this page only while you type." },
    { value: 0, label: "Published", note: "Publishing is not implemented." },
    { value: 0, label: "Sales", note: "The marketplace cannot sell anything yet." },
    { value: null, label: "Earnings", note: "No payout or ledger service exists." },
    { value: 0, label: "Orders", note: "No order service exists." },
    { value: 0, label: "Reviews", note: "No review service exists." },
    { value: null, label: "Analytics", note: "No analytics are collected." },
  ]);
  const renderPanels = (listingsMessage, listingsAvailable) => {
    const panels = root.querySelector("[data-creator-panels]");
    if (!panels) return;
    panels.innerHTML = [
      ["Listings and drafts", listingsMessage, "Open the listings layout", "listings/index.html", listingsAvailable],
      // Phase 26: entry point into Hire a Builder — the directory itself renders the honest state when no
      // account service is configured, so the link is always valid but its "available" badge tracks the service.
      ["Hire a Builder", adapters.hire.configured
        ? "Open job requests from buyers: browse the directory, read a job, and send a proposal from its page."
        : "Job requests and proposals need a connected account service; the directory says so until one exists.",
        "Open the job directory", "../marketplace/hire.html", adapters.hire.configured],
      ["Sales and orders", "Your sales data will appear here once marketplace selling is available.", "Open the orders layout", "orders/index.html", false],
      ["Earnings and payouts", "Earnings appear here only after a real payout service can settle real sales.", "Open the earnings layout", "earnings/index.html", false],
      ["Reviews", "Reviews from buyers appear here once the marketplace can serve them.", "Open the reviews layout", "reviews/index.html", false],
      ["Analytics", "Views, saves, conversion, and traffic are not measured. Nothing here is estimated.", "Open the analytics layout", "analytics/index.html", false],
    ].map(([title, message, actionLabel, href, available]) => `
      <article class="panel">
        <div class="panel-head"><div><h3>${escapeText(title)}</h3><p class="small muted">${escapeText(message)}</p></div>
        ${available ? '<span class="badge badge--current">Available</span>' : '<span class="badge badge--planned">Not available yet</span>'}</div>
        <a class="text-link" href="${escapeAttribute(href)}">${escapeText(actionLabel)} <span aria-hidden="true">→</span></a>
      </article>`).join("");
  };

  if (!preview && adapters.creator.configured) {
    // Live: listing counts come from the service; everything the marketplace cannot do stays a zero or a dash with a reason.
    if (metrics) metrics.innerHTML = metricsMarkup([
      { value: null, label: "Listings", note: "Loading your listing count from the account service." },
      { value: null, label: "Drafts", note: "Loading drafts." },
      { value: null, label: "Published", note: "Loading published listings." },
      { value: 0, label: "Sales", note: "The marketplace cannot sell anything yet." },
      { value: null, label: "Earnings", note: "No payout or ledger service exists." },
      { value: 0, label: "Orders", note: "No order service exists." },
      { value: 0, label: "Reviews", note: "No review service exists." },
      { value: null, label: "Analytics", note: "No analytics are collected." },
    ]);
    renderPanels("Stored, edited, published, and archived through the account service — open the listings view to manage them.", true);
    adapters.creator.listMyListings().then((result) => {
      if (!metrics) return;
      const failureNote = result.status === RESULT.UNAUTHORIZED
        ? "Sign in to read your listing counts."
        : "The account service could not be reached.";
      if (result.status !== RESULT.OK) {
        metrics.innerHTML = metricsMarkup([
          { value: null, label: "Listings", note: failureNote },
          { value: null, label: "Drafts", note: "Counts load with your session." },
          { value: null, label: "Published", note: "Counts load with your session." },
          { value: 0, label: "Sales", note: "The marketplace cannot sell anything yet." },
          { value: null, label: "Earnings", note: "No payout or ledger service exists." },
          { value: 0, label: "Orders", note: "No order service exists." },
          { value: 0, label: "Reviews", note: "No review service exists." },
          { value: null, label: "Analytics", note: "No analytics are collected." },
        ]);
        if (result.status === RESULT.UNAUTHORIZED) renderPanels("Sign in to load your stored listings from the account service.", false);
        return;
      }
      const counts = result.payload?.counts ?? {};
      metrics.innerHTML = metricsMarkup([
        { value: Number(counts.total ?? 0), label: "Listings", note: "Stored on the account service." },
        { value: Number(counts.draft ?? 0), label: "Drafts", note: "Private until you publish them." },
        { value: Number(counts.published ?? 0), label: "Published", note: "Live in marketplace search." },
        { value: Number(counts.archived ?? 0), label: "Archived", note: "Removed from public discovery." },
        { value: 0, label: "Sales", note: "The marketplace cannot sell anything yet." },
        { value: null, label: "Earnings", note: "No payout or ledger service exists." },
        { value: 0, label: "Reviews", note: "No review service exists." },
        { value: null, label: "Analytics", note: "No analytics are collected." },
      ]);
    });
    return;
  }
  if (metrics) metrics.innerHTML = dashMetrics();
  renderPanels("Your listings will appear here once creator listings can be stored and published.", false);
}

/* ------------------------------------------------------------------ listings */

/**
 * Live listings: the session's own rows from `GET /creator/listing`, with real lifecycle states and the two
 * transitions the service implements — publish (validated against the server's prerequisites) and archive
 * (confirmed, one-way). Every refusal is shown with the service's own message; nothing is optimistically mutated.
 */
async function renderOwnListingsLive(adapters, { region, tabs }) {
  let rows = [];
  let notice = null;

  const badgeFor = (status) => {
    if (status === "PUBLISHED") return '<span class="badge badge--current">Published</span>';
    if (status === "ARCHIVED") return '<span class="badge badge--muted">Archived</span>';
    return '<span class="badge badge--muted">Draft</span>';
  };
  const activeTab = () => tabs.find((tab) => tab.getAttribute("aria-selected") === "true")?.dataset.tab ?? "all";

  const renderTab = (tabName) => {
    for (const tab of tabs) tab.setAttribute("aria-selected", String(tab.dataset.tab === tabName));
    // The page's "Unpublished" tab is the Phase 25 ARCHIVED state — archive is the only way out of public view.
    const key = tabName === "unpublished" ? "archived" : tabName;
    const filtered = key === "all" ? rows : rows.filter((row) => row.status.toLowerCase() === key);
    const noticeHtml = notice
      ? `<div class="state-card state-card--error" role="alert" style="margin-bottom:16px"><div class="state-head"><span class="badge">Refused</span><h3>The service refused the operation</h3></div><p>${escapeText(notice)}</p></div>`
      : "";
    if (filtered.length === 0) {
      region.dataset.state = STATE.EMPTY;
      region.innerHTML = noticeHtml + `
        <div class="state-card state-card--empty">
          <div class="state-head"><span class="badge badge--muted">Nothing yet</span><h3>${tabName === "all" ? "No listings yet" : `No ${escapeText(tabName)} listings`}</h3></div>
          <p>Your listings are stored on the account service, and this filter has none. Create a draft, then publish it when it is ready — drafts stay private until you do.</p>
          <div class="state-actions"><a class="button button-primary" href="new.html">Open the create-listing flow</a></div>
        </div>`;
      return;
    }
    region.dataset.state = STATE.POPULATED;
    region.innerHTML = noticeHtml + dataTableMarkup({
      caption: "Your listings from the account service. Published rows are public; drafts and archived rows are private to you.",
      columns: [
        { label: "Listing", rowHeader: true }, { label: "Status" }, { label: "Compatibility" }, { label: "Published" }, { label: "Actions" },
      ],
      rows: filtered.map((row) => [
        escapeText(row.title),
        badgeFor(row.status),
        escapeText(row.compatibility),
        escapeText(row.publishedLabel),
        `<span class="table-actions">
           ${row.status === "DRAFT"
          ? `<a class="button button-secondary button-small" href="edit.html?id=${escapeAttribute(row.id)}">Edit</a>`
          : '<button type="button" class="button button-secondary button-small" disabled title="Only draft listings are editable">Edit</button>'}
           ${row.status === "DRAFT"
          ? `<button type="button" class="button button-primary button-small" data-publish-listing="${escapeAttribute(row.id)}">Publish</button>`
          : '<button type="button" class="button button-primary button-small" disabled>Published</button>'}
           ${row.status === "ARCHIVED"
          ? '<button type="button" class="button button-secondary button-small" disabled>Archived</button>'
          : `<button type="button" class="button button-secondary button-small" data-archive-listing="${escapeAttribute(row.id)}">Archive</button>`}
         </span>`,
      ]),
      htmlColumns: [1, 4],
    });
  };

  const load = async () => {
    renderLoading(region, { rows: 4, title: "Loading your listings" });
    const result = await adapters.creator.listMyListings();
    if (result.status === RESULT.UNAUTHORIZED) {
      renderState(region, {
        kind: STATE.UNAUTHORIZED,
        title: "Sign in to see your listings",
        message: result.message ?? "Your listings are stored on the account service and need an active session.",
        action: { label: "Sign in", href: "../signin.html" },
      });
      return;
    }
    if (result.status !== RESULT.OK) {
      renderState(region, {
        kind: STATE.ERROR,
        title: "Your listings could not be loaded",
        message: result.message ?? "The account service did not answer.",
        details: ["Nothing was changed. Try again."],
      });
      return;
    }
    rows = (result.payload?.listings ?? []).map((item) => ({
      id: item.id,
      title: item.title,
      status: item.status,
      compatibility: `${item.edition} · ${(item.minecraftVersions ?? []).join(", ")}`,
      publishedLabel: item.publishedAt ? item.publishedAt.slice(0, 10) : null,
    }));
    renderTab(activeTab());
  };

  for (const tab of tabs) tab.addEventListener("click", () => renderTab(tab.dataset.tab));

  region.addEventListener("click", async (event) => {
    const publishButton = event.target.closest("[data-publish-listing]");
    const archiveButton = event.target.closest("[data-archive-listing]");
    if (publishButton) {
      const listingId = publishButton.dataset.publishListing;
      publishButton.disabled = true;
      publishButton.textContent = "Publishing…";
      const result = await adapters.creator.publishListing(listingId);
      if (result.status === RESULT.OK) {
        notice = null;
        announce("Listing published. It is now visible in marketplace search.");
        await load();
        return;
      }
      publishButton.disabled = false;
      publishButton.textContent = "Publish";
      notice = result.message ?? "The service refused the publish.";
      if (result.status === RESULT.UNAUTHORIZED) notice = "Sign in to publish your listings.";
      renderTab(activeTab());
      announce(notice);
      return;
    }
    if (archiveButton) {
      const listingId = archiveButton.dataset.archiveListing;
      const confirmed = globalThis.confirm(
        "Archive this listing? It will be removed from public search. Archiving is permanent — there is no unpublish-back-to-draft in this phase.",
      );
      if (!confirmed) return;
      archiveButton.disabled = true;
      archiveButton.textContent = "Archiving…";
      const result = await adapters.creator.unpublishListing(listingId);
      if (result.status === RESULT.OK) {
        notice = null;
        announce("Listing archived. It is no longer publicly visible.");
        await load();
        return;
      }
      archiveButton.disabled = false;
      archiveButton.textContent = "Archive";
      notice = result.message ?? "The service refused the archive.";
      if (result.status === RESULT.UNAUTHORIZED) notice = "Sign in to archive your listings.";
      renderTab(activeTab());
      announce(notice);
    }
  });

  await load();
}

export function initCreatorListings(root = document.querySelector('[data-page="creator-listings"]')) {
  ensureLiveRegion();
  if (!root) return;
  const adapters = createAdapters();
  const preview = previewRequested();
  const banner = root.querySelector("[data-preview-banner]");
  const region = root.querySelector("[data-listings-region]");
  const tabs = [...root.querySelectorAll('[role="tab"]')];
  if (!preview && adapters.creator.configured) {
    if (banner) banner.innerHTML = "";
    renderOwnListingsLive(adapters, { region, tabs });
    return;
  }
  if (banner) banner.innerHTML = preview ? previewNote() : "";
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

/** Bounded, auditable validation. Every message names the field and the rule; nothing is invented.
 *
 * In `live` mode the rules match what Phase 25 listings actually store: fields the service does not carry (short
 * description, build type, difficulty, release channel, pricing, instructions) are neither required nor shown as
 * stored, and preview-image references must be bounded https URLs. */
export function validateStep(step, draft, { live = false } = {}) {
  const errors = [];
  const text = (value) => (typeof value === "string" ? value.trim() : "");
  if (step === "details") {
    const title = text(draft.title);
    if (title.length < LIMITS.title.minimum || title.length > LIMITS.title.maximum) {
      errors.push({ field: "title", message: `Use between ${LIMITS.title.minimum} and ${LIMITS.title.maximum} characters for the title.` });
    }
    const short = text(draft.shortDescription);
    if (!live && (short.length < LIMITS.shortDescription.minimum || short.length > LIMITS.shortDescription.maximum)) {
      errors.push({ field: "shortDescription", message: `Summarize the build in ${LIMITS.shortDescription.minimum}–${LIMITS.shortDescription.maximum} characters.` });
    }
    const full = text(draft.fullDescription);
    if (full.length < LIMITS.fullDescription.minimum || full.length > LIMITS.fullDescription.maximum) {
      errors.push({ field: "fullDescription", message: `Describe the build in ${LIMITS.fullDescription.minimum}–${LIMITS.fullDescription.maximum} characters.` });
    }
    if (!CATEGORIES.includes(draft.category)) errors.push({ field: "category", message: "Choose a category." });
    if (!text(draft.subcategory)) errors.push({ field: "subcategory", message: "Name the subcategory, for example Housing or Gardens." });
    if (!live && !BUILD_TYPES.includes(draft.buildType)) errors.push({ field: "buildType", message: "Choose the build type." });
    if (!live && !DIFFICULTIES.includes(draft.difficulty)) errors.push({ field: "difficulty", message: "Choose the difficulty." });
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
    if (live) {
      for (const slot of ["image0", "image1", "image2", "image3"]) {
        const reference = text(draft[slot]);
        if (!reference) continue;
        if (!/^https:\/\/\S+$/.test(reference) || reference.length > 300) {
          errors.push({ field: slot, message: "Each preview image reference must be an https URL of 300 characters or fewer." });
        }
      }
    }
  }
  if (step === "compatibility") {
    if (!draft.edition) errors.push({ field: "edition", message: "Choose the Minecraft edition." });
    if (!text(draft.minecraftVersion)) errors.push({ field: "minecraftVersion", message: "State the Minecraft version." });
    if (draft.edition === "java") {
      if (!draft.loader) errors.push({ field: "loader", message: "Choose the loader for a Java build." });
      else if (!loaderBelongsToEdition(draft.loader, draft.edition)) errors.push({ field: "loader", message: "That loader does not belong to this edition." });
    }
    if (!live && !RELEASE_CHANNELS.includes(draft.releaseChannel)) errors.push({ field: "releaseChannel", message: "Choose the release channel." });
  }
  if (step === "pricing" && live) {
    // Phase 25 listings store no price: nothing here is sent to the service, so nothing here is validated as one.
    return errors;
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
  if (step === "instructions" && live) return errors;
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
  const adapters = createAdapters();
  // Preview mode keeps the local-only flow even when a service is configured; live mode is configured + not preview.
  const live = adapters.creator.configured && !previewRequested();
  const editId = root.dataset.page === "creator-listing-edit"
    ? new URLSearchParams(globalThis.location.search).get("id")
    : null;
  let editContext = editId ? "loading" : null; // "loading" | "ready" | "missing" | "unauthorized" | "error"
  const draft = {
    title: "", shortDescription: "", fullDescription: "", category: "", subcategory: "", tags: [], buildType: "", difficulty: "",
    acknowledgedMediaLimits: false, edition: "", minecraftVersion: "", loader: "", loaderVersion: "", releaseChannel: "",
    compatibilityNotes: "", pricingModel: "FREE", price: "", salePrice: "",
    usageInstructions: "", minecraftRequirements: "", knownLimitations: "", creatorNotes: "",
    image0: "", image1: "", image2: "", image3: "",
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
              <span>${live
                ? "I understand that no media can be uploaded yet — preview images can only be referenced by URL.<small>Required to continue.</small>"
                : "I understand that no media can be uploaded yet, and that this listing cannot be published with images.<small>Required to continue.</small>"}</span>
            </label>
          </div>
          ${live ? `
          <div class="stack-lg" style="margin-top:18px">
            <div>
              <h3 class="small">Preview image references</h3>
              <p class="field-hint">Optional · up to 4 https URLs, 300 characters each. Phase 25 stores references, not files: the URLs are shown on your listing page, and CraftMind does not host, scan, or review them.</p>
            </div>
            ${["image0", "image1", "image2", "image3"].map((slot, index) => `
              ${field(slot, { label: `Preview image URL ${index + 1}`, hint: "https:// URL · 300 characters maximum", value: draft[slot], required: false, maximum: 300 })}
            `).join("")}
          </div>` : ""}`;
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
          <p class="muted">${live
            ? "Phase 25 listings do not carry these fields yet. Keep them in this draft for a later phase — they are not sent to the service and are not shown on any listing page."
            : "Tell a buyer exactly how to use the build. These fields are stored with the listing and shown on its page."}</p>
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
          <p class="small muted">${escapeText(draft.fullDescription || "No description yet.")}</p>
          ${live ? `
          <div class="banner banner--planned" style="margin-top:18px" role="note">
            <span class="banner-mark">NOT STORED</span>
            <p>Phase 25 listings store title, description, category, subcategory, edition, Minecraft version, loaders, tags, and preview image references. Short description, build type, difficulty, release channel, pricing, and instructions stay in this draft only.</p>
          </div>` : ""}`;
      }
      default:
        if (live) {
          return `
          <h2 id="wizard-title">Publish</h2>
          <p class="muted">Save this listing to your account, then publish it into marketplace search. Drafts stay private to you; only published listings appear in public search, and every check below runs on the service.</p>
          <div class="state-card" role="note" data-publish-panel>
            <div class="state-head"><span class="badge badge--current">Listing service</span><h3>${editId ? "Save changes to your draft" : "Save and publish"}</h3></div>
            <p>The service verifies an active account, the Creator entitlement, an active creator profile, and an accepted creator agreement before publishing — and answers with its own actionable message when something is missing. The browser never claims a listing is published.</p>
            <ul class="state-list">
              <li>Saving writes a draft only. Nothing is written to your browser storage.</li>
              <li>Publishing is refused, honestly, until every prerequisite is met.</li>
              <li>Listings carry no price in this phase: there is no checkout, and no control here can start one.</li>
            </ul>
            <div class="state-actions">
              <button type="button" class="button button-primary" data-wizard-save>${editId ? "Save changes" : "Save draft"}</button>
              <button type="button" class="button button-secondary" data-wizard-publish>Publish now</button>
            </div>
            <p class="field-hint" data-publish-status role="status" aria-live="polite" style="margin-top:12px"></p>
          </div>`;
        }
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

  /** The exact body `POST/PATCH /creator/listing` accepts. No owner, status, id, price, or verification travels. */
  function serviceBody() {
    return {
      title: (draft.title ?? "").trim(),
      description: (draft.fullDescription ?? "").trim(),
      category: draft.category,
      subcategory: (draft.subcategory ?? "").trim(),
      edition: draft.edition,
      minecraftVersions: [(draft.minecraftVersion ?? "").trim()].filter(Boolean),
      loaders: draft.loader ? [draft.loader] : [],
      tags: (draft.tags ?? []).map((tag) => String(tag).trim()).filter(Boolean),
      imageReferences: [draft.image0, draft.image1, draft.image2, draft.image3]
        .map((reference) => (reference ?? "").trim()).filter(Boolean),
    };
  }

  function publishStatus(html) {
    const status = panel.querySelector("[data-publish-status]");
    if (status) status.innerHTML = html;
  }

  /** Validates every step; on failure, jumps to the first failing step and shows its errors. Returns true when clean. */
  function validateAllSteps() {
    collectDraft();
    const failing = STEPS.findIndex((step) => validateStep(step.id, draft, { live }).length > 0);
    if (failing === -1) return true;
    currentStep = failing;
    render();
    renderErrors(validateStep(STEPS[failing].id, draft, { live }));
    announce(`Step ${failing + 1} needs attention before saving.`);
    return false;
  }

  async function runSave({ publish }) {
    if (!validateAllSteps()) return;
    if (editContext === "loading") { publishStatus("Your listing is still loading — try again in a moment."); return; }
    if (editContext === "missing") { publishStatus("This address is not one of your listings, or it does not exist."); return; }
    if (editContext === "unauthorized") { publishStatus('Sign in to save listings. <a href="../signin.html">Sign in</a>'); return; }
    if (editContext === "error") { publishStatus("Your listing could not be loaded, so saving is blocked. Reload the page to try again."); return; }
    const saveButton = panel.querySelector("[data-wizard-save]");
    const publishButton = panel.querySelector("[data-wizard-publish]");
    if (saveButton) saveButton.disabled = true;
    if (publishButton) publishButton.disabled = true;
    publishStatus("Saving to the account service…");
    const body = serviceBody();
    const saved = editId
      ? await adapters.creator.updateDraft(editId, body)
      : await adapters.creator.createDraft(body);
    if (saved.status !== RESULT.OK) {
      const signIn = saved.status === RESULT.UNAUTHORIZED ? ' <a href="../signin.html">Sign in</a>' : "";
      publishStatus(`${escapeText(saved.message ?? "The service refused the save.")}${signIn}`);
      if (saveButton) saveButton.disabled = false;
      if (publishButton) publishButton.disabled = false;
      announce("The service refused the save.");
      return;
    }
    const listingId = saved.payload?.id ?? editId;
    if (!publish) {
      publishStatus(`Draft saved — ${escapeText(listingId)}. It is private until you publish. <a href="index.html">Open your listings</a>`);
      announce("Draft saved on the account service.");
      if (saveButton) saveButton.disabled = false;
      if (publishButton) publishButton.disabled = false;
      return;
    }
    publishStatus("Publishing…");
    const published = await adapters.creator.publishListing(listingId);
    if (published.status === RESULT.OK) {
      publishStatus(`Published. <a href="../marketplace/build.html?build=${encodeURIComponent(listingId)}">View it on the marketplace</a> · <a href="index.html">Your listings</a>`);
      announce("Listing published. It is now visible in marketplace search.");
      return;
    }
    const needsOnboarding = /agreement|onboarding/i.test(published.message ?? "");
    publishStatus(`${escapeText(published.message ?? "The service refused the publish.")}${needsOnboarding ? ' <a href="../onboarding/index.html">Open onboarding</a>' : ""}`);
    announce(published.message ?? "The service refused the publish.");
    if (saveButton) saveButton.disabled = false;
    if (publishButton) publishButton.disabled = false;
  }

  /** Live edit mode: load the owned listing once and prefill the draft from the service's own row. */
  function loadEditListing() {
    if (!live || !editId) return;
    adapters.creator.listMyListings().then((result) => {
      const summaryElement = root.querySelector("[data-draft-summary]");
      const finish = (context, message) => {
        editContext = context;
        if (summaryElement) summaryElement.textContent = message;
      };
      if (result.status === RESULT.UNAUTHORIZED) {
        finish("unauthorized", "Sign in to edit this listing. Your listings are stored on the account service.");
        return;
      }
      if (result.status !== RESULT.OK) {
        finish("error", "This listing could not be loaded from the account service, so saving is blocked.");
        return;
      }
      const row = (result.payload?.listings ?? []).find((item) => item.id === editId);
      if (!row) {
        finish("missing", "That listing does not exist, or it is not one of your listings.");
        return;
      }
      draft.title = row.title ?? "";
      draft.fullDescription = row.description ?? "";
      draft.shortDescription = (row.description ?? "").slice(0, LIMITS.shortDescription.maximum);
      draft.category = row.category ?? "";
      draft.subcategory = row.subcategory ?? "";
      draft.edition = row.edition ?? "";
      draft.minecraftVersion = row.minecraftVersions?.[0] ?? "";
      draft.loader = row.loaders?.[0] ?? "";
      draft.tags = Array.isArray(row.tags) ? [...row.tags] : [];
      const references = Array.isArray(row.imageReferences) ? row.imageReferences : [];
      draft.image0 = references[0] ?? "";
      draft.image1 = references[1] ?? "";
      draft.image2 = references[2] ?? "";
      draft.image3 = references[3] ?? "";
      finish("ready", `Editing ${row.title} (${editId}). Saving writes your changes to the service; publishing happens from this step.`);
      render();
    });
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
    next.textContent = currentStep === STEPS.length - 1
      ? (live ? "Use the save and publish controls below" : "Publishing not available")
      : "Continue";
    next.disabled = currentStep === STEPS.length - 1;
    if (live && STEPS[currentStep].id === "publish") {
      panel.querySelector("[data-wizard-save]")?.addEventListener("click", () => runSave({ publish: false }));
      panel.querySelector("[data-wizard-publish]")?.addEventListener("click", () => runSave({ publish: true }));
      if (editContext === "loading") publishStatus("Loading your listing from the account service…");
      if (editContext === "unauthorized") publishStatus('Sign in to save listings. <a href="../signin.html">Sign in</a>');
      if (editContext === "missing") publishStatus("This address is not one of your listings, or it does not exist.");
      if (editContext === "error") publishStatus("Your listing could not be loaded, so saving is blocked. Reload the page to try again.");
    }
    document.getElementById("wizard-title")?.focus?.();
  }

  root.querySelector("[data-wizard-back]").addEventListener("click", () => {
    if (currentStep > 0) { currentStep -= 1; render(); announce(`Step ${currentStep + 1} of ${STEPS.length}: ${STEPS[currentStep].label}.`); }
  });
  root.querySelector("[data-wizard-next]").addEventListener("click", () => {
    collectDraft();
    const errors = validateStep(STEPS[currentStep].id, draft, { live });
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
    summary.textContent = live
      ? "Fields are saved only when you press a save control on the final step. Nothing is written to your browser storage. Phase 25 listings carry no price."
      : `Draft lives in this page only. Nothing is uploaded, and nothing is written to your browser storage. BuildPlan limits for reference: ${BUILD_PLAN_LIMITS.maximumOperations} operations, ${BUILD_PLAN_LIMITS.maximumWidth}×${BUILD_PLAN_LIMITS.maximumHeight}×${BUILD_PLAN_LIMITS.maximumDepth} blocks.`;
    if (live && editId) summary.textContent = "Loading your listing from the account service…";
  }
  loadEditListing();
  render();
}

export { LIMITS as LISTING_LIMITS, STEPS as LISTING_STEPS, PLACEHOLDER_FEE_PERCENT };
