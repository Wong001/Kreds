import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Button, FlatList, Image, Modal, Pressable, SafeAreaView, StyleSheet, Text,
  TouchableOpacity, View,
} from "react-native";
import { useVideoPlayer, VideoView } from "expo-video";
import {
  Beat, beatNow, FeedItem, getBlobImage, getFeed, getHistory, getStories, getStoryImage,
  getStoryVideoUrl, getSyncStats, getVideoUrl, isBatteryExempt, onBeat, onState, onSync,
  onSyncProgress, requestBatteryExemption, startNode, stopNode, StoryItem, syncNow, SyncStats,
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

// Task 3 (B.2d-3): the fullscreen story viewer's media body -- STORIES ARE
// PLAINTEXT (no content key, see getStoryImage/getStoryVideoUrl's doc in
// modules/tor-manager), so this fetches via those two functions, never
// getBlobImage/getVideoUrl (which decrypt). Same lazy-per-item, undefined/
// null-distinct loading contract as Thumbnail/FullscreenVideo above --
// undefined = loading, null = resolved but unavailable (not yet
// downloaded, or the isolated decoder rejected it), shown as a "media
// unavailable" placeholder distinct from loading, matching the brief's
// fail-closed requirement. The caller (App's Modal body) mounts this with
// `key={story.msgId}` so advancing to a new story -- photo or video --
// always fully unmounts the previous instance (and, for a video, its
// VideoPlayerView/player) rather than relying on prop-diffing inside a
// kept-alive component: with the fullscreen viewer staying open across an
// entire cycle (unlike the single-post FullscreenVideo above, which only
// ever mounts once per open/close), an explicit remount boundary is the
// simplest guarantee that the previous story's player is always released,
// not just usually.
function StoryMedia({ story }: { story: StoryItem }) {
  const [uri, setUri] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    setUri(undefined);
    const fetcher = story.mediaKind === "video" ? getStoryVideoUrl : getStoryImage;
    fetcher(story.media)
      .then((r) => { if (!cancelled) setUri(r); })
      .catch(() => { if (!cancelled) setUri(null); });
    return () => { cancelled = true; };
  }, [story.msgId, story.mediaKind, story.media]);

  if (uri === undefined) return <Text style={styles.fullscreenState}>Loading…</Text>;
  if (uri === null) return <Text style={styles.fullscreenState}>Media unavailable</Text>;
  if (story.mediaKind === "video") return <VideoPlayerView uri={uri} />;
  return <Image source={{ uri }} style={styles.fullscreenImage} resizeMode="contain" />;
}

