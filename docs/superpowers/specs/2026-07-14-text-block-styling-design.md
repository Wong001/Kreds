# Text block styling — design

Date: 2026-07-14
Status: approved direction (August, in-session — "text box is kinda
boring"). Extends the wall collage redesign (spec 2026-07-13); ships on
the same parked bundle branch.

## Problem

A wall text block renders top-left, span-scaled, sans, ink-colored — a
sticky note. August wants alignment (both axes), size control, a better
font story, bold/italic, and color — with monochrome choices adapting to
dark mode rather than breaking in it.

## Design

**Where the style lives:** a new `texts` map on the mutable
`profile_layout` record — `{msg_id: {h, v, size, font, weight, style,
color}}` — the exact photo-grid idiom: presentation restyles in place
(post immutable), latest-wins, syncs to friends, carried forward by
EVERY layout write (including `set_album`'s reconciliation). Every field
optional; defaults are omitted so the map stays minimal and an unstyled
block renders exactly as today.

**Options (validated enums, wire-additive, no protocol bump):**
- `h`: `left` (default) / `center` / `right`
- `v`: `top` (default) / `middle` / `bottom`
- `size`: `auto` (default — the current span-scaled `text-w*` behavior)
  / `s` / `m` / `l` / `xl`
- `font`: `sans` (default, Instrument Sans) / `disp` (Bricolage
  Grotesque — already self-hosted; no new assets)
- `weight`: `normal` (default) / `bold`
- `style`: `normal` (default) / `italic`
- `color`: `default` (theme ink — adapts to dark mode, per August's
  monochrome note) / `accent` (resolved by each VIEWER to the AUTHOR's
  accent, like the banner) / one of the ten `ACCENTS` hexes
  (messages.py — the standing palette, chosen to read on both themes).
  Raw arbitrary hex is deliberately NOT allowed: it's the structured-
  options-never-user-CSS rule, and it's what keeps dark mode honest.

**UI:** the block-settings modal (Arrange → tap/gear on a text block)
gains a "Text" group, immediate-apply like every other control there:
segmented H-align + V-align rows, size chips (Auto/S/M/L/XL), Sans/
Display toggle, B and I toggles, and a color row (Default / Accent /
the ten swatches). Text blocks only (a block with photos/video never
shows the group). Journal entries unchanged.

**Server:** `texts` validation on `KIND_PROFILE_LAYOUT`;
`node.set_block_text(msg_id, **style)` (ValueError → 400, drops
default-valued fields, refuses non-text blocks — a block with blobs);
`POST /api/block-text`; `profile_view` annotates wall text blocks with
`text_style` (fully-defaulted dict, so the client never guesses).

**Client:** the text branch of `renderBlock` becomes a flex container
mapping `h`/`v` to justify/align, `size` to classes (auto keeps
`text-w*`), `font`/`weight`/`style` to classes, `color` to an inline
CSS variable (`accent` → the profile's accent already on the page;
hexes as-is; `default` → no override, theme ink flows).

## Testing

Node tests (map carry-forward across every layout write, validation,
non-text refusal, `text_style` annotation defaults); one integration
assertion (style survives sync, viewer sees author's accent resolution);
web-asset tests (modal group, class mapping, no-group-on-photo-blocks);
a styled-text leg appended to the collage live smoke (set center/middle/
XL/display/bold/accent → classes + computed style assert + friend view).
August: dark-mode/light-mode reads of every color option.

## Out of scope

New font families (assets); arbitrary hex text color (structured-options
rule); journal text styling; per-word/rich-text formatting (the block is
one run of text — mixed inline formatting is a different feature).
