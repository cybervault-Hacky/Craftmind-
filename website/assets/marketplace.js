/**
 * Marketplace page controllers (Phase 21, catalog reads since Phase 25): the marketplace home and the build detail page.
 *
 * Three modes, never mixed:
 *
 *   * **Live** — an account service is configured: search, filters, and pagination query `GET /marketplace/listings`
 *     for PUBLISHED rows only, detail loads one public projection, and every name shown is the creator's real handle
 *     and display name from the service. Ordering is the service's declared order (newest published first); there is
 *     no relevance, trending, or popularity claim anywhere, and an empty result is a success.
 *   * **Preview** — `?preview=1`: the sample catalog, always labelled in the interface.
 *   * **Unconfigured** — neither: an honest unavailable state plus the full, working local filter controls.
 *
 * Purchase controls stay unavailable in every mode (there is no checkout in this phase), and the saved-items list
 * stays in memory for the current page only (nothing is written to browser storage).
 */

import { RESULT, createAdapters } from "./adapters.js";
import { describeCompatibility } from "./compatibility.js";
import { listingCardMarkup, placeholderCoverMarkup, ratingMarkup } from "./components.js";
import { DEMO_LISTINGS, PREVIEW_CREATORS, creatorByHandle, listingById, previewRequested } from "./preview-catalog.js";
import { STATE, announce, ensureLiveRegion, escapeAttribute, escapeText, formatTimestamp, orDash, renderLoading, renderState } from "./state.js";

const savedListings = new Set();
/** Cards loaded during this page visit, keyed by listing id — the only source the saved region draws from in live mode. */
const liveCardsById = new Map();
/** Live catalog page size; the service allows at most 48 per request. */
const LIVE_PAGE_SIZE = 12;

function creatorInitials(displayName) {
  const words = String(displayName ?? "").split(/\s+/).filter(Boolean).slice(0, 2);
  return words.map((word) => word[0].toUpperCase()).join("") || "•";
}

/** Maps the service's public listing projection onto the card shape the shared components render. */
function liveListingCard(item) {
  return {
    id: item.id,
    title: item.title,
    summary: item.summary ?? "",
    creator: item.creator?.handle ?? "",
    creatorDisplayName: item.creator?.displayName ?? "",
    creatorInitials: creatorInitials(item.creator?.displayName),
    edition: item.edition,
    minecraftVersion: item.minecraftVersions?.[0] ?? null,
    loader: item.loaders?.[0] ?? null,
    category: item.category,
    subcategory: item.subcategory,
    tags: item.tags ?? [],
    // Phase 25 stores no price, so the card shows an honest dash — never a fabricated number or a "free" claim.
    priceLabel: null,
    updatedAt: item.publishedAt,
  };
}

function previewBanner(preview) {
  if (!preview) return "";
  return `
  <div class="banner banner--preview banner--section" role="note">
    <span class="banner-mark">PREVIEW DATA</span>
    <p><strong>This catalog is sample data, not inventory.</strong> No marketplace backend exists yet, so these cards exist
    to design and review the layout. Prices are placeholders, purchase controls are unavailable, and nothing here can be
    bought, saved permanently, or sold.</p>
  </div>`;
}

function compatibilityFor(listing) {
  return describeCompatibility({
    edition: listing.edition,
    minecraftVersion: listing.minecraftVersion,
    loader: listing.loader,
    loaderVersion: listing.loaderVersion,
  });
}

function applyFilters(listings, filters) {
  const query = filters.query.trim().toLowerCase();
  let results = listings.filter((listing) => {
    if (filters.category && listing.category !== filters.category) return false;
    if (filters.edition && listing.edition !== filters.edition) return false;
    if (filters.freeOnly && !/free/i.test(listing.priceLabel ?? "")) return false;
    if (!query) return true;
    const haystack = [listing.title, listing.summary, listing.category, listing.subcategory, ...(listing.tags ?? [])]
      .filter(Boolean).join(" ").toLowerCase();
    return haystack.includes(query);
  });
  switch (filters.sort) {
    case "title": results = [...results].sort((left, right) => String(left.title).localeCompare(String(right.title))); break;
    case "popular": results = [...results].sort((left, right) => (right.screenshots ?? 0) - (left.screenshots ?? 0)); break;
    case "newest": results = [...results].sort((left, right) => Date.parse(right.updatedAt ?? "") - Date.parse(left.updatedAt ?? "") || 0); break;
    case "price-asc": results = [...results].sort((left, right) => String(left.priceLabel).localeCompare(String(right.priceLabel))); break;
    default: break;
  }
  return results;
}

function listingCardOptions(preview) {
  return {
    preview,
    creatorHref: null,
    actionLabel: "View details",
  };
}

function renderCatalog(region, listings, { preview, emptyTitle, emptyMessage, onSavedChange = null, detailBase = "build.html" }) {
  if (listings.length === 0) {
    renderState(region, {
      kind: STATE.EMPTY,
      title: emptyTitle,
      message: emptyMessage,
    });
    return;
  }
  region.dataset.state = STATE.POPULATED;
  region.className = "listing-grid";
  region.innerHTML = listings.map((listing) => listingCardMarkup(listing, {
    ...listingCardOptions(preview),
    saved: savedListings.has(listing.id),
    detailHref: `${detailBase}?build=${encodeURIComponent(listing.id)}${preview ? "&preview=1" : ""}`,
    creatorHref: `../creators/profile.html?creator=${encodeURIComponent(listing.creator)}${preview ? "&preview=1" : ""}`,
    compatibility: compatibilityFor(listing),
  })).join("");
  for (const button of region.querySelectorAll("[data-save-listing]")) {
    button.addEventListener("click", () => {
      toggleSaved(region, listings, button.dataset.saveListing, preview);
      onSavedChange?.();
    });
  }
}

