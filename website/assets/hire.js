/**
 * Hire a Builder controllers (Phase 26).
 *
 * Five pages, one rule each: **real service data or an honest state — never an invented job, profile, count, or
 * award.** The directory searches OPEN jobs through the adapter; the detail page proposes through it; the buyer
 * posts, edits, cancels, and awards through it; the creator manages their own proposals through it. Every value
 * from the service is escaped before it reaches markup, every write reports the server's own typed message, and
 * nothing is written to browser storage. Sign-in prompts appear only where a session is actually required.
 *
 * This module performs no network request of its own (the adapter module is the only fetch boundary), stores
 * nothing, and renders no sample record: there is no preview mode here because hiring data is not illustrative
 * layout — an unconfigured or empty service simply says so and offers the real next action.
 */

import { createAdapters, RESULT } from "./adapters.js";
import {
  STATE, announce, ensureLiveRegion, escapeAttribute, escapeText, formatTimestamp, orDash, renderLoading, renderState,
} from "./state.js";

const PAGE_SIZE = 12;
const JOB_ID_PATTERN = /^job_[0-9a-fA-F-]{36}$/;
const PROPOSAL_ID_PATTERN = /^prp_[0-9a-fA-F-]{36}$/;
const LOADERS_BY_EDITION = Object.freeze({
  java: ["Fabric", "Forge", "NeoForge", "Vanilla"],
  bedrock: ["Bedrock Native"],
  legacy: [],
});
const CURRENCIES = ["INR", "USD", "EUR", "GBP"];

function statusBadge(status) {
  const tones = {
    OPEN: "current", AWARDED: "planned", CANCELLED: "muted",
    SUBMITTED: "current", WITHDRAWN: "muted", SELECTED: "current", NOT_SELECTED: "muted",
  };
  return `<span class="badge badge--${tones[status] ?? "muted"}">${escapeText(status)}</span>`;
}

function budgetLine(budget) {
  if (!budget) return "No budget range set";
  return `${escapeText(orDash(budget.min))}–${escapeText(orDash(budget.max))} ${escapeText(budget.currency)}`;
}

/** Shared failure rendering: typed service refusals keep the server's own message; sign-in stays its own state. */
function renderFailure(region, result, { title, hint }) {
  if (result.status === RESULT.UNAUTHORIZED) {
    renderState(region, {
      kind: STATE.UNAUTHORIZED,
      title: "Sign in to continue",
      message: result.message ?? "This action needs an active account session.",
      action: { label: "Sign in", href: "../signin.html" },
    });
    return;
  }
  if (result.status === RESULT.UNAVAILABLE) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No account service is connected to this site",
      message: result.message ?? "There is no hire service to read from, so no jobs, proposals, or counts can be shown.",
      details: ["Connect the account service to load real hire requests and proposals."],
    });
    return;
  }
  renderState(region, {
    kind: STATE.ERROR,
    title,
    message: result.message ?? hint,
    details: ["Nothing was changed unless the message says otherwise."],
  });
}

function jobCardMarkup(job) {
  const detailHref = `job.html?job=${encodeURIComponent(job.id)}`;
  const chips = [
    `<span class="badge badge--muted">${escapeText(job.edition)}</span>`,
    `<span class="badge badge--muted">${escapeText(job.minecraftVersion)}</span>`,
    ...(job.deadline ? [`<span class="badge badge--planned">By ${escapeText(job.deadline)}</span>`] : []),
    ...(job.status ? [statusBadge(job.status)] : []),
  ].join(" ");
  return `<article class="card listing-card">
    <span class="card-meta">${escapeText(job.proposalCount ?? 0)} proposal(s)</span>
    <h3><a href="${escapeAttribute(detailHref)}">${escapeText(job.title)}</a></h3>
    <p>${escapeText(job.summary ?? job.description)}</p>
    <p class="small muted">${chips}</p>
    <p class="small"><strong>${budgetLine(job.budget)}</strong></p>
  </article>`;
}

/* ------------------------------------------------------------------ public directory (hire.html) */

