/**
 * Provider-neutral developer-assistant orchestration. A provider can suggest one registered tool call; only the server
 * registry can authorize/execute it, and mutating calls always stop at the explicit confirmation boundary.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import {
  developerAiToolCall,
  developerToolSpecifications,
  recordDeveloperAiAudit,
  recordDeveloperAiSecurityAudit,
} from "./admin-tools.js";

function validatePrompt(prompt) {
  if (typeof prompt !== "string" || prompt.trim().length < 1 || prompt.length > 2000 ||
      /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(prompt)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return prompt.trim();
}

async function selectWithTimeout(provider, request) {
  let timeout;
  try {
    return await Promise.race([
      Promise.resolve().then(() => provider.selectToolCall(request)),
      new Promise((resolve, reject) => {
        timeout = setTimeout(() => reject(new Error("provider timeout")), 15_000);
        timeout.unref?.();
      }),
    ]);
  } finally {
    clearTimeout(timeout);
  }
}

function validateProviderResponse(response) {
  if (response === null || typeof response !== "object" || Array.isArray(response) ||
      Object.keys(response).some((key) => !["message", "toolCall"].includes(key)) ||
      (response.toolCall !== undefined && !Object.hasOwn(response, "toolCall"))) {
    throw new AccountApiError(ErrorCode.DEVELOPER_AI_RESPONSE_INVALID);
  }
  const message = (Object.hasOwn(response, "message") ? response.message : "") ?? "";
  if (typeof message !== "string" || message.length > 2000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(message)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_AI_RESPONSE_INVALID);
  }
  if (response.toolCall === undefined) {
    if (!message.trim()) throw new AccountApiError(ErrorCode.DEVELOPER_AI_RESPONSE_INVALID);
    return { message: message.trim(), toolCall: null };
  }
  if (message.trim().length > 1000) throw new AccountApiError(ErrorCode.DEVELOPER_AI_RESPONSE_INVALID);
  return { message: message.trim(), toolCall: response.toolCall };
}

function validateSecurityPrompt(prompt) {
  if (prompt === undefined || prompt === null || prompt === "") return "";
  if (typeof prompt !== "string" || prompt.trim().length < 1 || prompt.length > 500 ||
      /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(prompt)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
  }
  return prompt.trim();
}

function validateSecurityAnalysisResponse(response) {
  if (response === null || typeof response !== "object" || Array.isArray(response)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_AI_RESPONSE_INVALID);
  }
  // The security contract is message-only: a tool proposal in this mode — including a production incident response
  // tool — is refused outright, so the AI can never approve, request, or execute a security action.
  if (Object.keys(response).some((key) => key !== "message") || !Object.hasOwn(response, "message")) {
    throw new AccountApiError(ErrorCode.DEVELOPER_AI_RESPONSE_INVALID);
  }
  const message = response.message;
  if (typeof message !== "string" || message.trim().length < 1 || message.length > 4000 ||
      /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(message)) {
    throw new AccountApiError(ErrorCode.DEVELOPER_AI_RESPONSE_INVALID);
  }
  return message.trim();
}

export class DeveloperAiCoordinator {
  constructor({ provider = null } = {}) {
    this.provider = provider;
  }

  get isAvailable() {
    return Boolean(this.provider && typeof this.provider.selectToolCall === "function");
  }

  async runTurn({ database, configuration, actor, prompt, context = {} }) {
    let safePrompt;
    try {
      safePrompt = validatePrompt(prompt);
    } catch (error) {
      recordDeveloperAiAudit(database, actor, "FAILURE", { resultCode: ErrorCode.DEVELOPER_TOOL_INPUT_INVALID });
      throw error;
    }
    if (!this.isAvailable) {
      recordDeveloperAiAudit(database, actor, "FAILURE", { resultCode: ErrorCode.DEVELOPER_AI_UNAVAILABLE });
      throw new AccountApiError(ErrorCode.DEVELOPER_AI_UNAVAILABLE);
    }

    let proposal;
    try {
      // Deliberately give the provider only the operator's request and closed tool schemas—never database handles,
      // credentials, account rows, session state, configuration secrets, or executable functions.
      proposal = await selectWithTimeout(this.provider, Object.freeze({
        prompt: safePrompt,
        tools: developerToolSpecifications(actor),
        maximumToolCalls: 1,
      }));
    } catch {
      recordDeveloperAiAudit(database, actor, "FAILURE", { resultCode: ErrorCode.DEVELOPER_AI_UNAVAILABLE });
      throw new AccountApiError(ErrorCode.DEVELOPER_AI_UNAVAILABLE);
    }

    let validated;
    try {
      validated = validateProviderResponse(proposal);
    } catch (error) {
      recordDeveloperAiAudit(database, actor, "FAILURE", { resultCode: ErrorCode.DEVELOPER_AI_RESPONSE_INVALID });
      throw error;
    }

    if (validated.toolCall === null) {
      recordDeveloperAiAudit(database, actor, "SUCCESS", { resultType: "message" });
      return { message: validated.message, tool: null, result: null };
    }

    let result;
    try {
      result = developerAiToolCall(database, configuration, actor, validated.toolCall, context);
    } catch (error) {
      const outcome = error instanceof AccountApiError && error.code === ErrorCode.DEVELOPER_ACCESS_DENIED ? "DENIED" : "FAILURE";
      recordDeveloperAiAudit(database, actor, outcome, {
        resultCode: error instanceof AccountApiError ? error.code : ErrorCode.UNKNOWN_ERROR,
      });
      throw error;
    }
    const toolName = typeof validated.toolCall.name === "string" ? validated.toolCall.name : null;
    recordDeveloperAiAudit(database, actor, "SUCCESS", {
      toolName, confirmationRequired: Boolean(result?.confirmationRequired),
    });
    return {
      message: validated.message,
      tool: toolName,
      result,
    };
  }

  /**
   * AI-assisted security investigation. The model receives a bounded, sanitized security brief (counts, categories,
   * statuses, reasons, opaque references) and may only return prose: summarize, explain, classify against the
   * registered categories, or recommend one of the registered response tools by name. It cannot propose a tool call
   * here, cannot mint authorization, and cannot alter the autonomous policy path — detection and immediate protection
   * already ran without it, and continue to run if this provider is missing or failing.
   */
  async runSecurityAnalysis({ database, actor, prompt = "", incidentId = null, context = {} }) {
    const engine = context.securityEngine;
    let safePrompt;
    try {
      safePrompt = validateSecurityPrompt(prompt);
      if (!incidentId && !safePrompt) throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      if (incidentId !== null && (typeof incidentId !== "string" || !/^inc_[0-9a-f-]{36}$/.test(incidentId))) {
        throw new AccountApiError(ErrorCode.DEVELOPER_TOOL_INPUT_INVALID);
      }
    } catch (error) {
      recordDeveloperAiSecurityAudit(database, actor, "FAILURE", { resultCode: ErrorCode.DEVELOPER_TOOL_INPUT_INVALID });
      throw error;
    }
    if (!engine || typeof engine.buildSecurityBrief !== "function") {
      recordDeveloperAiSecurityAudit(database, actor, "FAILURE", { resultCode: ErrorCode.SECURITY_ENGINE_UNAVAILABLE });
      throw new AccountApiError(ErrorCode.SECURITY_ENGINE_UNAVAILABLE);
    }

    let brief;
    try {
      brief = engine.buildSecurityBrief({ incidentId });
    } catch (error) {
      recordDeveloperAiSecurityAudit(database, actor, "FAILURE", {
        resultCode: error instanceof AccountApiError ? error.code : ErrorCode.UNKNOWN_ERROR,
      });
      throw error;
    }

    if (!this.isAvailable) {
      recordDeveloperAiSecurityAudit(database, actor, "FAILURE", { resultCode: ErrorCode.DEVELOPER_AI_UNAVAILABLE });
      throw new AccountApiError(ErrorCode.DEVELOPER_AI_UNAVAILABLE);
    }

    let proposal;
    try {
      proposal = await selectWithTimeout(this.provider, Object.freeze({
        mode: "SECURITY_ANALYSIS",
        responseContract: "MESSAGE_ONLY",
        maximumToolCalls: 0,
        prompt: safePrompt || "Summarize the supplied security context and explain the recommended next step.",
        tools: [],
        securityContext: brief,
      }));
    } catch {
      recordDeveloperAiSecurityAudit(database, actor, "FAILURE", { resultCode: ErrorCode.DEVELOPER_AI_UNAVAILABLE });
      throw new AccountApiError(ErrorCode.DEVELOPER_AI_UNAVAILABLE);
    }

    let message;
    try {
      message = validateSecurityAnalysisResponse(proposal);
    } catch (error) {
      recordDeveloperAiSecurityAudit(database, actor, "FAILURE", { resultCode: ErrorCode.DEVELOPER_AI_RESPONSE_INVALID });
      throw error;
    }

    recordDeveloperAiSecurityAudit(database, actor, "SUCCESS", {
      resultType: "security_analysis",
      incident: brief.incidents[0]?.reference ?? null,
    });
    return {
      message,
      incidentReference: brief.incidents[0]?.reference ?? null,
      context: { counts: brief.counts, incidents: brief.incidents, recentActions: brief.recentActions },
    };
  }
}