function toggleSaved(region, listings, listingId, preview) {
  if (savedListings.has(listingId)) {
    savedListings.delete(listingId);
    announce("Removed from saved items for this page.");
  } else {
    savedListings.add(listingId);
    announce("Saved for this page only. Nothing was sent anywhere and nothing was stored in your browser.");
  }
  renderCatalog(region, listings, { preview, emptyTitle: "No builds match", emptyMessage: "Adjust the filters above." });
}

function renderMarketplaceHome(root, preview) {
  const catalog = root.querySelector("[data-catalog-region]");
  const filters = { query: "", category: "", edition: "", freeOnly: false, sort: "relevance" };
  const search = root.querySelector("[data-catalog-search]");
  const categorySelect = root.querySelector("[data-catalog-category]");
  const editionSelect = root.querySelector("[data-catalog-edition]");
  const sortSelect = root.querySelector("[data-catalog-sort]");
  const freeToggle = root.querySelector("[data-catalog-free]");
  const resetButton = root.querySelector("[data-catalog-reset]");
  const statusLine = root.querySelector("[data-catalog-status]");
  const savedRegion = root.querySelector("[data-saved-region]");
  const featuredRegion = root.querySelector("[data-featured-region]");
  const creatorsRegion = root.querySelector("[data-creators-region]");

  const banner = root.querySelector("[data-preview-banner]");
  if (banner) banner.innerHTML = previewBanner(preview);

  const unavailable = () => renderState(catalog, {
    kind: STATE.UNAVAILABLE,
    title: "No listings are available",
    message: "No account service is connected to this site, so there is no marketplace inventory to read. Search, filters, and sorting work locally, but there is no listing store to query.",
    details: [
      "Listing search, categories, and creator filters read from a connected account service.",
      "Purchase and download of builds stay unavailable in this phase — no checkout exists.",
      "Review the layout with sample data by adding ?preview=1 to this address.",
    ],
  });

  const update = () => {
    if (!preview) { unavailable(); if (savedRegion) renderSaved(savedRegion, preview); updateStatus(statusLine, null, preview); return; }
    const results = applyFilters(DEMO_LISTINGS, filters);
    renderCatalog(catalog, results, {
      preview,
      emptyTitle: "No sample builds match",
      emptyMessage: "Nothing in the preview set matches these filters. Reset them to see all four sample records.",
      onSavedChange: refreshSaved,
    });
    if (savedRegion) renderSaved(savedRegion, preview);
    updateStatus(statusLine, results.length, preview);
  };

  search?.addEventListener("input", () => { filters.query = search.value; update(); });
  categorySelect?.addEventListener("change", () => { filters.category = categorySelect.value; update(); });
  editionSelect?.addEventListener("change", () => { filters.edition = editionSelect.value; update(); });
  sortSelect?.addEventListener("change", () => { filters.sort = sortSelect.value; update(); });
  freeToggle?.addEventListener("change", () => { filters.freeOnly = freeToggle.checked; update(); });
  resetButton?.addEventListener("click", () => {
    filters.query = ""; filters.category = ""; filters.edition = ""; filters.freeOnly = false; filters.sort = "relevance";
    if (search) search.value = "";
    if (categorySelect) categorySelect.value = "";
    if (editionSelect) editionSelect.value = "";
    if (sortSelect) sortSelect.value = "relevance";
    if (freeToggle) freeToggle.checked = false;
    update();
    announce("Filters cleared.");
  });

  if (featuredRegion) {
    featuredRegion.innerHTML = preview
      ? `<div class="listing-grid">${DEMO_LISTINGS.slice(0, 2).map((listing) => listingCardMarkup(listing, {
          ...listingCardOptions(preview),
          saved: savedListings.has(listing.id),
          detailHref: `build.html?build=${encodeURIComponent(listing.id)}&preview=1`,
          compatibility: compatibilityFor(listing),
        })).join("")}</div>`
      : renderState(featuredRegion, { kind: STATE.UNAVAILABLE, title: "No featured ranking", message: "This site has no account service connected, so nothing can be featured here. There is no featured or trending ranking in this phase in any case: discovery is search, filters, and creator links over every published listing." });
  }

  if (creatorsRegion) {
    creatorsRegion.innerHTML = preview
      ? `<div class="card-grid">${DEMO_LISTINGS.map((listing) => creatorByHandle(listing.creator)).filter(Boolean)
          .filter((creator, index, all) => all.findIndex((candidate) => candidate.handle === creator.handle) === index)
          .map((creator) => `
            <article class="creator-card">
              <span class="avatar avatar-lg" aria-hidden="true">${escapeText(creator.initials)}</span>
              <h3>${escapeText(creator.displayName)}</h3>
              <p class="small muted">Creator profile layout preview. No follower count, rating, or earnings figure exists, so none is shown.</p>
              <a class="text-link" href="creators/profile.html?creator=${encodeURIComponent(creator.handle)}&preview=1">Open profile layout <span aria-hidden="true">→</span></a>
            </article>`).join("")}</div>`
      : renderState(creatorsRegion, { kind: STATE.UNAVAILABLE, title: "Nothing to show yet", message: "Creator highlights need a connected account service; this site has none, so no creator can be listed here." });
  }

  const refreshSaved = () => { if (savedRegion) renderSaved(savedRegion, preview); };
  if (savedRegion) savedRegion.dataset.wired = "true";
  update();
}

