const VERIFICATION_TABLE = "email_verifications";
const RECOVERY_TABLE = "password_recoveries";

// The identifier is proven to be one of two literals before it reaches SQL.
export function clearConsumed(database, { table, userId, now }) {
  if (![VERIFICATION_TABLE, RECOVERY_TABLE].includes(table)) throw new Error("unsupported one-time token table");
  return database.prepare(`UPDATE ${table} SET consumed_at = ? WHERE user_id = ? AND consumed_at IS NULL`).run(now, userId);
}

// A column chosen from a ternary of literals; the value is always bound.
export function findByDigest(database, { accessToken, refreshToken }) {
  const column = accessToken !== undefined ? "access_digest" : "refresh_digest";
  return database.prepare(`SELECT * FROM sessions WHERE ${column} = ?`).get(accessToken ?? refreshToken);
}

// Filter fragments assembled from placeholders only.
export function search(database, { edition, version }) {
  const where = [];
  const parameters = [];
  if (edition) { where.push("edition = ?"); parameters.push(edition); }
  if (version) { where.push("minecraft_version = ?"); parameters.push(version); }
  const clause = where.length ? `WHERE ${where.join(" AND ")}` : "";
  return database.prepare(`SELECT COUNT(*) AS count FROM buyer_jobs ${clause}`).get(...parameters).count;
}
