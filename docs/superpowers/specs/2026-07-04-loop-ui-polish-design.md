# Loop UI Polish + Dark Mode — Design

**Date:** 2026-07-04
**Status:** Approved (design discussion; dark-as-neutral confirmed, custom-hex accent confirmed via "bigger palette" scope)
**Basis:** Loop on `main` (posts, DMs, profiles, stories; "quiet canvas, loud people" light theme in `hearth/web/`)
**Goal:** Make Feed / Messages / Profile look modern and finished (not blank/prototype), add a light/dark theme toggle, and widen accent customization. Shareable tonight.

---

## Design principles (unchanged)

"Quiet canvas, loud people" holds in both themes: the chrome is achromatic (light OR neutral-dark), and all color comes from people — their photos and their chosen accent. Dark mode is a variable swap, not a redesign, because every screen already reads from `--paper/--card/--ink/--line/--me`.

## 1. Dark mode (neutral)

- A sun/moon toggle in the header. Theme persists in `localStorage["loop_theme"]`; on load, apply the stored choice, else follow `prefers-color-scheme`.
- Mechanism: `document.documentElement.dataset.theme = "dark" | "light"`; CSS defines `:root[data-theme="dark"] { ... }` overriding the neutral tokens. Light stays the default `:root`.
- **Neutral dark scale** (cool, considered — NOT the old ember-charcoal): `--paper #15171c`, `--card #1d2026`, `--ink #e7e9ee`, `--ink-2 #b6bac2`, `--muted #868b95`, `--faint #5c616b`, `--line #2b2f37`, `--line-2 #23262d`, `--fill #262a32`. Accent (`--me`) and user media provide all color in both themes.
- Elements that hardcode `#fff`/`#000` (e.g. `.btn.solid { color:#fff }`, story-viewer overlay) are audited so they read correctly in dark; the story viewer stays black (media on black is correct in both themes).

## 2. Wider accent customization

- The editor's accent picker grows from 10 to ~16 suggested swatches **plus a native color picker** (`<input type="color">`) for any custom color.
- **Backend change (flagged):** accent validation relaxes from "must be one of 10 `ACCENTS`" to "any valid lowercase `#rrggbb` hex". Safe because accent is only ever used as a validated CSS custom-property value (no injection surface). `messages.ACCENTS` stays as the suggested-swatch source; it's no longer enforced.
  - `hearth/messages.py`: profile-branch accent check → `_is_hex_color(accent)` (regex `^#[0-9a-f]{6}$`).
  - `hearth/api.py`: the `accent not in ACCENTS` guard → the same hex check.
  - Test updates in lockstep: `test_profile_model.py` (a custom hex like `#abc123` now valid; non-hex like `"cobalt"` still rejected; uppercase `#2743D6` still rejected — client sends lowercase); `test_api_profile.py::test_post_profile_bad_accent_400` uses a non-hex value (`"not-a-color"`) instead of `#ffffff`.
- Client always lowercases the chosen hex before sending.

## 3. Messages, rebuilt as a real chat

- Full-height two-pane layout: conversation list (left) + thread (right).
- **Conversation list:** each row shows an avatar (from the counterpart's profile), name, and last-message preview; the open conversation is highlighted.
- **Thread:** fills the column and scrolls; message bubbles (own = accent-tinted, right; friend = neutral, left) with a small timestamp; the **compose bar is pinned to the bottom** (not floating at top).
- **Empty states:** no conversation selected → centered "Pick a conversation to start"; open thread with no messages → "No messages yet — say hi." (No view/seen metrics — unchanged.)

## 4. Styled form controls (kill the prototype look)

- Every raw `<input type="file">` is visually hidden and driven by a styled icon-button (compose "＋ Photo", DM attach, story add, profile avatar/banner). The input still exists for the actual file dialog; only its default chrome is replaced.
- The `keeps` expiry `<select>` gets styled (custom caret, padding, border matching inputs).
- A consistent `:focus-visible` ring (accent or ink, subtle) replaces the browser default — kills the orange Chrome outline on buttons.
- A small set of **inline SVG icons** (attach/paperclip, send, camera, sun, moon, plus, close) — no dependency, CSP-safe, `currentColor`-tinted so they theme automatically.

## 5. Empty states + declutter

- **Feed empty:** "Nothing here yet — say something to your people."
- **Friend ceremony:** the 1-2-3-4 buttons collapse behind a single "Add friend" button that expands the step flow on demand (same pattern as the profile editor), decluttering the sidebar.
- **"Me" sidebar card:** show the avatar thumbnail + display name (not just a button + raw hash); keep "Edit profile" and a gracefully-truncated identity hash with a copy affordance.
- Micro-polish: consistent spacing scale, hover states on interactive rows, softer card shadow, clearer type hierarchy.

## Files touched

- `hearth/messages.py` — accent validation → hex format (+ `_is_hex_color` helper).
- `hearth/api.py` — profile accent guard → hex format.
- `hearth/web/style.css` — dark theme tokens, chat layout, styled controls, focus-visible, icon styling, empty states, polish (the bulk).
- `hearth/web/index.html` — theme toggle button, messages markup for the pinned-compose layout, file-input wrappers, inline SVG icon defs.
- `hearth/web/app.js` — theme toggle + persist, chat rendering/empty-states, styled file pickers, ceremony collapse, expanded palette + custom-hex in the editor, Me card, empty states.
- Tests: `tests/test_profile_model.py`, `tests/test_api_profile.py` updated for the accent-hex change.

## Testing

- **Backend:** accent validation accepts any lowercase `#rrggbb`, rejects non-hex/uppercase; profile round-trips a custom hex through API. Full suite stays green.
- **Visual:** verified in the running 4-node demo (light AND dark), not unit-tested (JS/CSS). Smoke: post in dark mode, open a chat and see bubbles + bottom compose + empty states, pick a custom accent and see the profile update, styled file buttons work.

## Out of scope (this slice)

Profile *layout* themes (avatar-left vs hero), font choice, post density, message reactions/read-state, the logo tweak (deferred — the floating oo's revisit later). These are the "go big" follow-ups.
