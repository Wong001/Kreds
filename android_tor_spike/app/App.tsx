import React, { useCallback, useEffect, useState } from "react";
import {
  Button, FlatList, Image, Modal, Pressable, SafeAreaView, StyleSheet, Text,
  TouchableOpacity, View,
} from "react-native";
import { useVideoPlayer, VideoView } from "expo-video";
import {
  Beat, beatNow, FeedItem, getBlobImage, getFeed, getHistory, getSyncStats, getVideoUrl,
  isBatteryExempt, onBeat, onState, onSync, onSyncProgress, requestBatteryExemption, startNode,
  stopNode, syncNow, SyncStats,
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

// Task 3 (B.2d-2): mounts the actual expo-video player -- ONLY once a real
// getVideoUrl() URL is known (the parent FullscreenVideo below gates this).
// useVideoPlayer's underlying useReleasingSharedObject releases the player
// both when its `source` dependency changes AND on unmount (verified against
// expo-modules-core's implementation) -- so this component intentionally
// stays as thin as possible: mounted only while the fullscreen viewer shows
// this exact video, unmounted (releasing the player) the instant it
// doesn't, whether that's the user closing the modal (fullscreen -> null in
// App) or switching to a different item.
function VideoPlayerView({ uri }: { uri: string }) {
  const player = useVideoPlayer(uri, (p) => { p.play(); });
  return (
    <VideoView player={player} style={styles.fullscreenImage} nativeControls contentFit="contain" />
  );
}

// Task 3 (B.2d-2): resolves (msgId, videoHash) -> a getVideoUrl() URL, lazily
// and per-pair, same shape as the Thumbnail/fullImage effects above --
// undefined = loading, null = resolved but unavailable (no content key /
// server couldn't start), matching getVideoUrl's own null-on-any-miss
// contract. VideoPlayerView (and therefore the player it owns) is only ever
// mounted once a non-null uri is in hand.
function FullscreenVideo({ msgId, hash }: { msgId: string; hash: string }) {
  const [uri, setUri] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    setUri(undefined);
    getVideoUrl(msgId, hash)
      .then((r) => { if (!cancelled) setUri(r); })
      .catch(() => { if (!cancelled) setUri(null); });
    return () => { cancelled = true; };
  }, [msgId, hash]);

  if (uri === undefined) return <Text style={styles.fullscreenState}>Loading…</Text>;
  if (uri === null) return <Text style={styles.fullscreenState}>Media unavailable</Text>;
  return <VideoPlayerView uri={uri} />;
}

