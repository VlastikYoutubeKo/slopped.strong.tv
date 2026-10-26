// Spouští tabuli z odjezdy-system (app/board.js, motivy, vector.js) BEZE ZMĚN v Android WebView.
// Napodobuje jen to, co board.js potřebuje z Node.js. Systémovou vrstvu OS (aktualizace, Wi-Fi, instalátor,
// více displejů, vzdálená správa) nahrazuje neškodnými náhradami – na Androidu nedává smysl a nemá co dělat.
// /dev/fb0 = <canvas>, klávesy přicházejí z ovladače přes Kotlin (__key), soubory z assets přes Native.
'use strict';
(function () {
  const W = 1920, H = 1080, STRIDE = W * 4;      // nativní rozlišení TV → LCD motivy kreslí ostré TTF písmo
  const args = JSON.parse(Native.args());        // např. ["--no-menu"] ve spořiči
  const PERSIST = ['/etc/odjezdy/', '/var/lib/odjezdy/'];
  const isPersist = (p) => PERSIST.some((d) => p.startsWith(d));

  // ---------------------------------------------------------------- obrazovka
  const canvas = document.getElementById('screen');
  canvas.width = W; canvas.height = H;
  const ctx = canvas.getContext('2d', { alpha: false });
  const image = ctx.createImageData(W, H);
  const dst32 = new Uint32Array(image.data.buffer);
  // board.js zapisuje do framebufferu jen změněné řádky (fs.writeSync s offsetem) – převádíme a kreslíme jen ty.
  let dirty0 = H, dirty1 = 0, frameBuf = null, pending = false;
  function present() {
    pending = false;
    if (!frameBuf || dirty1 <= dirty0) return;
    const y0 = dirty0, y1 = dirty1;
    dirty0 = H; dirty1 = 0;
    const src = new Uint32Array(frameBuf.buffer, frameBuf.byteOffset + y0 * STRIDE, (y1 - y0) * W);
    const base = y0 * W;
    // framebuffer je B G R A (LE 0xAARRGGBB), canvas R G B A (LE 0xAABBGGRR): prohodit R a B
    for (let i = 0; i < src.length; i++) {
      const p = src[i];
      dst32[base + i] = (p & 0xff00ff00) | ((p >>> 16) & 0xff) | ((p & 0xff) << 16);
    }
    ctx.putImageData(image, 0, 0, 0, y0, W, y1 - y0);
  }
  function markDirty(buf, pos, len) {
    frameBuf = buf;
    dirty0 = Math.min(dirty0, Math.floor(pos / STRIDE));
    dirty1 = Math.max(dirty1, Math.min(H, Math.ceil((pos + len) / STRIDE)));
    if (!pending) { pending = true; requestAnimationFrame(present); }
  }

  // ---------------------------------------------------------------- CommonJS loader (soubory z assets)
  const cache = new Map();
  const builtins = {};
  const stubs = {};
  const assetPath = (p) => p.replace(/^\/+/, '');
  const norm = (p) => {
    const out = [];
    for (const seg of p.split('/')) { if (seg === '..') out.pop(); else if (seg && seg !== '.') out.push(seg); }
    return '/' + out.join('/');
  };
  const path = {
    sep: '/',
    join: (...a) => norm(a.filter((x) => x !== '').join('/')),
    resolve: (...a) => norm(a.reduce((acc, x) => (x.startsWith('/') ? x : acc + '/' + x), '/')),
    dirname: (p) => p.replace(/\/[^/]*$/, '') || '/',
    basename: (p, ext) => { const b = p.replace(/^.*\//, ''); return ext && b.endsWith(ext) ? b.slice(0, -ext.length) : b; },
    extname: (p) => (/\.[^./]*$/.exec(p) || [''])[0],
  };
  const PKG = { 'base64-js': '/vendor/base64-js/index.js', ieee754: '/vendor/ieee754/index.js', buffer: '/vendor/buffer/index.js' };
  function load(file, transform) {
    if (stubs[file]) return stubs[file];
    if (cache.has(file)) return cache.get(file).exports;
    let src = Native.readText(assetPath(file));
    if (src == null) throw enoent(file);
    src = src.replace(/^#!.*/, '');
    if (transform) src = transform(src);
    const module = { exports: {}, filename: file };
    cache.set(file, module);
    const dir = path.dirname(file);
    const req = (id) => {
      if (builtins[id]) return builtins[id];
      if (PKG[id]) return load(PKG[id]);
      let p = id.startsWith('/') ? norm(id) : path.join(dir, id);
      if (!/\.(js|json)$/.test(p)) p += '.js';
      if (p.endsWith('.json')) return JSON.parse(Native.readText(assetPath(p)));
      return load(p);
    };
    const fn = new Function('module', 'exports', 'require', '__dirname', '__filename', 'process', 'Buffer', src + '\n//# sourceURL=' + file);
    fn(module, module.exports, req, dir, file, process, RealBuffer);
    return module.exports;
  }
  const enoent = (p) => Object.assign(new Error('ENOENT: no such file, ' + p), { code: 'ENOENT' });

  // Buffer = balík „buffer“ z npm (MIT), stejný, jaký používají bundlery pro prohlížeč; načte se při startu.
  let RealBuffer;

  // ---------------------------------------------------------------- process
  let stdinCb = null;
  const t0 = performance.now();
  const process = {
    env: { W: String(W), H: String(H), ODJEZDY_NO_UPDATE: '1', ODJEZDY_VT: 'tty1', ODJEZDY_DISPLAYS: 'off', ODJEZDY_CONFIG: '/etc/odjezdy/config.json' },
    argv: ['node', '/app/board.js', ...args],
    platform: 'linux',
    stdin: { isTTY: true, setRawMode() {}, setEncoding() {}, on(ev, cb) { if (ev === 'data') stdinCb = cb; } },
    stdout: { isTTY: true, write() {} },
    stderr: { write(s) { Native.log(String(s).trim()); } },
    hrtime: { bigint: () => BigInt(Math.round(performance.now() * 1e6)) },
    memoryUsage: () => ({ rss: (performance.memory && performance.memory.usedJSHeapSize) || 0 }),
    uptime: () => (performance.now() - t0) / 1000,
    on() {},
    exit(code) { Native.log('process.exit(' + code + ')'); Native.exit(code | 0); },
  };

  // ---------------------------------------------------------------- fs
  const mem = new Map();
  const sysfs = {
    '/sys/class/graphics/fb0/virtual_size': `${W},${H}`,
    '/sys/class/graphics/fb0/bits_per_pixel': '32',
    '/sys/class/graphics/fb0/stride': String(STRIDE),
    '/sys/class/graphics/fb0/name': 'android-webview',
    '/sys/class/tty/tty0/active': 'tty1',
  };
  const encOf = (o) => (typeof o === 'string' ? o : o && o.encoding) || null;
  const fs = {
    constants: { O_RDONLY: 0, O_WRONLY: 1, O_RDWR: 2, O_NONBLOCK: 2048 },
    readFileSync(p, opts) {
      p = String(p);
      const enc = encOf(opts);
      const asText = (t) => (enc ? t : RealBuffer.from(t, 'utf8'));
      if (sysfs[p] != null) return asText(sysfs[p]);
      if (p === '/proc/meminfo') return asText(Native.meminfo());
      if (isPersist(p)) { const t = Native.fileLoad(p); if (t == null) throw enoent(p); return asText(t); }
      if (mem.has(p)) return asText(mem.get(p));
      const a = assetPath(p);
      if (p.endsWith('.gz')) {
        // Build Androidu ukládá *.gz z assets rozbalené (bez přípony) – podporujeme obojí.
        if (Native.exists(a)) return Object.assign(RealBuffer.alloc(0), { __gz: a });
        if (Native.exists(a.slice(0, -3))) return Object.assign(RealBuffer.alloc(0), { __plain: a.slice(0, -3) });
        throw enoent(p);
      }
      if (enc) { const t = Native.readText(a); if (t == null) throw enoent(p); return t; }
      const b64 = Native.readBase64(a);
      if (b64 == null) throw enoent(p);
      return RealBuffer.from(b64, 'base64');
    },
    existsSync(p) { p = String(p); return sysfs[p] != null || mem.has(p) || (isPersist(p) ? Native.fileLoad(p) != null : Native.exists(assetPath(p))); },
    writeFileSync(p, data) { p = String(p); const t = typeof data === 'string' ? data : RealBuffer.from(data).toString('utf8'); if (isPersist(p)) Native.fileSave(p, t); else mem.set(p, t); },
    appendFileSync(p, data) { Native.log(String(data).trim()); },
    renameSync(a, b) { const t = fs.readFileSync(a, 'utf8'); fs.writeFileSync(b, t); fs.unlinkSync(a); },
    unlinkSync(p) { if (isPersist(p)) Native.fileDelete(p); else mem.delete(p); },
    mkdirSync() {},
    readdirSync(p, opts) {
      const a = assetPath(String(p));
      if (!Native.isDir(a)) throw enoent(p);
      const names = JSON.parse(Native.list(a));
      if (!(opts && opts.withFileTypes)) return names;
      return names.map((n) => { const d = Native.isDir(a + '/' + n); return { name: n, isDirectory: () => d, isFile: () => !d }; });
    },
    openSync(p) { if (p === '/dev/fb0') return 3; throw enoent(p); },   // vstupní zařízení /dev/input nejsou → ovladač jde přes stdin
    writeSync(fd, buf, off = 0, len = buf.length, pos = 0) { if (fd === 3) markDirty(buf, pos == null ? 0 : pos, len); return len; },
    readSync() { throw Object.assign(new Error('EAGAIN'), { code: 'EAGAIN' }); },
    closeSync() {},
    fstatSync() { throw enoent('fd'); },
    readlinkSync(p) { throw enoent(p); },
  };
  const gunzip = (b) => RealBuffer.from((b.__plain ? Native.readText(b.__plain) : Native.readGz(b.__gz)) || '', 'utf8');

  Object.assign(builtins, {
    fs,
    path,
    zlib: { gunzipSync: gunzip, deflateSync() { throw new Error('zlib.deflateSync není v Androidu k dispozici'); } },
    os: {
      homedir: () => '/home/odjezdy',
      loadavg: () => [0, 0, 0],
      networkInterfaces() { const ip = Native.ip(); return ip ? { wlan0: [{ family: 'IPv4', internal: false, address: ip }] } : {}; },
    },
    child_process: {
      exec(cmd, o, cb) { cb = typeof o === 'function' ? o : cb; if (cb) setTimeout(() => cb(new Error('není k dispozici')), 0); },
      execFile(f, a, o, cb) { cb = [a, o, cb].find((x) => typeof x === 'function'); if (cb) setTimeout(() => cb(new Error('není k dispozici')), 0); },
      spawn() { throw new Error('není k dispozici'); },
    },
    module: { _resolveFilename: (req) => req },
    crypto: { randomBytes: (n) => RealBuffer.from(globalThis.crypto.getRandomValues(new Uint8Array(n))) },
  });

  // ---------------------------------------------------------------- náhrady systémových modulů OS
  const VERSION = (Native.readText('app/VERSION') || '').trim() || 'android';
  stubs['/app/updater.js'] = {
    UPDATE_DIR: '/var/lib/odjezdy/app',
    pick: () => null,                       // na Androidu se kód nikdy nestahuje ani nepřepisuje
    versionOf: () => VERSION,
    check: async () => null,
    install: async () => { throw new Error('Aktualizace jde přes novou verzi aplikace'); },
    confirm() {},
  };
  stubs['/app/system.js'] = {
    cmdline: () => '',
    isLive: () => false,
    reboot() {},
    setKeymap() {},
    wifi: { available: () => false, status: async () => ({}), scan: async () => [], connect: async () => ({ ok: false }) },
    installer: { available: () => false, defaultOptions: () => ({}), options: async () => null, disks: async () => [], run() {} },
  };
  stubs['/app/displays.js'] = {
    detect: () => ({ mode: 'single' }),
    preferredMode: () => null,
    displayConfig: (n) => `/etc/odjezdy/display${n}.json`,
    startChildren() {}, stopChildren() {}, reloadChild() {},
    startKms: async () => { throw new Error('KMS není k dispozici'); },
    kmsConnect: async () => null, wlList: async () => [], wlConnect: async () => null,
  };
  stubs['/app/admin.js'] = {
    start() {},                             // vzdálená správa (HTTP server) na TV neběží
    addresses: () => [],
    readToken: () => '',
    newToken() {},
    // stejná logika jako app/admin.js: jas podle rozvrhu z nastavení
    scheduledBrightness(rules, now = new Date()) {
      const day = now.getDay() === 0 ? 7 : now.getDay(), min = now.getHours() * 60 + now.getMinutes();
      const toMin = (s) => { const m = /^(\d{1,2}):(\d{2})$/.exec(String(s || '')); return m ? (+m[1]) * 60 + (+m[2]) : null; };
      for (const r of rules || []) {
        const a = toMin(r.from), b = toMin(r.to);
        if (a == null || b == null) continue;
        const days = Array.isArray(r.days) && r.days.length ? r.days : [1, 2, 3, 4, 5, 6, 7];
        const inRange = a <= b ? min >= a && min < b : min >= a || min < b;
        const startDay = a > b && min < b ? (day === 1 ? 7 : day - 1) : day;
        if (inRange && days.includes(startDay)) return Math.max(0, Math.min(100, Number(r.brightness) || 0));
      }
      return 100;
    },
  };

  // Vlastní hlavičky (User-Agent) by vynutily CORS preflight; požadavky vyřizuje nativní proxy (BoardHost).
  const nativeFetch = globalThis.fetch.bind(globalThis);
  globalThis.fetch = (url, opts = {}) => nativeFetch(url, { signal: opts.signal });

  // ---------------------------------------------------------------- start
  try {
    RealBuffer = load('/vendor/buffer/index.js').Buffer;
    // Polyfill v compare() nejdřív kopíruje celý cílový buffer. board.js porovnává snímek po řádcích
    // (1080× za snímek), což dělalo ~9 GB kopírování a 10 s na snímek. Node to má nativně (memcmp),
    // tady stejné chování bez kopírování; zarovnané úseky po 32 bitech.
    RealBuffer.prototype.compare = function (target, start = 0, end = target.length, thisStart = 0, thisEnd = this.length) {
      const la = thisEnd - thisStart, lb = end - start, n = Math.min(la, lb);
      let i = 0;
      if (((this.byteOffset + thisStart) & 3) === 0 && ((target.byteOffset + start) & 3) === 0) {
        const q = n >> 2;
        const a32 = new Uint32Array(this.buffer, this.byteOffset + thisStart, q);
        const b32 = new Uint32Array(target.buffer, target.byteOffset + start, q);
        let k = 0;
        while (k < q && a32[k] === b32[k]) k++;
        i = k << 2;
      }
      for (; i < n; i++) {
        const x = this[thisStart + i], y = target[start + i];
        if (x !== y) return x < y ? -1 : 1;
      }
      return la < lb ? -1 : la > lb ? 1 : 0;
    };
    load('/app/board.js', (src) => src + `
;globalThis.__odj = {
  mode: () => state.mode,
  query: () => state.query || '',
  setQuery(q) { state.query = q; state.results = searchStops(q, setupMaxResults(m)); state.sel = 0; },
  favorites: () => (state.cfg.favorites || []).map((s) => s.name),
  stop: () => state.cfg.stop.name,
};`);
  } catch (e) {
    document.getElementById('err').textContent = 'Chyba při startu tabule:\n' + (e.stack || e);
    Native.log('fatal ' + (e.stack || e));
  }

  // Klávesy z ovladače ve stejné podobě, jakou board.js čte z terminálu.
  globalThis.__key = (k) => { if (stdinCb) stdinCb(k); };
  window.addEventListener('error', (e) => Native.log('js ' + e.message + ' @' + e.filename + ':' + e.lineno));
  window.addEventListener('unhandledrejection', (e) => Native.log('js promise ' + (e.reason && e.reason.stack || e.reason)));
})();
