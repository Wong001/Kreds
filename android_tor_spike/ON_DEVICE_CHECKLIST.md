# Spike on-device run (G20, serial ZY32DLZQ2N)

The desk gates are all green (spike pytest 9/9 + wire vitest 20/20; the TS
handshake completes real HELLO/AUTH against the real node over loopback).
This is the one human-driven step: prove the same handshake over Tor from
the phone.

Every PowerShell session that touches adb must first dot-source the env
(the JDK/SDK vars are User-level and not inherited):

    . .\android_tor_spike\tools\env.ps1

## Prep (desk, once)

1. Desktop Kreds app CLOSED. From the repo root:

       .venv\Scripts\python.exe android_tor_spike\tools\mint_phone_fixture.py

   (asks for the app-lock credential if you have one; writes
   android_tor_spike\spike_phone_fixture.json -- gitignored, holds the
   phone's device private key.)

2. Start the desktop Kreds app and wait until it is fully online (Tor
   connected -- the node must be reachable at its onion). The fixture's
   onion_addr is baked in at mint time, so mint AFTER the onion is stable
   for this run.

## Phone (USB, from the repo root)

3. `. .\android_tor_spike\tools\env.ps1`
4. `adb -s ZY32DLZQ2N install -r android_tor_spike\app\android\app\build\outputs\apk\release\app-release.apk`
5. Open "KredsTorSpike" on the phone once, then background it (first open
   creates the app's external files dir that step 6 pushes into).
6. `adb -s ZY32DLZQ2N push android_tor_spike\spike_phone_fixture.json /sdcard/Android/data/eu.kreds.torspike/files/`
7. In the app, tap **Connect**.

Expected: "tor bootstrap N%" climbing (first run can take 1-3 min), then
"dialing home node" (tens of seconds -- onion circuit build), then
"handshake", then the result line **CONNECTED to home node over Tor**.

## If it fails: note the LAST stage line + the result line

That pair localizes the layer. All the layers are separable by design.

| Last stage / result line                     | Layer that failed             | Grab this                    |
|----------------------------------------------|-------------------------------|------------------------------|
| ERROR: ... (reading fixture)                 | push path / steps 5-6         | rerun steps 5-6              |
| tor bootstrap stuck / TOR_TIMEOUT            | tor-android bootstrap         | tor.log + logcat             |
| TOR_DIED: dlopen(libtor.so) failed           | libtor.so not loadable (W^X?) | logcat                       |
| TOR_DIED: tor_api symbol missing             | JNI shim vs libtor mismatch   | logcat                       |
| TOR_DIED: ... set_command_line failed        | bad tor args                  | logcat                       |
| TOR_DIED: tor exited with code N             | tor self-exited               | tor.log + logcat             |
| ERROR: ... (dialing) / socks                 | SOCKS dial / node onion       | is the desktop online?       |
| FAILED at hello: node cert failed / identity | node HELLO / wrong node       | result line                  |
| FAILED at auth: ...                          | device-key proof (desk-gated) | result line                  |
| FAILED at probe: accepted by non-home-node   | dialed the wrong onion        | fixture onion_addr           |
| REFUSED by node                              | cert/identity not recognized  | result line + fixture        |

(The TOR_DIED reason strings come straight from the JNI shim's exit codes
-- a dlopen failure exits before tor.log is written, so that reason line
IS the diagnostic. Any FAILED-at-hello/auth is highly unlikely: the wire
port is desk-gated byte-for-byte, so it implicates the transport bridge or
a wrong node, not the crypto.)

Diagnostics:
- `adb -s ZY32DLZQ2N logcat -d > android_tor_spike\logcat.txt`
- `adb -s ZY32DLZQ2N pull /sdcard/Android/data/eu.kreds.torspike/files/tor.log android_tor_spike\`

## Cleanup (whenever the spike phone should stop being able to auth)

- Phone: uninstall the app, OR
  `adb -s ZY32DLZQ2N shell rm /sdcard/Android/data/eu.kreds.torspike/files/spike_phone_fixture.json`
- Desk: delete android_tor_spike\spike_phone_fixture.json
- The cert was never published to the node's store (proven in
  tests/test_handshake_desk.py: the unpublished own-identity cert
  authenticates via the own-identity path), so there is nothing to revoke
  -- deleting the two fixture copies IS the removal. `revoke_device` with
  the fixture's device_pub remains available as belt-and-braces.
