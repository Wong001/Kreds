import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  ActivityIndicator, Button, Platform, SafeAreaView, StyleSheet, Text, TextInput,
  TouchableOpacity, View,
} from "react-native";
import { CameraView, useCameraPermissions } from "expo-camera";
import {
  bootstrap, hasIdentity, onPairProgress, pairWithNode, PairStatus,
} from "./modules/tor-manager";
import WebShell from "./WebShell";

// First-load pairing (spec 2026-07-22-android-first-load-pairing-design):
// a fresh install has no identity yet, so it can't just drop into WebShell
// (which assumes a paired device -- see WebShell's own bootstrap/getWebUrl
// sequence). This screen owns the ceremony: scan/type the desktop's link,
// run the real pairing ceremony over Tor, then hand off to WebShell.

// The device-name field's default -- "phone" plus the Android model when
// RN's own Platform constants expose it. react-native is already a
// dependency, so this adds none (the brief: "expo-device or the RN
// Platform constants -- whichever the project already has; if neither, a
// plain 'phone' default"). iOS/web have no Model constant, so they fall
// back to the plain default; the field stays editable either way.
function defaultDeviceName(): string {
  if (Platform.OS === "android" && Platform.constants.Model) {
    return `phone (${Platform.constants.Model})`;
  }
  return "phone";
}

type Screen =
  | { kind: "menu" }
  | { kind: "comingSoon" }
  | { kind: "link" }
  | { kind: "pairing"; phase: "dialing" | "waiting" | "installing" }
  | { kind: "success" }
  | { kind: "error"; status: Exclude<PairStatus, "linked">; reason?: string };

// Distinct, retryable copy per non-linked outcome (brief Step 2).
const ERROR_COPY: Record<Exclude<PairStatus, "linked">, string> = {
  denied: "Your desktop denied the request",
  expired: "Code expired or already used — make a new one on your desktop",
  unreachable: "Couldn't reach your node — check it's running with Tor",
  bad_link: "That doesn't look like a Kreds link code",
};

const PHASE_COPY: Record<"dialing" | "waiting" | "installing", string> = {
  dialing: "Connecting to your node…",
  waiting: "Waiting for your desktop to accept…",
  installing: "Setting up your device…",
};