function initHireDirectory(root) {
  const adapters = createAdapters();
  const region = root.querySelector("[data-jobs-region]");
  const form = root.querySelector("[data-hire-form]");
  const statusLine = root.querySelector("[data-hire-status]");
  const prevButton = root.querySelector("[data-hire-prev]");
  const nextButton = root.querySelector("[data-hire-next]");
  const pageInfo = root.querySelector("[data-hire-page]");
  const searchInput = root.querySelector("[data-hire-search]");
  const editionSelect = root.querySelector("[data-hire-edition]");
  const versionInput = root.querySelector("[data-hire-version]");
  const filters = { q: "", edition: "", version: "", offset: 0 };
  let lastHasMore = false;
  let serial = 0;
  let debounceTimer = null;

  if (!adapters.hire.configured) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No open jobs are available",
      message: "No account service is connected to this site, so there is no hire service to read open job requests from.",
      details: [
        "Connect the account service to load real OPEN jobs posted by buyers.",
        "Posting a job and sending a proposal both need that service too.",
      ],
      action: { label: "Post a job", href: "hire-post.html" },
    });
    return;
  }

  async function update() {
    const requestSerial = ++serial;
    renderLoading(region, { rows: 5, title: "Searching open jobs" });
    const params = { limit: String(PAGE_SIZE), offset: String(filters.offset) };
    for (const key of ["q", "edition", "version"]) if (filters[key]) params[key] = filters[key];
    const result = await adapters.hire.searchJobs(params);
    if (requestSerial !== serial) return;
    if (result.status !== RESULT.OK) {
      renderFailure(region, result, { title: "Open jobs could not be loaded", hint: "The hire service did not answer." });
      if (statusLine) statusLine.textContent = "The hire service could not be reached with these filters.";
      return;
    }
    const payload = result.payload ?? {};
    const items = Array.isArray(payload.items) ? payload.items : [];
    const total = Number(payload.total ?? 0);
    lastHasMore = Boolean(payload.hasMore);
    if (items.length === 0) {
      renderState(region, {
        kind: STATE.EMPTY,
        title: "No open jobs match",
        message: filters.q || filters.edition || filters.version
          ? "Nothing in the OPEN job directory matches these filters right now."
          : "There are no open hire requests right now. You can post the first one.",
        action: { label: "Post a job", href: "hire-post.html" },
      });
    } else {
      region.dataset.state = STATE.POPULATED;
      region.className = "card-grid";
      region.innerHTML = items.map(jobCardMarkup).join("");
    }
    const shownFrom = total === 0 ? 0 : filters.offset + 1;
    const shownTo = filters.offset + items.length;
    if (statusLine) statusLine.textContent = `${total} open job(s) match. Showing ${shownFrom}–${shownTo}.`;
    if (pageInfo) {
      const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
      pageInfo.textContent = total === 0 ? "No pages" : `Page ${Math.floor(filters.offset / PAGE_SIZE) + 1} of ${pages}`;
    }
    if (prevButton) prevButton.disabled = filters.offset === 0;
    if (nextButton) nextButton.disabled = !lastHasMore;
    announce(`${total} open job(s) match these filters.`);
  }

  const schedule = () => {
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => { filters.offset = 0; update(); }, 250);
  };
  form?.addEventListener("submit", (event) => { event.preventDefault(); filters.offset = 0; update(); });
  searchInput?.addEventListener("input", () => { filters.q = searchInput.value.trim(); schedule(); });
  editionSelect?.addEventListener("change", () => { filters.edition = editionSelect.value; filters.offset = 0; update(); });
  versionInput?.addEventListener("input", () => { filters.version = versionInput.value.trim(); schedule(); });
  prevButton?.addEventListener("click", () => { filters.offset = Math.max(0, filters.offset - PAGE_SIZE); update(); });
  nextButton?.addEventListener("click", () => { if (lastHasMore) { filters.offset += PAGE_SIZE; update(); } });
  update();
}

/* --------------------------------------------------------------------- job detail (job.html) */

