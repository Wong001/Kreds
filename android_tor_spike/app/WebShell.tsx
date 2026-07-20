import React, { useEffect, useState } from "react";
import { ActivityIndicator, StyleSheet, Text, View } from "react-native";
import { WebView } from "react-native-webview";
import { bootstrap, startNode, syncNow, getWebUrl } from "./modules/tor-manager";

/** vp1: full-screen host for the desktop web UI, served by the native loopback
 *  server. For slice 1 this component also owns the engine bootstrap (Tor +
 *  node) so a fresh launch has content to render; background sync (Brick C)
 *  keeps it current. The WebView loads getWebUrl() -- the one-time-token URL --
 *  with cookies enabled so app.js's same-origin /api fetches authenticate. */
export default function WebShell() {
  const [uri, setUri] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        await bootstrap();          // start Tor
        startNode();                // start the background node (Brick C sync)
        syncNow();                  // vp1: kick an immediate foreground sync so a
                                    // fresh launch has current content to render
                                    // (do not await -- fire-and-forget; the feed
                                    // reflects the last successful sync, and the
                                    // 15-min background cycle keeps it current).
        const url = await getWebUrl();
        if (!url) { setErr("web server not available"); return; }
        setUri(url);
      } catch (e: any) {
        setErr(String(e?.message ?? e));
      }
    })();
  }, []);

  if (err) return (<View style={styles.center}><Text>{err}</Text></View>);
  if (!uri) return (<View style={styles.center}><ActivityIndicator size="large" /></View>);

  return (
    <WebView
      source={{ uri }}
      style={styles.web}
      originWhitelist={["http://127.0.0.1*"]}
      sharedCookiesEnabled
      thirdPartyCookiesEnabled
      javaScriptEnabled
      domStorageEnabled
      mediaPlaybackRequiresUserAction={false}
      allowsInlineMediaPlayback
      onShouldStartLoadWithRequest={(req) => req.url.startsWith("http://127.0.0.1")}
    />
  );
}

const styles = StyleSheet.create({
  web: { flex: 1 },
  center: { flex: 1, alignItems: "center", justifyContent: "center" },
});
