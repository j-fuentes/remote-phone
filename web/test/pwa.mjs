// Static checks for the PWA files: manifest fields, sw asset list vs disk, icons, index wiring.
import { readFileSync, existsSync } from 'node:fs';
const dir = new URL('../', import.meta.url); // web/
const m = JSON.parse(readFileSync(new URL('manifest.webmanifest', dir), 'utf8'));
const fail = [];
const need = ['name', 'short_name', 'start_url', 'display', 'icons', 'theme_color', 'background_color'];
for (const k of need) if (!(k in m)) fail.push('manifest missing ' + k);
if (m.display !== 'standalone' && m.display !== 'fullscreen') fail.push('display is ' + m.display);
if (!m.icons.some((i) => i.sizes === '192x192')) fail.push('no 192 icon');
if (!m.icons.some((i) => i.sizes === '512x512')) fail.push('no 512 icon');
if (!m.icons.some((i) => i.purpose === 'maskable')) fail.push('no maskable icon');
for (const i of m.icons) if (!existsSync(new URL(i.src, dir))) fail.push('missing icon file ' + i.src);
const sw = readFileSync(new URL('sw.js', dir), 'utf8');
const listed = [...sw.matchAll(/'([\w.-]+\.(?:html|js|png|webmanifest))'/g)].map((x) => x[1]);
for (const f of listed) if (!existsSync(new URL(f, dir))) fail.push('sw lists a missing file: ' + f);
for (const f of ['index.html', 'remotephone.js', 'manifest.webmanifest']) if (!listed.includes(f)) fail.push('sw does not cache ' + f);
const html = readFileSync(new URL('index.html', dir), 'utf8');
if (!html.includes('rel="manifest"')) fail.push('index.html has no manifest link');
if (!html.includes('apple-touch-icon')) fail.push('index.html has no apple-touch-icon');
if (!html.includes('id="install"')) fail.push('index.html has no install button');
const js = readFileSync(new URL('remotephone.js', dir), 'utf8');
if (!js.includes("register('sw.js')")) fail.push('remotephone.js does not register the service worker');
if (!js.includes('beforeinstallprompt')) fail.push('no beforeinstallprompt handler');
console.log(fail.length ? 'pwa: ' + fail.join('; ') : 'pwa: ok (' + listed.length + ' cached assets)');
process.exit(fail.length ? 1 : 0);
