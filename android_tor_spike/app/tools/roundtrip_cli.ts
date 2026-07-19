// Desk-only: consumes {cert, device_priv, nonce}, emits the TS-computed
// auth body/signature and a TS re-serialization of the cert, for the
// python side of the roundtrip gate to verify with real hearth code.
import { readFileSync, writeFileSync } from "node:fs";
import {
  authBody, CertDict, dumps, PyFloat, signRaw, toHex, verifyCert,
} from "../src/wire";

const [inPath, outPath] = process.argv.slice(2);
const inp = JSON.parse(readFileSync(inPath, "utf8")) as {
  cert: CertDict; device_priv: string; nonce: string;
};

const body = authBody(inp.nonce);
const certJson = dumps({ ...inp.cert, enrolled_at: new PyFloat(inp.cert.enrolled_at) });

writeFileSync(outPath, JSON.stringify({
  auth_body_hex: toHex(body),
  sig: signRaw(inp.device_priv, body),
  cert_verifies: verifyCert(inp.cert),
  cert_json: certJson,
}));
