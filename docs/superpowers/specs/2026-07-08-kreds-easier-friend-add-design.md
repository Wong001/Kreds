# Kreds Easier Friend-Add (auto-deliver over Tor + expiring invite) — Design

**Date:** 2026-07-08
**Status:** Approved (design discussion, this session)
**Branch:** `kreds-easier-friend-add` off `main`
**Version:** bump `hearth.__version__` `0.2.0` -> `0.3.0` (a CORE change — ships as the first real auto-update swap-on-restart test with Josh).

**Basis:** the existing 4-message friend ceremony in `hearth/node.py` — `create_invite()` (A: cert + `.onion` addr + fresh `nonce`; stores `_pending_invites[nonce]`), `respond_to_invite(invite)` (B: verifies A's cert, stores `(cert_A, addr_A)` under `nonce_B` in `_pending_responses`, returns a response = B's cert+addr, echoes `nonce`, signs `_friend_add_body(nonce)`, includes `peer_nonce=nonce_B`), `finalize_invite(response)` (A: requires `nonce in _pending_invites`, verifies B's sig, **adds B**, returns a final signed over `nonce_B`), `complete_invite(final)` (B: verifies A's sig over `nonce_B`, **adds A**). Transport: `hearth/sync.py` `_on_conn`/`_session` run a hello->**friend-auth** handshake (known friends only); `sync_with(address)` dials via `hearth/transport.py` `connect` + `read_frame`/`write_frame`.

## Why

Today's friend-add is a ~4-step manual copy-paste back-and-forth (A code -> B paste -> B code -> A paste). It's the first thing a new user does and it's clunky. The crypto is sound and stays **untouched** — we only (1) change the *transport* of steps 2-4 from manual copy-paste to automatic delivery over Tor, so it becomes "A shares one code, B pastes it, done," and (2) make the invite code **ephemeral** (single-use, single-active, 10-min expiry) so a code can't leak or linger.

## Key insight

The nonce in A's invite (shared in person) is the capability that authenticates B to A; the nonce in B's response authenticates A to B; both are signed. **None of that changes.** We keep every nonce + signature check and only automate how steps 2-4 travel: once B has A's invite, B knows A's `.onion` address, so B can *deliver* its response to A over Tor and get the final back — no second/third/fourth manual paste.

## Components

### 1. Expiring, single-use, single-active invite (`hearth/node.py`)

- `create_invite(ttl_seconds=600)`: **purge** expired + any prior unused pending invite first (single active code — generating a new one kills the old), then store `_pending_invites[nonce] = expiry` where `expiry = time.time() + ttl` (was `True`). Return the invite JSON **plus** `expires_at` (so the UI can count down). 10-minute default.
- `finalize_invite(response)` (and the Tor handler below): after the existing `nonce in _pending_invites` check, **reject if expired** (`time.time() >= expiry`) and delete the nonce (`raise ValueError("invite expired")`). Single-use is already enforced (the nonce is deleted on success).
- Wall-clock `time.time()` is fine for a 10-min TTL (no monotonic/sleep concern at this scale). No background sweeper — expiry is checked lazily on use and swept on the next `create_invite`.
- `_pending_responses` entries (B's side, awaiting the final) also get a TTL + purge, so a stale half-add doesn't linger.

### 2. Auto-delivery over Tor (`hearth/node.py` + `hearth/sync.py`)

- **New pre-friend handshake frame.** In `sync.py`, branch on the first frame's type *before* the friend-auth session: a frame `{"t": "friend-add", "payload": <response-json>}` is dispatched to a node handler (NOT the friend-auth path — they're not friends yet). The handler runs `node.finalize_invite(payload)`; on success it replies `{"t": "friend-final", "payload": <final-json>}` and closes; on failure (`no matching invite` / `expired` / bad sig) it replies `{"t": "refused"}`. Authentication is entirely via the invite nonce + signatures inside `finalize_invite` — a dial from a random `.onion` with no valid pending nonce is refused. **Rate-limit** inbound `friend-add` frames (cheap reject; a small per-window cap) so a stranger can't spam finalize attempts.
- **Dialer helper** `sync.deliver_friend_add(address, response_json) -> final_json | None`: dial `address` via `transport.connect`, send the `friend-add` frame, read the reply; return the final payload on `friend-final`, else `None` (refused/unreachable/timeout).
- **Orchestration** `node.add_friend_via_invite(invite_json) -> dict`: `resp = respond_to_invite(invite)` (builds B's response, stores the pending-response with TTL, learns A's addr); then `final = await deliver_friend_add(addr_A, resp)`; if `final`: `complete_invite(final)` -> `{"status": "connected", "friend": <name/id>}`; if `None` (A offline / unreachable): `{"status": "manual", "response": resp}` — the caller shows the response code for the **manual fallback = exactly today's flow** (A pastes B's response -> `finalize` returns the final code -> A gives it to B -> B `complete`s). No auto-delivery in the fallback — that's the point of the fallback.
- **A's side is automatic:** A's node receives B's `friend-add` over Tor, finalizes (adds B), replies the final. A never pastes anything. A's UI updates via the existing WS "friends changed" notify so Josh appears live.

