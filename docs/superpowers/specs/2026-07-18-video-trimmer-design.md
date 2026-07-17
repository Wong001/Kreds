# Video editor: trim + crop + cover (design, 2026-07-18)

Today a >15s video is rejected outright (`videogate.py:66-68`) and the
user is told to edit it elsewhere — while two pieces of shipped copy
already promise better (the story error's "In-app trimming is coming
soon", app.js:2202, and the wall composer's false "will be trimmed to
the story rules on post", app.js:2058). This slice builds the promised
editor: pick any video, choose a ≤15s window, crop it to an aspect,
pick a cover frame, post.

## Decisions (August, 2026-07-18)

- **Surfaces:** stories + wall/profile posts — the two places video
  exists. DM video and a journal-composer video picker stay out of
  scope (DM video is its own product/protocol slice).
- **Length cap:** 15s stays, both surfaces. Revisit after this ships
  (longer caps / audio are gated on chunked blob transfer,
  ROADMAP items).
- **Editing scope:** trim + cover frame + crop.
- **Crop model:** aspect presets (Original / 1:1 / 9:16 / 16:9) with
  drag-to-pan and wheel/pinch-zoom under a fixed crop frame. No
  free-form rectangle.

## Architecture — client simulates, the node executes

There is no server in Kreds; "processing" happens on the user's own
node, the local Python process the UI already talks to over loopback.

1. The UI collects edit choices against a local `<video>` preview —
   it never touches video pixels for real (no ffmpeg.wasm, no
   MediaRecorder re-encode; both considered and rejected: heavy or
   lossy, and the node must re-transcode anyway).
2. On Post, the client uploads the **original file + edit parameters**
   to its own node over loopback (the original never leaves the
   machine at this step, so size is nearly free).
3. The node's bundled ffmpeg cuts/crops/transcodes through the
   existing videogate, producing the ≤5 MB MP4 + poster.
4. Only those node-produced bytes gossip to friends over Tor. The
   trust property is unchanged and worth restating in its honest form:
   **friends' nodes only ever render video bytes a Kreds videogate
   produced** — the browser preview is cosmetic.

Known consideration (not designed for now): once home nodes exist, the
web UI may run on a different machine than the node, and uploading a
full original crosses a real network. Flagged for the home-node slice.

## Editor UI (one shared modal, both composers)

Picking a video in the wall composer or the story "+" tile opens a
fullscreen editor modal (lightbox-family styling) instead of
immediately accepting the file.

- **Preview:** muted `<video>` of the local file (object URL),
  loop-playing the selected trim window (timeupdate wraps playback
  back to the window start).
- **Timeline:** a filmstrip strip built client-side (seek the video,
  draw small frames to a canvas — local file, same-origin, no
  taint) with two drag handles. Window constrained to ≤15.0s and
  > 0s; times labeled (start / end / window length). Handles snap the
  playhead so the user sees the exact boundary frame while dragging.
- **Aspect chips:** Original / 1:1 / 9:16 / 16:9. Picking a non-native
  aspect shows a fixed crop frame; the video pans (pointer drag) and
  zooms (wheel; pinch on touch) beneath it via CSS transform. Original
  = no crop. Zoom is clamped so the crop frame is always fully
  covered by video (no letterboxing lies).
- **Cover:** a marker on the timeline, draggable within the trim
  window; default = window start. The marker seeks the preview so the
  user sees the exact poster frame.
- **Done / Cancel:** Done returns to the composer, which shows the
  normal video preview plus a small "trimmed · cropped" badge
  (whichever apply) and keeps the edit params alongside the File.
  Cancel discards the pick entirely. Re-opening the editor (click the
  preview) restores current choices.
- **Short videos too:** a ≤15s video still opens the editor (crop and
  cover are useful regardless); its full length is the default window.
- **Degraded mode:** if the browser engine can't decode the file for
  preview (e.g. HEVC in some WebView2 installs), the editor still
  opens if duration metadata is readable: trim via plain slider +
  numeric times, no filmstrip, crop disabled (can't position blind),
  cover defaults to window start. If even metadata fails, fall back to
  today's behavior (post raw, node validates). The node gate accepts
  either way — client capability never gates what the node can do.

## Wire format

One new optional multipart field on **`/api/post`** and **`/api/story`**:

```
video_edit = {"start": float_seconds,          # into the source
              "duration": float_seconds,        # 0 < d <= 15.0
              "crop": {"x": f, "y": f, "w": f, "h": f} | null,
              "poster_t": float_seconds}        # into the CUT, 0 <= t <= duration
```

- `crop` is normalized to the source frame (0..1, x+w<=1, y+h<=1),
  null/absent = no crop. Minimum crop size 0.1 in each axis.
- Rotation: `crop` is relative to the **display-oriented** frame (what
  the user saw). Both the browser `<video>` and ffmpeg auto-rotate
  per metadata by default, so the two agree; the build must not
  disable ffmpeg autorotate, and a portrait-phone-video case is part
  of the test matrix (classic mismatch bug).
- Node-side validation rejects with clean 400s: bad JSON, duration
  over 15s or non-positive, crop out of bounds/too small, poster_t
  outside the window, start beyond source end (caught by ffmpeg cut
  producing an empty/short output — see gate section).
- `video_edit` without a video field is a 400.
- **Story raw-cap fix rides along:** `/api/story` currently checks all
  media against the 50 MB image cap (api.py:622); non-image media now
  gets `MAX_VIDEO_UPLOAD` (100 MB), matching `/api/post` video. The
  image-magic sniff moves to a shared helper both api.py and node.py
  use (it lives in node.py today).

## Node gate changes (`hearth/videogate.py`)

`transcode_video(data, edit=None)`:

- **No edit → byte-identical to today** (same probe, same reject at
  >15s source, same single-pass args, poster from frame 1). Every
  existing caller/test stays valid; old web clients keep working.
- **With edit:**
  - The >15s check applies to the **cut duration**, not the source —
    a 3-minute source with a 12s window is the point. Source duration
    is still probed to validate `start < source_end`.
  - Cut+crop+transcode in one ffmpeg pass: `-ss start -t duration`
    before `-i` (fast keyframe seek; frame-accurate on output since we
    re-encode), filter chain `crop=<pixels from normalized rect, even
    dimensions>` then the existing
    `scale=w=-2:h=min(720,trunc(ih/2)*2)` and the exact current
    encode flags (`-an -c:v libx264 -pix_fmt yuv420p -crf 28
    -maxrate 2500k -bufsize 5000k -preset veryfast
    -movflags +faststart`).
  - Output probed for sanity: a cut that produced nothing (start past
    end) fails with a clear ValueError, not a 0-byte blob.
  - **Poster** extracted from the cut output at `-ss poster_t`
    (clamped to the last frame) instead of frame 1, then through the
    existing image gate at 1080 — unchanged pipeline after that.
  - 5 MB output ceiling and 60s subprocess timeout unchanged. A 15s
    window at current flags lands well under both; the cut happens
    before decode-heavy work so long sources don't blow the timeout —
    verify with a large-source test during build, bump `_TIMEOUT` only
    if measurement demands it.

## Copy fixes (become true statements)

- Wall composer preview note: describe reality — "opens the editor;
  posts the trimmed clip" (final wording August's, per the voice rule
  it's UI copy shipped in-app so Claude drafts, August may reword).
- Story >15s error path: with the editor in front of every pick, the
  node's "longer than 15 seconds" 400 becomes a shouldn't-happen
  (degraded-mode raw posts excepted) — keep the alert but drop the
  "coming soon" promise.

## Mixed versions

- New web + old core: old `/api/post` ignores the unknown field and
  rejects long sources — confusing. Release with `min_core_for_web`
  set to this version (two-step update, same as 0.3.12).
- Old web + new core: `video_edit` absent → identical behavior. Safe.

## Testing

- **videogate unit tests** with ffmpeg-synthesized sources (`testsrc`
  color bars, various lengths/resolutions/aspects; no fixture binaries
  in the repo): cut duration lands within tolerance of requested;
  crop output dimensions match the normalized rect (even-rounded);
  poster bytes differ frame-1 vs poster_t; no-edit path unchanged
  (existing tests keep passing untouched); rejects — window >15s,
  start past end, crop out of bounds, non-video bytes.
- **API tests:** `video_edit` validation matrix on both endpoints;
  story video >50 MB now accepted up to 100 MB (and >100 rejected).
- **UI smoke (UI_E2E=1):** compose flow driving the real editor —
  pick a generated 30s video, drag the window to ~10s, pick 1:1, drag
  the cover marker, post; assert via the node store + a duration probe
  of the stored blob that the synced artifact is ~10s, square, with
  the chosen poster. Story leg and wall leg.
- **Degraded mode:** unit-level (force the no-decode branch) rather
  than hunting a codec Chromium can't decode in CI.

## Out of scope (named, not implied)

DM video; journal-composer video picker; caps beyond 15s; audio;
chunked/streamed blob transfer; ffmpeg.wasm; native camera capture;
home-node remote-upload optimization; free-form crop.
