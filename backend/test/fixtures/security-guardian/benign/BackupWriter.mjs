import { spawnSync } from "node:child_process";
import { writeFileSync } from "node:fs";

// An argument array with no shell, and a 0600 mode on a file that holds material.
export function snapshot(target, contents) {
  writeFileSync(target, contents, { mode: 0o600 });
  return spawnSync("git", ["rev-parse", "--short", "HEAD"], { encoding: "utf8" }).stdout?.trim() ?? null;
}
