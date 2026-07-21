import { readFileSync } from "fs";
import { resolve } from "path";
import { describe, it, expect } from "vitest";

// hearth/web is at the repo root, two levels up from android_tor_spike/app.
const web = (f: string) => readFileSync(resolve(__dirname, "../../../hearth/web", f), "utf8");

// Every write-affordance selector the read-only seam must hide (journal view
// + self-profile chrome doors). Kept in sync with the body.readonly block in
// hearth/web/style.css by hand - this list IS the contract.
const HIDDEN_SELECTORS = [
  ".composer",
  ".comment-composer",
  ".rx-open",
  ".rx-picker",
  ".pact.del",
  ".settings-del",
  ".story-tile .story-ring.add",
  ".comment-x",
  "#profile-cog",
  "#profile-arrange",
  "#profile-addfriend",
  "#dm-compose",            // vp2: the DM composer bar (Photo + textarea + Send)
];

// The subset where a dropped `display: none` (not just a dropped selector)
// is worth guarding against explicitly - the highest-traffic write doors.
const LOAD_BEARING_SELECTORS = [".composer", ".comment-composer", ".rx-open", ".comment-x", "#dm-compose"];

function escapeSelector(sel: string): string {
  // Space-separated compound selectors (e.g. ".story-tile .story-ring.add")
  // may be reflowed across whitespace/newlines in the stylesheet; match
  // each token literally but allow flexible whitespace between tokens.
  return sel
    .split(/\s+/)
    .map((part) => part.replace(/[.#]/g, "\\$&"))
    .join("\\s+");
}

function selectorPresentRegex(sel: string): RegExp {
  return new RegExp("body\\.readonly\\s+" + escapeSelector(sel));
}

function selectorHiddenRegex(sel: string): RegExp {
  // The selector must appear under body.readonly, and within a bounded
  // distance (still inside the same/adjacent rule) a `{ ... }` block
  // containing `display: none` must follow - catches both a selector
  // migrating out of the hide rule AND the `display: none` property being
  // dropped from the block it's in.
  return new RegExp(
    "body\\.readonly\\s+" + escapeSelector(sel) + "[\\s\\S]{0,600}?\\{[^{}]*display\\s*:\\s*none[^{}]*\\}"
  );
}

describe("vp1 read-only seam", () => {
  it("app.js toggles body.readonly from STATE.readonly", () => {
    const js = web("app.js");
    expect(js).toMatch(/classList\.toggle\(\s*["']readonly["']\s*,\s*!!STATE\.readonly\s*\)/);
  });

  it("style.css hides every known write affordance under body.readonly", () => {
    const css = web("style.css");
    expect(css).toMatch(/body\.readonly/);
    for (const sel of HIDDEN_SELECTORS) {
      expect(css, `expected body.readonly to hide "${sel}"`).toMatch(selectorPresentRegex(sel));
    }
  });

  it("load-bearing write affordances actually pair with display:none (not just selector text)", () => {
    const css = web("style.css");
    for (const sel of LOAD_BEARING_SELECTORS) {
      expect(css, `expected "${sel}" to resolve to a display:none rule under body.readonly`)
        .toMatch(selectorHiddenRegex(sel));
    }
  });

  it("the read-only count chips are never hidden by a body.readonly rule", () => {
    const css = web("style.css");
    expect(css).not.toMatch(/body\.readonly\s+\.rx-count-chip\s*\{[^}]*display\s*:\s*none/);
    // also guard against .rx-count-chip riding along in a shared selector
    // list that resolves to display:none anywhere under body.readonly
    expect(css).not.toMatch(selectorHiddenRegex(".rx-count-chip"));
  });

  it("index.html mobile tab bar has a Messages entry (vp2)", () => {
    const html = web("index.html");
    expect(html).toMatch(/<button[^>]*data-tab=["']messages["'][^>]*>/);
  });
});
