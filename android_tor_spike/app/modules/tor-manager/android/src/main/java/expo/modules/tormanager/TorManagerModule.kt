package expo.modules.tormanager

import android.os.Build
import android.util.Base64
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Reserved msgId for the MediaServer resolver's story branch (B.2d-3 Task 2).
 *  Real msgIds are hex64 (a message id), so this string can never collide
 *  with one -- getStoryVideoUrl passes it as MediaServer.urlFor's msgId
 *  segment, and the resolver injected in ensureMediaServer branches on it
 *  BEFORE the decrypt path to serve a story hash as plaintext (see that
 *  resolver's doc comment for why a story must never go through
 *  blobKeys/decryptBlob). */
private const val STORY_MARKER = "story"

class TorManagerModule : Module() {
    // recv/send/dial block on socket I/O; keep them OFF the single default
    // AsyncFunction HandlerThread (would deadlock the concurrent recv+send
    // of the accepted-path probe). Dispatchers.IO is multi-threaded.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nodeReceiver: android.content.BroadcastReceiver? = null

    // Task 7 (B.2): the decrypted feed as of the last successful sync.
    // getFeed() serves this cache rather than re-running DecryptPass on
    // every call (DecryptPass.run does a full table scan over
    // store.allMessages() plus, per message, resolving wraps/wrap_grants --
    // cheap at B.2's real scale (253 messages) but there is no reason to
    // redo that work on every dashboard poll when only a completed sync can
    // possibly change the answer). Written from syncNow's SyncResult.Ok
    // branch on ioScope (Dispatchers.IO); read from getFeed's AsyncFunction
    // body on the module's default (non-ioScope) queue -- a different
    // thread. @Volatile guarantees the reader sees a fully-published List
    // reference rather than relying on incidental happens-before from
    // elsewhere; the List itself (DecryptPass.run's sortedByDescending
    // result) is never mutated after being built, only replaced wholesale.
    @Volatile private var feedCache: List<DecryptPass.Decrypted> = emptyList()

    // Task 5 (B.2d): per-message content keys for every message in
    // feedCache that carries a blob (DecryptPass.Result.keys -- msgId ->
    // contentKey), needed by getBlobImage to decrypt a blob on demand.
    // SAME lifetime as feedCache, deliberately: both come from the same
    // DecryptPass.Result (`res` in syncNow's success branch, captured once
    // so `res.feed` and `res.keys` are guaranteed to describe the SAME
    // decrypt pass -- calling DecryptPass.run twice and assigning each
    // field separately could race a concurrent syncNow into pairing one
    // run's feed with a DIFFERENT run's keys). In-memory only, replaced
    // wholesale on every successful sync, NEVER persisted to disk -- this
    // is key material (a message's content key), and feedCache's own
    // reasoning (nothing here survives past the next sync, no reason to
    // ever write it out) applies with even more force here. @Volatile for
    // the same cross-thread-visibility reason as feedCache: written from
    // ioScope (Dispatchers.IO) in syncNow, read from getBlobImage's
    // AsyncFunction body (also ioScope, but a possibly different thread --
    // Dispatchers.IO is a pool). The Map itself is never mutated after
    // being built (DecryptPass.run returns a fresh Map each call), only
    // the reference is replaced.
    @Volatile private var blobKeys: Map<String, ByteArray> = emptyMap()

    // Task 3 (B.2d-4): a post's msgId -> its aggregated engagement view
    // (DecryptPass.responsesPass -- reactions tally + resolved comment
    // list), read by getFeed to attach a `responses` field to each feed
    // item. SAME lifetime/refresh point as feedCache/blobKeys, deliberately
    // -- populated in syncNow's SAME success branch, from a decrypt pass
    // over the SAME just-refreshed store, so a getFeed call always sees a
    // responsesByPost consistent with the feedCache it's serving alongside
    // (never a stale responses map paired with a freshly-synced feed, or
    // vice versa). @Volatile for the same cross-thread-visibility reason as
    // feedCache/blobKeys (written on ioScope, read from getFeed's queue).
    // In-memory only, never persisted -- decrypt-on-read, same reasoning as
    // blobKeys' own doc.
    @Volatile private var responsesByPost: Map<String, KotlinResponses.Responses> = emptyMap()

