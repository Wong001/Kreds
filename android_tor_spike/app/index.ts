import { registerRootComponent } from "expo";

import FirstLoad from "./FirstLoad";

// First-load pairing (Task 7): FirstLoad is now the root component -- it
// gates on hasIdentity() and renders WebShell unchanged once a device is
// linked (vp1: the phone's UI is the desktop web app in a WebView, WebShell
// owns the engine lifecycle). App.tsx (the old dev dashboard) is left in
// the tree, unregistered, for reference / engine-control fallback.
registerRootComponent(FirstLoad);
