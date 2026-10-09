/**
 * Marketplace listings (Phase 25).
 *
 * The first real listing storage for CraftMind, built on the identity and capability boundaries that already exist:
 * a listing is owned by a creator profile (never by a client-supplied account id), creating and editing require the
 * same entitlement + active-profile rules as every other creator write, and publishing adds one more genuine
 * prerequisite — the creator agreement recorded by seller onboarding. Verification is not a listing concern: nothing
 * here reads, writes, or implies email/phone/KYC/Trusted-Seller state, and publishing never mints a badge.
 *
 * Discovery is ordinary database search over published rows: parameterized LIKE queries with escaped wildcards, the
 * site's existing category and compatibility vocabularies, deterministic ordering (`published_at DESC, listing_id`),
 * and bounded pagination. No relevance scores, no popularity, no fabricated metrics — drafts and archived rows are
 * excluded by construction: every public query carries `status = 'PUBLISHED'` in its WHERE clause, not by filtering
 * afterwards.
 *
 * There are no price, sales, review, or download columns because none of those mechanisms exist in this phase;
 * inventing the column would invite inventing the data.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { AUDIT_ACTOR_KIND, appendAuditRecord } from "./audit.js";
import { requireAccount } from "./entitlements.js";
import { newListingId } from "./ids.js";
import { publishPrerequisitesInTransaction } from "./capabilities.js";
import { RESOURCE_KIND } from "./ownership.js";
import { validateBoundedText, validateSafeReference } from "./text-fields.js";
import {
  COMPATIBILITY_LIMITS,
  isKnownEdition,
  isKnownLoader,
  isWellFormedMinecraftVersion,
  loaderBelongsToEditions,
} from "./compatibility.js";

/** The listing categories, mirroring `website/assets/preview-catalog.js` CATEGORIES — one vocabulary, drift-tested. */
export const LISTING_CATEGORIES = Object.freeze([
  "Structures", "Landscaping", "Interiors", "Redstone", "Farms", "Decorations", "Mini-games", "Whole worlds",
]);

export const LISTING_STATUS = Object.freeze({ DRAFT: "DRAFT", PUBLISHED: "PUBLISHED", ARCHIVED: "ARCHIVED" });

export const LISTING_FIELD_LIMITS = Object.freeze({
  title: { minimum: 3, maximum: 120 },
  description: { minimum: 10, maximum: 5000 },
  subcategory: { maximum: 40 },
  tags: { maximum: 10, tagMaximum: 32 },
  images: { maximum: 4, referenceMaximum: 300 },
  searchQuery: { maximum: 200 },
  page: { defaultLimit: 24, maximumLimit: 48, maximumOffset: 100_000 },
});

function nowIso(nowMillis = Date.now()) {
  return new Date(nowMillis).toISOString();
}

/* ------------------------------------------------------------------------------ validation (one place) */

