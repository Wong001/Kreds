// Desk-only Stream over node:net -- the phone uses TorStream instead.
// Contract: read(n) resolves with exactly n bytes as soon as they exist,
// rejects on EOF/error; write resolves on flush; close destroys.
import * as net from "node:net";
import { Stream } from "../src/wire";

export function connectTcp(host: string, port: number): Promise<Stream> {
  return new Promise((resolve, reject) => {
    const sock = net.createConnection({ host, port });
    let buffer = Buffer.alloc(0);
    let ended = false;
    let failure: Error | null = null;
    let waiting: { n: number; ok: (b: Uint8Array) => void; err: (e: Error) => void } | null = null;

    // Single delivery path: called on every state change (data/end/error
    // and at the start of each read).
    const pump = () => {
      if (!waiting) return;
      const w = waiting;
      if (buffer.length >= w.n) {
        waiting = null;
        const out = new Uint8Array(buffer.subarray(0, w.n));
        buffer = buffer.subarray(w.n);
        w.ok(out);
      } else if (failure) {
        waiting = null;
        w.err(failure);
      } else if (ended) {
        waiting = null;
        w.err(new Error(`EOF: wanted ${w.n} bytes, have ${buffer.length}`));
      }
    };

    sock.on("data", (c) => { buffer = Buffer.concat([buffer, c]); pump(); });
    sock.on("end", () => { ended = true; pump(); });
    sock.on("error", (e) => { failure = e; pump(); reject(e); });
    sock.on("connect", () => resolve({
      read(n: number): Promise<Uint8Array> {
        return new Promise((ok, err) => {
          if (waiting) { err(new Error("concurrent read")); return; }
          waiting = { n, ok, err };
          pump();
        });
      },
      write(b: Uint8Array): Promise<void> {
        return new Promise((ok, err) =>
          sock.write(Buffer.from(b), (e) => (e ? err(e) : ok())));
      },
      close(): void { sock.destroy(); },
    }));
  });
}
