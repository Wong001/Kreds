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

/** Task 7 (B.2): bundles the outcome of the enc-key resolve/guard/compose
 *  step in `syncNow` (see the try/catch around it) into one value so that
 *  step can be a single expression a `try` can return -- matching the
 *  store-init block's shape just above it (`val store = try { ... } catch
 *  { stream.close(); emit(...); return@AsyncFunction Unit }`). */
private data class EncKeyPrep(
    val outbound: List<Map<String, Any?>>,
    val encPriv: String,
    val encPub: String,
    val shouldPublish: Boolean,
)

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

        Events("torProgress", "nodeBeat", "nodeState", "nodeSync")

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
                mapOf("ts" to it.ts, "ok" to it.ok, "latencyMs" to it.latencyMs, "reason" to it.reason)
            }
        }

        // -- Brick B.1: foreground-triggered content sync --
        // -- Brick B.2 (Task 7): + enc-key publish (guarded) + decrypt pass --
        AsyncFunction("syncNow") {
            fun emit(ok: Boolean, messages: Int, blobs: Int, identities: Int, reason: String?, feedUpdated: Boolean) {
                sendEvent("nodeSync", mapOf(
                    "ok" to ok, "messages" to messages, "blobs" to blobs,
                    "identities" to identities, "reason" to reason,
                    "feedUpdated" to feedUpdated))
            }
            val ctx = appContext.reactContext
            if (ctx == null) { emit(false, 0, 0, 0, "no context", false); return@AsyncFunction Unit }
            if (!TorEngine.isUp) {
                emit(false, 0, 0, 0, "tor not up - start the node first", false)
                return@AsyncFunction Unit
            }
            try {
                val fx = KotlinHandshake.parseFixture(
                    File(TorEngine.externalDir(), "spike_phone_fixture.json").readText())
                val (host, port) = KotlinHandshake.splitAddr(fx.onion_addr)
                val stream = TorStream(TorEngine.dial(host, port))

                // AUTH ONLY -- do NOT use KotlinHandshake.run/runOverStream here: their
                // acceptance probe IS the sync REVOCATIONS swap, and chaining straight
                // into KotlinSync.run (whose own first phase also sends "revocations")
                // would send that frame twice and desync the session -- proved on the
                // BB-5 desk gate. authOnlyOverStream does HELLO+AUTH only, leaves the
                // stream open, and throws (rather than returning a verdict) on failure --
                // accept/refuse is surfaced by KotlinSync.run's own revocations phase.
                val peerCert = try {
                    KotlinHandshake.authOnlyOverStream(stream, fx)
                } catch (e: Exception) {
                    stream.close()
                    emit(false, 0, 0, 0, "auth: ${e.message}", false)
                    return@AsyncFunction Unit
                }

                // authOnlyOverStream verifies the peer cert is validly signed but does
                // NOT pin it to our home identity (only runOverStream's accepted branch
                // does that). Pin here so a wrong onion address can never sync us into
                // a stranger's node.
                if (peerCert.identity_pub != fx.cert.identity_pub) {
                    stream.close()
                    emit(false, 0, 0, 0, "auth: node identity is not our home identity", false)
                    return@AsyncFunction Unit
                }

                // SqliteSyncStore construction / addIdentity are SQLite I/O and can throw
                // (e.g. DB locked). authOnlyOverStream succeeding leaves the stream open
                // by contract -- only KotlinSync.run's finally closes it -- so a failure
                // here, before KotlinSync.run ever runs, must close the stream itself or
                // the Tor connection leaks for the process lifetime.
                val store = try {
                    SqliteSyncStore(ctx).also { it.addIdentity(fx.cert.identity_pub) }
                } catch (e: Exception) {
                    stream.close()
                    emit(false, 0, 0, 0, "store: ${e.message}", false)
                    return@AsyncFunction Unit
                }
                // (own identity is seeded above, inside the try, so ingest's is_known
                // gate admits its own-identity messages; the HAVE phase adds the node's
                // known identities.)

                // Task 7 (B.2): this device's own enc keypair (generated once,
                // persisted -- EncKeys.getOrCreate), the already-published
                // guard (EncKeyPublishGuard) deciding whether THIS sync's
                // outbound push needs a freshly-composed, freshly-seq'd
                // `enckey` message, and building that message when it does.
                // EncKeys.getOrCreate / store.getPublishedEncPub / store.nextSeq
                // are all SQLite I/O and CAN throw (e.g. DB locked -- plausible
                // under overlapping syncNow calls racing on the same DB file).
                // authOnlyOverStream succeeding leaves the stream open by
                // contract -- only KotlinSync.run's finally closes it -- so a
                // failure here, before KotlinSync.run ever runs, must close the
                // stream itself or the Tor connection leaks for the process
                // lifetime -- same reasoning, same shape, as the store-init
                // try/catch immediately above.
                val prep = try {
                    val (priv, pub) = EncKeys.getOrCreate(store)
                    val publish = EncKeyPublishGuard.shouldPublish(pub, store.getPublishedEncPub())
                    val outbound = if (publish) {
                        listOf(KotlinSync.composeEncKey(
                            fx, pub, store.nextSeq(), System.currentTimeMillis() / 1000.0))
                    } else emptyList()
                    EncKeyPrep(outbound, priv, pub, publish)
                } catch (e: Exception) {
                    stream.close()
                    emit(false, 0, 0, 0, "enckey: ${e.message}", false)
                    return@AsyncFunction Unit
                }

                when (val r = KotlinSync.run(stream, store, fx.device_pub, prep.outbound)) {
                    is SyncResult.Ok -> {
                        // Mark published ONLY once the sync that carried the
                        // push has FULLY succeeded -- see EncKeyPublishGuard's
                        // doc. A sync that throws/fails below this point never
                        // reaches here, so the marker stays stale/absent and
                        // the very next syncNow call retries the push.
                        if (prep.shouldPublish) store.setPublishedEncPub(prep.encPub)
                        // Decrypt pass + feed cache refresh: only a completed
                        // sync can change what's decryptable (new messages/
                        // wrap_grants may have just been pulled), so this is
                        // the one place the cache is recomputed -- see
                        // feedCache's own doc comment for why getFeed() itself
                        // just serves this cache instead of re-running.
                        feedCache = DecryptPass.run(store, fx.device_pub, prep.encPriv, fx.cert.identity_pub).feed
                        // r.identities (SyncResult.Ok, from KotlinSync.run --
                        // UNTOUCHABLE) is the raw identities-table count,
                        // which includes the phone's own identity (seeded
                        // just above, before KotlinSync.run) -- see
                        // friendsCount's doc for why that's the wrong number
                        // to label "friends" downstream.
                        emit(true, r.messages, r.blobs, friendsCount(store), null, true)
                    }
                    is SyncResult.SelfRevoked -> emit(false, 0, 0, 0, "self-revoked", false)
                    is SyncResult.Failed -> emit(false, 0, 0, 0, "${r.stage}: ${r.reason}", false)
                }
            } catch (e: Exception) {
                emit(false, 0, 0, 0, "io: ${e.message}", false)
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
                // createdAt: Double, already normalized in DecryptPass).
                // Rebuilt into a fresh Map here anyway rather than passing
                // the data class through, matching this module's existing
                // event/return marshaling style (getHistory/getSyncStats).
                mapOf(
                    "msgId" to d.msgId, "kind" to d.kind, "author" to d.author,
                    "text" to d.text, "createdAt" to d.createdAt,
                )
            }
        }

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