/**
 * Live catalog: every keystroke and toggle re-queries the service for PUBLISHED rows. Pagination is bounded by the
 * service (limit ≤ 48, offset ≤ 100000); ordering is the service's declared newest-first order, optionally sorted
 * by title within the loaded page — no other sort exists, because no relevance or popularity signal does.
 */
function renderMarketplaceLive(root, adapters) {
  const catalog = root.querySelector("[data-catalog-region]");
  const search = root.querySelector("[data-catalog-search]");
  const categorySelect = root.querySelector("[data-catalog-category]");
  const editionSelect = root.querySelector("[data-catalog-edition]");
  const sortSelect = root.querySelector("[data-catalog-sort]");
  const freeToggle = root.querySelector("[data-catalog-free]");
  const resetButton = root.querySelector("[data-catalog-reset]");
  const statusLine = root.querySelector("[data-catalog-status]");
  const savedRegion = root.querySelector("[data-saved-region]");
  const featuredRegion = root.querySelector("[data-featured-region]");
  const creatorsRegion = root.querySelector("[data-creators-region]");
  const versionInput = root.querySelector("[data-catalog-version]");
  const loaderSelect = root.querySelector("[data-catalog-loader]");
  const creatorInput = root.querySelector("[data-catalog-creator]");
  const prevButton = root.querySelector("[data-catalog-prev]");
  const nextButton = root.querySelector("[data-catalog-next]");
  const pageInfo = root.querySelector("[data-catalog-page]");
  const banner = root.querySelector("[data-preview-banner]");
  if (banner) banner.innerHTML = "";

  // Live mode restricts sort to what can honestly be ordered (published time; title within the loaded page) and hides
  // the free-only toggle: Phase 25 listings store no price, so a price filter would be a lie.
  if (sortSelect) {
    sortSelect.innerHTML = [
      '<option value="newest">Newest published</option>',
      '<option value="title">Title (loaded results)</option>',
    ].join("");
  }
  if (freeToggle) {
    const wrapper = freeToggle.closest(".choice") ?? freeToggle.closest(".field");
    if (wrapper) wrapper.hidden = true;
  }

  const filters = { q: "", category: "", edition: "", version: "", loader: "", creator: "", sort: "newest", offset: 0 };
  let lastItems = [];
  let lastTotal = 0;
  let lastHasMore = false;
  let requestSerial = 0;
  let debounceTimer = null;

  function renderItems(items) {
    lastItems = items;
    renderCatalog(catalog, items, {
      preview: false,
      emptyTitle: "No published listings match",
      emptyMessage: "Adjust or clear the filters. Only listings a creator has published are searchable, and drafts and archived listings never appear here.",
      onSavedChange: refreshSaved,
    });
    refreshSaved();
  }

  function refreshSaved() {
    if (savedRegion) renderSaved(savedRegion, false, true, () => renderItems(lastItems));
  }

  async function update() {
    const serial = ++requestSerial;
    renderLoading(catalog, { rows: 6, title: "Searching published listings" });
    const params = { limit: String(LIVE_PAGE_SIZE), offset: String(filters.offset) };
    for (const key of ["q", "category", "edition", "version", "loader", "creator"]) {
      if (filters[key]) params[key] = filters[key];
    }
    const result = await adapters.marketplace.searchListings(params);
    if (serial !== requestSerial) return;
    if (result.status !== RESULT.OK) {
      renderState(catalog, {
        kind: STATE.ERROR,
        title: "The catalog could not be read",
        message: result.message ?? "The catalog service did not answer.",
        details: ["Nothing was changed. Try again, or reset the filters."],
      });
      if (statusLine) statusLine.textContent = "The catalog service could not be reached with these filters.";
      if (prevButton) prevButton.disabled = true;
      if (nextButton) nextButton.disabled = true;
      return;
    }
    const payload = result.payload ?? {};
    let items = (payload.items ?? []).map(liveListingCard);
    if (filters.sort === "title") items = [...items].sort((left, right) => left.title.localeCompare(right.title));
    for (const item of items) liveCardsById.set(item.id, item);
    lastTotal = Number(payload.total ?? 0);
    lastHasMore = Boolean(payload.hasMore);
    renderItems(items);
    const shownFrom = lastTotal === 0 ? 0 : filters.offset + 1;
    const shownTo = filters.offset + items.length;
    if (statusLine) {
      const orderNote = filters.sort === "title" ? " Title order applies to the loaded page only." : "";
      statusLine.textContent = `${lastTotal} published listing(s) match. Showing ${shownFrom}–${shownTo}.${orderNote}`;
    }
    if (pageInfo) {
      const pages = Math.max(1, Math.ceil(lastTotal / LIVE_PAGE_SIZE));
      pageInfo.textContent = lastTotal === 0 ? "No pages" : `Page ${Math.floor(filters.offset / LIVE_PAGE_SIZE) + 1} of ${pages}`;
    }
    if (prevButton) prevButton.disabled = filters.offset === 0;
    if (nextButton) nextButton.disabled = !lastHasMore;
    announce(`${lastTotal} published listing(s) match these filters.`);
  }

  const schedule = () => {
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => { filters.offset = 0; update(); }, 250);
  };

  search?.addEventListener("input", () => { filters.q = search.value; schedule(); });
  categorySelect?.addEventListener("change", () => { filters.category = categorySelect.value; filters.offset = 0; update(); });
  editionSelect?.addEventListener("change", () => { filters.edition = editionSelect.value; filters.offset = 0; update(); });
  versionInput?.addEventListener("input", () => { filters.version = versionInput.value.trim(); schedule(); });
  loaderSelect?.addEventListener("change", () => { filters.loader = loaderSelect.value; filters.offset = 0; update(); });
  creatorInput?.addEventListener("input", () => { filters.creator = creatorInput.value.trim().toLowerCase(); schedule(); });
  sortSelect?.addEventListener("change", () => { filters.sort = sortSelect.value; update(); });
  prevButton?.addEventListener("click", () => {
    filters.offset = Math.max(0, filters.offset - LIVE_PAGE_SIZE);
    update();
  });
  nextButton?.addEventListener("click", () => {
    if (!lastHasMore) return;
    filters.offset += LIVE_PAGE_SIZE;
    update();
  });
  resetButton?.addEventListener("click", () => {
    filters.q = ""; filters.category = ""; filters.edition = ""; filters.version = "";
    filters.loader = ""; filters.creator = ""; filters.sort = "newest"; filters.offset = 0;
    if (search) search.value = "";
    if (categorySelect) categorySelect.value = "";
    if (editionSelect) editionSelect.value = "";
    if (versionInput) versionInput.value = "";
    if (loaderSelect) loaderSelect.value = "";
    if (creatorInput) creatorInput.value = "";
    if (sortSelect) sortSelect.value = "newest";
    update();
    announce("Filters cleared.");
  });

  if (featuredRegion) {
    renderState(featuredRegion, {
      kind: STATE.UNAVAILABLE,
      title: "No featured ranking",
      message: "There is no featured or trending ranking in this phase. Discovery is search, category, compatibility, and creator filters over every published listing.",
    });
  }
  if (creatorsRegion) {
    renderState(creatorsRegion, {
      kind: STATE.EMPTY,
      title: "Creator highlights",
      message: "There is no directory ranking in this phase. Every listing card carries its real creator, and each name links to that creator's profile.",
    });
  }
  if (savedRegion) savedRegion.dataset.wired = "true";
  update();
}

