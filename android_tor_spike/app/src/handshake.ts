// Mirror of hearth/sync.py _session, phone = initiator, stopping at the
// earliest unambiguous accept/refuse signal after AUTH. Runs identically
// over a node:net socket (desk gate) and TorManager's SOCKS stream (phone).
import {
  authBody, CertDict, PyFloat, readFrame, signRaw, Stream, verifyCert,
  verifyRaw, writeFrame,
} from "./wire";

export interface Fixture {
  device_priv: string;
  device_pub: string;
  cert: CertDict;
  onion_addr: string;
}

export type HandshakeResult =
  | { status: "accepted" }
  | { status: "refused" }
  | { status: "failed"; stage: string; reason: string };

function failed(stage: string, reason: string): HandshakeResult {
  return { status: "failed", stage, reason };
}

// Grace window for the acceptance probe's unsolicited-refusal read (see
// the probe comment below). Loopback refusals land in ~ms; Tor refusals
// may exceed this and fall through to the write path.
const PROBE_GRACE_MS = 1500;

export async function handshake(
  stream: Stream,
  fixture: Fixture,
  randomHex16: () => string,   // 16 random bytes as 32 hex chars
): Promise<HandshakeResult> {
  try {
    // -- HELLO (initiator writes first; node's _on_conn reads it, then
    //    _session sends the node's own hello) --
    const myNonce = randomHex16();
    await writeFrame(stream, {
      t: "hello",
      // enrolled_at is float-typed in python; PyFloat keeps 1234.0 as
      // "1234.0" through our serializer (see wire.ts)
      cert: { ...fixture.cert, enrolled_at: new PyFloat(fixture.cert.enrolled_at) },
      nonce: myNonce,
    });
    const peerHello = await readFrame(stream);
    if (peerHello.t !== "hello") return failed("hello", `unexpected frame t=${peerHello.t}`);
    const peerCert = peerHello.cert as CertDict;
    if (!verifyCert(peerCert)) return failed("hello", "node cert failed verification");
    // NOTE: no identity-match check here -- sync.py's _session has none, and
    // enforcing it at HELLO would block the node's own refused path from
    // ever being observed. Identity is pinned in the ACCEPTED branch below.
    // (Amended per Task 5 implementer finding.)

    // -- AUTH (mutual device-key proof, sync.py:451-458) --
    await writeFrame(stream, {
      t: "auth",
      sig: signRaw(fixture.device_priv, authBody(peerHello.nonce)),
    });
    const peerAuth = await readFrame(stream);
    if (peerAuth.t !== "auth") return failed("auth", `unexpected frame t=${peerAuth.t}`);
    if (!verifyRaw(peerCert.device_pub, peerAuth.sig, authBody(myNonce))) {
      return failed("auth", "node failed device-key proof");
    }

    // -- acceptance probe --
    // The node reveals accept/refuse only AFTER the AUTH swap: on refusal
    // it writes {"t":"refused"} IMMEDIATELY and closes (sync.py:472-483);
    // on acceptance it proceeds to the REVOCATIONS phase, where as
    // responder it READS our frame before writing its own (_swap,
    // sync.py:411). Ordering matters on our side: writing our revocations
    // frame first races the refusing node's close -- the TCP RST purges
    // the buffered refused frame out of our receive buffer (observed on
    // Windows loopback). So: issue the ONE verdict read up front, grace-
    // wait briefly for an unsolicited refusal, and only then send our
    // empty revocations frame -- the same pending read then consumes the
    // node's revocations reply. The verdict read is created exactly once,
    // so there is never a concurrent read on the stream. Residual race
    // (refusal slower than the grace window on a high-latency path)
    // surfaces as failed/io, never a wrong verdict. We discard the node's
    // revocation list and never reach DEFRIENDS/HAVE/MESSAGES/BLOBS --
    // the node logs one broken session at its DEFRIENDS read, which is
    // expected spike noise. (Amended per Task 5 implementer finding.)
    const verdictPromise = readFrame(stream);
    let graceTimer: ReturnType<typeof setTimeout> | undefined;
    const grace = new Promise<"timeout">((resolve) => {
      graceTimer = setTimeout(() => resolve("timeout"), PROBE_GRACE_MS);
    });
    let verdict: any;
    try {
      const first = await Promise.race([verdictPromise, grace]);
      if (first === "timeout") {
        await writeFrame(stream, { t: "revocations", revs: [] });
        verdict = await verdictPromise;
      } else {
        verdict = first;
      }
    } finally {
      if (graceTimer !== undefined) clearTimeout(graceTimer);
    }
    if (verdict.t === "refused") return { status: "refused" };
    if (verdict.t === "revocations") {
      // Accepted -- but by whom? A FRIEND's node also knows our identity
      // and would accept this same device cert. The spike must only ever
      // claim "connected to home node", so the identity pin lives HERE,
      // in the accepted branch, not at HELLO.
      if (peerCert.identity_pub !== fixture.cert.identity_pub) {
        return failed("probe", "accepted by a non-home-node identity");
      }
      return { status: "accepted" };
    }
    return failed("probe", `unexpected frame t=${verdict.t}`);
  } catch (e) {
    return failed("io", String(e));
  } finally {
    stream.close();
  }
}

export function splitAddr(addr: string): [string, number] {
  const i = addr.lastIndexOf(":");
  if (i < 0) throw new Error(`address has no port: ${addr}`);
  return [addr.slice(0, i), Number(addr.slice(i + 1))];
}
