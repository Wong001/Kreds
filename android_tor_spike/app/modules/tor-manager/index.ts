// The narrow manager interface the whole client will depend on (spec):
// bootstrap / socksPort / dial / suspend, plus the fixtureDir constant.
import { requireNativeModule } from "expo-modules-core";
import { fromByteArray, toByteArray } from "base64-js";
import type { Stream } from "../../src/wire";

const native = requireNativeModule("TorManager");

export const fixtureDir: string = native.fixtureDir;

export function onProgress(cb: (p: number) => void): () => void {
  const sub = native.addListener("torProgress", (e: { progress: number }) => cb(e.progress));
  return () => sub.remove();
}

export function bootstrap(): Promise<number> {
  return native.bootstrap();
}

export function socksPort(): number {
  return native.socksPort();
}

export class TorStream implements Stream {
  constructor(private id: number) {}
  async read(n: number): Promise<Uint8Array> {
    return toByteArray(await native.recv(this.id, n));
  }
  async write(b: Uint8Array): Promise<void> {
    await native.send(this.id, fromByteArray(b));
  }
  close(): void {
    native.closeConn(this.id);
  }
}

export async function dial(host: string, port: number): Promise<TorStream> {
  return new TorStream(await native.dial(host, port));
}

export function suspendTor(): Promise<void> {
  return native.suspendTor();
}

// `messages`/`blobs`/`identities` (Brick C Task 3): the pulled counts
// HeartbeatStore now persists alongside each Beat (Task 2). Present on every
// getHistory() entry -- the native mapping defaults them to 0 for pre-Brick-C
// history and always includes the keys, see TorManagerModule.getHistory --
// but ABSENT on the live "nodeBeat" broadcast/event, which Task 2 left
// unchanged (ts/ok/latencyMs/reason only). Optional here so both shapes
// typecheck under the one Beat type the `beats` dashboard state mixes them
// into; App.tsx's onBeat handler re-runs getHistory() on every live beat
// rather than trusting the (count-less) live payload, so the dashboard's
// derived "last sync" line always reads from the count-carrying source.
// `skipped` (Task 3 review fix): true iff this Beat is a benign mutex skip
// (TorNodeService's syncCycle saw SyncRunner.SyncOutcome.ran == false) --
// a dedicated flag, NOT to be inferred from `reason`'s text. Same
// availability shape as the counts above: present on getHistory() entries,
// absent on the live nodeBeat broadcast (defaults to false via `??` at
// call sites).
export interface Beat {
  ts: number; ok: boolean; latencyMs: number; reason: string | null;
  messages?: number; blobs?: number; identities?: number; skipped?: boolean;
}

export function startNode(): void { native.startNode(); }
export function stopNode(): void { native.stopNode(); }
export function beatNow(): void { native.beatNow(); }
export function getHistory(): Promise<Beat[]> { return native.getHistory(); }
export function isBatteryExempt(): boolean { return native.isBatteryExempt(); }
export function requestBatteryExemption(): void { native.requestBatteryExemption(); }

export function onBeat(cb: (b: Beat) => void): () => void {
  const sub = native.addListener("nodeBeat", (e: Beat) => cb(e));
  return () => sub.remove();
}
export function onState(cb: (s: string) => void): () => void {
  const sub = native.addListener("nodeState", (e: { state: string }) => cb(e.state));
  return () => sub.remove();
}

// -- Brick B.1: foreground-triggered content sync --
export interface SyncStats { messages: number; blobs: number; identities: number }

export function syncNow(): void { native.syncNow(); }
export function getSyncStats(): Promise<SyncStats> { return native.getSyncStats(); }

