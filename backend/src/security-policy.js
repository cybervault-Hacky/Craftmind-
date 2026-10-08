/**
 * The centralized autonomous response policy.
 *
 * This module is the only place that decides what CraftMind may do to defend itself, and it is the only issuer of
 * security-action authorization. A response plan is a bounded list of registered security tools with bounded
 * arguments; nothing here can express "run this", "delete that", or "execute anything". Critical predefined threats
 * produce a plan that runs immediately — no human approval, and no AI approval, is part of the path.
 *
 * Automated plans never contain a permanent or destructive action. They tighten rate limits, temporarily reject an
 * abusive request source, revoke a suspicious session, temporarily throttle a targeted account, create the incident,
 * audit it, and notify the developer. Permanent account deletion, permanent bans, configuration changes, and source
 * modification remain developer-only operations outside this policy.
 */

export const SECURITY_TOOL = Object.freeze({
  APPLY_RATE_LIMIT: "security.applyRateLimit",
  REJECT_ABUSIVE_REQUEST: "security.rejectAbusiveRequest",
  REVOKE_SESSION: "security.revokeSession",
  PROTECT_ACCOUNT: "security.protectAccount",
  CREATE_INCIDENT: "security.createIncident",
  NOTIFY_DEVELOPER: "security.notifyDeveloper",
  RELEASE_PROTECTION: "security.releaseProtection",
  RESOLVE_INCIDENT: "security.resolveIncident",
});

const PROTECTION_CATEGORY = Object.freeze({
  BRUTE_FORCE: "USER_AUTH",
  SESSION_ABUSE: "SESSION",
  RATE_LIMIT_ABUSE: "ANY",
  UNAUTHORIZED_ACCESS: "DEVELOPER_ADMIN",
  CONFIRMATION_ABUSE: "CONFIRMATION",
  REQUEST_ABUSE: "ANY",
});

const INCIDENT_SEVERITY_BY_RISK = Object.freeze({ MEDIUM: "MEDIUM", HIGH: "HIGH", CRITICAL: "CRITICAL" });

/**
 * Plan templates, ordered by the progressive ladder the incident severity walks:
 *
 *   MEDIUM   — tighten the specific subject the rule attributed the signal to (account, session, or source). No
 *              source-wide measure, so an ambiguous signal can never slow unrelated traffic.
 *   HIGH     — the same subject, tightened further, plus the subject-specific remedy (revoke the suspicious session).
 *   CRITICAL — the subject remedy, the stronger account protection where an account exists, a temporary rejection of
 *              the source, and the developer alert.
 *
 * `DENY` is only ever issued for `SOURCE` (enforced here and by a database CHECK), so no automated plan can lock an
 * account out of its own sign-in; every automated measure is expiring and reversible.
 */
