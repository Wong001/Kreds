# Phone Reachability — Onion Service + Revocation Parity (Arc 2) — Design

Date: 2026-07-23. Arc 2 of phone-as-full-node. Combined slice (decided with
August): the security gate (revocation/defriend parity) AND the reachability
goal (Tor onion) in one spec, sliced into tasks, onion spike first to
de-risk. Self-revocation triggers a FULL WIPE to First-Load (folds in the
revocation half of slice C — August approved the destructive reset).

## Context

Arc 1 (merged, PR #19) gave the phone the RESPONDER half of `_session` — it
can answer a sync — but bound to `127.0.0.1` only. Two things block real
reachability:
1. **The phone has never processed a revocation or defriend on ANY path.**
   `knownIdentities` is add-only (no `removeIdentity` exists); no per-device
   revoked flag; `SelfRevoked` is detected but takes NO local action. Latent
   today (the phone only dials a desktop that enforces everything); LIVE the
   moment an onion makes the phone reachable — a defriended friend or a
   revoked device could dial the phone's `.onion` and be served. The arc-1
   whole-branch review named closing this a BLOCKING precondition.
2. **No onion service.** `TorEngine` dials out only; `ControlPort` does
   cookie-auth + reply-reading for bootstrap but has no `ADD_ONION`. Unproven
   on tor-android (Brick A only dialed out).

## Part A — Revocation & defriend parity (the security gate)

Mirror hearth line-for-line into the phone's Kotlin (the phone lacks a peer
table + a full DeviceView-revocation model, so it's a faithful SUBSET).

1. **Store primitives** (`SyncStore`/`SqliteSyncStore`/`InMemorySyncStore`):
   - `removeIdentity(id)` (mirror `store.py:162`) + `purgeAuthoredBy(id)`
     (mirror `store.py:1036` — tombstone all messages authored by id) so a
     defriend makes `knownIdentities.contains(id) → false`.
   - A per-device revoked set (a `revokedDevices` table/set keyed by
     device_pub, OR a revocation field on the deviceViews rows) — the phone
     has no `DeviceView.revocation` today.
   - `getMeta(k)`/`setMeta(k,v)` (absent from `SyncStore` entirely) — Part B
     needs it for the onion key + `gossip_addr`; add here.
2. **Ingest inbound REVOCATIONS** on BOTH `KotlinSync.run` (outbound dial)
   and `KotlinSync.serve` (inbound) — stop discarding (KotlinSync.kt:220-227
   run / :364-381 serve). Mirror `store.ingest_revocation` (store.py:410-430):
   verify the RevocationCert against the identity's views, mark the device
   revoked, retro-drop (tombstone) that device's messages past
   `last_valid_seq`. RevocationCert verify = port hearth's
   `Verifier.process_revocation` (identity.py) — a self-authenticating cert
   signed by the identity key.
3. **Apply inbound DEFRIENDS** on both paths — mirror
   `node.apply_defriend_notice` (node.py:1746-1780), the SUBSET the phone
   supports: `notice.target == ownIdentity` && `notice.author != ownIdentity`
   (self-author guard) && `notice.verify()` && `isKnown(author)` →
   `purgeAuthoredBy(author)` + `removeIdentity(author)`. (Skip the peer-table/
   device-views/disconnected cleanup hearth does — the phone has no peer
   table; note it. `add_disconnected` equivalent optional/deferred.)
4. **Post-AUTH revoked-device refusal** in `KotlinHandshake.respondHandshake`
   — mirror sync.py:637-641: after AUTH, for a non-self peer, if the peer's
   device_pub is in our revoked set → write `{"t":"refused"}` + Failed. This
   closes the arc-1 blocking finding. (Also apply the mid-session defriend/
   not-known re-check hearth does at sync.py:741-758 — after DEFRIENDS, if
   `!isKnown(peerIdentity)` or the peer device is now revoked, end the
   session before HAVE/MESSAGES.)
5. **Self-revoke → full wipe** (`enterRevokedState`, the Kotlin analog of
   node.py:3144): when an ingested revocation (either sync path) names OUR
   OWN device_pub, the phone must permanently retire: wipe `pairing.json`
   (PairingStore) INCLUDING device_priv + identity_priv, delete/clear the
   SQLite store + blob caches, stop `TorNodeService`, and signal the RN layer
   so `hasIdentity()` → false → the First-Load screen. This is the
   destructive reset August approved. It must be idempotent + safe mid-sync
   (stop the session, don't half-serve). NOTE: the phone has no App-lock/
   paper-seed today, so the seed-restoration hearth does (node.py:3158+) is
   n/a — the identity survives on the user's OTHER devices (the desktop),
   which is the whole point of revoking one device.

## Part B — Onion publish (reachability)

6. **`ControlPort.addOnion(virtualPort, targetPort, keyBlob?)`** — a new
   method: `AUTHENTICATE` (cookie, as bootstrap does), send
   `ADD_ONION NEW:ED25519-V3 Flags=Detach Port=<virtual>,127.0.0.1:<target>`
   (or `ADD_ONION <keyBlob> Flags=Detach Port=...` on republish), read the
   multi-line `250-ServiceID=… / 250-PrivateKey=… / 250 OK` reply and parse
   `KEY=VALUE` fields (port hearth `_parse_control_reply` tor.py:122-131 +
   `publish_onion` tor.py:325-345). `Flags=Detach` is REQUIRED so the onion
   survives the control connection closing (ControlPort is per-call). Returns
   `(serviceId, keyBlob)`. **TASK 1 = the spike**: prove this works on the
   G20 and the desktop can dial the resulting `.onion` → arc-1 GossipServer
   answers over Tor. De-risk before investing in the rest.
7. **Persist the onion key + `gossip_addr`** via `getMeta`/`setMeta` (Part A):
   store the returned `keyBlob` so every launch republishes the SAME onion
   identity (stable `.onion`); store `gossip_addr = "<serviceId>.onion:9997"`.
   `ONION_VIRTUAL_PORT = 9997` fixed forever (tor.py:41 — re-picking it once
   deadlocked every node).
8. **Wire into `TorNodeService`:** after Tor bootstrap completes AND
   `GossipServer.start()` has returned (its ephemeral `boundPort` is known),
   call `ControlPort.addOnion(9997, boundPort, savedKeyBlob)`; persist key +
   gossip_addr. Re-issue on every restart (fresh boundPort, same key → same
   `.onion`). Sequencing is load-bearing: `ADD_ONION` needs the target port
   up front, and `GossipServer` gets a fresh ephemeral port each start.
9. **Advertise the real address:** `serve()`/`run()` send the real
   `gossip_addr` in HAVE (arc 1 sent `""`), so the desktop's
   `_merge_peer_address` (sync.py:773-774) learns the phone's `.onion` and
   can DIAL BACK — the bidirectional link that makes the phone a reachable
   node.

## Testing / gates

- **hearth pytest:** unaffected (no hearth changes — the phone mirrors, hearth
  is the reference). Confirm green.
- **Kotlin JVM:** revocation ingest (verify + retro-drop + revoked-device
  refusal), defriend apply (removeIdentity → isKnown false → serve() refuses
  that peer next dial — the OVER-SERVE-STOPS negative), self-revoke wipe
  (enterRevokedState clears pairing.json + store; hasIdentity→false), the
  post-AUTH revoked refusal in respondHandshake, ControlPort.addOnion reply
  parsing (against scripted control-port replies — pin the ADD_ONION reply
  shape from hearth), gossip_addr in HAVE.
- **Loopback gate (extend the arc-1 SyncServeLoopbackTest):** a real hearth
  node that has REVOKED the phone's peer device dials → refused post-AUTH; a
  node carrying a DEFRIEND notice for the phone → after the phone applies it,
  the phone serves that identity NOTHING on the next round (removeIdentity
  took effect). Prove the gate at the real wire.
- **Onion spike (Task 1, on-device):** ADD_ONION on the G20 returns a
  serviceId; the DESKTOP dials `<serviceId>.onion:9997` over Tor and the
  phone's GossipServer completes a sync. This is the FIRST real Tor
  reachability of the whole phone effort.
- **On-device DoD (August drives):** (a) phone publishes a stable `.onion`
  (same across app restarts); (b) desktop learns it (via the phone's HAVE)
  and dials the phone over Tor → sync completes, content flows desktop→phone
  without the phone polling; (c) revoke the phone's device from the desktop →
  the phone WIPES to First-Load on its next sync; (d) a defriend → the phone
  stops serving that identity; (e) regression: loopback + outbound sync still
  work.

## Honest limits

- Arc 2 proves reachability with THE DESKTOP (own sibling device) dialing the
  phone. FRIENDS dialing the phone directly is arc 3 — own-device onion
  addresses don't propagate to friends via gossip today (a friend peer table
  + address advertisement to friends is arc 3).
- The both-offline store-and-forward problem is untouched (arc 4).
- The nudge/liveness channel folds onto this next: once the desktop can dial
  the phone, a held connection + push is the small remaining step.
- Self-revoke wipe is irreversible on the phone by design (re-pair to rejoin);
  the identity survives on the desktop. The separate failed-unlock panic-wipe
  (August's other slice-C idea, tied to at-rest App-lock) stays a future
  feature — not this arc.
- Onion-service viability on tor-android is proven by Task 1 or the arc is
  BLOCKED there (report honestly; the revocation parity work is still valuable
  for arc 3 regardless).

## Out of scope (later arcs)

Friend peering + address propagation to friends (arc 3); store-and-forward /
relay (arc 4); the nudge channel (folds on next); App-lock + failed-unlock
panic-wipe; the phone gossiping OTHER nodes' addresses (peer-table relay).
