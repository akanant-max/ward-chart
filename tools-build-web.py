"""Builds web/ -- the iPad (and any browser) install of Ward Chart.

Same page and same shim.js as the Android app; adds a manifest, a service
worker so it opens offline, and home-screen icons. Run from the repo root.
"""
import io, os, shutil, sys, time
from PIL import Image, ImageDraw

A = "app/src/main/assets"
OUT = "web"
shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT + "/icons")

page = io.open(A + "/index.html", encoding="utf-8").read()
head_extra = (
    '<link rel="manifest" href="manifest.webmanifest">\n'
    '<meta name="apple-mobile-web-app-capable" content="yes">\n'
    '<meta name="mobile-web-app-capable" content="yes">\n'
    '<meta name="apple-mobile-web-app-title" content="Ward Chart">\n'
    '<meta name="apple-mobile-web-app-status-bar-style" content="default">\n'
    '<meta name="theme-color" content="#1c1b19">\n'
    '<link rel="apple-touch-icon" href="icons/icon-180.png">\n'
)
page = page.replace("<head>\n", "<head>\n" + head_extra, 1)
reg = ("<script>if('serviceWorker' in navigator){window.addEventListener('load',function(){"
       "navigator.serviceWorker.register('sw.js').catch(function(){});});}</script>\n</body>")
page = page.replace("</body>", reg, 1)
io.open(OUT + "/index.html", "w", encoding="utf-8").write(page)
shutil.copy(A + "/shim.js", OUT + "/shim.js")

io.open(OUT + "/manifest.webmanifest", "w").write("""{
  "name": "Ward Chart", "short_name": "Ward Chart",
  "start_url": "./", "scope": "./", "display": "standalone",
  "background_color": "#1c1b19", "theme_color": "#1c1b19",
  "icons": [
    {"src": "icons/icon-192.png", "sizes": "192x192", "type": "image/png"},
    {"src": "icons/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any maskable"}
  ]
}
""")

ver = str(int(time.time()))
io.open(OUT + "/sw.js", "w").write("""const CACHE = 'ward-chart-%s';
const FILES = ['./', 'index.html', 'shim.js', 'manifest.webmanifest', 'icons/icon-180.png', 'icons/icon-192.png', 'icons/icon-512.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(caches.match(e.request, { ignoreSearch: true }).then(hit => hit || fetch(e.request)));
});
""" % ver)

def icon(n):
    im = Image.new("RGB", (n, n), "#1c1b19")
    d = ImageDraw.Draw(im)
    m = n * 0.24
    d.rounded_rectangle([m, m * 0.9, n - m, n - m * 0.9], radius=n * 0.06, outline="#f2efe8", width=max(2, n // 40))
    for i in range(3):
        y = n * (0.40 + i * 0.12)
        d.line([n * 0.34, y, n * 0.66, y], fill="#f2efe8", width=max(2, n // 48))
    im.save("%s/icons/icon-%d.png" % (OUT, n))
for n in (180, 192, 512): icon(n)
print("wrote web/ (cache ward-chart-%s)" % ver)