function initHireJob(root) {
  const adapters = createAdapters();
  const region = root.querySelector("[data-job-region]");
  const proposalForm = root.querySelector("[data-proposal-form]");
  const proposalStatus = root.querySelector("[data-proposal-status]");
  const jobId = new URLSearchParams(globalThis.location.search).get("job") ?? "";

  if (!adapters.hire.configured) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No job service is connected to this site",
      message: "No account service is connected, so no job detail or proposal can be read or sent from here.",
    });
    return;
  }
  if (!JOB_ID_PATTERN.test(jobId)) {
    renderState(region, {
      kind: STATE.ERROR,
      title: "That is not a job address",
      message: "Open a job from the directory so its identifier can be read from the service.",
      action: { label: "Back to open jobs", href: "hire.html" },
    });
    return;
  }

  function setProposalAvailability(signedIn) {
    if (!proposalForm) return;
    proposalForm.hidden = !signedIn;
    const prompt = root.querySelector("[data-proposal-signin]");
    if (prompt) prompt.hidden = signedIn;
  }

  async function load() {
    renderLoading(region, { rows: 6, title: "Loading job" });
    const result = await adapters.hire.getJob(jobId);
    if (result.status !== RESULT.OK) {
      renderFailure(region, result, { title: "This job is not available", hint: "It may have been awarded or cancelled." });
      if (proposalForm) proposalForm.hidden = true;
      const prompt = root.querySelector("[data-proposal-signin]");
      if (prompt) prompt.hidden = true;
      return;
    }
    const job = result.payload ?? {};
    region.dataset.state = STATE.POPULATED;
    region.className = "section-tight";
    const images = Array.isArray(job.imageReferences) ? job.imageReferences : [];
    region.innerHTML = `
      <article class="card">
        <div class="state-head">${statusBadge("OPEN")}<h2>${escapeText(job.title)}</h2></div>
        <p class="small muted">
          <span class="badge badge--muted">${escapeText(job.edition)}</span>
          <span class="badge badge--muted">${escapeText(job.minecraftVersion)}</span>
          ${(job.loaders ?? []).map((loader) => `<span class="badge badge--muted">${escapeText(loader)}</span>`).join(" ")}
          ${job.deadline ? `<span class="badge badge--planned">By ${escapeText(job.deadline)}</span>` : ""}
        </p>
        <p><strong>${budgetLine(job.budget)}</strong> · ${escapeText(job.proposalCount ?? 0)} proposal(s) so far</p>
        <p class="small muted">Posted ${escapeText(formatTimestamp(job.createdAt))}</p>
        <h3>What the buyer needs</h3>
        <p>${escapeText(job.description)}</p>
        <h3>Scope</h3>
        <p>${escapeText(job.scope)}</p>
        ${images.length > 0 ? `<h3>Reference images</h3><ul class="state-list">${images.map((url) => `<li><a href="${escapeAttribute(url)}" rel="noopener noreferrer" target="_blank">${escapeText(url)}</a></li>`).join("")}</ul>` : ""}
        <p class="small muted">Awarding a proposal only records which builder the buyer selected. No payment, milestone, or delivery exists in this phase.</p>
      </article>`;
    setProposalAvailability(adapters.hire.signedIn);
    announce(`Loaded job: ${job.title}`);
  }

  proposalForm?.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!proposalStatus) return;
    proposalStatus.textContent = "";
    const values = {
      message: (proposalForm.elements.namedItem("message")?.value ?? "").trim(),
      scope: (proposalForm.elements.namedItem("scope")?.value ?? "").trim(),
      budgetMinRaw: (proposalForm.elements.namedItem("budgetMin")?.value ?? "").trim(),
      budgetMaxRaw: (proposalForm.elements.namedItem("budgetMax")?.value ?? "").trim(),
      budgetCurrency: proposalForm.elements.namedItem("budgetCurrency")?.value ?? "",
      deliveryRaw: (proposalForm.elements.namedItem("deliveryEstimateDays")?.value ?? "").trim(),
    };
    const validation = validateProposalFields(values);
    if (validation) {
      proposalStatus.textContent = validation;
      proposalStatus.dataset.tone = "error";
      return;
    }
    const body = { jobId, message: values.message, scope: values.scope };
    if (values.budgetMinRaw || values.budgetMaxRaw) {
      body.budgetMin = Number(values.budgetMinRaw);
      body.budgetMax = Number(values.budgetMaxRaw);
      body.budgetCurrency = values.budgetCurrency;
    }
    if (values.deliveryRaw) body.deliveryEstimateDays = Number(values.deliveryRaw);
    proposalStatus.dataset.tone = "";
    proposalStatus.textContent = "Sending your proposal…";
    const result = await adapters.hire.submitProposal(body);
    if (result.status === RESULT.OK) {
      proposalStatus.textContent = "Proposal sent. You can review and manage it from your proposals page.";
      proposalStatus.dataset.tone = "success";
      announce("Proposal sent.");
      proposalForm.reset();
      load();
      return;
    }
    proposalStatus.dataset.tone = "error";
    proposalStatus.textContent = result.message ?? "The proposal could not be sent.";
    if (result.status === RESULT.UNAUTHORIZED) {
      proposalStatus.innerHTML = 'Sign in to send a proposal. <a href="../signin.html">Sign in</a>';
    }
  });

  load();
}

