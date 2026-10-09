(() => {
  "use strict";

  // Tokens exist only in this closure for the current tab. Nothing is written to browser storage or the DOM.
  let accessToken = null;
  let refreshToken = null;
  let currentDeveloper = null;
  let selectedUserEmail = null;
  let pendingConfirmationToken = null;
  let selectedPanel = "overview-panel";

  const $ = (selector) => document.querySelector(selector);
  const notice = $("#notice");
  const loginPanel = $("#login-panel");
  const dashboard = $("#dashboard");

  function setNotice(message, tone = "info") {
    notice.textContent = message || "";
    notice.dataset.tone = tone;
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = String(text);
    return node;
  }

  function clear(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }

  async function parseResponse(response) {
    const text = await response.text();
    let payload = null;
    try { payload = text ? JSON.parse(text) : null; } catch { payload = null; }
    if (!response.ok) {
      const failure = new Error(payload?.error?.message || "The developer service did not complete the request.");
      failure.code = payload?.error?.code || "UNKNOWN_ERROR";
      failure.status = response.status;
      throw failure;
    }
    if (text && payload === null) throw new Error("The developer service returned an invalid response.");
    return payload;
  }

  async function send(path, { method = "GET", body, authenticated = true } = {}) {
    const headers = new Headers();
    if (body !== undefined) headers.set("Content-Type", "application/json");
    if (authenticated && accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
    const response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store",
      credentials: "omit",
      redirect: "error",
      referrerPolicy: "no-referrer",
    });
    return parseResponse(response);
  }

  async function rotateDeveloperSession() {
    if (!refreshToken) throw new Error("Sign in again to continue.");
    const response = await send("/developer/auth/refresh", {
      method: "POST", body: { refreshToken }, authenticated: false,
    });
    accessToken = response.session.accessToken;
    refreshToken = response.session.refreshToken;
  }

  async function api(path, options = {}, retry = true) {
    try {
      return await send(path, options);
    } catch (error) {
      if (retry && error.status === 401 && refreshToken && path !== "/developer/auth/refresh" && path !== "/developer/auth/logout") {
        try {
          await rotateDeveloperSession();
          return await api(path, options, false);
        } catch (refreshError) {
          clearCredentials();
          throw refreshError;
        }
      }
      throw error;
    }
  }

  function clearCredentials() {
    accessToken = null;
    refreshToken = null;
    currentDeveloper = null;
    loginPanel.hidden = false;
    dashboard.hidden = true;
    pendingConfirmationToken = null;
    hideConfirmation();
  }

  async function tool(name, args = {}) {
    const response = await api("/developer/tools/invoke", {
      method: "POST", body: { tool: name, arguments: args },
    });
    return response.result;
  }

  function showPanel(panelId) {
    selectedPanel = panelId;
    document.querySelectorAll(".panel").forEach((panel) => { panel.hidden = panel.id !== panelId; });
    document.querySelectorAll(".tabs button").forEach((button) => {
      const selected = button.dataset.panel === panelId;
      if (selected) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });
  }

  function showConfirmation(result, onCompleted) {
    pendingConfirmationToken = result.confirmationToken;
    $("#confirmation-summary").textContent = result.summary;
    $("#confirmation").hidden = false;
    $("#confirm-action").onclick = async () => {
      if (!pendingConfirmationToken) return;
      const submitted = pendingConfirmationToken;
      pendingConfirmationToken = null;
      $("#confirm-action").disabled = true;
      try {
        const completed = await api("/developer/tools/confirm", {
          method: "POST", body: { confirmationToken: submitted },
        });
        hideConfirmation();
        setNotice("The confirmed administrative action completed. Its result was appended to the audit log.", "success");
        if (onCompleted) await onCompleted(completed.result);
      } catch (error) {
        hideConfirmation();
        setNotice(error.message, "error");
      } finally {
        $("#confirm-action").disabled = false;
      }
    };
    $("#cancel-action").onclick = async () => {
      if (!pendingConfirmationToken) return;
      const submitted = pendingConfirmationToken;
      pendingConfirmationToken = null;
      try {
        await api("/developer/tools/cancel", { method: "POST", body: { confirmationToken: submitted } });
        setNotice("The action was cancelled and recorded.");
      } catch (error) {
        setNotice(error.message, "error");
      } finally {
        hideConfirmation();
      }
    };
  }

  function hideConfirmation() {
    $("#confirmation").hidden = true;
    $("#confirmation-summary").textContent = "";
    pendingConfirmationToken = null;
  }

  async function invokeForConfirmation(name, args, onCompleted) {
    try {
      const result = await tool(name, args);
      if (!result?.confirmationRequired || typeof result.confirmationToken !== "string") {
        throw new Error("The server did not issue a valid confirmation challenge.");
      }
      showConfirmation(result, onCompleted);
      setNotice("Review the action summary and explicitly confirm or cancel.");
    } catch (error) {
      setNotice(error.message, "error");
    }
  }

  async function loadOverview() {
    try {
      const data = await tool("overview");
      const target = $("#overview-content");
      clear(target);
      const entries = [
        ["Active users", data.users.active], ["Suspended users", data.users.suspended],
        ["Deleted users", data.users.deleted], ["Active user sessions", data.activeUserSessions],
        ["Active developer accounts", data.activeDeveloperAccounts],
      ];
      for (const [label, value] of entries) {
        const card = element("div", "metric");
        card.append(element("strong", "", value));
        card.append(element("span", "muted", label));
        target.append(card);
      }
    } catch (error) { setNotice(error.message, "error"); }
  }

  async function inspectSelectedUser() {
    if (!selectedUserEmail) return;
    try {
      const result = await tool("inspectUser", { email: selectedUserEmail });
      const panel = $("#user-result");
      clear(panel);
      const user = result.user;
      const record = element("div", "record");
      record.append(element("h3", "", user.email));
      record.append(element("p", "", `Display name: ${user.displayName}`));
      record.append(element("p", "", `Status: ${user.status}`));
      record.append(element("p", "", `Email verified: ${user.emailVerified ? "Yes" : "No"}`));
      record.append(element("p", "", `Created: ${user.createdAt}`));
      record.append(element("p", "", `Updated: ${user.updatedAt}`));
      const actions = element("div", "button-row");
      const listButton = element("button", "secondary", "List active sessions");
      listButton.type = "button";
      listButton.onclick = loadUserSessions;
      actions.append(listButton);
      if (["OWNER", "ADMIN"].includes(currentDeveloper.role)) {
        const revokeButton = element("button", "secondary", "Revoke all sessions");
        revokeButton.type = "button";
        revokeButton.onclick = () => invokeForConfirmation("revokeUserSessions", { email: selectedUserEmail }, refreshUserPanels);
        actions.append(revokeButton);
        const statusButton = element("button", user.status === "ACTIVE" ? "danger" : "secondary", user.status === "ACTIVE" ? "Suspend account" : "Restore account");
        statusButton.type = "button";
        statusButton.onclick = () => invokeForConfirmation(user.status === "ACTIVE" ? "suspendUser" : "restoreUser", { email: selectedUserEmail }, refreshUserPanels);
        actions.append(statusButton);
      }
      if (actions.childElementCount > 0) record.append(actions);
      panel.append(record);
      const canManageGrants = ["OWNER", "ADMIN"].includes(currentDeveloper.role);
      $("#grant-form").hidden = !canManageGrants;
      $("#grant-form").querySelector("button").disabled = !canManageGrants;
      await loadEntitlements();
    } catch (error) { setNotice(error.message, "error"); }
  }

  async function loadUserSessions() {
    if (!selectedUserEmail) return;
    try {
      const result = await tool("listUserSessions", { email: selectedUserEmail });
      const target = $("#user-sessions");
      clear(target);
      target.append(element("h3", "", `Active sessions (${result.sessions.length})`));
      if (!result.sessions.length) target.append(element("p", "muted", "No active sessions."));
      for (const session of result.sessions) {
        const record = element("div", "record");
        record.append(element("p", "", session.deviceLabel));
        record.append(element("p", "muted", `Created ${session.createdAt} · Last used ${session.lastUsedAt} · Expires ${session.expiresAt}`));
        target.append(record);
      }
      setNotice("Session credentials, client addresses, user agents, and fingerprints are not exposed.", "success");
    } catch (error) { setNotice(error.message, "error"); }
  }

  async function loadEntitlements() {
    if (!selectedUserEmail) return;
    try {
      const result = await tool("listEntitlements", { email: selectedUserEmail });
      const target = $("#grant-list");
      clear(target);
      target.append(element("h3", "", "Access-grant metadata"));
      if (!result.grants.length) target.append(element("p", "muted", "No administrative grants."));
      for (const grant of result.grants) {
        const record = element("div", "record");
        record.append(element("p", "", `${grant.entitlementKey} · ${grant.active ? "Active" : "Inactive"}`));
        record.append(element("p", "muted", `Granted ${grant.grantedAt} · Expires ${grant.expiresAt}${grant.revokedAt ? ` · Revoked ${grant.revokedAt}` : ""}`));
        if (grant.active && ["OWNER", "ADMIN"].includes(currentDeveloper.role)) {
          const revoke = element("button", "danger", "Prepare revocation");
          revoke.type = "button";
          revoke.onclick = () => invokeForConfirmation("revokeEntitlement", { grantId: grant.grantId }, loadEntitlements);
          record.append(revoke);
        }
        target.append(record);
      }
    } catch (error) { setNotice(error.message, "error"); }
  }

  async function refreshUserPanels() {
    await inspectSelectedUser();
    await loadUserSessions();
    await loadEntitlements();
    await loadOverview();
  }

  function securityRecord(title, lines) {
    const record = element("div", "record");
    record.append(element("p", "", title));
    for (const line of lines.filter(Boolean)) record.append(element("p", "muted", line));
    return record;
  }

  async function loadSecurityCenter() {
    try {
      const overview = await tool("securityOverview");
      const metrics = [
        ["Status", overview.status], ["Open incidents", overview.openIncidents],
        ["Critical incidents", overview.criticalIncidents], ["Investigating", overview.investigatingIncidents],
        ["Active protections", overview.activeProtections], ["Unread alerts", overview.unreadNotifications],
        ["Events (24h)", overview.eventsLast24h], ["Automated actions (24h)", overview.automatedActionsLast24h],
      ];
      const overviewTarget = $("#security-overview");
      clear(overviewTarget);
      for (const [label, value] of metrics) {
        const card = element("div", "metric");
        card.append(element("strong", "", value));
        card.append(element("span", "muted", label));
        overviewTarget.append(card);
      }

      const notifications = await tool("listSecurityNotifications", { limit: 25 });
      const notificationTarget = $("#security-notifications");
      clear(notificationTarget);
      if (!notifications.notifications.length) notificationTarget.append(element("p", "muted", "No security alerts."));
      for (const alert of notifications.notifications) {
        notificationTarget.append(securityRecord(
          `${alert.priority} · ${alert.title}`,
          [alert.body, `${alert.createdAt} · incident ${alert.incidentReference ?? "n/a"} · ${alert.status}`],
        ));
      }

      const actions = await tool("listSecurityActions", { limit: 25 });
      const actionTarget = $("#security-actions");
      clear(actionTarget);
      if (!actions.actions.length) actionTarget.append(element("p", "muted", "No automated protections recorded yet."));
      for (const action of actions.actions) {
        actionTarget.append(securityRecord(
          `${action.actionType} · ${action.result}${action.resultCode ? ` (${action.resultCode})` : ""}`,
          [
            `${action.occurredAt} · scope ${action.scope}${action.expiresAt ? ` · expires ${action.expiresAt}` : ""}`,
            `policy ${action.policyId} · reversible ${action.reversible ? "yes" : "no"}${action.releasedAt ? ` · released ${action.releasedAt}` : ""}`,
          ],
        ));
      }

      const incidents = await tool("listSecurityIncidents", { limit: 25 });
      const incidentTarget = $("#security-incidents");
      const incidentSelect = $("#security-ai-incident");
      clear(incidentTarget);
      clear(incidentSelect);
      incidentSelect.append(new Option("Whole security overview", ""));
      if (!incidents.incidents.length) incidentTarget.append(element("p", "muted", "No incidents recorded."));
      for (const incident of incidents.incidents) {
        incidentTarget.append(securityRecord(
          `${incident.severity} · ${incident.reference} · ${incident.threatCategory}`,
          [
            `Status ${incident.status} · risk ${incident.riskScore}/100 · ${incident.eventCount} signals`,
            `Detected ${incident.detectedAt} · last activity ${incident.lastActivityAt}`,
            incident.reasons.length ? `Reasons: ${incident.reasons.join("; ")}` : null,
            incident.resolution ? `Resolution: ${incident.resolution}` : null,
          ],
        ));
        incidentSelect.append(new Option(`${incident.severity} ${incident.reference}`, incident.incidentId));
      }

      const events = await tool("listSecurityEvents", { limit: 40 });
      const eventTarget = $("#security-events");
      clear(eventTarget);
      if (!events.events.length) eventTarget.append(element("p", "muted", "No security events recorded."));
      for (const event of events.events) {
        eventTarget.append(securityRecord(
          `${event.severity} · ${event.eventType} · ${event.result}`,
          [`${event.occurredAt} · ${event.sourceCategory}${event.routeCategory ? ` · ${event.routeCategory}` : ""}${event.incidentId ? " · linked to an incident" : ""}`],
        ));
      }
      return overview;
    } catch (error) {
      setNotice(error.message, "error");
      return null;
    }
  }

  async function loadSecurityAiStatus() {
    try {
      const status = await api("/developer/ai/status");
      const submit = $("#security-ai-submit");
      submit.disabled = !status.available;
      if (!status.available) {
        $("#security-ai-result").textContent = "Developer AI is unavailable in this service. Detection, incidents, and automated protections continue to run without it.";
      }
    } catch (error) {
      $("#security-ai-result").textContent = error.message;
      $("#security-ai-submit").disabled = true;
    }
  }

  async function loadDeveloperSessions() {
    try {
      const result = await api("/developer/auth/sessions");
      const target = $("#developer-sessions");
      clear(target);
      for (const session of result.sessions) {
        const record = element("div", "record");
        record.append(element("p", "", `${session.deviceLabel}${session.isCurrent ? " · This session" : ""}`));
        record.append(element("p", "muted", `Created ${session.createdAt} · Last used ${session.lastUsedAt} · Expires ${session.expiresAt}`));
        if (!session.isCurrent) {
          const revoke = element("button", "danger", "Revoke session");
          revoke.type = "button";
          revoke.onclick = async () => {
            try {
              await api("/developer/auth/sessions/revoke", { method: "POST", body: { sessionId: session.sessionId } });
              setNotice("Developer session revoked.", "success");
              await loadDeveloperSessions();
            } catch (error) { setNotice(error.message, "error"); }
          };
          record.append(revoke);
        }
        target.append(record);
      }
      if (!result.sessions.length) target.append(element("p", "muted", "No active developer sessions."));
    } catch (error) { setNotice(error.message, "error"); }
  }

  async function loadAudit() {
    try {
      const result = await tool("listAuditLog", { limit: 50 });
      const target = $("#audit-events");
      clear(target);
      for (const event of result.events) {
        const record = element("article", "record");
        record.append(element("p", "", `${event.action} · ${event.outcome}`));
        record.append(element("p", "muted", [
          event.occurredAt,
          `Actor ${event.actorKind}${event.actorEmail ? ` (${event.actorEmail})` : ""}`,
          event.targetEmail ? `Target ${event.targetEmail}` : null,
          event.incidentId ? "Incident reference recorded" : null,
        ].filter(Boolean).join(" · ")));
        const metadata = element("pre", "result", JSON.stringify(event.metadata));
        record.append(metadata);
        target.append(record);
      }
      if (!result.events.length) target.append(element("p", "muted", "No audit events yet."));
    } catch (error) { setNotice(error.message, "error"); }
  }

  async function loadConfigurationStatus() {
    try {
      const result = await tool("configurationStatus");
      const target = $("#config-content");
      clear(target);
      const rows = [
        ["Service", result.service], ["Environment", result.production ? "Production" : "Non-production"],
        ["Schema version", result.schemaVersion], ["Email mode", result.emailDeliveryMode],
        ["Bootstrap configured", result.bootstrap.configured ? "Yes" : "No"],
        ["Bootstrap consumed", result.bootstrap.consumed ? "Yes" : "No"],
        ["Developer AI environment configured", result.developerAi.environmentConfigured ? "Yes" : "No"],
        ["Developer AI adapter available", result.developerAi.adapterAvailable ? "Yes" : "No"],
        ["Configured AI provider", result.developerAi.provider || "None"],
        ["Configured AI model", result.developerAi.model || "None"],
        ["Rate limiter scope", result.rateLimitScope],
      ];
      for (const [label, value] of rows) {
        const row = element("div", "record");
        row.append(element("strong", "", `${label}: `));
        row.append(element("span", "", value));
        target.append(row);
      }
    } catch (error) { setNotice(error.message, "error"); }
  }

  async function loadAiStatus() {
    try {
      const result = await api("/developer/ai/status");
      const available = result.available;
      $("#ai-status").textContent = available
        ? "Provider adapter available. Each response is still treated as an untrusted registered-tool proposal."
        : "Unavailable: no Developer AI provider adapter is installed in this service. No external AI call will be attempted.";
      $("#ai-submit").disabled = !available;
    } catch (error) {
      $("#ai-status").textContent = error.message;
      $("#ai-submit").disabled = true;
    }
  }

  async function showDashboard() {
    loginPanel.hidden = true;
    dashboard.hidden = false;
    $("#identity").textContent = currentDeveloper.email;
    $("#role").textContent = currentDeveloper.role;
    const isOwner = currentDeveloper.role === "OWNER";
    $('[data-panel="config-panel"]').hidden = !isOwner;
    $("#grant-form").hidden = !["OWNER", "ADMIN"].includes(currentDeveloper.role);
    showPanel("overview-panel");
    const tasks = [loadOverview(), loadAiStatus()];
    if (isOwner) tasks.push(loadConfigurationStatus());
    await Promise.all(tasks);
    try {
      // High-priority security alerts surface immediately when the dashboard opens (Phase 20 notification foundation).
      const securityStatus = await tool("securityOverview");
      if (securityStatus.unreadNotifications > 0 || securityStatus.criticalIncidents > 0) {
        setNotice(
          `Security alerts: ${securityStatus.unreadNotifications} unread alert(s); ${securityStatus.criticalIncidents} active critical incident(s); ${securityStatus.activeProtections} active automated protection(s). Open the Security center.`,
          "error",
        );
      }
    } catch { /* the security overview is informational; a failure must not block the dashboard */ }
  }

  $("#login-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const email = $("#login-email").value;
    const password = $("#login-password").value;
    $("#login-password").value = "";
    try {
      const response = await send("/developer/auth/login", { method: "POST", body: { email, password }, authenticated: false });
      accessToken = response.session.accessToken;
      refreshToken = response.session.refreshToken;
      currentDeveloper = response.developer;
      const me = await api("/developer/auth/me");
      currentDeveloper = me.developer;
      setNotice("Developer session established. Tokens are held in memory only.", "success");
      await showDashboard();
    } catch (error) {
      clearCredentials();
      setNotice(error.message, "error");
    }
  });

  $("#bootstrap-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const secret = $("#bootstrap-secret").value;
    const password = $("#bootstrap-password").value;
    $("#bootstrap-secret").value = "";
    $("#bootstrap-password").value = "";
    try {
      await send("/developer/auth/bootstrap", { method: "POST", body: { secret, password }, authenticated: false });
      setNotice("Initial owner created. Bootstrap is consumed; sign in with the password you just set.", "success");
      $("#login-email").focus();
    } catch (error) { setNotice(error.message, "error"); }
  });

  $("#logout").addEventListener("click", async () => {
    let remoteRevoked = false;
    let failure = null;
    try {
      await api("/developer/auth/me"); // refresh an expired access credential first, if possible
      await api("/developer/auth/logout", { method: "POST", body: {} });
      remoteRevoked = true;
    } catch (error) { failure = error; }
    clearCredentials();
    if (remoteRevoked) setNotice("Signed out. The developer session was revoked.", "success");
    else setNotice(`Local credentials were cleared, but server revocation did not complete: ${failure?.message || "try again from a trusted network"}`, "error");
  });

  document.querySelectorAll(".tabs button").forEach((button) => {
    button.addEventListener("click", async () => {
      showPanel(button.dataset.panel);
      if (selectedPanel === "security-panel") await loadDeveloperSessions();
      if (selectedPanel === "security-center-panel") {
        await loadSecurityCenter();
        await loadSecurityAiStatus();
      }
      if (selectedPanel === "audit-panel") await loadAudit();
      if (selectedPanel === "ai-panel") await loadAiStatus();
      if (selectedPanel === "config-panel") await loadConfigurationStatus();
    });
  });

  $("#user-search-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    selectedUserEmail = $("#user-email").value.trim();
    clear($("#user-result"));
    clear($("#user-sessions"));
    clear($("#grant-list"));
    await inspectSelectedUser();
  });

  $("#grant-form").querySelector("button").disabled = true;
  $("#grant-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!selectedUserEmail) return;
    const localExpiry = $("#grant-expiry").value;
    if (!localExpiry) return setNotice("Choose a grant expiry.", "error");
    const expiresAt = new Date(localExpiry).toISOString();
    await invokeForConfirmation("grantEntitlement", {
      email: selectedUserEmail,
      entitlementKey: $("#grant-type").value,
      expiresAt,
    }, refreshUserPanels);
  });

  $("#refresh-dev-sessions").addEventListener("click", loadDeveloperSessions);
  $("#refresh-audit").addEventListener("click", loadAudit);
  $("#refresh-security").addEventListener("click", async () => {
    const overview = await loadSecurityCenter();
    if (overview) setNotice("Security center refreshed from stored incident, event, and action records.", "success");
  });

  $("#security-ai-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const incidentId = $("#security-ai-incident").value;
    const prompt = $("#security-ai-prompt").value.trim();
    const result = $("#security-ai-result");
    result.textContent = "Working…";
    try {
      const response = await api("/developer/ai/security-summary", {
        method: "POST",
        body: { ...(incidentId ? { incidentId } : {}), ...(prompt ? { prompt } : {}) },
      });
      result.textContent = response.message;
      if (response.incidentReference) result.textContent += `\n\nIncident: ${response.incidentReference}`;
    } catch (error) {
      result.textContent = error.message;
    }
  });

  $("#ai-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const prompt = $("#ai-prompt").value;
    $("#ai-prompt").value = "";
    $("#ai-result").textContent = "Working…";
    try {
      const response = await api("/developer/ai/turn", { method: "POST", body: { prompt } });
      $("#ai-result").textContent = response.message || "";
      if (response.tool && response.result) {
        if (response.result.confirmationRequired) {
          $("#ai-result").textContent += `\n\nRegistered tool: ${response.tool}\nThe action is waiting for your explicit confirmation.`;
          showConfirmation(response.result, refreshUserPanels);
        } else {
          $("#ai-result").textContent += `\n\nRegistered tool: ${response.tool}\n${JSON.stringify(response.result, null, 2)}`;
        }
      }
    } catch (error) { $("#ai-result").textContent = error.message; }
  });

  clearCredentials();
})();