// -- Brick B.2 (Task 7): decrypted own-authored readable history --
// Mirrors DecryptPass.Decrypted (Kotlin) -- msg_id/kind/decrypted text/
// created_at for each own post or DM this device could decrypt (via an
// inline wrap or a backfilled wrap_grant). Populated by syncNow (re-run
// after every successful sync); getFeed() just reads that cache -- see
// TorManagerModule's feedCache doc comment.
// `author` (B.2c Task 3): the resolved display name for the message's
// author -- the latest stored profile name for that identity, else
// "friend-" + identityPub.take(8) (or "me" for our own identity with no
// stored profile yet). See DecryptPass.resolveAuthor.
// `blobs`/`thumbs` (B.2d Task 5): hash REFERENCES only -- never blob bytes
// or key material -- resolved on demand via getBlobImage(msgId, hash).
// `thumbs` is (string | null)[], position-aligned with `blobs`: hearth
// legitimately records a null entry for a photo whose thumbnail generation
// failed (see DecryptPass.Decrypted's doc).
// `media`/`poster` (B.2d-2 Task 1): plaintext OUTER-PAYLOAD envelope fields
// -- "photo" (default) or "video" -- mirroring DecryptPass.Decrypted.media/
// poster (see its Kotlin doc comment). `poster` is a hex64 blob-hash
// reference to the video's AVIF still (resolved the same way as any other
// blob/thumb hash, via getBlobImage), or null for a photo post.
// `storyRefMediaHash` (B.2d-3 Task 3, gap fix): plaintext, DM-only
// outer-payload field -- mirrors DecryptPass.Decrypted.storyRefMediaHash
// (its Kotlin doc has the full disclosure-class reasoning, same class as
// media/poster above). Present (a hex64 blob hash) iff this DM's payload
// carried a shape-valid `story_ref`; null for an ordinary DM or any post.
// The story-reply chip's ONLY consumer: resolves via getStoryImage(hash) --
// plaintext, same as any other story media -- for the "replied to your
// story" thumbnail on this DM's feed row.
// `responses` (B.2d-4 Task 3): this post's aggregated engagement view --
// mirrors TorManagerModule's getFeed marshal of DecryptPass.responsesPass's
// per-target KotlinResponses.Responses (reactions tally + resolved comment
// list) via the responsesByPost cache. null when this post has no valid,
// decryptable KIND_RESPONSES record from its own author yet (no engagement,
// or nothing attributable) -- the pass is fail-closed, so every such case
// collapses to the same null, never a partial/wrong result. `color` is the
// alias hue (0..359) when a comment's `display` is an anonymous alias, or
// null when `display` is a real resolved name (see KotlinResponses.Comment/
// resolveDisplay's own doc for the attribution rule this mirrors).
export interface FeedItem {
  msgId: string; kind: string; author: string; text: string; createdAt: number;
  blobs: string[]; thumbs: (string | null)[];
  media: string; poster: string | null;
  storyRefMediaHash: string | null;
  responses?: {
    reactions: Record<string, number>;
    comments: { body: string; display: string; color: number | null; createdAt: number }[];
  } | null;
}

export function getFeed(): Promise<FeedItem[]> { return native.getFeed(); }

// Task 5 (B.2d): resolves one blob/thumb hash reference (from a FeedItem's
// `blobs`/`thumbs`) into a displayable `data:<mime>;base64,<...>` URI, or
// null on any miss (not yet synced, no content key, decrypt/decode
// failure) -- callers show a placeholder on null, never treat it as an
// error. Lazy: nothing is decrypted/decoded until a caller actually asks
// for this specific (msgId, hash) pair (e.g. when a feed image scrolls
// into view), and nothing here is cached on the JS side -- the native
// side re-derives it from blobKeys + the stored cipher blob on every call.
export function getBlobImage(msgId: string, hash: string): Promise<string | null> {
  return native.getBlobImage(msgId, hash);
}

// Task 3 (B.2d-2): resolves a video post's full blob hash (FeedItem.blobs[0]
// when media === "video") into a http://127.0.0.1 URL a platform video
// player can stream (range requests included) -- backed by TorManagerModule's
// lazily-started, token-guarded loopback MediaServer (see its Kotlin class
// doc). null on the same misses getBlobImage returns null for (no content
// key for msgId -- not yet synced / not entitled), checked BEFORE the server
// is ever started. Nothing is cached on the JS side, same as getBlobImage --
// each call re-resolves the URL (the server itself, once started, is reused
// across calls for this module's lifetime).
export function getVideoUrl(msgId: string, hash: string): Promise<string | null> {
  return native.getVideoUrl(msgId, hash);
}

