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
