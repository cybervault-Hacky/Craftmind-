/**
 * The listing API surface (Phase 25).
 *
 * Every mutation takes a **session token** and resolves the account from it — never from the request body — so no
 * caller can create, edit, publish, or archive a listing on someone else's behalf. Bodies carry content only: the
 * strict body reader rejects unknown keys outright, which is what refuses client-supplied `ownerId`, `creatorId`,
 * `status`, `publishedAt`, `salesTotal`, `reviewCount`, or `verification` instead of silently dropping them.
 *
 * The public reads are the discovery contract: a PUBLISHED-only search with bounded, validated filters and a
 * published-only detail. Neither requires a session, neither audits (public reads are not security events), and
 * neither can return a draft through any parameter combination.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { requireAccountIdForToken } from "./accounts.js";
import { strictBody } from "./creator-server-api.js";
import { runTransaction } from "./transactions.js";
import {
  archiveListingInTransaction,
  createListingInTransaction,
  ownListingsInTransaction,
  ownerListingInTransaction,
  parseListingSearchQuery,
  publicListingInTransaction,
  publishListingInTransaction,
  searchPublishedListingsInTransaction,
  updateListingInTransaction,
} from "./marketplace-listings.js";

const CONTENT_KEYS = ["title", "description", "subcategory", "category", "edition", "minecraftVersions", "loaders", "tags", "imageReferences"];

export function createListingForSession(database, configuration, accessToken, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, {
    required: ["title", "description", "category", "edition", "minecraftVersions"],
    optional: CONTENT_KEYS.filter((key) => !["title", "description", "category", "edition", "minecraftVersions"].includes(key)),
  });
  return runTransaction(database, () => createListingInTransaction(database, configuration, userId, payload));
}

export function ownListingsForSession(database, configuration, accessToken) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => ownListingsInTransaction(database, configuration, userId));
}

export function listingForSession(database, configuration, accessToken, listingId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => ownerListingInTransaction(database, configuration, userId, listingId));
}

export function updateListingForSession(database, configuration, accessToken, listingId, body) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const payload = strictBody(body, { optional: CONTENT_KEYS });
  return runTransaction(database, () => updateListingInTransaction(database, configuration, userId, listingId, payload));
}

export function publishListingForSession(database, configuration, accessToken, listingId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => publishListingInTransaction(database, configuration, userId, listingId));
}

export function archiveListingForSession(database, configuration, accessToken, listingId) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  return runTransaction(database, () => archiveListingInTransaction(database, configuration, userId, listingId));
}

/** Public search: validate the query string first (outside the transaction), then run the bounded read. */
export function searchPublishedListings(database, searchParams) {
  const filters = parseListingSearchQuery(searchParams);
  return runTransaction(database, () => searchPublishedListingsInTransaction(database, filters));
}

export function publicListing(database, listingId) {
  if (typeof listingId !== "string" || listingId.length === 0) throw new AccountApiError(ErrorCode.LISTING_NOT_FOUND);
  return runTransaction(database, () => publicListingInTransaction(database, listingId));
}
