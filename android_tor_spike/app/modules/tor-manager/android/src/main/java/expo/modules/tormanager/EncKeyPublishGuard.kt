package expo.modules.tormanager

/** Task 7 (B.2): the already-published guard `TorManagerModule.syncNow`
 *  applies before deciding whether this sync's outbound push includes a
 *  freshly-composed `enckey` message.
 *
 *  Pulled out of `TorManagerModule` (an Expo `Module`, awkward to
 *  instantiate under a plain JVM unit test -- it wires Android
 *  `BroadcastReceiver`/`Context` access in `OnCreate`) into this standalone,
 *  dependency-free object specifically so the guard DECISION itself is
 *  directly JVM-testable, independent of the module class or any store
 *  implementation.
 *
 *  Rule (pinned, simplest robust option -- see the Task 4 review carry-
 *  forward this resolves, `.superpowers/sdd/task-4-report.md` concern 2):
 *  after a sync that pushes the phone's device-signed `enckey` message
 *  completes with `SyncResult.Ok`, the caller persists the pushed `enc_pub`
 *  as the "published" marker (`SyncStore.setPublishedEncPub`). A later sync
 *  skips composing+pushing a fresh `enckey` (and so skips burning a fresh
 *  `SyncStore.nextSeq()`) iff the marker already equals the CURRENT
 *  `enc_pub`. Consequences that fall out of that one rule, deliberately:
 *   - A regenerated/rotated key (e.g. `EncKeys.getOrCreate` self-healing a
 *     corrupt stored pair) changes `enc_pub`, so the marker no longer
 *     matches -- the next sync republishes automatically, no separate
 *     "rotation" case needed.
 *   - A sync that fails (auth error, revoked, `SyncResult.Failed`, or even
 *     an exception after a real wire push but before the caller reaches the
 *     "mark published" step) must NEVER have the marker set for that
 *     attempt -- callers only call `setPublishedEncPub` from the
 *     `SyncResult.Ok` branch, so a failed sync leaves the marker as it was
 *     and the next `syncNow` call naturally retries the push.
 *   - This is phone-side bookkeeping ONLY, not a correctness mechanism: the
 *     node dedups inbound messages by (identity_pub, device_pub, seq) /
 *     msg_id, so an occasional duplicate `enckey` push (e.g. this device's
 *     marker write racing a process death right after a successful sync) is
 *     harmless there. The guard exists purely to avoid burning a fresh
 *     outbound seq + signed message on every single `syncNow` call. */
object EncKeyPublishGuard {
    /** @param currentEncPub this device's current enc_pub (from
     *  `EncKeys.getOrCreate`).
     *  @param publishedMarker `SyncStore.getPublishedEncPub()` -- null if
     *  this device has never successfully published one.
     *  @return true if `syncNow` should compose a fresh `enckey` message
     *  (via `KotlinSync.composeEncKey` + `SyncStore.nextSeq()`) and include
     *  it in this sync's outbound push. */
    fun shouldPublish(currentEncPub: String, publishedMarker: String?): Boolean =
        publishedMarker != currentEncPub
}
