/**
 * Site entry point (Phase 21).
 *
 * One small, dependency-free module that wires the shared chrome (mobile navigation, contextual account/creator
 * navigation, footer year-free copying), then hands off to the controller for the current page. It performs no network
 * request, stores nothing in the browser, and never injects HTML from an untrusted source — every controller escapes the
 * values it renders.
 */

import { initAccount } from "./account.js";
import { initCreatorAnalytics, initCreatorDashboard, initCreatorEarnings, initCreatorListings, initCreatorOrders, initCreatorReviews, initListingWizard } from "./creator-studio.js";
import { initHireDirectory, initHireJob, initHireManage, initHireOrders, initHireOrderDetail, initHirePost, initHireProposals } from "./hire.js";
import { initMarketplace, initMarketplaceDirectory } from "./marketplace.js";
import { initMembership } from "./membership.js";
import { initBuyerOnboarding, initOnboardingHub, initSellerOnboarding, initSignIn } from "./onboarding.js";
import { ensureLiveRegion } from "./state.js";

/** Progressive enhancement marker: CSS may style `[data-js="on"]` refinements without breaking the no-JS reading. */
function markScripted() {
  document.documentElement.dataset.js = "on";
}

/**
 * Wires the mobile navigation disclosure. Without JavaScript the navigation stays a horizontally scrollable list, so
 * every destination remains reachable and no page depends on this enhancement.
 */
function initNavigation() {
  for (const toggle of document.querySelectorAll("[data-nav-toggle]")) {
    const target = document.getElementById(toggle.getAttribute("aria-controls"));
    if (!target) continue;
    toggle.addEventListener("click", () => {
      const expanded = toggle.getAttribute("aria-expanded") === "true";
      toggle.setAttribute("aria-expanded", String(!expanded));
      target.dataset.collapsed = String(expanded);
    });
  }
  // Highlight the contextual section link that matches the current document.
  const here = globalThis.location.pathname.split("/").pop() || "index.html";
  for (const link of document.querySelectorAll("[data-section-link]")) {
    if (link.getAttribute("href") === here) link.setAttribute("aria-current", "page");
  }
}

/** Renders the small "account context" line in the header of account and creator pages. */
function initContextLine() {
  for (const line of document.querySelectorAll("[data-context-line]")) {
    line.textContent = line.dataset.contextLine;
  }
}

function initCurrentPage() {
  const page = document.body.dataset.page ?? "";
  if (page.startsWith("marketplace")) initMarketplace();
  if (page === "creators" || page === "creator-profile") initMarketplaceDirectory();
  if (page === "membership") initMembership();
  if (page === "creator-dashboard") initCreatorDashboard();
  if (page === "creator-listings") initCreatorListings();
  if (page === "creator-orders") initCreatorOrders();
  if (page === "creator-earnings") initCreatorEarnings();
  if (page === "creator-reviews") initCreatorReviews();
  if (page === "creator-analytics") initCreatorAnalytics();
  if (page === "creator-listing-new" || page === "creator-listing-edit") {
    initListingWizard(document.querySelector(`[data-page="${page}"]`));
  }
  if (page.startsWith("account")) initAccount();
  // Phase 24: the dedicated account-experience pages — sign-in/registration, role choice, and the two forms.
  if (page === "signin") initSignIn();
  if (page === "onboarding") initOnboardingHub();
  if (page === "onboarding-buyer") initBuyerOnboarding();
  if (page === "onboarding-seller") initSellerOnboarding();
  // Phase 26: Hire a Builder — directory, detail, buyer post/manage, and creator proposals.
  if (page === "hire-directory") initHireDirectory(document.querySelector('[data-page="hire-directory"]'));
  if (page === "hire-job") initHireJob(document.querySelector('[data-page="hire-job"]'));
  if (page === "hire-post") initHirePost(document.querySelector('[data-page="hire-post"]'));
  if (page === "hire-manage") initHireManage(document.querySelector('[data-page="hire-manage"]'));
  if (page === "hire-proposals") initHireProposals(document.querySelector('[data-page="hire-proposals"]'));
  // Phase 27: marketplace order lifecycle — the buyer/creator order lists and the authorized order detail.
  if (page === "hire-orders") initHireOrders(document.querySelector('[data-page="hire-orders"]'));
  if (page === "hire-order") initHireOrderDetail(document.querySelector('[data-page="hire-order"]'));
}

markScripted();
ensureLiveRegion();
initNavigation();
initContextLine();
initCurrentPage();