function validateClaimList(values, { maximum, itemMaximum, accept, allowEmpty = false }) {
  if (values === undefined && allowEmpty) return [];
  if (!Array.isArray(values) || values.length === 0) {
    if (allowEmpty) return [];
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  const unique = [...new Set(values)];
  if (unique.length > maximum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  if (unique.some((value) => typeof value !== "string" || value.length === 0 || value.length > itemMaximum || !accept(value))) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  return unique;
}

/** Normalizes a create/update body into storable fields. Throws `INVALID_REQUEST` — never repairs silently. */
export function validateListingContent({
  title, description, subcategory, category, edition, minecraftVersions, loaders, tags, imageReferences,
}) {
  const normalizedTitle = validateBoundedText(title, { ...LISTING_FIELD_LIMITS.title });
  const normalizedDescription = validateBoundedText(description, {
    ...LISTING_FIELD_LIMITS.description, allowNewlines: true,
  });
  if (typeof category !== "string" || !LISTING_CATEGORIES.includes(category)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  if (typeof edition !== "string" || !isKnownEdition(edition)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const versions = validateClaimList(minecraftVersions, {
    maximum: COMPATIBILITY_LIMITS.maximumVersions, itemMaximum: 16, accept: isWellFormedMinecraftVersion,
  });
  const selectedLoaders = validateClaimList(loaders, {
    maximum: COMPATIBILITY_LIMITS.maximumLoaders, itemMaximum: 32, allowEmpty: true,
    accept: (loader) => isKnownLoader(loader) && loaderBelongsToEditions(loader, [edition]),
  });
  const normalizedTags = validateClaimList(tags, {
    maximum: LISTING_FIELD_LIMITS.tags.maximum, itemMaximum: LISTING_FIELD_LIMITS.tags.tagMaximum, allowEmpty: true,
    accept: (tag) => validateBoundedText(tag, { minimum: 1, maximum: LISTING_FIELD_LIMITS.tags.tagMaximum }) === tag,
  });
  const images = validateClaimList(imageReferences, {
    maximum: LISTING_FIELD_LIMITS.images.maximum, itemMaximum: LISTING_FIELD_LIMITS.images.referenceMaximum,
    allowEmpty: true,
    accept: (reference) => validateSafeReference(reference, { maximum: LISTING_FIELD_LIMITS.images.referenceMaximum }) === reference,
  });
  const normalizedSubcategory = validateBoundedText(subcategory ?? "", {
    ...LISTING_FIELD_LIMITS.subcategory, allowEmpty: true,
  });
  return {
    title: normalizedTitle,
    description: normalizedDescription,
    subcategory: normalizedSubcategory,
    category,
    edition,
    minecraftVersions: versions,
    loaders: selectedLoaders,
    tags: normalizedTags,
    imageReferences: images,
  };
}

/* --------------------------------------------------------------------------- ownership and capability */

/**
 * The creator identity behind every listing write: account from the session, profile from the account's own row,
 * entitlement and ACTIVE status from the existing rules. Denials of a suspended profile are audited before the
 * refusal, exactly like Phase 23's profile writes.
 */
export function requireListingCreatorInTransaction(database, configuration, userId, { now = Date.now() } = {}) {
  requireAccount(database, userId);
  const prerequisites = publishPrerequisitesInTransaction(database, configuration, userId, { now });
  if (!prerequisites.entitled) throw new AccountApiError(ErrorCode.CREATOR_ENTITLEMENT_REQUIRED);
  if (!prerequisites.profileExists) throw new AccountApiError(ErrorCode.CREATOR_PROFILE_NOT_FOUND);
  if (!prerequisites.operational) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "CREATOR_ACCESS_DENIED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { resourceKind: RESOURCE_KIND.CREATOR_PROFILE, profileStatus: prerequisites.profile.status, surface: "listing" },
      occurredAt: nowIso(now),
    });
    throw new AccountApiError(ErrorCode.CREATOR_PROFILE_SUSPENDED);
  }
  return prerequisites;
}

function listingRowById(database, listingId) {
  if (typeof listingId !== "string" || !/^lst_[0-9a-fA-F-]{36}$/.test(listingId)) {
    throw new AccountApiError(ErrorCode.LISTING_NOT_FOUND);
  }
  return database.prepare("SELECT * FROM marketplace_listings WHERE listing_id = ?").get(listingId) ?? null;
}

/**
 * Loads a listing for an actor who must OWN it. A row that belongs to another creator is reported with the same
 * uniform not-found as a row that does not exist — an attacker learns nothing from the difference — while the
 * attempt itself is audited as a denial against the acting account.
 */
function ownedListingRow(database, listingId, actor) {
  const row = listingRowById(database, listingId);
  if (!row) throw new AccountApiError(ErrorCode.LISTING_NOT_FOUND);
  if (row.creator_id !== actor.profile.creatorId) {
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "CREATOR_ACCESS_DENIED",
      targetUserId: actor.userId,
      outcome: "DENIED",
      metadata: { resourceKind: "LISTING", reason: "not_owner", surface: "listing" },
      occurredAt: nowIso(),
    });
    throw new AccountApiError(ErrorCode.LISTING_NOT_FOUND);
  }
  return row;
}

/* ---------------------------------------------------------------------------------- writes (in-tx) */

function auditListing(database, actionType, userId, metadata, now) {
  appendAuditRecord(database, {
    actorKind: AUDIT_ACTOR_KIND.SYSTEM,
    actionType,
    targetUserId: userId,
    outcome: "SUCCESS",
    metadata,
    occurredAt: nowIso(now),
  });
}

