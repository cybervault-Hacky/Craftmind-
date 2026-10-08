/**
 * Marketplace page controllers (Phase 21): the marketplace home and the build detail page.
 *
 * With no marketplace backend, the default rendering is an honest unavailable state plus the full, working filter,
 * search, and sort controls. A reviewer can opt into the sample catalog with `?preview=1`; preview mode is always
 * labelled in the interface, purchase controls stay unavailable, and the saved-items list stays in memory for the
 * current page only (nothing is written to browser storage).
 */

import { createAdapters } from "./adapters.js";
import { describeCompatibility } from "./compatibility.js";
import { listingCardMarkup, placeholderCoverMarkup, ratingMarkup } from "./components.js";
import { DEMO_LISTINGS, PREVIEW_CREATORS, creatorByHandle, listingById, previewRequested } from "./preview-catalog.js";
import { STATE, announce, ensureLiveRegion, escapeText, formatTimestamp, orDash, renderState } from "./state.js";

const savedListings = new Set();

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

function renderCatalog(region, listings, { preview, emptyTitle, emptyMessage, onSavedChange = null }) {
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
    detailHref: `build.html?build=${encodeURIComponent(listing.id)}${preview ? "&preview=1" : ""}`,
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
    message: "The marketplace backend is not implemented yet. Search, filters, and sorting work, but there is no inventory to read.",
    details: [
      "Listing search, categories, and featured sections will read from a marketplace service.",
      "Purchase and download of builds stay unavailable until that service and its payment flow exist.",
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
      : renderState(featuredRegion, { kind: STATE.UNAVAILABLE, title: "Nothing to show yet", message: "Featured builds will appear here once a marketplace service exists." });
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
      : renderState(creatorsRegion, { kind: STATE.UNAVAILABLE, title: "Nothing to show yet", message: "Creator highlights will appear here once creators can publish real listings." });
  }

  const refreshSaved = () => { if (savedRegion) renderSaved(savedRegion, preview); };
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

function renderSaved(region, preview) {
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
  const saved = [...savedListings].map((id) => listingById(id)).filter(Boolean);
  region.dataset.state = STATE.POPULATED;
  region.className = "listing-grid";
  region.innerHTML = saved.map((listing) => listingCardMarkup(listing, {
    preview, saved: true, compatibility: compatibilityFor(listing),
    detailHref: `build.html?build=${encodeURIComponent(listing.id)}&preview=1`,
  })).join("");
  for (const button of region.querySelectorAll("[data-save-listing]")) {
    button.addEventListener("click", () => {
      savedListings.delete(button.dataset.saveListing);
      renderSaved(region, preview);
      const catalog = document.querySelector("[data-catalog-region]");
      if (catalog) {
        renderCatalog(catalog, applyFilters(DEMO_LISTINGS, { query: "", category: "", edition: "", freeOnly: false, sort: "relevance" }), {
          preview, emptyTitle: "No builds match", emptyMessage: "Adjust the filters above.", onSavedChange: () => renderSaved(region, preview),
        });
      }
      announce("Removed from saved items for this page.");
    });
  }
}

function renderBuildDetail(root, preview) {
  const params = new URLSearchParams(globalThis.location.search);
  const listingId = params.get("build");
  const region = root.querySelector("[data-build-region]");
  const banners = root.querySelector("[data-build-banner]");
  if (banners) banners.innerHTML = previewBanner(preview);

  const listing = preview ? listingById(listingId ?? "") : null;
  if (!listing) {
    renderState(region, {
      kind: listingId ? STATE.UNAVAILABLE : STATE.EMPTY,
      title: listingId ? "This build cannot be opened" : "No build selected",
      message: listingId
        ? "Build details are served by the marketplace backend, which is not implemented yet, so no listing can be loaded from an address."
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
  // The adapters are consulted so the page always reflects real configuration state rather than assuming one.
  if (!adapters.marketplace.configured) {
    if (root) renderMarketplaceHome(root, preview);
    if (detailRoot) renderBuildDetail(detailRoot, preview);
  }
}

/* ------------------------------------------------------------------ creators */

function renderCreatorsDirectory(root, preview) {
  const banner = root.querySelector("[data-preview-banner]");
  if (banner) banner.innerHTML = previewBanner(preview);
  const region = root.querySelector("[data-creators-directory]");
  if (!region) return;
  if (!preview) {
    renderState(region, {
      kind: STATE.EMPTY,
      title: "No creators to show",
      message: "Creator profiles are stored by the creator service, which is not implemented. This directory lists real creators only, so it stays empty until that service exists.",
      details: [
        "A profile will show a display name, a bio, the categories a creator works in, and their published listings.",
        "Ratings appear only from real reviews, and follower counts only from a real follower service.",
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

function renderCreatorProfile(root, preview) {
  const params = new URLSearchParams(globalThis.location.search);
  const handle = params.get("creator");
  const banner = root.querySelector("[data-profile-banner]");
  if (banner) banner.innerHTML = previewBanner(preview);
  const creator = preview ? creatorByHandle(handle ?? "") : null;
  const profile = root.querySelector("[data-creator-profile]");
  const listings = root.querySelector("[data-creator-listings]");
  const reviews = root.querySelector("[data-creator-reviews]");

  if (!creator) {
    renderState(profile, {
      kind: handle ? STATE.UNAVAILABLE : STATE.EMPTY,
      title: handle ? "This creator profile cannot be opened" : "No creator selected",
      message: handle
        ? "Creator profiles are served by the creator service, which is not implemented, so no profile can be loaded from an address."
        : "Open a creator from the directory, or review the layout with ?preview=1.",
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
  const directoryRoot = document.querySelector('[data-page="creators"]');
  const profileRoot = document.querySelector('[data-page="creator-profile"]');
  const preview = previewRequested();
  if (directoryRoot) renderCreatorsDirectory(directoryRoot, preview);
  if (profileRoot) renderCreatorProfile(profileRoot, preview);
}