function validateProposalFields(values) {
  if (values.message.length < 10 || values.message.length > 2000) return "The message must be 10–2000 characters.";
  if (values.scope.length < 5 || values.scope.length > 2000) return "The scope must be 5–2000 characters.";
  const budgetParts = [values.budgetMinRaw, values.budgetMaxRaw].filter(Boolean).length;
  if (budgetParts === 1) return "Provide both a minimum and a maximum budget, or neither.";
  if (budgetParts === 2) {
    const min = Number(values.budgetMinRaw);
    const max = Number(values.budgetMaxRaw);
    if (!Number.isSafeInteger(min) || !Number.isSafeInteger(max) || min < 0 || max > 100_000_000) return "Budgets are whole numbers between 0 and 100000000.";
    if (min > max) return "The minimum budget cannot exceed the maximum.";
    if (!CURRENCIES.includes(values.budgetCurrency)) return "Choose one of the listed currencies.";
  }
  if (values.deliveryRaw) {
    const days = Number(values.deliveryRaw);
    if (!Number.isSafeInteger(days) || days < 1 || days > 365) return "The delivery estimate must be 1–365 days.";
  }
  return null;
}

/* ------------------------------------------------------------------- post a job (hire-post.html) */

function initHirePost(root) {
  const adapters = createAdapters();
  const region = root.querySelector("[data-job-form-region]");
  const shell = root.querySelector("[data-job-form-shell]");
  const form = root.querySelector("[data-job-form]");
  const status = root.querySelector("[data-job-form-status]");

  if (!adapters.hire.configured) {
    if (region) region.hidden = false;
    if (shell) shell.hidden = true;
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No account service is connected to this site",
      message: "Posting a hire request writes to the account service, which is not connected to this site.",
    });
    return;
  }
  if (!adapters.hire.signedIn) {
    if (region) region.hidden = false;
    if (shell) shell.hidden = true;
    renderState(region, {
      kind: STATE.UNAUTHORIZED,
      title: "Sign in to post a job",
      message: "A job request belongs to your account, so a session is required first.",
      action: { label: "Sign in", href: "../signin.html" },
    });
    return;
  }
  if (region) region.hidden = true;

  form?.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!status) return;
    status.textContent = "";
    const field = (name) => (form.elements.namedItem(name)?.value ?? "").trim();
    const checkedLoaders = [...form.querySelectorAll('[data-hire-loader]:checked')].map((input) => input.value);
    const imageLines = field("imageReferences").split("\n").map((line) => line.trim()).filter(Boolean);
    const body = {
      title: field("title"),
      description: field("description"),
      edition: field("edition"),
      minecraftVersion: field("minecraftVersion"),
      scope: field("scope"),
      loaders: checkedLoaders,
      imageReferences: imageLines,
    };
    const budgetMinRaw = field("budgetMin");
    const budgetMaxRaw = field("budgetMax");
    if (budgetMinRaw || budgetMaxRaw) {
      body.budgetMin = Number(budgetMinRaw);
      body.budgetMax = Number(budgetMaxRaw);
      body.budgetCurrency = field("budgetCurrency");
    }
    const deadline = field("deadline");
    if (deadline) body.deadline = deadline;
    const validation = validateJobFields(body, { hasBudget: Boolean(budgetMinRaw || budgetMaxRaw), deadline });
    if (validation) {
      status.textContent = validation;
      status.dataset.tone = "error";
      return;
    }
    status.dataset.tone = "";
    status.textContent = "Posting your job…";
    const result = await adapters.hire.createJob(body);
    if (result.status === RESULT.OK) {
      status.dataset.tone = "success";
      status.textContent = "Job posted. It is live in the open directory until you award or cancel it.";
      announce("Job posted.");
      form.reset();
      const createdId = result.payload?.id;
      const manage = root.querySelector("[data-job-created-link]");
      if (manage && createdId && JOB_ID_PATTERN.test(createdId)) {
        manage.hidden = false;
        manage.querySelector("a")?.setAttribute("href", `hire-manage.html?job=${encodeURIComponent(createdId)}`);
      }
      return;
    }
    status.dataset.tone = "error";
    status.textContent = result.message ?? "The job could not be posted.";
    if (result.status === RESULT.UNAUTHORIZED) {
      status.innerHTML = 'Sign in to post a job. <a href="../signin.html">Sign in</a>';
    }
  });
}