function updateStatus(statusLine, count, preview) {
  if (!statusLine) return;
  if (!preview) {
    statusLine.textContent = "No catalog service is connected, so this page shows no listings.";
    return;
  }
  statusLine.textContent = `${count} sample record(s) shown. Preview data only — nothing here is real inventory.`;
}

function renderSaved(region, preview, live = false, rerenderCatalog = null) {
  if (live && savedListings.size === 0) {
    renderState(region, {
      kind: STATE.EMPTY,
      title: "No saved builds yet",
      message: "Save a card to keep it here for this visit. Saving is a page-local convenience: nothing is sent to the service and nothing is written to your browser.",
    });
    return;
  }
  if (!preview || savedListings.size === 0) {
    renderState(region, {
      kind: STATE.EMPTY,
      title: "No saved builds yet",
      message: preview
        ? "Saving a sample card in preview mode keeps it in this page for the current visit. Saves are not sent anywhere and are not stored in your browser."
        : "Saved builds will appear here once the marketplace can serve real listings.",
    });
    return;
  }
  const saved = live
    ? [...savedListings].map((id) => liveCardsById.get(id)).filter(Boolean)
    : [...savedListings].map((id) => listingById(id)).filter(Boolean);
  region.dataset.state = STATE.POPULATED;
  region.className = "listing-grid";
  region.innerHTML = saved.map((listing) => listingCardMarkup(listing, {
    preview, saved: true, compatibility: compatibilityFor(listing),
    detailHref: `build.html?build=${encodeURIComponent(listing.id)}${preview ? "&preview=1" : ""}`,
  })).join("");
  for (const button of region.querySelectorAll("[data-save-listing]")) {
    button.addEventListener("click", () => {
      savedListings.delete(button.dataset.saveListing);
      const catalog = document.querySelector("[data-catalog-region]");
      if (live && rerenderCatalog) {
        renderSaved(region, false, true, rerenderCatalog);
        rerenderCatalog();
      } else {
        renderSaved(region, preview);
        if (catalog) {
          renderCatalog(catalog, applyFilters(DEMO_LISTINGS, { query: "", category: "", edition: "", freeOnly: false, sort: "relevance" }), {
            preview, emptyTitle: "No builds match", emptyMessage: "Adjust the filters above.", onSavedChange: () => renderSaved(region, preview),
          });
        }
      }
      announce("Removed from saved items for this page.");
    });
  }
}

