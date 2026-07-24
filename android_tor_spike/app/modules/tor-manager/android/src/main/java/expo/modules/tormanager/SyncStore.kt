package expo.modules.tormanager

import java.security.MessageDigest

data class SyncStats(val messages: Int, val blobs: Int, val identities: Int)

/** A stored message's fields DecryptPass (Task 5, B.2) needs: msgId + kind
 *  (both already columns/derived in both store impls) plus payload and
 *  identityPub -- the message's AUTHOR (cert.identity_pub). identityPub is
 *  required, not merely convenient: postAad/dmAad's `from` parameter is the
 *  author, and hearth's own reference (node.py's `_content_key`) reads it
 *  from `msg.cert.identity_pub`, NOT from any field inside the payload --
 *  a real post/dm payload carries no "from"/"author" key at all. */
data class StoredMsg(val msgId: String, val kind: String, val identityPub: String, val payload: Map<String, Any?>)

/** A stored, unexpired KIND_STORY row, shaped for the module/UI (B.2d-3
 *  Task 1). `author` is the stored identity_pub (the cert's, same
 *  provenance as StoredMsg.identityPub -- a story payload carries no
 *  "author"/"from" field of its own). `media`/`poster` are blob hashes
 *  (poster is null for photo stories, or when a video story has none);
 *  `mediaKind` is the "photo"|"video" discriminator. Stories are PLAINTEXT
 *  (see hearth messages.make_story / node.py _content_key returning
 *  (None, None) for KIND_STORY) -- no content-key/decrypt step applies. */
data class StoredStory(
    val msgId: String, val author: String, val mediaKind: String,
    val media: String, val poster: String?, val caption: String, val createdAt: Double)

/** Latest-wins profile-layout view (vp3 slice 3): the subset of a
 *  KIND_PROFILE_LAYOUT payload the wall renderer actually consumes.
 *  order/grids are intentionally dropped (legacy, unused by rendering).
 *  Empty maps (not null) when the identity has never published a layout,
 *  so wall assembly can always index without a null guard. pins/spans/texts
 *  are msgId -> a sub-map (pins: {x,y,w,h}; spans: {w,h}; texts: a subset of
 *  {h,v,size,font,weight,style,color}); sizes is msgId -> "small"|"wide"|"full". */
data class ProfileLayout(
    val pins: Map<String, Map<String, Any?>>,
    val spans: Map<String, Map<String, Any?>>,
    val sizes: Map<String, String>,
    val texts: Map<String, Map<String, Any?>>)

/** One stored dialable peer (friend-peering Task 1; mirrors hearth
 *  store.py's `peers` table, store.py:39-40: `address TEXT PRIMARY KEY,
 *  identity_pub TEXT`). `identityPub` is null when only the address is
 *  known so far (e.g. before the peer's identity has been confirmed). */
data class Peer(val address: String, val identityPub: String?)

/** Parse a layout payload's pins/spans/texts field (a JSON object of
 *  msgId -> sub-object) into a plain Kotlin map, dropping any entry whose
 *  key isn't a String or whose value isn't itself a map. Shared by both
 *  store impls (module-`internal` so SqliteSyncStore, a separate file, can
 *  use it); both feed it already-plain Kotlin maps (InMemory: native
 *  payloads; SQLite: jsonToMap output), so a plain `as?` cast is enough. */
@Suppress("UNCHECKED_CAST")
internal fun layoutSubMaps(v: Any?): Map<String, Map<String, Any?>> {
    val m = v as? Map<*, *> ?: return emptyMap()
    val out = linkedMapOf<String, Map<String, Any?>>()
    for ((k, sub) in m) {
        val key = k as? String ?: continue
        val subMap = sub as? Map<String, Any?> ?: continue
        out[key] = subMap
    }
    return out
}

/** Parse a layout payload's `sizes` field (msgId -> "small"|"wide"|"full")
 *  into a plain String map, dropping non-String keys/values. */
internal fun layoutSizes(v: Any?): Map<String, String> {
    val m = v as? Map<*, *> ?: return emptyMap()
    val out = linkedMapOf<String, String>()
    for ((k, sv) in m) {
        val key = k as? String ?: continue
        val value = sv as? String ?: continue
        out[key] = value
    }
    return out
}

