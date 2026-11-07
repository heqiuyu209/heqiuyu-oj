// Actual local Vue/Chrome; every API response is synthetic and external requests are blocked.
const assert = require('assert').strict;
const fs = require('fs'), os = require('os'), path = require('path');
const { spawn } = require('child_process');
const base = process.env.UI_REVIEW_URL || 'http://127.0.0.1:8080';
const output = process.env.UI_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-settings-platforms-review');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'hoj-settings-platforms-'));
fs.mkdirSync(output, { recursive: true });
const report = { fixtureOnly: true, checks: [], requests: [], errors: [], validated: false };
let actor = 'alice', accounts = [], ws, cdp;
const user = () => ({ uid: actor, username: actor, roleList: ['default_user'], gender: 'secrecy', avatar: '', solvedList: [], scoreList: [] });
const snapshot = () => ({ accounts: actor === 'alice' ? accounts : [], summary: { localSolved: 0, externalSolved: 0, uniqueSolved: 0, duplicatesRemoved: 0, complete: true } });
function fixture(request) {
  const p = new URL(request.url).pathname, method = request.method;
  const data = request.postData ? JSON.parse(request.postData) : null;
  report.requests.push({ path: p, method, data });
  if (p === '/api/get-website-config') return { name: 'HOJ', shortName: 'HOJ', openPublicDiscussion: true, openGroupDiscussion: true, openContestComment: true, register: true };
  if (p === '/api/get-user-auth-info') return { roles: ['default_user'] };
  if (p === '/api/msg/unread') return { comment: 0, reply: 0, like: 0, sys: 0, mine: 0 };
  if (p === '/api/get-common-announcement') return { total: 0, records: [] };
  if (p === '/api/get-last-week-submission-statistics') return { dateStrList: [], acCountList: [], totalCountList: [] };
  if (['/api/home-carousel', '/api/get-recent-contest', '/api/get-recent-other-contest', '/api/get-recent-seven-ac-rank', '/api/get-recent-updated-problem'].includes(p)) return [];
  if (p === '/api/learning/accounts') {
    if (method === 'POST') accounts.push({ id: 1, platform: data.platform, handle: data.handle, displayName: data.handle,
      profileUrl: 'https://atcoder.jp/users/' + data.handle, verified: false, attempted: null,
      rating: null, maxRating: null, representativeRating: null, ratedSolved: 0, historyComplete: false,
      syncState: 'IDLE', coverage: [], ratingHistory: [], stale: true });
    return snapshot();
  }
  if (p === '/api/learning/accounts/1/proof') {
    accounts[0].proofCode = 'HOJ-SYNTHETIC-CHECK'; accounts[0].proofExpiresAt = Date.now() + 600000;
    return snapshot();
  }
  if (p === '/api/learning/accounts/1/verify') { accounts[0].verified = true; accounts[0].proofCode = null; return snapshot(); }
  if (p === '/api/learning/accounts/1/submission-credentials') return { configured: false, credentialType: 'BROWSER_SESSION', submissionSupported: true, version: 0 };
  if (p.includes('/external')) throw Error('Settings requested a public profile instead of its own account list');
  return {};
}
const browser = spawn(process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--disable-extensions', '--disable-background-networking',
  '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank',
], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
let stderr = '';
browser.stderr.on('data', data => { stderr = (stderr + data).slice(-2000); });
browser.on('error', error => report.errors.push(error.message));
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const watchdog = setTimeout(() => { browser.kill(); process.exitCode = 1; }, 120000);
async function until(check, description, timeout = 15000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await check()) return; await sleep(100); }
  throw Error('Timed out: ' + description);
}
const component = name => `(function find(v){if(v.$options.name===${JSON.stringify(name)}||v.$options.__file?.endsWith('/'+${JSON.stringify(name)}+'.vue'))return v;for(const c of v.$children){const r=find(c);if(r)return r}})(document.getElementById('app').__vue__)`;
let ev;
(async () => {
  const portFile = path.join(profile, 'DevToolsActivePort');
  await until(() => { if (browser.exitCode !== null) throw Error(stderr); return fs.existsSync(portFile); }, 'Chrome startup');
  const port = fs.readFileSync(portFile, 'utf8').split('\n')[0];
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
  ws = new WebSocket(pages.find(p => p.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let id = 0;
  const pending = new Map();
  cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const seq = ++id, timer = setTimeout(() => { pending.delete(seq); reject(Error('CDP timeout: ' + method)); }, 15000);
    pending.set(seq, { resolve, reject, timer }); ws.send(JSON.stringify({ id: seq, method, params }));
  });
  ws.onmessage = async event => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const call = pending.get(message.id); pending.delete(message.id); clearTimeout(call.timer);
      message.error ? call.reject(Error(message.error.message)) : call.resolve(message.result);
    } else if (message.method === 'Runtime.exceptionThrown') report.errors.push(message.params.exceptionDetails.text);
    else if (message.method === 'Fetch.requestPaused') {
      const r = message.params, url = new URL(r.request.url);
      try {
        if (url.origin !== new URL(base).origin) return await cdp('Fetch.failRequest', { requestId: r.requestId, errorReason: 'BlockedByClient' });
        if (!url.pathname.startsWith('/api/')) return await cdp('Fetch.continueRequest', { requestId: r.requestId });
        await cdp('Fetch.fulfillRequest', { requestId: r.requestId, responseCode: 200,
          responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
          body: Buffer.from(JSON.stringify({ status: 200, data: fixture(r.request) })).toString('base64') });
      } catch (error) { report.errors.push(error.message); await cdp('Fetch.failRequest', { requestId: r.requestId, errorReason: 'Failed' }).catch(() => {}); }
    }
  };
  ev = async expression => { const r = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails)); return r.result.value; };
  await cdp('Runtime.enable'); await cdp('Page.enable'); await cdp('Fetch.enable', { patterns: [{ urlPattern: 'http*' }] });
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: `localStorage.setItem('Web_Language','"zh-CN"');` });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1080, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base + '/home' });
  await until(() => ev(`!!document.getElementById('app')?.__vue__?.$store`), 'Vue app');
  await ev(`(()=>{const s=document.getElementById('app').__vue__.$store;s.commit('changeUserInfo',{userInfo:${JSON.stringify(user())}});s.commit('changeUserToken','settings-fixture-token');return document.getElementById('app').__vue__.$router.push('/setting').then(()=>true)})()`);
  await until(() => ev(`!!${component('Setting')}`), 'settings route');
  assert.equal(report.requests.filter(r => r.path === '/api/learning/accounts').length, 0, 'Closed panel should not fetch or poll accounts');
  await ev(`[...document.querySelectorAll('.el-collapse-item__header')].find(e=>e.textContent.includes('跨平台账号关联')).click()`);
  await sleep(600);
  report.platformOptions = await ev(`[...document.querySelectorAll('.external-link select option')].map(e=>e.textContent)`);
  assert.deepEqual(report.platformOptions, ['Codeforces', 'AtCoder', '牛客'], 'Settings must expose the server-backed three-platform flow');
  assert(await ev(`${component('ExternalAccounts')}.owner`));
  assert.equal(await ev(`${component('ExternalAccounts')}.uid`), 'alice');
  report.checks.push('Settings mounts the owner component only when expanded, with all three real platforms.');
  await ev(`(()=>{const s=document.querySelector('.external-link select');s.value='ATCODER';s.dispatchEvent(new Event('change',{bubbles:true}));const i=document.querySelector('.external-link input');i.value='fixture_at';i.dispatchEvent(new Event('input',{bubbles:true}))})()`);
  await ev(`document.querySelector('.external-link button').click()`);
  await until(() => ev(`document.querySelector('.external-source')?.textContent.includes('fixture_at')`), 'linked account');
  assert(report.requests.some(r => r.path === '/api/learning/accounts' && r.method === 'POST' && r.data.platform === 'ATCODER' && r.data.handle === 'fixture_at'));
  await ev(`[...document.querySelectorAll('.source-actions button')].find(e=>e.textContent.includes('验证账号归属')).click()`);
  await until(() => ev(`!!document.querySelector('.source-proof')`), 'proof code');
  assert(await ev(`document.querySelector('.source-proof').textContent.includes('Affiliation')`));
  await ev(`document.querySelector('.source-proof button').click()`);
  await until(() => ev(`!!document.querySelector('.remote-credential-settings button')`), 'verified credential entry');
  await ev(`document.querySelector('.remote-credential-settings button').click()`);
  await until(() => ev(`!!document.querySelector('.credential-status')`), 'session configuration');
  assert(report.requests.some(r => r.path.endsWith('/submission-credentials') && r.method === 'GET'));
  await ev(`${component('RemoteCredentialSettings')}.visible=false`);
  report.checks.push('AtCoder link/proof/verification and session-settings requests reach the canonical API with fixtures.');
  for (const width of [1440, 390]) {
    await cdp('Emulation.setDeviceMetricsOverride', { width, height: 1080, deviceScaleFactor: 1, mobile: false });
    await sleep(300);
    assert(await ev('document.documentElement.scrollWidth<=innerWidth'), 'Horizontal overflow at ' + width);
    const shot = await cdp('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(output, 'settings-' + width + '.png'), Buffer.from(shot.data, 'base64'));
  }
  actor = 'bob';
  await ev(`(()=>{const s=document.getElementById('app').__vue__.$store;s.commit('changeUserInfo',{userInfo:${JSON.stringify(user())}});s.commit('changeUserToken','settings-bob-token')})()`);
  await until(() => ev(`${component('ExternalAccounts')}?.uid==='bob'&&${component('ExternalAccounts')}.snapshot?.accounts.length===0`), 'new owner context');
  assert(!await ev(`document.querySelector('.external-accounts').textContent.includes('fixture_at')`));
  await ev(`document.getElementById('app').__vue__.$store.commit('clearUserInfoAndToken')`);
  await until(() => ev(`!${component('ExternalAccounts')}`), 'logout unmount');
  report.checks.push('Owner changes clear the old account view; logout destroys the private panel.');
  assert.equal(report.errors.length, 0, report.errors.join('\n'));
  report.validated = true;
})().catch(error => { report.failure = error.stack; process.exitCode = 1; }).finally(async () => {
  clearTimeout(watchdog); if (ws) ws.close();
  const closed = new Promise(resolve => browser.once('close', resolve)); browser.kill();
  await Promise.race([closed, sleep(3000)]);
  if (browser.exitCode !== null && path.dirname(path.resolve(profile)) === path.resolve(os.tmpdir()) && path.basename(profile).startsWith('hoj-settings-platforms-')) fs.rmSync(profile, { recursive: true, force: true });
  fs.writeFileSync(path.join(output, 'settings-platforms.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ validated: report.validated, checks: report.checks, failure: report.failure, errors: report.errors }));
});
