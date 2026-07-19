// Byte-for-byte port of hearth/identity.py canonical(), hearth/transport.py
// frames, and the device-key sign/verify that HELLO/AUTH uses. The
// authoritative cross-language contract is fixtures/wire_vectors.json,
// generated from the real Python implementation.
// NOTE: installed @noble/curves resolved to 2.2.0 (brief's `npm install
// @noble/curves` has no version pin), whose subpath exports require the
// .js extension -- "@noble/curves/ed25519" (no extension, as literally
// written in the brief) does not resolve under this version's exports map.
import { ed25519 } from "@noble/curves/ed25519.js";

export const PROTOCOL = "hearth/v0.2";
export const MAX_FRAME = 16 * 1024 * 1024;

/** Marks a number that Python's JSON treats as float (e.g. enrolled_at).
 * JSON.parse collapses 1234.0 to 1234; Python does not. Schema-known
 * float fields must be wrapped so serialization renders Python-style. */
export class PyFloat {
  constructor(public readonly value: number) {}
}

export function toHex(b: Uint8Array): string {
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}

export function fromHex(h: string): Uint8Array {
  if (h.length % 2 !== 0) throw new Error("odd-length hex");
  const out = new Uint8Array(h.length / 2);
  for (let i = 0; i < out.length; i++) {
    const v = parseInt(h.slice(2 * i, 2 * i + 2), 16);
    if (Number.isNaN(v)) throw new Error("bad hex");
    out[i] = v;
  }
  return out;
}

function pyFloatRepr(n: number): string {
  if (!Number.isFinite(n)) throw new Error("non-finite float unsupported");
  const s = String(n);
  // Python repr() and JS String() both emit the shortest round-trip
  // decimal, so they agree wherever both use plain fixed notation. The
  // notations diverge at extreme magnitudes (Python: 1e+16 / 1e-05; JS:
  // 10000000000000000 / 0.00001). No spike value is near either edge --
  // refuse loudly rather than guess.
  if (/[eE]/.test(s) || Math.abs(n) >= 1e16 || (n !== 0 && Math.abs(n) < 1e-4)) {
    throw new Error(`float out of spike-supported range: ${n}`);
  }
  return Number.isInteger(n) ? `${s}.0` : s;
}

function escapeString(s: string): string {
  // Python json.dumps default ensure_ascii=True: escape every UTF-16
  // unit above 0x7E (astral chars become surrogate-pair escapes, exactly
  // like CPython), lowercase 4-digit \uXXXX, C shorthands for controls.
  let out = '"';
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    const ch = s[i];
    if (ch === '"') out += '\\"';
    else if (ch === "\\") out += "\\\\";
    else if (c === 0x08) out += "\\b";
    else if (c === 0x09) out += "\\t";
    else if (c === 0x0a) out += "\\n";
    else if (c === 0x0c) out += "\\f";
    else if (c === 0x0d) out += "\\r";
    else if (c < 0x20 || c > 0x7e) out += "\\u" + c.toString(16).padStart(4, "0");
    else out += ch;
  }
  return out + '"';
}

function codePointCompare(a: string, b: string): number {
  // Python sorts str by code point; JS default sort compares UTF-16 code
  // units. They disagree when an astral key meets a U+E000..U+FFFF key.
  const A = Array.from(a), B = Array.from(b);
  const n = Math.min(A.length, B.length);
  for (let i = 0; i < n; i++) {
    const d = A[i].codePointAt(0)! - B[i].codePointAt(0)!;
    if (d !== 0) return d;
  }
  return A.length - B.length;
}

export function dumps(v: unknown): string {
  if (v === null || v === undefined) return "null";
  if (v === true) return "true";
  if (v === false) return "false";
  if (typeof v === "string") return escapeString(v);
  if (v instanceof PyFloat) return pyFloatRepr(v.value);
  if (typeof v === "number") {
    if (!Number.isSafeInteger(v)) {
      throw new Error(
        `bare non-integer number in serialization: ${v} -- wrap schema-known floats in PyFloat`);
    }
    return String(v);
  }
  if (Array.isArray(v)) return "[" + v.map(dumps).join(",") + "]";
  if (typeof v === "object") {
    const o = v as Record<string, unknown>;
    const keys = Object.keys(o).sort(codePointCompare);
    return "{" + keys.map((k) => escapeString(k) + ":" + dumps(o[k])).join(",") + "}";
  }
  throw new Error(`unsupported type in serialization: ${typeof v}`);
}

/** hearth.identity.canonical: json.dumps(obj, sort_keys=True,
 *  separators=(",", ":")).encode() */
export function canonical(obj: Record<string, unknown>): Uint8Array {
  // dumps() output is pure ASCII (ensure_ascii), so per-char codes are bytes.
  const s = dumps(obj);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export interface Stream {
  /** Return exactly n bytes or throw. */
  read(n: number): Promise<Uint8Array>;
  write(b: Uint8Array): Promise<void>;
  close(): void;
}

/** hearth.transport.write_frame: 4-byte big-endian length + JSON bytes.
 *  The Python reader is key-order-agnostic, so reusing the sorted
 *  serializer here is safe -- and it keeps PyFloat handling in one place. */
export async function writeFrame(s: Stream, obj: unknown): Promise<void> {
  const payload = canonical(obj as Record<string, unknown>);
  if (payload.length > MAX_FRAME) throw new Error("frame too large");
  const out = new Uint8Array(4 + payload.length);
  new DataView(out.buffer).setUint32(0, payload.length, false);
  out.set(payload, 4);
  await s.write(out);
}

export async function readFrame(s: Stream): Promise<any> {
  const header = await s.read(4);
  const n = new DataView(header.buffer, header.byteOffset, 4).getUint32(0, false);
  if (n > MAX_FRAME) throw new Error("frame too large");
  const body = await s.read(n);
  let text = "";
  // TextDecoder is unavailable on RN Hermes; frames are ASCII-safe on the
  // read path only after JSON.parse -- decode UTF-8 manually via escape
  // trick is overkill: the node always sends ensure_ascii JSON (Python
  // json.dumps default), so bytes are pure ASCII.
  for (const b of body) {
    if (b > 0x7e) throw new Error("non-ascii frame byte (unexpected: python sends ensure_ascii)");
    text += String.fromCharCode(b);
  }
  return JSON.parse(text);
}

export function signRaw(devicePrivHex: string, data: Uint8Array): string {
  return toHex(ed25519.sign(data, fromHex(devicePrivHex)));
}

export function verifyRaw(pubHex: string, sigHex: string, data: Uint8Array): boolean {
  try {
    return ed25519.verify(fromHex(sigHex), data, fromHex(pubHex));
  } catch {
    return false;
  }
}

export interface CertDict {
  identity_pub: string;
  device_pub: string;
  device_name: string;
  enrolled_at: number;
  signature: string;
}

/** hearth.sync._auth_body */
export function authBody(nonceHex: string): Uint8Array {
  return canonical({ type: "gossip-auth", protocol: PROTOCOL, nonce: nonceHex });
}

/** hearth.identity.EnrollmentCert.body -- enrolled_at is float-typed. */
export function certBody(c: CertDict): Uint8Array {
  return canonical({
    type: "enrollment", protocol: PROTOCOL,
    identity_pub: c.identity_pub, device_pub: c.device_pub,
    device_name: c.device_name, enrolled_at: new PyFloat(c.enrolled_at),
  });
}

/** hearth.identity.EnrollmentCert.verify */
export function verifyCert(c: CertDict): boolean {
  return verifyRaw(c.identity_pub, c.signature, certBody(c));
}