// vp1 (Task 3): resolves the loopback web server's one-time-token URL
// (http://127.0.0.1:<port>/?__t=<token>) that WebShell loads into its
// WebView -- see TorManagerModule.getWebUrl (Task 2) for the server/token
// lifecycle. null if the server isn't available.
export function getWebUrl(): Promise<string | null> {
  return native.getWebUrl();
}

// Task 6 (B.2d): live sync-progress feedback -- the sync takes 1-2 min
// on-device with no visible activity otherwise. Forwards KotlinSync.run's
// phase-boundary callbacks (fired from the native side as they happen,
// not batched) plus a trailing "done" phase the module emits once the
// post-sync decrypt pass (getFeed's cache) is ready. `phase` is one of
// "connecting" | "handshake" | "messages" | "blobs" | "decrypting" | "done"
// -- not a closed union here (deliberately: a future phase name is a
// forward-compatible no-op for any listener that only recognizes a
// subset, no need to update this type union in lockstep with the native
// side). `count` is phase-specific (e.g. running message/blob counts
// during "messages"/"blobs", the decrypted feed size for "done") and 0 for
// phases with nothing to count yet. Purely additive observability --
// never changes what syncNow does, only what it reports while doing it;
// the existing onSync (terminal nodeSync event) is unaffected.
export function onSyncProgress(cb: (p: { phase: string; count: number }) => void): () => void {
  const sub = native.addListener("onSyncProgress", (e: { phase: string; count: number }) => cb(e));
  return () => sub.remove();
}

// -- B.2d-3 Task 2: plaintext story render paths + getStories --
// Stories carry no content key (see the Kotlin StoredStory/SyncStore doc) --
// getStoryImage/getStoryVideoUrl never decrypt anything, unlike
// getBlobImage/getVideoUrl above (which resolve a POST's blob through a
// content key). A story's AVIF stills still go through the isolated
// :imagedecode process on the native side (KotlinImageDecode.toRenderable) --
// that isolation is about untrusted (friend-authored) image bytes, which a
// story's media still is, independent of encryption.
export interface StoryItem {
  msgId: string; author: string; authorName: string;
  mediaKind: string; media: string; poster: string | null;
  caption: string; createdAt: number;
}

// The active (unexpired) story list, newest-first -- see the native
// activeStories/getStories doc for the expiry rule and authorName fallback.
// Nothing here is cached on the JS side (mirrors getFeed's own polling
// pattern): call again to pick up a story that just expired or one a sync
// just pulled in.
export function getStories(): Promise<StoryItem[]> {
  return native.getStories();
}

// Resolves a story's image/poster blob hash (StoryItem.media when
// mediaKind === "photo", or StoryItem.poster for a video story) into a
// displayable `data:<mime>;base64,<...>` URI, or null on any miss (not yet
// synced, or a format the decoder can't render) -- same null-means-
// placeholder contract as getBlobImage. Deliberately takes only `hash`, no
// `msgId`: unlike getBlobImage there is no content key to look up, so there
// is nothing an msgId would add.
export function getStoryImage(hash: string): Promise<string | null> {
  return native.getStoryImage(hash);
}

// Resolves a video story's full blob hash (StoryItem.media when
// mediaKind === "video") into a http://127.0.0.1 URL a platform video player
// can stream (range requests included) -- backed by the same loopback
// MediaServer getVideoUrl uses. null if the server can't start / the module
// has been destroyed, mirroring getVideoUrl's null handling for those cases
// (there is no content-key gate here the way getVideoUrl has one, since
// stories have no content key to gate on).
export function getStoryVideoUrl(hash: string): Promise<string | null> {
  return native.getStoryVideoUrl(hash);
}

