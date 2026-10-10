/**
 * The marketplace analytics API surface (Phase 31): read-only, session-authoritative aggregate insight.
 *
 * Both functions resolve the acting account from the **session token** and nothing else. The overview needs the token
 * only to require that the caller is an authorized participant (analytics are not an anonymous feed); the
 * per-account view uses the resolved id as the sole scoping key. No body is read and no `userId`/`creatorId`
 * parameter is ever consulted, so there is no supplied identity to inject and no protected filter to bypass — a
 * caller structurally cannot ask for another account's numbers. Each request runs inside the service's serialized
 * read transaction so the many aggregate queries see one consistent snapshot rather than a torn view.
 */

import { requireAccountIdForToken } from "./accounts.js";
import { runTransaction } from "./transactions.js";
import { creatorAnalytics, overviewAnalytics, parseAnalyticsQuery } from "./marketplace-analytics.js";

export function overviewAnalyticsForSession(database, configuration, accessToken, searchParams) {
  // Resolve the session first (a missing/invalid token is refused before any query runs); the id itself is not a
  // scope for the global view, but requiring a live session is what keeps analytics to authorized participants.
  requireAccountIdForToken(database, configuration, accessToken);
  const filters = parseAnalyticsQuery(searchParams);
  return runTransaction(database, () => overviewAnalytics(database, filters));
}

export function creatorAnalyticsForSession(database, configuration, accessToken, searchParams) {
  const userId = requireAccountIdForToken(database, configuration, accessToken);
  const filters = parseAnalyticsQuery(searchParams);
  return runTransaction(database, () => creatorAnalytics(database, userId, filters));
}