const PLANS = Object.freeze({
  BRUTE_FORCE: Object.freeze({
    MEDIUM: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "TIGHT", durationSeconds: "DEFAULT" },
    ],
    HIGH: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "MINIMAL", durationSeconds: "DEFAULT" },
    ],
    CRITICAL: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "MINIMAL", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.REJECT_ABUSIVE_REQUEST, scope: "SOURCE", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.NOTIFY_DEVELOPER },
    ],
  }),
  SESSION_ABUSE: Object.freeze({
    MEDIUM: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "TIGHT", durationSeconds: "DEFAULT" },
    ],
    HIGH: [
      { tool: SECURITY_TOOL.REVOKE_SESSION, scope: "SUBJECT", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "MINIMAL", durationSeconds: "DEFAULT" },
    ],
    CRITICAL: [
      { tool: SECURITY_TOOL.REVOKE_SESSION, scope: "SUBJECT", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "MINIMAL", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.REJECT_ABUSIVE_REQUEST, scope: "SOURCE", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.NOTIFY_DEVELOPER },
    ],
  }),
  RATE_LIMIT_ABUSE: Object.freeze({
    MEDIUM: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "TIGHT", durationSeconds: "DEFAULT" },
    ],
    HIGH: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "MINIMAL", durationSeconds: "DEFAULT" },
    ],
    CRITICAL: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "MINIMAL", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.REJECT_ABUSIVE_REQUEST, scope: "SOURCE", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.NOTIFY_DEVELOPER },
    ],
  }),
  UNAUTHORIZED_ACCESS: Object.freeze({
    MEDIUM: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "TIGHT", durationSeconds: "DEFAULT" },
    ],
    HIGH: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "MINIMAL", durationSeconds: "DEFAULT" },
    ],
    CRITICAL: [
      { tool: SECURITY_TOOL.PROTECT_ACCOUNT, scope: "SUBJECT", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.REJECT_ABUSIVE_REQUEST, scope: "SOURCE", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.NOTIFY_DEVELOPER },
    ],
  }),
  CONFIRMATION_ABUSE: Object.freeze({
    MEDIUM: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "TIGHT", durationSeconds: "DEFAULT" },
    ],
    HIGH: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "MINIMAL", durationSeconds: "DEFAULT" },
    ],
    CRITICAL: [
      { tool: SECURITY_TOOL.PROTECT_ACCOUNT, scope: "SUBJECT", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.REJECT_ABUSIVE_REQUEST, scope: "SOURCE", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.NOTIFY_DEVELOPER },
    ],
  }),
  REQUEST_ABUSE: Object.freeze({
    MEDIUM: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "TIGHT", durationSeconds: "DEFAULT" },
    ],
    HIGH: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "MINIMAL", durationSeconds: "DEFAULT" },
    ],
    CRITICAL: [
      { tool: SECURITY_TOOL.APPLY_RATE_LIMIT, scope: "SUBJECT", mode: "THROTTLE", maximum: "MINIMAL", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.REJECT_ABUSIVE_REQUEST, scope: "SOURCE", durationSeconds: "DEFAULT" },
      { tool: SECURITY_TOOL.NOTIFY_DEVELOPER },
    ],
  }),
});

function boundedDuration(seconds, configuration) {
  const maximum = configuration.security.response.maximumProtectionSeconds;
  const fallback = configuration.security.response.defaultProtectionSeconds;
  const requested = seconds === "DEFAULT" ? fallback : Number(seconds);
  if (!Number.isFinite(requested) || requested <= 0) return fallback;
  return Math.min(Math.max(Math.trunc(requested), 1), maximum);
}

/**
 * Turns a plan descriptor into the exact bounded arguments a registered tool accepts. Nothing unbounded, nothing
 * permanent: every duration is clamped to the configured automated-protection ceiling.
 */
export function expandPolicyAction(action, { assessment, configuration }) {
  const response = configuration.security.response;
  const durationMs = boundedDuration(action.durationSeconds, configuration) * 1000;
  // `SUBJECT` means "the subject the detection rule actually attributed this signal to": an account brute-force
  // throttles that account, a source-only pattern throttles that source. A plan can never invent a subject it does
  // not have a reference for — the tool skips the action instead.
  const scope = action.scope === "SUBJECT" ? assessment.subjectKind : (action.scope ?? "SOURCE");
  const common = {
    scope,
    // Scope and subject always agree: an account protection targets the account, a source rejection targets the source.
    subjectKind: scope,
    category: PROTECTION_CATEGORY[assessment.category] ?? "ANY",
    durationMs,
    reason: `${assessment.category} automatic response: ${assessment.reason}`,
  };
  switch (action.tool) {
    case SECURITY_TOOL.APPLY_RATE_LIMIT: {
      const maximum = action.mode === "DENY"
        ? 1
        : action.maximum === "MINIMAL"
          ? Math.max(1, Math.floor(response.throttleMaximum / 3))
          : response.throttleMaximum;
      return {
        ...common,
        mode: action.mode ?? "THROTTLE",
        maximum,
        windowMs: response.throttleWindowMs,
      };
    }
    case SECURITY_TOOL.REJECT_ABUSIVE_REQUEST:
      return { ...common, mode: "DENY", maximum: 1, windowMs: response.throttleWindowMs };
    case SECURITY_TOOL.REVOKE_SESSION:
      return { durationMs, reason: `revoked after ${assessment.reason}` };
    case SECURITY_TOOL.PROTECT_ACCOUNT:
      return { ...common, mode: "THROTTLE", maximum: Math.max(1, Math.floor(response.throttleMaximum / 3)), windowMs: response.throttleWindowMs };
    case SECURITY_TOOL.NOTIFY_DEVELOPER:
      return { priority: INCIDENT_SEVERITY_BY_RISK[assessment.severity] ?? "MEDIUM" };
    default:
      return {};
  }
}