interface SyncStore {
    fun summary(): Map<String, Map<String, Map<String, Any>>>
    fun knownIdentities(): List<String>
    fun addIdentity(id: String)
    /** Drops `id` from known identities (phone-onion-reachability Task 2;
     *  mirrors hearth store.py:162 remove_identity's plain `DELETE FROM
     *  identities WHERE identity_pub=?` -- no cascading side effects here,
     *  same as the hearth original). Does NOT itself touch any already-
     *  stored message authored by `id` -- that is purgeAuthoredBy's job
     *  (hearth's own defriend path calls both, e.g. node.py's unfriend flow
     *  calls remove_identity AND purge_authored_by separately). A caller
     *  that re-derives its `messagesNotIn` `entitled` set from
     *  knownIdentities() (as real entitlement computation does) will
     *  naturally stop serving `id`'s messages after this call, since `id`
     *  has fallen out of that derived set -- but messagesNotIn itself does
     *  not consult knownIdentities(), only the `entitled` parameter it is
     *  given. Removing an identity that was never known is a no-op. */
    fun removeIdentity(id: String)
    /** Deletes every stored message authored by `id` (identity_pub == id),
     *  returning the count of rows removed (phone-onion-reachability Task
     *  2; mirrors hearth store.py:1036 purge_authored_by). hearth's
     *  purge_authored_by performs a REAL delete, not a tombstone insert
     *  (unlike _tombstone, which both impls' analog below also uses for
     *  markRevoked's retro-drop) -- and additionally cleans its `dm_keys`/
     *  `undecryptable` side tables, which have no phone-store analog (this
     *  store has neither table), so there is nothing extra to clean here.
     *  After this call, messagesNotIn can no longer serve any of `id`'s
     *  (now-deleted) messages regardless of what `entitled` set a caller
     *  passes -- unlike removeIdentity above, this does not depend on the
     *  caller re-deriving `entitled` from knownIdentities(), because the
     *  give-side scan reads directly off the underlying message rows,
     *  which are now gone. */
    fun purgeAuthoredBy(id: String): Int
    /** Marks `devicePub` revoked as of `lastValidSeq`, and retro-drops
     *  (deletes) that device's already-stored messages with `seq >
     *  lastValidSeq` (phone-onion-reachability Task 2; mirrors the retro-
     *  drop loop inside hearth's store.ingest_revocation, store.py:421-427
     *  -- `SELECT msg_id FROM messages WHERE device_pub=? AND seq>?`, each
     *  then tombstoned). Messages with `seq <= lastValidSeq` are kept: they
     *  were validly signed before the revocation took effect, matching
     *  hearth's own boundary (the retro-drop query is a strict `seq>?`,
     *  not `seq>=?`). hearth tombstones (INSERT INTO tombstones + DELETE
     *  FROM messages) because it has a tombstones table to gossip the
     *  deletion to peers from; this store has no such table (same posture
     *  as purgeAuthoredBy above), so this deletes the rows outright. A
     *  second call for the SAME devicePub overwrites the stored
     *  lastValidSeq (latest call wins, like a plain per-device row) and
     *  retro-drops again against the NEW threshold -- this is a snapshot
     *  of "revoked as of seq X", not an accumulating revocation log. This
     *  task provides only the store primitive; RevocationCert parsing and
     *  wire ingest are Task 3. */
    fun markRevoked(devicePub: String, lastValidSeq: Int)
    /** True if `devicePub` has ever been passed to markRevoked (phone-
     *  onion-reachability Task 2). A pure membership check against the
     *  revoked-device set -- it does NOT compare against any particular
     *  seq (the seq gate is applied once, at markRevoked's retro-drop
     *  time, not evaluated again here). False for a device that was never
     *  revoked. */
    fun isRevokedDevice(devicePub: String): Boolean
    /** Arbitrary key/value store (phone-onion-reachability Task 2; mirrors
     *  hearth store.py:121-133's `meta` table / set_meta/get_meta). Task 3's
     *  wire-ingest layer will use this for onion-reachability bookkeeping
     *  (e.g. tracking revocation-processing progress); this task only
     *  provides the primitive, with no defined keys yet. Null if `k` was
     *  never set. */
    fun getMeta(k: String): String?
    /** Overwrites (not appends) any existing value stored for `k`. */
    fun setMeta(k: String, v: String)
    fun ingestMessage(m: SignedMessage): Boolean
    /** Marks a message as needing to be PUSHED on the next sync (the
     *  outbound task: `Compose.post` calls this immediately after
     *  `ingestMessage(signed)` so a composed post doesn't just sit in local
     *  storage -- see `SyncRunner.runTransport`, which drains this queue
     *  into `KotlinSync.run`'s `outbound` list and clears it only once that
     *  sync completes with `SyncResult.Ok`). Idempotent -- re-queueing an
     *  already-pending msgId must not duplicate it (a caller retrying a
     *  failed sync, or composing twice before a sync runs, must still only
     *  push the message once per queued entry).
     *
     *  `wireDict` (outbound review wave, FIX 1) is the exact
     *  `SignedMessage.toDict()` of the message being queued -- the queue
     *  stores THIS, not a msgId-only row re-derived later from `messages`.
     *  That's deliberate: `messages`' own storage (`msg_json`, written via
     *  `serialize()`/org.json) is lossy for round-tripping an INTEGRAL
     *  `Double` (e.g. `created_at=150.0` reads back as Kotlin `Int` 150,
     *  which `KotlinWire.dumps` then renders `"150"`, not `"150.0"` -- a
     *  canonical-byte mismatch the receiving node's device-signature check
     *  rejects). Storing the wire dict itself at queue time -- via
     *  `KotlinWire.dumps`, the SAME canonical serializer `writeFrameBytes`
     *  uses on the wire -- makes the re-send byte-exact BY CONSTRUCTION, for
     *  any magnitude, not just the large-magnitude production values that
     *  happen to dodge the bug. `msgId` is expected to match `wireDict`'s
     *  own signed content (every real caller passes `signed.msgId()` and
     *  `signed.toDict()` together), but a store impl need not cross-validate
     *  that. */
    fun addPendingOutbound(msgId: String, wireDict: Map<String, Any?>)
    /** The wire dicts (`{cert, seq, payload, signature}` -- the same shape
     *  as `SignedMessage.toDict()`) of every queued-but-not-yet-pushed
     *  message, exactly as passed to `addPendingOutbound` (see its doc for
     *  why the queue stores the wire dict itself rather than re-deriving it
     *  from `messages`' lossy `msg_json`). Pushing a queued message re-
     *  serializes this exact dict over the wire via
     *  `KotlinWire.writeFrameBytes`, and the receiving node's device-
     *  signature check depends on those bytes being canonically identical
     *  to what was signed at compose time (in particular, `created_at`'s
     *  float representation must survive bit-for-bit). Stable insertion
     *  order (oldest-queued first). */
    fun pendingOutbound(): List<Map<String, Any?>>
    /** Removes `msgIds` from the pending-outbound queue -- call only after
     *  a sync that pushed them has completed with `SyncResult.Ok` (a failed
     *  or skipped sync must leave them queued so the next sync retries the
     *  push). The underlying row in `messages` is untouched; this only
     *  clears queue membership. Empty list is a no-op. */
    fun clearPendingOutbound(msgIds: List<String>)
    fun missingBlobs(): List<String>
    fun putBlob(hash: String, data: ByteArray): Boolean
    /** Read counterpart to putBlob (Task 4, B.2d): the stored blob's raw
     *  (still content-key-encrypted -- see KotlinBlobCrypt) bytes for
     *  `hash`, or null if no blob with that hash has ever been stored.
     *  Never throws for a missing hash -- callers (feed photo loading) must
     *  be able to treat "not synced yet" as an ordinary, expected state. */
    fun getBlob(hash: String): ByteArray?
    fun stats(): SyncStats
    /** This device's own X25519 enc keypair (encPrivHex, encPubHex), or null
     *  if none has been generated yet. See EncKeys.getOrCreate. */
    fun getEncKey(): Pair<String, String>?
    fun setEncKey(priv: String, pub: String)
    /** This device's next outbound message seq (Task 4, B.2): the phone
     *  tracks its own seq the same way hearth's DeviceKeys.sign_message does
     *  (identity.py:304-316) -- starts at 1 (seq starts at 0, incremented
     *  BEFORE first use, so the first-ever message is seq=1), and each call
     *  returns the seq to use for the NEXT outbound message while persisting
     *  the following value, so a second call never repeats a seq (a repeat
     *  would be rejected at the peer's seq-reuse gate -- hearth
     *  identity.py:577, Verifier.verify_message). */
    fun nextSeq(): Int
    /** The enc_pub (hex) most recently confirmed PUBLISHED to the home node
     *  (Task 7, B.2) -- i.e. included in an outbound `enckey` message during
     *  a sync that completed with `SyncResult.Ok` -- or null if this device
     *  has never successfully published one. Set only via
     *  `setPublishedEncPub`, after such a sync (see
     *  `TorManagerModule.syncNow` and `EncKeyPublishGuard`). This is
     *  DELIBERATELY separate from `getEncKey`/`setEncKey` (the keypair
     *  itself, Task 3): a device generates its enc keypair once at rest, but
     *  "have I told the node about THIS pub yet" is a fact about network
     *  history, not about the key material, and must survive independently
     *  (e.g. a crash between generating a key and successfully syncing it
     *  must leave this null so the next sync retries the push). */
    fun getPublishedEncPub(): String?
    /** Persists `pub` as the published marker (see `getPublishedEncPub`).
     *  Callers must only call this AFTER a sync that pushed `pub` as the
     *  outbound enckey has completed successfully -- never speculatively
     *  before the push is confirmed accepted. */
    fun setPublishedEncPub(pub: String)
    /** Every stored message (Task 5, B.2 DecryptPass): unfiltered by kind or
     *  author. NOTE (corrected -- a prior version of this comment claimed
     *  the store only ever holds own-identity content; that is FALSE):
     *  KotlinSync's HAVE phase adds every identity the node reports as
     *  `known` (the friend list) via `addIdentity`, and the node's
     *  messages_not_in then serves any message an entitled peer's identity
     *  is owed -- so this store CAN and does hold friend-authored messages
     *  (e.g. a friend-authored wrap_grant, see wrapGrantsFor's doc below).
     *  DecryptPass itself filters to kind post/dm; it does not (this task)
     *  additionally filter by author -- see wrapGrantsFor's doc for why
     *  that distinction matters for grants specifically. */
    fun allMessages(): List<StoredMsg>
    /** The `wraps` maps from every stored wrap_grant message whose `target`
     *  equals msgId AND whose SIGNING identity (the grant message's own
     *  cert.identity_pub) is a MEMBER OF acceptedSigners, ordered OLDEST-TO-
     *  NEWEST by (created_at, seq) -- mirrors hearth's `store.wrap_grants(
     *  target, author)` (store.py:773-790), generalized from a single
     *  author to a caller-chosen signer set (B.2c: DecryptPass's entitlement
     *  rule -- posts accept only the post's author; DMs additionally accept
     *  the DM's recipient, but ONLY when that recipient is our own identity,
     *  covering hearth's `maintain_received_dm_grants` recipient-signed
     *  backfill). Callers fold left-to-right over the (already filtered)
     *  result to prefer the newest grant for a given device (DecryptPass
     *  does this).
     *
     *  The signer filter is NOT optional (correcting an earlier version of
     *  this comment, which wrongly reasoned it could be skipped because
     *  "the store only holds own content"): B.1's sync does not admit only
     *  own-authored messages -- see allMessages' doc above. A hostile
     *  mutual friend, once known, can author `wrap_grant{target: <your own
     *  post>, wraps:{yourDevicePub: crafted}, created_at: <future>}` and
     *  have it synced into this same store. Without this filter, a newest-
     *  wins fold over UNFILTERED grants would prefer that crafted grant
     *  over your real (older) own-authored one; unwrapKey would "succeed"
     *  with the wrong key, decryptBody would then fail AEAD auth, and your
     *  own real post would silently vanish from the feed -- a fail-closed
     *  but real denial-of-render. Requiring `acceptedSigners` as a
     *  parameter (rather than a separate unfiltered accessor) is
     *  deliberate: it stops a caller from accidentally reintroducing this
     *  gap -- the caller must name every identity it trusts, explicitly,
     *  every time. */
    fun wrapGrantsFor(msgId: String, acceptedSigners: Set<String>): List<Map<String, Any?>>
    /** Display-name resolution (B.2c Task 3): identity_pub -> the `name`
     *  field of that identity's LATEST stored KIND_PROFILE message, by
     *  (created_at, seq). KIND_PROFILE payloads are PLAINTEXT (hearth's
     *  `messages.make_profile`, messages.py:104-115 -- signs
     *  {"kind":"profile","name":...,"created_at":...} with no wraps/
     *  body_ct at all), so this needs no decrypt step, unlike post/dm.
     *  Identities with no stored profile message are simply absent from
     *  the returned map -- callers apply their own fallback (DecryptPass:
     *  own identity -> "me", any other identity -> "friend-" +
     *  identityPub.take(8)). No entitlement/signer filtering here: unlike
     *  wrap_grant, a profile message's own author IS its subject (there is
     *  no "whose name is this" ambiguity for a forged sender to exploit --
     *  a message from identity X can only ever claim X's own display name,
     *  gated the same is_known+signature checks every stored message
     *  already passes at ingest). */
    fun profileNames(): Map<String, String>
    /** device_pub -> enc_pub over this identity's KIND_ENCKEY messages,
     *  latest-wins per device by (created_at, seq). Mirrors hearth
     *  store.enckeys, EXCEPT it cannot exclude revoked devices (the Kotlin
     *  store models no revocations) -- a documented outbound limitation. */
    fun enckeys(identityPub: String): Map<String, String>
    /** The set of device_pubs this store has ever held a (verified-at-ingest)
     *  message from, for `identity` (B.2d-4 Task 2). Mirrors hearth's
     *  `store.load_views(identity_pub)` used by node._device_bound: hearth
     *  records a DeviceView for every device it verifies a signature from
     *  (identity.py Verifier.verify_message), and this store only ever stores
     *  a message AFTER verifyDeviceSignature passes (ingestMessage) -- so the
     *  distinct (device_pub) column of `identity`'s stored messages is that
     *  same enrolled-device record, with no extra plumbing.
     *
     *  This is the device-binding source for KotlinResponses' responder
     *  attribution. The production `deviceBound` predicate is
     *  `{ id, dev -> val v = deviceViews(id); v.isEmpty() || dev in v }` --
     *  an EMPTY set is deliberately PERMISSIVE (mirrors _device_bound's
     *  `if not views: return True`: we simply may not have exchanged a
     *  message with that identity yet, which is expected for a public entry
     *  from someone who is only the AUTHOR's mutual friend, not this
     *  viewer's -- not evidence of forgery). A NON-empty set requires
     *  membership: a fabricated key impersonating a known friend won't be in
     *  their view, which is exactly what catches a valid-sig-but-forged
     *  device (sig-alone is insufficient -- an attacker controls both halves
     *  of a sig check on a self-minted keypair). */
    fun deviceViews(identity: String): Set<String>
    /** Unexpired KIND_STORY rows (B.2d-3 Task 1) -- `payload.expires_at as
     *  Number > nowSeconds` (strict: a story whose expires_at == nowSeconds
     *  is treated as already expired, not still-active), newest-first by
     *  `created_at`. Mirrors the design doc's `payload.expires_at > now`
     *  filter -- expiry/GC of the underlying stored message is the
     *  desktop's job; this accessor only decides what the phone SHOWS.
     *  Stories are plaintext (see StoredStory's doc) -- no decrypt step. */
    fun activeStories(nowSeconds: Double): List<StoredStory>
    /** The latest (by created_at, then seq) KIND_PROFILE payload for
     *  `identityPub`, or null if none is stored. PLAINTEXT: hearth's
     *  make_profile signs a plain dict with no wraps/body_ct (messages.py:
     *  104-115), so this is a JSON read, no decrypt -- same provenance basis
     *  as profileNames (a message can only ever claim its own author's
     *  profile). The whole payload is returned (incl. kind/created_at); the
     *  caller selects the display fields it wants. A null return is what
     *  drives the profile route's 404 for an unknown/record-less identity. */
    fun profileRecord(identityPub: String): Map<String, Any?>?
    /** The latest (by created_at, then seq) KIND_PROFILE_LAYOUT for
     *  `identityPub`, reduced to the pins/spans/sizes/texts the wall renderer
     *  uses (order/grids dropped). Empty maps (never null) when never
     *  published. PLAINTEXT (make_profile_layout signs a plain dict). */
    fun profileLayout(identityPub: String): ProfileLayout
    /** album_id -> member msgIds for `identityPub`, latest-wins PER album_id
     *  (by created_at, then seq -- the same per-key newest fold wrapGrantsFor
     *  uses, keyed by album_id here). Empty map when none. PLAINTEXT
     *  (make_album signs a plain dict). */
    fun albums(identityPub: String): Map<String, List<String>>
    /** The stored message with msgId, or null if not found. Used by
     *  ComposeResponse to resolve a target post's author/kind/placement. */
    fun messageById(msgId: String): SignedMessage?
    /** Give-side entitlement filter (gossip server Task 1) -- the exact port
     *  of hearth's store.messages_not_in (store.py:702-750): what THIS
     *  store may serve to `peerIdentity`, given `entitled` (the set of
     *  authors both sides are known to share -- computed by the caller, not
     *  this method) and `summaries` (the peer's own already-seen delta, a
     *  flattened (identity_pub, device_pub) -> SeenSet map -- the SAME
     *  shape `summary()` above would need to be re-decoded into off the
     *  wire, but already-decoded here). For each stored message, ascending
     *  by seq: skip if the author isn't in `entitled`; DM relays only to
     *  (author, recipient); RING is author-private (peerIdentity must equal
     *  the author); POST's audience is its inline `wraps` keys UNIONED with
     *  any wrap_grant devices the POST'S OWN AUTHOR separately signed for it
     *  (author-keyed -- a hostile friend's grant for someone else's post can
     *  never widen that post's audience, see `filterMessagesNotIn`'s
     *  grant-devs doc); WRAP_GRANT/RESPONSE/RESPONSES gate on their own
     *  inline `wraps` keys only, deliberately NO grant-union (spec
     *  2026-07-18: a response is never broadcast to the target post's
     *  audience). Every other kind (profile/story/enckey/album/
     *  profile_layout/delete/...) is unconditionally servable once past the
     *  entitled+seen-delta checks -- store.py's function has no audience
     *  gate for them, so neither does this port. Finally, an entry already
     *  covered by the peer's own summary (`dev.has(seq)`, SeenSet's own
     *  seen-check) is dropped. THIS IS THE SECURITY-CRITICAL BOUNDARY: an
     *  over-serve here leaks private content to a peer who isn't entitled to
     *  it. Both store impls delegate to the shared `filterMessagesNotIn` so
     *  the algorithm cannot drift between them. */
    fun messagesNotIn(summaries: Map<Pair<String, String>, SeenSet>, entitled: Set<String>, peerIdentity: String): List<SignedMessage>
    /** Byte length of each stored blob named in `hashes` (gossip server Task
     *  3's smallest-first blob give order, sync.py:794-815) -- a hash with
     *  no stored blob is simply absent from the result, not zero, so a
     *  caller ranking by size can tell "unknown" apart from "empty blob"
     *  (though an empty blob can't occur in practice -- putBlob's hash gate
     *  ties hash to content). Empty `hashes` returns an empty map without
     *  touching storage. */
    fun blobSizes(hashes: List<String>): Map<String, Long>

