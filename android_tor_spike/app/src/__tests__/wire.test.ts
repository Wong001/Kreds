import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import {
  authBody, canonical, certBody, fromHex, PyFloat, readFrame, Stream,
  signRaw, toHex, verifyCert, verifyRaw, writeFrame,
} from "../wire";

const vectors = JSON.parse(readFileSync(
  new URL("../../../fixtures/wire_vectors.json", import.meta.url), "utf8"));

/** Turn {"__pyfloat__": n} markers back into PyFloat instances. */
function revive(v: any): any {
  if (Array.isArray(v)) return v.map(revive);
  if (v && typeof v === "object") {
    const keys = Object.keys(v);
    if (keys.length === 1 && keys[0] === "__pyfloat__") return new PyFloat(v.__pyfloat__);
    return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, revive(x)]));
  }
  return v;
}

/** In-memory Stream over a fixed byte buffer, capturing writes. */
class MemoryStream implements Stream {
  written: number[] = [];
  private pos = 0;
  constructor(private data: Uint8Array = new Uint8Array(0)) {}
  async read(n: number): Promise<Uint8Array> {
    if (this.pos + n > this.data.length) throw new Error("EOF");
    const out = this.data.slice(this.pos, this.pos + n);
    this.pos += n;
    return out;
  }
  async write(b: Uint8Array): Promise<void> { this.written.push(...b); }
  close(): void {}
}

describe("canonical", () => {
  for (const c of vectors.canonical_cases) {
    it(c.name, () => {
      expect(toHex(canonical(revive(c.obj)))).toBe(c.bytes_hex);
    });
  }
  it("rejects bare non-integer numbers", () => {
    expect(() => canonical({ t: 1.5 })).toThrow(/PyFloat/);
  });
});

describe("auth", () => {
  for (const [i, c] of vectors.auth_cases.entries()) {
    it(`vector ${i}`, () => {
      const body = authBody(c.nonce);
      expect(toHex(body)).toBe(c.body_hex);
      expect(signRaw(c.device_priv, body)).toBe(c.sig);   // RFC 8032 is deterministic
      expect(verifyRaw(c.device_pub, c.sig, body)).toBe(true);
      expect(verifyRaw(c.device_pub, c.sig, authBody("00".repeat(16)))).toBe(false);
    });
  }
});

describe("cert", () => {
  for (const [i, c] of vectors.cert_cases.entries()) {
    it(`vector ${i} (valid=${c.valid})`, () => {
      if (c.body_hex !== null) expect(toHex(certBody(c.cert))).toBe(c.body_hex);
      expect(verifyCert(c.cert)).toBe(c.valid);
    });
  }
});

describe("frames", () => {
  for (const [i, c] of vectors.frame_cases.entries()) {
    it(`reads python frame ${i}`, async () => {
      expect(await readFrame(new MemoryStream(fromHex(c.frame_hex)))).toEqual(c.obj);
    });
    it(`self round-trips frame ${i}`, async () => {
      const w = new MemoryStream();
      await writeFrame(w, c.obj);
      expect(await readFrame(new MemoryStream(new Uint8Array(w.written)))).toEqual(c.obj);
    });
  }
});
