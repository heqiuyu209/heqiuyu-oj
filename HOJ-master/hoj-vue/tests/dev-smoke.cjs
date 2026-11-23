// Real local development server: GET requests only, no fixtures or account writes.
// --hmr temporarily appends an invisible CSS property and restores it in finally.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');
const assert = require('assert').strict;
const base = process.env.UI_REVIEW_URL || 'http://127.0.0.1:8080';
const output = process.env.DEV_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-dev-review');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'hoj-dev-browser-'));
const cssFile = path.resolve(__dirname, '../src/styles/collections.css');
const probe = 'hmr-' + Date.now();
const patch = Buffer.from('\n/* HOJ dev smoke ' + probe + ' */\n:root { --hoj-dev-probe: ' + probe + '; }\n');
let inserted = false, ws;
const report = { base, api: [], pages: [], errors: [], validated: false };
const browser = spawn(process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--disable-extensions',
  '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank',
], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
let stderr = '';
browser.stderr.on('data', data => { stderr = (stderr + data).slice(-1500); });
browser.on('error', error => { stderr = error.message; });
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check, description, timeout = 45000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (await check()) return;
    await sleep(400);
  }
  throw Error('Timed out: ' + description);
}
function restore() {
  if (!inserted) return;
  // Remove only our exact marker, preserving any concurrent user edits.
  const current = fs.readFileSync(cssFile), index = current.indexOf(patch);
  if (index !== -1) fs.writeFileSync(cssFile, Buffer.concat([current.subarray(0, index), current.subarray(index + patch.length)]));
  inserted = false;
}
(async () => {
  fs.mkdirSync(output, { recursive: true });
  const portFile = path.join(profile, 'DevToolsActivePort');
  await until(() => {
    if (browser.exitCode !== null) throw Error(stderr);
    return fs.existsSync(portFile);
  }, 'Chrome startup', 15000);
  const port = fs.readFileSync(portFile, 'utf8').split('\n')[0];
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
  ws = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let id = 0;
  const pending = new Map();
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const seq = ++id;
    const timer = setTimeout(() => { pending.delete(seq); reject(Error('CDP timeout: ' + method)); }, 15000);
    pending.set(seq, { resolve, reject, timer });
    ws.send(JSON.stringify({ id: seq, method, params }));
  });
  ws.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const call = pending.get(message.id);
      clearTimeout(call.timer); pending.delete(message.id);
      message.error ? call.reject(Error(message.error.message)) : call.resolve(message.result);
    } else if (message.method === 'Runtime.exceptionThrown') {
      report.errors.push(message.params.exceptionDetails.text);
    }
  };
  const ev = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw Error(result.exceptionDetails.text);
    return result.result.value;
  };
  await cdp('Runtime.enable');
  await cdp('Page.enable');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1080, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base + '/home' });
  await until(() => ev("!!document.querySelector('.home-grid')"), 'home page');
  for (const endpoint of ['/api/get-website-config', '/api/get-problem-list?currentPage=1&limit=3', '/api/languages']) {
    const response = await fetch(base + endpoint, { signal: AbortSignal.timeout(15000) });
    const body = await response.json();
    report.api.push({ endpoint, http: response.status, status: body.status });
    assert.equal(response.status, 200); assert.equal(body.status, 200);
  }
  const scoreboard = await fetch(base + '/scrollBoard/', { signal: AbortSignal.timeout(15000) });
  const scoreboardHtml = await scoreboard.text();
  assert.equal(scoreboard.status, 200);
  assert(/Scroll\s*Board|滚榜/.test(scoreboardHtml));
  const redirect = await fetch(base + '/scrollBoard?config=test', { redirect: 'manual' });
  assert.equal(redirect.status, 301);
  assert.equal(redirect.headers.get('location'), '/scrollBoard/?config=test');
  report.scoreboard = { status: scoreboard.status, redirect: redirect.status };
  for (const route of ['/home', '/problem', '/training', '/contest', '/discussion', '/group']) {
    await ev(`document.getElementById('app').__vue__.$router.push(${JSON.stringify(route)}).then(()=>true,()=>false)`);
    await sleep(1400);
    const state = await ev(`({path:location.pathname,overflow:document.documentElement.scrollWidth>innerWidth,heading:document.querySelector('.collection-heading h1')?.textContent,failed:!!document.querySelector('.collection-feedback[role="alert"]')})`);
    report.pages.push(state);
    assert.equal(state.path, route); assert(!state.overflow && !state.failed);
  }
  await ev("document.getElementById('app').__vue__.$router.push('/home').then(()=>true)");
  await sleep(700);
  if (process.argv.includes('--hmr')) {
    await ev(`window.__hojDevSentinel=${JSON.stringify(probe)}`);
    fs.appendFileSync(cssFile, patch); inserted = true;
    await until(() => ev(`getComputedStyle(document.documentElement).getPropertyValue('--hoj-dev-probe').trim()===${JSON.stringify(probe)}`), 'CSS hot update', 60000);
    assert.equal(await ev('window.__hojDevSentinel'), probe, 'page reloaded instead of hot updating');
    restore();
    await until(() => ev("!getComputedStyle(document.documentElement).getPropertyValue('--hoj-dev-probe').trim()"), 'CSS restoration', 60000);
    report.hmr = { applied: true, restored: true, pageStatePreserved: await ev('window.__hojDevSentinel') === probe };
    assert(report.hmr.pageStatePreserved);
  }
  const screenshot = await cdp('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(output, 'dev-home.png'), Buffer.from(screenshot.data, 'base64'));
  assert.equal(report.errors.length, 0);
  report.validated = true;
})().catch(error => {
  report.failure = error.message;
  process.exitCode = 1;
}).finally(() => {
  restore();
  if (ws) ws.close();
  browser.kill();
  fs.writeFileSync(path.join(output, 'dev-smoke.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
});