    // -- friend-peering Task 1: peer table (addPeer/listPeers/removePeer/
    //    addressFor) -- mirrors hearth store.py's peers table (schema
    //    store.py:39-40; add_peer store.py:217-221; list_peers store.py:
    //    223-227; remove_peer store.py:229-232; address_for store.py:
    //    234-239).

    /** Adds (or updates) a peer address this device knows how to dial
     *  (friend-peering Task 1; mirrors hearth store.py:217-221 add_peer's
     *  `INSERT OR REPLACE INTO peers VALUES(?,?)`). `address` is the peers
     *  table's PRIMARY KEY: a second call for the SAME address overwrites
     *  its identityPub (newest call wins) rather than erroring or
     *  duplicating -- there is at most one row per address. `identityPub`
     *  is null when only the address is known so far (mirrors store.py's
     *  `identity_pub: Optional[str] = None`). */
    fun addPeer(address: String, identityPub: String?)
    /** Every stored peer (friend-peering Task 1; mirrors hearth store.py:
     *  223-227 list_peers). Order is unspecified, same as store.py's own
     *  `SELECT address, identity_pub FROM peers` (no ORDER BY). */
    fun listPeers(): List<Peer>
    /** Drops the peer at `address`, if any (friend-peering Task 1; mirrors
     *  hearth store.py:229-232 remove_peer's plain `DELETE FROM peers WHERE
     *  address=?`). Removing an address that was never added is a no-op. */
    fun removePeer(address: String)
    /** The address of the FIRST stored peer whose identityPub equals
     *  `identityPub`, or null if no peer names that identity (friend-
     *  peering Task 1; mirrors hearth store.py:234-239 address_for's
     *  `SELECT address FROM peers WHERE identity_pub=?` + `fetchone()`).
     *  `address` is the peers table's primary key but `identity_pub` is
     *  not, so more than one address could in principle name the same
     *  identity; like store.py, this returns only the first match, not the
     *  full set. */
    fun addressFor(identityPub: String): String?
}

