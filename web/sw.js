/*
 * Service worker: makes the client installable and usable offline.
 *
 * The whole app is two files, so there is nothing to be clever about: cache them on install, serve
 * from the cache first, and update in the background on every load. Phones are only reachable on the
 * local network, so the page never needs the network after the first visit.
 */
'use strict';

const CACHE = 'remotephone-v1';
const ASSETS = [
  'index.html',
  'remotephone.js',
  'manifest.webmanifest',
  'icon-192.png',
  'icon-512.png',
  'icon-maskable.png',
  'apple-touch-icon.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return; // the phone's own requests are never cached

  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const hit = await cache.match(req, { ignoreSearch: true });
    if (hit) {
      // Serve at once, refresh in the background so the next load gets the new file
      if (req.mode === 'navigate' || url.pathname.endsWith('/remotephone.js')) {
        event.waitUntil((async () => {
          try {
            const fresh = await fetch(req);
            if (fresh.ok) await cache.put(req, fresh.clone());
          } catch { /* offline: keep what we have */ }
        })());
      }
      return hit;
    }
    try {
      const fresh = await fetch(req);
      if (fresh.ok) cache.put(req, fresh.clone());
      return fresh;
    } catch (e) {
      const fallback = await cache.match('index.html');
      if (fallback) return fallback;
      throw e;
    }
  })());
});
