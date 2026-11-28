const assert = require('assert').strict;

const panel = '.workspace-card-body[data-panel=learning]';
const app = "document.getElementById('app').__vue__";
const candidateTitle = '画像推荐：前缀和练习';
const otherTitle = '新账号的训练推荐';

function fixture(request) {
  const path = new URL(request.url).pathname;
  const uid = path.match(/^\/api\/learning\/users\/([^/]+)\/evidence$/)?.[1];
  if (uid) {
    const axes = ['基础算法', '数据结构', '图论'].map((name, i) => ({
      code: String(i), name, solved: i + 1, attempted: i + 2,
      representativeRating: null, objectiveSolved: 0, objectiveAttempted: 0,
    }));
    const combined = { coverageAvailable: true, coverageOnly: true, axes,
      overall: { solved: 6, attempted: 9, objectiveSolved: 0, objectiveAttempted: 0,
        duplicatesRemoved: 0, unmappedSolved: 0 } };
    return { uid, combined, sources: [] };
  }
  if (path === '/api/ai/recommendations' && request.method === 'GET') {
    const auth = Object.entries(request.headers || {}).find(([key]) => key.toLowerCase() === 'authorization')?.[1];
    const title = String(auth).includes('workspace-bob-token') ? otherTitle : candidateTitle;
    return { filters: { source: 'HOJ' }, groups: { BASIC: '基础算法' }, fingerprint: 'fixture-plan',
      basis: { minRating: null, maxRating: null }, items: [{
        id: 'HOJ:1001', kind: 'PROGRAMMING', title, url: '/problem/CF-4A', displayId: 'CF-4A',
        rating: 800, groups: [{ code: 'BASIC', name: '基础算法' }], reasons: ['画像显示基础算法仍有练习空间'],
      }] };
  }
  if (path === '/api/learning/training-plans') {
    return request.method === 'POST' ? { id: 1, state: 'ACTIVE' } : { items: [], total: 0 };
  }
}