/**
 * Builds the response plan for one assessment. `createIncident` always runs first so every other action carries an
 * incident reference; protections then run in the same transaction, which is what makes the critical path immediate
 * rather than "detected and queued".
 */
export function decideSecurityResponse({ assessment, risk, configuration }) {
  const templates = PLANS[assessment.category]?.[assessment.severity] ?? [];
  const actions = [
    {
      tool: SECURITY_TOOL.CREATE_INCIDENT,
      arguments: {
        severity: INCIDENT_SEVERITY_BY_RISK[assessment.severity] ?? "MEDIUM",
        threatCategory: assessment.category,
        subjectKind: assessment.subjectKind,
        subjectReference: assessment.subjectReference,
        riskScore: risk.score,
        riskLevel: risk.level,
        reasons: [...risk.reasons],
        thresholdsKey: assessment.thresholdsKey,
        // The number of signals observed inside the rule's window, not the number of stored rows.
        observedCount: Math.max(1, Math.min(1_000_000, assessment.count)),
        deduplicationKey: assessment.dedupKey,
      },
    },
  ];
  for (const template of templates) {
    // A subject-scoped account remedy is only expressible when the rule attributed the signal to an account; anything
    // else would be an action without a subject, which the registry refuses rather than guessing one.
    if (template.tool === SECURITY_TOOL.PROTECT_ACCOUNT && assessment.subjectKind !== "ACCOUNT") continue;
    actions.push({ tool: template.tool, arguments: expandPolicyAction(template, { assessment, configuration }) });
  }
  return Object.freeze({
    policyId: `AUTO_${assessment.category}_${assessment.severity}`,
    threatCategory: assessment.category,
    riskLevel: assessment.severity,
    actions: Object.freeze(actions.map((action) => Object.freeze({ ...action, arguments: Object.freeze(action.arguments) }))),
  });
}

/**
 * Issues the authorization envelope that every automated security tool demands. It is created only here, expires
 * quickly, is bound to one policy plan and tool name, and can neither be serialized from client input nor minted by
 * the AI boundary. A tool that receives anything else refuses to act.
 */
export function authorizeSecurityAction({ plan, toolName, configuration, now = Date.now() }) {
  const action = plan.actions.find((candidate) => candidate.tool === toolName);
  if (!action) return null;
  const lifetimeMs = Math.min(60_000, configuration.security.response.maximumProtectionSeconds * 1000);
  return Object.freeze({
    authorizedBy: "SECURITY_POLICY",
    policyId: plan.policyId,
    threatCategory: plan.threatCategory,
    riskLevel: plan.riskLevel,
    toolName,
    expiresAt: new Date(now + lifetimeMs).toISOString(),
    automated: true,
  });
}

/** True when an authorization envelope is present, unexpired, and bound to this exact tool and policy. */
export function isSecurityAuthorizationValid(authorization, toolName, { now = Date.now() } = {}) {
  if (!authorization || typeof authorization !== "object" || Array.isArray(authorization)) return false;
  if (authorization.authorizedBy !== "SECURITY_POLICY" || authorization.automated !== true) return false;
  if (authorization.toolName !== toolName) return false;
  if (typeof authorization.policyId !== "string" || !/^AUTO_[A-Z_]+_(MEDIUM|HIGH|CRITICAL)$/.test(authorization.policyId)) return false;
  const expiry = Date.parse(authorization.expiresAt);
  return Number.isFinite(expiry) && expiry > now;
}

export { PLANS as SECURITY_RESPONSE_PLANS, boundedDuration as boundProtectionSeconds };
