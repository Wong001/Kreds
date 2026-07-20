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
        OnDestroy { appContext.reactContext?.let { c -> nodeReceiver?.let { c.unregisterReceiver(it) } } }

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
                    feedCache = res.feed
                    blobKeys = res.keys
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