function validateJobFields(body, { hasBudget, deadline }) {
  if (body.title.length < 3 || body.title.length > 120) return "The title must be 3–120 characters.";
  if (body.description.length < 10 || body.description.length > 5000) return "The description must be 10–5000 characters.";
  if (body.scope.length < 5 || body.scope.length > 1000) return "The scope must be 5–1000 characters.";
  if (!LOADERS_BY_EDITION[body.edition]) return "Choose an edition.";
  if (!/\d/.test(body.minecraftVersion) || body.minecraftVersion.length > 32) return "Enter the single target Minecraft version, for example 1.20.1.";
  for (const loader of body.loaders) {
    if (!LOADERS_BY_EDITION[body.edition].includes(loader)) return `The ${body.edition} edition does not use the ${loader} loader.`;
  }
  if (body.imageReferences.length > 4) return "At most four reference images are allowed.";
  for (const reference of body.imageReferences) {
    let parsed;
    try { parsed = new URL(reference); } catch { return `That reference is not a valid URL: ${reference}`; }
    if (parsed.protocol !== "https:" || parsed.username || parsed.password) return "Reference images must be plain https URLs without credentials.";
    if (reference.length > 300) return "Each reference image URL must be 300 characters or fewer.";
  }
  if (hasBudget) {
    if (!Number.isSafeInteger(body.budgetMin) || !Number.isSafeInteger(body.budgetMax)) return "Budgets are whole numbers.";
    if (body.budgetMin < 0 || body.budgetMax > 100_000_000 || body.budgetMin > body.budgetMax) return "The budget range must be ordered, between 0 and 100000000.";
    if (!CURRENCIES.includes(body.budgetCurrency)) return "Choose one of the listed currencies.";
  }
  if (deadline && deadline < new Date().toISOString().slice(0, 10)) return "The deadline cannot be in the past.";
  return null;
}

/* --------------------------------------------------------------- buyer manage (hire-manage.html) */