export function createListingInTransaction(database, configuration, userId, body, { now = Date.now() } = {}) {
  const actor = { userId, ...requireListingCreatorInTransaction(database, configuration, userId, { now }) };
  // Server-controlled properties in the body are refused, never ignored: status, timestamps, ownership, counts, and
  // verification are not a caller's to set (the strict layer rejects unknown keys; this guards the domain boundary).
  const content = validateListingContent(body);
  const listingId = newListingId();
  const timestamp = nowIso(now);
  database.prepare(
    `INSERT INTO marketplace_listings
       (listing_id, creator_id, title, description, category, subcategory, edition, minecraft_versions,
        loaders, tags, image_references, status, published_at, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', NULL, ?, ?)`,
  ).run(
    listingId, actor.profile.creatorId, content.title, content.description, content.category, content.subcategory,
    content.edition, JSON.stringify(content.minecraftVersions), JSON.stringify(content.loaders),
    JSON.stringify(content.tags), JSON.stringify(content.imageReferences), timestamp, timestamp,
  );
  auditListing(database, "LISTING_CREATED", userId, { listingId, category: content.category, edition: content.edition }, now);
  return ownerListingView(listingRowById(database, listingId));
}

export function updateListingInTransaction(database, configuration, userId, listingId, body, { now = Date.now() } = {}) {
  const actor = { userId, ...requireListingCreatorInTransaction(database, configuration, userId, { now }) };
  const row = ownedListingRow(database, listingId, actor);
  if (row.status !== LISTING_STATUS.DRAFT) {
    throw new AccountApiError(ErrorCode.LISTING_STATE_CONFLICT, "Only draft listings are editable. Archive the listing first if it needs changes.");
  }
  const patch = { ...body };
  delete patch.listingId;
  if (Object.keys(patch).length === 0) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  // Validate the patch as a complete document over the stored row, so a partial update cannot produce a state the
  // create path would have rejected.
  const content = validateListingContent({
    title: patch.title ?? row.title,
    description: patch.description ?? row.description,
    subcategory: patch.subcategory ?? row.subcategory,
    category: patch.category ?? row.category,
    edition: patch.edition ?? row.edition,
    minecraftVersions: patch.minecraftVersions ?? JSON.parse(row.minecraft_versions),
    loaders: patch.loaders ?? JSON.parse(row.loaders),
    tags: patch.tags ?? JSON.parse(row.tags),
    imageReferences: patch.imageReferences ?? JSON.parse(row.image_references),
  });
  const timestamp = nowIso(now);
  database.prepare(
    `UPDATE marketplace_listings
        SET title = ?, description = ?, category = ?, subcategory = ?, edition = ?, minecraft_versions = ?,
            loaders = ?, tags = ?, image_references = ?, updated_at = ?
      WHERE listing_id = ?`,
  ).run(
    content.title, content.description, content.category, content.subcategory, content.edition,
    JSON.stringify(content.minecraftVersions), JSON.stringify(content.loaders), JSON.stringify(content.tags),
    JSON.stringify(content.imageReferences), timestamp, listingId,
  );
  auditListing(database, "LISTING_UPDATED", userId, { listingId, fields: Object.keys(patch).sort() }, now);
  return ownerListingView(listingRowById(database, listingId));
}

export function publishListingInTransaction(database, configuration, userId, listingId, { now = Date.now() } = {}) {
  const actor = { userId, ...requireListingCreatorInTransaction(database, configuration, userId, { now }) };
  const row = ownedListingRow(database, listingId, actor);
  if (row.status === LISTING_STATUS.PUBLISHED) throw new AccountApiError(ErrorCode.LISTING_STATE_CONFLICT);
  if (!actor.agreementRecorded) {
    // A genuine prerequisite, refused with an actionable typed error and a denial record that outlives this
    // transaction (the audit capture frames flush after rollback).
    appendAuditRecord(database, {
      actorKind: AUDIT_ACTOR_KIND.SYSTEM,
      actionType: "CREATOR_ACCESS_DENIED",
      targetUserId: userId,
      outcome: "DENIED",
      metadata: { resourceKind: "LISTING", reason: "agreement_missing", surface: "listing" },
      occurredAt: nowIso(now),
    });
    throw new AccountApiError(ErrorCode.LISTING_PUBLISH_BLOCKED);
  }
  const timestamp = nowIso(now);
  database.prepare(
    `UPDATE marketplace_listings SET status = 'PUBLISHED', published_at = ?, updated_at = ? WHERE listing_id = ?`,
  ).run(timestamp, timestamp, listingId);
  auditListing(database, "LISTING_PUBLISHED", userId, { listingId }, now);
  return ownerListingView(listingRowById(database, listingId));
}