/** One stored message's fields `filterMessagesNotIn` needs, kept
 *  store-representation-agnostic (a plain Kotlin `payload` Map either way --
 *  InMemorySyncStore's native payload or SqliteSyncStore's `MsgJson`-parsed
 *  one) so BOTH `SyncStore` impls run the IDENTICAL entitlement algorithm
 *  below instead of two hand-maintained copies that could silently drift
 *  apart on this security-critical path. `raw` is the fully reconstructed
 *  `SignedMessage` returned to the caller when this row survives every
 *  gate. */
internal data class GossipRow(
    val msgId: String, val identityPub: String, val devicePub: String,
    val seq: Int, val kind: String, val payload: Map<String, Any?>, val raw: SignedMessage,
)

/** A message payload's `wraps` map, reduced to its device-pub keys (the
 *  audience it names) -- ignores the wrap VALUES entirely (messagesNotIn
 *  never decrypts, only routes). Malformed/missing `wraps` is an empty
 *  audience, not an error. */
internal fun wrapKeys(payload: Map<String, Any?>): Set<String> =
    (payload["wraps"] as? Map<*, *>)?.keys?.filterIsInstance<String>()?.toSet() ?: emptySet()

/** THE per-message audience predicate (friend-peering review fix, Finding 1,
 *  CRITICAL): given one message's `kind`/`payload`/`authorIdentity`/`msgId`,
 *  may `peerIdentity` receive it? Extracted out of `filterMessagesNotIn`'s
 *  own `when(kind)` block so the GIVE side (`filterMessagesNotIn`, below)
 *  and the PUSH side (`SyncRunner.filterPendingForPeer`, which fans the
 *  phone's own composed-but-unsent outbound queue to each dialed peer) share
 *  ONE decision and cannot silently diverge -- before this extraction,
 *  `SyncRunner` had no gate at all on the push path and fanned the WHOLE
 *  pending queue (posts/DMs/responses) to every peer unfiltered: a DM meant
 *  for one friend reached every other friend too, and a reaction/comment
 *  (kind "response") -- whose whole point is responder-anonymity -- was
 *  pushed to every friend as a raw wire dict naming the plaintext target
 *  msg_id, de-anonymizing responder->post attribution.
 *
 *  Exact same rules `filterMessagesNotIn` always enforced (byte-faithful to
 *  hearth's store.messages_not_in, store.py:702-750): DM relays only to
 *  (author, recipient); RING is author-private; POST's audience is its
 *  inline `wraps` keys UNIONED with `grantDevs[authorIdentity to msgId]`
 *  (author-keyed grant union -- see `filterMessagesNotIn`'s own doc on why a
 *  hostile grant naming someone else's post can never widen it);
 *  WRAP_GRANT/RESPONSE/RESPONSES gate on their own inline `wraps` keys only,
 *  deliberately NO grant-union (a response is never broadcast to the target
 *  post's audience). Every other kind (profile/story/enckey/album/
 *  profile_layout/delete/...) is unconditionally allowed -- store.py's
 *  function has no audience gate for them either. */
