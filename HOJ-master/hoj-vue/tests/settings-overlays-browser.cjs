// Actual local Vue/Chrome; every API response is synthetic and external requests are blocked.
const assert = require('assert').strict;
const fs = require('fs'), os = require('os'), path = require('path');
const { spawn } = require('child_process');
const base = process.env.UI_REVIEW_URL || 'http://127.0.0.1:8080';
const output = process.env.UI_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-settings-overlays-review');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'hoj-settings-overlays-'));
fs.mkdirSync(output, { recursive: true });
const report = { fixtureOnly: true, checks: [], requests: [], errors: [], validated: false };
let actor = 'alice', ws, cdp;
const credentialState = new Map();
let accounts = ['CODEFORCES', 'ATCODER', 'NOWCODER'].map((platform, i) => ({
  id:i+1, platform, handle:platform==='NOWCODER'?'123456789012':'W'.repeat(platform==='ATCODER'?32:24),
  verified:true, attempted:null, rating:null, maxRating:null, representativeRating:null,
  ratedSolved:0, historyComplete:false, syncState:'IDLE', coverage:[], ratingHistory:[], stale:true,
  profileUrl:'https://example.invalid/synthetic-profile'
}));
const user = () => ({ uid: actor, username: actor, roleList: ['default_user'], gender: 'secrecy', avatar: '', solvedList: [], scoreList: [] });
const snapshot = () => ({ accounts: actor === 'alice' ? accounts : [], summary: { localSolved: 0, externalSolved: 0, uniqueSolved: 0, duplicatesRemoved: 0, complete: true } });
function fixture(request) {
  const p = new URL(request.url).pathname, method = request.method;
  const data = request.postData ? JSON.parse(request.postData) : null;
  report.requests.push({ path: p, method, data });
  if (p === '/api/overlay-fixture-error') return { __response: { status:400, msg:'Synthetic overlay check' } };
  if (p === '/api/get-website-config') return { name: 'HOJ', shortName: 'HOJ', openPublicDiscussion: true, openGroupDiscussion: true, openContestComment: true, register: true };
  if (p === '/api/get-user-auth-info') return { roles: ['default_user'] };
  if (p === '/api/msg/unread') return { comment: 0, reply: 0, like: 0, sys: 0, mine: 0 };
  if (p === '/api/get-common-announcement') return { total: 0, records: [] };
  if (p === '/api/get-last-week-submission-statistics') return { dateStrList: [], acCountList: [], totalCountList: [] };
  if (['/api/home-carousel', '/api/get-recent-contest', '/api/get-recent-other-contest', '/api/get-recent-seven-ac-rank', '/api/get-recent-updated-problem'].includes(p)) return [];
  const credential = p.match(/^\/api\/learning\/accounts\/(\d+)\/submission-credentials$/);
  if (credential) {
    const key=actor+':'+credential[1];
    const state=credentialState.get(key)||{configured:false, credentialType:'BROWSER_SESSION', submissionSupported:true, version:0};
    if(method==='PUT') {
      assert.equal(data.authorized,true); assert.equal(data.version,state.version);
      assert(data.cookieHeader.includes('SYNTHETIC_NEVER_SENT'));
      credentialState.set(key,{...state,configured:true,version:state.version+1,sessionCheckedAt:Date.now()});
    }
    return credentialState.get(key)||state;
  }
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
        const reply=fixture(r.request), envelope=reply.__response||{status:200,data:reply};
        await cdp('Fetch.fulfillRequest', { requestId: r.requestId, responseCode: 200,
          responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
          body: Buffer.from(JSON.stringify(envelope)).toString('base64') });
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
  await ev(`[...document.querySelectorAll('.el-collapse-item__header')].find(e=>e.textContent.includes('跨平台账号关联')).click()`);
  await until(()=>ev(`document.querySelectorAll('.remote-credential-settings > .el-button').length===3`),'three verified accounts');
  const vault = accountId => `(function find(v){if(v.$options.name==='RemoteCredentialSettings'&&v.account.id===${accountId})return v;for(const c of v.$children){const r=find(c);if(r)return r}})(document.getElementById('app').__vue__)`;
  const dialog = `[...document.querySelectorAll('.remote-credential-dialog')].find(e=>e.getBoundingClientRect().width>0)`;
  async function pointerClick(expression) {
    const point=await ev(`(()=>{const e=${expression},r=e.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;return {x,y,hit:e.contains(document.elementFromPoint(x,y))}})()`);
    assert(point.hit,'Pointer target is covered by another layer');
    await cdp('Input.dispatchMouseEvent',{type:'mousePressed',x:point.x,y:point.y,button:'left',clickCount:1});
    await cdp('Input.dispatchMouseEvent',{type:'mouseReleased',x:point.x,y:point.y,button:'left',clickCount:1});
  }
  for(const [width,height] of [[320,568],[390,844],[1440,1080]]) {
    await cdp('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile:width<600});
    for(const accountId of [1,2,3]) {
      await ev(`document.querySelectorAll('.remote-credential-settings > .el-button')[${accountId-1}].click()`);
      await until(()=>ev(`${vault(accountId)}?.settings&&!${vault(accountId)}.loading`),'session dialog loaded');
      await sleep(250);
      await ev(`${dialog}.querySelector('.credential-help summary').click()`);
      const dimensions=await ev(`(()=>{const d=${dialog},b=d.querySelector('.el-dialog__body'),r=d.getBoundingClientRect(),f=d.querySelector('.el-dialog__footer').getBoundingClientRect();return {width:innerWidth,height:innerHeight,docWidth:document.documentElement.scrollWidth,left:r.left,right:r.right,top:r.top,bottom:r.bottom,footerBottom:f.bottom,bodyWidth:b.clientWidth,bodyScrollWidth:b.scrollWidth}})()`);
      report.checks.push({accountId,width,height,dimensions});
      assert(dimensions.docWidth<=width+1,'Page horizontal overflow');
      assert(dimensions.left>=0&&dimensions.right<=width+1,'Dialog outside viewport');
      assert(dimensions.top>=0&&dimensions.bottom<=height+1&&dimensions.footerBottom<=height+1,'Footer outside viewport');
      const shot=await cdp('Page.captureScreenshot',{format:'png'});
      fs.writeFileSync(path.join(output,`session-${accountId}-${width}.png`),Buffer.from(shot.data,'base64'));
      const layers=await ev(`(()=>{const d=${dialog},c=d.querySelector('.el-dialog__headerbtn'),r=c.getBoundingClientRect(),nav=[...document.querySelectorAll('.mobile-nav,#header')].find(e=>e.getBoundingClientRect().width>0),n=(nav.querySelector('button')||nav).getBoundingClientRect();return {closeHit:c.contains(document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)),navigationBlocked:!nav.contains(document.elementFromPoint(n.left+n.width/2,n.top+n.height/2)),dialogZ:Number(getComputedStyle(d.closest('.el-dialog__wrapper')).zIndex)}})()`);
      report.checks.push({accountId,width,layers});
      assert(layers.closeHit,'Navigation covers dialog close button at '+width);
      assert(layers.navigationBlocked,'Navigation remains above modal mask');
      assert(dimensions.bodyScrollWidth<=dimensions.bodyWidth+1,'Session dialog requires horizontal scroll at '+width+' for account '+accountId);
      await ev(`(()=>{const d=${dialog},input=d.querySelector('#remote-submit-session');input.scrollIntoView({block:'center'});input.value='session=SYNTHETIC_NEVER_SENT';input.dispatchEvent(new Event('input',{bubbles:true}))})()`);
      assert.equal(await ev(`${vault(accountId)}.authorized`),false);
      assert(await ev(`(()=>{const d=${dialog};return [...d.querySelectorAll('.el-dialog__footer button')].find(b=>b.textContent.includes('核对并授权')).disabled})()`));
      await ev(`${dialog}.querySelector('.credential-consent').click()`);
      assert.equal(await ev(`${vault(accountId)}.authorized`),true);
      const putBefore=report.requests.filter(r=>r.method==='PUT').length;
      await ev(`[...${dialog}.querySelectorAll('.el-dialog__footer button')].find(b=>b.textContent.includes('核对并授权')).click()`);
      await until(()=>ev(`!${vault(accountId)}.busy&&${vault(accountId)}.settings?.configured`),'session saved');
      assert.equal(report.requests.filter(r=>r.method==='PUT').length,putBefore+1);
      assert.equal(await ev(`${vault(accountId)}.cookieHeader`),'');
      assert.equal(await ev(`${vault(accountId)}.authorized`),false);
      await ev(`[...${dialog}.querySelectorAll('.el-dialog__footer button')].find(b=>b.textContent.trim()==='关闭').click()`);
      await until(()=>ev(`!${vault(accountId)}.visible&&!${vault(accountId)}.settings`),'closed session reset');
      await ev(`document.querySelectorAll('.remote-credential-settings > .el-button')[${accountId-1}].click()`);
      await until(()=>ev(`${vault(accountId)}?.settings&&!${vault(accountId)}.loading`),'metadata-only reopen');
      assert.equal(await ev(`${vault(accountId)}.cookieHeader`),'');
      assert(!await ev(`${dialog}.textContent.includes('SYNTHETIC_NEVER_SENT')`));
      await pointerClick(`${dialog}.querySelector('.el-dialog__headerbtn')`);
      await until(()=>ev(`!${vault(accountId)}.visible&&!${vault(accountId)}.settings`),'pointer close reset');
      await sleep(220);
    }
  }
  await ev(`document.querySelectorAll('.remote-credential-settings > .el-button')[1].click()`);
  await until(()=>ev(`${vault(2)}.settings&&!${vault(2)}.loading`),'open before identity switch');
  await pointerClick(`[...${dialog}.querySelectorAll('.el-dialog__footer button')].find(b=>b.textContent.includes('撤销授权'))`);
  await until(()=>ev(`!![...document.querySelectorAll('.el-message-box__wrapper')].find(e=>e.getBoundingClientRect().width>0)`),'nested revoke confirmation');
  assert(await ev(`(()=>{const d=${dialog},box=[...document.querySelectorAll('.el-message-box__wrapper')].find(e=>e.getBoundingClientRect().width>0);return Number(getComputedStyle(box).zIndex)>Number(getComputedStyle(d.closest('.el-dialog__wrapper')).zIndex)})()`));
  await pointerClick(`[...document.querySelector('.el-message-box__btns').querySelectorAll('button')].find(b=>b.textContent.includes('取消'))`);
  await until(()=>ev(`![...document.querySelectorAll('.el-message-box__wrapper')].some(e=>e.getBoundingClientRect().width>0)`),'revoke cancelled');
  await ev(`document.getElementById('app').__vue__.$http.get('/api/overlay-fixture-error').catch(()=>false)`);
  await until(()=>ev(`!![...document.querySelectorAll('.m-message-container')].find(e=>e.textContent.includes('Synthetic overlay check'))`),'real error toast');
  assert(await ev(`(()=>{const d=${dialog},m=[...document.querySelectorAll('.m-message-container')].find(e=>e.textContent.includes('Synthetic overlay check'));return Number(getComputedStyle(m).zIndex)>Number(getComputedStyle(d.closest('.el-dialog__wrapper')).zIndex)})()`),'Global error toast must be above the active dialog');
  report.checks.push('Nested confirmation and real interceptor error toast stay above the active dialog.');
  await ev(`${vault(2)}.cookieHeader='SYNTHETIC_NEVER_SENT';true`);
  actor='bob';
  await ev(`document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:${JSON.stringify(user())}});true`);
  await until(()=>ev(`!${vault(2)}`),'old owner dialog destroyed');
  assert(!await ev(`document.body.textContent.includes('SYNTHETIC_NEVER_SENT')`));
  assert.equal(report.errors.length, 0, report.errors.join('\n'));
  report.validated = true;
})().catch(error => { report.failure = error.stack; process.exitCode = 1; }).finally(async () => {
  clearTimeout(watchdog); if (ws) ws.close();
  const closed = new Promise(resolve => browser.once('close', resolve)); browser.kill();
  await Promise.race([closed, sleep(3000)]);
  if (browser.exitCode !== null && path.dirname(path.resolve(profile)) === path.resolve(os.tmpdir()) && path.basename(profile).startsWith('hoj-settings-overlays-')) fs.rmSync(profile, { recursive: true, force: true });
  fs.writeFileSync(path.join(output, 'settings-overlays.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify({ validated: report.validated, checks: report.checks, failure: report.failure, errors: report.errors }));
});
