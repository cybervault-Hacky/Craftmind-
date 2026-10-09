/**
 * Hire a Builder controllers (Phases 26–27).
 *
 * Seven pages, one rule each: **real service data or an honest state — never an invented job, profile, count,
 * award, order, delivery, or approval.** The directory searches OPEN jobs through the adapter; the detail page proposes through it; the buyer
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
const ORDER_ID_PATTERN = /^ord_[0-9a-fA-F-]{36}$/;
/** The one honest sentence every order surface repeats: orders track agreed work, never money. */
const PAYMENT_NOTICE = "Payment processing, escrow, refunds, and payouts are not yet supported: an order tracks agreed work between two accounts, not money.";
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
    // Phase 27: order and milestone states ride the same three tones.
    ACTIVE: "current", COMPLETED: "planned",
    PENDING: "muted", IN_PROGRESS: "planned", REVISION_REQUESTED: "current", APPROVED: "planned",
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
        ${job.orderId ? `<p class="small"><a href="order.html?order=${encodeURIComponent(job.orderId)}">Open the order for this awarded job →</a></p>` : ""}
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
        ${proposal.orderId ? `<p class="small"><a href="order.html?order=${encodeURIComponent(proposal.orderId)}">View the order for this proposal →</a></p>` : ""}
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


/* ----------------------------------------------------------------- orders list (orders.html) */

/** One order card: real status, real milestone counts, and the role this account plays on it. */
function orderCardMarkup(order, role) {
  const counts = order.milestoneCounts ?? {};
  return `<article class="card listing-card">
    <span class="card-meta">${statusBadge(order.status)} <span class="badge badge--muted">${escapeText(role === "BUYER" ? "You are the buyer" : "You are the builder")}</span></span>
    <h3><a href="order.html?order=${encodeURIComponent(order.id)}">${escapeText(order.jobTitle)}</a></h3>
    <p class="small">${escapeText(counts.approved ?? 0)} of ${escapeText(counts.total ?? 0)} milestone(s) approved</p>
    <p class="small muted">${budgetLine(order.budget)} · ${escapeText(order.revisionLimit)} revision round(s) per milestone · created ${escapeText(formatTimestamp(order.createdAt))}</p>
  </article>`;
}

function initHireOrders(root) {
  const adapters = createAdapters();
  const region = root.querySelector("[data-orders-region]");
  const statusLine = root.querySelector("[data-orders-status]");

  if (!adapters.hire.configured) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No account service is connected to this site",
      message: "Your orders live on the account service, which is not connected to this site.",
      details: ["Connect the account service to load the orders you bought or are building."],
    });
    return;
  }
  if (!adapters.hire.signedIn) {
    renderState(region, {
      kind: STATE.UNAUTHORIZED,
      title: "Sign in to see your orders",
      message: "Orders are read only through your own session, as the buyer or as the builder.",
      action: { label: "Sign in", href: "../signin.html" },
    });
    return;
  }

  async function render() {
    renderLoading(region, { rows: 5, title: "Loading your orders" });
    const [asBuyer, asCreator] = await Promise.all([
      adapters.hire.listMyBuyerOrders(),
      adapters.hire.listMyCreatorOrders(),
    ]);
    const failed = [asBuyer, asCreator].find((result) => result.status !== RESULT.OK);
    if (failed) {
      renderFailure(region, failed, { title: "Your orders could not be loaded", hint: "The account service did not answer." });
      if (statusLine) statusLine.textContent = "The account service could not be reached.";
      return;
    }
    const buyerPayload = asBuyer.payload ?? {};
    const creatorPayload = asCreator.payload ?? {};
    const buyerOrders = Array.isArray(buyerPayload.items) ? buyerPayload.items : [];
    const creatorOrders = Array.isArray(creatorPayload.items) ? creatorPayload.items : [];
    const buyerTotal = Number(buyerPayload.total ?? buyerOrders.length);
    const creatorTotal = Number(creatorPayload.total ?? creatorOrders.length);
    if (buyerOrders.length === 0 && creatorOrders.length === 0) {
      renderState(region, {
        kind: STATE.EMPTY,
        title: "No orders yet",
        message: "An order appears here after a buyer converts an awarded proposal into one — you will see it as the buyer or as the builder.",
        action: { label: "Browse open jobs", href: "hire.html" },
      });
      if (statusLine) statusLine.textContent = "No orders on this account yet.";
      return;
    }
    const overflowNote = (total, shown) => total > shown
      ? `<p class="small muted">Showing the ${escapeText(shown)} most recent of ${escapeText(total)} — older orders stay available through the service.</p>`
      : "";
    region.dataset.state = STATE.POPULATED;
    region.className = "section-tight";
    region.innerHTML = `
      ${buyerOrders.length > 0 ? `<h3>Orders you bought (${escapeText(buyerTotal)})</h3>
      <div class="card-grid">${buyerOrders.map((order) => orderCardMarkup(order, "BUYER")).join("")}</div>
      ${overflowNote(buyerTotal, buyerOrders.length)}` : ""}
      ${creatorOrders.length > 0 ? `<h3>Orders you are building (${escapeText(creatorTotal)})</h3>
      <div class="card-grid">${creatorOrders.map((order) => orderCardMarkup(order, "CREATOR")).join("")}</div>
      ${overflowNote(creatorTotal, creatorOrders.length)}` : ""}
      ${buyerOrders.length === 0 ? '<p class="small muted">You have no orders as a buyer yet.</p>' : ""}
      ${creatorOrders.length === 0 ? '<p class="small muted">You have no orders as a builder yet.</p>' : ""}
      <p class="small muted">${escapeText(PAYMENT_NOTICE)}</p>`;
    if (statusLine) {
      statusLine.textContent = `${buyerTotal} order(s) as buyer · ${creatorTotal} as builder.`;
    }
    announce(`${buyerTotal + creatorTotal} order(s) on this account.`);
  }

  render();
}

