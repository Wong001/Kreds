# Loop Visual System + Profiles — Design

**Date:** 2026-07-03
**Status:** Approved (design discussion; visual direction approved from the `loop-visual-v1` mockup)
**Basis:** Existing app on branch `hearth-vertical-slice` (feed, messages, friends, devices; `profile` message kind carries only `name`)
**Working name:** **Loop** (display name only; codebase/package stays `hearth` — the name is one display string to swap, done here)

---

## Two halves of one slice

1. **Visual system** — apply Loop's "quiet canvas, loud people" language to every existing screen.
2. **Profiles** — the first feature built into that language: a profile page + editor with per-user accent, customizable avatar (shape / size / placement), banner, and bio.

Both ship together because the profile page is the surface that proves the visual system.

## Design philosophy (the anchor)

**Quiet canvas, loud people.** The platform chrome is achromatic — paper, ink, hairline greys, no brand accent anywhere. All color on any screen comes from people: a photo someone shares, or the accent a person chooses for their own profile. This is a hard rule, not a guideline: if something is colored, a person put it there.

## Visual tokens

**Color (chrome — every screen):**
- `--paper #F6F7F9` (cool near-white canvas), `--card #FFFFFF` (raised surfaces)
- `--ink #17191E` (near-black text), `--ink-2 #3B3F47`, `--muted #6B7079`, `--faint #969BA3`
- `--line #E3E6EB` (hairlines), `--line-2 #EEF0F3`, `--fill #EDEFF2`

**Color (people — profiles/content only):** a curated accent palette a user picks from (~10 options), e.g. Cobalt `#2743D6`, Clay `#C0563B`, Moss `#3E7C55`, Violet `#8A5CD0`, Ink `#17191E`, plus ~5 more (teal, amber, rose, slate, plum). Stored per identity; applied only as a scoped `--me` CSS custom property inside that person's profile/content contexts. Custom hex is a later addition; v1 is the curated set. Any stored accent is validated as a strict `#RRGGBB` before use.

**Type:** system-native stack (`system-ui, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif`) — no webfont, so no silent-fallback risk. Personality is treatment, not face: display 800 weight at −0.03em tracking; uppercase micro-labels at +0.12em; `ui-monospace` for identity hashes and other data. Type scale (px): 30 display / 21 heading / 15 body / 13 small / 11 label.

**Wordmark:** "Loop" with the L and p in the heavy display weight and the two o's as thin open rings (a nod to the closed circle). Implemented as a **reusable inline SVG component** (`web/logo.js` or an inline SVG snippet) so it is crisp at any size and used identically in the app header and on profiles. Stroke weight of the rings is deliberately lighter than the L/p.

**Layout:** hairlines + whitespace carry structure. No cards-on-cards, no decorative accent rails. Corners ~12–16px, not pill-everything. Dark mode is **deferred** (v1 is the light canvas as approved).

## Applying the system to existing screens

Restyle (no behavior change) `web/index.html`, `web/style.css`, and touch `web/app.js` only where markup/class names change:
- **Header:** replace the text "Hearth" with the SVG wordmark; tabs (Feed / Messages) in the new type treatment.
- **Feed:** posts on the quiet canvas — monogram or photo avatar, name, device + relative time, body, photo, expiry chip, delete affordance. No vanity metrics exist and none are added (this absence is a stated feature).
- **Messages:** conversation list + thread bubbles in the new language; the reader's own bubbles tinted with *their* accent, the friend's neutral.
- **Friends / Devices panels:** same restyle; QR stays functional.
- **Revoked banner:** restyled to match.
- All user-facing copy that says "Hearth" becomes "Loop."

## Profiles — data model

Extend the existing `profile` message kind (still latest-wins per identity, still gossiped and validated exactly like today — all structured data, no executable content):

```
profile payload {
  kind: "profile",
  name: str,                       # existing
  bio: str (<= 240),               # new
  accent: "#RRGGBB" (from palette),# new
  avatar: <blob hash> | null,      # new — re-encoded image, see gate below
  avatar_shape: "circle"|"squircle"|"square"|"triangle",
  avatar_size:  "s"|"m"|"l",
  avatar_align: "left"|"center"|"right",
  banner: <blob hash> | null,      # new — re-encoded image; null => accent gradient
  created_at: float
}
```

Validation rejects anything off-enum, over-length, or a non-hex accent. Missing new fields default (round / m / left / accent gradient) so older profiles render fine.

## Profiles — the image transcode gate (security)

Avatar and banner uploads pass through a server-side gate before storage, using **Pillow** (already installed via `qrcode[pil]`):
1. Open with Pillow; reject if it won't parse as an image (`hearth/imagegate.py`).
2. Take the **first frame only** (strips animation — animated avatars are deferred until a proper video/animation pipeline exists).
3. Resize down to a max dimension (avatar 512px, banner 1500px wide) — kills decompression-bomb dimensions.
4. Re-encode to a known-good static format (PNG or JPEG) and store *those* bytes as a content-addressed blob.

Every viewer therefore only ever renders bytes Loop produced, never the uploader's original file — closing the decoder-exploit / decompression-bomb surface for the class of image inputs. The 5 MB blob cap still applies pre-gate.

## Profiles — page + editor

- **Profile page** (`/api/profile/{identity_pub}` → data; rendered client-side): banner, avatar (shape/size/placement from the person's settings), name, short identity hash, bio, accent applied as `--me`, and the person's own posts below (photo posts as a grid, text posts as a list). Viewable for self and any friend.
- **Editor** (own profile only): pick accent (swatch grid), upload avatar + choose shape/size/placement, upload banner, edit bio + display name. "Save" publishes an updated `profile` message (existing publish path). Live-updates via the existing WebSocket.
- Entry points: click your own name/avatar in the header → your profile; click a friend in Friends or a post author → their profile.

## API additions

- `GET /api/profile/{identity_pub}` → the profile fields above + that identity's posts.
- `POST /api/profile` — extended to multipart (name, bio, accent, avatar_shape/size/align, optional avatar file, optional banner file). Files pass the transcode gate; 400 on invalid enum/hex/parse failure, 413 over cap. This replaces today's JSON name-only endpoint; the existing `test_api.py` profile test is updated to the multipart form (name field only) in lockstep.
- Avatar and banner bytes are served by the existing `GET /api/blob/{hash}` — they are ordinary re-encoded public blobs, no new route needed.

## Testing

- **Visual system:** existing tests stay green (restyle is CSS/markup; behavior unchanged). Any changed element IDs/classes referenced by tests get updated in lockstep.
- **imagegate unit tests:** valid image re-encoded + downsized; animated input reduced to one static frame; non-image bytes rejected; oversized-dimension input capped.
- **profile model tests:** extended payload validates; off-enum/over-length/bad-hex rejected; missing new fields default; latest-wins across gossip; a friend's updated profile propagates and renders.
- **API tests:** `POST /api/profile` multipart round-trip (fields + avatar + banner); `GET /api/profile/{id}` returns fields + posts; 400 on bad enum, 413 on oversized image.

## Out of scope (stated)

Dark mode toggle; animated avatars/banners (deferred to the video/animation transcode pipeline); custom-hex accents (v1 is the curated palette); Stories; community personas; calls. The proprietary/stylized QR remains deferred to a native-camera-client milestone; the functional standard QR stays as-is.

## Deviations from real Loop (unchanged from prior slices)

Copy-paste still stands in for QR camera scan; localhost for onion address; plaintext TCP for Tor. This slice changes look + adds profiles; it does not touch transport.