/** Live detail: one public projection of a PUBLISHED listing, with the creator's real attribution. */
function renderBuildLive(region, listingId, adapters) {
  if (!listingId) {
    renderState(region, {
      kind: STATE.EMPTY,
      title: "No build selected",
      message: "Open a listing from the marketplace page. Each card links here with a listing identifier.",
    });
    return;
  }
  renderLoading(region, { rows: 6, title: "Loading the listing" });
  adapters.marketplace.getListing(listingId).then((result) => {
    if (result.status !== RESULT.OK) {
      renderState(region, {
        kind: STATE.ERROR,
        title: "This build cannot be opened",
        message: result.message ?? "That listing does not exist, or it is not available.",
        details: [
          "Only listings a creator has published are served here; drafts and archived listings stay private.",
          "If the address was mistyped, return to the marketplace and search again.",
        ],
        action: { label: "Back to marketplace", href: "index.html" },
      });
      return;
    }
    const item = result.payload ?? {};
    const card = liveListingCard(item);
    const compatibility = compatibilityFor(card);
    const images = Array.isArray(item.imageReferences) ? item.imageReferences.filter((reference) => typeof reference === "string") : [];
    const creatorHandle = item.creator?.handle ?? "";
    region.dataset.state = STATE.POPULATED;
    region.innerHTML = `
    <div class="detail-layout">
      <div class="stack-lg">
        <div>
          <p class="crumbs"><a href="index.html">Marketplace</a> <span aria-hidden="true">/</span> <span>${escapeText(item.category ?? "")}</span></p>
          <h2>${escapeText(item.title ?? "Untitled listing")}</h2>
          <div class="row">
            <span class="small muted">Published ${escapeText(item.publishedAt ? formatTimestamp(item.publishedAt) : "")}</span>
            ${creatorHandle ? `<span class="small muted">Created by <a href="../creators/profile.html?creator=${escapeAttribute(creatorHandle)}">${escapeText(item.creator?.displayName ?? creatorHandle)}</a></span>` : ""}
          </div>
          <p class="muted" style="margin-top:14px">${escapeText(item.summary ?? "")}</p>
        </div>
        <div class="panel">
          <div class="panel-head"><div><h3>Preview</h3><p class="small muted">${images.length > 0 ? "Images provided by the creator as references; CraftMind does not host or review them." : "No preview image was provided for this listing, so this is an abstract placeholder rather than a picture of the build."}</p></div></div>
          ${images.length > 0
            ? `<div class="screenshot-grid">${images.slice(0, 4).map((reference) => `<img src="${escapeAttribute(reference)}" alt="Creator-provided preview for ${escapeAttribute(item.title ?? "this listing")}" loading="lazy" style="width:100%;border-radius:10px;border:1px solid var(--line-soft);object-fit:cover;min-height:160px">`).join("")}</div>`
            : `<div class="listing-cover" style="border-radius:10px;border:1px solid var(--line-soft)" role="img" aria-label="Abstract placeholder cover. No preview image has been provided for this listing.">${placeholderCoverMarkup(item.id ?? listingId)}</div>`}
        </div>
        <div class="panel">
          <div class="panel-head"><div><h3>Description</h3></div></div>
          <p class="muted">${escapeText(item.description ?? "").replace(/\n/g, "<br>")}</p>
        </div>
        <div class="panel">
          <div class="panel-head"><div><h3>Ratings and reviews</h3><p class="small muted">Reviews arrive with a later marketplace phase. No review data exists.</p></div></div>
          ${ratingMarkup({ average: null, count: 0 })}
          <div class="state-card state-card--empty" style="margin-top:16px">
            <div class="state-head"><span class="badge badge--muted">Nothing yet</span><h3>No reviews</h3></div>
            <p>This build has not been reviewed. Reviews, ratings, and creator responses are part of a marketplace service that is not implemented yet.</p>
          </div>
        </div>
        <div class="panel">
          <div class="panel-head"><div><h3>Related builds</h3><p class="small muted">Related listings would need a similarity ranking, which does not exist in this phase.</p></div></div>
          <div class="state-card state-card--unavailable"><div class="state-head"><span class="badge badge--planned">Not available yet</span><h3>Nothing to show</h3></div><p>No related builds can be listed without a ranking service.</p></div>
        </div>
      </div>
      <aside class="detail-aside stack">
        <div class="panel">
          <h3>Get this build</h3>
          <p class="small muted">Purchases are coming soon. No transaction, download, or entitlement exists for any listing, and no control on this page can start one.</p>
          <div class="stack">
            <button type="button" class="button button-primary" disabled>Buy · not available</button>
            <button type="button" class="button button-secondary" disabled>Download · not available</button>
          </div>
          <p class="price-note">Listing price: ${escapeText(orDash(null))}. Listings in this phase carry no price, so no figure is shown.</p>
        </div>
        <div class="panel">
          <h3>Compatibility</h3>
          ${keyValueRows([
            ["Edition", item.edition],
            ["Minecraft version", (item.minecraftVersions ?? []).join(", ")],
            ["Loader", (item.loaders ?? []).join(", ")],
            ["CraftMind support", compatibility.status],
            ["Certification", compatibility.certification],
          ])}
          <p class="price-note">${escapeText(compatibility.summary)}</p>
        </div>
        <div class="panel">
          <h3>Listing facts</h3>
          ${keyValueRows([
            ["Category", item.category],
            ["Subcategory", item.subcategory],
            ["Creator", item.creator?.displayName ? `${item.creator.displayName} (${creatorHandle})` : null],
            ["Published", item.publishedAt ? formatTimestamp(item.publishedAt) : null],
          ])}
          ${(item.tags ?? []).length > 0 ? `<ul class="tag-list" style="margin-top:14px">${(item.tags ?? []).map((tag) => `<li class="tag">${escapeText(tag)}</li>`).join("")}</ul>` : ""}
        </div>
      </aside>
    </div>`;
  });
}