    // Task 3 (B.2d-2): the loopback media server getVideoUrl streams decrypted
    // video bytes through. Lazily constructed by ensureMediaServer() below --
    // NOT in OnCreate -- so a phone that never opens a video post never spins
    // up the accept-loop thread at all. Constructed at most ONCE per module
    // instance (ensureMediaServer is @Synchronized and checks this field
    // first) and reused for every subsequent getVideoUrl call, matching
    // MediaServer.start()'s own idempotency (a second start() on the same
    // instance is a no-op returning the existing port) -- the point of this
    // field is avoiding a SECOND MediaServer instance (a different token, a
    // second accept-loop thread) if two feed rows race to open their first
    // video concurrently. Torn down in OnDestroy so its daemon accept-loop
    // thread never outlives this module instance.
    @Volatile private var mediaServer: MediaServer? = null

    // Review fix (B.2d-2 Task 3): closes a construct/destroy race that the
    // plain @Volatile teardown in OnDestroy left open. ensureMediaServer and
    // teardownMediaServer are BOTH @Synchronized on the same monitor
    // (`this`), so they mutually exclude -- but mutual exclusion of the two
    // METHOD BODIES alone isn't enough: if OnDestroy's teardown runs to
    // completion BEFORE a concurrently-invoked getVideoUrl even calls
    // ensureMediaServer (e.g. RN Fast Refresh / a bridge reload landing
    // right after a first video tap, on ioScope, which is never cancelled),
    // the teardown seeing mediaServer==null would no-op, and the LATER
    // ensureMediaServer call would happily start a fresh server that then
    // outlives this already-destroyed module instance -- exactly the
    // listener-thread leak this module exists to avoid. `destroyed` closes
    // that window: teardownMediaServer sets it (still inside the
    // synchronized block, before nulling mediaServer), and ensureMediaServer
    // checks it FIRST, inside its own synchronized block, and refuses to
    // start anything once it's true. Combined with the shared monitor, this
    // guarantees one of exactly two outcomes for any interleaving: either
    // ensureMediaServer's construction fully happens-before teardown (and
    // teardown then stops the real, assigned server), or teardown fully
    // happens-before ensureMediaServer (and ensureMediaServer sees
    // destroyed==true and never starts a server at all). There is no third
    // interleaving where a server is started but never stopped.
    @Volatile private var destroyed = false

    /** Lazily starts (once) the loopback media server getVideoUrl/
     *  getStoryVideoUrl resolve URLs from. The injected resolver has two
     *  branches (B.2d-3 Task 2 added the first):
     *
     *  - msgId == STORY_MARKER: a story's video blob. Stories are PLAINTEXT
     *    by design (no content key, see StoredStory's doc) -- this branch
     *    serves SqliteSyncStore.getBlob's raw bytes directly and MUST NEVER
     *    consult blobKeys or call KotlinBlobCrypt.decryptBlob. Feeding a real
     *    (non-story) post's ciphertext hash through this branch by mistake
     *    would just serve unplayable ciphertext -- ordinary garbage, not a
     *    plaintext leak -- but the reverse mistake (a story hash falling
     *    through to the decrypt branch below) would silently corrupt with
     *    AEAD failure, which is why this check is an explicit `if` gated on
     *    the reserved, uncollidable STORY_MARKER rather than any inference
     *    from blobKeys' contents.
     *  - otherwise: getBlobImage's decrypt path exactly (blobKeys ->
     *    SqliteSyncStore.getBlob -> KotlinBlobCrypt.decryptBlob) -- unchanged
     *    from before this task.
     *
     *  Either way this returns raw bytes rather than a rendered data URI --
     *  MediaServer streams those bytes straight to the platform player,
     *  re-resolving (re-decrypting, for the non-story branch) on every call
     *  (a seek re-reads the whole blob then slices; acceptable at the <=5MB
     *  gate cap per the brief -- a future large-media case could cache the
     *  resolved bytes for the currently-playing hash only, evicted on stop,
     *  but that's not built here). Nothing the resolver returns is ever
     *  written to disk -- MediaServer holds it in memory only long enough to
     *  write it to the response socket. @Synchronized: getVideoUrl/
     *  getStoryVideoUrl run on ioScope (Dispatchers.IO, a thread pool), so
     *  two feed rows (or story views) opening their first video at once must
     *  not race into constructing two servers -- and (see `destroyed`'s doc
     *  above) so a concurrent OnDestroy can't race a server into existence
     *  after teardown either. Returns null once this module instance has
     *  been destroyed -- getVideoUrl/getStoryVideoUrl already treat a null
     *  result the same as "no content key"/"not found", so no new null-
     *  handling was needed at either call site beyond widening its type. */
    @Synchronized
    private fun ensureMediaServer(ctx: android.content.Context): MediaServer? {
        if (destroyed) return null
        mediaServer?.let { return it }
        val s = MediaServer { msgId, hash ->
            if (msgId == STORY_MARKER) {
                SqliteSyncStore(ctx).getBlob(hash)
            } else {
                blobKeys[msgId]?.let { key ->
                    SqliteSyncStore(ctx).getBlob(hash)?.let { cipher -> KotlinBlobCrypt.decryptBlob(key, cipher) }
                }
            }
        }
        s.start()
        mediaServer = s
        return s
    }

