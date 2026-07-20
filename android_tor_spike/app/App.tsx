import React, { useCallback, useEffect, useState } from "react";
import { Button, FlatList, SafeAreaView, StyleSheet, Text, View } from "react-native";
import {
  Beat, beatNow, FeedItem, getFeed, getHistory, getSyncStats, isBatteryExempt, onBeat,
  onState, onSync, requestBatteryExemption, startNode, stopNode, syncNow, SyncStats,
} from "./modules/tor-manager";

export default function App() {
  const [state, setState] = useState("stopped");
  const [beats, setBeats] = useState<Beat[]>([]);
  const [exempt, setExempt] = useState(true);
  const [syncStats, setSyncStats] = useState<SyncStats>({ messages: 0, blobs: 0, identities: 0 });
  const [lastSync, setLastSync] = useState<string>("");
  // null = not yet fetched this mount (loading); [] = fetched, nothing decrypted yet
  // (distinct empty-state, see the getFeed() render below).
  const [feed, setFeed] = useState<FeedItem[] | null>(null);

  const refresh = useCallback(async () => setBeats(await getHistory()), []);
  const refreshSyncStats = useCallback(async () => setSyncStats(await getSyncStats()), []);
  const refreshFeed = useCallback(async () => setFeed(await getFeed()), []);

  useEffect(() => {
    setExempt(isBatteryExempt());
    refresh();
    refreshSyncStats();
    refreshFeed();
    const offState = onState(setState);
    const offBeat = onBeat((b) => setBeats((prev) => [b, ...prev].slice(0, 50)));
    const offSync = onSync((r) => {
      setSyncStats({ messages: r.messages, blobs: r.blobs, identities: r.identities });
      setLastSync(r.ok
        ? `synced: ${r.messages} msgs, ${r.blobs} blobs, ${r.identities} friends`
        : `sync failed: ${r.reason}`);
      if (r.feedUpdated) refreshFeed();
    });
    return () => { offState(); offBeat(); offSync(); };
  }, [refresh, refreshSyncStats, refreshFeed]);

  return (
    <SafeAreaView style={styles.container}>
      <Text style={styles.title}>Kreds node</Text>
      <Text style={styles.state}>state: {state}</Text>
      {!exempt && (
        <View style={styles.warn}>
          <Text style={styles.warnText}>Battery optimization may kill the node.</Text>
          <Button title="Exempt Kreds from battery optimization"
            onPress={() => { requestBatteryExemption(); setTimeout(() => setExempt(isBatteryExempt()), 500); }} />
        </View>
      )}
      <View style={styles.row}>
        <Button title="Start node" onPress={startNode} />
        <Button title="Stop node" onPress={stopNode} />
        <Button title="Beat now" onPress={beatNow} />
      </View>
      <Text style={styles.subtitle}>Content sync</Text>
      <View style={styles.row}>
        <Button title="Sync now" onPress={syncNow} />
      </View>
      <Text style={styles.state}>
        messages: {syncStats.messages} / blobs: {syncStats.blobs} / friends: {syncStats.identities}
      </Text>
      {!!lastSync && <Text style={lastSync.startsWith("sync failed") ? styles.fail : styles.ok}>{lastSync}</Text>}
      <Text style={styles.subtitle}>Feed ({feed === null ? "..." : feed.length})</Text>
      {feed === null && <Text style={styles.state}>Loading feed...</Text>}
      {feed !== null && feed.length === 0 && (
        <Text style={styles.state}>No decrypted items yet - Sync now to load your history</Text>
      )}
      {feed !== null && feed.length > 0 && (
        <FlatList
          style={styles.list}
          data={feed}
          keyExtractor={(item) => item.msgId}
          renderItem={({ item }) => (
            <Text style={styles.feedItem}>
              {item.author} · {item.kind} · {new Date(item.createdAt * 1000).toLocaleString()} — {item.text}
            </Text>
          )}
        />
      )}
      <Text style={styles.subtitle}>Heartbeats ({beats.length})</Text>
      <FlatList
        style={styles.list}
        data={beats}
        keyExtractor={(b) => String(b.ts)}
        renderItem={({ item }) => (
          <Text style={item.ok ? styles.ok : styles.fail}>
            {new Date(item.ts).toLocaleTimeString()} {item.ok ? `OK ${item.latencyMs}ms` : `FAIL ${item.reason}`}
          </Text>
        )}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 20, gap: 10 },
  title: { fontSize: 22, fontWeight: "700" },
  subtitle: { fontSize: 16, fontWeight: "600", marginTop: 8 },
  state: { fontSize: 16 },
  row: { flexDirection: "row", gap: 8, flexWrap: "wrap" },
  list: { flex: 1 },
  ok: { fontSize: 14, color: "#1a7f37", paddingVertical: 2 },
  fail: { fontSize: 14, color: "#b00020", paddingVertical: 2 },
  feedItem: { fontSize: 14, paddingVertical: 2 },
  warn: { backgroundColor: "#fff3cd", padding: 10, borderRadius: 6, gap: 6 },
  warnText: { fontSize: 14 },
});
