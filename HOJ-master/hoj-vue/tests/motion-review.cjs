// Actual local UI, guest session, no fixtures or business writes.
// Run: node tests/motion-review.cjs (Chrome and Node 22+ required).
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const assert = require('assert').strict;
const base = process.env.UI_REVIEW_URL || 'http://127.0.0.1:8080';
const output = process.env.MOTION_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-motion-review');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'hoj-motion-browser-'));
const report = { base, errors: [], checks: [], validated: false };
const browser = spawn(process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', [
  '--headless=new', '--disable-gpu', '--no-first-run', '--disable-extensions',
  '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank',
], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
let ws, stderr = '';
browser.stderr.on('data', data => { stderr = (stderr + data).slice(-1500); });
browser.on('error', error => { stderr = error.message; });
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const watchdog = setTimeout(() => { browser.kill(); process.exit(1); }, 180000);
async function until(check, description, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await check()) return;
    await sleep(60);
  }
  throw Error('Timed out: ' + description);
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
      report.errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text);
    }
  };
  const ev = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
  };
  const shot = async name => {
    const result = await cdp('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(output, name + '.png'), Buffer.from(result.data, 'base64'));
  };
  const click = async selector => {
    const point = await ev(`(() => { const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2}; })()`);
    await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', ...point });
    await cdp('Input.dispatchMouseEvent', { type: 'mousePressed', ...point, button: 'left', clickCount: 1 });
    await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', ...point, button: 'left', clickCount: 1 });
    return point;
  };
  await cdp('Runtime.enable'); await cdp('Page.enable'); await cdp('Animation.enable');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1080, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base + '/home' });
  await until(() => ev("!!document.querySelector('.home-grid')"), 'home page');
  const bindPage = () => ev(`window.findMotionVm = function (match, vm = document.getElementById('app').__vue__) { if (match(vm)) return vm; for (const child of vm.$children || []) { const found=findMotionVm(match,child); if(found) return found; } };
    window.motionSettings=findMotionVm(v=>v.$options.props?.themeOnly);
    window.motionNav=findMotionVm(v=>v.$options.methods?.onNavLeave);
    window.motionAnimations=[]; window.nativeAnimate=Element.prototype.animate;
    Element.prototype.animate=function(frames,options){ if(options?.pseudoElement) motionAnimations.push({frames,options}); return nativeAnimate.call(this,frames,options); };
    true`);
  await bindPage();
  const settled = () => until(() => ev("!document.documentElement.classList.contains('theme-revealing')"), 'theme transition cleanup');
  // Do not emulate media here: the original regression was hidden by forcing
  // no-preference even though this machine actually requests reduced motion.
  const nativeMotion = await ev("({systemReduced:matchMedia('(prefers-reduced-motion: reduce)').matches,setting:motionSettings.settings.motion,effective:document.documentElement.dataset.motion,duration:getComputedStyle(document.querySelector('#header')).transitionDuration})");
  assert.equal(nativeMotion.setting, 'system');
  assert.equal(nativeMotion.effective, nativeMotion.systemReduced ? 'reduced' : 'full');
  await click('#header .appearance-control .el-button'); await sleep(300);
  await click('.motion-options .el-radio-button:nth-child(2)');
  assert.equal(await ev('document.documentElement.dataset.motion'), 'full');
  assert(await ev("getComputedStyle(document.querySelector('#header')).transitionDuration.includes('0.62s')"));
  await click('.theme-options .el-radio-button:nth-child(2)');
  await until(() => ev('motionAnimations.length===1'), 'explicit full animation with native system preference');
  assert.equal(await ev('motionAnimations[0].options.duration'), 1400);
  await settled(); await shot('motion-full-settings');
  // Reload rather than just inspecting storage: the selected mode must survive startup.
  await cdp('Page.reload');
  await until(() => ev("!!document.querySelector('.home-grid') && !window.motionSettings"), 'reloaded home');
  await bindPage();
  assert.equal(await ev('motionSettings.settings.motion'), 'full');
  assert.equal(await ev('document.documentElement.dataset.motion'), 'full');
  report.checks.push({check:'native-motion-preference-and-explicit-full',...nativeMotion,fullDuration:1400,persistsAfterReload:true});
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  await sleep(100);
  assert.equal(await ev('document.documentElement.dataset.motion'), 'full', 'explicit full overrides system reduction');
  await click('#header .appearance-control .el-button'); await sleep(300);
  await click('.motion-options .el-radio-button:nth-child(3)');
  await click('.theme-options .el-radio-button:nth-child(1)');
  assert.equal(await ev('document.documentElement.dataset.theme'), 'light');
  assert.equal(await ev('motionAnimations.length'), 0);
  assert.equal(await ev("getComputedStyle(document.querySelector('#header')).transitionDuration"), '0s');
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }] });
  await sleep(100);
  assert.equal(await ev('document.documentElement.dataset.motion'), 'reduced', 'explicit reduction overrides system animations');
  await click('.motion-options .el-radio-button:nth-child(1)');
  assert.equal(await ev('document.documentElement.dataset.motion'), 'full');
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  await until(() => ev("document.documentElement.dataset.motion==='reduced'"), 'live system motion preference');
  await click('#header .appearance-control .el-button');
  report.checks.push({check:'motion-modes-and-live-system-changes',passed:true});
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  await ev("motionSettings.set('theme','light'); motionSettings.set('navigation','dynamic'); true");
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }] });
  await click('#header .appearance-control .el-button');
  await until(() => ev("[...document.querySelectorAll('.appearance-panel')].some(n=>n.getBoundingClientRect().height>0)"), 'appearance popover');
  await sleep(250);
  for (const [value, index] of [['dark', 2], ['light', 1]]) {
    const point = await click('.theme-options .el-radio-button:nth-child(' + index + ')');
    await until(() => ev(`motionAnimations.length>${value === 'dark' ? 0 : 1}`), 'parabolic reveal animation');
    await sleep(480);
    assert(await ev("document.documentElement.classList.contains('theme-revealing')"), 'old and new themes remain visible during the slow sweep');
    await shot('theme-reveal-to-' + value);
    const animation = await ev('motionAnimations[motionAnimations.length-1]');
    const origin = animation.frames[0].clipPath.match(/M ([\d.-]+) ([\d.-]+)/).slice(1).map(Number);
    assert(Math.abs(origin[0]-point.x)<1 && Math.abs(origin[1]-point.y)<1, 'reveal starts at clicked theme option');
    assert(animation.frames.every(frame=>frame.clipPath.includes(' Q ')), 'curved wave uses a parabolic boundary');
    assert(animation.options.duration>=1200, 'visible slow theme transition');
    assert.equal(animation.options.pseudoElement, '::view-transition-new(root)');
    await settled();
    assert.equal(await ev('document.documentElement.dataset.theme'), value);
    report.checks.push({ check: 'button-origin-parabolic-sweep', theme: value, point, duration: animation.options.duration });
  }
  await ev("Promise.all([motionSettings.set('theme','dark'),motionSettings.set('theme','light'),motionSettings.set('theme','dark')]).then(()=>true)");
  await settled();
  assert.equal(await ev('document.documentElement.dataset.theme'), 'dark');
  report.checks.push({ check: 'rapid-latest-theme-wins', passed: true });
  for (const fallback of ['reduced-motion', 'unsupported-browser', 'snapshot-error']) {
    const count = await ev('motionAnimations.length');
    if (fallback === 'reduced-motion') await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
    else await ev(`window.savedStartTransition=document.startViewTransition; document.startViewTransition=${fallback === 'snapshot-error' ? '()=>{throw Error("review unsupported snapshot")}' : 'undefined'}; true`);
    await ev("motionSettings.set('theme','light').then(()=>true)");
    await settled();
    assert.equal(await ev('document.documentElement.dataset.theme'), 'light');
    assert.equal(await ev('motionAnimations.length'), count);
    if (fallback === 'reduced-motion') await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }] });
    else await ev('document.startViewTransition=savedStartTransition; true');
    await ev("motionSettings.set('theme','dark').then(()=>true)");
    report.checks.push({ check: fallback + '-fallback', passed: true });
  }
  // Keyboard selection uses the option's centre rather than a fake mouse coordinate (0,0).
  await ev("document.querySelector('.appearance-panel .el-radio-button input[value=light]').focus(); true");
  await cdp('Input.dispatchKeyEvent', { type: 'keyDown', key: ' ', code: 'Space', windowsVirtualKeyCode: 32 });
  await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: ' ', code: 'Space', windowsVirtualKeyCode: 32 });
  await settled();
  assert.equal(await ev('document.documentElement.dataset.theme'), 'light');
  report.checks.push({ check: 'keyboard-theme-selection', passed: true });
  const beforeSystem = await ev('motionAnimations.length');
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'light' }] });
  await ev("motionSettings.set('theme','system').then(()=>true)");
  assert.equal(await ev('motionAnimations.length'), beforeSystem, 'same resolved colour needs no animation');
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'dark' }] });
  await until(() => ev("document.documentElement.dataset.theme==='dark'"), 'follow system theme');
  assert.equal(await ev('motionAnimations.length'), beforeSystem, 'automatic system changes remain immediate');
  await ev("motionSettings.set('theme','light').then(()=>true)");
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }] });
  report.checks.push({ check: 'same-resolved-theme-and-follow-system', passed: true });
  await cdp('Input.dispatchKeyEvent', {type:'keyDown',key:'Escape',windowsVirtualKeyCode:27});
  await sleep(300);
  await click('.collection-primary-link');
  await until(() => ev("location.pathname==='/problem'"), 'practice navigation');
  await sleep(300);
  await ev("document.getElementById('app').__vue__.$router.push('/home').then(()=>true)");
  await until(() => ev("!!document.querySelector('.home-grid')"), 'return home');
  await ev('document.activeElement.blur(); scrollTo(0,0); true');
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 900, y: 600 });
  const rect = () => ev("(() => { const h=document.querySelector('#header'),r=h.getBoundingClientRect(); return {width:r.width,height:r.height,condensed:h.classList.contains('nav-condensed'),scrolled:h.classList.contains('nav-scrolled'),glass:getComputedStyle(h,'::before').opacity}; })()");
  await sleep(850);
  const expanded = await rect();
  await ev('scrollTo(0,100); true'); await sleep(850);
  const condensed = await rect();
  assert(condensed.condensed && condensed.width<expanded.width && condensed.height<expanded.height);
  assert(condensed.width/expanded.width<.8, 'desktop shrink must be clearly visible');
  assert(condensed.height/expanded.height<.75, 'capsule becomes visibly shorter');
  await shot('nav-motion-condensed');
  // A temporary striped backing measures real backdrop blur, not just a CSS value.
  const glassSample = await ev(`(() => {
    const h=document.querySelector('#header'), r=h.getBoundingClientRect();
    const logo=h.querySelector('.logo').getBoundingClientRect();
    const first=h.querySelector('.nav-links > li').getBoundingClientRect();
    const probe=document.createElement('div'); probe.id='motion-glass-probe';
    probe.style.cssText='position:fixed;pointer-events:none;z-index:1999;left:'+r.x+'px;top:'+r.y+'px;width:'+r.width+'px;height:'+r.height+'px;background:repeating-linear-gradient(90deg,#000 0px,#000 4px,#fff 4px,#fff 8px)';
    document.body.appendChild(probe);
    return {x:Math.round((logo.right+first.left)/2-16),y:Math.round(r.y+r.height/2-8),width:32,height:16};
  })()`);
  await sleep(100);
  const blurred = (await cdp('Page.captureScreenshot', {format:'png'})).data;
  await ev(`(() => { const style=document.createElement('style'); style.id='motion-glass-clear'; style.textContent='#app #header::before{backdrop-filter:none!important;-webkit-backdrop-filter:none!important}'; document.head.appendChild(style); })()`);
  await sleep(100);
  const clear = (await cdp('Page.captureScreenshot', {format:'png'})).data;
  const glassContrast = await ev(`(async () => {
    async function deviation(data) {
      const image=new Image(); image.src='data:image/png;base64,'+data; await image.decode();
      const canvas=document.createElement('canvas'); canvas.width=32; canvas.height=16;
      const ctx=canvas.getContext('2d'); const box=${JSON.stringify(glassSample)};
      ctx.drawImage(image,box.x,box.y,box.width,box.height,0,0,32,16);
      const dataPixels=ctx.getImageData(0,0,32,16).data, samples=[];
      for(let i=0;i<dataPixels.length;i+=4) samples.push(dataPixels[i]);
      const mean=samples.reduce((a,b)=>a+b,0)/samples.length;
      return Math.sqrt(samples.reduce((sum,x)=>sum+(x-mean)**2,0)/samples.length);
    }
    return {blurred:await deviation(${JSON.stringify(blurred)}),clear:await deviation(${JSON.stringify(clear)})};
  })()`);
  await ev("document.getElementById('motion-glass-probe').remove(); document.getElementById('motion-glass-clear').remove(); true");
  assert(glassContrast.blurred < glassContrast.clear * .4, 'backdrop blur softens content behind transparent navigation');
  report.checks.push({check:'real-backdrop-blur',...glassContrast});
  for (const y of [50, 63, 40, 25]) { await ev(`scrollTo(0,${y}); true`); await sleep(80); assert((await rect()).scrolled); }
  await ev('scrollTo(0,20); true'); await sleep(500); assert(!(await rect()).scrolled);
  await ev('scrollTo(0,100); true'); await sleep(500);
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 720, y: 30 }); await sleep(750);
  assert(!(await rect()).condensed);
  await shot('nav-motion-expanded');
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 900, y: 600 });
  await sleep(70); assert(!(await rect()).condensed, 'leave grace period');
  await sleep(600); assert((await rect()).condensed);
  await ev("document.querySelector('#header .el-menu-item').focus(); true"); await sleep(500);
  assert(!(await rect()).condensed, 'keyboard focus keeps navigation open');
  await ev('document.activeElement.blur(); true');
  report.checks.push({ check: 'nav-scroll-hover-focus-and-hysteresis', expanded, condensed });
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  await until(() => ev("document.documentElement.dataset.motion==='reduced'"), 'reduced navigation');
  assert.equal(await ev("getComputedStyle(document.querySelector('#header')).transitionDuration"), '0s');
  await cdp('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }] });
  report.checks.push({ check: 'reduced-motion-nav', passed: true });
  for (const width of [1920, 1440, 1200, 1024, 992, 390]) {
    await cdp('Emulation.setDeviceMetricsOverride', { width, height: 1080, deviceScaleFactor: 1, mobile: width === 390 });
    await sleep(700);
    const layout = await ev("({overflow:document.documentElement.scrollWidth>innerWidth,mobile:!!document.querySelector('.mobile-nav'),items:[...document.querySelectorAll('#header .nav-links > li')].map(n=>({text:n.innerText.trim(),left:n.getBoundingClientRect().left,right:n.getBoundingClientRect().right}))})");
    assert(!layout.overflow, 'viewport overflow at '+width);
    if (width>=992) { assert.equal(layout.items.length,9); assert(layout.items.every(n=>n.left>=0&&n.right<=width)); }
    else assert(layout.mobile);
    report.checks.push({ check: 'responsive-nav', width, passed: true });
  }
  await cdp('Emulation.setDeviceMetricsOverride', { width: 992, height: 1080, deviceScaleFactor: 1, mobile: true });
  await cdp('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 1 });
  await sleep(500);
  assert(await ev("matchMedia('(pointer: coarse)').matches"));
  const coarse = await ev("({padding:getComputedStyle(document.querySelector('#header .el-menu-item')).paddingLeft,width:document.querySelector('#header').offsetWidth,expected:innerWidth-32})");
  assert.equal(coarse.padding, '7px'); assert.equal(coarse.width, coarse.expected);
  report.checks.push({ check: 'touch-landscape-keeps-compact-spacing', ...coarse });
  await cdp('Emulation.setTouchEmulationEnabled', { enabled: false });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await sleep(500);
  await ev('scrollTo(0,0); window.motionSettings=findMotionVm(v=>v.$options.props?.themeOnly); true');
  await click('.mobile-nav .appearance-control .el-button'); await sleep(300);
  const mobileOrigin = await click('.theme-options .el-radio-button:nth-child(2)');
  await settled();
  assert.equal(await ev('document.documentElement.dataset.theme'), 'dark');
  await shot('theme-mobile-dark');
  report.checks.push({ check: 'mobile-theme-button', point: mobileOrigin, passed: true });
  assert.equal(report.errors.length, 0);
  report.validated = true;
})().catch(error => { report.failure = error.message; process.exitCode = 1; }).finally(() => {
  clearTimeout(watchdog);
  if (ws) ws.close();
  browser.kill();
  fs.writeFileSync(path.join(output, 'motion-review.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
});
