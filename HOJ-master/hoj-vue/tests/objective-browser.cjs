// Isolated browser regression: all /api/* requests are fulfilled with fixtures.
// Start with CDUTETC_LOCAL_ASSETS=1, then set UI_REVIEW_URL to the dev server.
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const assert = require('assert').strict;
const base = process.env.UI_REVIEW_URL || 'http://127.0.0.1:8067';
const output = process.env.OBJECTIVE_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-objective-review');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'hoj-objective-browser-'));
fs.mkdirSync(output, { recursive: true });
const report = { checks: [], screenshots: [], errors: [], requests: [] };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const questions = [
  { id: 1, version: 1, title: '二分查找的复杂度', questionType: 'SINGLE_CHOICE', stem: '对有序数组进行二分查找，最坏时间复杂度是多少？', options: [{ id: 'A', text: 'O(log n)' }, { id: 'B', text: 'O(n)' }], correctAnswers: ['A'], explanation: '每次比较将候选区间缩小约一半。', tagIds: [] },
  { id: 2, version: 1, title: '稳定排序', questionType: 'MULTIPLE_CHOICE', stem: '选择以下稳定的排序算法。', options: [{ id: 'A', text: '归并排序' }, { id: 'B', text: '堆排序' }, { id: 'C', text: '插入排序' }], correctAnswers: ['A', 'C'], explanation: '标准归并排序与插入排序可以保持相等元素的原始顺序。', tagIds: [] },
  { id: 3, version: 1, title: '广度优先搜索', questionType: 'TRUE_FALSE', stem: '无权图的广度优先搜索可以求单源最短路。', options: [{ id: 'TRUE', text: '正确' }, { id: 'FALSE', text: '错误' }], correctAnswers: ['TRUE'], explanation: 'BFS 按距离逐层扩展。', tagIds: [] },
];
const sets = [
  { id: 1, title: '算法基础练习', description: '', mode: 'PRACTICE', questionIds: [1, 2, 3], questionCount: 3, version: 1, published: true, answerPolicy: 'AFTER_ANSWER', explanationPolicy: 'AFTER_ANSWER', answerReleaseAt: null, explanationReleaseAt: null },
  { id: 2, title: '计算机基础整卷自测', description: '', mode: 'PAPER', questionIds: [1, 2, 3], questionCount: 3, version: 1, published: true, answerPolicy: 'AFTER_FINISH', explanationPolicy: 'AFTER_FINISH', answerReleaseAt: null, explanationReleaseAt: null },
];
let uid = 'objective-alice', role = 'root', nextRun = 100, nextQuestion = 10, nextSet = 10;
const runs = new Map(), starts = new Map();
const user = () => ({ uid, username: uid, roleList: [role], avatar: '', solvedList: [], scoreList: [] });
const clone = value => JSON.parse(JSON.stringify(value));
function view(run) {
  const set = sets.find(set => set.id === run.setId);
  const finished = run.state === 'FINISHED';
  const rows = questions.map(question => {
    const selected = run.answers[question.id] || [];
    const submitted = finished || run.mode === 'PRACTICE' && selected.length > 0;
    const visible = submitted && (run.mode === 'PRACTICE' || finished);
    const { correctAnswers, explanation, ...publicQuestion } = clone(question);
    return { ...publicQuestion, selectedAnswers: selected, submitted, answerVisible: visible, explanationVisible: visible,
      ...(visible ? { correctAnswers, explanation, correct: JSON.stringify(selected) === JSON.stringify(correctAnswers), score: JSON.stringify(selected) === JSON.stringify(correctAnswers) ? 1 : 0 } : {}) };
  });
  const result = { id: run.id, setId: run.setId, setVersion: 1, mode: run.mode, title: run.title, state: run.state, questions: rows, answeredCount: Object.keys(run.answers).length, createdAt: run.createdAt, answerPolicy: set.answerPolicy, explanationPolicy: set.explanationPolicy };
  if (finished) Object.assign(result, { score: rows.filter(q => q.correct).length, maxScore: rows.length });
  return result;
}
function fixture(request) {
  const url = new URL(request.url), p = url.pathname, method = request.method;
  const data = request.postData ? JSON.parse(request.postData) : {};
  report.requests.push({ path: p, method, data });
  const ok = data => ({ status: 200, data, msg: 'success' });
  if (p.includes('website-config')) return ok({ name: 'HOJ', shortName: 'HOJ', openPublicDiscussion: true, openGroupDiscussion: true, openContestComment: true, description: '', recordName: '', recordUrl: '' });
  if (p === '/api/get-user-auth-info') return ok({ roles: [role] });
  if (p === '/api/get-user-home-info') return ok(user());
  if (p === '/api/msg/unread') return ok({ comment: 0, reply: 0, like: 0, sys: 0, mine: 0 });
  if (p === '/api/get-all-problem-tags') return ok([]);
  if (p === '/api/objective/sets') return ok({ records: clone(sets), total: sets.length });
  if (p === '/api/objective/runs' && method === 'POST') {
    let run = starts.get(uid + ':' + data.requestId);
    if (!run) {
      const set = sets.find(set => set.id === data.setId);
      run = { id: ++nextRun, uid, setId: set.id, title: set.title, mode: set.mode, state: 'IN_PROGRESS', createdAt: Date.now(), answers: {} };
      runs.set(run.id, run); starts.set(uid + ':' + data.requestId, run);
    }
    return ok(view(run));
  }
  if (p === '/api/objective/runs') {
    const own = [...runs.values()].filter(run => run.uid === uid).map(({ id, setId, title, mode, state, createdAt }) => ({ id, setId, title, mode, state, createdAt }));
    return ok({ records: own, total: own.length });
  }
  const match = p.match(/^\/api\/objective\/runs\/(\d+)(\/answers|\/finish)?$/);
  if (match) {
    const run = runs.get(Number(match[1]));
    if (!run || run.uid !== uid) return { status: 404, data: null, msg: '答题记录不存在' };
    if (match[2] === '/answers') {
      assert.deepEqual(Object.keys(data).sort(), ['questionId', 'selectedAnswers']);
      run.answers[data.questionId] = data.selectedAnswers.slice().sort();
    }
    if (match[2] === '/finish') run.state = 'FINISHED';
    return ok(view(run));
  }
  if (p === '/api/admin/objective/questions' && method === 'GET') return ok({ records: questions.map(({ id, title, questionType, version }) => ({ id, title, questionType, version, published: true })), total: questions.length });
  if (p === '/api/admin/objective/questions') {
    const question = { ...data, id: data.id || ++nextQuestion, version: (data.version || 0) + 1 };
    const index = questions.findIndex(q => q.id === question.id);
    if (index >= 0) questions[index] = question; else questions.push(question);
    return ok(question);
  }
  if (p.match(/^\/api\/admin\/objective\/questions\/\d+$/)) return ok({ ...clone(questions.find(q => q.id === Number(p.split('/').pop()))), published: true });
  if (p === '/api/admin/objective/sets' && method === 'GET') return ok({ records: clone(sets), total: sets.length });
  if (p === '/api/admin/objective/sets') { const set = { ...data, id: data.id || ++nextSet, version: (data.version || 0) + 1, questionCount: data.questionIds.length }; sets.push(set); return ok(set); }
  if (p.match(/^\/api\/admin\/objective\/sets\/\d+$/)) return ok(clone(sets.find(set => set.id === Number(p.split('/').pop()))));
  return ok([]);
}
let ws, stderr = '';
const browser = spawn(process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', ['--headless=new', '--disable-gpu', '--no-first-run', '--disable-extensions', '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
browser.stderr.on('data', data => { stderr = (stderr + data).slice(-2000); });
async function until(check, label, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await check()) return; await sleep(150); }
  throw Error('Timeout: ' + label);
}
(async () => {
  const portFile = path.join(profile, 'DevToolsActivePort');
  await until(() => { if (browser.exitCode !== null) throw Error(stderr); return fs.existsSync(portFile); }, 'Chrome startup', 15000);
  const port = fs.readFileSync(portFile, 'utf8').split('\n')[0];
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
  ws = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let seq = 0;
  const pending = new Map();
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++seq;
    const timer = setTimeout(() => { pending.delete(id); reject(Error('CDP timeout: ' + method)); }, 30000);
    pending.set(id, { resolve, reject, timer }); ws.send(JSON.stringify({ id, method, params }));
  });
  ws.onmessage = async event => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const call = pending.get(message.id); clearTimeout(call.timer); pending.delete(message.id);
      if (message.error) call.reject(Error(message.error.message)); else call.resolve(message.result);
    } else if (message.method === 'Runtime.exceptionThrown') report.errors.push(message.params.exceptionDetails.text);
    else if (message.method === 'Fetch.requestPaused') {
      try {
        const body = fixture(message.params.request);
        await cdp('Fetch.fulfillRequest', { requestId: message.params.requestId, responseCode: 200, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body: Buffer.from(JSON.stringify(body)).toString('base64') });
      } catch (error) { report.errors.push(error.message); await cdp('Fetch.failRequest', { requestId: message.params.requestId, errorReason: 'Failed' }); }
    }
  };
  const ev = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
  };
  const find = name => "(function find(vm){if(vm.$options.name===" + JSON.stringify(name) + ")return vm;for(const child of vm.$children||[]){const result=find(child);if(result)return result;}})(document.getElementById('app').__vue__)";
  const route = async location => { await ev("document.getElementById('app').__vue__.$router.push(" + JSON.stringify(location) + ").then(()=>true,()=>false)"); await sleep(400); };
  const shot = async name => {
    // Let user feedback expire and dialog transitions settle before layout QA.
    await sleep(4600);
    if (name === 'objective-admin-policies') await ev("document.querySelectorAll('.objective-editor-dialog .el-dialog__body').forEach(body=>{if(body.getBoundingClientRect().height)body.scrollTop=body.scrollHeight});true");
    const result = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
    const file = path.join(output, name + '.png'); fs.writeFileSync(file, Buffer.from(result.data, 'base64')); report.screenshots.push(file);
  };
  await cdp('Runtime.enable'); await cdp('Page.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.setItem('token','objective-fixture-token');localStorage.setItem('Web_Language','\"zh-CN\"');localStorage.setItem('userInfo'," + JSON.stringify(JSON.stringify(user())) + ");" });
  await cdp('Page.navigate', { url: base + '/objective' });
  await until(() => ev("!!document.querySelector('.objective-list .el-table__body tr')"), 'objective sets');
  await shot('objective-sets-desktop');
  await ev("document.querySelector('.objective-list .el-table__body tr button').click()");
  await until(() => ev("!!document.querySelector('.objective-answer .el-radio')"), 'practice answer');
  assert.equal(await ev("!!document.querySelector('.objective-feedback')"), false);
  await ev("document.querySelector('.objective-answer .el-radio').click()");
  await until(() => ev("!document.querySelector('.answer-footer button').disabled"), 'answer selection');
  await ev("document.querySelector('.answer-footer button').click()");
  await until(() => ev("document.querySelector('.objective-feedback')?.innerText.includes('回答正确')"), 'practice grading');
  assert.equal(await ev("document.querySelector('.answer-footer button').disabled"), true);
  await shot('objective-practice-result');
  report.checks.push('Practice submits selected IDs only, shows server results, and locks accepted answers.');

  await ev(find('ObjectiveRun') + ".selectQuestion(2)");
  await until(() => ev("document.querySelectorAll('.objective-answer .el-checkbox').length===3"), 'multiple choice');
  await ev("document.querySelectorAll('.objective-answer .el-checkbox')[0].click()");
  await until(() => ev(find('ObjectiveAnswer') + ".selected.includes('A')"), 'first multiple selection');
  await ev("document.querySelectorAll('.objective-answer .el-checkbox')[2].click()");
  await until(() => ev("!document.querySelector('.answer-footer button').disabled"), 'multiple selection');
  await ev("document.querySelector('.answer-footer button').click()");
  await until(() => ev("document.querySelector('.objective-feedback')?.innerText.includes('回答正确')"), 'multiple result');
  const multiple = report.requests.filter(r => r.path.endsWith('/answers')).pop().data;
  assert.deepEqual(multiple.selectedAnswers, ['A', 'C']);
  await ev(find('ObjectiveRun') + ".selectQuestion(3)");
  await until(() => ev("document.querySelector('.objective-answer')?.innerText.includes('判断题')"), 'true false');
  await ev("document.querySelector('.objective-answer .el-radio').click()");
  await until(() => ev("!document.querySelector('.answer-footer button').disabled"), 'boolean selection');
  await ev("document.querySelector('.answer-footer button').click()");
  await until(() => ev("document.querySelector('.objective-feedback')?.innerText.includes('回答正确')"), 'true false result');
  report.checks.push('Multiple choice and true/false use the same server-backed answer workflow.');

  await route('/objective');
  await until(() => ev("document.querySelectorAll('.objective-list .el-table__body tr').length===2"), 'return to sets');
  await ev("document.querySelectorAll('.objective-list .el-table__body tr')[1].querySelector('button').click()");
  await until(() => ev("document.querySelector('.run-summary')?.innerText.includes('整卷答题')"), 'paper');
  await ev("document.querySelector('.objective-answer .el-radio').click();document.querySelector('.answer-footer button').click()");
  await until(() => ev(find('ObjectiveRun') + ".run.answeredCount===1"), 'paper save');
  assert.equal(await ev("!!document.querySelector('.objective-feedback')"), false);
  assert.equal(await ev("document.querySelector('.answer-footer button').disabled"), false);
  await ev("document.querySelectorAll('.objective-answer .el-radio')[1].click();document.querySelector('.answer-footer button').click()");
  await until(() => ev("JSON.stringify(" + find('ObjectiveRun') + ".run.questions[0].selectedAnswers)==='[\"B\"]'"), 'paper edit');
  await shot('objective-paper-before-finish');
  await ev(find('ObjectiveRun') + ".finish();true");
  await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'finish confirmation');
  await ev("document.querySelector('.el-message-box__btns .el-button--primary').click()");
  await until(() => ev("document.querySelector('.run-summary')?.innerText.includes('已交卷')"), 'finished paper');
  assert.equal(await ev("document.querySelector('.answer-footer button').disabled"), true);
  assert.equal(await ev("document.querySelector('.objective-feedback')?.innerText.includes('回答错误')"), true);
  await ev(find('ObjectiveRun') + ".onlyWrong=true;" + find('ObjectiveRun') + ".selectFirst()");
  assert.equal(await ev(find('ObjectiveRun') + ".visibleQuestions.length"), 3);
  await shot('objective-paper-review');
  report.checks.push('Paper drafts stay editable and hide grading until explicit finish; finished answers and wrong-question review are available.');

  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await sleep(400);
  assert.equal(await ev("document.documentElement.scrollWidth>innerWidth"), false);
  await shot('objective-paper-mobile');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });

  const finishedRunId = await ev(find('ObjectiveRun') + '.run.id');
  await ev(find('ObjectiveRun') + '.restart();true');
  await until(() => ev(find('ObjectiveRun') + '.run?.id!==' + finishedRunId + '&&' + find('ObjectiveRun') + '.run?.state===\'IN_PROGRESS\''), 'restart paper');
  const pendingRunId = await ev(find('ObjectiveRun') + '.run.id');
  const finishCount = () => report.requests.filter(request => request.path.endsWith('/finish')).length;
  const beforeStaleFinish = finishCount();
  await ev('window.pendingFinish=' + find('ObjectiveRun') + '.finish();true');
  await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'pending finish confirmation');
  await ev("document.getElementById('app').__vue__.$router.back();true");
  await until(() => ev(find('ObjectiveRun') + '.run?.id===' + finishedRunId), 'back to previous paper during confirmation');
  await ev("document.querySelector('.el-message-box__btns .el-button--primary').click()");
  await ev('window.pendingFinish.then(()=>true)');
  assert.equal(finishCount(), beforeStaleFinish, 'stale confirmation must not submit either record');
  assert.equal(runs.get(pendingRunId).state, 'IN_PROGRESS');
  await ev("document.getElementById('app').__vue__.$router.forward();true");
  await until(() => ev(find('ObjectiveRun') + '.run?.id===' + pendingRunId), 'forward to pending paper');
  await ev('window.pendingFinish=' + find('ObjectiveRun') + '.finish();true');
  await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'cancel finish confirmation');
  await ev("document.querySelector('.el-message-box__btns .el-button:not(.el-button--primary)').click()");
  await ev('window.pendingFinish.then(()=>true)');
  assert.equal(finishCount(), beforeStaleFinish, 'cancel must not submit');
  await ev('window.pendingFinish=' + find('ObjectiveRun') + '.finish();true');
  await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'current finish confirmation');
  await ev("document.querySelector('.el-message-box__btns .el-button--primary').click()");
  await ev('window.pendingFinish.then(()=>true)');
  assert.equal(finishCount(), beforeStaleFinish + 1);
  assert.equal(report.requests.filter(request => request.path.endsWith('/finish')).pop().path, '/api/objective/runs/' + pendingRunId + '/finish');
  assert.equal(runs.get(pendingRunId).state, 'FINISHED');
  report.checks.push('Finish confirmation is invalidated by browser navigation; cancelling sends no request and confirming the current paper submits exactly once.');

  await route('/admin/objective');
  await until(() => ev("!!document.querySelector('.objective-admin .el-table__body tr')"), 'admin');
  await ev(find('ObjectiveAdmin') + ".newQuestion()");
  await until(() => ev("!!document.querySelector('.el-dialog__body textarea')"), 'question editor');
  await ev("Object.assign(" + find('ObjectiveAdmin') + ".question,{title:'新判断题',stem:'二分查找需要有序性。',questionType:'TRUE_FALSE',correctAnswers:['TRUE'],explanation:'本题考查前提条件。',published:true});" + find('ObjectiveAdmin') + ".saveQuestion()");
  await until(() => ev("!" + find('ObjectiveAdmin') + ".questionDialog"), 'save question');
  assert(report.requests.some(r => r.path === '/api/admin/objective/questions' && r.method === 'POST' && r.data.questionType === 'TRUE_FALSE'));
  await ev(find('ObjectiveAdmin') + ".tab='sets';" + find('ObjectiveAdmin') + ".reset();");
  await until(() => ev("!!document.querySelector('.objective-admin .el-table__body tr')"), 'admin sets');
  await ev(find('ObjectiveAdmin') + ".newSet();");
  const future = Date.now() + 3600000;
  await ev("Object.assign(" + find('ObjectiveAdmin') + ".problemSet,{title:'指定时间开放试卷',mode:'PAPER',questionIds:[1,2,3],answerPolicy:'AT_TIME',answerReleaseAt:" + future + ",explanationPolicy:'NEVER',published:true});true");
  await shot('objective-admin-policies');
  await ev(find('ObjectiveAdmin') + ".saveSet()");
  await until(() => ev("!" + find('ObjectiveAdmin') + ".setDialog"), 'save policies');
  const policy = report.requests.filter(r => r.path === '/api/admin/objective/sets' && r.method === 'POST').pop().data;
  assert.equal(policy.answerPolicy, 'AT_TIME'); assert.equal(policy.answerReleaseAt, future); assert.equal(policy.explanationPolicy, 'NEVER');
  report.checks.push('Admin creates questions and saves independent scheduled-answer / hidden-explanation policies.');

  await route('/admin/problems');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await until(() => ev("!!document.querySelector('.admin-mobile-nav')"), 'mobile admin navigation');
  await ev("document.querySelector('.admin-mobile-nav button').click()");
  await until(() => ev("!!document.querySelector('.mu-drawer')"), 'mobile admin drawer');
  await ev("[...document.querySelectorAll('.mu-drawer .mu-item-title')].find(item=>item.textContent.trim()==='题目管理').click()");
  await until(() => ev("!!document.querySelector('.mu-drawer a[href=\"/admin/objective\"]')?.getBoundingClientRect().height"), 'mobile objective menu entry');
  await shot('objective-admin-mobile-menu');
  await ev("document.querySelector('.mu-drawer a[href=\"/admin/objective\"]').click()");
  await until(() => ev("document.getElementById('app').__vue__.$route.path==='/admin/objective'&&!!document.querySelector('.objective-admin')"), 'mobile objective navigation');
  await until(() => ev("!document.querySelector('.mu-drawer')||getComputedStyle(document.querySelector('.mu-drawer')).visibility==='hidden'"), 'mobile drawer closes');
  assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'), false);
  report.checks.push('Mobile admin drawer exposes objective management, navigates to it, and closes after selection.');

  uid = 'objective-bob'; role = 'default_user';
  await ev("const store=document.getElementById('app').__vue__.$store;store.commit('changeUserInfo',{userInfo:" + JSON.stringify(user()) + "});true");
  await route('/objective');
  await until(() => ev("!!document.querySelector('.objective-list')"), 'new account');
  await ev(find('ObjectiveList') + ".tab='history';" + find('ObjectiveList') + ".reset();true");
  await until(() => ev("!" + find('ObjectiveList') + ".loading"), 'new history');
  assert.equal(await ev(find('ObjectiveList') + ".rows.length"), 0);
  report.checks.push('Switching accounts clears prior objective state and loads only the new account history.');
  assert.deepEqual(report.errors, []);
  report.validated = true;
  fs.writeFileSync(path.join(output, 'objective-browser.json'), JSON.stringify(report, null, 2));
  console.log('Objective browser: ' + report.checks.length + ' workflows passed; ' + report.screenshots.length + ' screenshots; no runtime exceptions.');
})().catch(error => {
  report.failure = error.stack; fs.writeFileSync(path.join(output, 'objective-browser.json'), JSON.stringify(report, null, 2)); console.error(error); process.exitCode = 1;
}).finally(() => { if (ws) ws.close(); browser.kill(); });
