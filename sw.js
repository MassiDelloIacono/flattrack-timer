// Fa funzionare l'app anche senza connessione (in pista spesso non c'è campo).
const CACHE = 'ftt-v67';
const FILES = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png', './icon-maskable-192.png', './icon-maskable-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// Prima la rete (così ricevi gli aggiornamenti), se non c'è campo usa la copia salvata.
// I font di Google vengono salvati la prima volta che li scarichi.
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  e.respondWith(
    // per i file dell'app chiedo sempre la versione aggiornata (niente cache del browser)
    (new URL(req.url).origin === location.origin ? fetch(req.url, {cache: 'no-cache'}) : fetch(req)).then(res => {
      const copy = res.clone();
      caches.open(CACHE).then(c => c.put(req, copy)).catch(() => {});
      return res;
    }).catch(() => caches.match(req).then(r => r || caches.match('./index.html')))
  );
});
