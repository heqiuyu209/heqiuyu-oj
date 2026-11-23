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
  const pick = name => ev('document.querySelector(' + JSON.stringify(panel + ' #tab-' + name) + ').click()');
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
  await pick('recommend');
  assert((await text()).includes('登录查看推荐'), 'Guest should be offered login');
  assert.equal(countReads(), initial, 'Guest must not load account-specific data');
  await mode('简洁双栏');
  await signIn('workspace-alice');
  assert.equal(countReads(), initial, 'Closed learning card must not load profiles or recommendations');
  await mode('学习模式');
  await until(() => ev('!!document.querySelector("' + panel + ' .ability-content svg")'), 'Ability chart did not render in the workspace');
  assert(!requests.some(r => r.path === '/api/ai/recommendations'), 'Recommendations must load only after opening their tab');
  checks.push('Guest, closed card and unopened recommendations make no account-specific request beyond the visible ability tab.');

  await pick('notes');
  await ev('(function(){ const input = document.querySelector("' + panel +
    ' textarea"); input.value="原题的复盘"; input.dispatchEvent(new Event("input", { bubbles: true })); })()');
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
  assert.equal(await ev('document.querySelector("' + panel + ' textarea").value'), '', 'Notes from the previous problem leaked into the next problem');
  await route('/problem/P1000');
  await pick('notes');
  assert.equal(await ev('document.querySelector("' + panel + ' textarea").value'), '原题的复盘');
  checks.push('A recommendation opens the expected problem and notes remain isolated by problem.');

  await pick('recommend');
  await until(async () => (await text()).includes(candidateTitle), 'The original candidate list is missing');
  await signIn('workspace-bob');
  await until(async () => (await text()).includes(otherTitle), 'Recommendations did not refresh for the new account');
  assert(!(await text()).includes(candidateTitle), 'Previous account recommendation is still visible');
  await route('/contest/1/problem/P1000/full-screen');
  assert(!await ev('!!document.querySelector("' + panel + ' #tab-recommend")'), 'Contest problem shows the practice recommendation tab');
  await pick('notes');
  assert(await ev('!!document.querySelector("' + panel + ' textarea")'), 'Contest route should keep its local notes');
  assert(!requests.some(r => r.path === '/api/ai/recommendations/explain'), 'Opening the workspace must not request a model');
  checks.push('Account changes reload the plan and contest problems keep notes without exposing the practice recommendation.');
  await ev(app + '.$store.commit("clearUserInfoAndToken")');
  await route('/problem/P1000');
  await mode('简洁双栏');
  return checks;
}

module.exports = { fixture, review };