internal fun peerMayReceive(
    kind: String,
    payload: Map<String, Any?>,
    authorIdentity: String,
    msgId: String,
    peerIdentity: String,
    peerDevices: Set<String>,
    grantDevs: Map<Pair<String, String>, Set<String>>,
): Boolean = when (kind) {
    "dm" -> {
        val recipient = payload["to"] as? String
        peerIdentity == authorIdentity || peerIdentity == recipient
    }
    "ring" -> peerIdentity == authorIdentity
    "post" -> {
        val wr = wrapKeys(payload) + (grantDevs[authorIdentity to msgId] ?: emptySet())
        peerIdentity == authorIdentity || peerDevices.any { it in wr }
    }
    "wrap_grant", "response", "responses" -> {
        val wr = wrapKeys(payload)
        peerIdentity == authorIdentity || peerDevices.any { it in wr }
    }
    else -> true
}

/** The give-side entitlement filter itself (gossip server Task 1) -- the
 *  exact port of hearth's store.messages_not_in (store.py:702-750),
 *  factored out of both `SyncStore` impls so they cannot diverge on this
 *  security-critical boundary. `rows` must already be ordered by seq
 *  ascending (mirrors store.py's `ORDER BY seq ASC`); `peerDevices` must
 *  already be `deviceViews(peerIdentity)` (the caller's job, since both
 *  store impls already have their own `deviceViews`). The audience decision
 *  itself is `peerMayReceive` (above) -- this function's own remaining job
 *  is building `grantDevs` from `rows` and applying the seen-delta check. */
internal fun filterMessagesNotIn(
    rows: List<GossipRow>, summaries: Map<Pair<String, String>, SeenSet>,
    entitled: Set<String>, peerIdentity: String, peerDevices: Set<String>,
): List<SignedMessage> {
    // (author, targetMsgId) -> devices named by THAT AUTHOR's own
    // wrap_grant messages (store.py:707-715) -- author-KEYED so a hostile
    // friend's grant naming target=someone-else's-post can never widen that
    // post's audience: the union below only ever consults
    // grantDevs[(thatPost'sOwnAuthor, thatPost'sMsgId)], and a grant signed
    // by anyone else lands under a DIFFERENT map key that this post's union
    // never reads. This is the same author-keying
    // test_routing_gate_ignores_non_author_grants (hearth) and
    // ignoresForeignAuthoredGrantAndPrefersOlderOwnAuthoredGrant
    // (DecryptPassTest, the read-side analog) both pin down.
    val grantDevs = hashMapOf<Pair<String, String>, MutableSet<String>>()
    for (r in rows) {
        if (r.kind != "wrap_grant") continue
        val target = r.payload["target"] as? String ?: continue
        grantDevs.getOrPut(r.identityPub to target) { mutableSetOf() }.addAll(wrapKeys(r.payload))
    }

    val out = mutableListOf<SignedMessage>()
    for (r in rows) {
        if (r.identityPub !in entitled) continue
        if (!peerMayReceive(r.kind, r.payload, r.identityPub, r.msgId, peerIdentity, peerDevices, grantDevs))
            continue
        val dev = summaries[r.identityPub to r.devicePub]
        if (dev != null && dev.has(r.seq)) continue
        out.add(r.raw)
    }
    return out
}

