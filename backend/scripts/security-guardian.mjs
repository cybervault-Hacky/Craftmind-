#!/usr/bin/env node
/**
 * Security Guardian — deterministic source analysis for the CraftMind repository.
 *
 *   node --no-warnings=ExperimentalWarning scripts/security-guardian.mjs [options]
 *
 * It reads this repository's source and configuration and prints findings backed by the quoted line that produced
 * them. It never executes what it reads, never opens a network connection, and never prints a secret value: a matched
 * credential is reported as a length. Rules and their limits live in `src/security-guardian.js`.
 *
 * Exit codes, because a scan that found nothing and a scan that could not run must not look the same to a script:
 *   0  no open finding at or above --fail-on, and coverage was complete
 *   1  an open finding at or above --fail-on
 *   2  the scan did not run (bad argument, unreadable root)
 *   3  the scan stopped early (cancelled, or a size/time/count bound was hit) — "nothing found" here means "not checked"
 *
 * Reports are sanitized but they do name files and quote code, so `--output` writes 0600 and refuses to place a
 * report inside a served website directory — the same rule `backup-database.mjs` follows.
 */

import { mkdirSync, writeFileSync } from "node:fs";
import { basename, dirname, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

import {
  DEFAULT_LIMITS, SEVERITIES, SecurityGuardianError, listRules, renderJsonReport, renderMarkdownReport, scanRepository,
  validateScanReport,
} from "../src/security-guardian.js";

const REPOSITORY_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");

const HELP = `usage: node scripts/security-guardian.mjs [options]

  --root PATH           repository to scan (default: ${relative(process.cwd(), REPOSITORY_ROOT) || "."})
  --paths P...          restrict the scan to these repository-relative paths (repeatable)
  --rule ID             run only this rule (repeatable; --list-rules shows the identifiers)
  --list-rules          print the rule table and exit
  --format F            markdown (default) or json
  --output FILE         write the report instead of printing it (0600, never inside a served site directory)
  --fail-on SEVERITY    exit 1 at this severity or above (default HIGH; use INFORMATIONAL to gate on everything)
  --max-findings N      stop after N findings (default ${DEFAULT_LIMITS.maximumFindings})
  --max-file-bytes N      skip files larger than N (default ${DEFAULT_LIMITS.maximumFileBytes})
  --max-seconds N       stop after N seconds (default ${DEFAULT_LIMITS.maximumSeconds})
  --suppressions PATH   JSON file of reviewed decisions (default: security-guardian.json in the root, if present)
  --no-suppressions     ignore any suppression file
  --progress            print a line per 100 files as the scan runs
  --ai-review           explain the AI stage (this command never contacts a provider)
  --help, -h            this text`;

function parseArguments(argv) {
  const options = {
    root: REPOSITORY_ROOT, paths: [], ruleIds: null, format: "markdown", output: null, failOn: "HIGH",
    limits: {}, suppressionsPath: null, useSuppressions: true, listRules: false, aiReview: false, progress: false, help: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const next = () => {
      const value = argv[index + 1];
      if (typeof value !== "string" || value.startsWith("--")) throw new SecurityGuardianError("ARGUMENT_INVALID", `${argument} requires a value`);
      index += 1;
      return value;
    };
    switch (argument) {
      case "--help": case "-h": options.help = true; break;
      case "--list-rules": options.listRules = true; break;
      case "--ai-review": options.aiReview = true; break;
      case "--progress": options.progress = true; break;
      case "--no-suppressions": options.useSuppressions = false; break;
      case "--root": options.root = resolve(next()); break;
      case "--paths": options.paths.push(next()); break;
      case "--rule": (options.ruleIds ??= []).push(next()); break;
      case "--format": options.format = next(); break;
      case "--output": options.output = resolve(next()); break;
      case "--fail-on": options.failOn = next().toUpperCase(); break;
      case "--suppressions": options.suppressionsPath = resolve(next()); break;
      case "--max-findings": options.limits.maximumFindings = Number.parseInt(next(), 10); break;
      case "--max-file-bytes": options.limits.maximumFileBytes = Number.parseInt(next(), 10); break;
      case "--max-seconds": options.limits.maximumSeconds = Number.parseInt(next(), 10); break;
      default: throw new SecurityGuardianError("ARGUMENT_INVALID", `unknown option ${argument}`);
    }
  }
  if (!SEVERITIES.includes(options.failOn)) throw new SecurityGuardianError("ARGUMENT_INVALID", `--fail-on must be one of ${SEVERITIES.join(", ")}`);
  if (!["markdown", "json"].includes(options.format)) throw new SecurityGuardianError("ARGUMENT_INVALID", "--format must be markdown or json");
  for (const [key, value] of Object.entries(options.limits)) {
    if (!Number.isInteger(value) || value < 1) throw new SecurityGuardianError("ARGUMENT_INVALID", `${key} is not a positive integer`);
  }
  return options;
}

function printRules() {
  for (const rule of listRules()) {
    process.stdout.write(`${rule.id}  [${rule.severity} / ${rule.confidence} confidence]  ${rule.title}\n`);
    process.stdout.write(`    ${rule.rationale}\n`);
    process.stdout.write(`    fix: ${rule.remediation}\n`);
    process.stdout.write(`    test: ${rule.regressionTestSuggestion}\n`);
    if (rule.scope) process.stdout.write(`    scope: ${rule.scope.join(", ")}\n`);
  }
  return 0;
}

/** The report names files and quotes code; keep it out of anything a web server would serve. */
function assertSafeOutputPath(outputPath, repositoryRoot) {
  const inside = (candidate) => candidate === repositoryRoot + sep || candidate.startsWith(repositoryRoot + sep);
  if (!inside(outputPath) && !inside(dirname(outputPath))) return;
  const relativePath = relative(repositoryRoot, dirname(outputPath)).split(sep).join("/");
  if (relativePath === "website" || relativePath.startsWith("website/")) {
    throw new SecurityGuardianError("OUTPUT_REFUSED", "a report must not be written inside website/, which the site serves");
  }
}

async function loadSuppressions(options) {
  if (!options.useSuppressions) return [];
  const path = options.suppressionsPath ?? resolve(options.root, "security-guardian.json");
  const { readFile } = await import("node:fs/promises");
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    if (options.suppressionsPath) throw new SecurityGuardianError("SUPPRESSIONS_UNREADABLE", `could not read ${basename(path)}: ${error.message}`);
    return [];
  }
}

