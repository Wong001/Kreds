// Desk handshake runner: RESULT accepted|refused|failed <stage>: <reason>
import { readFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { Fixture, handshake, splitAddr } from "../src/handshake";
import { connectTcp } from "./node_stream";

const [fixturePath, addrOverride] = process.argv.slice(2);
const fixture = JSON.parse(readFileSync(fixturePath, "utf8")) as Fixture;
const [host, port] = splitAddr(addrOverride ?? fixture.onion_addr);

// Wrapped in an async IIFE rather than using top-level await: this app's
// package.json has no "type": "module", so tsx/esbuild compiles .ts
// files to CJS output, which esbuild refuses for top-level await. Same
// class of environment-reality adaptation as wire.ts's @noble/curves
// subpath fix -- behavior is otherwise identical to plain top-level
// await.
(async () => {
  const stream = await connectTcp(host, port);
  const result = await handshake(stream, fixture, () => randomBytes(16).toString("hex"));
  if (result.status === "failed") {
    console.log(`RESULT failed ${result.stage}: ${result.reason}`);
  } else {
    console.log(`RESULT ${result.status}`);
  }
  process.exit(result.status === "accepted" ? 0 : 1);
})();
