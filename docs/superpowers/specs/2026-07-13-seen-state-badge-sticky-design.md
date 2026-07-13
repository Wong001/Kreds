# Seen-state fix + unread DM badge + sticky bars — design

Date: 2026-07-13
Status: approved direction (August, in-session). Spec for review before
writing-plans. Live-test follow-up batch (ROADMAP "Live-test findings
2026-07-10": unread badge, sticky top bar) plus a seen-state bug August
reported in-session.

## Problem

Three small, user-visible gaps from real use:

1. **The freshness dot never clears from reading.** The circle rail and
   chip bar show a "new post" dot per person (`isFresh`: latest FEED post
   time vs a localStorage watermark). But `markOpenedNow` is called in
   exactly ONE place — clicking that person's chip in the journal filter
   bar (`app.js` chip handler). Reading their post in the Everyone feed or
   opening their profile page never touches the watermark, so the dot
   sticks until the user happens to use the chip. Reported by August after
   real use.
2. **No unread indicator for DMs.** Over Tor, sync latency means a DM can
   land minutes after it was sent; nothing in the UI says "you have a new
   message" unless the Messages view is already open. (ROADMAP: "unread
   badge — top-bar red count for unread DMs; local knowledge, honest;
   masks Tor sync latency.")
3. **The whole page scrolls, including the nav and composer.** Scrolled
   deep in the journal there is no way to post or navigate without
   scrolling back up. (ROADMAP: "sticky top bar + composer on scroll.")

## Design

### 1. Seen-state fix

"Seen" for a person's new-post dot becomes any of:

- **their post was actually on screen in the journal** (new),
- **you opened their profile page** (new),
- **you clicked their chip** (existing, kept).

Mechanism:

- `renderJournal()` attaches one shared `IntersectionObserver` to post
  entries (re-attached after each render, since entries are rebuilt).
  When an entry is **≥60% visible for ~700ms** (dwell timer per entry, so
  fling-scrolling past a post does not count as reading it), the author's
  watermark bumps to **that post's `created_at`** — never to "now", and
  never backwards (`max(current, created_at)`).
  - Why `created_at`, not now: if the user saw the Tuesday post but a
    Thursday post sits below the fold, the watermark lands at Tuesday and
    `isFresh` (latest > watermark) honestly keeps the dot.
- `openProfile(identity)` for a non-self profile marks "now" — a
  deliberate visit clears the person's dot outright.
- After any watermark bump, re-render the chip bar and circle rail
  (`renderChipbar()` + `renderCircleRail()`, debounced) so the dot clears
  while the user is looking, not on the next WS refresh.

Unchanged honesty guards: watermark stays localStorage-only
(`kreds_opened:` prefix, same key format), per-device, no server field,
no seen-data ever leaves the client.

Rejected: scroll-position heuristics (imprecise about what was actually
visible); clear-all-on-journal-open (marks below-the-fold posts seen —
dishonest).

### 2. Unread DM badge

**Server — one additive read-only field.** `node.conversations()` gains
`last_from_me: bool` (whether the newest message in the thread was
authored by this identity). Without direction, the client cannot tell
"friend wrote me" from "I wrote last". No store/protocol/crypto change;
`/api/conversations` shape is otherwise untouched.

**Client:**

- New per-thread watermark, prefix **`kreds_dm_opened:`** + identity_pub
  (deliberately separate from the post watermark's `kreds_opened:`).
- A conversation is **unread** when `!last_from_me && last_at >
  dmOpened(identity)`.
- **Badge:** a red count pill on the desktop `#nav-messages` button =
  number of unread conversations (people, not messages). Hidden at zero.
- **Conversation list:** unread rows get a highlight + dot, so inside the
  Messages view the badge is explained at a glance.
- **Clearing:** `openThread()` marks that thread's watermark; sending a
  message marks it too (you have obviously seen the thread you are
  replying in).
- **Data flow:** `refresh()` fetches `/api/conversations` once per cycle
  and hands the result to both the badge renderer and (when visible) the
  Messages view — this also removes the existing double-fetch
  (`loadConversations` called from both `refresh()` and `openThread()`),
  a named backlog item. `loadConversations`/badge render from the shared
  result instead of re-fetching.

**Update-skew degrade** (web payload may run ahead of core — allowed
skew, same class as `hide_to_tray`): when `last_from_me` is `undefined`,
fall back to `last_at > watermark` alone. That can over-badge a thread
where the user's own *other* device wrote last, but never silently
under-badges. One code comment states this.

**Honest limits, stated where the watermark lives:** per-device
(localStorage), not synced across devices — same boundary as the post
watermark; "synced read-state" stays its own roadmap item. Mobile has no
Messages tab, so the badge is desktop-nav-only this slice; a mobile
Messages entry is a named follow-up, not silently included.

**Perf boundary, stated:** `node.conversations()` decrypts each thread's
tail on every call, and `refresh()` now calls it every cycle. Fine at
friend-scale (the double-fetch removal offsets it); if it ever shows up,
the fix is a lighter server-side last-message query, not client caching.

### 3. Sticky top bar + composer

**Root cause first:** `.app` uses `overflow: hidden` for its
rounded-card clipping, and any non-`visible` overflow on an ancestor
defeats `position: sticky` against the page scroll. Change it to
**`overflow: clip`** — identical visual clipping, no scroll container
created, sticky works.

- `.appnav` → `position: sticky; top: 0` (desktop shell: `top: 40px`
  under the fixed titlebar, keyed off `body.desktop`).
- The chip bar + journal composer move into **one sticky header-group
  wrapper** pinned just below the nav — one wrapper, not two
  independently-offset sticky elements, because the chip bar wraps to
  extra rows as friends grow and hardcoded per-element offsets would
  break. Solid `--paper` background + hairline bottom border so entries
  scroll under it cleanly.
- The nav height offset for the wrapper comes from one CSS custom
  property (set where the nav's height is defined), not a magic number
  repeated in two places.
- **≤820px: only the nav sticks** (the wrapper's sticky is disabled in
  the existing mobile media query) — a small viewport is not eaten by
  three stacked bars.
- z-index: below every overlay (circle overlay, lightbox, block
  settings, profile editor, lock screen are `fixed` at z≥25); the sticky
  pair stays under 20.

Rejected: making `.appmain` its own scroll container (`overflow-y:
auto`) — centralizes scrolling but breaks body-level scroll behaviors
(mobile URL-bar collapse, existing scroll-position code) for no gain.

## Testing

Per the project's testing split (Claude: automated + networking; August:
behavioral/UI):

- **Server:** unit test for `last_from_me` (last message mine vs theirs,
  and absent-thread ordering unchanged).
- **Web assets/DOM:** badge markup + hidden-at-zero class logic; sticky
  CSS properties present (`overflow: clip` on `.app`, `sticky` on nav +
  wrapper, the ≤820px override); observer wiring exists; the
  `kreds_dm_opened:` prefix and skew-degrade comment present.
- **Playwright live smoke** (two isolated real nodes, free non-demo
  ports — the pattern every feature-12 increment used): B posts → A's
  rail dot appears → scrolled into view ≥dwell → dot clears without a
  reload; B posts again with the post kept below the fold → dot stays;
  open B's profile → dot clears. B DMs A → badge shows 1 on A → A opens
  the thread → badge clears → A sends from the thread → still clear.
  This class of bug (static asserts passing on comment text while the
  live behavior is broken) is exactly what the roadmap's
  "permanent behavioral test suite" note is about — these smokes are
  written runnable, candidates for that suite.
- **August's checklist at merge:** sticky feel while scrolling (desktop +
  mobile widths, desktop shell titlebar offset), badge timing over real
  Tor latency between real machines, dwell feel (700ms — tunable
  constant), dark mode on the new pill/highlight.

## Release

`last_from_me` touches `hearth/node.py`, so this is **not** a web-only
hot-update: core + web release, **0.3.10** on both, normal signed
pipeline (`RELEASE.md`). No wire-protocol change (`hearth/v0.2`
untouched), no store migration, no crypto change.

## Out of scope (named, deliberate)

- Full-identity verification screen (its own slice, still queued).
- Mobile Messages tab / mobile badge placement.
- Synced (cross-device) read-state — roadmap item, unchanged.
- Home-feed photo lightbox and other deferred UI polish.
