import { registerRootComponent } from "expo";

import WebShell from "./WebShell";

// vp1: the phone's UI is now the desktop web app in a WebView (WebShell owns
// the engine lifecycle for slice 1). App.tsx (the old dev dashboard) is left in
// the tree, unregistered, for reference / engine-control fallback.
registerRootComponent(WebShell);