/* ------------------------------------------------------------- order detail (order.html) */

function orderActionStatus(root) {
  return root.querySelector("[data-order-status]");
}

/** Sets a status line with an optional tone; error tones keep the service's own message verbatim. */
function setOrderStatus(line, message, tone = "") {
  if (!line) return;
  line.textContent = message;
  line.dataset.tone = tone;
}

/** Client-side mirror of the delivery-note rule: bounded text and no markup characters. */
function validateNote(note) {
  if (note.length < 1 || note.length > 2000) return "The delivery note must be 1–2000 characters.";
  if (/[<>]/.test(note)) return "The delivery note cannot contain angle brackets — describe the work in plain text.";
  return null;
}

function validateReason(reason) {
  if (reason.length < 5 || reason.length > 1000) return "The reason must be 5–1000 characters.";
  if (/[<>]/.test(reason)) return "The reason cannot contain angle brackets — describe the change in plain text.";
  return null;
}

/** Evidence references: one per line, https-only, host-bearing, credential-free, bounded — like the service. */
function validateEvidenceLines(lines) {
  if (lines.length > 4) return "At most four external links are allowed.";
  for (const line of lines) {
    if (line.length > 300) return "Each external link must be 300 characters or fewer.";
    let parsed;
    try { parsed = new URL(line); } catch { return `That is not a valid URL: ${line}`; }
    if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) {
      return "External links must be plain https URLs without credentials.";
    }
  }
  return null;
}