export default function App() {
  const [state, setState] = useState("stopped");
  const [beats, setBeats] = useState<Beat[]>([]);
  const [exempt, setExempt] = useState(true);
  const [syncStats, setSyncStats] = useState<SyncStats>({ messages: 0, blobs: 0, identities: 0 });
  // Task 3 (Brick C): the outcome of the last MANUAL "Sync now" tap
  // specifically (via the nodeSync terminal event) -- distinct from the
  // "last sync" line below, which reflects the latest sync overall
  // (background-service or manual) via getHistory. Named syncNowResult
  // (not lastSync) to keep that distinction visible in the code.
  // {text, kind} rather than a bare string (review fix): `kind` is set
  // directly from r.ok/r.skipped in the onSync handler below, so the
  // render's style choice never has to string-match `text` -- the fragile
  // pattern this fix removes (r.reason's exact wording is display text
  // only, never load-bearing for neutral-vs-red).
  const [syncNowResult, setSyncNowResult] = useState<{ text: string; kind: "ok" | "skip" | "fail" } | null>(null);
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
  // isVideo (B.2d-2 Task 3): distinguishes a video post's fullscreen open
  // (hash is the video blob -- blobs[0] -- for getVideoUrl) from a photo's
  // (hash is the full-res blob for getBlobImage, unchanged) -- the Modal
  // body below renders the expo-video player for the former, the existing
  // Image path for the latter.
  const [fullscreen, setFullscreen] = useState<{ msgId: string; hash: string; isVideo: boolean } | null>(null);
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

  const openFullscreen = useCallback((msgId: string, hash: string, isVideo: boolean) => {
    setFullscreen({ msgId, hash, isVideo });
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
        setSyncNowResult({ kind: "ok", text: `synced: ${r.messages} msgs, ${r.blobs} blobs, ${r.identities} friends` });
      } else if (r.skipped) {
        // Task 3 review fix: r.skipped is the dedicated native flag (set by
        // TorManagerModule's syncNow only on the outcome.ran == false
        // branch) -- NOT a match on r.reason's text. A concurrent sync
        // (almost certainly the 15-min background one) already held the
        // mutex; this is a skip, not a failure, so it gets a neutral note
        // rather than the red fail styling, and leaves syncStats alone
        // (r's counts are meaningless zeros here).
        setSyncNowResult({ kind: "skip", text: "sync already in progress - the background sync is running, try again shortly" });
      } else {
        setSyncStats({ messages: r.messages, blobs: r.blobs, identities: r.identities });
        setSyncNowResult({ kind: "fail", text: `sync failed: ${r.reason}` });
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
  // isVideo guard (B.2d-2 Task 3): a video's fullscreen open is handled
  // entirely by <FullscreenVideo> below (getVideoUrl, not getBlobImage) --
  // skip this fetch so opening a video never wastes a pointless
  // decrypt-blob-as-image attempt (it would fail to decode and resolve to
  // null anyway, since KotlinImageDecode can't render an mp4).
  useEffect(() => {
    if (!fullscreen || fullscreen.isVideo) return;
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
          the onBeat->refresh() wiring in the effect above. `skipped` (review
          fix) is the native flag, not a match on `reason`'s text -- a
          background mutex-skip renders neutral here too, not red. */}
      {beats[0] ? (
        <Text style={beats[0].skipped ? styles.neutral : beats[0].ok ? styles.ok : styles.fail}>
          last sync: {new Date(beats[0].ts).toLocaleTimeString()} — {beats[0].skipped
            ? (beats[0].reason ?? "sync in progress")
            : beats[0].ok
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
          syncNowResult.kind === "fail" ? styles.fail
          : syncNowResult.kind === "skip" ? styles.neutral
          : styles.ok
        }>{syncNowResult.text}</Text>
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
              {/* Task 3 (B.2d-2): a video post (media === "video", always
                  carrying exactly one blob -- the video file -- per hearth's
                  validate_payload) renders its `poster` still through the
                  SAME Thumbnail/getBlobImage path as any other image (the
                  poster is just another blob hash under this message's same
                  content key) but with fullHash wired to the VIDEO blob
                  (blobs[0]), not the poster -- tapping it opens the
                  fullscreen viewer against the video, not the still. A play
                  overlay marks it as playable. A photo post (media !==
                  "video", or no poster) is UNCHANGED: the original
                  blobs.map(Thumbnail) loop below, untouched. */}
              {item.media === "video" && item.poster ? (
                <View style={styles.thumbRow}>
                  <View style={styles.videoThumbWrap}>
                    <Thumbnail
                      key={`${item.blobs[0]}-video`}
                      msgId={item.msgId}
                      displayHash={item.poster}
                      fullHash={item.blobs[0]}
                      onOpen={(msgId, hash) => openFullscreen(msgId, hash, true)}
                    />
                    <View style={styles.playOverlay} pointerEvents="none">
                      <Text style={styles.playOverlayText}>▶</Text>
                    </View>
                  </View>
                </View>
              ) : item.blobs.length > 0 ? (
                <View style={styles.thumbRow}>
                  {item.blobs.map((hash, i) => (
                    <Thumbnail
                      key={`${hash}-${i}`}
                      msgId={item.msgId}
                      displayHash={item.thumbs[i] ?? hash}
                      fullHash={hash}
                      onOpen={(msgId, hash) => openFullscreen(msgId, hash, false)}
                    />
                  ))}
                </View>
              ) : null}
            </View>
          )}
        />
      )}
      {/* Task 3 (Brick C): renamed from "Heartbeats" -- the background
          service now runs a full content sync on this cadence (was a bare
          AUTH heartbeat pre-Brick-C), so each row is a sync result. Ok rows
          show the pulled counts (now on every getHistory() entry) in place
          of the old raw latencyMs. A `skipped` row (review fix: the native
          flag, not a match on `reason`'s text) is a benign background
          mutex-skip, not a failure -- rendered neutral/SKIP, not red/FAIL. */}
      <Text style={styles.subtitle}>Recent syncs ({beats.length})</Text>
      <FlatList
        style={styles.list}
        data={beats}
        keyExtractor={(b) => String(b.ts)}
        renderItem={({ item }) => (
          <Text style={item.skipped ? styles.neutral : item.ok ? styles.ok : styles.fail}>
            {new Date(item.ts).toLocaleTimeString()} {item.skipped
              ? `SKIP ${item.reason ?? "sync in progress"}`
              : item.ok
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
          {/* Task 3 (B.2d-2): fullscreen.isVideo routes to the expo-video
              player (FullscreenVideo, mounted/unmounted with this branch --
              see its doc for why that alone releases the player on close);
              everything below is the pre-existing photo path, unchanged. */}
          {fullscreen?.isVideo ? (
            <FullscreenVideo msgId={fullscreen.msgId} hash={fullscreen.hash} />
          ) : (
            <>
              {fullImage === undefined && <Text style={styles.fullscreenState}>Loading…</Text>}
              {fullImage === null && <Text style={styles.fullscreenState}>Media unavailable</Text>}
              {!!fullImage && (
                <Image source={{ uri: fullImage }} style={styles.fullscreenImage} resizeMode="contain" />
              )}
            </>
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
  // Task 3 (B.2d-2): the ▶ play-overlay badge on a video post's poster tile.
  videoThumbWrap: { position: "relative" },
  playOverlay: {
    position: "absolute", top: 0, left: 0, right: 0, bottom: 0,
    alignItems: "center", justifyContent: "center",
  },
  playOverlayText: { color: "#fff", fontSize: 22, textShadowColor: "rgba(0,0,0,0.7)", textShadowRadius: 3 },
  fullscreenBackdrop: {
    flex: 1, backgroundColor: "rgba(0,0,0,0.92)", alignItems: "center", justifyContent: "center",
  },
  fullscreenImage: { width: "100%", height: "100%" },
  fullscreenState: { color: "#fff", fontSize: 16 },
  warn: { backgroundColor: "#fff3cd", padding: 10, borderRadius: 6, gap: 6 },
  warnText: { fontSize: 14 },
});
