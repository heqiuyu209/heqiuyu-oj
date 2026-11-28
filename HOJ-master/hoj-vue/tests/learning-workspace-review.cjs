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
  const element = selector => 'document.querySelector(' + JSON.stringify(panel + ' ' + selector) + ')';
  const notes = element('#pane-notes .CodeMirror') + '?.CodeMirror';
  const writeNote = value => ev(notes + '.setValue(' + JSON.stringify(value) + ')');
  const click = selector => ev(element(selector) + '.click()');
  const writeField = (selector, value) => ev('(() => { const input=' + element(selector) + '; input.value=' +
    JSON.stringify(value) + '; input.dispatchEvent(new Event("input", { bubbles: true })); })()');
  const todoTitles = () => ev('Array.from(document.querySelectorAll(' + JSON.stringify(panel + ' .todo-title') +
    '), title => title.textContent)');
  const time = () => ev(element('.pomodoro-time') + '.textContent.trim()');
  const timerAction = () => click('.pomodoro-primary');
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
    ['草稿板', '笔记', '待办', '番茄钟', '推荐']);
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
  assert.equal(await ev(notes + '.getValue()'), '原题的复盘');
  await writeNote('');
  await ev(notes + '.focus()');
  await cdp('Input.insertText', { text: '## 标题' });
  await until(() => ev(notes + '.getValue() === "## 标题"'), 'The Markdown editor did not accept typing');
  const delimiter = element('.learning-note-editor .cm-formatting-header');
  assert(await ev('getComputedStyle(' + delimiter + ').display !== "none"'), 'The edited line should display its syntax');
  await cdp('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', text: '\r', windowsVirtualKeyCode: 13 });
  await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  await until(() => ev(notes + '.getValue() === "## 标题\\n" && getComputedStyle(' + delimiter + ').display === "none"'),
    'Pressing Enter should reveal the formatted heading');
  assert(await ev('Array.from(document.querySelectorAll(".learning-note-editor .cm-header-2")).some(node => node.textContent === "标题" && node.offsetWidth > 0)'));
  await ev(notes + '.setCursor(0, 4)');
  await until(() => ev('getComputedStyle(' + delimiter + ').display !== "none"'), 'Returning to the heading should reveal its Markdown');

  await ev(`window.noteExports = [];
    window.showSaveFilePicker = async options => {
      const entry = { options, text: '', saved: false };
      window.noteExports.push(entry);
      return { name: options.suggestedName, createWritable: async () => ({
        write: async text => { entry.text = text; },
        close: async () => { entry.saved = true; },
      }) };
    };`);
  const dialog = '.problem-note-export-dialog';
  const exportNote = async index => {
    await ev('document.querySelector(' + JSON.stringify(dialog + ' .el-dialog__footer .el-button--primary') + ').click()');
    await until(() => ev('!!window.noteExports[' + index + ']?.saved'), 'The Markdown file was not saved');
    return ev('window.noteExports[' + index + ']');
  };
  await click('.learning-note-export > button');
  const onlyNotes = await exportNote(0);
  assert.equal(onlyNotes.options.suggestedName, 'P1000.md');
  assert.deepEqual(onlyNotes.options.types[0].accept, { 'text/markdown': ['.md'] });
  assert(onlyNotes.text.startsWith('# 二分查找：第一个不小于目标的数\n'));
  assert(onlyNotes.text.includes('- 题号：P1000\n'));
  assert(onlyNotes.text.includes('](<' + await ev('location.origin') + '/problem/P1000>)'));
  assert(onlyNotes.text.includes('## 笔记\n\n## 标题') && !onlyNotes.text.includes('## 题面') && !onlyNotes.text.includes('## 代码'));
  const code = 'int main() { /* ``` */ return 0; }';
  await ev("document.querySelector('[data-panel=editor] .CodeMirror').CodeMirror.setValue(" + JSON.stringify(code) + ')');
  await click('.learning-note-export > button');
  for (const section of ['statement', 'code', 'notes']) {
    await ev('document.querySelector(' + JSON.stringify(dialog + ' input[type=checkbox][value=' + section + ']') + ').click()');
  }
  const withStatementAndCode = await exportNote(1);
  assert(withStatementAndCode.text.includes('## 题面\n') && withStatementAndCode.text.includes('### 描述\n'));
  assert(withStatementAndCode.text.includes('## 代码\n\n````') && withStatementAndCode.text.includes(code));
  assert(!withStatementAndCode.text.includes('## 笔记'), 'Unchecked notes should not be exported');
  await writeNote('补充原题复盘');
  checks.push('Markdown headings preview in place on Enter, remain editable, and selected export sections are written as a local Markdown file.');
  await pick('todo');
  assert.deepEqual(await todoTitles(), ['旧计划'], 'The old plan should be available as a task');
  await writeField('.todo-add input', '做一道数组题');
  await click('.todo-add button');
  assert.deepEqual(await todoTitles(), ['旧计划', '做一道数组题']);
  await click('.todo-row:last-child button[aria-label="编辑待办"]');
  await writeField('.todo-edit textarea', '先检查两个边界样例');
  await click('.todo-edit button[type=submit]');
  await click('.todo-row:last-child input[type=checkbox]');
  assert(await ev(element('.todo-row:last-child input[type=checkbox]') + '.checked'));
  await click('.todo-row:first-child button[aria-label="删除待办"]');
  assert.deepEqual(await todoTitles(), ['先检查两个边界样例']);

  await pick('timer');
  assert.equal(await time(), '25:00');
  await ev('window.originalStudyClock=Date.now; window.studyClock=Date.now(); Date.now=()=>window.studyClock');
  try {
    await timerAction();
    await ev('window.studyClock += 60000');
    await until(async () => await time() === '24:00', 'The timer did not count down');
    await timerAction();
    await ev('window.studyClock += 120000');
    assert.equal(await time(), '24:00', 'A paused timer must keep its remaining time');
    await timerAction();
    await mode('简洁双栏');
    await ev('window.studyClock += 2000');
    await mode('学习模式');
    await pick('timer');
    assert.equal(await time(), '23:58', 'The timer should recover its deadline after remount');
    await ev('window.studyClock += 24 * 60000');
    await until(async () => await time() === '00:00', 'Expired focus time should reach zero');
    assert((await text()).includes('专注时间到了'), 'The timer should show its completion message');
    await timerAction();
    assert.equal(await time(), '05:00', 'The next round should be a five-minute break');
    await click('.pomodoro-actions button:last-child');
    assert.equal(await time(), '05:00');
  } finally {
    await ev('Date.now=window.originalStudyClock');
  }
  checks.push('The focus timer pauses, restores its deadline after remount and offers a break when it expires.');
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
  assert.equal(await ev(notes + '.getValue()'), '', 'Notes from the previous problem leaked into the next problem');
  await pick('todo');
  assert.deepEqual(await todoTitles(), ['先检查两个边界样例'], 'Practice problems should share the account task list');
  await pick('timer');
  assert.equal(await time(), '05:00', 'Problem navigation reset the timer mode');
  await route('/problem/P1000');
  await pick('notes');
  assert.equal(await ev(notes + '.getValue()'), '补充原题复盘');
  await pick('todo');
  assert.deepEqual(await todoTitles(), ['先检查两个边界样例'], 'A deleted legacy task must not be imported again');
  assert(await ev(element('.todo-row input[type=checkbox]') + '.checked'), 'The completion flag did not persist');
  checks.push('Recommendations navigate to the problem. Notes stay with their problem, and editing and checking tasks persist across problems.');

  await pick('recommend');
  await until(async () => (await text()).includes(candidateTitle), 'The original candidate list is missing');
  await signIn('workspace-bob');
  await until(async () => (await text()).includes(otherTitle), 'Recommendations did not refresh for the new account');
  assert(!(await text()).includes(candidateTitle), 'Previous account recommendation is still visible');
  await pick('todo');
  assert.deepEqual(await todoTitles(), [], 'Tasks from the other account are visible');
  await pick('timer');
  assert.equal(await time(), '25:00', 'The other account timer is visible');
  await signIn('workspace-alice');
  await route('/contest/1/problem/P1000/full-screen');
  await pick('notes');
  assert(!await ev('!!document.querySelector("' + panel + ' #tab-recommend")'), 'Contest problem shows the practice recommendation tab');
  assert(await ev('!!' + notes), 'Contest route should keep its local notes');
  await pick('todo');
  assert.deepEqual(await todoTitles(), [], 'Practice tasks appear in the contest list');
  assert(!requests.some(r => r.path === '/api/ai/recommendations/explain'), 'Opening the workspace must not request a model');
  checks.push('Account switches isolate timers and tasks. Contest workspaces have their own task list.');
  await ev(app + '.$store.commit("clearUserInfoAndToken")');
  await route('/problem/P1000');
  await mode('简洁双栏');
  return checks;
}

module.exports = { fixture, review };