export default function FirstLoad() {
  // hasIdentity is a cheap sync local-file check (PairingStore.hasIdentity)
  // -- safe to call as the initializer so an already-linked device never
  // flashes the menu screen before this decides to just render WebShell.
  const [linked, setLinked] = useState(() => hasIdentity());
  const [screen, setScreen] = useState<Screen>({ kind: "menu" });
  const [deviceName, setDeviceName] = useState(defaultDeviceName);
  const [typedCode, setTypedCode] = useState("");
  const [permission, requestPermission] = useCameraPermissions();
  // Independent of `screen` so the user's last choice (camera vs typed)
  // survives a retry from the error screen without threading it through
  // every Screen variant.
  const [manualEntry, setManualEntry] = useState(false);
  // Guards onBarcodeScanned against firing again for the same frame while
  // a scan is already being processed -- CameraView keeps calling it on
  // every detected frame until the component unmounts (which only happens
  // once `screen` leaves "link", one render later).
  const scanLockRef = useRef(false);

  useEffect(() => {
    const off = onPairProgress(({ stage }) => {
      if (stage === "dialing" || stage === "waiting") {
        setScreen((s) => (s.kind === "pairing" ? { kind: "pairing", phase: stage } : s));
      }
    });
    return off;
  }, []);

  const runCeremony = useCallback(async (link: string) => {
    if (scanLockRef.current) return;
    scanLockRef.current = true;
    setScreen({ kind: "pairing", phase: "dialing" });
    try {
      // pairWithNode fast-fails "unreachable" (no pairProgress events at
      // all) unless Tor is already bootstrapped -- see its doc in
      // modules/tor-manager. bootstrap() is idempotent (TorEngine.bootstrap
      // returns immediately if already up), so this never re-pays the cost
      // if a prior attempt already brought Tor up, and WebShell's own
      // bootstrap() call after we hand off below is likewise a cheap no-op.
      await bootstrap();
      const result = await pairWithNode(link, deviceName.trim() || "phone");
      if (result.status === "linked") {
        // No native signal marks the (already-synchronous) local install
        // step separately from the ceremony's "waiting" event -- see
        // onPairProgress's doc. Hold this phase for a brief, fixed beat so
        // it's actually visible (otherwise React's batching would collapse
        // it into the very next state update) before the success note.
        setScreen({ kind: "pairing", phase: "installing" });
        await new Promise((resolve) => setTimeout(resolve, 500));
        setScreen({ kind: "success" });
        setTimeout(() => setLinked(true), 1500);
      } else {
        setScreen({ kind: "error", status: result.status, reason: result.reason });
      }
    } catch (e: any) {
      setScreen({ kind: "error", status: "unreachable", reason: String(e?.message ?? e) });
    } finally {
      scanLockRef.current = false;
    }
  }, [deviceName]);

  const startLink = useCallback(async () => {
    let res = permission;
    if (!res?.granted) res = await requestPermission();
    setManualEntry(!res.granted);
    setTypedCode("");
    setScreen({ kind: "link" });
  }, [permission, requestPermission]);

  const tryCameraAgain = useCallback(async () => {
    const res = await requestPermission();
    if (res.granted) setManualEntry(false);
  }, [requestPermission]);

  const onBarcodeScanned = useCallback((res: { data: string }) => {
    if (scanLockRef.current || !res.data) return;
    runCeremony(res.data);
  }, [runCeremony]);

  const submitTypedCode = useCallback(() => {
    const code = typedCode.trim();
    if (code) runCeremony(code);
  }, [typedCode, runCeremony]);

  // Once linked (either hasIdentity() was already true on a returning
  // device, or the ceremony above just succeeded), this screen is done --
  // WebShell owns everything from here (its own bootstrap/startNode/
  // syncNow/getWebUrl sequence, unchanged).
  if (linked) return <WebShell />;

  return (
    <SafeAreaView style={styles.container}>
      {screen.kind === "menu" && (
        <View style={styles.center}>
          <Text style={styles.title}>Kreds</Text>
          <View style={styles.menuButtons}>
            <Button title="Link to your node" onPress={startLink} />
            <View style={styles.gap} />
            <Button title="Start a new profile" onPress={() => setScreen({ kind: "comingSoon" })} />
          </View>
        </View>
      )}

      {screen.kind === "comingSoon" && (
        <View style={styles.center}>
          <Text style={styles.subtitle}>Coming soon</Text>
          <Text style={styles.state}>
            Creating a new profile on your phone isn't ready yet. For now, link this phone
            to a Kreds node you already run elsewhere.
          </Text>
          <View style={styles.gap} />
          <Button title="Back" onPress={() => setScreen({ kind: "menu" })} />
        </View>
      )}

      {screen.kind === "link" && (
        <View style={styles.linkScreen}>
          <Text style={styles.subtitle}>Link to your node</Text>
          <Text style={styles.label}>Device name</Text>
          <TextInput
            style={styles.input}
            value={deviceName}
            onChangeText={setDeviceName}
            placeholder="phone"
            autoCapitalize="none"
            autoCorrect={false}
          />

          {!manualEntry && permission?.granted ? (
            <>
              <View style={styles.cameraBox}>
                <CameraView
                  style={styles.camera}
                  barcodeScannerSettings={{ barcodeTypes: ["qr"] }}
                  onBarcodeScanned={onBarcodeScanned}
                />
              </View>
              <Text style={styles.state}>Point your camera at the code on your desktop.</Text>
              <TouchableOpacity onPress={() => setManualEntry(true)}>
                <Text style={styles.linkText}>Type the code instead</Text>
              </TouchableOpacity>
            </>
          ) : (
            <>
              <Text style={styles.label}>Pairing code</Text>
              <TextInput
                style={styles.input}
                value={typedCode}
                onChangeText={setTypedCode}
                placeholder="Paste or type the code from your desktop"
                autoCapitalize="none"
                autoCorrect={false}
              />
              <View style={styles.row}>
                <Button title="Continue" onPress={submitTypedCode} disabled={!typedCode.trim()} />
              </View>
              {!permission?.granted && permission?.canAskAgain && (
                <TouchableOpacity onPress={tryCameraAgain}>
                  <Text style={styles.linkText}>Try camera access again</Text>
                </TouchableOpacity>
              )}
            </>
          )}

          <View style={styles.gap} />
          <Button title="Back" onPress={() => setScreen({ kind: "menu" })} />
        </View>
      )}

      {screen.kind === "pairing" && (
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={styles.subtitle}>{PHASE_COPY[screen.phase]}</Text>
          {screen.phase === "waiting" && (
            <Text style={styles.state}>
              This can take a few minutes — accept the request on your desktop when it appears.
            </Text>
          )}
        </View>
      )}

      {screen.kind === "success" && (
        <View style={styles.center}>
          <Text style={styles.ok}>Linked — your history will fill in over the next syncs</Text>
        </View>
      )}

      {screen.kind === "error" && (
        <View style={styles.center}>
          <Text style={styles.fail}>{ERROR_COPY[screen.status]}</Text>
          {screen.status === "unreachable" && !!screen.reason && (
            <Text style={styles.smallPrint}>{screen.reason}</Text>
          )}
          <View style={styles.gap} />
          <Button title="Try again" onPress={() => setScreen({ kind: "link" })} />
          <View style={styles.gap} />
          <Button title="Back to menu" onPress={() => setScreen({ kind: "menu" })} />
        </View>
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 20, gap: 10 },
  center: { flex: 1, alignItems: "center", justifyContent: "center", gap: 10, padding: 20 },
  title: { fontSize: 32, fontWeight: "700", marginBottom: 24 },
  subtitle: { fontSize: 18, fontWeight: "600", textAlign: "center" },
  state: { fontSize: 14, textAlign: "center", color: "#444" },
  label: { fontSize: 13, fontWeight: "600", color: "#444", marginTop: 4 },
  input: {
    borderWidth: 1, borderColor: "#ccc", borderRadius: 8, padding: 10, fontSize: 15,
  },
  row: { flexDirection: "row", gap: 8, flexWrap: "wrap" },
  menuButtons: { width: "100%", maxWidth: 280 },
  linkScreen: { flex: 1, gap: 10 },
  gap: { height: 12 },
  cameraBox: { width: "100%", height: 320, borderRadius: 12, overflow: "hidden", backgroundColor: "#000" },
  camera: { flex: 1 },
  linkText: { fontSize: 14, color: "#3b5bdb", textAlign: "center", paddingVertical: 4 },
  ok: { fontSize: 16, color: "#1a7f37", textAlign: "center" },
  fail: { fontSize: 16, color: "#b00020", textAlign: "center" },
  smallPrint: { fontSize: 12, color: "#666", textAlign: "center" },
});
