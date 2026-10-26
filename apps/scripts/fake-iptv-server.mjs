// Testovací server pro TV Stream: napodobuje Xtream Codes API (živě, rádio, filmy, seriály), TVHeadend s Digest
// přihlášením a portál Stalker / Ministra.
//   node scripts/fake-iptv-server.mjs            (port 8099)
//   adb reverse tcp:8099 tcp:8099                → na boxu je server na http://127.0.0.1:8099
// Xtream: uživatel "demo" / heslo "demo".  TVHeadend: "tvh" / "tajne" (Digest, MD5, qop=auth).
// Stalker: portál http://127.0.0.1:8099/stalker_portal/c/, MAC 00:1A:79:AA:BB:CC.
// Streamy přesměruje na veřejné testovací video / internetové rádio (SomaFM posílá skutečná ICY metadata).
import http from 'node:http';
import crypto from 'node:crypto';

const PORT = 8099;
const HLS = 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8';
const TS = 'https://test-streams.mux.dev/x36xhzz/url_0/url_462/193039199_mp4_h264_aac_hd_7.ts';
// Film = HLS VOD s pevnou délkou (.mp4 v adrese a HLS uvnitř zkouší, že aplikace umí opravit špatný odhad typu).
const MP4 = 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8';
const RADIO = { 201: 'https://ice1.somafm.com/groovesalad-128-mp3', 202: 'https://ice1.somafm.com/defcon-128-mp3', 203: 'https://ice1.somafm.com/dronezone-128-mp3' };
const XT = { user: 'demo', pass: 'demo' };
const TVH = { user: 'tvh', pass: 'tajne', realm: 'tvheadend' };
const MAC = '00:1A:79:AA:BB:CC';
const md5 = (s) => crypto.createHash('md5').update(s).digest('hex');
const nonces = new Set();
const LOGO = 'https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/czech-republic/nova-cz.png';

// Vymyšlený program: bloky po 30–90 min kolem aktuálního času, pro každý kanál jiný.
const SHOWS = ['Události', 'Počasí', 'Sport', 'Dokument: Krajina Česka', 'Film: Slunce, seno', 'Zprávy ve 23', 'Kriminálka', 'Snídaně s Novou', 'Hudební klub', 'Reportéři'];
function epgFor(key, count = 8) {
  const seed = [...key].reduce((s, ch) => s + ch.charCodeAt(0), 0);
  let t = Math.floor(Date.now() / 1800000) * 1800000 - 1800000 * ((seed % 3) + 1);
  const out = [];
  for (let i = 0; i < count; i++) {
    const len = [30, 45, 60, 90][(seed + i) % 4] * 60000;
    out.push({ start: t, stop: t + len, title: SHOWS[(seed + i) % SHOWS.length], desc: `Testovací popis pořadu č. ${i + 1} pro kanál ${key}. Ukázka delšího textu, aby bylo vidět zalomení a ořez v detailu průvodce.` });
    t += len;
  }
  return out.filter((p) => p.stop > Date.now());
}
const b64 = (s) => Buffer.from(s, 'utf8').toString('base64');
const json = (res, obj) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };

// Filmy a seriály (vymyšlené). Plakát = barevný placeholder z veřejné služby.
const poster = (n) => `https://picsum.photos/seed/tvs${n}/300/450`;
const MOVIES = Array.from({ length: 60 }, (_, i) => ({
  num: i + 1, name: `Testovací film ${i + 1} (${2000 + (i % 25)})`, stream_id: 500 + i, stream_icon: poster(i), rating: (5 + (i % 5) + 0.3).toFixed(1),
  added: String(Math.floor(Date.now() / 1000) - i * 86400), category_id: String(1 + (i % 3)), container_extension: 'mp4', year: String(2000 + (i % 25)),
}));
const SERIES = Array.from({ length: 24 }, (_, i) => ({
  series_id: 700 + i, name: `Testovací seriál ${i + 1}`, cover: poster(100 + i), plot: 'Vymyšlený seriál pro zkoušku aplikace. Ukázkový děj, který se trochu zalamuje na více řádků.',
  releaseDate: `${2005 + (i % 15)}-01-01`, rating: (6 + (i % 4)).toFixed(1), genre: 'Drama', category_id: String(1 + (i % 2)), last_modified: String(Math.floor(Date.now() / 1000) - i * 3600),
}));

