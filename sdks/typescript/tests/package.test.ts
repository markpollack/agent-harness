import { describe, expect, it } from "vitest";

import { VERSION } from "../src/index.js";

describe("package scaffold", () => {
  it("exports a version", () => {
    expect(VERSION).toBeTruthy();
  });
});
