#!/usr/bin/env node
// Review page for the daily wallpaper list (wallpapers/daily_wallpapers.json, see docs/wallpapers.md).
// Shows every light/dark pair over a mock home screen; approve or reject each photo, add new ones by their
// Unsplash image link (they wait under "spare" until Apply pairs them), then "Apply" rewrites
// the list without the rejected photos (the rest are paired up again). Decisions are kept in
// wallpapers/review.json, so a later session only asks about new photos. Publish with scripts/publish-wallpapers.sh.
//
//   npm run wallpapers            # http://inilauncher-wallpapers.localhost:4411
//   WALLPAPERS_PORT=5000 npm run wallpapers

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
// WALLPAPERS_DIR points at another folder (e.g. a copy, for trying things out)
const DIR = process.env.WALLPAPERS_DIR ? path.resolve(process.env.WALLPAPERS_DIR) : path.join(root, 'wallpapers');
const LIST = path.join(DIR, 'daily_wallpapers.json');
const REVIEW = path.join(DIR, 'review.json');
const PORT = Number(process.env.WALLPAPERS_PORT || 4411);

const readJson = (file, fallback) => (fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : fallback);
const writeJson = (file, data) => {
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + '\n');
  fs.renameSync(tmp, file);
};

function load() {
  const list = readJson(LIST, null);
  if (!list || !Array.isArray(list.pairs)) throw new Error(`No pairs in ${LIST}`);
  const review = readJson(REVIEW, { decisions: {} });
  return { list, review };
}

/** Drops rejected photos and pairs the rest up again in order; photos left without a partner wait in "spare". */
function apply() {
  const { list, review } = load();
  const rejected = (p) => review.decisions[p.url] === 'rejected';
  const lights = [...list.pairs.map((p) => p.light), ...(list.spare?.light ?? [])].filter((p) => !rejected(p));
  const darks = [...list.pairs.map((p) => p.dark), ...(list.spare?.dark ?? [])].filter((p) => !rejected(p));
  const n = Math.min(lights.length, darks.length);
  if (n === 0) throw new Error('That would leave no pairs: approve at least one light and one dark photo');
  const next = { ...list, pairs: lights.slice(0, n).map((light, i) => ({ light, dark: darks[i] })) };
  delete next.spare;
  if (lights.length > n || darks.length > n) next.spare = { light: lights.slice(n), dark: darks.slice(n) };
  writeJson(LIST, next);
  return { pairs: n, spareLight: lights.length - n, spareDark: darks.length - n };
}

/** The image link the app downloads: the photo's base URL with the size the list uses. */
function normalizeImage(raw) {
  let u;
  try {
    u = new URL(String(raw).trim());
  } catch {
    throw new Error('The image link is not a URL');
  }
  if (u.hostname === 'plus.unsplash.com') throw new Error('That is an Unsplash+ photo, which is not free to use');
  if (u.protocol !== 'https:' || u.hostname !== 'images.unsplash.com' || !/^\/photo-[\w-]+$/.test(u.pathname)) {
    throw new Error('Paste the image address (images.unsplash.com/photo-…): right-click the photo on Unsplash → Copy image address');
  }
  return `https://images.unsplash.com${u.pathname}?w=1440&q=85&fit=max`;
}

/** Adds a photo to "spare" (light or dark) as "to review"; Apply pairs it with a waiting photo of the other kind. */
async function addPhoto({ image, page, by, kind }) {
  if (!['light', 'dark'].includes(kind)) throw new Error('Choose light or dark');
  const url = normalizeImage(image);
  const pageUrl = String(page ?? '').trim().replace(/[?#].*$/, '');
  if (pageUrl && !/^https:\/\/unsplash\.com\/photos\/[\w-]+$/.test(pageUrl)) {
    throw new Error('The page link should look like https://unsplash.com/photos/… (or leave it empty)');
  }
  const handle = String(by ?? '').trim().replace(/^@?/, '@');
  const { list } = load();
  const base = (u) => u.replace(/\?.*$/, '');
  const all = [...list.pairs.flatMap((p) => [p.light, p.dark]), ...(list.spare?.light ?? []), ...(list.spare?.dark ?? [])];
  if (all.some((p) => base(p.url) === base(url))) throw new Error('That photo is already in the list');
  const check = await fetch(url, { method: 'HEAD' }).catch(() => null);
  if (!check?.ok || !(check.headers.get('content-type') ?? '').startsWith('image/')) {
    throw new Error('That image link does not load');
  }
  const photo = { url, page: pageUrl || 'https://unsplash.com', by: handle === '@' ? '' : handle };
  const spare = { light: [], dark: [], ...list.spare };
  spare[kind] = [...spare[kind], photo];
  writeJson(LIST, { ...list, spare });
  return photo;
}

function body(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => {
      data += c;
      if (data.length > 100_000) reject(new Error('Body too large'));
    });
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch {
        reject(new Error('Malformed JSON'));
      }
    });
  });
}