// -- Task 7 (first-load pairing): hasIdentity/pairWithNode ceremony bridge --
// hasIdentity is a plain (sync) Function, same shape as isBatteryExempt --
// a cheap local file check (PairingStore.hasIdentity, internal-then-legacy
// dual read), never network I/O. index.ts's gate calls this once to decide
// FirstLoad vs WebShell.
export function hasIdentity(): boolean { return native.hasIdentity(); }

// The 5 fixed ceremony outcomes (TorManagerModule.pairWithNode's doc): the
// UI contract FirstLoad.tsx switches on. "unreachable" additionally carries
// `reason` (dial/timeout/io/bad-package detail, for the small-print under
// the retryable error message -- never parsed, display only).
export type PairStatus = "linked" | "denied" | "expired" | "unreachable" | "bad_link";
export interface PairResult { status: PairStatus; reason?: string }

// Runs the full pairing ceremony (dial -> pair-request -> one bounded
// reply -> install+persist) off the main thread. Never rejects for an
// ordinary ceremony outcome -- see TorManagerModule.pairWithNode's doc --
// so callers switch on `status` rather than try/catch. Requires Tor already
// bootstrapped (the native side fast-fails "unreachable" otherwise, with NO
// pairProgress events -- see TorManagerModule's `TorEngine.isUp` guard);
// callers must `await bootstrap()` first, same as WebShell's own mount
// sequence (bootstrap() is idempotent, so this never double-pays the cost
// if Tor is already up from an earlier bootstrap() call).
export function pairWithNode(link: string, deviceName: string): Promise<PairResult> {
  return native.pairWithNode(link, deviceName);
}

// Progress events for a pairWithNode() ceremony currently in flight:
// "dialing" (right before the SOCKS dial to the desktop's onion address)
// then "waiting" (request frame written, now blocking on the human's
// Accept/Deny click on the desktop -- can legitimately run for most of 10
// minutes, see PAIR_TIMEOUT_MS's doc in TorManagerModule.kt). No event
// marks the local install step that follows a "waiting" reply -- it's
// synchronous and already complete by the time pairWithNode's promise
// resolves.
export function onPairProgress(cb: (p: { stage: string }) => void): () => void {
  const sub = native.addListener("pairProgress", (e: { stage: string }) => cb(e));
  return () => sub.remove();
}

// Task 6 (phone-onion-reachability): fires once this device's own identity
// has just been wiped -- TorNodeService.enterRevokedState (self-revoked, or
// a sibling device observing our revocation) deletes pairing.json + the
// synced store and broadcasts, which the native module bridges to this
// event (registered in TorManagerModule's Events(...) list -- an
// unregistered event name silently drops, see that list's own comment).
// The payload carries nothing (there is nothing left to report); the
// listener's whole job is to re-check hasIdentity() (now false) and drop
// back to the First-Load menu -- mirrors how onPairProgress/onState are
// consumed, see FirstLoad.tsx.
export function onRevoked(cb: () => void): () => void {
  const sub = native.addListener("revoked", () => cb());
  return () => sub.remove();
}

export function onSync(cb: (r: {
  ok: boolean; messages: number; blobs: number; identities: number; reason?: string;
  // Task 7 (B.2): true iff this sync completed successfully and the decrypted
  // feed cache (getFeed()) was re-run against its result -- false on any
  // failure path, in which case the feed cache is unchanged from before this
  // call. Always present (the native side emits it on every nodeSync event,
  // success or failure); existing callers that don't read it are unaffected.
  feedUpdated: boolean;
  // `skipped` (Task 3 review fix): true iff this terminal event is a benign
  // mutex skip (a concurrent sync -- almost always the 15-min background
  // one -- already held SyncRunner's process-wide lock), not a real
  // failure. Always present (the module's `emit` defaults it false and sets
  // it true only on the outcome.ran == false branch) -- the dedicated
  // source of truth for the dashboard's neutral-vs-red decision. `reason`
  // ("sync already in progress") is display text only; do not string-match
  // it to detect a skip.
  skipped: boolean;
}) => void): () => void {
  const sub = native.addListener("nodeSync", (e: any) => cb(e));
  return () => sub.remove();
}
