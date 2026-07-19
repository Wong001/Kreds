import React, { useCallback, useState } from "react";
import { Button, SafeAreaView, StyleSheet, Text } from "react-native";
// expo-file-system 57 split readAsStringAsync into the /legacy subpath;
// the classic API (signature unchanged) lives there now.
import * as FileSystem from "expo-file-system/legacy";
import * as Crypto from "expo-crypto";
import { bootstrap, dial, fixtureDir, onProgress } from "./modules/tor-manager";
import { Fixture, handshake, splitAddr } from "./src/handshake";

function randomHex16(): string {
  return Array.from(Crypto.getRandomBytes(16))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export default function App() {
  const [stage, setStage] = useState("idle");
  const [result, setResult] = useState("");
  const [busy, setBusy] = useState(false);

  const connect = useCallback(async () => {
    setBusy(true);
    setResult("");
    try {
      setStage("reading fixture");
      const raw = await FileSystem.readAsStringAsync(
        `file://${fixtureDir}/spike_phone_fixture.json`);
      const fixture = JSON.parse(raw) as Fixture;

      setStage("tor bootstrap 0%");
      const off = onProgress((p) => setStage(`tor bootstrap ${p}%`));
      try {
        await bootstrap();
      } finally {
        off();
      }

      setStage("dialing home node");
      const [host, port] = splitAddr(fixture.onion_addr);
      const stream = await dial(host, port);

      setStage("handshake");
      const r = await handshake(stream, fixture, randomHex16);
      setStage("done");
      setResult(
        r.status === "accepted" ? "CONNECTED to home node over Tor"
        : r.status === "refused" ? "REFUSED by node"
        : `FAILED at ${r.stage}: ${r.reason}`);
    } catch (e) {
      setStage("done");
      setResult(`ERROR: ${String(e)}`);
    } finally {
      setBusy(false);
    }
  }, []);

  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>Kreds Tor spike</Text>
      <Button title="Connect" onPress={connect} disabled={busy} />
      <Text style={styles.stage}>{stage}</Text>
      <Text style={styles.result}>{result}</Text>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: "center", justifyContent: "center", gap: 16 },
  title: { fontSize: 20, fontWeight: "600" },
  stage: { fontSize: 16 },
  result: { fontSize: 16, fontWeight: "600", paddingHorizontal: 24, textAlign: "center" },
});