    /** Review fix (B.2d-2 Task 3): the OnDestroy-side half of the
     *  construct/destroy race fix -- see `destroyed`'s doc comment above for
     *  the full reasoning. @Synchronized on the SAME monitor as
     *  ensureMediaServer (this is what makes the fix work: without a shared
     *  monitor, "check destroyed" and "check/assign mediaServer" could still
     *  interleave). Idempotent: a second call (shouldn't happen in practice,
     *  OnDestroy fires once) just re-sets destroyed=true and no-ops the
     *  already-null mediaServer?.stop(). */
    @Synchronized
    private fun teardownMediaServer() {
        destroyed = true
        mediaServer?.stop()
        mediaServer = null
    }

    /** B.2c Task 3: the phone's real friend count -- `store.knownIdentities()`
     *  minus the phone's OWN identity. Both `getSyncStats` and `syncNow`'s
     *  success path previously surfaced the identities-table's raw row
     *  count (SqliteSyncStore.stats().identities / SyncResult.Ok.identities)
     *  as-is under the JS-facing key "identities" (App.tsx labels it
     *  "friends") -- but that table holds the phone's OWN identity too,
     *  seeded via `store.addIdentity(fx.cert.identity_pub)` at the top of
     *  every syncNow, alongside real friends admitted by KotlinSync.run's
     *  (untouchable) HAVE phase from the node's reported `known` list. A
     *  phone with zero real friends therefore showed "friends: 1" (itself).
     *
     *  The own identity is read from the same fixture file syncNow already
     *  parses (`spike_phone_fixture.json`), rather than threading it through
     *  as a parameter -- getSyncStats has no fixture in scope today, and
     *  parsing here keeps both call sites' fix identical instead of one
     *  passing a value the other derives fresh. If the fixture can't be
     *  read, this fails toward "no friends" (returns 0) rather than the
     *  phantom-friend direction (previously: falling back to the raw,
     *  unfiltered known-identities count, which could silently re-admit the
     *  own identity as a counted "friend" -- the exact bug this whole
     *  function exists to fix). A caller seeing 0 friends when the fixture
     *  is temporarily unreadable is the safe failure shape; seeing an
     *  inflated count never is. */
    private fun friendsCount(store: SyncStore): Int {
        val ownIdentityPub = try {
            KotlinHandshake.parseFixture(
                File(TorEngine.externalDir(), "spike_phone_fixture.json").readText()
            ).cert.identity_pub
        } catch (e: Exception) { return 0 }
        return store.knownIdentities().count { it != ownIdentityPub }
    }

