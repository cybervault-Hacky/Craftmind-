/**
 * Provider-neutral developer-assistant orchestration. A provider can suggest one registered tool call; only the server
 * registry can authorize/execute it, and mutating calls always stop at the explicit confirmation boundary.
 */

import { AccountApiError, ErrorCode } from "./errors.js";
import { developerAiToolCall, developerToolSpecifications, recordDeveloperAiAudit } from "./admin-tools.js";

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
}
