// Review the running local UI. Credentials come from the environment, never from fixtures.
// Only login and the existing read-only dashboard POST are allowed. Form changes are discarded.
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');
const assert = require('assert').strict;
const base = process.env.UI_REVIEW_URL || 'http://127.0.0.1:8080';
const output = process.env.ADMIN_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-admin-review');
if (!process.env.HOJ_REVIEW_USERNAME || !process.env.HOJ_REVIEW_PASSWORD) throw Error('Set HOJ_REVIEW_USERNAME and HOJ_REVIEW_PASSWORD locally.');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'hoj-admin-browser-'));
const report = { base, checks: [], errors: [], blockedWrites: [], validated: false };
const browser = spawn(process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--disable-extensions',
  '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank',
], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
let ws, stderr = '';
browser.stderr.on('data', data => { stderr = (stderr + data).slice(-1000); });
browser.on('error', error => { stderr = error.message; });
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(check, name, timeout = 45000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await check()) return; await sleep(250); }
  throw Error('Timed out: ' + name);
}
(async () => {
  fs.mkdirSync(output, { recursive: true });
  const portFile = path.join(profile, 'DevToolsActivePort');
  await until(() => { if (browser.exitCode !== null) throw Error(stderr); return fs.existsSync(portFile); }, 'Chrome startup', 15000);
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
      const call = pending.get(message.id); clearTimeout(call.timer); pending.delete(message.id);
      message.error ? call.reject(Error(message.error.message)) : call.resolve(message.result);
    } else if (message.method === 'Runtime.exceptionThrown') {
      report.errors.push(message.params.exceptionDetails.text);
    } else if (message.method === 'Fetch.requestPaused') {
      const { requestId, request } = message.params;
      const allowedPost = request.method === 'POST' && ['/api/admin/login', '/api/admin/dashboard/get-sessions'].includes(new URL(request.url).pathname);
      if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method) && !allowedPost) {
        report.blockedWrites.push({ method: request.method, path: new URL(request.url).pathname });
        cdp('Fetch.failRequest', { requestId, errorReason: 'BlockedByClient' }).catch(() => {});
      } else cdp('Fetch.continueRequest', { requestId }).catch(() => {});
    }
  };
  const ev = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
  };
  const shot = async name => {
    if (!name.includes('training-prerequisite')) assert(await ev("![...document.querySelectorAll('.el-message-box__wrapper')].some(e => e.offsetHeight)"), 'unexpected dialog obscures ' + name);
    const screenshot = await cdp('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(output, name + '.png'), Buffer.from(screenshot.data, 'base64'));
  };
  const injectHelpers = () => ev(`window.findReviewVm = function (match, vm = document.getElementById('app').__vue__) { if (match(vm)) return vm; for (const child of vm.$children || []) { const found = findReviewVm(match, child); if (found) return found; } }; true`);
  const click = async selector => {
    const point = await ev(`(() => { const node = document.querySelector(${JSON.stringify(selector)}); node.scrollIntoView({block:'center'}); const rect = node.getBoundingClientRect(); return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 }; })()`);
    await cdp('Input.dispatchMouseEvent', { type: 'mousePressed', ...point, button: 'left', clickCount: 1 });
    await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', ...point, button: 'left', clickCount: 1 });
  };
  const theme = async value => { await ev(`findReviewVm(v => v.$options.props?.themeOnly).set('theme', ${JSON.stringify(value)}); true`); await sleep(150); };
  const route = async pathname => {
    await ev(`document.getElementById('app').__vue__.$router.push(${JSON.stringify(pathname)}).then(()=>true,()=>false)`);
    await until(() => ev(`location.pathname===${JSON.stringify(pathname)} && !!document.querySelector('.content-app .el-card')`), pathname);
    await sleep(900);
    await ev('window.scrollTo(0,0)');
  };
  const color = selector => ev(`(() => { const node = document.querySelector(${JSON.stringify(selector)}); if (!node) throw Error('Missing color target: ' + ${JSON.stringify(selector)}); return getComputedStyle(node).backgroundColor; })()`);
  await cdp('Runtime.enable'); await cdp('Page.enable'); await cdp('Network.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*', requestStage: 'Request' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1080, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base + '/admin/login' });
  await until(() => ev("!!document.querySelector('.admin-login .el-input__inner')"), 'login');
  await injectHelpers();
  for (const mode of ['light', 'dark']) { await theme(mode); await shot('admin-login-' + mode); }
  await ev(`(() => { const vm = findReviewVm(v => !!v.ruleForm2); vm.ruleForm2.username = ${JSON.stringify(process.env.HOJ_REVIEW_USERNAME)}; vm.ruleForm2.password = ${JSON.stringify(process.env.HOJ_REVIEW_PASSWORD)}; vm.handleLogin(); return true; })()`);
  await until(() => ev("location.pathname==='/admin/dashboard'"), 'administrator login');
  await sleep(2500);
  report.checks.push({ check: 'real-admin-login', passed: true });
  for (const mode of ['light', 'dark']) {
    await theme(mode);
    const surface = mode === 'dark' ? 'rgb(25, 47, 60)' : 'rgb(255, 255, 255)';
    await route('/admin/user');
    assert.equal(await color('#admin-header'), surface);
    assert.equal(await color('.el-card'), surface);
    assert(await ev("document.body.classList.contains('admin-view') && !document.querySelector('#header') && document.documentElement.scrollWidth <= innerWidth"));
    await shot('admin-user-' + mode);
    // Original user editor: opening and closing only.
    await ev("document.querySelector('.vxe-body--row .el-icon-edit-outline').closest('button').click(); true");
    await sleep(450);
    assert.equal(await color('.dialog .el-dialog, .el-dialog__wrapper:not([style*="display: none"]) .el-dialog'), surface);
    await shot('admin-user-dialog-' + mode);
    await ev("[...document.querySelectorAll('.el-dialog__wrapper')].find(e => e.offsetHeight)?.querySelector('.el-dialog__headerbtn').click(); true");
    await route('/admin/notice');
    assert(await ev("getComputedStyle(document.querySelector('.admin-notice-hint')).color === getComputedStyle(document.querySelector('.footer')).color"));
    await shot('admin-notice-' + mode);
    await route('/admin/switch');
    // Exercise both states in the form only; never click its Save button.
    await ev("(() => { const vm = findReviewVm(v => !!v.switchConfig); vm.switchConfig.openPublicJudge = true; vm.switchConfig.openGroupJudge = false; return true; })()");
    await sleep(400);
    const off = await color('.el-switch:not(.is-checked) .el-switch__core');
    assert.equal(off, mode === 'dark' ? 'rgb(69, 95, 112)' : 'rgb(66, 93, 108)');
    await shot('admin-switch-' + mode);
    for (const pathname of ['/admin/contest/create', '/admin/training/create', '/admin/problem/create']) {
      await route(pathname);
      await until(() => ev("!!document.querySelector('.v-show-content')"), 'Markdown editor');
      assert.equal(await color('.v-show-content'), surface);
      assert.equal(await color('.auto-textarea-input'), surface);
      assert(await ev("getComputedStyle(document.querySelector('.v-note-op .op-icon')).color === getComputedStyle(document.querySelector('.footer')).color"));
      if (pathname === '/admin/contest/create') {
        await ev("(() => { const input = document.querySelector('.auto-textarea-input'); input.value = '## 赛前说明\\n\\n请阅读比赛规则。支持 **C++、Python、Java**。'; input.dispatchEvent(new Event('input', { bubbles: true })); input.closest('.v-note-wrapper').scrollIntoView({block:'center'}); return true; })()");
        await sleep(250);
        assert(await ev("document.querySelector('.v-show-content').textContent.includes('赛前说明')"));
        await shot('admin-editor-' + mode);
        await click('.el-date-editor input');
        await until(() => ev("!!document.querySelector('.el-picker-panel')?.offsetHeight"), 'date picker');
        await sleep(300);
        assert.equal(await color('.el-picker-panel'), surface);
        await shot('admin-datepicker-' + mode);
        await ev("document.querySelector('.el-date-editor input').blur(); document.querySelector('.panel-title').click(); true");
      }
      const prerequisite = pathname.includes('training') && await ev("[...document.querySelectorAll('.el-message-box__wrapper')].some(e => e.offsetHeight)");
      if (prerequisite) {
        await shot('admin-training-prerequisite-' + mode);
        await ev("document.querySelector('.el-message-box__btns button').click(); true");
        await until(() => ev("location.pathname==='/admin/training/category' && ![...document.querySelectorAll('.el-message-box__wrapper')].some(e=>e.offsetHeight)"), 'training category prerequisite');
      }
      report.checks.push({ check: 'editor-theme', mode, path: pathname, passed: true, ...(prerequisite ? { limitation: 'Existing category prerequisite shown; training form submission not tested.' } : {}) });
    }
    report.checks.push({ check: 'shell-dialog-notice-switch', mode, off, passed: true });
  }
  await route('/admin/user');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await sleep(600);
  assert(await ev("!!document.querySelector('.admin-mobile-nav') && document.documentElement.scrollWidth <= innerWidth"));
  await shot('admin-mobile-dark');
  await ev("document.querySelector('.admin-mobile-nav .mu-icon-button').click(); true"); await sleep(350);
  await shot('admin-mobile-drawer-dark');
  // Verify the admin body scope is removed when returning to the public UI.
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1080, deviceScaleFactor: 1, mobile: false });
  await ev("document.getElementById('app').__vue__.$router.push('/home').then(()=>true,()=>false)");
  await sleep(1200);
  assert(await ev("!document.body.classList.contains('admin-view') && !document.documentElement.classList.contains('admin-view') && !!document.querySelector('#header') && !findReviewVm(v => v.$options.props?.themeOnly).themeOnly"));
  report.checks.push({ check: 'mobile-and-public-scope', passed: true });
  // Hold only external application scripts, so the real early-theme script/loader run normally.
  await cdp('Network.setBlockedURLs', { urls: ['*.js', '*.js?*'] });
  for (const mode of ['light', 'dark', 'system']) {
    await ev(`localStorage.setItem('cdutetc:appearance', JSON.stringify({ theme: ${JSON.stringify(mode)} })); true`);
    await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'dark' }, { name: 'prefers-reduced-motion', value: 'reduce' }] });
    await cdp('Page.navigate', { url: base + '/admin/login' });
    await until(() => ev("!!document.querySelector('#app-loader img')?.naturalWidth"), 'badge loader');
    assert.equal(await color('#app-loader'), mode === 'light' ? 'rgb(237, 243, 246)' : 'rgb(16, 31, 41)');
    assert(await ev("getComputedStyle(document.querySelector('.loader-badge'), '::after').animationName === 'none'"));
    await shot('loader-' + mode);
    report.checks.push({ check: 'early-loader-badge-theme-reduced-motion', mode, passed: true });
  }
  await cdp('Network.setBlockedURLs', { urls: [] });
  await cdp('Page.navigate', { url: base + '/admin/user' });
  await until(() => ev("!!document.querySelector('.content-app .el-card') && !document.getElementById('app-loader')"), 'loader removed');
  assert.equal(report.blockedWrites.length, 0, 'unexpected API write');
  assert.equal(report.errors.length, 0);
  report.validated = true;
})().catch(error => { report.failure = error.message; process.exitCode = 1; }).finally(() => {
  if (ws) ws.close(); browser.kill();
  fs.mkdirSync(output, { recursive: true });
  fs.writeFileSync(path.join(output, 'admin-theme-review.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
});