    override fun definition() = ModuleDefinition {
        Name("TorManager")

        Constants(
            "fixtureDir" to (appContext.reactContext?.getExternalFilesDir(null)?.absolutePath ?: "")
        )

        Events("torProgress", "nodeBeat", "nodeState", "nodeSync", "onSyncProgress")

        OnCreate {
            val ctx = appContext.reactContext ?: return@OnCreate
            TorEngine.init(ctx)
            // Task 3 (B.2d): route AVIF decode through the isolated :imagedecode
            // process. KotlinImageDecode's magic-byte dispatcher (pure, in the
            // main process) hands only AVIF payloads to this decoder; everything
            // it touches is one image's cleartext bytes, decoded in the sandbox.
            // The main process never links the native decoder itself.
            ImageDecodeClient.init(ctx)
            KotlinImageDecode.avifDecoder = { bytes -> ImageDecodeClient.decodeAvif(bytes) }
            val filter = android.content.IntentFilter().apply {
                addAction(TorNodeService.BROADCAST_BEAT); addAction(TorNodeService.BROADCAST_STATE)
            }
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                    when (i?.action) {
                        TorNodeService.BROADCAST_BEAT -> sendEvent("nodeBeat", mapOf(
                            "ts" to i.getLongExtra("ts", 0), "ok" to i.getBooleanExtra("ok", false),
                            "latencyMs" to i.getLongExtra("latencyMs", 0), "reason" to i.getStringExtra("reason")))
                        TorNodeService.BROADCAST_STATE -> sendEvent("nodeState",
                            mapOf("state" to i.getStringExtra("state")))
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33)
                ctx.registerReceiver(rx, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(rx, filter)
            nodeReceiver = rx
        }
        OnDestroy {
            appContext.reactContext?.let { c -> nodeReceiver?.let { c.unregisterReceiver(it) } }
            // Task 3 (B.2d-2): stop the loopback media server's daemon
            // accept-loop thread (MediaServer.start(), see its class doc) so
            // it doesn't outlive this module instance. Routed through the
            // @Synchronized teardownMediaServer() (review fix) rather than a
            // bare `mediaServer?.stop(); mediaServer = null` -- the bare
            // version raced against a concurrent ensureMediaServer() call
            // (see `destroyed`'s doc comment for the exact interleaving) and
            // could leak a server started just after this ran. Safe to call
            // even if getVideoUrl was never invoked (mediaServer stays null
            // -> no-op stop).
            teardownMediaServer()
        }

        AsyncFunction("bootstrap") { promise: Promise ->
            TorEngine.bootstrap(
                onProgress = { p -> sendEvent("torProgress", mapOf("progress" to p)) },
                onDone = { port -> promise.resolve(port) },
                onError = { code, msg -> promise.reject(code, msg, null) },
            )
        }

        Function("socksPort") { SOCKS_PORT }

        AsyncFunction("dial") { host: String, port: Int -> TorEngine.dial(host, port) }.runOnQueue(ioScope)

        AsyncFunction("send") { id: Int, b64: String ->
            // Last expression must be Unit: Expo marshals it back to JS and
            // cannot serialize java.net.SocketOutputStream ("Unknown type",
            // an on-device failure the spike hit). TorEngine.send is a
            // Unit-returning fun, so this is safe -- do not inline it into an
            // apply{} that returns the stream.
            TorEngine.send(id, Base64.decode(b64, Base64.NO_WRAP))
        }.runOnQueue(ioScope)

        AsyncFunction("recv") { id: Int, n: Int ->
            Base64.encodeToString(TorEngine.recv(id, n), Base64.NO_WRAP)
        }.runOnQueue(ioScope)

        Function("closeConn") { id: Int -> TorEngine.close(id) }

        AsyncFunction("suspendTor") { TorEngine.suspend() }

        // -- Brick A: background node control --
        Function("startNode") { appContext.reactContext?.let { TorNodeService.start(it) } }
        Function("stopNode") { appContext.reactContext?.let { TorNodeService.stop(it) } }
        Function("beatNow") { appContext.reactContext?.let { TorNodeService.beatNow(it) } }

        AsyncFunction("getHistory") {
            val ctx = appContext.reactContext ?: return@AsyncFunction emptyList<Map<String, Any?>>()
            HeartbeatStore.history(ctx).map {
                // Brick C Task 3: surface the pulled counts Task 2 added to the
                // persisted Beat (messages/blobs/identities) -- defaulted to 0
                // on the Kotlin side for pre-Brick-C entries, so every history
                // item always carries them here regardless of when it was
                // recorded. The live "nodeBeat" broadcast/event is UNCHANGED
                // (ts/ok/latencyMs/reason only) -- these three fields exist
                // ONLY on this getHistory() result, per index.ts's Beat type.
                // `skipped` (Task 3 fix): the dedicated mutex-skip flag --
                // App.tsx renders a skipped=true row neutral regardless of
                // `reason`'s exact wording.
                mapOf("ts" to it.ts, "ok" to it.ok, "latencyMs" to it.latencyMs, "reason" to it.reason,
                    "messages" to it.messages, "blobs" to it.blobs, "identities" to it.identities,
                    "skipped" to it.skipped)
            }
        }

        // -- Brick B.1: foreground-triggered content sync --
        // -- Brick B.2 (Task 7): + enc-key publish (guarded) + decrypt pass --
        // -- Brick C  (Task 1): the TRANSPORT (dial/auth/pin/store/enc-key/
        //    KotlinSync.run/mark-published) now lives in SyncRunner, run under
        //    a process-wide mutex and callable JS-free by the background
        //    service. syncNow keeps ONLY the foreground concerns: the fixture
        //    read, the onProgress event bridge, and -- on a returned success --
        //    the DecryptPass + feed/blob-key cache refresh + terminal
        //    nodeSync/onSyncProgress emits. The single new behavior is the
        //    "sync already in progress" skip (outcome.ran == false). --
        AsyncFunction("syncNow") {
            // `skipped` (Task 3 fix): defaults false so every pre-existing
            // call site (real successes/failures) is unaffected -- only the
            // outcome.ran == false branch below passes skipped = true. This
            // is the dedicated boolean the JS side checks; `reason`'s exact
            // wording ("sync already in progress") is display text only, no
            // longer load-bearing for the neutral-vs-red decision.
            fun emit(
                ok: Boolean, messages: Int, blobs: Int, identities: Int, reason: String?,
                feedUpdated: Boolean, skipped: Boolean = false,
            ) {
                sendEvent("nodeSync", mapOf(
                    "ok" to ok, "messages" to messages, "blobs" to blobs,
                    "identities" to identities, "reason" to reason,
                    "feedUpdated" to feedUpdated, "skipped" to skipped))
            }
            val ctx = appContext.reactContext
            if (ctx == null) { emit(false, 0, 0, 0, "no context", false); return@AsyncFunction Unit }
            if (!TorEngine.isUp) {
                emit(false, 0, 0, 0, "tor not up - start the node first", false)
                return@AsyncFunction Unit
            }
            // Fixture read stays foreground (SyncRunner takes an already-parsed
            // Fixture). A read/parse failure emits the same "io:" nodeSync the
            // pre-refactor single outer try/catch did.
            val fx = try {
                KotlinHandshake.parseFixture(
                    File(TorEngine.externalDir(), "spike_phone_fixture.json").readText())
            } catch (e: Exception) {
                emit(false, 0, 0, 0, "io: ${e.message}", false)
                return@AsyncFunction Unit
            }

            // Task 6 (B.2d): purely additive observability -- forwards
            // SyncRunner/KotlinSync.run's phase-boundary callbacks
            // (connecting/handshake/messages/blobs/decrypting) as
            // "onSyncProgress" events. The injected onProgress param;
            // SyncRunner's background caller (Task 2) passes the default no-op.
            val onProgress = { phase: String, count: Int ->
                sendEvent("onSyncProgress", mapOf("phase" to phase, "count" to count))
            }

            val outcome = SyncRunner.runSync(ctx, fx, onProgress)

            if (!outcome.ran) {
                // The one new behavior: a concurrent sync already held the
                // process-wide mutex, so this call skipped immediately (no
                // second Tor dial, no double KotlinSync.run). Feed unchanged.
                // skipped = true is the source of truth for the UI's neutral
                // note; `reason` stays human-readable text only.
                emit(false, 0, 0, 0, "sync already in progress", false, skipped = true)
                return@AsyncFunction Unit
            }

            if (outcome.ok) {
                // Decrypt pass + feed/key cache refresh: only a completed sync
                // can change what's decryptable (new messages/wrap_grants may
                // have just been pulled), so this is the one place the caches
                // are recomputed -- see feedCache's and blobKeys' own doc
                // comments for why getFeed()/getBlobImage() just serve these
                // caches instead of re-running. Captured once into `res` so
                // feedCache and blobKeys are always set from the SAME decrypt
                // pass (see blobKeys' doc for why that matters). EncKeys.
                // getOrCreate is idempotent -- it returns the SAME persisted
                // priv SyncRunner's transport already generated/read this sync.
                //
                // Wrapped in its own try: DecryptPass.run / SqliteSyncStore are
                // SQLite I/O and can throw. The pre-refactor code ran this
                // inside syncNow's single outer try, whose catch emitted an
                // "io:" nodeSync -- preserve that exact failure shape (feed
                // left as it was, since the throw precedes the cache writes).
                try {
                    val store = SqliteSyncStore(ctx)
                    val (priv, _) = EncKeys.getOrCreate(store)
                    val res = DecryptPass.run(store, fx.device_pub, priv, fx.cert.identity_pub)
                    // Task 3 (B.2d-4): computed BEFORE any of the three
                    // caches are mutated, same reasoning as capturing `res`
                    // above -- if responsesPass throws (same SQLite-I/O risk
                    // as DecryptPass.run), feedCache/blobKeys/responsesByPost
                    // must ALL stay at their prior, mutually consistent
                    // values (the outer catch below then reports this sync
                    // as failed) rather than feedCache/blobKeys advancing to
                    // the new sync while responsesByPost is left stale.
                    val responses = DecryptPass.responsesPass(store, fx.device_pub, priv, fx.cert.identity_pub)
                    feedCache = res.feed
                    blobKeys = res.keys
                    responsesByPost = responses
                    // Task 6 (B.2d): the trailing "done" phase -- emitted here
                    // (not inside KotlinSync.run, which returned before
                    // DecryptPass even started) once the feed cache this sync
                    // refreshed is ready for getFeed() to serve. Count is the
                    // decrypted feed size (res.feed.size), matching what just
                    // became visible to the UI, not the raw wire message count.
                    //
                    // Own try/catch, swallowed: this sendEvent runs AFTER
                    // feedCache/blobKeys are already mutated to their
                    // successful state -- if it threw uncaught, the enclosing
                    // catch would emit nodeSync ok=false and the real
                    // emit(true, ...) below would never run, reporting a sync
                    // that fully succeeded (feed already updated) as failed. A
                    // side-channel (observability) send must never flip the
                    // terminal nodeSync outcome -- same reasoning as
                    // KotlinSync's own `progress` swallow wrapper.
                    try {
                        sendEvent("onSyncProgress", mapOf("phase" to "done", "count" to res.feed.size))
                    } catch (_: Throwable) {}
                    // friendsCount(store), NOT outcome.identities: outcome.
                    // identities is KotlinSync.run's raw identities-table count,
                    // which includes the phone's own identity (seeded before
                    // the sync) -- see friendsCount's doc for why that's the
                    // wrong number to label "friends" downstream. Preserves the
                    // exact value the pre-refactor success branch emitted.
                    emit(true, outcome.messages, outcome.blobs, friendsCount(store), null, true)
                } catch (e: Exception) {
                    emit(false, 0, 0, 0, "io: ${e.message}", false)
                }
            } else {
                // SelfRevoked / Failed / io -- SyncRunner already mapped these
                // to (messages,blobs,identities)=(0,0,0) with the matching
                // reason ("self-revoked" / "${stage}: ${reason}" / "io: ...").
                // feedUpdated stays false (no sync failure ever refreshes the
                // feed), exactly as the pre-refactor SelfRevoked/Failed/outer-
                // catch branches emitted.
                emit(false, outcome.messages, outcome.blobs, outcome.identities, outcome.error, false)
            }
        }.runOnQueue(ioScope)

        // Task 7 (B.2): the decrypted own-authored feed (post/dm text), as of
        // the last successful syncNow -- see feedCache's doc comment. No
        // store/network I/O here, so this deliberately does NOT need
        // .runOnQueue(ioScope) (nothing here can block).
        AsyncFunction("getFeed") {
            feedCache.map { d ->
                // Plain Kotlin/JS-marshalable types only (String/Double) --
                // DecryptPass.Decrypted's fields are already plain (msgId:
                // String from a SQL column, kind: String, text: String
                // pulled from an org.json body via `as? String` -- org.json
                // strings ARE plain java.lang.String, not a wrapper type --
                // createdAt: Double, already normalized in DecryptPass;
                // blobs: List<String> and thumbs: List<String?>, Task 5
                // (B.2d) -- hash REFERENCES only, never blob bytes/keys --
                // the UI resolves each into a data URI on demand via
                // getBlobImage(msgId, hash)).
                // Rebuilt into a fresh Map here anyway rather than passing
                // the data class through, matching this module's existing
                // event/return marshaling style (getHistory/getSyncStats).
                mapOf(
                    "msgId" to d.msgId, "kind" to d.kind, "author" to d.author,
                    "text" to d.text, "createdAt" to d.createdAt,
                    "blobs" to d.blobs, "thumbs" to d.thumbs,
                    // media/poster (B.2d-2 Task 1): plaintext outer-payload
                    // envelope fields (DecryptPass.Decrypted.media's doc) --
                    // "photo"/"video" plus the video's poster blob-hash ref
                    // (null for a photo post).
                    "media" to d.media, "poster" to d.poster,
                    // storyRefMediaHash (B.2d-3 Task 3, gap fix): plaintext,
                    // DM-only outer-payload field (DecryptPass.Decrypted.
                    // storyRefMediaHash's doc) -- the story-reply chip's
                    // ONLY consumer, resolved via getStoryImage(hash) same
                    // as any other story media. null for an ordinary DM or
                    // any post.
                    "storyRefMediaHash" to d.storyRefMediaHash,
                    // responses (B.2d-4 Task 3): this post's aggregated
                    // engagement view (DecryptPass.responsesPass, via the
                    // responsesByPost cache -- same lifetime as feedCache,
                    // see its own doc comment), or null when this msgId has
                    // no valid KIND_RESPONSES record (no engagement yet, or
                    // nothing decryptable/attributable-for-this-target --
                    // responsesPass is fail-closed, so "null" covers every
                    // one of those cases identically). `comments` rebuilt
                    // into plain marshalable maps -- KotlinResponses.Comment
                    // is a Kotlin data class, not directly bridge-safe, same
                    // reasoning as this whole mapOf() rebuild.
                    "responses" to responsesByPost[d.msgId]?.let { r ->
                        mapOf(
                            "reactions" to r.reactions,
                            "comments" to r.comments.map { c ->
                                mapOf(
                                    "body" to c.body, "display" to c.display,
                                    "color" to c.aliasColor, "createdAt" to c.createdAt,
                                )
                            },
                        )
                    },
                )
            }
        }

        // Task 5 (B.2d): lazy in-memory blob decrypt+decode -- resolves one
        // blob/thumb reference from getFeed's `blobs`/`thumbs` lists into a
        // displayable `data:<mime>;base64,<...>` URI, or null on ANY miss
        // (no content key for msgId, blob not synced yet, wrong-key/tampered
        // AEAD failure, or a format toRenderable can't render, e.g. a video)
        // -- never throws, so the UI's contract is simply "null -> show a
        // placeholder", matching DecryptPass's own fail-closed-per-item
        // idiom rather than failing the whole feed render. Store I/O (SQLite
        // blob read) + AVIF decode (isolated-process IPC via
        // ImageDecodeClient, Task 3) both can block, so this runs on
        // ioScope/Dispatchers.IO, same as syncNow/dial/send/recv -- not the
        // module's default single HandlerThread.
        AsyncFunction("getBlobImage") { msgId: String, hash: String ->
            val ctx = appContext.reactContext ?: return@AsyncFunction null
            val key = blobKeys[msgId] ?: return@AsyncFunction null
            val store = SqliteSyncStore(ctx)
            val cipher = store.getBlob(hash) ?: return@AsyncFunction null
            val plain = KotlinBlobCrypt.decryptBlob(key, cipher) ?: return@AsyncFunction null
            val (mime, bytes) = KotlinImageDecode.toRenderable(plain) ?: return@AsyncFunction null
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.runOnQueue(ioScope)

        // Task 3 (B.2d-2): resolves a video post's full blob hash (a
        // FeedItem's `blobs[0]` when `media === "video"`) into a
        // http://127.0.0.1 URL the platform player (expo-video) can stream
        // from, range requests included -- see MediaServer's class doc. null
        // when there's no content key for msgId (same "not entitled/not
        // synced yet" miss getBlobImage returns null for) -- this is checked
        // BEFORE ever starting the server, so a miss never spins one up.
        // Store/AVIF-decode I/O happen later, per request, on MediaServer's
        // own accept-loop/handler threads, not here -- this call only starts
        // the server (if not already running) and formats a URL string, so
        // it's cheap, but stays on ioScope/ensureMediaServer for consistency
        // with every other store-touching AsyncFunction in this module.
        AsyncFunction("getVideoUrl") { msgId: String, hash: String ->
            val ctx = appContext.reactContext ?: return@AsyncFunction null
            // ensureMediaServer(ctx)?.let { ... } (review fix): ensureMediaServer
            // now returns null once the module is destroyed (see its doc) -- the
            // ?. here is the only call-site change that widening required, since
            // getVideoUrl already returns null for every other "can't resolve"
            // case (no content key, no reactContext).
            if (blobKeys[msgId] == null) null else ensureMediaServer(ctx)?.urlFor(msgId, hash)
        }.runOnQueue(ioScope)

        // -- B.2d-3 Task 2: plaintext story render paths --
        // Stories carry no content key (see StoredStory's doc on SyncStore) --
        // these three functions deliberately never touch blobKeys or call
        // KotlinBlobCrypt.decryptBlob, unlike their post/dm counterparts
        // (getBlobImage/getVideoUrl) just above. A story's AVIF stills still
        // route through the isolated :imagedecode process via
        // KotlinImageDecode.toRenderable -- that isolation is about WHO
        // authored the bytes being decoded (a friend, for a story, same as
        // any post), not about whether they're encrypted, so it stays even
        // though nothing here is a decrypt.

        // Resolves a story's image/poster blob hash straight to a displayable
        // `data:<mime>;base64,<...>` URI -- no blobKeys lookup, no
        // decryptBlob, matching getBlobImage's shape apart from that missing
        // step. Null on any miss (not synced yet, or a format toRenderable
        // can't render), same fail-closed-per-item contract as getBlobImage.
        AsyncFunction("getStoryImage") { hash: String ->
            val ctx = appContext.reactContext ?: return@AsyncFunction null
            val bytes = SqliteSyncStore(ctx).getBlob(hash) ?: return@AsyncFunction null
            val (mime, out) = KotlinImageDecode.toRenderable(bytes) ?: return@AsyncFunction null
            "data:$mime;base64," + Base64.encodeToString(out, Base64.NO_WRAP)
        }.runOnQueue(ioScope)

        // Resolves a video story's full blob hash into a http://127.0.0.1 URL
        // the platform player can stream from -- same loopback MediaServer
        // getVideoUrl uses, but urlFor's msgId segment is STORY_MARKER
        // instead of a real msgId, which is what routes the request into the
        // resolver's plaintext (no-decrypt) branch above. Unlike getVideoUrl,
        // there is no blobKeys gate to check first (stories have no content
        // key to gate on) -- null here only means "server couldn't start" or
        // "module already destroyed" (ensureMediaServer returning null),
        // mirroring getVideoUrl's null handling for those same cases.
        AsyncFunction("getStoryVideoUrl") { hash: String ->
            val ctx = appContext.reactContext ?: return@AsyncFunction null
            ensureMediaServer(ctx)?.urlFor(STORY_MARKER, hash)
        }.runOnQueue(ioScope)

        // The active (unexpired) story list (B.2d-3 Task 1's activeStories +
        // profileNames), shaped for the UI. `authorName` resolves the same
        // way DecryptPass.resolveAuthor does for feed items (own/friend
        // display name, else "friend-" + identityPub.take(8)) -- profileNames
        // is re-read fresh on every call (cheap: a small, indexed table scan,
        // and stories aren't cached the way feedCache is) rather than reusing
        // any feed-side cache, so a story from a friend with no post/dm yet
        // in feedCache still gets a name. `now` uses System.currentTimeMillis
        // (wall-clock seconds), matching the unit activeStories'
        // expires_at/created_at fields are stored in (hearth's story payloads
        // are wall-clock seconds, not monotonic).
        AsyncFunction("getStories") {
            val ctx = appContext.reactContext ?: return@AsyncFunction emptyList<Map<String, Any?>>()
            val store = SqliteSyncStore(ctx)
            val now = System.currentTimeMillis() / 1000.0
            val names = store.profileNames()
            store.activeStories(now).map {
                mapOf(
                    "msgId" to it.msgId, "author" to it.author,
                    "authorName" to (names[it.author] ?: "friend-" + it.author.take(8)),
                    "mediaKind" to it.mediaKind, "media" to it.media,
                    "poster" to it.poster, "caption" to it.caption, "createdAt" to it.createdAt,
                )
            }
        }.runOnQueue(ioScope)

        AsyncFunction("getSyncStats") {
            val ctx = appContext.reactContext
                ?: return@AsyncFunction mapOf("messages" to 0, "blobs" to 0, "identities" to 0)
            val store = SqliteSyncStore(ctx)
            val st = store.stats()
            mapOf("messages" to st.messages, "blobs" to st.blobs, "identities" to friendsCount(store))
        }

        Function("isBatteryExempt") {
            val ctx = appContext.reactContext ?: return@Function false
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

        Function("requestBatteryExemption") {
            val ctx = appContext.reactContext ?: return@Function Unit
            val i = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