function initHireOrderDetail(root) {
  const adapters = createAdapters();
  const region = root.querySelector("[data-order-region]");
  const historyRegion = root.querySelector("[data-order-history]");
  const statusLine = orderActionStatus(root);
  const orderId = new URLSearchParams(globalThis.location.search).get("order") ?? "";

  if (!adapters.hire.configured) {
    renderState(region, {
      kind: STATE.UNAVAILABLE,
      title: "No account service is connected to this site",
      message: "Orders live on the account service, which is not connected to this site.",
      details: ["Connect the account service to read order terms, milestones, and delivery history."],
    });
    if (historyRegion) renderState(historyRegion, { kind: STATE.UNAVAILABLE, title: "No delivery history to show", message: "There is no account service to read deliveries from." });
    return;
  }
  if (!adapters.hire.signedIn) {
    renderState(region, {
      kind: STATE.UNAUTHORIZED,
      title: "Sign in to open this order",
      message: "Orders are read only through your own session, as the buyer or as the builder.",
      action: { label: "Sign in", href: "../signin.html" },
    });
    if (historyRegion) renderState(historyRegion, { kind: STATE.UNAVAILABLE, title: "No delivery history to show", message: "Sign in to read the delivery history of an order you are on." });
    return;
  }
  if (!ORDER_ID_PATTERN.test(orderId)) {
    renderState(region, {
      kind: STATE.ERROR,
      title: "That is not an order address",
      message: "Open one of your orders so its identifier can be read from the service.",
      action: { label: "Back to your orders", href: "orders.html" },
    });
    return;
  }

  let view = null; // { order, role, milestones } from the service
  let revisionsByMilestone = new Map();

  function revisionsUsed(milestoneId) {
    return revisionsByMilestone.get(milestoneId) ?? 0;
  }

  function milestoneActions(order, role, milestone) {
    const active = order.status === "ACTIVE";
    const parts = [];
    if (!active) {
      parts.push('<p class="small muted">This order is closed, so its milestones no longer change.</p>');
      return parts.join("");
    }
    if (role === "CREATOR") {
      if (milestone.status === "PENDING" || milestone.status === "REVISION_REQUESTED") {
        parts.push(`<button type="button" class="button button-small" data-start="${escapeAttribute(milestone.id)}">Start this milestone</button>`);
      }
      if (milestone.status === "IN_PROGRESS" || milestone.status === "REVISION_REQUESTED") {
        parts.push(`<form class="stack" data-deliver-form="${escapeAttribute(milestone.id)}" novalidate>
          <div class="field"><label for="note-${escapeAttribute(milestone.id)}">Delivery note</label>
          <textarea class="input" id="note-${escapeAttribute(milestone.id)}" name="note" rows="3" required placeholder="What you delivered, where it is, and how to verify it."></textarea></div>
          <div class="field"><label for="evidence-${escapeAttribute(milestone.id)}">External links (one per line, up to 4, https only)</label>
          <textarea class="input" id="evidence-${escapeAttribute(milestone.id)}" name="evidence" rows="2"></textarea>
          <p class="field-hint">CraftMind stores these links as text and does not visit, scan, or verify them.</p></div>
          <div class="field"><label for="compat-${escapeAttribute(milestone.id)}">Compatibility note (optional)</label>
          <input class="input" id="compat-${escapeAttribute(milestone.id)}" name="compatibilityNote" maxlength="500"></div>
          <button type="submit" class="button button-small">Submit delivery${milestone.latestDeliveryVersion > 0 ? " (new version)" : ""}</button>
          <p class="field-hint" data-deliver-status="${escapeAttribute(milestone.id)}" role="status" aria-live="polite"></p>
        </form>`);
      }
    }
    if (role === "BUYER" && milestone.status === "SUBMITTED") {
      const used = revisionsUsed(milestone.id);
      const limit = order.revisionLimit;
      parts.push(`<p class="small muted">${escapeText(used)} of ${escapeText(limit)} in-scope revision round(s) used on this milestone.</p>`);
      if (used < limit) {
        parts.push(`<form class="stack" data-revision-form="${escapeAttribute(milestone.id)}" novalidate>
          <div class="field"><label for="reason-${escapeAttribute(milestone.id)}">Revision reason</label>
          <textarea class="input" id="reason-${escapeAttribute(milestone.id)}" name="reason" rows="2" required placeholder="What needs to change, within the agreed scope."></textarea></div>
          <label class="row" style="gap:8px;align-items:center"><input type="checkbox" name="outsideScope" data-outside-scope> Outside the original scope (record as a scope change)</label>
          <p class="field-hint" data-scope-hint="${escapeAttribute(milestone.id)}" hidden>A scope change does not consume a revision round and does not change the agreed terms. No payment for extra work exists in this phase, so the request is recorded for the builder to read.</p>
          <div class="row" style="gap:12px">
            <button type="submit" class="button button-secondary button-small">Request revision</button>
            <button type="button" class="button" data-approve="${escapeAttribute(milestone.id)}">Approve milestone</button>
          </div>
          <p class="field-hint" data-revision-status="${escapeAttribute(milestone.id)}" role="status" aria-live="polite"></p>
        </form>`);
      } else {
        parts.push(`<p class="small muted">The agreed revision limit is reached. Further requests must be raised as a scope change.</p>`);
        parts.push(`<div class="row" style="gap:12px">
          <form class="stack" data-revision-form="${escapeAttribute(milestone.id)}" novalidate style="flex:1">
            <div class="field"><label for="reason-${escapeAttribute(milestone.id)}">Scope-change reason</label>
            <textarea class="input" id="reason-${escapeAttribute(milestone.id)}" name="reason" rows="2" required></textarea>
            <input type="hidden" name="outsideScope" value="forced" data-outside-scope></div>
            <button type="submit" class="button button-secondary button-small">Request scope change</button>
            <p class="field-hint" data-revision-status="${escapeAttribute(milestone.id)}" role="status" aria-live="polite"></p>
          </form>
          <button type="button" class="button" data-approve="${escapeAttribute(milestone.id)}">Approve milestone</button>
        </div>`);
        parts.push('<p class="small muted">A scope change does not change the agreed terms, and no payment for extra work exists in this phase.</p>');
      }
    }
    if (role === "BUYER" && milestone.status !== "SUBMITTED") {
      parts.push(`<p class="small muted">${milestone.status === "APPROVED"
        ? "Approved — this milestone never reopens."
        : "Approval becomes available once the builder submits a delivery."}</p>`);
    }
    if (role === "CREATOR" && milestone.status === "SUBMITTED") {
      parts.push('<p class="small muted">Waiting for the buyer to approve or request a revision.</p>');
    }
    if (role === "CREATOR" && milestone.status === "APPROVED") {
      parts.push('<p class="small muted">Approved by the buyer.</p>');
    }
    return parts.join("");
  }

  function renderDetail({ order, role, milestones }) {
    const counts = order.milestoneCounts ?? {};
    const allApproved = counts.total > 0 && counts.approved === counts.total;
    region.dataset.state = STATE.POPULATED;
    region.className = "section-tight";
    region.innerHTML = `
      <p><a href="orders.html">← Your orders</a></p>
      <article class="card">
        <div class="state-head">${statusBadge(order.status)}<h2>${escapeText(order.jobTitle)}</h2></div>
        <p class="small muted">
          <span class="badge badge--muted">${escapeText(role === "BUYER" ? "You are the buyer" : "You are the builder")}</span>
          <span class="badge badge--muted">${escapeText(order.edition)}</span>
          <span class="badge badge--muted">${escapeText(order.minecraftVersion)}</span>
          ${(order.loaders ?? []).map((loader) => `<span class="badge badge--muted">${escapeText(loader)}</span>`).join(" ")}
        </p>
        <p><strong>Agreed scope:</strong> ${escapeText(order.scope)}</p>
        <p class="small"><strong>Terms frozen at creation:</strong> ${budgetLine(order.budget)} · ${order.deadline ? `deadline ${escapeText(order.deadline)}` : "no deadline"} · ${order.deliveryEstimateDays ? `${escapeText(order.deliveryEstimateDays)}-day estimate` : "no delivery estimate"} · ${escapeText(order.revisionLimit)} revision round(s) per milestone · builder ${escapeText(order.creator?.handle ?? "unknown")}</p>
        <p class="small muted">Created ${escapeText(formatTimestamp(order.createdAt))} · ${escapeText(counts.approved ?? 0)} of ${escapeText(counts.total ?? 0)} milestone(s) approved</p>
        <p class="small muted">${escapeText(PAYMENT_NOTICE)} Order records are workflow records only — they are not proof that any payment happened.</p>
      </article>
      <h3>Milestones</h3>
      <div class="card-grid">
        ${milestones.map((milestone) => `<article class="card">
          <div class="state-head">${statusBadge(milestone.status)}<h4>${escapeText(milestone.position)}. ${escapeText(milestone.title)}</h4></div>
          <p>${escapeText(milestone.description)}</p>
          <p class="small"><strong>Acceptance criteria:</strong> ${escapeText(milestone.acceptanceCriteria)}</p>
          <p class="small muted">${milestone.amount !== null && milestone.amount !== undefined ? `Agreed amount: ${escapeText(milestone.amount)} ${escapeText(order.budget?.currency ?? "")}` : "No per-milestone amount (the order has no agreed budget to split)."}${milestone.latestDeliveryVersion > 0 ? ` · latest delivery: v${escapeText(milestone.latestDeliveryVersion)}` : ""}</p>
          <div data-milestone-actions="${escapeAttribute(milestone.id)}">${milestoneActions(order, role, milestone)}</div>
        </article>`).join("")}
      </div>
      <h3>Order actions</h3>
      <div class="row" style="gap:12px">
        ${order.status === "ACTIVE" && allApproved ? '<button type="button" class="button" data-complete>Complete this order</button>' : ""}
        ${order.status === "ACTIVE" && (counts.approved ?? 0) === 0 ? '<button type="button" class="button button-secondary" data-cancel>Cancel this order</button>' : ""}
      </div>
      ${order.status === "ACTIVE" && (counts.approved ?? 0) > 0 && !allApproved
        ? '<p class="small muted">This order has approved milestones, so neither side can cancel it any more. Closing an order with approved work belongs to a future dispute phase.</p>' : ""}
      ${order.status === "ACTIVE" && !allApproved && (counts.approved ?? 0) === 0
        ? '<p class="small muted">Either side can cancel while no milestone is approved. Cancelling freezes the workflow and never deletes history.</p>' : ""}
      ${order.status === "COMPLETED" ? '<p class="small muted">Every milestone was approved and the order is complete. Completion is a workflow record, not proof of payment.</p>' : ""}
      ${order.status === "CANCELLED" ? '<p class="small muted">This order was cancelled. Milestones, deliveries, and revision history remain readable — nothing was deleted.</p>' : ""}`;
    wireMilestoneActions(order, role, milestones);
    wireOrderActions(order);
  }

  function wireMilestoneActions(order, role, milestones) {
    for (const button of region.querySelectorAll("[data-start]")) {
      button.addEventListener("click", async () => {
        setOrderStatus(statusLine, "Starting the milestone…");
        button.disabled = true;
        const started = await adapters.hire.startOrderMilestone(order.id, button.dataset.start);
        if (started.status === RESULT.OK) {
          announce("Milestone started.");
          await load();
          return;
        }
        button.disabled = false;
        setOrderStatus(statusLine, started.message ?? "The milestone could not be started.", "error");
      });
    }
    for (const form of region.querySelectorAll("[data-deliver-form]")) {
      const milestoneId = form.dataset.deliverForm;
      form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const line = form.querySelector(`[data-deliver-status="${CSS.escape(milestoneId)}"]`);
        const note = (form.elements.namedItem("note")?.value ?? "").trim();
        const compatibilityNote = (form.elements.namedItem("compatibilityNote")?.value ?? "").trim();
        const evidenceLines = (form.elements.namedItem("evidence")?.value ?? "").split("\n").map((row) => row.trim()).filter(Boolean);
        const validation = validateNote(note) ?? validateEvidenceLines(evidenceLines);
        if (validation) { setOrderStatus(line, validation, "error"); return; }
        const submit = form.querySelector('button[type="submit"]');
        if (submit) submit.disabled = true;
        setOrderStatus(line, "Submitting the delivery…");
        const body = { note, evidenceReferences: evidenceLines };
        if (compatibilityNote) body.compatibilityNote = compatibilityNote;
        const result = await adapters.hire.submitOrderDelivery(order.id, milestoneId, body);
        if (result.status === RESULT.OK) {
          announce("Delivery submitted.");
          await load();
          return;
        }
        if (submit) submit.disabled = false;
        setOrderStatus(line, result.message ?? "The delivery could not be submitted.", "error");
      });
    }
    for (const form of region.querySelectorAll("[data-revision-form]")) {
      const milestoneId = form.dataset.revisionForm;
      const outside = form.querySelector("[data-outside-scope]");
      const hint = form.querySelector(`[data-scope-hint="${CSS.escape(milestoneId)}"]`);
      outside?.addEventListener("change", () => { if (hint && outside.type === "checkbox") hint.hidden = !outside.checked; });
      form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const line = form.querySelector(`[data-revision-status="${CSS.escape(milestoneId)}"]`);
        const reason = (form.elements.namedItem("reason")?.value ?? "").trim();
        const outsideField = form.querySelector("[data-outside-scope]");
        const outsideScope = outsideField?.type === "checkbox" ? outsideField.checked : outsideField?.value === "forced";
        const validation = validateReason(reason);
        if (validation) { setOrderStatus(line, validation, "error"); return; }
        const message = outsideScope
          ? "Record this as a scope change? It does not consume a revision round, does not change the agreed terms, and no payment for extra work exists in this phase."
          : `Send this milestone back for revision? One of the ${order.revisionLimit} agreed in-scope round(s) will be used.`;
        if (!globalThis.confirm(message)) return;
        const submit = form.querySelector('button[type="submit"]');
        if (submit) submit.disabled = true;
        const result = await adapters.hire.requestMilestoneRevision(order.id, milestoneId, { reason, ...(outsideScope ? { outsideScope: true } : {}) });
        if (result.status === RESULT.OK) {
          announce(outsideScope ? "Scope change recorded." : "Revision requested.");
          await load();
          return;
        }
        if (submit) submit.disabled = false;
        setOrderStatus(line, result.message ?? "The request could not be recorded.", "error");
      });
    }
    for (const button of region.querySelectorAll("[data-approve]")) {
      button.addEventListener("click", async () => {
        if (!globalThis.confirm("Approve this milestone? Approval is final: an approved milestone is never reopened, and only the buyer can approve.")) return;
        button.disabled = true;
        setOrderStatus(statusLine, "Approving the milestone…");
        const approved = await adapters.hire.approveOrderMilestone(order.id, button.dataset.approve);
        if (approved.status === RESULT.OK) {
          announce("Milestone approved.");
          await load();
          return;
        }
        button.disabled = false;
        setOrderStatus(statusLine, approved.message ?? "The milestone could not be approved.", "error");
      });
    }
    void milestones;
  }

  function wireOrderActions(order) {
    region.querySelector("[data-complete]")?.addEventListener("click", async (event) => {
      const button = event.currentTarget;
      if (!globalThis.confirm("Complete this order? Every milestone is already approved, and completion closes the order.")) return;
      button.disabled = true;
      setOrderStatus(statusLine, "Completing the order…");
      const completed = await adapters.hire.completeOrder(order.id);
      if (completed.status === RESULT.OK) {
        announce("Order completed.");
        await load();
        return;
      }
      button.disabled = false;
      setOrderStatus(statusLine, completed.message ?? "The order could not be completed.", "error");
    });
    region.querySelector("[data-cancel]")?.addEventListener("click", async (event) => {
      const button = event.currentTarget;
      if (!globalThis.confirm("Cancel this order? Nothing is deleted, but the order freezes: no further work, deliveries, or approvals can happen.")) return;
      button.disabled = true;
      setOrderStatus(statusLine, "Cancelling the order…");
      const cancelled = await adapters.hire.cancelOrder(order.id);
      if (cancelled.status === RESULT.OK) {
        announce("Order cancelled.");
        await load();
        return;
      }
      button.disabled = false;
      setOrderStatus(statusLine, cancelled.message ?? "The order could not be cancelled.", "error");
    });
  }

  function renderHistory(history) {
    const deliveries = Array.isArray(history.deliveries) ? history.deliveries : [];
    const revisions = Array.isArray(history.revisions) ? history.revisions : [];
    revisionsByMilestone = new Map();
    for (const revision of revisions) {
      if (revision.kind === "REVISION") {
        revisionsByMilestone.set(revision.milestoneId, (revisionsByMilestone.get(revision.milestoneId) ?? 0) + 1);
      }
    }
    if (!historyRegion) return;
    if (deliveries.length === 0 && revisions.length === 0) {
      renderState(historyRegion, {
        kind: STATE.EMPTY,
        title: "No deliveries yet",
        message: "Delivery versions and revision decisions appear here once the builder starts work.",
      });
      return;
    }
    historyRegion.dataset.state = STATE.POPULATED;
    historyRegion.className = "section-tight";
    const overflow = Number(history.totalDeliveries ?? deliveries.length) > deliveries.length
      ? `<p class="small muted">Showing the ${escapeText(deliveries.length)} most recent of ${escapeText(history.totalDeliveries)} deliveries.</p>`
      : "";
    historyRegion.innerHTML = `
      <h3>Delivery history (${escapeText(history.totalDeliveries ?? deliveries.length)})</h3>
      <div class="card-grid">
        ${deliveries.map((delivery) => {
          const links = Array.isArray(delivery.evidenceReferences) ? delivery.evidenceReferences : [];
          return `<article class="card">
            <div class="state-head"><span class="badge badge--current">v${escapeText(delivery.version)}</span><h4>Milestone delivery</h4></div>
            <p class="small muted">Submitted ${escapeText(formatTimestamp(delivery.submittedAt))} · milestone ${escapeText(delivery.milestoneId)}</p>
            <p>${escapeText(delivery.note)}</p>
            ${delivery.compatibilityNote ? `<p class="small muted">Compatibility: ${escapeText(delivery.compatibilityNote)}</p>` : ""}
            ${links.length > 0 ? `<ul class="state-list">${links.map((url) => `<li><a href="${escapeAttribute(url)}" rel="noopener noreferrer" target="_blank">${escapeText(url)}</a> <span class="small muted">External link — not verified by CraftMind</span></li>`).join("")}</ul>` : ""}
          </article>`;
        }).join("")}
      </div>
      ${overflow}
      <h3>Revision and scope history (${escapeText(revisions.length)})</h3>
      ${revisions.length === 0
        ? '<p class="small muted">No revision or scope-change requests have been recorded.</p>'
        : `<ul class="state-list">${revisions.map((revision) => `<li>
            <span class="badge badge--${revision.kind === "SCOPE_CHANGE" ? "planned" : "muted"}">${escapeText(revision.kind === "SCOPE_CHANGE" ? "SCOPE CHANGE" : "REVISION")}</span>
            <span class="small muted">${escapeText(formatTimestamp(revision.createdAt))} · milestone ${escapeText(revision.milestoneId)}</span>
            <p class="small">${escapeText(revision.reason)}</p>
          </li>`).join("")}</ul>`}
      <p class="small muted">Prior delivery versions are never replaced: a new submission adds a version and keeps every earlier one readable.</p>`;
  }

  async function load() {
    renderLoading(region, { rows: 6, title: "Loading this order" });
    if (historyRegion) renderLoading(historyRegion, { rows: 3, title: "Loading delivery history" });
    const [detail, history] = await Promise.all([
      adapters.hire.getOrderDetail(orderId),
      adapters.hire.getOrderHistory(orderId, { limit: "100" }),
    ]);
    if (detail.status !== RESULT.OK) {
      renderFailure(region, detail, { title: "This order is not available", hint: "Only your own orders are shown here." });
      if (historyRegion) renderState(historyRegion, { kind: STATE.UNAVAILABLE, title: "No delivery history to show", message: "The order could not be opened." });
      return;
    }
    view = {
      order: detail.payload?.order ?? {},
      role: detail.payload?.role ?? "BUYER",
      milestones: Array.isArray(detail.payload?.milestones) ? detail.payload.milestones : [],
    };
    if (history.status === RESULT.OK) renderHistory(history.payload ?? {});
    else if (historyRegion) renderState(historyRegion, { kind: STATE.ERROR, title: "Delivery history could not be loaded", message: history.message ?? "The account service did not answer." });
    renderDetail(view);
    announce(`Loaded order: ${view.order.jobTitle ?? "order"}`);
  }

  load();
}


export {
  initHireDirectory,
  initHireJob,
  initHirePost,
  initHireManage,
  initHireProposals,
  initHireOrders,
  initHireOrderDetail,
};