function send(res, status, payload, type = 'application/json') {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(type === 'application/json' ? JSON.stringify(payload) : payload);
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/') return send(res, 200, PAGE, 'text/html; charset=utf-8');
    if (req.method === 'GET' && req.url === '/favicon.svg') return send(res, 200, FAVICON, 'image/svg+xml');
    if (req.method === 'GET' && req.url === '/api/list') return send(res, 200, load());
    if (req.method === 'POST' && req.url === '/api/review') {
      const { url, status } = await body(req);
      if (typeof url !== 'string' || !['approved', 'rejected', 'unreviewed'].includes(status)) {
        return send(res, 400, { error: 'Need "url" and "status" (approved, rejected or unreviewed)' });
      }
      const { review } = load();
      if (status === 'unreviewed') delete review.decisions[url];
      else review.decisions[url] = status;
      writeJson(REVIEW, review);
      return send(res, 200, { ok: true });
    }
    if (req.method === 'POST' && req.url === '/api/add') return send(res, 200, await addPhoto(await body(req)));
    if (req.method === 'POST' && req.url === '/api/apply') return send(res, 200, apply());
    send(res, 404, { error: 'Not found' });
  } catch (e) {
    send(res, 400, { error: e.message });
  }
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Wallpaper review: http://inilauncher-wallpapers.localhost:${PORT}  (Ctrl+C to stop)`);
});