function initHireManage(root) {
  const adapters = createAdapters();
  const region = root.querySelector("[data-manage-region]");
  const statusLine = root.querySelector("[data-manage-status]");

  if (!adapters.hire.configured) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No account service is connected to this site",
      message: "Your posted jobs live on the account service, which is not connected to this site.",
    });
    return;
  }
  if (!adapters.hire.signedIn) {
    renderState(region, {
      kind: STATE.UNAUTHORIZED,
      title: "Sign in to manage your jobs",
      message: "Job requests are read and changed only through your own session.",
      action: { label: "Sign in", href: "../signin.html" },
    });
    return;
  }

  const params = new URLSearchParams(globalThis.location.search);
  const jobId = params.get("job") ?? "";

  async function renderList() {
    renderLoading(region, { rows: 4, title: "Loading your jobs" });
    const result = await adapters.hire.listMyJobs();
    if (result.status !== RESULT.OK) {
      renderFailure(region, result, { title: "Your jobs could not be loaded", hint: "The hire service did not answer." });
      return;
    }
    const payload = result.payload ?? {};
    const jobs = Array.isArray(payload.jobs) ? payload.jobs : [];
    if (jobs.length === 0) {
      renderState(region, {
        kind: STATE.EMPTY,
        title: "You have not posted a job yet",
        message: "Job requests you post appear here with their status and proposal counts.",
        action: { label: "Post a job", href: "hire-post.html" },
      });
      return;
    }
    region.dataset.state = STATE.POPULATED;
    region.className = "card-grid";
    region.innerHTML = jobs.map((job) => `<article class="card listing-card">
      <span class="card-meta">${statusBadge(job.status)} ${escapeText(job.proposalCount ?? 0)} proposal(s)</span>
      <h3><a href="hire-manage.html?job=${encodeURIComponent(job.id)}">${escapeText(job.title)}</a></h3>
      <p>${escapeText(job.description.slice(0, 180))}${job.description.length > 180 ? "…" : ""}</p>
      <p class="small muted">Updated ${escapeText(formatTimestamp(job.updatedAt))}</p>
    </article>`).join("");
    if (statusLine) {
      statusLine.textContent = `${payload.counts?.total ?? jobs.length} job(s): ${payload.counts?.open ?? 0} open, ${payload.counts?.awarded ?? 0} awarded, ${payload.counts?.cancelled ?? 0} cancelled.`;
    }
  }

  async function renderDetail(id) {
    renderLoading(region, { rows: 6, title: "Loading your job" });
    const result = await adapters.hire.getOwnedJob(id);
    if (result.status !== RESULT.OK) {
      renderFailure(region, result, { title: "That job could not be opened", hint: "Only your own jobs are shown here." });
      return;
    }
    const { job, proposals = [], counts = {} } = result.payload ?? {};
    const editable = job.status === "OPEN";
    region.dataset.state = STATE.POPULATED;
    region.className = "section-tight";
    region.innerHTML = `
      <p><a href="hire-manage.html">← All your jobs</a></p>
      <article class="card">
        <div class="state-head">${statusBadge(job.status)}<h2>${escapeText(job.title)}</h2></div>
        <p class="small muted">${escapeText(job.edition)} · ${escapeText(job.minecraftVersion)} · ${budgetLine(job.budget)} · ${job.deadline ? `by ${escapeText(job.deadline)}` : "no deadline"}</p>
        <p>${escapeText(job.description)}</p>
        <p><strong>Scope:</strong> ${escapeText(job.scope)}</p>
        <p class="small muted">Posted ${escapeText(formatTimestamp(job.createdAt))}${job.awardedAt ? ` · awarded ${escapeText(formatTimestamp(job.awardedAt))}` : ""}</p>
        ${editable ? '<h3>Edit this job</h3>' : "<p class=\"small muted\">Closed jobs are read-only: awarding and cancelling are final.</p>"}
        ${editable ? `<form class="stack" data-edit-job-form novalidate>
          <div class="field"><label for="edit-title">Title</label><input class="input" id="edit-title" name="title" value="${escapeAttribute(job.title)}" required></div>
          <div class="field"><label for="edit-description">Description</label><textarea class="input" id="edit-description" name="description" rows="4" required>${escapeText(job.description)}</textarea></div>
          <div class="field"><label for="edit-scope">Scope</label><textarea class="input" id="edit-scope" name="scope" rows="3" required>${escapeText(job.scope)}</textarea></div>
          <div class="row" style="gap:12px">
            <button type="submit" class="button button-secondary button-small">Save changes</button>
            <button type="button" class="button button-secondary button-small" data-cancel-job ${counts.submitted ? "" : ""}>Cancel this job</button>
          </div>
          <p class="field-hint" data-edit-status role="status" aria-live="polite"></p>
        </form>` : ""}
        <h3>Proposals (${escapeText(counts.submitted ?? 0)} awaiting review)</h3>
        <div data-proposal-list></div>
      </article>`;
    const list = region.querySelector("[data-proposal-list]");
    renderProposalReview(list, job, proposals, counts);

    const editForm = region.querySelector("[data-edit-job-form]");
    editForm?.addEventListener("submit", async (event) => {
      event.preventDefault();
      const editStatus = editForm.querySelector("[data-edit-status]");
      const value = (name) => (editForm.elements.namedItem(name)?.value ?? "").trim();
      const body = { title: value("title"), description: value("description"), scope: value("scope") };
      if (body.title.length < 3 || body.title.length > 120) { editStatus.textContent = "The title must be 3–120 characters."; editStatus.dataset.tone = "error"; return; }
      if (body.description.length < 10 || body.description.length > 5000) { editStatus.textContent = "The description must be 10–5000 characters."; editStatus.dataset.tone = "error"; return; }
      if (body.scope.length < 5 || body.scope.length > 1000) { editStatus.textContent = "The scope must be 5–1000 characters."; editStatus.dataset.tone = "error"; return; }
      editStatus.textContent = "Saving…";
      editStatus.dataset.tone = "";
      const saved = await adapters.hire.updateJob(job.id, body);
      if (saved.status === RESULT.OK) {
        announce("Job updated.");
        renderDetail(job.id);
        return;
      }
      editStatus.dataset.tone = "error";
      editStatus.textContent = saved.message ?? "The changes could not be saved.";
    });
    region.querySelector("[data-cancel-job]")?.addEventListener("click", async () => {
      if (!globalThis.confirm(`Cancel “${job.title}”? This closes the job and its open proposals. This cannot be undone.`)) return;
      const cancelled = await adapters.hire.cancelJob(job.id);
      if (cancelled.status === RESULT.OK) {
        announce("Job cancelled.");
        renderDetail(job.id);
        return;
      }
      if (statusLine) { statusLine.textContent = cancelled.message ?? "The job could not be cancelled."; statusLine.dataset.tone = "error"; }
    });
  }

  function renderProposalReview(list, job, proposals, counts) {
    if (!list) return;
    if (job.status !== "OPEN") {
      const winner = proposals.find((proposal) => proposal.status === "SELECTED");
      list.innerHTML = `<p class="small muted">${winner
        ? `Awarded to ${escapeText(winner.creator?.handle ?? "a builder")} on ${escapeText(formatTimestamp(job.awardedAt))}.`
        : "The job is closed, so its proposals are closed too."}</p>`;
      return;
    }
    if (proposals.length === 0) {
      list.innerHTML = '<p class="small muted">No proposals yet. Creators respond to open jobs from the directory.</p>';
      return;
    }
    list.innerHTML = `<div class="card-grid">${proposals.map((proposal) => `
      <article class="card">
        <div class="state-head">${statusBadge(proposal.status)}<h4>${escapeText(proposal.creator?.handle ?? "creator")}</h4></div>
        <p class="small muted">${escapeText(proposal.creator?.displayName ?? "")} · ${budgetLine(proposal.budget)}${proposal.deliveryEstimateDays ? ` · ${escapeText(proposal.deliveryEstimateDays)} days` : ""}</p>
        <p>${escapeText(proposal.message)}</p>
        <p class="small"><strong>Scope:</strong> ${escapeText(proposal.scope)}</p>
        ${proposal.status === "SUBMITTED" ? `<button type="button" class="button button-small" data-award="${escapeAttribute(proposal.id)}">Award this job to ${escapeText(proposal.creator?.handle ?? "this builder")}</button>` : ""}
      </article>`).join("")}</div>`;
    for (const button of list.querySelectorAll("[data-award]")) {
      button.addEventListener("click", async () => {
        if (!globalThis.confirm("Award this job to the selected builder? The decision is final and the other proposals will be closed.")) return;
        button.disabled = true;
        const awarded = await adapters.hire.awardProposal(job.id, button.dataset.award);
        if (awarded.status === RESULT.OK) {
          announce("Job awarded.");
          renderDetail(job.id);
          return;
        }
        button.disabled = false;
        if (statusLine) { statusLine.textContent = awarded.message ?? "The job could not be awarded."; statusLine.dataset.tone = "error"; }
      });
    }
    if (counts) { /* counts are rendered by the detail header; kept for clarity */ }
  }

  if (jobId && JOB_ID_PATTERN.test(jobId)) renderDetail(jobId);
  else if (jobId) renderState(region, { kind: STATE.ERROR, title: "That is not a job address", message: "Open one of your jobs from the list.", action: { label: "Back to your jobs", href: "hire-manage.html" } });
  else renderList();
}

