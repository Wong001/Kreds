import React, { useCallback, useEffect, useState } from "react";
import {
  Button, FlatList, Image, Modal, Pressable, SafeAreaView, StyleSheet, Text,
  TouchableOpacity, View,
} from "react-native";
import {
  Beat, beatNow, FeedItem, getBlobImage, getFeed, getHistory, getSyncStats, isBatteryExempt,
  onBeat, onState, onSync, onSyncProgress, requestBatteryExemption, startNode, stopNode,
  syncNow, SyncStats,
} from "./modules/tor-manager";

// Task 7 (B.2d): one feed-row thumbnail. Fetches its own (msgId, displayHash)
// lazily on mount via a component-local effect -- this is the per-hash
// "state" the brief asks for: each Thumbnail instance owns exactly one
// getBlobImage call, guarded by the effect's dependency array so parent
// re-renders (e.g. a sibling row's fetch resolving) never re-trigger it.
// undefined = still loading, null = resolved but unavailable (missing /
// decrypt-fail / non-image such as a video) -- kept distinct from loading
// so the placeholder chip only appears once we actually know there's
// nothing to show.
function Thumbnail({ msgId, displayHash, fullHash, onOpen }: {
  msgId: string; displayHash: string; fullHash: string;
  onOpen: (msgId: string, hash: string) => void;
}) {
  const [uri, setUri] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    setUri(undefined);
    getBlobImage(msgId, displayHash)
      .then((r) => { if (!cancelled) setUri(r); })
      // Defensive: getBlobImage's native contract says it never rejects, but
      // if a bridge error ever did surface here, fail closed into the
      // distinct "media unavailable" placeholder rather than getting stuck
      // on the loading state forever.
      .catch(() => { if (!cancelled) setUri(null); });
    return () => { cancelled = true; };
  }, [msgId, displayHash]);

  if (uri === undefined) {
    return <View style={[styles.thumb, styles.thumbLoading]} />;
  }
  if (uri === null) {
    return (
      <View style={[styles.thumb, styles.thumbPlaceholder]}>
        <Text style={styles.thumbPlaceholderText}>media unavailable</Text>
      </View>
    );
  }
  return (
    <TouchableOpacity onPress={() => onOpen(msgId, fullHash)}>
      <Image source={{ uri }} style={styles.thumb} resizeMode="cover" />
    </TouchableOpacity>
  );
}

