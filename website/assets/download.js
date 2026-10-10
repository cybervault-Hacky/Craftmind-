/**
 * The download page's live release check.
 *
 * `website/download.html` answers exactly one question — is there an APK you may install right now? The page ships a
 * disabled control and a stated reason, and `scripts/check_website.py` keeps it that way. This module's whole job is
 * to replace that static answer with the live one **only when the repository's own release metadata describes an
 * Android package**: on a published asset it renders the file, its size, its published time, the SHA-256 GitHub
 * publishes with it, and a link to the release page; on every other answer — no release, a release without an APK, a
 * rate limit, an unreachable service, a page that names no repository — it leaves the control disabled and says why.
 *
 * Three properties the rest of this file exists to keep:
 *
 *   * **Nothing is enabled by failure.** The unavailable path is both the default and the reset path: `markUnavailable()`
 *     re-disables the control and empties the live region, so no sequence of responses can leave a stale or
 *     speculative link on the page;
 *   * **Nothing is trusted past the adapter's check.** Every value rendered here already passed the pinned-origin and
 *     asset validation in `adapters.js`, and every one is written with `textContent` — no markup from a response
 *     reaches the page as HTML, and this module performs no request of its own;
 *   * **Nothing new is styled.** The live region reuses the `.release-facts` structure the page already renders, so a
 *     published release looks like the page, not like a banner bolted onto it.
 *
 * The check runs once per page view and is not cached: the site keeps nothing in browser storage (a hard
 * invariant), so the cost is one unauthenticated metadata request per visit. If traffic ever made that a rate-limit
 * problem, the fix would be for the release workflow to write a small `release.json` beside the site — not for this
 * page to start guessing.
 */

import { createReleaseAdapter, RESULT } from "./adapters.js";
import { announce, formatTimestamp, orDash } from "./state.js";

function describeSize(sizeBytes) {
  if (!Number.isFinite(sizeBytes) || sizeBytes < 0) return "—";
  return `${Number(sizeBytes).toLocaleString()} bytes (about ${(sizeBytes / 1_000_000).toFixed(1)} MB)`;
}

/** Mirrors the `.release-facts` structure the static page already uses: a `<b>` label and its value, per row. */
function factRow([label, value]) {
  const row = document.createElement("span");
  const name = document.createElement("b");
  name.textContent = label;
  row.append(name);
  row.append(document.createTextNode(` ${orDash(value)}`));
  return row;
}

/**
 * Renders a verified asset. The control is replaced rather than re-labelled: a disabled `<button>` is the page's
 * honest default, and only a link whose destination the release service actually returned can take its place.
 */
function releasePageLink(repository, label = "Release notes and checksum on GitHub ↗") {
  const releasePage = document.createElement("a");
  releasePage.className = "text-link";
  releasePage.href = `https://github.com/${repository}/releases/latest`;
  releasePage.rel = "noopener noreferrer";
  releasePage.textContent = label;
  return releasePage;
}

/**
 * A release that carries more than one package is not this page's choice to make: picking the first asset is how a
 * debug build gets offered as "the release". So the names are listed, no download is enabled, and the release page
 * is where the visitor goes to choose.
 */
function renderAmbiguous({ button, status, region, repository }, release) {
  const caution = document.createElement("p");
  caution.className = "small muted";
  caution.textContent =
    `The newest published release carries ${release.packages.length} Android packages, so this page does not pick one for you. `
    + "Open the release, read which file is which, and install the one that is actually the release build.";

  const facts = document.createElement("div");
  facts.className = "release-facts";
  facts.setAttribute("aria-label", "Packages in the published release");
  for (const item of release.packages) {
    facts.append(factRow(["PACKAGE", `${item.fileName} (${describeSize(item.sizeBytes)})`]));
  }

  region.replaceChildren(caution, releasePageLink(repository), facts);
  region.hidden = false;
  button.hidden = false;
  button.disabled = true;
  status.textContent = "Not available from this page — the release publishes more than one package, so choose it on the release page.";
  announce("That release offers several packages, so no single download is presented here.");
}

function renderArtifact({ button, status, region, repository }, artifact) {
  const link = document.createElement("a");
  link.className = "button";
  link.href = artifact.url;
  link.rel = "noopener noreferrer";
  link.textContent = `DOWNLOAD ${artifact.fileName}`;

  const caution = document.createElement("p");
  caution.className = "small muted";
  caution.textContent =
    "This file is the asset attached to the newest published CraftMind release. Compare its SHA-256 with the value "
    + "GitHub shows on the release page before installing it, and install only the file you fetched from this page.";

  const facts = document.createElement("div");
  facts.className = "release-facts";
  facts.setAttribute("aria-label", "Published release details");
  for (const row of [
    ["TAG", artifact.tag],
    ["FILE", artifact.fileName],
    ["SIZE", describeSize(artifact.sizeBytes)],
    ["PUBLISHED", artifact.publishedAt ? formatTimestamp(artifact.publishedAt) : "—"],
    ["SHA-256", artifact.sha256],
  ]) {
    facts.append(factRow(row));
  }

  region.replaceChildren(link, caution, releasePageLink(repository), facts);
  region.hidden = false;
  button.hidden = true;
  status.textContent = "A published release is available — verify the checksum before you install it.";
  announce(`CraftMind ${artifact.tag || "release"} is available to download.`);
}

/** The default and the reset path: the static, honest answer, with the control disabled again. */
function markUnavailable({ button, status, region }, message) {
  region.hidden = true;
  region.replaceChildren();
  button.hidden = false;
  button.disabled = true;
  status.textContent = message;
}

export function initDownload(root = document) {
  const channel = root.querySelector("[data-release-channel]");
  const button = root.querySelector("[data-release-cta]");
  const status = root.querySelector("[data-release-status-text]");
  const region = root.querySelector("[data-release-region]");
  // Without these hooks there is nothing safe to update, and the static markup is already the correct answer for a
  // site with nothing published — so the page is left exactly as it was written.
  if (!channel || !button || !status || !region) return;

  const context = { button, status, region, repository: channel.dataset.releaseRepository ?? "" };
  const adapter = createReleaseAdapter({ repository: context.repository });
  if (!adapter.configured) {
    markUnavailable(context, "Not checked — this page does not name a source repository to ask.");
    return;
  }

  status.textContent = "Checking the CraftMind release channel…";
  adapter.latestArtifact().then((result) => {
    if (result.status === RESULT.OK && result.payload) {
      if (result.payload.ambiguous) renderAmbiguous(context, result.payload);
      else renderArtifact(context, result.payload);
      return;
    }
    if (result.status === RESULT.EMPTY) {
      markUnavailable(context, result.message ?? "No release has been verified just now, so there is nothing to download.");
      return;
    }
    markUnavailable(context, result.message ?? "The release channel could not be checked, so there is nothing to download.");
  });
}