/* ---------------------------------------------------------- creator proposals (hire-proposals.html) */

function initHireProposals(root) {
  const adapters = createAdapters();
  const region = root.querySelector("[data-proposals-region]");
  const statusLine = root.querySelector("[data-proposals-status]");

  if (!adapters.hire.configured) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No account service is connected to this site",
      message: "Your proposals live on the account service, which is not connected to this site.",
    });
    return;
  }
  if (!adapters.hire.signedIn) {
    renderState(region, {
      kind: STATE.UNAUTHORIZED,
      title: "Sign in to see your proposals",
      message: "Proposals are read and changed only through your own session.",
      action: { label: "Sign in", href: "../signin.html" },
    });
    return;
  }

  async function render() {
    renderLoading(region, { rows: 4, title: "Loading your proposals" });
    const result = await adapters.hire.listMyProposals();
    if (result.status !== RESULT.OK) {
      renderFailure(region, result, { title: "Your proposals could not be loaded", hint: "The hire service did not answer." });
      return;
    }
    const payload = result.payload ?? {};
    const proposals = Array.isArray(payload.proposals) ? payload.proposals : [];
    if (statusLine) {
      statusLine.textContent = `${payload.counts?.total ?? proposals.length} proposal(s): ${payload.counts?.submitted ?? 0} active, ${payload.counts?.selected ?? 0} selected.`;
    }
    if (proposals.length === 0) {
      renderState(region, {
        kind: STATE.EMPTY,
        title: "No proposals yet",
        message: "Find an open job that fits your skills and send a proposal from its page.",
        action: { label: "Browse open jobs", href: "hire.html" },
      });
      return;
    }
    region.dataset.state = STATE.POPULATED;
    region.className = "card-grid";
    region.innerHTML = proposals.map((proposal) => {
      const canManage = proposal.status === "SUBMITTED" && proposal.job?.status === "OPEN";
      return `<article class="card">
        <div class="state-head">${statusBadge(proposal.status)}<h3><a href="job.html?job=${encodeURIComponent(proposal.job.id)}">${escapeText(proposal.job.title)}</a></h3></div>
        <p class="small muted">Job is ${escapeText(proposal.job.status)} · ${budgetLine(proposal.budget)}${proposal.deliveryEstimateDays ? ` · ${escapeText(proposal.deliveryEstimateDays)} days` : ""}</p>
        <p>${escapeText(proposal.message)}</p>
        <p class="small"><strong>Scope:</strong> ${escapeText(proposal.scope)}</p>
        <p class="small muted">Sent ${escapeText(formatTimestamp(proposal.createdAt))} · updated ${escapeText(formatTimestamp(proposal.updatedAt))}</p>
        ${canManage ? `<div class="row" style="gap:12px">
          <button type="button" class="button button-secondary button-small" data-withdraw="${escapeAttribute(proposal.id)}">Withdraw</button>
          <button type="button" class="button button-secondary button-small" data-edit-proposal="${escapeAttribute(proposal.id)}">Edit</button>
        </div>
        <form class="stack" data-edit-proposal-form="${escapeAttribute(proposal.id)}" hidden novalidate>
          <div class="field"><label for="edit-message-${escapeAttribute(proposal.id)}">Message</label><textarea class="input" id="edit-message-${escapeAttribute(proposal.id)}" name="message" rows="3" required>${escapeText(proposal.message)}</textarea></div>
          <div class="field"><label for="edit-scope-${escapeAttribute(proposal.id)}">Scope</label><textarea class="input" id="edit-scope-${escapeAttribute(proposal.id)}" name="scope" rows="3" required>${escapeText(proposal.scope)}</textarea></div>
          <div class="row" style="gap:12px">
            <button type="submit" class="button button-small">Save proposal</button>
            <button type="button" class="button button-secondary button-small" data-cancel-edit>Cancel</button>
          </div>
          <p class="field-hint" data-edit-proposal-status role="status" aria-live="polite"></p>
        </form>` : ""}
      </article>`;
    }).join("");

    for (const button of region.querySelectorAll("[data-withdraw]")) {
      button.addEventListener("click", async () => {
        if (!globalThis.confirm("Withdraw this proposal? You can send a fresh one while the job stays open.")) return;
        button.disabled = true;
        const withdrawn = await adapters.hire.withdrawProposal(button.dataset.withdraw);
        if (withdrawn.status === RESULT.OK) { announce("Proposal withdrawn."); render(); return; }
        button.disabled = false;
        if (statusLine) { statusLine.textContent = withdrawn.message ?? "The proposal could not be withdrawn."; statusLine.dataset.tone = "error"; }
      });
    }
    for (const button of region.querySelectorAll("[data-edit-proposal]")) {
      button.addEventListener("click", () => {
        const form = region.querySelector(`[data-edit-proposal-form="${CSS.escape(button.dataset.editProposal)}"]`);
        if (form) { form.hidden = false; button.hidden = true; }
      });
    }
    for (const form of region.querySelectorAll("[data-edit-proposal-form]")) {
      const proposalId = form.dataset.editProposalForm;
      form.querySelector("[data-cancel-edit]")?.addEventListener("click", () => {
        form.hidden = true;
        region.querySelector(`[data-edit-proposal="${CSS.escape(proposalId)}"]`)?.removeAttribute("hidden");
      });
      form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const editStatus = form.querySelector("[data-edit-proposal-status]");
        const message = (form.elements.namedItem("message")?.value ?? "").trim();
        const scope = (form.elements.namedItem("scope")?.value ?? "").trim();
        const validation = validateProposalFields({ message, scope, budgetMinRaw: "", budgetMaxRaw: "", budgetCurrency: "", deliveryRaw: "" });
        if (validation) { editStatus.textContent = validation; editStatus.dataset.tone = "error"; return; }
        editStatus.textContent = "Saving…";
        editStatus.dataset.tone = "";
        const saved = await adapters.hire.updateProposal(proposalId, { message, scope });
        if (saved.status === RESULT.OK) { announce("Proposal updated."); render(); return; }
        editStatus.dataset.tone = "error";
        editStatus.textContent = saved.message ?? "The proposal could not be updated.";
      });
    }
  }

  render();
}

export {
  initHireDirectory,
  initHireJob,
  initHirePost,
  initHireManage,
  initHireProposals,
};