const FAVICON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="7" fill="#17201C"/><rect x="7" y="6" width="8" height="20" rx="2" fill="#e8efe9"/><rect x="17" y="6" width="8" height="20" rx="2" fill="#4b5a52"/></svg>`;

const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Inilauncher wallpapers</title>
<link rel="icon" href="/favicon.svg">
<style>
  :root { color-scheme: light dark; --bg:#f4f5f3; --fg:#1b1f1d; --muted:#6a716d; --card:#fff; --line:#dde1de; --ok:#1f7a4d; --no:#b3261e; }
  @media (prefers-color-scheme: dark) { :root { --bg:#121513; --fg:#e7ebe8; --muted:#97a09a; --card:#1b1f1d; --line:#2c322e; } }
  * { box-sizing: border-box; }
  body { margin:0; font:15px/1.45 system-ui, sans-serif; background:var(--bg); color:var(--fg); }
  header { position:sticky; top:0; z-index:2; display:flex; flex-wrap:wrap; gap:.6rem 1rem; align-items:center; padding:.8rem 1rem; background:var(--bg); border-bottom:1px solid var(--line); }
  h1 { font-size:1.05rem; margin:0; flex:1 1 auto; }
  .stats { color:var(--muted); font-size:.9rem; }
  .filters { display:flex; gap:.3rem; flex-wrap:wrap; }
  button { font:inherit; border:1px solid var(--line); background:var(--card); color:var(--fg); border-radius:8px; padding:.4rem .7rem; cursor:pointer; min-height:36px; }
  button[aria-pressed="true"] { border-color:var(--fg); }
  button.apply { background:var(--fg); color:var(--bg); border-color:var(--fg); }
  main { padding:1rem; display:grid; gap:1rem; grid-template-columns:repeat(auto-fill, minmax(min(100%, 300px), 1fr)); }
  .pair { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:.7rem; }
  .pair h2 { font-size:.85rem; font-weight:600; margin:0 0 .5rem; color:var(--muted); }
  .photos { display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:.6rem; }
  .photo { display:flex; flex-direction:column; gap:.4rem; min-width:0; }
  .phone { position:relative; aspect-ratio:9/19.5; border-radius:14px; overflow:hidden; background:#888 center/cover no-repeat; outline:3px solid transparent; outline-offset:2px; }
  .photo[data-status="approved"] .phone { outline-color:var(--ok); }
  .photo[data-status="rejected"] .phone { outline-color:var(--no); opacity:.45; }
  .mock { position:absolute; inset:0; padding:14% 10%; display:flex; flex-direction:column; gap:.35rem; font-size:clamp(9px, 2.6vw, 12px); }
  .mock .clock { font-size:2.6em; font-weight:300; line-height:1; }
  .mock .date { opacity:.85; margin-bottom:auto; }
  .mock .app { font-size:1.35em; }
  .mock .apps { margin-bottom:22%; display:grid; gap:.35em; }
  .light .mock { color:#000; } .dark .mock { color:#fff; }
  .meta { font-size:.8rem; color:var(--muted); display:flex; justify-content:space-between; gap:.3rem; }
  .meta a { color:inherit; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; min-width:0; }
  .actions { display:grid; grid-template-columns:1fr 1fr; gap:.3rem; }
  .actions button { padding:.3rem; font-size:.85rem; }
  .photo[data-status="approved"] .yes { background:var(--ok); color:#fff; border-color:var(--ok); }
  .photo[data-status="rejected"] .no { background:var(--no); color:#fff; border-color:var(--no); }
  .toast { position:fixed; left:50%; bottom:1rem; transform:translateX(-50%); background:var(--fg); color:var(--bg); padding:.6rem 1rem; border-radius:8px; opacity:0; transition:opacity .2s; pointer-events:none; max-width:90vw; }
  .toast.show { opacity:1; }
  .hidden { display:none; }
  .add { margin:1rem 1rem 0; background:var(--card); border:1px solid var(--line); border-radius:12px; padding:.7rem; }
  .add summary { cursor:pointer; font-weight:600; }
  .add form { display:grid; gap:.6rem; margin-top:.7rem; grid-template-columns:repeat(auto-fit, minmax(min(100%, 220px), 1fr)); align-items:end; }
  .add label { display:grid; gap:.25rem; font-size:.85rem; color:var(--muted); }
  .add input, .add select { font:inherit; color:var(--fg); background:var(--bg); border:1px solid var(--line); border-radius:8px; padding:.45rem .6rem; min-height:36px; width:100%; }
  .add p { margin:.5rem 0 0; font-size:.85rem; color:var(--muted); }
</style>
</head>
<body>
<header>
  <h1>Daily wallpapers</h1>
  <span class="stats" id="stats"></span>
  <div class="filters" role="group" aria-label="Show">
    <button data-filter="all" aria-pressed="true">All</button>
    <button data-filter="unreviewed" aria-pressed="false">To review</button>
    <button data-filter="rejected" aria-pressed="false">Rejected</button>
  </div>
  <button class="apply" id="apply">Apply to list</button>
</header>
<details class="add" id="addBox">
  <summary>Add photo</summary>
  <p>On unsplash.com, open a free photo, right-click it → <b>Copy image address</b>, and paste it here. Portrait photos work best: calm and bright for light (black text), dark and low-contrast for dark (white text). New photos wait as spare until <b>Apply</b> pairs them.</p>
  <form id="addForm">
    <label>Image address<input name="image" required placeholder="https://images.unsplash.com/photo-…" inputmode="url" autocomplete="off"></label>
    <label>Photo page (optional)<input name="page" placeholder="https://unsplash.com/photos/…" inputmode="url" autocomplete="off"></label>
    <label>Photographer (optional)<input name="by" placeholder="@username" autocomplete="off"></label>
    <label>Kind<select name="kind"><option value="light">Light</option><option value="dark">Dark</option></select></label>
    <button type="submit">Add</button>
  </form>
</details>
<main id="grid"></main>
<div class="toast" id="toast" role="status"></div>
<script>
let data, filter = 'all';
const $ = (s) => document.querySelector(s);
const status = (p) => data.review.decisions[p.url] || 'unreviewed';
const toast = (t) => { const el = $('#toast'); el.textContent = t; el.classList.add('show'); clearTimeout(el._t); el._t = setTimeout(() => el.classList.remove('show'), 3000); };
const thumb = (u) => u.replace(/\\?.*$/, '') + '?w=480&q=70&fit=max';
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;' }[c]));

function photoHtml(p, kind) {
  return '<div class="photo ' + kind + '" data-url="' + esc(p.url) + '" data-status="' + status(p) + '">' +
    '<div class="phone" style="background-image:url(&quot;' + esc(thumb(p.url)) + '&quot;)">' +
      '<div class="mock"><div class="clock">9:41</div><div class="date">Thu, 26 Nov</div>' +
      '<div class="apps"><div class="app">Phone</div><div class="app">Messages</div><div class="app">Camera</div><div class="app">Notes</div></div></div>' +
    '</div>' +
    '<div class="meta"><span>' + kind + '</span><a href="' + esc(p.page) + '" target="_blank" rel="noopener">' + esc(p.by || 'Unsplash') + '</a></div>' +
    '<div class="actions"><button class="yes" data-set="approved">Approve</button><button class="no" data-set="rejected">Reject</button></div>' +
  '</div>';
}

function render() {
  const groups = data.list.pairs.map((pair, i) => ({ title: 'Day ' + (i + 1), light: pair.light, dark: pair.dark }));
  const spare = data.list.spare || {};
  (spare.light || []).forEach((p) => groups.push({ title: 'Spare (no partner yet)', light: p }));
  (spare.dark || []).forEach((p) => groups.push({ title: 'Spare (no partner yet)', dark: p }));
  $('#grid').innerHTML = groups.map((g) => {
    const shown = [g.light, g.dark].filter(Boolean).some((p) => filter === 'all' || status(p) === filter);
    return '<section class="pair' + (shown ? '' : ' hidden') + '"><h2>' + g.title + '</h2><div class="photos">' +
      (g.light ? photoHtml(g.light, 'light') : '<div></div>') + (g.dark ? photoHtml(g.dark, 'dark') : '<div></div>') + '</div></section>';
  }).join('');
  const all = groups.flatMap((g) => [g.light, g.dark].filter(Boolean));
  const count = (s) => all.filter((p) => status(p) === s).length;
  $('#stats').textContent = all.length + ' photos · ' + count('approved') + ' approved · ' + count('rejected') + ' rejected · ' + count('unreviewed') + ' to review';
}

async function load() {
  const r = await fetch('/api/list');
  data = await r.json();
  if (!r.ok) return toast(data.error);
  render();
}

$('#grid').addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-set]');
  if (!btn) return;
  const el = btn.closest('.photo');
  const next = el.dataset.status === btn.dataset.set ? 'unreviewed' : btn.dataset.set;
  const r = await fetch('/api/review', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url: el.dataset.url, status: next }) });
  if (!r.ok) return toast((await r.json()).error);
  if (next === 'unreviewed') delete data.review.decisions[el.dataset.url]; else data.review.decisions[el.dataset.url] = next;
  render();
});

document.querySelectorAll('[data-filter]').forEach((b) => b.addEventListener('click', () => {
  filter = b.dataset.filter;
  document.querySelectorAll('[data-filter]').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
  render();
}));

$('#addForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = e.target;
  const btn = form.querySelector('button');
  btn.disabled = true;
  const r = await fetch('/api/add', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(Object.fromEntries(new FormData(form))) });
  const res = await r.json();
  btn.disabled = false;
  if (!r.ok) return toast(res.error);
  form.image.value = ''; form.page.value = ''; form.by.value = '';
  toast('Added as spare ' + form.kind.value + ' photo. Approve it, then Apply to list.');
  load();
});

$('#apply').addEventListener('click', async () => {
  const r = await fetch('/api/apply', { method: 'POST' });
  const res = await r.json();
  if (!r.ok) return toast(res.error);
  toast('List saved: ' + res.pairs + ' pairs' + (res.spareLight + res.spareDark ? ', ' + (res.spareLight + res.spareDark) + ' spare' : '') + '. Publish with scripts/publish-wallpapers.sh');
  load();
});

load();
</script>
</body>
</html>`;
