/**
 * Deterministic, bounded, explainable risk scoring.
 *
 * The threshold band is authoritative: crossing a rule's critical count *is* a critical threat, which is what lets a
 * predefined critical response run immediately. The numeric score adds context (volume, concurrent categories,
 * previous incidents, endpoint sensitivity) but can only keep the level or escalate it by at most one band, and it is
 * always accompanied by the structured reasons that produced it. No AI output participates in this calculation.
 */

const LEVELS = Object.freeze({ LOW: 0, MEDIUM: 1, HIGH: 2, CRITICAL: 3 });
const LEVEL_NAMES = Object.freeze(["LOW", "MEDIUM", "HIGH", "CRITICAL"]);

/** Base weight per threat category: how dangerous the pattern is before frequency is considered. */
const CATEGORY_BASE = Object.freeze({
  BRUTE_FORCE: 20,
  SESSION_ABUSE: 25,
  UNAUTHORIZED_ACCESS: 30,
  RATE_LIMIT_ABUSE: 15,
  CONFIRMATION_ABUSE: 20,
  REQUEST_ABUSE: 15,
});

const BAND_SCORE = Object.freeze({ MEDIUM: 15, HIGH: 30, CRITICAL: 45 });
const SUBJECT_LABEL = Object.freeze({
  ACCOUNT: "same protected account",
  SESSION: "same session credential",
  SOURCE: "same request source",
  DEVELOPER: "same developer identity",
  ROUTE: "same protected route",
});
const MAXIMUM_REASONS = 6;
const MAXIMUM_REASON_LENGTH = 200;

function boundedReason(text) {
  if (typeof text !== "string") return null;
  const trimmed = text.trim();
  if (!trimmed) return null;
  return trimmed.length > MAXIMUM_REASON_LENGTH ? `${trimmed.slice(0, MAXIMUM_REASON_LENGTH - 1)}…` : trimmed;
}

function levelFromScore(score) {
  if (score >= 75) return "CRITICAL";
  if (score >= 50) return "HIGH";
  if (score >= 25) return "MEDIUM";
  return "LOW";
}

/**
 * @param {{ category: string, severity: string, count: number, thresholds: object, subjectKind: string, reason: string }} assessment
 * @param {{ concurrentCategories: Array, previousIncidents: number, endpointSensitivity: string }} context
 * @returns {{ level: string, score: number, reasons: string[] }} a bounded, explainable decision
 */
export function scoreThreat(assessment, context = {}) {
  const base = CATEGORY_BASE[assessment.category] ?? 15;
  const band = BAND_SCORE[assessment.severity] ?? 15;
  const volumeBonus = Math.min(10, Math.floor(assessment.count / 5));
  const concurrent = Array.isArray(context.concurrentCategories) ? context.concurrentCategories : [];
  const concurrentPoints = Math.min(16, concurrent.length * 8);
  const historyPoints = Math.min(15, Math.max(0, Number(context.previousIncidents ?? 0)) * 5);
  const sensitivityPoints = context.endpointSensitivity === "HIGH" ? 10 : 0;
  const score = Math.min(100, base + band + volumeBonus + concurrentPoints + historyPoints + sensitivityPoints);

  const reasons = [];
  const addReason = (text) => {
    const reason = boundedReason(text);
    if (reason && !reasons.includes(reason) && reasons.length < MAXIMUM_REASONS) reasons.push(reason);
  };
  addReason(assessment.reason);
  addReason(SUBJECT_LABEL[assessment.subjectKind] ?? "same subject");
  for (const category of concurrent.slice(0, 2)) {
    addReason(`${category.count} concurrent ${category.category} signals`);
  }
  if (context.previousIncidents > 0) {
    addReason(`${context.previousIncidents} related incident${context.previousIncidents === 1 ? "" : "s"} in the recent window`);
  }
  if (context.endpointSensitivity === "HIGH") addReason("developer/admin endpoint sensitivity");

  // The threshold band is the floor. Two or more independent concurrent signal families escalate exactly one band.
  let level = assessment.severity;
  if (concurrent.length >= 2 && LEVELS[level] < LEVELS.CRITICAL) {
    level = LEVEL_NAMES[LEVELS[level] + 1];
    addReason("pattern escalated one band by concurrent signal families");
  }
  if (LEVELS[levelFromScore(score)] > LEVELS[level]) level = levelFromScore(score);

  return Object.freeze({
    level,
    score,
    reasons: Object.freeze(reasons),
  });
}

export { LEVELS as SECURITY_RISK_LEVELS, levelFromScore };