async function main(argv) {
  let options;
  try {
    options = parseArguments(argv);
  } catch (error) {
    process.stderr.write(`security-guardian: ${error.message}\n\n${HELP}\n`);
    return 2;
  }
  if (options.help) { process.stdout.write(HELP + "\n"); return 0; }
  if (options.listRules) return printRules();
  if (options.aiReview) {
    process.stdout.write(
      "Security Guardian never sends anything to a provider from this command: no hidden network call is allowed to\n"
      + "ride along with a scan.\n\n"
      + "AI review exists as `reviewFindingsWithAi({ provider, report, consent: true })` in src/security-guardian.js, for a\n"
      + "deployment that already runs the developer-AI provider. It reuses that provider seam and its message-only\n"
      + "contract: the model sees only the already-masked evidence (rule, severity, path, line, excerpt) for at most 24\n"
      + "findings, gets no tool schema, and a reply that proposes a tool call is refused. Its output is returned beside\n"
      + "the findings, never merged into them, and it cannot change a severity, a status, or whether a finding exists.\n"
    );
    return 0;
  }

  let interrupted = false;
  const onInterrupt = () => { interrupted = true; };
  process.on("SIGINT", onInterrupt);

  let suppressions;
  try {
    suppressions = await loadSuppressions(options);
  } catch (error) {
    process.stderr.write(`security-guardian: ${error.message}\n`);
    return 2;
  }

  const started = Date.now();
  let report;
  try {
    report = await scanRepository({
      root: options.root,
      paths: options.paths,
      ruleIds: options.ruleIds,
      limits: options.limits,
      suppressions,
      startedAt: started,
      shouldContinue: () => !interrupted,
    });
    validateScanReport(report);
  } catch (error) {
    process.stderr.write(`security-guardian: ${error instanceof SecurityGuardianError ? `${error.code}: ${error.message}` : error.message}\n`);
    return 2;
  }

  if (options.progress) process.stderr.write(`scanned ${report.coverage.filesScanned} of ${report.coverage.filesConsidered} files in ${((Date.now() - started) / 1000).toFixed(1)}s\n`);

  const body = options.format === "json" ? renderJsonReport(report) : renderMarkdownReport(report);
  if (options.output) {
    try {
      assertSafeOutputPath(options.output, resolve(options.root));
      mkdirSync(dirname(options.output), { recursive: true });
      writeFileSync(options.output, body + "\n", { mode: 0o600, flag: "w" });
    } catch (error) {
      process.stderr.write(`security-guardian: ${error.message}\n`);
      return 2;
    }
  } else {
    process.stdout.write(body + "\n");
  }

  // SEVERITIES runs most severe first, so "at or above the gate" is index <= gate index. Reading it the other way
  // makes the gate both noisy and blind: a CRITICAL-only run would fail on informational findings, and a MEDIUM
  // gate would let a HIGH through.
  const blocked = report.findings.filter((finding) => finding.status === "OPEN"
    && SEVERITIES.indexOf(finding.severity) <= SEVERITIES.indexOf(options.failOn));
  const headline = `${report.status}: ${report.summary.open} open finding(s)`
    + (report.summary.total > report.summary.open ? ` (${report.summary.total - report.summary.open} accepted or suppressed)` : "")
    + `; ${report.coverage.filesScanned} file(s) read`
    + (report.coverage.limitsHit.length ? `; stopped early: ${report.coverage.limitsHit.join(", ")}` : "")
    + (interrupted ? "; cancelled by signal" : "");
  if (!options.output || blocked.length > 0) process.stderr.write(headline + "\n");
  // A finding at or above the gate outranks an unfinished scan, because the developer has to be told about the
  // exposure either way; `status: INCOMPLETE` and the `--fail-on` line both remain in the report.
  if (blocked.length > 0) return 1;
  // `status === "INCOMPLETE"` is the authoritative "this did not cover everything" signal, and it is wider than the
  // limits list: a file that was never read (too large, unreadable) belongs here even when no cap was hit.
  return interrupted || report.status === "INCOMPLETE" || report.coverage.limitsHit.length > 0 ? 3 : 0;
}

main(process.argv.slice(2)).then((code) => process.exitCode = code, (error) => {
  process.stderr.write(`security-guardian: unexpected failure: ${error?.stack ?? error}\n`);
  process.exitCode = 2;
});