async function until(check, description) {
  for (let i = 0; i < 160; i++) {
    if (await check()) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.fail(description);
}

async function review({ ev, cdp, route, shot, requests }) {
  const checks = [];
  const pick = async name => {
    const selector = JSON.stringify(panel + ' #tab-' + name);
    await until(() => ev('!!document.querySelector(' + selector + ')'), 'Learning tool did not appear: ' + name);
    await ev('document.querySelector(' + selector + ').click()');
  };
  const field = name => 'document.querySelector(' + JSON.stringify(panel + ' #pane-' + name + ' textarea') + ')';
  const readField = name => ev(field(name) + '.value');
  const writeField = (name, value) => ev('(() => { const input=' + field(name) + '; input.value=' +
    JSON.stringify(value) + '; input.dispatchEvent(new Event("input", { bubbles: true })); })()');
  const text = () => ev('document.querySelector(' + JSON.stringify(panel) + ')?.innerText || ""');
  const mode = label => ev('(function(){ const button = Array.from(document.querySelectorAll(".workspace-actions button"))' +
    '.find(node => node.textContent.trim() === ' + JSON.stringify(label) + '); button.click(); })()');
  const countReads = () => requests.filter(r => r.path === '/api/ai/recommendations' || /^\/api\/learning\/users\/[^/]+\/evidence$/.test(r.path)).length;
  const signIn = actor => {
    const info = { uid: actor, username: actor, roleList: ['user'], avatar: '', gender: 'secrecy' };
    return ev(app + '.$store.commit("changeUserInfo", { userInfo: ' + JSON.stringify(info) + ' });' +
      app + '.$store.commit("changeUserToken", ' + JSON.stringify(actor + '-token') + '); true');
  };

  const initial = countReads();
  await mode('学习模式');
  const canvas = JSON.stringify(panel + ' canvas');
  await until(() => ev('!!document.querySelector(' + canvas + ')'), 'Scratchpad did not appear');
  const tabs = JSON.stringify(panel + ' .el-tabs__item');
  assert.deepEqual(await ev('Array.from(document.querySelectorAll(' + tabs + '), tab=>tab.textContent.trim())'),
    ['草稿板', '笔记', '计划', '推荐']);
  const brush = JSON.stringify(panel + ' .scratchpad-brush');
  const showsBrush = () => ev('!!document.querySelector(' + brush + ')');
  const clickTool = label => ev('Array.from(document.querySelectorAll(' + JSON.stringify(panel + ' .scratchpad-tool') +
    ')).find(button => button.textContent.trim() === ' + JSON.stringify(label) + ').click()');
  assert.equal(await showsBrush(), false, 'Brush settings should start collapsed');
  await clickTool('画笔');
  assert.equal(await showsBrush(), false, 'The first pen click should select the pen');
  await clickTool('画笔');
  assert.equal(await showsBrush(), true, 'The second pen click should expand brush settings');
  await ev('document.querySelector(' + JSON.stringify(panel + ' button[aria-label="红色"]') + ').click()');
  await ev('(() => { const range = document.querySelector(' + JSON.stringify(panel + ' input[aria-label="画笔粗细"]') +
    '); range.value="8"; range.dispatchEvent(new Event("input", { bubbles: true })); })()');
  await clickTool('画笔');
  assert.equal(await showsBrush(), false, 'Clicking the pen again should collapse its settings');
  await clickTool('画笔');
  await clickTool('橡皮');
  assert.equal(await showsBrush(), false, 'Selecting another tool should collapse brush settings');
  await clickTool('画笔');
  assert.equal(await showsBrush(), false, 'Selecting the pen again should start with settings collapsed');
  await ev('document.querySelector(' + canvas + ').scrollIntoView({block:"center",behavior:"instant"})');
  const point = await ev('(() => { const r=document.querySelector(' + canvas +
    ').getBoundingClientRect(); return { x: r.left+r.width/3, y: r.top+r.height/3 }; })()');
  const end = { x: point.x + 20, y: point.y + 15 };
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', button: 'none', buttons: 0, ...point });
  await cdp('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', buttons: 1, clickCount: 1, ...point });
  await cdp('Input.dispatchMouseEvent', { type: 'mouseMoved', button: 'left', buttons: 1, ...end });
  await cdp('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', buttons: 0, clickCount: 1, ...end });
  const hasInk = () => ev('(() => { const c=document.querySelector(' + canvas + ');' +
    'return !!c && c.getContext("2d").getImageData(0,0,c.width,c.height).data.some((v,i)=>i%4===3&&v>0); })()');
  await until(hasInk, 'Drawing did not appear on the canvas');
  await mode('简洁双栏');
  await mode('学习模式');
  await until(hasInk, 'Drawing did not restore after changing the layout');
  const strokeStyle = await ev('(() => { const { color, width } = document.querySelector(".problem-scratchpad").__vue__.items[0];' +
    'return { color, width }; })()');
  assert.deepEqual(strokeStyle, { color: '#cf414e', width: 8 });
  checks.push('The scratchpad expands brush settings on the second pen click and restores the original brush color and width.');
  await pick('recommend');
  assert((await text()).includes('登录查看推荐'), 'Guest should be offered login');
  assert.equal(countReads(), initial, 'Guest must not load account-specific data');
  await mode('简洁双栏');
  await signIn('workspace-alice');
  assert.equal(countReads(), initial, 'Closed learning card must not load profiles or recommendations');
  await ev('localStorage.setItem("cdutetc:notes:draft:workspace-alice:-:-:-:P1000:",' +
    'JSON.stringify({ notes:"原题的复盘", plan:"旧计划" }))');
  await mode('学习模式');
  await until(() => ev('!!document.querySelector(' + canvas + ')'), 'Signed-in scratchpad did not appear');
  assert(!(await hasInk()), 'The signed-in scratchpad showed a guest drawing');
  assert.equal(countReads(), initial, 'Opening the scratchpad must not fetch a profile or recommendations');

  await pick('notes');
  assert.equal(await readField('notes'), '原题的复盘');
  await pick('plan');
  assert.equal(await readField('plan'), '旧计划');
  await writeField('plan', '先检查两个边界样例');
  await pick('recommend');
  await until(async () => (await text()).includes(candidateTitle), 'The workspace did not show personalized exercises');
  assert((await text()).includes('基础算法仍有练习空间'));
  assert(!await ev('document.querySelector("' + panel + ' .learning-recommendations details").open'),
    'The narrow recommendation card should show candidates before expanded filters');
  await ev('document.querySelector("' + panel + ' .recommendation-filter-summary").click()');
  assert(await ev('document.querySelector("' + panel + ' .learning-recommendations details").open'));
  await ev('document.querySelector("' + panel + ' .recommendation-filter-summary").click()');
  for (const width of [1440, 992, 390, 320]) {
    await cdp('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
    const fits = await ev('(async function(){ const p=document.querySelector("' + panel + '");' +
      'p.scrollIntoView({block:"start"}); window.scrollBy(0,-75); await new Promise(requestAnimationFrame); return p.scrollWidth<=p.clientWidth+1; })()');
    assert(fits, 'Recommendation side card overflows at viewport width ' + width);
    if (width >= 768) {
      const aligned = await ev(`(() => {
        const cards = Array.from(document.querySelectorAll('.workspace-grid .workspace-card'), card => card.getBoundingClientRect());
        return cards.length === 3 && cards.every(card => Math.abs(card.top - cards[0].top) <= 1 && Math.abs(card.bottom - cards[0].bottom) <= 1);
      })()`);
      assert(aligned, 'Learning mode cards are not aligned at viewport width ' + width);
    }
    if (width === 1440 || width === 390) await shot('learning-workspace-' + width);
  }
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1080, deviceScaleFactor: 1, mobile: false });
  await ev('document.querySelector("' + panel + ' .recommendation-save button").click()');
  await until(() => requests.some(r => r.path === '/api/learning/training-plans' && r.method === 'POST'), 'Saving the candidate plan did not reach the normal endpoint');
  const save = requests.find(r => r.path === '/api/learning/training-plans' && r.method === 'POST');
  assert.deepEqual(JSON.parse(save.data).selection.candidateIds, ['HOJ:1001']);
  checks.push('The same candidate list, reasons and save action work at all tested widths.');

  await ev('document.querySelector("' + panel + ' .recommendation-title a").click()');
  await until(() => ev("location.pathname === '/problem/CF-4A'"), 'Clicking the recommendation did not open the problem');
  await pick('notes');
  assert.equal(await readField('notes'), '', 'Notes from the previous problem leaked into the next problem');
  await pick('plan');
  assert.equal(await readField('plan'), '', 'Plan from the previous problem leaked into the next problem');
  await route('/problem/P1000');
  await pick('notes');
  assert.equal(await readField('notes'), '原题的复盘');
  await pick('plan');
  assert.equal(await readField('plan'), '先检查两个边界样例');
  checks.push('Recommendations navigate to the expected problem. The separate notes and plan tabs preserve existing data and isolate it by problem.');

  await pick('recommend');
  await until(async () => (await text()).includes(candidateTitle), 'The original candidate list is missing');
  await signIn('workspace-bob');
  await until(async () => (await text()).includes(otherTitle), 'Recommendations did not refresh for the new account');
  assert(!(await text()).includes(candidateTitle), 'Previous account recommendation is still visible');
  await route('/contest/1/problem/P1000/full-screen');
  await pick('notes');
  assert(!await ev('!!document.querySelector("' + panel + ' #tab-recommend")'), 'Contest problem shows the practice recommendation tab');
  assert(await ev('!!' + field('notes')), 'Contest route should keep its local notes');
  assert(!requests.some(r => r.path === '/api/ai/recommendations/explain'), 'Opening the workspace must not request a model');
  checks.push('Account changes reload the plan and contest problems keep notes without exposing the practice recommendation.');
  await ev(app + '.$store.commit("clearUserInfoAndToken")');
  await route('/problem/P1000');
  await mode('简洁双栏');
  return checks;
}

module.exports = { fixture, review };