export function archiveListingInTransaction(database, configuration, userId, listingId, { now = Date.now() } = {}) {
  const actor = { userId, ...requireListingCreatorInTransaction(database, configuration, userId, { now }) };
  const row = ownedListingRow(database, listingId, actor);
  if (row.status === LISTING_STATUS.ARCHIVED) throw new AccountApiError(ErrorCode.LISTING_STATE_CONFLICT);
  const timestamp = nowIso(now);
  database.prepare(
    `UPDATE marketplace_listings SET status = 'ARCHIVED', updated_at = ? WHERE listing_id = ?`,
  ).run(timestamp, listingId);
  auditListing(database, "LISTING_ARCHIVED", userId, { listingId, previousStatus: row.status }, now);
  return ownerListingView(listingRowById(database, listingId));
}

/* ------------------------------------------------------------------------------------ reads (in-tx) */

function parseJsonList(column, raw) {
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) throw new Error("not a list");
    return parsed;
  } catch {
    // Only this module writes these columns; anything else reaching the parser is tampering — fail closed.
    throw new AccountApiError(ErrorCode.UNKNOWN_ERROR);
  }
}

/** The owner's view: full content plus lifecycle state. Never includes creator ids or account ids. */
export function ownerListingView(row) {
  return Object.freeze({
    id: row.listing_id,
    title: row.title,
    description: row.description,
    category: row.category,
    subcategory: row.subcategory,
    edition: row.edition,
    minecraftVersions: Object.freeze(parseJsonList("minecraft_versions", row.minecraft_versions)),
    loaders: Object.freeze(parseJsonList("loaders", row.loaders)),
    tags: Object.freeze(parseJsonList("tags", row.tags)),
    imageReferences: Object.freeze(parseJsonList("image_references", row.image_references)),
    status: row.status,
    publishedAt: row.published_at,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  });
}

/** The public projection: exactly the fields a stranger should see — no ids beyond the public one, no lifecycle. */
export function publicListingView(row, creator) {
  const description = row.description;
  return Object.freeze({
    id: row.listing_id,
    title: row.title,
    description,
    summary: description.length > 180 ? `${description.slice(0, 180)}…` : description,
    category: row.category,
    subcategory: row.subcategory,
    edition: row.edition,
    minecraftVersions: Object.freeze(parseJsonList("minecraft_versions", row.minecraft_versions)),
    loaders: Object.freeze(parseJsonList("loaders", row.loaders)),
    tags: Object.freeze(parseJsonList("tags", row.tags)),
    imageReferences: Object.freeze(parseJsonList("image_references", row.image_references)),
    publishedAt: row.published_at,
    creator: Object.freeze({
      handle: creator.handle,
      displayName: creator.display_name,
      avatarReference: creator.avatar_reference ?? null,
    }),
  });
}

function publicCreatorRowForListing(database, row) {
  return database.prepare(
    "SELECT handle, display_name, avatar_reference FROM creator_profiles WHERE creator_id = ?",
  ).get(row.creator_id);
}

export function ownListingsInTransaction(database, configuration, userId, { now = Date.now() } = {}) {
  const actor = { userId, ...requireListingCreatorInTransaction(database, configuration, userId, { now }) };
  const rows = database.prepare(
    "SELECT * FROM marketplace_listings WHERE creator_id = ? ORDER BY updated_at DESC, listing_id DESC",
  ).all(actor.profile.creatorId);
  return Object.freeze({
    listings: Object.freeze(rows.map(ownerListingView)),
    counts: Object.freeze({
      total: rows.length,
      draft: rows.filter((row) => row.status === LISTING_STATUS.DRAFT).length,
      published: rows.filter((row) => row.status === LISTING_STATUS.PUBLISHED).length,
      archived: rows.filter((row) => row.status === LISTING_STATUS.ARCHIVED).length,
    }),
  });
}

export function ownerListingInTransaction(database, configuration, userId, listingId, { now = Date.now() } = {}) {
  const actor = { userId, ...requireListingCreatorInTransaction(database, configuration, userId, { now }) };
  return ownerListingView(ownedListingRow(database, listingId, actor));
}

export function publicListingInTransaction(database, listingId) {
  const row = listingRowById(database, listingId);
  if (!row || row.status !== LISTING_STATUS.PUBLISHED) throw new AccountApiError(ErrorCode.LISTING_NOT_FOUND);
  const creator = publicCreatorRowForListing(database, row);
  if (!creator) throw new AccountApiError(ErrorCode.LISTING_NOT_FOUND);
  return publicListingView(row, creator);
}

/* ----------------------------------------------------------------------------------------- discovery */