function digestOk(req) {
  const h = req.headers.authorization || '';
  if (!h.startsWith('Digest ')) return false;
  const p = Object.fromEntries([...h.matchAll(/(\w+)=("([^"]*)"|[^,\s]*)/g)].map((m) => [m[1], m[3] ?? m[2]]));
  if (p.username !== TVH.user || !nonces.has(p.nonce)) return false;
  const ha1 = md5(`${TVH.user}:${TVH.realm}:${TVH.pass}`);
  const ha2 = md5(`${req.method}:${p.uri}`);
  return p.response === md5(`${ha1}:${p.nonce}:${p.nc}:${p.cnonce}:${p.qop}:${ha2}`);
}

// ---- Stalker / Ministra
const ST_TOKEN = 'TESTTOKEN123';
function stalker(req, res, url) {
  const q = url.searchParams;
  const cookie = req.headers.cookie || '';
  const js = (o) => json(res, { js: o });
  if (q.get('action') === 'handshake') return js({ token: ST_TOKEN });
  if (!cookie.includes(`mac=${MAC}`) || req.headers.authorization !== `Bearer ${ST_TOKEN}`) { res.writeHead(401); return res.end('no'); }
  const type = q.get('type'), action = q.get('action');
  if (action === 'get_profile') return js({ id: 1 });
  if (type === 'itv' && action === 'get_genres') return js([{ id: '*', title: 'All' }, { id: '10', title: 'Zprávy' }, { id: '11', title: 'Rádio' }]);
  if (type === 'itv' && action === 'get_all_channels') return js({ data: [
    { id: '901', name: 'Stalker Zprávy', number: '1', tv_genre_id: '10', logo: '', cmd: 'ffrt http://localhost/ch/901' },
    { id: '902', name: 'Stalker Sport HD', number: '2', tv_genre_id: '10', logo: '', cmd: 'ffrt http://localhost/ch/902' },
    { id: '903', name: 'Stalker Rádio Groove', number: '3', tv_genre_id: '11', logo: '', cmd: 'ffrt http://localhost/ch/903' },
  ] });
  if (type === 'itv' && action === 'create_link') { const id = (q.get('cmd') || '').split('/').pop(); return js({ cmd: `ffmpeg ${id === '903' ? RADIO[201] : HLS}` }); }
  if (type === 'itv' && action === 'get_short_epg') return js(epgFor('st' + q.get('ch_id'), 6).map((p) => ({ name: p.title, descr: p.desc, start_timestamp: p.start / 1000, stop_timestamp: p.stop / 1000 })));
  if (type === 'vod' && action === 'get_categories') return js([{ id: '1', title: 'Filmy' }]);
  if (type === 'vod' && action === 'get_ordered_list') return js({ total_items: 2, max_page_items: 14, data: [{ id: '1', name: 'Stalker film 1', cmd: '/media/1.mpg', year: '2012' }, { id: '2', name: 'Stalker film 2', cmd: '/media/2.mpg', year: '2018' }] });
  if (type === 'vod' && action === 'create_link') return js({ cmd: MP4 });
  return js([]);
}

http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const q = url.searchParams;
  console.log(req.method, url.pathname + (q.get('action') ? '?action=' + q.get('action') : ''), req.headers.authorization ? '(auth)' : '');

  // ---- Stalker
  if (url.pathname.endsWith('/server/load.php')) return stalker(req, res, url);

  // ---- Xtream Codes
  if (url.pathname === '/player_api.php') {
    if (q.get('username') !== XT.user || q.get('password') !== XT.pass) return json(res, { user_info: { auth: 0 } });
    const action = q.get('action');
    if (!action) return json(res, { user_info: { auth: 1, status: 'Active', allowed_output_formats: ['m3u8', 'ts'] }, server_info: {} });
    if (action === 'get_live_categories') return json(res, [{ category_id: '1', category_name: 'Zprávy' }, { category_id: '2', category_name: 'Sport' }, { category_id: '3', category_name: 'Rádio' }]);
    if (action === 'get_live_streams') return json(res, [
      { num: 1, name: 'Test HLS 1', stream_id: 101, category_id: '1', epg_channel_id: 'x101' },
      { num: 2, name: 'Test HLS 2', stream_id: 102, category_id: '1' },
      { num: 3, name: 'Test Sport', stream_id: 103, category_id: '2', stream_icon: LOGO },
      { num: 4, name: 'SomaFM Groove Salad', stream_id: 201, category_id: '3' },
      { num: 5, name: 'SomaFM DEF CON', stream_id: 202, category_id: '3' },
      { num: 6, name: 'SomaFM Drone Zone', stream_id: 203, category_id: '3' },
    ]);
    if (action === 'get_short_epg') return json(res, { epg_listings: epgFor('x' + q.get('stream_id'), Number(q.get('limit') || 8)).map((p) => ({
      title: b64(p.title), description: b64(p.desc), start_timestamp: String(p.start / 1000), stop_timestamp: String(p.stop / 1000),
    })) });
    if (action === 'get_vod_categories') return json(res, [{ category_id: '1', category_name: 'Akční' }, { category_id: '2', category_name: 'Komedie' }, { category_id: '3', category_name: 'Dokumenty' }]);
    if (action === 'get_vod_streams') return json(res, q.get('category_id') ? MOVIES.filter((m) => m.category_id === q.get('category_id')) : MOVIES);
    if (action === 'get_vod_info') return json(res, { info: { plot: 'Vymyšlený film pro zkoušku aplikace. Delší popis, aby bylo vidět zalamování textu v detailu.', genre: 'Akční / Komedie', releasedate: '2014-05-01', duration_secs: 5400, cast: 'Jan Novák, Eva Svobodová', director: 'Petr Dvořák', rating: '7.4', movie_image: poster(1) } });
    if (action === 'get_series_categories') return json(res, [{ category_id: '1', category_name: 'Drama' }, { category_id: '2', category_name: 'Sci-fi' }]);
    if (action === 'get_series') return json(res, q.get('category_id') ? SERIES.filter((s) => s.category_id === q.get('category_id')) : SERIES);
    if (action === 'get_series_info') {
      const seasons = {};
      for (let s = 1; s <= 3; s++) seasons[s] = Array.from({ length: 6 }, (_, e) => ({ id: String(9000 + s * 100 + e), episode_num: e + 1, title: `Epizoda ${e + 1}`, container_extension: 'mp4', season: s, info: { plot: 'Děj epizody.', duration_secs: 2700 } }));
      return json(res, { info: { name: 'Testovací seriál', cover: poster(100), plot: 'Vymyšlený seriál. Tři řady po šesti epizodách.', releaseDate: '2018-01-01', rating: '8.1', genre: 'Drama' }, episodes: seasons });
    }
    return json(res, []);
  }
  if (url.pathname.startsWith(`/live/${XT.user}/${XT.pass}/`)) {
    const id = Number(url.pathname.split('/').pop().split('.')[0]);
    res.writeHead(302, { Location: RADIO[id] ?? (url.pathname.endsWith('.ts') ? TS : HLS) });
    return res.end();
  }
  if (url.pathname.startsWith(`/movie/${XT.user}/${XT.pass}/`) || url.pathname.startsWith(`/series/${XT.user}/${XT.pass}/`)) { res.writeHead(302, { Location: MP4 }); return res.end(); }

  // ---- TVHeadend (vše pod Digest přihlášením)
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/stream/') || url.pathname.startsWith('/dvrfile/')) {
    if (!digestOk(req)) {
      const nonce = crypto.randomBytes(16).toString('hex');
      nonces.add(nonce);
      res.writeHead(401, { 'WWW-Authenticate': `Digest realm="${TVH.realm}", qop="auth", nonce="${nonce}", opaque="${md5('o')}"` });
      return res.end('Unauthorized');
    }
    if (url.pathname === '/api/serverinfo') return json(res, { sw_version: '4.3-test', name: 'fake-tvheadend' });
    if (url.pathname === '/api/channeltag/grid') return json(res, { entries: [{ uuid: 't1', name: 'ČT', enabled: true }, { uuid: 't2', name: 'Ostatní', enabled: true }] });
    if (url.pathname === '/api/channel/grid') return json(res, { entries: [
      { uuid: 'c1', name: 'ČT1 HD (test)', number: 1, enabled: true, tags: ['t1'] },
      { uuid: 'c2', name: 'ČT24 (test)', number: 24, enabled: true, tags: ['t1'] },
      { uuid: 'c3', name: 'Vypnutý kanál', number: 99, enabled: false, tags: [] },
      { uuid: 'c4', name: 'Nova (test)', number: 3, enabled: true, tags: ['t2'], icon_public_url: LOGO },
    ] });
    if (url.pathname === '/api/epg/events/grid') {
      const ev = (uuid, p) => ({ channelUuid: uuid, start: p.start / 1000, stop: p.stop / 1000, title: p.title, description: p.desc });
      if (q.get('mode') === 'now') return json(res, { entries: ['c1', 'c2', 'c4'].map((u) => ev(u, epgFor(u)[0])) });
      const ch = q.get('channel');
      return json(res, { entries: ch === 'c2' ? [] : epgFor(ch, Number(q.get('limit') || 10)).map((p) => ev(ch, p)) });
    }
    if (url.pathname === '/api/dvr/entry/grid') return json(res, { entries: [
      { uuid: 'r1', disp_title: 'Události (nahrávka)', disp_subtitle: 'Večerní vydání', channel: 'c1', channelname: 'ČT1 HD (test)', start: Math.floor(Date.now() / 1000) - 7200, stop: Math.floor(Date.now() / 1000) - 3600, sched_status: 'completed', filesize: 734003200, disp_description: 'Testovací nahrávka.' },
      { uuid: 'r2', disp_title: 'Film (nahrávka)', channel: 'c4', channelname: 'Nova (test)', start: Math.floor(Date.now() / 1000) - 90000, stop: Math.floor(Date.now() / 1000) - 83000, sched_status: 'completed', filesize: 2147483648 },
      { uuid: 'r3', disp_title: 'Zítřejší pořad', channel: 'c1', channelname: 'ČT1 HD (test)', start: Math.floor(Date.now() / 1000) + 80000, stop: Math.floor(Date.now() / 1000) + 83600, sched_status: 'scheduled' },
    ] });
    if (url.pathname.startsWith('/dvrfile/')) { res.writeHead(302, { Location: HLS }); return res.end(); }
    if (url.pathname.startsWith('/stream/channel/')) { res.writeHead(302, { Location: TS }); return res.end(); }
  }
  res.writeHead(404); res.end();
}).listen(PORT, () => console.log(`fake IPTV server on :${PORT}`));
