import { readFileSync } from "fs";
import { resolve } from "path";
import { describe, it, expect } from "vitest";

// hearth/web is at the repo root, two levels up from android_tor_spike/app.
const web = (f: string) => readFileSync(resolve(__dirname, "../../../hearth/web", f), "utf8");

describe("vp1 read-only seam", () => {
  it("app.js toggles body.readonly from STATE.readonly", () => {
    const js = web("app.js");
    expect(js).toMatch(/classList\.toggle\(\s*["']readonly["']\s*,\s*!!STATE\.readonly\s*\)/);
  });

  it("style.css hides write affordances but keeps read chips", () => {
    const css = web("style.css");
    expect(css).toMatch(/body\.readonly/);
    // the composers, comment input, reaction opener, delete, and story-add are hidden
    expect(css).toMatch(/body\.readonly\s+\.composer/);
    expect(css).toMatch(/body\.readonly\s+\.comment-composer/);
    expect(css).toMatch(/body\.readonly\s+\.rx-open/);
    // the read-only count chips are NOT hidden by a body.readonly rule
    expect(css).not.toMatch(/body\.readonly\s+\.rx-count-chip\s*\{[^}]*display\s*:\s*none/);
  });
});