/** Escapes LIKE wildcards so a search for `100%` or `a_b` matches those characters, never everything. */
function escapeLike(value) {
  return value.replace(/[\\%_]/g, (character) => `\\${character}`);
}

/**
 * Database-backed search over PUBLISHED rows only. Filters compose in one WHERE clause; ordering is
 * `published_at DESC, listing_id DESC` (stable, deterministic tie-break); pagination is bounded and returns
 * accurate totals. Empty results are a normal 200 — an empty shop is not an error.
 */
export function searchPublishedListingsInTransaction(database, filters, { now = Date.now() } = {}) {
  const where = ["status = 'PUBLISHED'"];
  const parameters = [];
  if (filters.q) {
    const needle = `%${escapeLike(filters.q)}%`;
    where.push("(title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR tags LIKE ? ESCAPE '\\')");
    parameters.push(needle, needle, needle);
  }
  if (filters.category) {
    where.push("category = ?");
    parameters.push(filters.category);
  }
  if (filters.edition) {
    where.push("edition = ?");
    parameters.push(filters.edition);
  }
  if (filters.version) {
    where.push("minecraft_versions LIKE ? ESCAPE '\\'");
    parameters.push(`%"${escapeLike(filters.version)}"%`);
  }
  if (filters.loader) {
    where.push("loaders LIKE ? ESCAPE '\\'");
    parameters.push(`%"${escapeLike(filters.loader)}"%`);
  }
  if (filters.creatorHandle) {
    where.push("creator_id = (SELECT creator_id FROM creator_profiles WHERE handle = ?)");
    parameters.push(filters.creatorHandle);
  }
  const clause = `WHERE ${where.join(" AND ")}`;
  const total = database.prepare(`SELECT COUNT(*) AS count FROM marketplace_listings ${clause}`).get(...parameters).count;
  const rows = database.prepare(
    `SELECT * FROM marketplace_listings ${clause}
      ORDER BY published_at DESC, listing_id DESC
      LIMIT ? OFFSET ?`,
  ).all(...parameters, filters.limit, filters.offset);
  const items = rows.map((row) => {
    const creator = publicCreatorRowForListing(database, row);
    return creator ? publicListingView(row, creator) : null;
  }).filter(Boolean);
  return Object.freeze({
    items: Object.freeze(items),
    total,
    limit: filters.limit,
    offset: filters.offset,
    hasMore: filters.offset + items.length < total,
    searchedAt: nowIso(now),
  });
}

/** Validates and normalizes the public search query string. Anything unbounded or unshaped is a typed 400. */
export function parseListingSearchQuery(searchParams) {
  const text = (name, maximum) => {
    const raw = searchParams.get(name);
    if (raw === null || raw.trim() === "") return null;
    return validateBoundedText(raw, { minimum: 1, maximum });
  };
  const integer = (name, { maximum, fallback = null }) => {
    const raw = searchParams.get(name);
    if (raw === null || raw === "") return fallback;
    if (!/^\d{1,9}$/.test(raw)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
    const value = Number(raw);
    if (!Number.isSafeInteger(value) || value > maximum) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
    return value;
  };

  const q = text("q", LISTING_FIELD_LIMITS.searchQuery.maximum);
  const category = searchParams.get("category");
  if (category && !LISTING_CATEGORIES.includes(category)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const edition = searchParams.get("edition");
  if (edition && !isKnownEdition(edition)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const version = text("version", 16);
  if (version && !isWellFormedMinecraftVersion(version)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const loader = text("loader", 32);
  if (loader && !isKnownLoader(loader)) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  const creatorHandle = text("creator", 32);
  if (creatorHandle && !/^[a-z0-9][a-z0-9-]{1,31}$/.test(creatorHandle)) {
    throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  }
  const limit = integer("limit", { maximum: LISTING_FIELD_LIMITS.page.maximumLimit, fallback: LISTING_FIELD_LIMITS.page.defaultLimit });
  const offset = integer("offset", { maximum: LISTING_FIELD_LIMITS.page.maximumOffset, fallback: 0 });
  if (limit !== null && limit < 1) throw new AccountApiError(ErrorCode.INVALID_REQUEST);
  return {
    q: q ?? undefined,
    category: category ?? undefined,
    edition: edition ?? undefined,
    version: version ?? undefined,
    loader: loader ?? undefined,
    creatorHandle: creatorHandle ?? undefined,
    limit: limit ?? LISTING_FIELD_LIMITS.page.defaultLimit,
    offset: offset ?? 0,
  };
}