/** Reference impl (JVM-testable, no Android). Also the shape the SQLite
 *  impl mirrors. */
class InMemorySyncStore : SyncStore {
    private val identities = linkedSetOf<String>()
    private val messages = linkedMapOf<String, SignedMessage>()     // msg_id -> msg
    private val seen = hashMapOf<Pair<String, String>, SeenSet>()   // (ipub,dpub) -> seen
    private val blobs = linkedMapOf<String, ByteArray>()            // hash -> data
    private var encKey: Pair<String, String>? = null                // (encPrivHex, encPubHex)
    private var seqCounter = 0                                      // next nextSeq() call returns seqCounter+1
    // msgId -> wireDict, insertion order (LinkedHashMap preserves the FIRST
    // insertion's position on a re-put with the same key, so a re-queue of
    // an already-pending msgId stays idempotent-in-place, matching the old
    // LinkedHashSet<String> behavior).
    private val pendingOutboundMsgs = linkedMapOf<String, Map<String, Any?>>()

    private fun sha(b: ByteArray) =
        KotlinWire.toHex(MessageDigest.getInstance("SHA-256").digest(b))

    override fun summary(): Map<String, Map<String, Map<String, Any>>> {
        val out = linkedMapOf<String, MutableMap<String, Map<String, Any>>>()
        for ((k, ss) in seen)
            out.getOrPut(k.first) { linkedMapOf() }[k.second] = ss.toJson()
        return out
    }

    override fun knownIdentities(): List<String> = identities.toList()
    override fun addIdentity(id: String) { identities.add(id) }

    override fun removeIdentity(id: String) { identities.remove(id) }

    override fun purgeAuthoredBy(id: String): Int {
        val toRemove = messages.filterValues { it.cert.identity_pub == id }.keys.toList()
        for (mid in toRemove) messages.remove(mid)
        return toRemove.size
    }

    // devicePub -> lastValidSeq, the per-device revoked set (markRevoked/isRevokedDevice).
    private val revokedDevices = hashMapOf<String, Int>()

    override fun markRevoked(devicePub: String, lastValidSeq: Int) {
        revokedDevices[devicePub] = lastValidSeq
        val toDrop = messages.filterValues {
            it.cert.device_pub == devicePub && it.seq > lastValidSeq
        }.keys.toList()
        for (mid in toDrop) messages.remove(mid)
    }

    override fun isRevokedDevice(devicePub: String): Boolean = devicePub in revokedDevices

    private val meta = hashMapOf<String, String>()
    override fun getMeta(k: String): String? = meta[k]
    override fun setMeta(k: String, v: String) { meta[k] = v }

    override fun ingestMessage(m: SignedMessage): Boolean {
        // is_known gate (mirrors hearth Store.ingest_message's first check):
        // accept only from an identity we already know -- own identity is
        // seeded before sync, friends are added during HAVE. Do NOT
        // auto-register senders.
        if (m.cert.identity_pub !in identities) return false
        // Enrollment-cert gate (security fix: forgeable enckey / device
        // injection) -- mirrors hearth's Verifier.verify_message FIRST check
        // (identity.py:562, `msg.cert.verify()`), which runs BEFORE the
        // device-body signature check just below. Without this, any AUTH'd
        // peer (a directly-peering friend, not just the trusted home node)
        // could inject a message whose cert.identity_pub names a VICTIM
        // identity and cert.device_pub names the ATTACKER's own device,
        // signing only the message body (what verifyDeviceSignature checks)
        // while leaving the enrollment signature garbage -- e.g. a forged
        // KIND_ENCKEY that would surface in enckeys(victim) and let content
        // get wrapped/re-granted to the attacker's device. verifyCert is the
        // EXACT SAME function KotlinHandshake already uses to authenticate a
        // peer's own cert at HELLO (KotlinHandshake.kt: runOverStream/
        // respondHandshake/authOnlyOverStream all call
        // KotlinWire.verifyCert(peerCert)) -- reused here, not reinvented.
        if (!KotlinWire.verifyCert(m.cert)) return false
        if (!m.verifyDeviceSignature()) return false
        val id = m.msgId()
        if (messages.containsKey(id)) return false            // already have this exact message
        // seq-reuse rejection -- SeenSet's whole purpose (D2 Ambush 2;
        // hearth Verifier.verify_message: `if not seen.add(seq): reject`).
        // A device reusing a seq with DIFFERENT content (different msg_id,
        // so past the dedup above) is rejected here.
        if (!seen.getOrPut(m.cert.identity_pub to m.cert.device_pub) { SeenSet() }.add(m.seq))
            return false
        messages[id] = m
        return true
    }

    // Stores the wireDict Map DIRECTLY (native types, e.g. created_at as the
    // caller's PyFloat/Double, seq as Int -- never JSON-round-tripped), so
    // KotlinWire.dumps sees the exact same values it would at fresh-compose
    // time, with zero float/canonical-fidelity risk. This already matches
    // what SqliteSyncStore's canonical wire_json storage achieves via
    // KotlinWire.dumps/MsgJson.toMap (see its doc) -- here it's simpler
    // still, since no serialization round-trip is needed at all.
    override fun addPendingOutbound(msgId: String, wireDict: Map<String, Any?>) {
        pendingOutboundMsgs[msgId] = wireDict
    }

    override fun pendingOutbound(): List<Map<String, Any?>> = pendingOutboundMsgs.values.toList()

    override fun clearPendingOutbound(msgIds: List<String>) {
        for (id in msgIds) pendingOutboundMsgs.remove(id)
    }

