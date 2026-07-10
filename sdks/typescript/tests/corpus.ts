/** Shared corpus access: the Conformance Kit at the repo root (DD-9 — read directly). */

import { readdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
// tests/ -> typescript/ -> sdks/ -> repo root
export const SPEC_DIR = resolve(here, "..", "..", "..", "spec");

const validDir = join(SPEC_DIR, "fixtures", "valid");
const invalidDir = join(SPEC_DIR, "fixtures", "invalid");

export const VALID_FIXTURES = readdirSync(validDir)
  .filter((f) => f.endsWith(".json"))
  .sort()
  .map((f) => ({ name: f.replace(/\.json$/, ""), path: join(validDir, f) }));

export const INVALID_FIXTURES = readdirSync(invalidDir)
  .filter((f) => f.endsWith(".json") && !f.endsWith(".expected.json"))
  .sort()
  .map((f) => ({ name: f.replace(/\.json$/, ""), path: join(invalidDir, f) }));

export function readFixture(path: string): string {
  return readFileSync(path, "utf-8");
}

export function readExpected(fixturePath: string): {
  errorCodes?: string[];
  anyOfCodes?: string[];
} {
  const sidecar = fixturePath.replace(/\.json$/, ".expected.json");
  return JSON.parse(readFileSync(sidecar, "utf-8"));
}

export function readSchema(): string {
  return readFileSync(join(SPEC_DIR, "workflow-v2alpha.schema.json"), "utf-8");
}
