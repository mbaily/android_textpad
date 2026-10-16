const CACHE_NAME = "textpad-editor-shell-v1";

function getScopePath() {
  const scopeUrl = new URL(self.registration.scope);
  return scopeUrl.pathname.replace(/\/$/, "");
}

function getShellUrls(scopePath) {
  return [
    `${scopePath}/`,
    `${scopePath}/manifest.webmanifest`,
    `${scopePath}/static/editor.css`,
    `${scopePath}/static/editor.js`,
    `${scopePath}/static/favicon.svg`,
    `${scopePath}/static/apple-touch-icon.png`,
    `${scopePath}/static/icon-192.png`,
    `${scopePath}/static/icon-512.png`,
  ];
}

self.addEventListener("install", (event) => {
  const scopePath = getScopePath();
  const urls = getShellUrls(scopePath);
  event.waitUntil(
    caches
      .open(CACHE_NAME)
      .then(async (cache) => {
        for (const url of urls) {
          try {
            const response = await fetch(url, { credentials: "same-origin", redirect: "manual" });
            if (response.status === 200) {
              await cache.put(url, response);
            }
          } catch {}
        }
      })
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("message", (event) => {
  if (event.data && event.data.type === "CLEAR_CACHE") {
    event.waitUntil(caches.delete(CACHE_NAME));
  }
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  const scopePath = getScopePath();
  const path = url.pathname;
  if (!path.startsWith(scopePath)) return;
  if (path.startsWith(`${scopePath}/api/`) || path === `${scopePath}/login` || path === `${scopePath}/sw.js`) {
    return;
  }

  const isEditorRoot = path === scopePath || path === `${scopePath}/`;
  if (isEditorRoot || request.mode === "navigate") {
    event.respondWith(
      (async () => {
        try {
          const networkResponse = await fetch(request);
          if (networkResponse.status === 200 && !networkResponse.redirected) {
            const cache = await caches.open(CACHE_NAME);
            await cache.put(`${scopePath}/`, networkResponse.clone());
          }
          return networkResponse;
        } catch (error) {
          const cache = await caches.open(CACHE_NAME);
          const cached = await cache.match(`${scopePath}/`);
          if (cached) return cached;
          throw error;
        }
      })()
    );
    return;
  }

  if (path.startsWith(`${scopePath}/static/`) || path === `${scopePath}/manifest.webmanifest`) {
    event.respondWith(
      (async () => {
        try {
          const networkResponse = await fetch(request);
          if (networkResponse.status === 200) {
            const cache = await caches.open(CACHE_NAME);
            await cache.put(path, networkResponse.clone());
          }
          return networkResponse;
        } catch (error) {
          const cache = await caches.open(CACHE_NAME);
          const cached = await cache.match(path);
          if (cached) return cached;
          throw error;
        }
      })()
    );
  }
});
