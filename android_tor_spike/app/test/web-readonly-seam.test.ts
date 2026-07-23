import { readFileSync } from "fs";
import { resolve } from "path";
import { describe, it, expect } from "vitest";

// hearth/web is at the repo root, two levels up from android_tor_spike/app.
const web = (f: string) => readFileSync(resolve(__dirname, "../../../hearth/web", f), "utf8");

// Every write-affordance selector the read-only seam must hide (journal view
// + self-profile chrome doors). Kept in sync with the body.readonly block in
// hearth/web/style.css by hand - this list IS the contract.
const HIDDEN_SELECTORS = [
  // NOTE: `.composer` (journal composer) is intentionally NOT here -- the first
  // outbound slice REVEALS it (see the "outbound-1" test below). outbound slice
  // TASK 7 REVEALS `.rx-open`, `.rx-picker`, `.comment-composer`, `.comment-x`
  // (reactions + comments). TASK 3 REVEALS `#dm-compose` (DM composer).
  // Everything else stays hidden until its own outbound slice.
  ".pact.del",
  ".settings-del",
  ".story-tile .story-ring.add",
  "#profile-cog",
  "#profile-arrange",
  "#profile-addfriend",
  "#profile-actions .ring-move",   // vp3: friend-profile ring-move (POST write)
  "#profile-actions .btn-danger",  // vp3: friend-profile Unfriend (POST write), scoped
  "#add-device",   // Task 5 review fix: pairing is desktop-side; phone LocalApi has no /api/pair routes
];

// The subset where a dropped `display: none` (not just a dropped selector)
// is worth guarding against explicitly - the highest-traffic write doors.
const LOAD_BEARING_SELECTORS = ["#profile-arrange"];

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

  it("app.js gives the friend-profile move button a ring-move class (vp3)", () => {
    const js = web("app.js");
    expect(js).toMatch(/el\(\s*["']button["']\s*,\s*["']ring-move["']/);
  });

  it("outbound-1: journal composer is REVEALED but every other write stays hidden", () => {
    const css = web("style.css");
    // the journal composer is no longer hidden under body.readonly
    expect(css).not.toMatch(/body\.readonly\s+\.composer\b/);
    // but these other write affordances are still hidden (note: .comment-composer
    // and .rx-open are revealed in task-7; #dm-compose is revealed in task-3;
    // .pact.del, .settings-del etc. stay hidden)
    for (const sel of ["#profile-wall-compose"]) {
      expect(css).toContain("body.readonly " + sel);
    }
  });

  it("outbound-task-7: reaction picker + comment composer are REVEALED but profile composers stay hidden", () => {
    const css = web("style.css");
    // the reaction picker, comment composer, rx-open, and comment-x are no longer hidden
    for (const sel of [".rx-open", ".rx-picker", ".comment-composer", ".comment-x"]) {
      expect(css, `expected "${sel}" to NO LONGER be hidden under body.readonly`)
        .not.toMatch(selectorPresentRegex(sel));
    }
    // but profile-wall-compose and profile-arrange stay hidden
    for (const sel of ["#profile-wall-compose", "#profile-arrange"]) {
      expect(css).toContain("body.readonly " + sel);
    }
  });

  it("outbound-task-3: dm composer is REVEALED but profile/arrange composers stay hidden", () => {
    const css = web("style.css");
    // dm-compose is no longer hidden under body.readonly
    expect(css, `expected "#dm-compose" to NO LONGER be hidden under body.readonly`)
      .not.toMatch(selectorPresentRegex("#dm-compose"));
    // but profile-wall-compose and profile-arrange stay hidden
    for (const sel of ["#profile-wall-compose", "#profile-arrange"]) {
      expect(css).toContain("body.readonly " + sel);
    }
  });
});