    /** Blob hashes referenced by stored POST/DM/STORY/PROFILE payloads, minus
     *  what we hold. Mirrors hearth.store.referenced_blobs for the KIND_POST/
     *  KIND_DM fields (blobs list + poster str + thumbs list), junk-guarded
     *  to strings, WIDENED (B.2d-3 Task 1) to also scan `story` rows and
     *  (vp3 profile-blob fix) `profile` rows.
     *
     *  The `poster` extraction below is already generic across kinds, so
     *  adding "story" to the scanned kinds makes a story's poster flow
     *  through it for free. `media`, however, is guarded to `kind=="story"`
     *  ONLY: a story's `media` is a blob hash (single hex64 string), but a
     *  POST's `media` is the "photo"/"video" DISCRIMINATOR -- extracting it
     *  unconditionally would add the literal string "photo"/"video" to
     *  missingBlobs as a bogus hash (the field-shape trap). Likewise
     *  `avatar`/`banner` are guarded to `kind=="profile"` -- a KIND_PROFILE's
     *  avatar/banner are blob-hash references (hearth referenced_blobs scans
     *  KIND_PROFILE for exactly these), and without this the profile header
     *  images are never requested during sync so they render broken. */
    override fun missingBlobs(): List<String> {
        val refs = linkedSetOf<String>()
        for (m in messages.values) {
            if (m.kind != "post" && m.kind != "dm" && m.kind != "story" && m.kind != "profile") continue
            (m.payload["blobs"] as? List<*>)?.forEach { if (it is String) refs.add(it) }
            (m.payload["poster"] as? String)?.let { if (it.isNotEmpty()) refs.add(it) }
            (m.payload["thumbs"] as? List<*>)?.forEach { if (it is String) refs.add(it) }
            if (m.kind == "story")
                (m.payload["media"] as? String)?.let { if (it.isNotEmpty()) refs.add(it) }
            if (m.kind == "profile")
                for (f in listOf("avatar", "banner"))
                    (m.payload[f] as? String)?.let { if (it.isNotEmpty()) refs.add(it) }
        }
        return refs.filter { it !in blobs }
    }

    override fun putBlob(hash: String, data: ByteArray): Boolean {
        if (sha(data) != hash) return false
        blobs[hash] = data
        return true
    }

    override fun getBlob(hash: String): ByteArray? = blobs[hash]

    override fun stats(): SyncStats = SyncStats(messages.size, blobs.size, identities.size)

    override fun getEncKey(): Pair<String, String>? = encKey
    override fun setEncKey(priv: String, pub: String) { encKey = priv to pub }

    override fun nextSeq(): Int { seqCounter += 1; return seqCounter }

    private var publishedEncPub: String? = null
    override fun getPublishedEncPub(): String? = publishedEncPub
    override fun setPublishedEncPub(pub: String) { publishedEncPub = pub }

    override fun allMessages(): List<StoredMsg> =
        messages.entries.map { (id, m) -> StoredMsg(id, m.kind, m.cert.identity_pub, m.payload) }

    override fun wrapGrantsFor(msgId: String, acceptedSigners: Set<String>): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return messages.values
            .filter {
                it.kind == "wrap_grant" && it.payload["target"] == msgId &&
                    it.cert.identity_pub in acceptedSigners
            }
            .sortedWith(compareBy(
                { (it.payload["created_at"] as? Number)?.toDouble() ?: 0.0 },
                { it.seq }))
            .mapNotNull { it.payload["wraps"] as? Map<String, Any?> }
    }

    override fun profileNames(): Map<String, String> {
        data class Candidate(val createdAt: Double, val seq: Int, val name: String)
        val best = linkedMapOf<String, Candidate>()
        for (m in messages.values) {
            if (m.kind != "profile") continue
            // Blank ("" or all-whitespace) stored names are treated as
            // absent, not as a valid-but-empty display name -- otherwise a
            // profile message with name:"" would render as a blank author
            // segment in the feed instead of falling back to the readable
            // "friend-" + prefix. A later profile message (real name) for
            // the same identity can still win normally; this only rejects
            // THIS candidate, not the identity as a whole.
            val name = (m.payload["name"] as? String)?.takeIf { it.isNotBlank() } ?: continue
            val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: 0.0
            val cur = best[m.cert.identity_pub]
            if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && m.seq > cur.seq))
                best[m.cert.identity_pub] = Candidate(createdAt, m.seq, name)
        }
        return best.mapValues { it.value.name }
    }

    override fun enckeys(identityPub: String): Map<String, String> {
        data class Cand(val createdAt: Double, val seq: Int, val encPub: String)
        val best = linkedMapOf<String, Cand>()
        for (m in messages.values) {
            if (m.kind != "enckey" || m.cert.identity_pub != identityPub) continue
            val enc = m.payload["enc_pub"] as? String ?: continue
            val ca = (m.payload["created_at"] as? Number)?.toDouble() ?: continue
            val dev = m.cert.device_pub
            val cur = best[dev]
            if (cur == null || ca > cur.createdAt || (ca == cur.createdAt && m.seq > cur.seq))
                best[dev] = Cand(ca, m.seq, enc)
        }
        return best.mapValues { it.value.encPub }
    }

    override fun deviceViews(identity: String): Set<String> =
        messages.values
            .filter { it.cert.identity_pub == identity }
            .map { it.cert.device_pub }
            .toSet()

    override fun activeStories(nowSeconds: Double): List<StoredStory> {
        val out = mutableListOf<StoredStory>()
        for ((id, m) in messages) {
            if (m.kind != "story") continue
            val expiresAt = (m.payload["expires_at"] as? Number)?.toDouble() ?: continue
            if (expiresAt <= nowSeconds) continue          // strict: == now is expired, not active
            val mediaKind = (m.payload["media_kind"] as? String) ?: continue
            val media = (m.payload["media"] as? String) ?: continue
            val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: 0.0
            out.add(StoredStory(
                msgId = id, author = m.cert.identity_pub, mediaKind = mediaKind, media = media,
                poster = m.payload["poster"] as? String,
                caption = (m.payload["caption"] as? String) ?: "", createdAt = createdAt))
        }
        return out.sortedByDescending { it.createdAt }
    }

    override fun profileRecord(identityPub: String): Map<String, Any?>? {
        data class Cand(val createdAt: Double, val seq: Int, val payload: Map<String, Any?>)
        var best: Cand? = null
        for (m in messages.values) {
            if (m.kind != "profile" || m.cert.identity_pub != identityPub) continue
            val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: 0.0
            val cur = best
            if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && m.seq > cur.seq))
                best = Cand(createdAt, m.seq, m.payload)
        }
        return best?.payload
    }

    override fun profileLayout(identityPub: String): ProfileLayout {
        data class Cand(val createdAt: Double, val seq: Int, val payload: Map<String, Any?>)
        var best: Cand? = null
        for (m in messages.values) {
            if (m.kind != "profile_layout" || m.cert.identity_pub != identityPub) continue
            val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: 0.0
            val cur = best
            if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && m.seq > cur.seq))
                best = Cand(createdAt, m.seq, m.payload)
        }
        val p = best?.payload ?: return ProfileLayout(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        return ProfileLayout(
            pins = layoutSubMaps(p["pins"]), spans = layoutSubMaps(p["spans"]),
            sizes = layoutSizes(p["sizes"]), texts = layoutSubMaps(p["texts"]))
    }

    override fun albums(identityPub: String): Map<String, List<String>> {
        data class Cand(val createdAt: Double, val seq: Int, val members: List<String>)
        val best = linkedMapOf<String, Cand>()
        for (m in messages.values) {
            if (m.kind != "album" || m.cert.identity_pub != identityPub) continue
            val albumId = m.payload["album_id"] as? String ?: continue
            val members = (m.payload["members"] as? List<*>)?.mapNotNull { it as? String } ?: continue
            val createdAt = (m.payload["created_at"] as? Number)?.toDouble() ?: 0.0
            val cur = best[albumId]
            if (cur == null || createdAt > cur.createdAt || (createdAt == cur.createdAt && m.seq > cur.seq))
                best[albumId] = Cand(createdAt, m.seq, members)
        }
        return best.mapValues { it.value.members }
    }

    override fun messageById(msgId: String): SignedMessage? = messages[msgId]

    override fun messagesNotIn(
        summaries: Map<Pair<String, String>, SeenSet>, entitled: Set<String>, peerIdentity: String
    ): List<SignedMessage> {
        val rows = messages.values.sortedBy { it.seq }.map { m ->
            GossipRow(m.msgId(), m.cert.identity_pub, m.cert.device_pub, m.seq, m.kind, m.payload, m)
        }
        return filterMessagesNotIn(rows, summaries, entitled, peerIdentity, deviceViews(peerIdentity))
    }

    override fun blobSizes(hashes: List<String>): Map<String, Long> {
        val out = linkedMapOf<String, Long>()
        for (h in hashes) blobs[h]?.let { out[h] = it.size.toLong() }
        return out
    }

    // address -> identityPub (friend-peering Task 1; mirrors hearth
    // store.py's peers table, address as PRIMARY KEY). LinkedHashMap.put
    // naturally gives addPeer's INSERT OR REPLACE semantics -- one row per
    // address, newest identityPub wins.
    private val peers = linkedMapOf<String, String?>()

    override fun addPeer(address: String, identityPub: String?) { peers[address] = identityPub }

    override fun listPeers(): List<Peer> = peers.map { (address, identityPub) -> Peer(address, identityPub) }

    override fun removePeer(address: String) { peers.remove(address) }

    override fun addressFor(identityPub: String): String? =
        peers.entries.firstOrNull { it.value == identityPub }?.key
}