### 3. API (`hearth/api.py`)

- `POST /api/friend/invite` -> `{payload, expires_at}` (create_invite; now with expiry).
- `POST /api/friend/add {payload: <A's invite>}` -> runs `add_friend_via_invite`; returns `{status:"connected", friend}` or `{status:"manual", response}` (offline fallback) or a 400 on a bad/expired invite. (Runs the blocking Tor dial via `asyncio.to_thread` / the async node path so it doesn't block the loop.)
- Keep `POST /api/friend/respond|finalize|complete` for the **manual fallback** path (unchanged crypto; finalize now enforces expiry).
- All friend endpoints stay behind the app-lock guard (not in the locked allowlist).

### 4. Web UI (`hearth/web`)

- **A ("Add friend" -> Share):** shows the invite code (copy button) + a live **"expires in MM:SS"** countdown; at 0 -> "Code expired" + a **Regenerate** button (re-calls `/friend/invite`). A stays on this screen; when B connects, the friends list updates live (WS) and A sees "Josh added you."
- **B ("Add friend" -> Enter code):** pastes A's code -> "Connecting over Tor…" -> **connected** ("You're now friends with Wong") OR, on the offline fallback, "Wong seems offline — send them this code:" showing the response for A to paste (degrades to today's flow). Keyboard-accessible; notes via `textContent` (no innerHTML of user data), consistent with the existing client.
- The current multi-box manual ceremony UI is kept as the fallback, not the default.

## Security (unchanged boundary + the additions)

- The in-person capability (A's invite nonce) is still what authorizes the add. `finalize_invite` accepts a response ONLY for a nonce A actually created, not expired, not already used, with a valid signature over `_friend_add_body(nonce)` by B's cert. Auto-delivery changes the *transport*, not this check.
- The pre-friend `friend-add` frame is exempt from friend-auth (by necessity) but authenticated by the nonce/sig; unmatched/expired/bad-sig -> refused; inbound rate-limited; nonce single-use + 10-min TTL. A random `.onion` cannot add itself (no valid pending nonce).
- No new plaintext secrets on disk; the response/final are ephemeral in-memory. App-lock guard unchanged.

## Testing

Claude-owned (crypto + protocol logic — test hard; reuse the existing loopback/two-node sync test harness, no real Tor needed):
- **Expiry:** an expired invite nonce -> `finalize`/handler reject (ValueError), nonce purged; `create_invite` purges the prior unused invite (single active) + expired ones; `expires_at` returned.
- **Auto-delivery happy path:** two in-process nodes over the loopback transport — A `create_invite`; B `add_friend_via_invite(invite)` -> B delivers the `friend-add` frame -> A finalizes (adds B) -> returns final -> B completes (adds A); assert **both** now list each other as friends, status `connected`.
- **Offline fallback:** `deliver_friend_add` to an unreachable address returns `None` -> `add_friend_via_invite` returns `{status:"manual", response}`; the manual `respond/finalize/complete` path still completes the add (unchanged).
- **Security:** a `friend-add` frame with no matching pending nonce -> `refused`, no friend added; an expired nonce -> refused; a bad signature -> refused; the rate-limit rejects a burst of inbound friend-add frames; a normal sync hello still runs the friend-auth session (the new branch doesn't break gossip between existing friends).
- `/api/friend/add` -> connected / manual / 400 (bad/expired); friend endpoints blocked while app-locked.
- Web-asset test: the new Add-friend UI markup (share+countdown / enter-code) present; `node --check` clean.
- Full suite green; the existing friend/sync/e2e tests unaffected.

August-owned (real 2-machine, packaged app): A shares one code, B pastes it, they **auto-connect over Tor** (A never pastes); the offline fallback when one is closed; the countdown + regenerate; and this whole feature delivered to Josh as the **0.3.0 core auto-update** (swap-on-restart).

## Out of scope (named)

- QR / camera scanning (with the iOS app). Shortening the invite code / shortlinks. Add-by-directory or any server-brokered discovery (there is no server — in-person capability is the security model). Contact verification UI (safety-number compare) — later. Re-keying / rotating an established friendship.

## Success criteria

- Friend-add is "A shares one code, B pastes it, done" when both are online (B auto-delivers its half over Tor, A finalizes automatically, both end up mutual friends), with a graceful copy-paste fallback when the other side is offline; the invite code is single-use, single-active, and expires after 10 minutes (leaked/lingering codes die); every existing nonce/signature authentication check is preserved and a random `.onion` cannot add itself; the pre-friend handshake is rate-limited; app-lock/gossip/existing friend paths are unaffected; full suite + `node --check` green; ships as `0.3.0`.