// Task 3 (B.2d-3): the story-reply-DM chip's thumbnail -- resolves
// story_ref.media_hash via getStoryImage (plaintext, same as any other
// story media; there is no content key to decrypt with). Collapses
// undefined (loading) and null (unavailable) into the SAME "render
// nothing" outcome -- unlike Thumbnail/StoryMedia above, the brief's
// contract here is specifically "show the chip text without the
// thumbnail" on a miss, and a mid-flight loading placeholder would just be
// a distracting flash for this small an element; the chip's text (in the
// parent) is unconditional either way, so nothing is ever silently blank.
function StoryReplyThumb({ hash }: { hash: string }) {
  const [uri, setUri] = useState<string | null | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    setUri(undefined);
    getStoryImage(hash)
      .then((r) => { if (!cancelled) setUri(r); })
      .catch(() => { if (!cancelled) setUri(null); });
    return () => { cancelled = true; };
  }, [hash]);

  if (!uri) return null;
  return <Image source={{ uri }} style={styles.storyReplyThumb} resizeMode="cover" />;
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
  // Task 3 (B.2d-3): unexpired stories (getStories() already filters
  // expiry -- see its doc), refreshed on mount + every onSync below. []
  // (not null) is both the initial and the empty-after-fetch state -- the
  // strip's contract is simply "hide when there's nothing to show" (brief
  // Step 1), unlike feed's null/[] loading-vs-empty distinction, so no
  // separate loading state is needed here.
  const [stories, setStories] = useState<StoryItem[]>([]);
  // Task 3 (B.2d-3): "story mode" for the existing fullscreen Modal --
  // holds the tapped author's stories (sorted oldest-first, so the viewer
  // plays back in the order they were posted) and the index currently
  // shown. null = story mode is not active (the Modal, if visible at all,
  // is showing a post/video via `fullscreen` instead). Kept as a separate
  // piece of state from `fullscreen` rather than folded into one type: the
  // two viewers have disjoint shapes (an index over a list vs. a single
  // msgId/hash/isVideo) and are opened from disjoint UI (the story strip
  // vs. a feed thumbnail) that can never be visible at the same time (the
  // Modal covers the whole screen), so there is no real ambiguity from
  // keeping them apart -- see the Modal's `visible`/`onRequestClose` below
  // for how the two are combined.
  const [storyViewer, setStoryViewer] = useState<{ items: StoryItem[]; index: number } | null>(null);

  const refresh = useCallback(async () => setBeats(await getHistory()), []);
  const refreshSyncStats = useCallback(async () => setSyncStats(await getSyncStats()), []);
  const refreshFeed = useCallback(async () => setFeed(await getFeed()), []);
  const refreshStories = useCallback(async () => setStories(await getStories()), []);

  // Task 3 (B.2d-3): group the flat, newest-first StoryItem[] by author for
  // the strip's chips -- Map preserves first-insertion order, so chips
  // come out ordered by each author's MOST RECENT story (the first of
  // theirs encountered while walking the newest-first list), a reasonable
  // default absent any other ordering spec. Recomputed only when `stories`
  // itself changes (not on every render) -- this is the guard against a
  // per-render regrouping storm, same spirit as the fetch-guarding effects
  // above.
  const storyGroups = useMemo(() => {
    const groups: { author: string; authorName: string; items: StoryItem[] }[] = [];
    const byAuthor = new Map<string, { author: string; authorName: string; items: StoryItem[] }>();
    for (const s of stories) {
      let g = byAuthor.get(s.author);
      if (!g) {
        g = { author: s.author, authorName: s.authorName, items: [] };
        byAuthor.set(s.author, g);
        groups.push(g);
      }
      g.items.push(s);
    }
    return groups;
  }, [stories]);

  // Task 7 (B.2d): reset the live-progress line each time a sync starts --
  // syncNow() itself has no return value to hook, so the reset happens at
  // the call site rather than off a native "started" event.
  const handleSyncNow = useCallback(() => {
    setProgressPhase("connecting");
    setProgressCounts({});
    syncNow();
  }, []);

  const openFullscreen = useCallback((msgId: string, hash: string, isVideo: boolean) => {
    // Task 3 (B.2d-3): clears story mode defensively -- see storyViewer's
    // doc above for why the two can't actually overlap via the UI, but
    // this keeps the invariant true in state as well, not just in practice.
    setStoryViewer(null);
    setFullscreen({ msgId, hash, isVideo });
  }, []);

  // Task 3 (B.2d-3): opens story mode for one author's chip -- sorted
  // oldest-first (see storyViewer's doc above), index 0. Guards against an
  // empty items list (shouldn't happen -- storyGroups only ever builds a
  // group from >=1 real StoryItem -- but starting a viewer with nothing to
  // show would be a silent no-op tap otherwise, so this is a defensive,
  // not load-bearing, check).
  const openStoryViewer = useCallback((items: StoryItem[]) => {
    if (items.length === 0) return;
    const sorted = [...items].sort((a, b) => a.createdAt - b.createdAt);
    // Defensive counterpart to openFullscreen's clear above.
    setFullscreen(null);
    setStoryViewer({ items: sorted, index: 0 });
  }, []);

  // Task 3 (B.2d-3): a tap while in story mode advances to the next story;
  // advancing past the last one closes the viewer (brief Step 2) -- same
  // outcome as the hardware-back path (Modal's onRequestClose below, which
  // also always closes rather than advances).
  const advanceStory = useCallback(() => {
    setStoryViewer((prev) => {
      if (!prev) return prev;
      const nextIndex = prev.index + 1;
      return nextIndex >= prev.items.length ? null : { ...prev, index: nextIndex };
    });
  }, []);

  useEffect(() => {
    setExempt(isBatteryExempt());
    refresh();
    refreshSyncStats();
    refreshFeed();
    refreshStories();
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
      // Task 3 (B.2d-3): unlike refreshFeed above, this is NOT gated on a
      // dedicated "storiesUpdated" flag -- nodeSync carries no such field
      // (stories are read straight from the store's activeStories, a
      // separate path from the feedCache feedUpdated describes) -- so this
      // runs on every terminal sync event, per the brief's "on mount + on
      // the onSync event" (unconditional), success/fail/skip alike. Cheap
      // (one native round-trip) and idempotent, so re-running it on a
      // no-op sync (a skip, or a sync that pulled nothing new) is harmless.
      refreshStories();
    });
    return () => { offState(); offBeat(); offProgress(); offSync(); };
  }, [refresh, refreshSyncStats, refreshFeed, refreshStories]);

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
      {/* Task 3 (B.2d-3): the story strip -- one chip per author with
          >=1 unexpired story (storyGroups, derived from `stories` via
          getStories()). Renders NOTHING (no empty box) when there are no
          active stories, per the brief's Step 1 -- the `storyGroups.length
          > 0` guard, not a hidden-but-present empty FlatList. */}
      {storyGroups.length > 0 && (
        <FlatList
          horizontal
          showsHorizontalScrollIndicator={false}
          style={styles.storyStrip}
          data={storyGroups}
          keyExtractor={(g) => g.author}
          renderItem={({ item: g }) => (
            <TouchableOpacity style={styles.storyChip} onPress={() => openStoryViewer(g.items)}>
              <Text style={styles.storyChipText}>{g.authorName}</Text>
              <Text style={styles.storyChipCount}>{g.items.length}</Text>
            </TouchableOpacity>
          )}
        />
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
              {/* Task 3 (B.2d-3): the story-reply chip -- additive to the
                  DM row that already renders above (B.2c); reads the
                  plaintext storyRefMediaHash the native side now surfaces
                  from this DM's outer-payload story_ref (see FeedItem's
                  doc in modules/tor-manager -- DM-only, null for an
                  ordinary DM or any post). The thumbnail is best-effort:
                  StoryReplyThumb renders nothing on a miss, leaving just
                  this chip's text, per the brief. */}
              {item.kind === "dm" && item.storyRefMediaHash && (
                <View style={styles.storyReplyChip}>
                  <StoryReplyThumb hash={item.storyRefMediaHash} />
                  <Text style={styles.storyReplyChipText}>↩ replied to your story</Text>
                </View>
              )}
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
        visible={!!fullscreen || !!storyViewer}
        transparent
        animationType="fade"
        // Task 3 (B.2d-3): hardware back always CLOSES (never advances),
        // whichever of the two viewers is open -- clearing both is
        // harmless since only one is ever non-null at a time (see
        // storyViewer's doc above).
        onRequestClose={() => { setFullscreen(null); setStoryViewer(null); }}
      >
        <Pressable
          style={styles.fullscreenBackdrop}
          // Task 3 (B.2d-3): in story mode a tap ADVANCES (brief Step 2:
          // "tap advances the index"); the pre-existing post/video viewer
          // is unchanged -- a tap there still closes.
          onPress={() => { if (storyViewer) { advanceStory(); } else { setFullscreen(null); } }}
        >
          {/* Task 3 (B.2d-3): story mode takes priority when active (the
              two are mutually exclusive in practice -- opening one clears
              the other, see openStoryViewer/openFullscreen). `key` forces a
              full remount per story -- see StoryMedia's doc for why that
              matters for video-to-video advances within one open Modal
              session, unlike the single-shot fullscreen.isVideo path below
              (unchanged). The caption renders only when non-empty. */}
          {storyViewer ? (
            <>
              <StoryMedia key={storyViewer.items[storyViewer.index].msgId}
                story={storyViewer.items[storyViewer.index]} />
              {!!storyViewer.items[storyViewer.index].caption && (
                <Text style={styles.storyCaption}>{storyViewer.items[storyViewer.index].caption}</Text>
              )}
            </>
          ) : fullscreen?.isVideo ? (
            /* Task 3 (B.2d-2): fullscreen.isVideo routes to the expo-video
               player (FullscreenVideo, mounted/unmounted with this branch --
               see its doc for why that alone releases the player on close);
               everything below is the pre-existing photo path, unchanged. */
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
  // Task 3 (B.2d-3): the story strip's author chips -- minimal dev-dashboard
  // styling only (no progress ring/gradient border etc. -- that's the later
  // visual-parity slice, per the brief).
  storyStrip: { flexGrow: 0, marginBottom: 4 },
  storyChip: {
    backgroundColor: "#eef1ff", borderRadius: 8, paddingVertical: 6, paddingHorizontal: 10,
    marginRight: 8, alignItems: "center", minWidth: 64,
  },
  storyChipText: { fontSize: 13, fontWeight: "600" },
  storyChipCount: { fontSize: 11, color: "#666" },
  storyCaption: { color: "#fff", fontSize: 14, marginTop: 10, paddingHorizontal: 16, textAlign: "center" },
  // Task 3 (B.2d-3): the story-reply-DM chip, additive on a DM feed row.
  storyReplyChip: { flexDirection: "row", alignItems: "center", gap: 6 },
  storyReplyThumb: { width: 28, height: 28, borderRadius: 4 },
  storyReplyChipText: { fontSize: 12, color: "#444", fontStyle: "italic" },
});
