const CACHE = "kreds-shell-v2";
const SHELL = ["/", "/static/style.css", "/static/app.js",
               "/static/manifest.json",
               "/static/fonts/bricolage-400.woff2",
               "/static/fonts/bricolage-600.woff2",
               "/static/fonts/bricolage-700.woff2",
               "/static/fonts/instrument-sans-400.woff2",
               "/static/fonts/instrument-sans-500.woff2",
               "/static/fonts/instrument-sans-600.woff2",
               "/static/fonts/plex-mono-400.woff2",
               "/static/fonts/plex-mono-500.woff2",
               "/static/icons/icon-192.png",
               "/static/icons/icon-512.png",
               "/static/icons/icon-512-maskable.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((ks) =>
    Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener("fetch", (e) => {
  const u = new URL(e.request.url);
  // Never cache live node data: API + websocket + blobs are network-only.
  if (u.pathname.startsWith("/api/") || u.pathname.startsWith("/ws")) return;
  // Network-first with cache fallback: the "server" is the user's own
  // local node, which is usually up, so always prefer the live shell (a
  // future app.js/style.css change reaches installed PWAs without anyone
  // needing to remember to bump CACHE). Fall back to the cached copy only
  // when the network is actually unavailable (offline).
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy));
        return res;
      })
      .catch(() => caches.match(e.request))
  );
});