// -- friend-peering Task 2: mergePeerAddress (onion-preferred, host-keyed
//    eviction) -- mirrors hearth sync.py's _is_onion (sync.py:71-73) and
//    _merge_peer_address (sync.py:93-131).

/** True if `address`'s host (everything before the LAST `:`) ends in
 *  `.onion` -- byte-faithful port of hearth's `_is_onion` (sync.py:71-73:
 *  `host = address.rsplit(":", 1)[0]; return host.endswith(".onion")`).
 *  `substringBeforeLast(":")` matches `rsplit(":", 1)[0]` exactly,
 *  including the no-colon case (both return the whole string unchanged).
 *  A free top-level function, not a SyncStore member, matching hearth's
 *  own module-level `_is_onion` -- it needs no store state at all. */
internal fun isOnion(address: String): Boolean =
    address.substringBeforeLast(":").endsWith(".onion")

/** Onion-preferred peer store (friend-peering Task 2): a byte-faithful
 *  port of hearth sync.py's `_merge_peer_address` (sync.py:93-131). An
 *  onion address is always kept; a non-onion address is stored only if we
 *  hold no onion address for that identity yet. Never let a plain-TCP
 *  address shadow a known onion one -- that would route a Tor peer's
 *  gossip over the clearnet.
 *
 *  Onion eviction is HOST-keyed, not identity-keyed (sync.py's own doc
 *  comment, reproduced here because the reasoning is load-bearing and this
 *  is a port, not a paraphrase): TorTransport.connect normalizes every
 *  .onion dial to the fixed ONION_VIRTUAL_PORT regardless of the stored
 *  port, so a same-host row at an old port is always a stale duplicate of
 *  the same node (an onion host uniquely identifies one service) and must
 *  be drained -- stale-port rows would otherwise both dial the same live
 *  service: redundant full gossip sessions every round, a stale self-row
 *  syncing with itself over Tor, and the stale row propagating family-wide
 *  via HAVE/pairing. A single identity CAN legitimately own multiple
 *  different onion hosts across devices, so those must not be collapsed --
 *  only same-host, different-port rows are evicted.
 *
 *  A free/extension function rather than a `SyncStore` interface member,
 *  for the same reason `ingestRevocation`/`applyDefriendNotice` are
 *  (RevocationCert.kt's doc): it needs no store-impl-specific state beyond
 *  already-public interface methods (`listPeers`, `addPeer`,
 *  `removePeer`), so implementing it ONCE here gives both
 *  `InMemorySyncStore` and `SqliteSyncStore` identical behavior for free. */
fun SyncStore.mergePeerAddress(identity: String, addr: String) {
    if (isOnion(addr)) {
        val host = addr.substringBeforeLast(":")
        // Evict any non-onion rows for this identity: otherwise the
        // gossip loop keeps dialing the stale clearnet address every
        // round, and a have-frame built from listPeers() would keep
        // propagating a non-onion address for a peer with a known onion --
        // exactly what this guardrail exists to prevent.
        for (pe in listPeers()) {
            if (pe.identityPub != identity) continue
            if (!isOnion(pe.address)) {
                removePeer(pe.address)
            } else if (pe.address != addr && pe.address.substringBeforeLast(":") == host) {
                removePeer(pe.address)          // stale same-host, old port
            }
        }
        addPeer(addr, identity)
        return
    }
    val known = listPeers().filter { it.identityPub == identity }.map { it.address }
    if (known.none { isOnion(it) }) {
        addPeer(addr, identity)
    }
}