function renderBuildDetail(root, preview, adapters) {
  const params = new URLSearchParams(globalThis.location.search);
  const listingId = params.get("build");
  const region = root.querySelector("[data-build-region]");
  const banners = root.querySelector("[data-build-banner]");
  if (banners) banners.innerHTML = previewBanner(preview);

  if (!preview && adapters?.marketplace?.configured) {
    renderBuildLive(region, listingId, adapters);
    return;
  }

  const listing = preview ? listingById(listingId ?? "") : null;
  if (!listing) {
    renderState(region, {
      kind: listingId ? STATE.UNAVAILABLE : STATE.EMPTY,
      title: listingId ? "This build cannot be opened" : "No build selected",
      message: listingId
        ? "Build details need a connected account service, and this site has none, so no listing can be loaded from an address."
        : "Open a listing from the marketplace page. Each card links here with a listing identifier.",
      details: preview ? [] : [
        "The page will show the cover, screenshots, creator, compatibility, price, and instructions of a real listing.",
        "Add ?preview=1 to review the layout with sample data.",
      ],
      action: { label: "Back to marketplace", href: "index.html" },
    });
    return;
  }

  const compatibility = compatibilityFor(listing);
  const creator = creatorByHandle(listing.creator);
  region.dataset.state = STATE.POPULATED;
  region.innerHTML = `
    <div class="detail-layout">
      <div class="stack-lg">
        <div>
          <p class="crumbs"><a href="index.html">Marketplace</a> <span aria-hidden="true">/</span> <span>${escapeText(listing.category)}</span></p>
          <h2>${escapeText(listing.title)}</h2>
          <div class="row">${PREVIEW_LABEL}${creator ? `<span class="small muted">Created by ${escapeText(creator.displayName)}</span>` : ""}</div>
          <p class="muted" style="margin-top:14px">${escapeText(listing.summary)}</p>
        </div>
        <div class="panel">
          <div class="panel-head"><div><h3>Preview area</h3><p class="small muted">No screenshot exists for a sample record, so this is an abstract placeholder rather than a picture of a build.</p></div></div>
          <div class="listing-cover" style="border-radius:10px;border:1px solid var(--line-soft)" role="img" aria-label="Abstract placeholder cover. No screenshot has been provided.">${placeholderCoverMarkup(listing.id)}</div>
          <div class="screenshot-grid">${Array.from({ length: listing.screenshots ?? 0 }, (_, index) => `<span class="screenshot">SCREENSHOT ${index + 1} · NOT PROVIDED</span>`).join("")}</div>
        </div>
        <div class="panel">
          <div class="panel-head"><div><h3>Description</h3></div></div>
          <p class="muted">${escapeText(listing.summary)}</p>
          <h4 class="small">Instructions</h4>
          <p class="muted small">Instructions are written by the creator and appear here once a listing exists. Nothing is generated or invented by CraftMind.</p>
          <h4 class="small">Creator notes</h4>
          <p class="muted small">${escapeText(orDash(null))} — a sample record carries no creator notes.</p>
          <h4 class="small">Usage and license</h4>
          <p class="muted small">No license terms exist for this sample record. Real listings will state their usage and license terms before purchase.</p>
        </div>
        <div class="panel">
          <div class="panel-head"><div><h3>Ratings and reviews</h3><p class="small muted">Reviews arrive with the marketplace service. No review data exists.</p></div></div>
          ${ratingMarkup({ average: null, count: 0 })}
          <div class="state-card state-card--empty" style="margin-top:16px">
            <div class="state-head"><span class="badge badge--muted">Nothing yet</span><h3>No reviews</h3></div>
            <p>This build has not been reviewed. Reviews, ratings, and creator responses are part of the marketplace service, which is not implemented yet.</p>
          </div>
        </div>
        <div class="panel">
          <div class="panel-head"><div><h3>Related builds</h3><p class="small muted">Related listings are computed by the marketplace service once it exists.</p></div></div>
          <div class="state-card state-card--unavailable"><div class="state-head"><span class="badge badge--planned">Not available yet</span><h3>Nothing to show</h3></div><p>No related builds can be listed without a catalog service.</p></div>
        </div>
      </div>
      <aside class="detail-aside stack">
        <div class="panel">
          <h3>Get this build</h3>
          <p class="small muted">Purchases are coming soon. No transaction, download, or entitlement exists for any listing, and no control on this page can start one.</p>
          <div class="stack">
            <button type="button" class="button button-primary" disabled>Buy · not available</button>
            <button type="button" class="button button-secondary" disabled>Download · not available</button>
          </div>
          <p class="price-note">Listing price: ${escapeText(orDash(listing.priceLabel))}. CraftMind does not publish a price for this sample record.</p>
        </div>
        <div class="panel">
          <h3>Compatibility</h3>
          ${keyValueRows([
            ["Edition", listing.edition],
            ["Minecraft version", listing.minecraftVersion],
            ["Loader", listing.loader],
            ["Loader version", listing.loaderVersion],
            ["CraftMind support", compatibility.status],
            ["Certification", compatibility.certification],
          ])}
          <p class="price-note">${escapeText(compatibility.buildable
            ? `Registered profile: ${compatibility.summary}.`
            : `${compatibility.summary}`)}</p>
        </div>
        <div class="panel">
          <h3>Listing facts</h3>
          ${keyValueRows([
            ["Category", listing.category],
            ["Subcategory", listing.subcategory],
            ["Build type", listing.buildType],
            ["Difficulty", listing.difficulty],
            ["Updated", listing.updatedAt ? formatTimestamp(listing.updatedAt) : null],
          ])}
          <ul class="tag-list" style="margin-top:14px">${(listing.tags ?? []).map((tag) => `<li class="tag">${escapeText(tag)}</li>`).join("")}</ul>
        </div>
      </aside>
    </div>`;
}

