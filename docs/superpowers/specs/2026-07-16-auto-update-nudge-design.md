# Auto-update check + in-app update banner — design

Date: 2026-07-16
Status: approved (August, 2026-07-16, section-by-section in session)
Slice: 0.3.15 (bundle with the logged self-peer-row drain follow-up)

## Problem

Updates are only discovered via a manual "Check for updates" button in
Settings -> Updates. Nobody clicks it, so rollouts are slow — the 0.3.14
outage fix only helps once every node updates, and there is no prompt
telling anyone an update exists. Wanted: the app notices updates on its
own and nudges the user, without ever applying or restarting behind their
back (security-sensitive P2P app; users stay in control).

## Decision (August, 2026-07-16)

- **Notify + one-click**, NOT auto-apply. The app auto-checks; an
  in-app banner offers a one-click apply. Nothing applies or restarts
  without a click.
- **Cadence: launch + periodic.** Check shortly after startup, then every
  ~6h while running (covers always-on nodes — the case the 0.3.14 rollout
  would have missed).
- The update MECHANISM (`update.check()` / `apply_web` / `stage_core`,
  signature verification, the `/api/update/*` endpoints) is UNCHANGED.
  This slice adds only the auto-check trigger and the banner surface.

## Design

### 1. Auto-check trigger (node/runner side)

- A slow update-check tick piggybacks the existing gossip loop (same
  place `maintain_enckey` / `maintain_wrap_grants` run — no new thread).
  Constants: first check ~60s after startup (let the node settle), then
  every `UPDATE_CHECK_INTERVAL = 6h`. Tracked by a monotonic last-check
  stamp, like the onion-sync cadence already in the loop.
- The tick calls `update.check()` (best-effort). On any failure (network,
  GitHub down, unverifiable/replayed manifest — `check()` already returns
  None or raises on those) it swallows and retries next cycle. An
  unverifiable manifest MUST never surface a banner.
- Result stored on the node as `update_status`:
  `{"available": bool, "kind": "web"|"core"|None, "version": str|None}`.
  Set from the `check()` result (`web_available`/`core_available`; if both,
  prefer surfacing "core" since web is gated behind it via
  `min_core_for_web`). Cleared (`available=False`) after a successful
  apply.
- `node.notify()` fires on a status change so the UI updates live over the
  existing `/ws` channel. `update_status` is exposed to the client
  (a field on an existing state payload the UI already fetches, e.g.
  `/api/state`, or a tiny dedicated getter — implementer picks the
  smallest seam).

### 2. The banner (web UI)

- A slim bar pinned to the top of the app window (inside the web UI,
  below the frameless titlebar), rendered ONLY when
  `update_status.available`. Not a modal — never blocks the app.
- Copy by kind (DRAFT — August words final):
  - web: "A Kreds update is ready" + button "Update now"
  - core: "A Kreds update is ready" + button "Restart to update"
- Dismissible (an x): hides for the session but RETURNS on the next
  check-cycle status push (nudge without trapping). Dismiss state is
  client-side only (not persisted) — deliberately, so a long-running
  session re-nudges.
- The button calls the existing `POST /api/update/apply`:
  - web apply -> the existing hot-swap + `location.reload()` path.
  - core apply -> the existing stage + restart flow (`restart_required`
    -> the existing "Restart now" handling in `renderUpdateSettings`,
    reused).
- Settings -> Updates panel is UNCHANGED — the banner is purely additive;
  manual check still works for anyone who wants it.

### 3. Data flow

startup -> (60s) first check -> update_status set -> notify -> /ws push
-> banner renders -> user clicks -> /api/update/apply -> (web: reload |
core: restart) -> new version. Periodic (~6h) re-checks repeat the loop;
a dismissed banner re-appears on the next status push if still available.

## Tests

- `update.check()` result maps to the right `update_status` shape:
  web-only -> kind "web"; core (or core+web) -> kind "core"; neither ->
  available False. (unit, faked `check()`)
- Best-effort: a `check()` that raises / returns None leaves
  `update_status` unchanged and does not crash the tick. (unit)
- The check tick respects the interval (doesn't run every gossip round) —
  monotonic-stamp gate, same test shape as the onion-sync cadence test.
- Banner content pins in `app.js`: renders only when available; web vs
  core copy + button; the x dismiss; the button hits
  `/api/update/apply`. (test_web_assets content pins)
- Existing update tests (`test_update*.py`) cover the real check/apply/
  verify behavior unchanged — not re-tested here.

## Out of scope

- Auto-APPLY (explicitly declined — notify only).
- Changing the signed-manifest / verification mechanism.
- Release notes / "what's new" surface in the banner (the manifest has a
  `notes` field; showing it is a later nicety, not this slice).
- Persisting dismiss state across sessions.
