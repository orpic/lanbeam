/*
 * LanBeam offline copy of the page.
 *
 * Only the page itself ("/") is cached, so that when the phone has left the Android's Wi-Fi and the
 * browser reloads the tab, LanBeam shows its own "Not connected" card instead of the browser's error.
 * Everything else (API calls, uploads, downloads) goes straight to the network and is never stored.
 * Browsers may refuse to run this at all with a self-signed certificate; the page works without it.
 */
const CACHE = "lanbeam-page-v1";
const PAGE = "/";

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.add(new Request(PAGE, { cache: "reload" }))).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k !== CACHE) await caches.delete(k);
    await self.clients.claim();
  })());
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== self.location.origin) return;
  if (url.pathname !== "/" && url.pathname !== "/index.html") return; // never downloads or API calls
  e.respondWith((async () => {
    const ac = new AbortController();
    const t = setTimeout(() => ac.abort(), 4000);
    try {
      const res = await fetch(e.request, { signal: ac.signal, cache: "no-store" });
      clearTimeout(t);
      if (res.ok) (await caches.open(CACHE)).put(PAGE, res.clone());
      return res;
    } catch (_) {
      clearTimeout(t);
      return (await caches.match(PAGE)) || Response.error();
    }
  })());
});