const PREVIEW_LABEL = '<span class="badge badge--preview">Preview data</span>';

function keyValueRows(rows) {
  return `<dl class="kv">${rows.map(([term, value]) => `<dt>${escapeText(term)}</dt><dd>${escapeText(orDash(value))}</dd>`).join("")}</dl>`;
}

/** Entry point used by `site.js` for the two marketplace pages. */
export function initMarketplace() {
  ensureLiveRegion();
  const adapters = createAdapters();
  const preview = previewRequested();
  const root = document.querySelector('[data-page="marketplace-home"]');
  const detailRoot = document.querySelector('[data-page="marketplace-build"]');
  // The adapters decide the mode: preview wins for layout review, then the live catalog when an origin is
  // configured, otherwise the honest unconfigured page. No branch invents data another branch cannot back.
  if (root) {
    if (!preview && adapters.marketplace.configured) renderMarketplaceLive(root, adapters);
    else renderMarketplaceHome(root, preview);
  }
  if (detailRoot) renderBuildDetail(detailRoot, preview, adapters);
}

/* ------------------------------------------------------------------ creators */

function verificationBadge(profile) {
  if (profile.verified) return '<span class="badge badge--current">Verified creator</span>';
  if (profile.verification === "PENDING") return '<span class="badge badge--planned">Verification pending</span>';
  return '<span class="badge badge--muted">Not verified</span>';
}

/** Live creator profile: the service's public projection plus that creator's PUBLISHED listings. */
function renderCreatorProfileLive(root, handle, adapters, { profile, listings, reviews }) {
  const showUnopenable = (message, details = []) => {
    renderState(profile, {
      kind: STATE.ERROR,
      title: handle ? "This creator profile cannot be opened" : "No creator selected",
      message,
      details,
      action: { label: "Back to creators", href: "index.html" },
    });
    renderState(listings, { kind: STATE.EMPTY, title: "No listings", message: "Listings load with the creator profile." });
    renderState(reviews, { kind: STATE.EMPTY, title: "No reviews", message: "Reviews need a marketplace service that is not implemented yet." });
  };
  if (!handle) {
    showUnopenable("Open a creator from a listing card, where each name is that creator's real handle.", ["Review the sample layout with ?preview=1."]);
    return;
  }
  renderLoading(profile, { rows: 4, title: "Loading the creator profile" });
  renderLoading(listings, { rows: 3, title: "Loading published listings" });
  adapters.creatorProfile.loadPublicProfile(handle).then(async (profileResult) => {
    if (profileResult.status !== RESULT.OK) {
      showUnopenable(profileResult.message ?? "That creator profile does not exist, or it is not publicly readable.");
      return;
    }
    const creator = profileResult.payload?.profile ?? {};
    profile.dataset.state = STATE.POPULATED;
    profile.className = "";
    profile.innerHTML = `
    <div class="panel">
      <div class="row" style="gap:20px;align-items:flex-start">
        <span class="avatar avatar-xl" aria-hidden="true">${escapeText(creatorInitials(creator.displayName))}</span>
        <div style="flex:1;min-width:240px">
          <div class="row"><h2 style="margin:0">${escapeText(creator.displayName ?? handle)}</h2>${verificationBadge(creator)}</div>
          <p class="small muted" style="margin-top:6px">@${escapeText(creator.handle ?? handle)}</p>
          <p class="muted" style="margin-top:10px">${creator.bio ? escapeText(creator.bio) : "This creator has not written a bio."}</p>
        </div>
      </div>
      ${keyValueRows([
        ["Joined", creator.createdAt ? formatTimestamp(creator.createdAt) : null],
        ["Category", creator.category],
        ["Followers", null],
        ["Average rating", null],
        ["Reviews", null],
      ])}
    </div>`;
    const searchResult = await adapters.marketplace.searchListings({ creator: handle, limit: "48" });
    if (searchResult.status !== RESULT.OK) {
      renderState(listings, {
        kind: STATE.ERROR,
        title: "Listings could not be loaded",
        message: searchResult.message ?? "The catalog service did not answer.",
      });
      return;
    }
    const payload = searchResult.payload ?? {};
    const cards = (payload.items ?? []).map(liveListingCard);
    for (const card of cards) liveCardsById.set(card.id, card);
    renderCatalog(listings, cards, {
      preview: false,
      emptyTitle: "No published listings",
      emptyMessage: "This creator has no published listings right now. Drafts and archived listings are never shown in public views.",
      detailBase: "../marketplace/build.html",
      onSavedChange: null,
    });
    renderState(reviews, {
      kind: STATE.EMPTY,
      title: "No reviews",
      message: "Reviews come from real purchases. No review data exists, so no rating is shown.",
    });
  });
}

