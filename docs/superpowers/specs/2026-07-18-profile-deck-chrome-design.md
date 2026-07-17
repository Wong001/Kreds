# Profile deck chrome cleanup (sketch 2026-07-18)

August's sketch of the own-profile wall asks for a cleaner viewing
surface: photo decks lose their visible arrows and "n/N" counter, and
the per-block editing chrome ("+" add-photos, per-block gear) retreats
into Arrange mode.

## 1. Arrows -> invisible tap zones

`renderDeck` (hearth/web/app.js) drops the `.deck-nav` `‹`/`›` pill
buttons. In their place: two **invisible full-height tap zones**
(`.deck-tap-prev` / `.deck-tap-next`) covering the left and right ~35%
of the deck. Left flips to the previous photo, right to the next; at
either end the zone is a no-op (same as today's disabled arrow — no
wrap). The middle ~30% keeps the existing behavior: tap opens the
lightbox at the current photo.

- Zones are real `<button>`s with `aria-label`s, fully transparent;
  `:focus-visible` draws a ring so the keyboard path stays visible.
- The existing 40px swipe gesture is untouched. A swipe's trailing
  click over a zone must not double-advance — the zones consume the
  same `swiped` flag the lightbox tap already checks.
- **Arrange mode:** `.block.arranging .deck-tap { display: none; }`.
  Buttons are exempt from the block's drag `pointerdown` (the
  `closest("button…")` bail), so leaving the zones visible would make
  70% of a deck block un-draggable in Arrange. Hidden, the whole block
  stays a drag surface, exactly as today.

## 2. "n/N" counter -> bottom-center dots

The wall deck's `.deck-count` badge goes away. `renderDeck` renders a
`.deck-dots` row instead — one 6px dot per photo, bottom-center over
the image, active dot highlighted, updated by `show()`. Styled for
on-photo legibility (translucent paper dots with a faint ink ring),
`aria-hidden` (the buttons + lightbox carry the semantics),
`pointer-events: none`.

- The **composer preview keeps its `.deck-count`** ("3" photo count,
  app.js `sizePreview` path) — that badge is a different affordance
  and wasn't in the sketch. The base `.deck-count` CSS stays for it;
  only the wall-deck usage and the `.block-deck .deck-count` /
  `.deck-nav` rules go.
- **Arrange mode:** `.block.arranging .deck-dots { display: none; }` —
  the "+" pill owns bottom-center there (pre-existing `.block-add`
  position).

## 3. "+" and per-block gear are Arrange-only

`renderBlock` moves the `.block-settings-btn` gear and the `.block-add`
"+" label from the unconditional `p.mine` branch into the
`ARRANGING && p.mine` branch. Outside Arrange, an own block renders
exactly like a friend sees it (plus the small Inner/Kreds scope tag,
which the sketch didn't touch). This consciously supersedes the
2026-07-15 "gear on every own block outside Arrange" decision — the
delete path survives because Arrange's tap-to-open **and** the gear
both reach the settings modal.

- The hover-reveal CSS (`.block .block-settings-btn { opacity: 0 }` +
  `:hover`/`:focus-within`/coarse-pointer reveals) is deleted — the
  gear now only exists where it was already always-visible.
- Existing Arrange pointer plumbing already exempts `button` and
  `label` targets from drag, so both controls stay clickable in
  Arrange with no new event code.

## Scope

- Applies to decks **everywhere** (own and friends' profiles) — the
  carousel is shared; only the +/gear gating is own-profile-specific
  (friends never had them). Approved by August 2026-07-18.
- In Arrange, tap-to-open-settings stays; the gear is the explicit
  affordance for the same modal. Approved.
- No backend, API, or layout-engine changes. Pure web-client chrome.

## Tests

- `tests/test_ui_smoke_albums.py`: arrow clicks -> `.deck-tap-next`
  clicks; "1/3"/"3/3"/"1/4" counter asserts -> dot count + active-index
  asserts; the "+" grow step now enters Arrange first (and the later
  explicit Arrange click is dropped — Arrange survives the in-place
  re-render).
- `tests/test_web_assets.py`: `test_block_gear_hover_revealed_outside_arrange`
  is replaced by an Arrange-only guard (gear + add created inside the
  `ARRANGING && p.mine` branch; hover-reveal CSS gone); the
  `.settings-opt.settings-del` cascade assert it housed is kept.
- Composer smoke (`test_ui_smoke_composer.py`) untouched — preview
  badge intentionally kept.
