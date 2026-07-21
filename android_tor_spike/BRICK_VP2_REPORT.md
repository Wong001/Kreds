# Visual Parity — Slice 2 (Messages) — on-device report

**Second slice of the shared-UI visual-parity arc.** The phone's **Messages
tab** now renders hearth's real desktop Messages view — the conversation list +
a DM thread — read-only, served by extending the slice-1 `LocalApi` with three
read-only routes over the phone's native store (decrypt-on-read). Reachable via
a new **Messages entry on the mobile tab bar** (the desktop's mobile bar had
none). No sending — the DM composer is hidden by the read-only seam.

Branch: `brick-vp2-messages` (base `70122f5` off main).
Plan: `docs/superpowers/plans/2026-07-21-android-visual-parity-messages-slice2.md`.

## What it does

- **`/api/conversations`** — the conversation list `[{identity_pub, name,
  last_text, last_from_me, last_at, count}]`, sorted newest-first. Built from
  the phone's native DMs, grouped by partner, matching hearth's `node.py`
  exactly. **Load-bearing:** app.js awaits it on *every* refresh tick (a 404
  would break the whole app boot), so it always returns 2xx (`[]` if no DMs).
- **`/api/dm/{identity_pub}`** — a DM thread `[{msg_id, from_me, created_at,
  expires_at, text, blobs, undecryptable, story_ref}]`, oldest-first. Built from
  the RAW stored DMs joined with the decrypt results, so hearth's
  **undecryptable-placeholder rows** ("cannot decrypt on this device") and
  message counts are reproduced exactly.
- **`/api/dm-blob/{msg_id}/{h}`** — DM photo bytes, decrypt-on-read, **kind-gated
  to DM** (a post's msgId 404s), **no Cache-Control** (matching hearth — DM media
  isn't immutably cacheable, unlike posts).
- **Mobile Messages tab** — `<button data-tab="messages">` added to the shared
  `.tabbar-mobile`; routes through the existing generic handler with no app.js
  change. Circle · Journal · **Messages** · Me.
- **DM composer hidden** — `#dm-compose` added to the `body.readonly` seam
  (read-only; the thread stays readable, the input bar is gone).
- `DecryptPass.kt` untouched; the whole slice is confined to `LocalApi.kt` +
  two additive shared-`hearth/web` edits.

## Desk gates (all GREEN — Claude, pre-August)

| Gate | Result |
|------|--------|
| `:tor-manager:testDebugUnitTest` (incl DM grouping + golden-shape + dmKeys tests) | BUILD SUCCESSFUL (150+ tests) |
| `npx vitest run` (incl the extended read-only-seam guard) | 25/25 |
| `assembleRelease` (under NDK r27.1) | BUILD SUCCESSFUL |
| Per-task reviews (grouping / routes / dm-blob / shared-UI) | all APPROVED (Tasks 3-4 zero issues) |
| Whole-branch review (opus) | **READY TO MERGE** — zero Critical/Important, 3 Minor follow-ups |
| All commits (bodies + subjects) | trailer-clean, lowercase `feat/docs(vp2)` |

**Shape fidelity + security, desk-proven:** conversations + dm-thread byte-match
hearth's `node.py`; dm-blob kind-gate structurally enforced (post msgId can't
resolve a DM key); decrypt-on-read, zero disk writes.

---

## On-device DoD — G20 (August drives)

**Field lessons (do these first):**
- Desktop node on **`serve --tor`**, unlocked.
- Install the **RELEASE** apk (debug → "Unable to load script").
- Play-Protect "install anyway" may need a physical tap.

**Honest boundary:** slice 2 is the **Messages view only, read-only**. The
Me/profile view is still slice 3 (it degrades gracefully). The mobile
conversation-list ↔ thread layout is hearth's stock responsive stacking (both
panes in one scroll box at ≤720px) — a proper single-pane-with-back mobile UX
is the top follow-up. No mobile unread badge yet (desktop-only).

### Steps

1. Desktop node up on `serve --tor`, unlocked. (Your phone already has synced
   DMs from B.2c — the "Test Laptop" / "CoomGuzzler" conversations.)
2. Launch the app; you should land in the Journal (slice 1).

### Checklist — tick each

- [ ] The bottom tab bar now shows **Circle · Journal · Messages · Me** (the new
      Messages tab, clearing the Android nav bar).
- [ ] Tapping **Messages** opens the conversation list — your DM partners with
      the last-message preview + timestamp.
- [ ] Tapping a conversation opens the **thread**: message bubbles, your own DMs
      on the right/accent, received on the left, with timestamps — read-only.
- [ ] A DM **photo** renders (via `/api/dm-blob`); tap opens it full-size.
- [ ] A **story-reply** DM shows its story-ref chip (if any).
- [ ] An **undecryptable** DM (if any) shows "(cannot decrypt on this device)"
      rather than a missing bubble.
- [ ] **Read-only**: no message composer/input bar, no Send, no Photo attach in
      the thread — while the bubbles stay fully readable.
- [ ] **Regression:** the Journal (slice 1) still renders as before.

### Verdict (August to fill)

> _(pass / partial / fail + notes — what rendered, any surprises)_

## After the run

On a **pass**, this slice merges to public main (the second visual-parity
slice). Then slice 3 (**Me/profile + the 4-column wall**) or slice 4 (polish).

## Follow-up tickets (Minor, none blocking merge — from the whole-branch review)

- **Self-DM guard** — `extractDmMsgs` doesn't filter a self-addressed DM
  (sender==to==own), which hearth drops (`store.py:1030`). Unreachable (no
  self-DM feature); a 1-line `partner != own` guard would restore exact parity.
- **`story_ref` forward-compat** — re-serialized key-by-key (fine for the flat
  `{story_id, media_hash}` schema; a future nested field would need raw
  passthrough).
- **Route-level test** — no JVM test pins dm-blob's no-Cache-Control header or
  the `handle()` kind gate (they need a Context); the `dmKeys` filter is
  unit-tested, the header is a code-only guarantee. An instrumented test is a
  follow-up.
- **rowid tiebreak** — same-`created_at` DMs order by `allMessages()` scan order
  (≈ SQLite rowid) rather than hearth's explicit `rowid ASC`; matches in
  practice, but exposing `rowid` on `StoredMsg` would make it exact.
- **Mobile Messages UX** — the ≤720px stacked conv-list+thread is awkward; a
  single-pane-with-back pattern + a mobile unread badge are the top polish
  items.
