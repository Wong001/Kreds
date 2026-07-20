# Brick B.2d-4 — Responses (reactions + comments) on the Android client

**The LAST richer-feed slice.** View-only. Renders reactions + comments under
posts. No composing, no hearth change, `seal_slots` deferred (private → alias).

Branch: `brick-b2d4-responses` (5 commits, base `4ca1c53` off merged public main).
Plan: `docs/superpowers/plans/2026-07-20-android-b2d4-responses.md`.
Spec: `docs/superpowers/specs/2026-07-20-android-b2d4-responses-design.md`.

---

## What it does

The phone already syncs aggregated `KIND_RESPONSES` records (a post author's
rolled-up reactions+comments, wrapped to the post's scope audience). This slice
decrypts and renders them:

1. **`responsesAad`** ported to `KotlinDmcrypt` (byte-matches hearth
   `responses_aad`), plus a genuine hearth-aggregated test vector.
2. **`KotlinResponses`** — the security core. Validates each entry, and for a
   PUBLIC entry attributes it to a real friend **only when both**
   `verifyRaw(device_pub, responder_sig, responseSigPayload(...))` **and**
   device-binding (the signing device is enrolled to the claimed identity)
   hold. Otherwise → a client-derived **alias** (name + hue from `alias_seed`).
   Private/unverified/wrong-device → alias. Fail-closed.
3. **Responses decrypt pass** — latest `KIND_RESPONSES` per (author, target),
   **author-scoped** (a record only counts for a post its own author signed),
   decrypt → aggregate → surfaced as `item.responses` on each feed post.
4. **App.tsx** — under each post: a reaction summary (`heart 3  fire 1`) and a
   comment list, real friends in default text, aliases in their hue.

## Desk gates (all GREEN — Claude, pre-August)

| Gate | Result |
|------|--------|
| `KotlinResponsesTest` (security core) | 21/21 |
| `KotlinDmcryptTest` (responsesAad vector round-trip) | green |
| `DecryptPassTest` (responses pass, latest-wins, author-scope drop) | 25 → 29 |
| `:tor-manager` unit suite | 117/117 |
| `assembleDebug` | SUCCESS |
| `assembleRelease` (both APKs) | SUCCESS |
| `tsc --noEmit` | 11 → 11 (0 new) |
| `vitest` | 20/20 |
| Per-task reviews (opus security core / opus pass / sonnet render) | all APPROVED |
| Whole-branch review (opus) | **READY TO MERGE**, 3 cosmetic Minors log-and-ship |
| Commit trailers (all 5, bodies + subjects) | CLEAN, no AI/co-author |

**Security core, desk-proven:** attribution requires the responder's real
device signature AND device-enrollment — a forged/wrong-device/private entry is
never attributed (mis-attribution is a desk-gate concern, already proven by the
real-vector tests: valid-sig+bound → name, corrupted-sig → alias, valid-sig on
unenrolled device → alias, wrong-target → alias). On-device just confirms REAL
responses render.

---

## On-device run — G20 (August drives)

**Field lessons (carried forward — do these first):**
- Run the desktop node with **`serve --tor`** (`hearth app` from source disables Tor).
- If the desktop node is **locked**, sync fails as a bare EOF — **unlock via the web UI** first.
- Install the **RELEASE** apk, not debug (debug → "Unable to load script").
- Play-Protect / "install anyway" may need a physical tap on the phone.

**Honest coverage boundary:** only responses that were aggregated by the author
*after* the phone's enc key published are wrapped to the phone and will decrypt.
Older posts may legitimately show **no responses**. That's expected, not a bug.

### Steps

1. Desktop node up on `serve --tor`, unlocked in the web UI.
2. From the **desktop**, add **reactions** + at least one **comment** to one of
   your own recent posts (one the phone can already see in its feed).
3. (Optional, if a friend node is reachable) have a friend **react + comment**
   on a post you both see — to exercise the public-by-name path with a second
   real identity.
4. On the **phone**, sync.
5. Open the feed.

### Checklist

- [ ] The post shows the **reaction counts** under it (e.g. `heart 2  fire 1`).
- [ ] The post shows the **comment(s)** under it with the comment text.
- [ ] A comment from a **known friend** (real, verified) shows their **name** in
      default text (not a hue-colored alias).
- [ ] A comment with no public attribution (private, or self/own where
      applicable) shows a **colored alias** name (an adjective + animal, e.g.
      "Curious Crane"), never a wrong real identity.
- [ ] **Regression:** own posts, photos (thumb + fullscreen), video, stories,
      and the DM/feed all still render as before — nothing broke.
- [ ] (Boundary) a post with no decryptable responses simply shows nothing
      extra (no crash, no error row).

### Verdict (August to fill)

> _(pass / partial / fail + notes — what rendered, any surprises)_

---

## Follow-up tickets (Minor, none blocking merge)

- **M1 (cross-platform parity):** App.tsx renders alias hue at `hsl(h, 60%, 45%)`;
  web `app.js` uses `55%` saturation, and `KotlinResponses.kt:457`'s doc claims
  `55%`. The **hue** (the security-relevant part) is byte-exact; only the fixed
  saturation drifts 5%. Align the App.tsx value or the doc when the visual-parity
  slice lands.
- **M2:** `aliasName` does `substring(0,2)` on `alias_seed`; an empty seed would
  throw — unreachable in production (`validEntry` guarantees hex32 before
  `resolveDisplay`), documented. Optional guard.
- **M3:** `num("created_at")` rejects a JSON `true` where hearth's
  `isinstance(True, int)` accepts it — divergence is in the safe direction (phone
  strictly drops an absurd entry). Ignore or align.
- **From B.2d-3 (still open):** `getStories` drops "me" for own stories;
  own outgoing story-reply chip shows wrong direction; `missingBlobs` re-scans
  expired story rows every sync.
- **From earlier:** loopback MediaServer hardening (GET/HEAD method allow-list,
  bounded pool, single store instance, constant-time token); `onSyncProgress`
  re-render throttling; avif-coder CVE-watch.

## After the run

On a **pass**, this brick merges to public main (PR #10) — the richer-feed arc
(photos → video → stories → responses) is complete. Remaining roadmap:
`seal_slots` de-anon (deferred), composing/posting from the phone (first outbound
slice), a real visual-parity pass, iOS (Mac-gated; the NEPacketTunnelProvider VPN
path is the persistent-background route).