export default function App() {
  const [state, setState] = useState("stopped");
  const [beats, setBeats] = useState<Beat[]>([]);
  const [exempt, setExempt] = useState(true);
  const [syncStats, setSyncStats] = useState<SyncStats>({ messages: 0, blobs: 0, identities: 0 });
  // Task 3 (Brick C): the outcome text of the last MANUAL "Sync now" tap
  // specifically (via the nodeSync terminal event) -- distinct from the
  // "last sync" line below, which reflects the latest sync overall
  // (background-service or manual) via getHistory. Named syncNowResult
  // (not lastSync) to keep that distinction visible in the code.
  const [syncNowResult, setSyncNowResult] = useState<string>("");
  // null = not yet fetched this mount (loading); [] = fetched, nothing decrypted yet
  // (distinct empty-state, see the getFeed() render below).
  const [feed, setFeed] = useState<FeedItem[] | null>(null);
  // Task 7 (B.2d): live sync-progress line. progressPhase null = not
  // currently syncing (either never started, or resolved by the terminal
  // nodeSync event -- see offSync below). progressCounts tracks the latest
  // count seen per phase so the status line can read "N messages / M
  // blobs" even after sync has moved on to a later phase.
  const [progressPhase, setProgressPhase] = useState<string | null>(null);
  const [progressCounts, setProgressCounts] = useState<Record<string, number>>({});
  // Task 7 (B.2d): the thumbnail currently open in the fullscreen viewer, if
  // any -- holds the FULL blob hash (never the thumb), per the brief.
  const [fullscreen, setFullscreen] = useState<{ msgId: string; hash: string } | null>(null);
  const [fullImage, setFullImage] = useState<string | null | undefined>(undefined);

  const refresh = useCallback(async () => setBeats(await getHistory()), []);
  const refreshSyncStats = useCallback(async () => setSyncStats(await getSyncStats()), []);
  const refreshFeed = useCallback(async () => setFeed(await getFeed()), []);

  // Task 7 (B.2d): reset the live-progress line each time a sync starts --
  // syncNow() itself has no return value to hook, so the reset happens at
  // the call site rather than off a native "started" event.
  const handleSyncNow = useCallback(() => {
    setProgressPhase("connecting");
    setProgressCounts({});
    syncNow();
  }, []);

  const openFullscreen = useCallback((msgId: string, hash: string) => {
    setFullscreen({ msgId, hash });
  }, []);

  useEffect(() => {
    setExempt(isBatteryExempt());
    refresh();
    refreshSyncStats();
    refreshFeed();
    const offState = onState(setState);
    // Task 3 (Brick C): re-fetch getHistory() on every live beat rather than
    // splicing the raw broadcast payload into state. The background service
    // (every 15 min) and "Beat now" both now run a full content sync, and
    // HeartbeatStore.record() persists the pulled counts BEFORE the
    // broadcast fires -- so by the time this listener runs, getHistory()
    // already has the fully-populated (counts-included) entry. The live
    // "nodeBeat" payload itself still carries only ts/ok/latencyMs/reason
    // (Task 2 left it unchanged), so trusting it directly would show 0
    // msgs/blobs for every freshly-arriving beat until some later refresh.
    const offBeat = onBeat(() => { refresh(); });
    const offProgress = onSyncProgress(({ phase, count }) => {
      setProgressPhase(phase);
      setProgressCounts((prev) => ({ ...prev, [phase]: count }));
    });
    const offSync = onSync((r) => {
      if (r.ok) {
        setSyncStats({ messages: r.messages, blobs: r.blobs, identities: r.identities });
        setSyncNowResult(`synced: ${r.messages} msgs, ${r.blobs} blobs, ${r.identities} friends`);
      } else if (r.reason === "sync already in progress") {
        // Task 3: a concurrent sync (almost certainly the 15-min background
        // one) already held the mutex -- this is a skip, not a failure, so
        // it gets a neutral note rather than the red fail styling, and
        // leaves syncStats alone (r's counts are meaningless zeros here).
        setSyncNowResult("sync already in progress - the background sync is running, try again shortly");
      } else {
        setSyncStats({ messages: r.messages, blobs: r.blobs, identities: r.identities });
        setSyncNowResult(`sync failed: ${r.reason}`);
      }
      // Terminal event: the live progress line resolves into syncNowResult above.
      setProgressPhase(null);
      setProgressCounts({});
      if (r.feedUpdated) refreshFeed();
    });
    return () => { offState(); offBeat(); offProgress(); offSync(); };
  }, [refresh, refreshSyncStats, refreshFeed]);

  // Task 7 (B.2d): fetch the FULL blob (never the thumb) only while the
  // fullscreen viewer is open for it -- lazy, and re-fires per (msgId,
  // hash) pair via the effect dependency array.
  useEffect(() => {
    if (!fullscreen) return;
    let cancelled = false;
    setFullImage(undefined);
    getBlobImage(fullscreen.msgId, fullscreen.hash)
      .then((r) => { if (!cancelled) setFullImage(r); })
      // Same fail-closed guarantee as the Thumbnail effect above.
      .catch(() => { if (!cancelled) setFullImage(null); });
    return () => { cancelled = true; };
  }, [fullscreen]);

  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>Kreds node</Text>
      <Text style={styles.state}>state: {state}</Text>
      {/* Task 3 (Brick C): the latest sync overall (background-service or
          manual), sourced from getHistory via `beats` -- NOT syncNowResult
          below, which is scoped to this device's own manual "Sync now" tap.
          beats[0] is newest-first (HeartbeatStore prepends), kept fresh by
          the onBeat->refresh() wiring in the effect above. */}
      {beats[0] ? (
        <Text style={beats[0].ok ? styles.ok : styles.fail}>
          last sync: {new Date(beats[0].ts).toLocaleTimeString()} — {beats[0].ok
            ? `ok, ${beats[0].messages ?? 0} msgs / ${beats[0].blobs ?? 0} blobs`
            : (beats[0].reason ?? "failed")}
        </Text>
      ) : (
        <Text style={styles.state}>last sync: none yet</Text>
      )}
      {!exempt && (
        <View style={styles.warn}>
          <Text style={styles.warnText}>Battery optimization may kill the node.</Text>
          <Button title="Exempt Kreds from battery optimization"
            onPress={() => { requestBatteryExemption(); setTimeout(() => setExempt(isBatteryExempt()), 500); }} />
        </View>
      )}
      <View style={styles.row}>
        <Button title="Start node" onPress={startNode} />
        <Button title="Stop node" onPress={stopNode} />
        <Button title="Beat now" onPress={beatNow} />
      </View>
      <Text style={styles.subtitle}>Content sync</Text>
      <View style={styles.row}>
        <Button title="Sync now" onPress={handleSyncNow} />
      </View>
      {progressPhase && (
        <Text style={styles.state}>
          Syncing… {progressPhase} — {progressCounts.messages ?? 0} messages / {progressCounts.blobs ?? 0} blobs
        </Text>
      )}
      <Text style={styles.state}>
        messages: {syncStats.messages} / blobs: {syncStats.blobs} / friends: {syncStats.identities}
      </Text>
      {!!syncNowResult && (
        <Text style={
          syncNowResult.startsWith("sync failed") ? styles.fail
          : syncNowResult.startsWith("sync already in progress") ? styles.neutral
          : styles.ok
        }>{syncNowResult}</Text>
      )}
      <Text style={styles.subtitle}>Feed ({feed === null ? "..." : feed.length})</Text>
      {feed === null && <Text style={styles.state}>Loading feed...</Text>}
      {feed !== null && feed.length === 0 && (
        <Text style={styles.state}>No decrypted items yet - Sync now to load your history</Text>
      )}
      {feed !== null && feed.length > 0 && (
        <FlatList
          style={styles.list}
          data={feed}
          keyExtractor={(item) => item.msgId}
          renderItem={({ item }) => (
            <View style={styles.feedRow}>
              <Text style={styles.feedItem}>
                {item.author} · {item.kind} · {new Date(item.createdAt * 1000).toLocaleString()} — {item.text}
              </Text>
              {item.blobs.length > 0 && (
                <View style={styles.thumbRow}>
                  {item.blobs.map((hash, i) => (
                    <Thumbnail
                      key={`${hash}-${i}`}
                      msgId={item.msgId}
                      displayHash={item.thumbs[i] ?? hash}
                      fullHash={hash}
                      onOpen={openFullscreen}
                    />
                  ))}
                </View>
              )}
            </View>
          )}
        />
      )}
      {/* Task 3 (Brick C): renamed from "Heartbeats" -- the background
          service now runs a full content sync on this cadence (was a bare
          AUTH heartbeat pre-Brick-C), so each row is a sync result. Ok rows
          show the pulled counts (now on every getHistory() entry) in place
          of the old raw latencyMs. */}
      <Text style={styles.subtitle}>Recent syncs ({beats.length})</Text>
      <FlatList
        style={styles.list}
        data={beats}
        keyExtractor={(b) => String(b.ts)}
        renderItem={({ item }) => (
          <Text style={item.ok ? styles.ok : styles.fail}>
            {new Date(item.ts).toLocaleTimeString()} {item.ok
              ? `OK ${item.messages ?? 0} msgs / ${item.blobs ?? 0} blobs`
              : `FAIL ${item.reason}`}
          </Text>
        )}
      />
      <Modal
        visible={!!fullscreen}
        transparent
        animationType="fade"
        onRequestClose={() => setFullscreen(null)}
      >
        <Pressable style={styles.fullscreenBackdrop} onPress={() => setFullscreen(null)}>
          {fullImage === undefined && <Text style={styles.fullscreenState}>Loading…</Text>}
          {fullImage === null && <Text style={styles.fullscreenState}>Media unavailable</Text>}
          {!!fullImage && (
            <Image source={{ uri: fullImage }} style={styles.fullscreenImage} resizeMode="contain" />
          )}
        </Pressable>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 20, gap: 10 },
  title: { fontSize: 22, fontWeight: "700" },
  subtitle: { fontSize: 16, fontWeight: "600", marginTop: 8 },
  state: { fontSize: 16 },
  row: { flexDirection: "row", gap: 8, flexWrap: "wrap" },
  list: { flex: 1 },
  ok: { fontSize: 14, color: "#1a7f37", paddingVertical: 2 },
  fail: { fontSize: 14, color: "#b00020", paddingVertical: 2 },
  // Task 3 (Brick C): "sync already in progress" is a skip, not a failure --
  // gray, distinct from both ok (green) and fail (red).
  neutral: { fontSize: 14, color: "#666666", paddingVertical: 2 },
  feedItem: { fontSize: 14, paddingVertical: 2 },
  feedRow: { paddingVertical: 2, gap: 4 },
  thumbRow: { flexDirection: "row", gap: 6, flexWrap: "wrap" },
  thumb: { width: 64, height: 64, borderRadius: 6 },
  thumbLoading: { backgroundColor: "#e0e0e0" },
  thumbPlaceholder: { backgroundColor: "#e0e0e0", alignItems: "center", justifyContent: "center", padding: 4 },
  thumbPlaceholderText: { fontSize: 10, color: "#666", textAlign: "center" },
  fullscreenBackdrop: {
    flex: 1, backgroundColor: "rgba(0,0,0,0.92)", alignItems: "center", justifyContent: "center",
  },
  fullscreenImage: { width: "100%", height: "100%" },
  fullscreenState: { color: "#fff", fontSize: 16 },
  warn: { backgroundColor: "#fff3cd", padding: 10, borderRadius: 6, gap: 6 },
  warnText: { fontSize: 14 },
});
