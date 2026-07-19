# Brick A on-device verification helpers (G20). Desktop Kreds must be
# ONLINE over Tor and the fixture already minted+pushed
# (see ON_DEVICE_CHECKLIST.md steps 1-2 -- unchanged from the spike).
# Dot-source the env first:  . .\android_tor_spike\tools\env.ps1
param([string]$Serial = "ZY32DLZQ2N")

function Beats {
    # Heartbeat lines the service logs (tag "TorNodeService" not guaranteed;
    # the notification + in-app history are the primary surfaces). Falls back
    # to a broad grep of the app's logcat.
    adb -s $Serial logcat -d | Select-String -Pattern "beat|heartbeat|TorNode|nodeBeat"
}

function FixtureThere {
    adb -s $Serial shell ls -l /sdcard/Android/data/eu.kreds.torspike/files/spike_phone_fixture.json
}

function DozeOn  { adb -s $Serial shell dumpsys deviceidle force-idle; "Doze forced. Wait >~6 min, then check the notification / in-app history for a new beat." }
function DozeOff { adb -s $Serial shell dumpsys deviceidle unforce; "Doze released." }

function KillApp {
    # Process-death recovery: START_STICKY should restart the service, which
    # re-bootstraps Tor and resumes beats unattended.
    adb -s $Serial shell am kill eu.kreds.torspike
    "Killed eu.kreds.torspike. Watch the notification reappear + beats resume (give Tor a warm re-bootstrap ~30-90s)."
}

function PullLogs {
    adb -s $Serial logcat -d > android_tor_spike\logcat.txt
    adb -s $Serial pull /sdcard/Android/data/eu.kreds.torspike/files/tor.log android_tor_spike\ 2>$null
    "Wrote android_tor_spike\logcat.txt (and tor.log if present)."
}

"Brick A on-device helpers loaded. Commands: FixtureThere | Beats | DozeOn | DozeOff | KillApp | PullLogs"
"Run order: FixtureThere -> (in app) Start node -> Beats -> background app -> Beats -> DozeOn -> wait -> Beats -> DozeOff -> KillApp -> Beats -> PullLogs"
