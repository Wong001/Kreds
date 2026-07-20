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
  const [lastSync, setLastSync] = useState<string>("");
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
    const offBeat = onBeat((b) => setBeats((prev) => [b, ...prev].slice(0, 50)));
    const offProgress = onSyncProgress(({ phase, count }) => {
      setProgressPhase(phase);
      setProgressCounts((prev) => ({ ...prev, [phase]: count }));
    });
    const offSync = onSync((r) => {
      setSyncStats({ messages: r.messages, blobs: r.blobs, identities: r.identities });
      setLastSync(r.ok
        ? `synced: ${r.messages} msgs, ${r.blobs} blobs, ${r.identities} friends`
        : `sync failed: ${r.reason}`);
      // Terminal event: the live progress line resolves into lastSync above.
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
      {!!lastSync && <Text style={lastSync.startsWith("sync failed") ? styles.fail : styles.ok}>{lastSync}</Text>}
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
      <Text style={styles.subtitle}>Heartbeats ({beats.length})</Text>
      <FlatList
        style={styles.list}
        data={beats}
        keyExtractor={(b) => String(b.ts)}
        renderItem={({ item }) => (
          <Text style={item.ok ? styles.ok : styles.fail}>
            {new Date(item.ts).toLocaleTimeString()} {item.ok ? `OK ${item.latencyMs}ms` : `FAIL ${item.reason}`}
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