function renderCreatorsDirectory(root, preview, adapters) {
  const banner = root.querySelector("[data-preview-banner]");
  if (banner) banner.innerHTML = previewBanner(preview);
  const region = root.querySelector("[data-creators-directory]");
  if (!region) return;
  if (!preview) {
    renderState(region, {
      kind: STATE.EMPTY,
      title: "No creator directory",
      message: adapters?.creatorProfile?.configured
        ? "The service exposes one creator profile per handle, not a list of every creator, so this directory stays empty. Open a creator from any listing card, where each name is that creator's real handle."
        : "This site has no account service connected, so no creator profile can be loaded here. Real creators appear by name on every listing card once a service is connected.",
      details: [
        "A profile shows a display name, a bio, the category a creator works in, and their published listings.",
        "Ratings appear only from real reviews, and follower counts only from a real follower service — neither exists.",
        "Open this address with ?preview=1 to review the layout with sample records.",
      ],
      action: { label: "Open the creator studio layout", href: "../creator/index.html" },
    });
    return;
  }
  region.dataset.state = STATE.POPULATED;
  region.className = "card-grid";
  region.innerHTML = PREVIEW_CREATORS.map((creator) => `
    <article class="creator-card">
      <span class="avatar avatar-lg" aria-hidden="true">${escapeText(creator.initials)}</span>
      <h3>${escapeText(creator.displayName)}</h3>
      <p class="small muted">Sample profile. Joined date, follower count, rating, and listing count are unavailable for a sample record, so none is shown.</p>
      <span class="row">${PREVIEW_BADGE_INLINE}<a class="text-link" href="profile.html?creator=${encodeURIComponent(creator.handle)}&preview=1">Open profile layout <span aria-hidden="true">→</span></a></span>
    </article>`).join("");
}

const PREVIEW_BADGE_INLINE = '<span class="badge badge--preview">Preview data</span>';

function renderCreatorProfile(root, preview, adapters) {
  const params = new URLSearchParams(globalThis.location.search);
  const handle = params.get("creator");
  const banner = root.querySelector("[data-profile-banner]");
  if (banner) banner.innerHTML = previewBanner(preview);
  const profile = root.querySelector("[data-creator-profile]");
  const listings = root.querySelector("[data-creator-listings]");
  const reviews = root.querySelector("[data-creator-reviews]");

  if (!preview && adapters?.creatorProfile?.configured) {
    renderCreatorProfileLive(root, handle, adapters, { profile, listings, reviews });
    return;
  }

  const creator = preview ? creatorByHandle(handle ?? "") : null;

  if (!creator) {
    renderState(profile, {
      kind: handle ? STATE.UNAVAILABLE : STATE.EMPTY,
      title: handle ? "This creator profile cannot be opened" : "No creator selected",
      message: handle
        ? "Creator profiles need a connected account service, and this site has none, so no profile can be loaded from an address."
        : "Open a creator from a listing card, or review the layout with ?preview=1.",
      details: preview ? [] : ["Add ?preview=1 to this address to review the layout with sample records."],
      action: { label: "Back to creators", href: "index.html" },
    });
    renderState(listings, { kind: STATE.EMPTY, title: "No listings", message: "Nothing can be listed without listing storage." });
    renderState(reviews, { kind: STATE.EMPTY, title: "No reviews", message: "Reviews need the marketplace service." });
    return;
  }

  profile.dataset.state = STATE.POPULATED;
  profile.className = "";
  profile.innerHTML = `
    <div class="panel">
      <div class="row" style="gap:20px;align-items:flex-start">
        <span class="avatar avatar-xl" aria-hidden="true">${escapeText(creator.initials)}</span>
        <div style="flex:1;min-width:240px">
          <div class="row"><h2 style="margin:0">${escapeText(creator.displayName)}</h2>${PREVIEW_BADGE_INLINE}</div>
          <p class="muted" style="margin-top:10px">Sample profile used to design this page. No bio, joined date, or lifetime statistic exists for a sample record.</p>
          <div class="row" style="margin-top:14px">
            <button type="button" class="button button-secondary button-small" disabled title="Following needs an account-scoped creator service">Follow · not available</button>
            <a class="button button-secondary button-small" href="../account/saved.html">Saved items</a>
          </div>
        </div>
      </div>
      ${keyValueRows([
        ["Joined", null],
        ["Followers", null],
        ["Average rating", null],
        ["Reviews", 0],
        ["Published listings", DEMO_LISTINGS.filter((listing) => listing.creator === creator.handle).length],
      ])}
    </div>`;

  const creatorListings = DEMO_LISTINGS.filter((listing) => listing.creator === creator.handle);
  renderCatalog(listings, creatorListings, {
    preview,
    emptyTitle: "No listings",
    emptyMessage: "This sample creator has no listings in the preview set.",
    detailBase: "../marketplace/build.html",
  });

  renderState(reviews, {
    kind: STATE.EMPTY,
    title: "No reviews",
    message: "Reviews come from real purchases. No review data exists for any sample record, so no rating is shown.",
  });
}

/** Entry point used by `site.js` for the public marketplace and creator-directory pages. */
export function initMarketplaceDirectory() {
  ensureLiveRegion();
  const adapters = createAdapters();
  const directoryRoot = document.querySelector('[data-page="creators"]');
  const profileRoot = document.querySelector('[data-page="creator-profile"]');
  const preview = previewRequested();
  if (directoryRoot) renderCreatorsDirectory(directoryRoot, preview, adapters);
  if (profileRoot) renderCreatorProfile(profileRoot, preview, adapters);
}
