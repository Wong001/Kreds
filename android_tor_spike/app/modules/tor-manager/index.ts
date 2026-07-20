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
export interface Beat {
  ts: number; ok: boolean; latencyMs: number; reason: string | null;
  messages?: number; blobs?: number; identities?: number;
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
export interface FeedItem {
  msgId: string; kind: string; author: string; text: string; createdAt: number;
  blobs: string[]; thumbs: (string | null)[];
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

export function onSync(cb: (r: {
  ok: boolean; messages: number; blobs: number; identities: number; reason?: string;
  // Task 7 (B.2): true iff this sync completed successfully and the decrypted
  // feed cache (getFeed()) was re-run against its result -- false on any
  // failure path, in which case the feed cache is unchanged from before this
  // call. Always present (the native side emits it on every nodeSync event,
  // success or failure); existing callers that don't read it are unaffected.
  feedUpdated: boolean;
}) => void): () => void {
  const sub = native.addListener("nodeSync", (e: any) => cb(e));
  return () => sub.remove();
}
