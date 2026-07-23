import React, { useEffect, useState } from "react";
import { ActivityIndicator, AppState, StyleSheet, Text, View, useColorScheme } from "react-native";
import { SafeAreaProvider, useSafeAreaInsets } from "react-native-safe-area-context";
import { WebView } from "react-native-webview";
import { beatNow, bootstrap, startNode, syncNow, getWebUrl } from "./modules/tor-manager";

/** vp1: full-screen host for the desktop web UI, served by the native loopback
 *  server. For slice 1 this component also owns the engine bootstrap (Tor +
 *  node) so a fresh launch has content to render; background sync (Brick C)
 *  keeps it current. The WebView loads getWebUrl() -- the one-time-token URL --
 *  with cookies enabled so app.js's same-origin /api fetches authenticate.
 *
 *  The WebView is inset by the bottom safe area (useSafeAreaInsets) so the
 *  desktop UI's sticky mobile tab bar sits ABOVE the Android system navigation
 *  bar instead of underneath it. The inset area is painted the web UI's
 *  --surface colour (light/dark) so it reads as an extension of the tab bar. */
function Shell() {
  const insets = useSafeAreaInsets();
  const scheme = useColorScheme();
  const surface = scheme === "dark" ? "#1C1E20" : "#FFFFFF";
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
                                    // adaptive background cadence (Task 6:
                                    // AdaptiveBackoff, 10 min - 1 hr) keeps it
                                    // current after that).
        const url = await getWebUrl();
        if (!url) { setErr("web server not available"); return; }
        setUri(url);
      } catch (e: any) {
        setErr(String(e?.message ?? e));
      }
    })();
  }, []);

  // Task 6 (friend-peering, cadence overhaul): on-app-resume event trigger.
  // Foreground/background-only ('active' <-> 'background'/'inactive')
  // transitions are noisy on Android (fired for the OS lock screen, the
  // notification shade, etc.), so this only fires on the transition INTO
  // 'active' -- the moment the user is actually back looking at the app --
  // not on every AppState change. beatNow() resets TorNodeService's
  // AdaptiveBackoff to base and runs one sweep now (see TorNodeService.
  // ACTION_BEAT_NOW's doc), same as the on-compose trigger on the native
  // side. This is separate from (and in addition to) the mount-time
  // syncNow() above -- mount always fires once on a fresh launch; this
  // fires on every subsequent return to the foreground, which mount alone
  // does not cover (a backgrounded-then-resumed app does not remount).
  useEffect(() => {
    let prev = AppState.currentState;
    const sub = AppState.addEventListener("change", (next) => {
      if (prev !== "active" && next === "active") beatNow();
      prev = next;
    });
    return () => sub.remove();
  }, []);

  if (err) return (<View style={styles.center}><Text>{err}</Text></View>);
  if (!uri) return (<View style={styles.center}><ActivityIndicator size="large" /></View>);

  return (
    <View style={{ flex: 1, paddingBottom: insets.bottom, backgroundColor: surface }}>
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
    </View>
  );
}

export default function WebShell() {
  return (
    <SafeAreaProvider>
      <Shell />
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  web: { flex: 1 },
  center: { flex: 1, alignItems: "center", justifyContent: "center" },
});
