// Isolated browser regression: all /api/* requests are fulfilled with fixtures.
// Start with CDUTETC_LOCAL_ASSETS=1, then set UI_REVIEW_URL to the dev server.
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const assert = require('assert').strict;
const base = process.env.UI_REVIEW_URL || 'http://127.0.0.1:8067';
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-paper-review');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'hoj-paper-browser-'));
fs.mkdirSync(output, { recursive: true });
const report = { checks: [], screenshots: [], errors: [], requests: [] };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const clone = value => JSON.parse(JSON.stringify(value));
let uid = 'paper-alice', role = 'root', nextRun = 100, nextSubmission = 10;
const user = () => ({ uid, username: uid, roleList: [role], avatar: '', solvedList: [], scoreList: [] });
const blank = { id: 4, title: '结果填空：路径与计数', source: '蓝桥杯 · 练习样卷', version: 1, questionType: 'FILL_BLANK', stem: '第一个空填写路径长度，第二个空填写计数结果。', blanks: [{ id: 'a', label: '路径长度', score: 3, answers: ['42', '0x2a'], trim: true, caseSensitive: false }, { id: 'b', label: '计数结果', score: 7, answers: ['8'], trim: true, caseSensitive: true }], options: [], correctAnswers: [], explanation: '逐空计算，每个结果独立计分。', published: true, practiceEnabled: true };
const choice = { id: 1, title: '二分查找', questionType: 'SINGLE_CHOICE', stem: '二分查找的最坏时间复杂度？', options: [{ id: 'A', text: 'O(log n)' }, { id: 'B', text: 'O(n)' }], correctAnswers: ['A'], blanks: [], published: true };
const multiple = { ...choice, id: 5, questionType: 'MULTIPLE_CHOICE', correctAnswers: ['A', 'B'] };
const judgment = { ...choice, id: 6, questionType: 'TRUE_FALSE', options: [{ id: 'TRUE', text: '正确' }, { id: 'FALSE', text: '错误' }], correctAnswers: ['TRUE'] };
const program = { id: 1, title: 'A+B', problemId: 'P1000', source: '蓝桥杯 · 编程练习', stem: '<p>读入两个整数，输出它们的和。</p>', input: '<p>一行两个整数。</p>', output: '<p>输出它们的和。</p>', examples: '<input>1 2</input><output>3</output>', timeLimit: 1000, memoryLimit: 256, languages: ['C++', 'Python3'], questionType: 'PROGRAMMING' };
const papers = [
  { id: 1, title: '蓝桥杯混合样卷', description: '填空与编程，在同一份试卷中完成。', version: 1, items: [{ kind: 'OBJECTIVE', referenceId: 4, score: 10 }, { kind: 'PROGRAMMING', referenceId: 1, score: 20 }, { kind: 'OBJECTIVE', referenceId: 1, score: 5 }], questionCount: 3, programMode: 'AFTER_FINISH', scoreRule: 'BEST', published: true, answerPolicy: 'AFTER_FINISH', explanationPolicy: 'AFTER_FINISH' },
  { id: 2, title: '编程即时练习卷', description: '即时判题与选择题。', version: 1, items: [{ kind: 'PROGRAMMING', referenceId: 1, score: 20 }, { kind: 'OBJECTIVE', referenceId: 1, score: 5 }], questionCount: 2, programMode: 'LIVE', scoreRule: 'BEST', published: true, answerPolicy: 'AFTER_FINISH', explanationPolicy: 'AFTER_FINISH' },
];
const runs = new Map(), starts = new Map();
let contestEnd = Date.now() + 3600000;
function contestView(run) {
  if (Date.now() >= contestEnd) run.state = 'FINISHED';
  const result = view(run);
  result.contestId = 6; result.contestType = 0; result.endTime = contestEnd; result.serverNow = Date.now();
  result.answerPolicy = 'AFTER_END'; result.explanationPolicy = 'AFTER_END';
  if (Date.now() < contestEnd) result.items.filter(q => q.kind === 'OBJECTIVE').forEach(q => { q.answerVisible = false; q.explanationVisible = false; delete q.correctAnswers; delete q.correct; delete q.explanation; });
  return result;
}
function view(run) {
  const finished = run.state === 'FINISHED';
  const rows = run.paper.items.map(item => {
    const key = item.kind + ':' + item.referenceId, saved = run.answers[key] || {};
    if (item.kind === 'PROGRAMMING') {
      const live = run.paper.programMode === 'LIVE'; const results = run.submissions || [];
      const grading = results.some(result => result.status === 5);
      return { ...clone(program), kind: item.kind, referenceId: item.referenceId, key, code: saved.code || '', language: saved.language || null, maxScore: item.score, grading, submissionCount: results.length, answerVisible: live || finished, explanationVisible: finished, ...(finished ? { explanation: '<p>将 a 与 b 相加。</p>' } : {}), ...((live || finished) ? { submissions: clone(results), ...(!grading ? { score: results.some(result => result.status === 0) ? item.score : 0 } : {}) } : {}) };
    }
    const q = [choice, blank, multiple, judgment].find(q => q.id === item.referenceId), selectedAnswers = saved.selectedAnswers || [], { correctAnswers, explanation, blanks, ...publicQuestion } = clone(q);
    const score = q.id === 4 ? (['42', '0x2a'].includes((selectedAnswers[0] || '').trim().toLowerCase()) ? 3 : 0) + (selectedAnswers[1] === '8' ? 7 : 0) : JSON.stringify(selectedAnswers.slice().sort()) === JSON.stringify(q.correctAnswers.slice().sort()) ? item.score : 0;
    return { ...publicQuestion, kind: item.kind, referenceId: item.referenceId, key, maxScore: item.score, blanks: blanks.map(({id, label, score}) => ({id, label, score})), selectedAnswers: clone(selectedAnswers), submitted: finished, answerVisible: finished, explanationVisible: finished, ...(finished ? { score, correct: score === item.score, correctAnswers: q.id === 4 ? ['42 / 0x2a', '8'] : correctAnswers, explanation } : {}) };
  });
  const gradingComplete = !rows.some(q => q.grading);
  return { id: run.id, paperId: run.paper.id, paperVersion: 1, title: run.paper.title, programMode: run.paper.programMode, scoreRule: run.paper.scoreRule, state: run.state, items: rows, maxScore: rows.reduce((s,q) => s + q.maxScore, 0), answeredCount: rows.filter(q => (q.selectedAnswers || []).some(Boolean) || q.code).length, gradingComplete, answerPolicy: 'AFTER_FINISH', explanationPolicy: 'AFTER_FINISH', ...(finished && gradingComplete ? { score: rows.reduce((s,q) => s + q.score, 0) } : {}) };
}
function fixture(request) {
  const url = new URL(request.url), p = url.pathname, method = request.method;
  const data = request.postData ? JSON.parse(request.postData) : {};
  report.requests.push({ path: p, method, data, query: Object.fromEntries(url.searchParams) }); const ok = data => ({ status: 200, data, msg: 'success' });
  if (p === '/api/get-problem-tags-and-classification') return ok([]);
  if (p === '/api/get-user-problem-status') return ok(Object.fromEntries(data.pidList.map(pid => [pid, { status: -10 }])));
  if (p === '/api/get-problem-list') {
    const rows = [{ pid: 901, problemId: 'R901', title: '评级练习', rating: 1200 }, { pid: 902, problemId: 'R902', title: '进阶练习', rating: 1800 }, { pid: 903, problemId: 'R903', title: '未评级练习', rating: null }].map(row => ({ ...row, difficulty: 0, type: 0, tags: [], total: 0, ac: 0 }));
    const records = rows.filter(row => (!url.searchParams.has('ratingMin') || row.rating !== null && row.rating >= Number(url.searchParams.get('ratingMin'))) && (!url.searchParams.has('ratingMax') || row.rating !== null && row.rating <= Number(url.searchParams.get('ratingMax'))));
    return ok({ records, total: records.length });
  }
  if (p === '/api/rating/rank') {
    const type = url.searchParams.get('type'), keyword = url.searchParams.get('searchUser') || '';
    const rows = Array.from({ length: type === 'contest' ? 2 : 31 }, (_, index) => ({ uid: 'rating-' + index, username: 'student' + index, nickname: '', avatar: '', rank: index + 1, rating: (type === 'contest' ? 1600 : 1200) - index, peak: 1700, delta: index ? -12 : 25, ratedCount: type === 'contest' ? 3 : 8 })).filter(row => row.username.includes(keyword));
    const page = Number(url.searchParams.get('currentPage') || 1), limit = Number(url.searchParams.get('limit') || 30);
    return ok({ records: rows.slice((page - 1) * limit, page * limit), total: rows.length, updatedAt: Date.now() });
  }
  if (p.startsWith('/api/rating/users/')) return ok({ practiceRating: 1200, contestRating: 1600, history: ['practice', 'contest'].filter(type => !url.searchParams.has('type') || type === url.searchParams.get('type')).map(type => ({ type, title: type === 'practice' ? '二分查找练习' : '正式计分比赛', occurredAt: Date.now(), oldRating: 1375, rating: 1400, delta: 25 })) });
  if (p === '/api/get-contest-info') return ok({ id: 6, mixedPaperId: 2, title: '蓝桥杯混合赛', author: 'teacher', type: 0, auth: 0, status: 0, startTime: new Date(Date.now() - 60000).toISOString(), endTime: new Date(contestEnd).toISOString(), now: new Date().toISOString(), sealRank: false, duration: 3660, rankShowName: 'username' });
  if (p === '/api/contests/6/mixed/run') {
    let run = [...runs.values()].find(r => r.contestId === 6 && r.uid === uid);
    if (!run) {
      const paper = clone(papers[1]);
      paper.items.push(...[4, 5, 6].map(referenceId => ({ kind: 'OBJECTIVE', referenceId, score: referenceId === 4 ? 10 : 5 })));
      run = { id: ++nextRun, contestId: 6, uid, paper, answers: {}, submissions: [], state: 'IN_PROGRESS', polls: 0 }; runs.set(run.id, run);
    }
    return ok(contestView(run));
  }
  if (p === '/api/contests/6/mixed/rank') return ok({ type: 0, sealed: false, records: [{ rank: 1, name: '参赛者 A', objectiveScore: 10, ac: 1, penalty: 1260, finished: true }, { rank: 2, name: '参赛者 B', objectiveScore: 0, ac: 2, penalty: 300, finished: true }] });
  if (p.includes('website-config')) return ok({ name: 'HOJ', shortName: 'HOJ', openPublicDiscussion: true, openGroupDiscussion: true, openContestComment: true });
  if (p === '/api/get-user-auth-info') return ok({ roles: [role] });
  if (p === '/api/get-user-home-info') return ok(user());
  if (p === '/api/msg/unread') return ok({ comment: 0, reply: 0, like: 0, sys: 0, mine: 0 });
  if (p === '/api/get-all-languages') return ok([{ name: 'C++', contentType: 'text/x-c++src' }, { name: 'Python3', contentType: 'text/x-python' }]);
  if (p === '/api/papers') return ok({ records: clone(papers), total: papers.length });
  if (p === '/api/papers/runs' && method === 'POST') {
    let run = starts.get(uid + data.requestId); if (!run) { run = { id: ++nextRun, uid, paper: clone(papers.find(paper => paper.id === data.paperId)), answers: {}, submissions: [], state: 'IN_PROGRESS', polls: 0 }; runs.set(run.id, run); starts.set(uid + data.requestId, run); } return ok(view(run));
  }
  if (p === '/api/papers/runs') { const own = [...runs.values()].filter(r => r.uid === uid).map(r => ({ id: r.id, title: r.paper.title, state: r.state, createdAt: Date.now() })); return ok({ records: own, total: own.length }); }
  const match = p.match(/^\/api\/papers\/runs\/(\d+)(\/answers|\/program-submissions|\/finish)?$/);
  if (match) {
    const run = runs.get(Number(match[1])); if (!run || run.uid !== uid) return { status: 404, data: null, msg: '答卷不存在' };
    if (match[2] === '/answers') { assert.equal(run.state, 'IN_PROGRESS'); run.answers[data.key] = clone(data); }
    if (match[2] === '/program-submissions') { assert.equal(run.paper.programMode, 'LIVE'); run.answers[data.key] = clone(data); run.submissions.push({ id: ++nextSubmission, status: 5 }); run.polls = 0; }
    if (match[2] === '/finish') { if (run.state !== 'FINISHED' && run.paper.programMode === 'AFTER_FINISH' && Object.values(run.answers).some(a => a.code)) run.submissions.push({ id: ++nextSubmission, status: 5 }); run.state = 'FINISHED'; }
    if (!match[2] && ++run.polls >= 2) run.submissions.forEach(result => { result.status = 0; result.score = 20; });
    return ok(run.contestId ? contestView(run) : view(run));
  }
  if (p === '/api/admin/papers/program-questions') return ok([clone(program)]);
  if (p === '/api/admin/papers/collections') return ok({ records: [...clone(papers).map(paper => ({ ...paper, source: 'PAPER', mode: 'PAPER' })), { id: 3, title: '旧版逐题训练集', source: 'OBJECTIVE', mode: 'PRACTICE', questionCount: 1, published: false }], total: papers.length + 1 });
  if (p === '/api/languages') return ok([{ id: 1, name: 'C++', contentType: 'text/x-c++src', oj: 'ME', isSpj: false }]);
  if (p === '/api/admin/problem' && method === 'POST') return ok({ ...data.problem, id: 20 });
  if (p === '/api/admin/problem/get-problem-list') return ok({ records: [], total: 0 });
  if (p === '/api/admin/papers' && method === 'GET') return ok({ records: clone(papers), total: papers.length });
  if (p === '/api/admin/papers') { assert(data.items.some(item => item.kind === 'PROGRAMMING')); assert(data.items.some(item => item.kind === 'OBJECTIVE')); const paper = { ...data, id: data.id || 9, version: (data.version || 0) + 1, questionCount: data.items.length }; papers.push(paper); return ok(paper); }
  if (p.match(/^\/api\/admin\/papers\/\d+$/)) return ok(clone(papers.find(paper => paper.id === Number(p.split('/').pop()))));
  if (p === '/api/admin/objective/questions' && method === 'GET') return ok({ records: [clone(blank), clone(choice)], total: 2 });
  if (p === '/api/admin/objective/questions') return ok({ ...data, id: 10, version: 1 });
  if (p.match(/^\/api\/admin\/objective\/questions\/\d+$/)) return ok(clone(Number(p.split('/').pop()) === 4 ? blank : choice));
  if (p === '/api/admin/objective/sets/3') return ok({ id: 3, title: '旧版逐题训练集', mode: 'PRACTICE', questionIds: [1], version: 1, published: false, answerPolicy: 'AFTER_ANSWER', explanationPolicy: 'NEVER' });
  if (p === '/api/admin/objective/sets' && method === 'POST') return ok({ ...data, id: data.id || 11, version: 1 });
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
    } else if (message.method === 'Runtime.exceptionThrown') report.errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text);
    else if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') { report.errors.push(message.params.args.map(a => a.description || a.value).join(' ')); }
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
  const shot = async (name, viewport = false) => {
    // Let user feedback expire and dialog transitions settle before layout QA.
    await sleep(4600);
    if (name === 'objective-admin-policies') await ev("document.querySelectorAll('.objective-editor-dialog .el-dialog__body').forEach(body=>{if(body.getBoundingClientRect().height)body.scrollTop=body.scrollHeight});true");
    const result = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: !viewport });
    const file = path.join(output, name + '.png'); fs.writeFileSync(file, Buffer.from(result.data, 'base64')); report.screenshots.push(file);
  };
  const checkObjectivePolling = async () => {
    const cases = [
      { key: 'OBJECTIVE:1', selected: ['A'], selector: '.el-radio' },
      { key: 'OBJECTIVE:5', selected: ['A', 'B'], selector: '.el-checkbox' },
      { key: 'OBJECTIVE:6', selected: ['TRUE'], selector: '.el-radio' },
      { key: 'OBJECTIVE:4', selected: ['42', '8'] },
    ];
    for (const test of cases) {
      await ev(find('PaperRun') + '.select(' + JSON.stringify(test.key) + ');true');
      await until(() => ev(find('PaperRun') + '.selectedKey===' + JSON.stringify(test.key)), 'select ' + test.key);
      if (test.selector) {
        for (let index = 0; index < test.selected.length; index++) await ev("document.querySelectorAll('.objective-answer " + test.selector + "')[" + index + "].click();true");
      }
      else await ev("document.querySelectorAll('.blank-answer input').forEach((n,i)=>{n.value=" + JSON.stringify(test.selected) + "[i];n.dispatchEvent(new Event('input',{bubbles:true}))});true");
      assert.deepEqual(await ev(find('ObjectiveAnswer') + '.selected'), test.selected);
      const started = Date.now();
      await until(() => ev(find('PaperRun') + '.run.serverNow>' + (started + 3500)), 'two result polls for ' + test.key, 12000);
      assert.deepEqual(await ev(find('ObjectiveAnswer') + '.selected'), test.selected, test.key + ' must keep unsaved answers across clock ticks and result polls');
      assert.equal(await ev(find('ObjectiveAnswer') + '.dirty'), true);
      if (test.selector) assert.equal(await ev("document.querySelectorAll('.objective-answer " + test.selector + ".is-checked').length"), test.selected.length);
      await ev(find('PaperRun') + ".select('PROGRAMMING:1');true");
      await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'unsaved answer guard');
      await ev("document.querySelector('.el-message-box__btns .el-button:not(.el-button--primary)').click();true");
      assert.equal(await ev(find('PaperRun') + '.selectedKey'), test.key);
      await ev("document.querySelector('.answer-footer button').click();true");
      await until(() => ev('!' + find('PaperRun') + '.saving&&!'+find('ObjectiveAnswer')+'.dirty'), 'save ' + test.key);
      const runId = await ev(find('PaperRun') + '.run.id');
      assert.deepEqual(runs.get(runId).answers[test.key].selectedAnswers, test.selected);
    }
    // An intentional refresh still discards edits after the user confirms it.
    await ev("const n=document.querySelector('.blank-answer input');n.value='99';n.dispatchEvent(new Event('input',{bubbles:true}));"+find('PaperRun')+'.refresh();true');
    await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'refresh discard guard');
    await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");
    await until(() => ev('!' + find('PaperRun') + '.loading&&!'+find('ObjectiveAnswer')+'.dirty'), 'restore saved answers on refresh');
    assert.deepEqual(await ev(find('ObjectiveAnswer') + '.selected'), ['42', '8']);
    await ev(find('PaperRun') + ".select('OBJECTIVE:1');true");
    await until(() => ev(find('ObjectiveAnswer') + '.single===\'A\''), 'restore saved choice after switching');
    report.checks.push('Unsaved single/multiple/true-false/fill answers survive countdown ticks and two result polls; guards, save, switching and confirmed refresh retain their expected behavior.');
  };
  await cdp('Runtime.enable'); await cdp('Page.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.setItem('token','objective-fixture-token');localStorage.setItem('Web_Language','\"zh-CN\"');localStorage.setItem('userInfo'," + JSON.stringify(JSON.stringify(user())) + ");" });
  await cdp('Page.navigate', { url: base + '/papers' });
  await until(() => ev("document.querySelectorAll('.paper-card').length===2"), 'paper list');
  if (process.env.PAPER_REVIEW_CASE === 'authoring') {
    await route('/admin/problems');
    await until(() => ev(find('QuestionBank') + '&&!'+find('ProblemList')+'.loading'), 'unified question bank');
    await ev(find('QuestionBank')+'.$refs.creator.open();true');
    await until(() => ev("document.querySelectorAll('.type-grid button').length===5"), 'question type picker');
    await shot('authoring-question-types');
    await ev(find('QuestionBank')+".$refs.creator.select('TRUE_FALSE');true");
    const objective = find('QuestionBank') + '.$refs.creator.$refs.objective';
    assert.equal(await ev(objective+'.standalone'), false);
    await ev(objective+".question.title='保密判断题';"+objective+".question.stem='二分查找要求序列有序。';"+objective+".question.correctAnswers=['TRUE'];"+objective+'.preview=true;true');
    await shot('authoring-question-preview');
    await ev(objective+'.saveQuestion();true');
    await until(() => ev(find('QuestionBank')+".tab==='objective'"), 'created question in objective bank');
    const savedQuestion = report.requests.filter(r=>r.path==='/api/admin/objective/questions'&&r.method==='POST').pop().data;
    assert.equal(savedQuestion.published, false); assert.equal(savedQuestion.practiceEnabled, false);
    report.checks.push('Unified bank offers five types; new objective questions default to confidential, preview safely, and return to the correct bank.');
    await route('/admin/papers');
    await until(() => ev(find('CollectionAdmin')+'.rows.length===3'), 'combined collection catalogue');
    await shot('authoring-collections');
    await ev(find('PaperAdmin')+'.create();'+find('PaperAdmin')+".form.title='保密蓝桥杯试卷';"+find('PaperAdmin')+".form.description='组卷中录题，不丢失已填写的内容。';true");
    await until(() => ev('!!'+find('PaperAdmin')+'.$refs.creator'), 'in-paper question creator');
    const creator = find('PaperAdmin')+'.$refs.creator', inPaper = creator+'.$refs.objective';
    await ev(creator+".select('FILL_BLANK');true");
    await ev(inPaper+".question.title='填空：加法';"+inPaper+".question.stem='1+2=____';"+inPaper+".question.blanks[0].answers=['3'];"+inPaper+'.question.blanks[0].score=5;'+inPaper+'.saveQuestion();true');
    await until(() => ev(find('PaperAdmin')+'.form.items.length===1'), 'inline objective added');
    assert.equal(await ev(find('PaperAdmin')+'.form.title'), '保密蓝桥杯试卷');
    assert.equal(await ev(find('PaperAdmin')+'.form.items[0].score'), 5);
    await ev(creator+".select('PROGRAMMING');true");
    const programEditor = creator+'.$refs.program';
    await until(() => ev(programEditor+'?.allLanguage?.length===1&&!!'+programEditor+'.problem.problemId===false'), 'inline programming editor');
    assert.equal(await ev(programEditor+'.problem.auth'), 2);
    await ev("document.querySelector('.program-create-dialog .el-switch').click();true"); assert.equal(await ev(programEditor+'.problem.auth'), 1);
    await ev("document.querySelector('.program-create-dialog .el-switch').click();true"); assert.equal(await ev(programEditor+'.problem.auth'), 2);
    await shot('authoring-program-editor', true);
    await ev(programEditor+".problem.problemId='AUTHORING20';"+programEditor+".problem.title='组卷中录入的加法';"+programEditor+".problem.description='输出 a+b';"+programEditor+".problem.input='两个整数';"+programEditor+".problem.output='两数之和';"+programEditor+'.problem.isUploadCase=false;'+programEditor+".problemSamples=[{input:'1 2',output:'3',score:100}];"+programEditor+'.problemLanguages='+programEditor+'.allLanguage.map(language=>language.name);'+programEditor+'.submit();true');
    await until(() => ev(find('PaperAdmin')+'.form.items.length===2&&!'+creator+'.programDialog'), 'inline programming added');
    assert.equal(await ev(find('PaperAdmin')+'.form.title'), '保密蓝桥杯试卷');
    assert.equal(await ev("document.getElementById('app').__vue__.$route.path"), '/admin/papers');
    await ev(find('PaperAdmin')+'.preview=true;true'); await shot('authoring-paper-preview');
    await ev(find('PaperAdmin')+'.save();true'); await until(() => ev('!'+find('PaperAdmin')+'.dialog'), 'private paper save');
    const paperPayload = report.requests.filter(r=>r.path==='/api/admin/papers'&&r.method==='POST').pop().data;
    assert.equal(paperPayload.published, false); assert.deepEqual(paperPayload.items.map(item=>item.kind), ['OBJECTIVE','PROGRAMMING']);
    report.checks.push('Inline objective and programming creation preserves paper fields, derives blank scores, adds both saved IDs, and saves a private mixed paper without navigation.');
    await ev(find('CollectionAdmin')+'.edit({id:3,source:"OBJECTIVE"});true');
    const sets = find('CollectionAdmin')+'.$refs.sets';
    await until(() => ev(sets+'.setDialog&&'+sets+'.problemSet.id===3'), 'legacy collection editor');
    await ev(sets+'.$refs.setCreator.select("SINGLE_CHOICE");true');
    const setQuestion = sets+'.$refs.setCreator.$refs.objective';
    await ev(setQuestion+".question.title='题集中新增';"+setQuestion+".question.stem='1+1=?';"+setQuestion+".question.options=[{id:'A',text:'2'},{id:'B',text:'3'}];"+setQuestion+".question.correctAnswers=['A'];"+setQuestion+'.standalone=true;'+setQuestion+'.saveQuestion();true');
    await until(() => ev(sets+'.problemSet.questionIds.length===2'), 'new question auto-added to legacy set');
    assert.equal(await ev(sets+'.problemSet.title'), '旧版逐题训练集');
    await ev(sets+'.saveSet();true'); await until(() => ev('!'+sets+'.setDialog'), 'save legacy set');
    report.checks.push('Existing objective sets remain editable in the unified catalogue; inline creation preserves old references and explicit standalone publishing.');
    await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await shot('authoring-collections-mobile'); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);
    assert.equal(await ev("document.querySelector('.mobile-collections article .el-button').getBoundingClientRect().width>0"),true);
    await route('/admin/questions/create'); await until(() => ev("document.querySelectorAll('.type-grid button').length===5"), 'mobile type chooser');
    await shot('authoring-types-mobile', true); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);
    report.checks.push('Unified administration and type selection fit mobile without horizontal overflow.');
    assert.deepEqual(report.errors, []); report.validated=true; fs.writeFileSync(path.join(output,'paper-browser.json'),JSON.stringify(report,null,2)); console.log('Authoring browser: '+report.checks.length+' workflows passed; '+report.screenshots.length+' screenshots.'); return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'rating') {
    await route('/acm-rank'); await until(() => ev("document.querySelectorAll('.rating-table .el-table__body tbody tr').length===30"), 'practice rating rank');
    assert.equal(await ev("document.querySelector('.rank-tabs [aria-current]').textContent"), '平时排名');
    assert.equal(await ev("document.querySelector('.site-rank').textContent.includes('ACM 排名')"), false);
    await ev("document.querySelector('.rank-footer .btn-next').click();true"); await until(() => ev(find('SiteRank') + '.rows[0]?.rank===31'), 'global rank on page two');
    await ev("const n=document.querySelector('.rank-search input');n.value='student0';n.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('.rank-search').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));true");
    await until(() => ev(find('SiteRank') + '.rows.length===1&&'+find('SiteRank')+'.page===1'), 'search resets pagination');
    assert.equal(await ev(find('SiteRank')+'.rows[0].rank'),1);
    await ev("document.querySelector('.rating-table .el-button').click();true"); await until(() => ev(find('SiteRank')+'.historyRows.length===1'), 'practice rating history');
    assert.equal(await ev(find('SiteRank')+'.historyRows[0].type'),'practice');
    assert.equal(report.requests.filter(request => request.path.startsWith('/api/rating/users/')).pop().query.type, 'practice');
    await ev(find('SiteRank')+'.dialog=false;true'); await route('/oi-rank'); await until(() => ev(find('SiteRank')+".type==='contest'&&"+find('SiteRank')+'.rows.length===2'), 'contest rating alias');
    assert.equal(await ev(find('SiteRank')+'.keyword'),''); await shot('site-rating-contest-desktop');
    await ev("document.querySelector('.rating-table .el-button').click();true"); await until(() => ev(find('SiteRank')+'.historyRows.length===1'), 'contest rating history');
    assert.equal(await ev(find('SiteRank')+'.historyRows[0].type'),'contest'); await ev(find('SiteRank')+'.dialog=false;true');
    assert.equal(report.requests.filter(request => request.path.startsWith('/api/rating/users/')).pop().query.type, 'contest');
    await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true }); await sleep(250);
    assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'), false); await shot('site-rating-mobile');
    await route('/rank/practice'); await until(() => ev(find('SiteRank')+'.rows.length===30'), 'practice tab on mobile');
    await ev(find('SiteRank')+".keyword='no-such-user';"+find('SiteRank')+'.search();true'); await until(() => ev('!'+find('SiteRank')+'.loading'), 'empty search');
    assert.equal(await ev(find('SiteRank')+'.rows.length'),0);
    await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
    await route('/problem'); await until(() => ev(find('ProblemList')+'?.problemList?.length===3'), 'numeric task ratings');
    assert.equal(await ev(find('ProblemList')+".$el.textContent.includes('简单')"), false);
    assert.equal(await ev("Array.from(document.querySelectorAll('.rating-chip')).some(n=>n.textContent.includes('未评级'))"), true);
    await ev(find('ProblemList')+'.query.ratingMin=1300;'+find('ProblemList')+'.query.ratingMax=2000;document.querySelector(\'.rating-filter button.el-button\').click();true');
    await until(() => ev(find('ProblemList')+'.problemList.length===1&&'+find('ProblemList')+'.problemList[0].rating===1800'), 'task rating interval reaches backend');
    const query = report.requests.filter(request => request.path === '/api/get-problem-list').pop().query;
    assert.equal(query.ratingMin, '1300'); assert.equal(query.ratingMax, '2000'); assert(!query.difficulty);
    await shot('task-rating-range');
    await route('/admin/objective'); await until(() => ev("!!document.querySelector('.objective-admin')"), 'objective rating editor');
    await ev(find('ObjectiveAdmin')+'.newQuestion();true');
    await ev(find('ObjectiveAdmin')+".question.title='评级选择题';"+find('ObjectiveAdmin')+".question.stem='选择 A';"+find('ObjectiveAdmin')+".question.options[0].text='正确';"+find('ObjectiveAdmin')+".question.options[1].text='错误';"+find('ObjectiveAdmin')+".question.correctAnswers=['A'];"+find('ObjectiveAdmin')+'.question.rating=1700;true');
    await ev(find('ObjectiveAdmin')+'.saveQuestion();true'); await until(() => ev('!'+find('ObjectiveAdmin')+'.questionDialog'), 'save objective rating');
    assert.equal(report.requests.filter(request => request.path === '/api/admin/objective/questions' && request.method === 'POST').pop().data.rating, 1700);
    await route('/admin/contest/create'); await until(() => ev(find('CreateContest')+'?.contest'), 'rated contest editor');
    const ratedSwitch = "Array.from(document.querySelectorAll('.el-form-item')).find(n=>n.querySelector('label')?.textContent.includes('计入比赛 Rating')).querySelector('.el-switch')";
    await ev(find('CreateContest')+'.contest.id=9;'+find('CreateContest')+'.contest.startTime=new Date(Date.now()+3600000).toISOString();true');
    assert.equal(await ev(ratedSwitch+".classList.contains('is-disabled')"), false);
    await ev(ratedSwitch+'.click();true'); assert.equal(await ev(find('CreateContest')+'.contest.rated'), true);
    await ev(find('CreateContest')+'.contest.startTime=new Date(Date.now()-3600000).toISOString();true');
    assert.equal(await ev(ratedSwitch+".classList.contains('is-disabled')"), true);
    report.checks.push('Rating boards, compatibility redirects, global rank across pagination/search, separate history, empty results and mobile layout.');
    report.checks.push('Task list shows numeric/unrated ratings without difficulty labels and sends rating interval filters to the backend.');
    report.checks.push('Admin objective rating reaches save payload; upcoming contests can enable rating and started contests lock the switch.');
    assert.deepEqual(report.errors, []); report.validated = true;
    fs.writeFileSync(path.join(output, 'paper-browser.json'), JSON.stringify(report, null, 2));
    console.log('Rating browser regression passed; desktop/mobile screenshots saved.'); return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'objective-poll') {
    await route('/contest/6/problems'); await until(() => ev("!!document.querySelector('.mixed-entry')"), 'mixed contest entry');
    await ev(find('MixedContestEntry')+'.enter();true'); await until(() => ev(find('PaperRun')+'?.run?.contestId===6'), 'mixed contest run');
    await checkObjectivePolling(); await shot('objective-options-retained');
    assert.deepEqual(report.errors, []); report.validated = true;
    fs.writeFileSync(path.join(output, 'paper-browser.json'), JSON.stringify(report, null, 2));
    console.log('Objective polling regression passed.'); return;
  }
  assert.equal(await ev("document.querySelector('.problem-navigation a[aria-current=\"page\"]')?.getAttribute('href')"), '/papers');
  assert.equal(await ev("document.querySelectorAll('.problem-navigation a').length"), 3);
  await shot('paper-list-desktop');
  await ev(find('PaperList') + '.open(' + find('PaperList') + '.rows[0]);true');
  await until(() => ev(find('PaperRun') + '?.run?.paperId===1'), 'mixed run');
  assert.equal(await ev("document.querySelectorAll('.workspace-card').length"), 2);
  assert.equal(await ev("document.querySelectorAll('.blank-answer input').length"), 2);
  assert.equal(await ev("document.querySelector('.origin').textContent.includes('蓝桥杯')"), true);
  assert.equal(await ev("document.querySelector('.objective-feedback')===null"), true);
  const dockVisible = () => ev("(()=>{const n=document.querySelector('.question-dock'),r=n.getBoundingClientRect();return getComputedStyle(n).position==='fixed'&&r.top>=0&&r.bottom<=innerHeight&&r.left>=0&&r.right<=innerWidth&&[...n.querySelectorAll('button')].every(b=>{const x=b.getBoundingClientRect();return x.height>=44&&!!document.elementFromPoint(x.left+x.width/2,x.top+x.height/2)?.closest('.question-dock')})})()");
  assert.equal(await ev("document.querySelector('.question-dock .previous-question').disabled"), true);
  await ev("window.scrollTo(0,document.querySelector('.workspace-grid').getBoundingClientRect().top+scrollY-12);true");
  await sleep(350); assert.equal(await ev("document.querySelector('.question-numbers').getBoundingClientRect().bottom<0"), true);
  assert.equal(await dockVisible(), true); await shot('paper-navigation-scrolled', true);
  await ev("document.querySelector('[data-panel=\"editor\"]').closest('.workspace-card').querySelector('button[aria-label=\"展开卡片\"]').click();true");
  await until(() => ev("!!document.querySelector('.workspace-card.expanded')"), 'expanded answer card');
  assert.equal(await dockVisible(), true);
  assert.equal(await ev("document.querySelector('.workspace-card.expanded').getBoundingClientRect().bottom<document.querySelector('.question-dock').getBoundingClientRect().top"), true);
  await ev("document.querySelector('.question-dock .next-question').click();true");
  await until(() => ev(find('PaperRun') + '.index===1'), 'next question while expanded');
  assert.equal(await ev("!!document.querySelector('.workspace-card.expanded .paper-code .CodeMirror')"), true);
  assert.equal(await dockVisible(), true); await shot('paper-navigation-expanded', true);
  await ev("document.querySelector('.question-dock .next-question').click();true");
  await until(() => ev(find('PaperRun') + '.index===2'), 'last question');
  assert.equal(await ev("document.querySelector('.question-dock .next-question').disabled"), true);
  await ev("document.querySelector('.question-dock .previous-question').click();true");
  await until(() => ev(find('PaperRun') + '.index===1'), 'previous from last question');
  await ev("document.querySelector('.question-dock .previous-question').click();true");
  await until(() => ev(find('PaperRun') + '.index===0'), 'return to first question');
  await ev("document.querySelector('.workspace-card.expanded button[aria-label=\"恢复卡片\"]').click();window.scrollTo(0,0);true");
  report.checks.push('Question dock remains visible and clickable after scrolling and expanding; switching across fill/program/choice keeps the expanded card; first/last boundaries disable correctly.');
  await ev("document.querySelectorAll('.blank-answer input').forEach((n,i)=>{n.value=i?'8':'42';n.dispatchEvent(new Event('input',{bubbles:true}))});true");
  assert.equal(await ev(find('ObjectiveAnswer') + '.dirty'), true);
  await ev("document.querySelector('.answer-footer button').click();true");
  await until(() => ev(find('PaperRun') + '.run.answeredCount===1&&!'+find('PaperRun')+'.saving'), 'save blanks');
  assert.equal(await ev(find('ObjectiveAnswer') + '.dirty'), false);
  await shot('paper-fill-desktop');
  await ev(find('PaperRun') + ".select('PROGRAMMING:1');true");
  await until(() => ev("!!document.querySelector('.paper-code .CodeMirror')"), 'code editor');
  assert.equal(await ev("document.querySelector('.paper-code').textContent.includes('提交判题')"), false);
  assert.equal(await ev("document.querySelector('.paper-statement .examples pre').textContent"), '1 2');
  await ev(find('PaperProgramAnswer') + ".code=" + JSON.stringify("#include <iostream>\nint main(){int a,b;std::cin>>a>>b;std::cout<<a+b;}") + ";true");
  await ev(find('PaperRun') + '.saveProgram(' + find('PaperProgramAnswer') + '.payload);true');
  await until(() => ev('!'+find('PaperRun')+'.saving'), 'save code');
  assert.equal(report.requests.filter(r => r.path.endsWith('/program-submissions')).length, 0);
  await shot('paper-program-desktop');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await sleep(250); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'), false); await shot('paper-program-mobile');
  await ev("window.scrollTo(0,document.querySelector('.paper-code').getBoundingClientRect().top+scrollY-80);true"); await sleep(350);
  assert.equal(await dockVisible(), true); await shot('paper-navigation-mobile', true);
  await ev("window.scrollTo(0,0);true");
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await ev(find('PaperRun') + ".select('OBJECTIVE:1');true");
  await until(() => ev("document.querySelectorAll('.objective-answer .el-radio').length===2"), 'mixed choice');
  await ev("document.querySelector('.objective-answer .el-radio').click();true");
  await ev('window.finishing='+find('PaperRun')+'.finish();true');
  await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'finish confirmation');
  await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");
  await ev('window.finishing.then(()=>true)');
  await until(() => ev(find('PaperRun') + '.run.state===\'FINISHED\'&&'+find('PaperRun')+'.run.score===35'), 'deferred grading completes', 12000);
  assert.deepEqual(runs.values().next().value.answers['OBJECTIVE:1'].selectedAnswers, ['A']);
  report.checks.push('Mixed fill/choice/program paper saves independently, judges deferred code only after finish, polls asynchronously, and grades 35/35.');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await sleep(300); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'), false); await shot('paper-result-mobile');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await route('/papers'); await ev(find('PaperList') + '.open('+find('PaperList')+'.rows[1]);true');
  await until(() => ev(find('PaperRun')+'?.run?.paperId===2'), 'live paper');
  await ev(find('PaperProgramAnswer')+".code='int main(){return 0;}';true");
  await ev(find('PaperRun')+'.submitProgram('+find('PaperProgramAnswer')+'.payload);true');
  await until(() => ev('!'+find('PaperRun')+'.saving&&'+find('PaperRun')+'.question.submissionCount===1'), 'live submission');
  await ev(find('PaperProgramAnswer')+".code='int main(){return 1;}';true");
  await until(() => ev(find('PaperRun')+'.question.score===20'), 'live poll', 12000);
  assert.equal(await ev(find('PaperProgramAnswer')+'.code'), 'int main(){return 1;}'); assert.equal(await ev(find('PaperProgramAnswer')+'.dirty'), true);
  assert.equal(await ev(find('PaperRun')+'.run.score===undefined'), true);
  await ev(find('PaperRun')+".select('OBJECTIVE:1');true");
  await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'unsaved guard');
  await ev("document.querySelector('.el-message-box__btns .el-button:not(.el-button--primary)').click();true");
  assert.equal(await ev(find('PaperRun')+'.selectedKey'), 'PROGRAMMING:1');
  await ev(find('PaperRun')+'.saveProgram('+find('PaperProgramAnswer')+'.payload);true'); await until(() => ev('!'+find('PaperRun')+'.saving'), 'save modified code');
  await ev("document.documentElement.dataset.theme='dark';true"); await shot('paper-program-dark'); await ev("document.documentElement.dataset.theme='light';true");
  report.checks.push('Live verdicts appear before finish without revealing total score; polling preserves unsaved code and navigation guards protect drafts.');
  await route('/admin/papers'); await until(() => ev("!!document.querySelector('.paper-admin')"), 'admin paper');
  await ev(find('PaperAdmin')+'.create();true'); await until(() => ev(find('PaperAdmin')+'.choices.length===2'), 'paper picker');
  await ev(find('PaperAdmin')+'.candidate=4;'+find('PaperAdmin')+'.add();true'); await until(() => ev(find('PaperAdmin')+'.form.items.length===1'), 'add fill');
  assert.equal(await ev(find('PaperAdmin')+'.form.items[0].score'), 10);
  await ev(find('PaperAdmin')+".kind='PROGRAMMING';"+find('PaperAdmin')+".searchChoices('');true"); await until(() => ev(find('PaperAdmin')+'.choices.length===1&&!'+find('PaperAdmin')+'.choiceLoading'), 'program picker');
  await ev(find('PaperAdmin')+'.candidate=1;'+find('PaperAdmin')+'.add();true'); await until(() => ev(find('PaperAdmin')+'.form.items.length===2'), 'add program');
  await ev(find('PaperAdmin')+".form.title='蓝桥杯组合练习';"+find('PaperAdmin')+".form.explanationPolicy='NEVER';"+find('PaperAdmin')+".form.answerPolicy='AT_TIME';"+find('PaperAdmin')+'.form.answerReleaseAt=2000000000000;true');
  await shot('paper-admin-editor'); await ev(find('PaperAdmin')+'.save();true'); await until(() => ev('!'+find('PaperAdmin')+'.dialog'), 'save paper');
  const payload = report.requests.filter(r => r.path==='/api/admin/papers'&&r.method==='POST').pop().data;
  assert.deepEqual(payload.items.map(item=>item.kind), ['OBJECTIVE','PROGRAMMING']); assert.equal(payload.answerReleaseAt,2000000000000); assert.equal(payload.explanationPolicy,'NEVER');
  await ev(find('PaperAdmin') + '.edit(1);true'); await until(() => ev(find('PaperAdmin')+'.form?.id===1&&'+find('PaperAdmin')+'.dialog'), 'edit existing mixed paper');
  assert.equal(await ev(find('PaperAdmin')+'.form.items[0].score'), 10);
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await sleep(250); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'), false); await shot('paper-admin-mobile');
  await ev(find('PaperAdmin')+'.close();true');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  report.checks.push('Admin references both banks, preserves order, derives blank-point totals, edits existing papers and saves independent release policies on desktop/mobile.');
  await route('/admin/objective'); await until(() => ev("!!document.querySelector('.objective-admin')"), 'blank administration');
  await ev(find('ObjectiveAdmin')+".newQuestion();"+find('ObjectiveAdmin')+".question.questionType='FILL_BLANK';"+find('ObjectiveAdmin')+'.changeQuestionType();true');
  await ev(find('ObjectiveAdmin')+".question.title='多空结果';"+find('ObjectiveAdmin')+".question.stem='填写计算结果';"+find('ObjectiveAdmin')+".question.blanks[0].answers=['42','0x2a'];"+find('ObjectiveAdmin')+'.question.blanks[0].score=3;'+find('ObjectiveAdmin')+'.addBlank();'+find('ObjectiveAdmin')+".question.blanks[1].answers=['8'];true");
  assert.equal(await ev("document.querySelectorAll('.blank-editor').length"), 2); await shot('blank-admin-editor');
  await ev(find('ObjectiveAdmin')+'.saveQuestion();true'); await until(() => ev('!'+find('ObjectiveAdmin')+'.questionDialog'), 'save blank question');
  assert.equal(report.requests.filter(r=>r.path==='/api/admin/objective/questions'&&r.method==='POST').pop().data.blanks.length,2);
  report.checks.push('Fill editor supports multiple blanks, equivalent answers and per-blank points.');
  await route('/admin/contest/create'); await until(() => ev("!!document.querySelector('.mixed-settings')"), 'mixed contest configuration');
  await ev(find('MixedContestSettings')+".toggle(true);true"); await sleep(100);
  await ev(find('MixedContestSettings')+".set('paperId',2);true"); await sleep(100);
  await ev(find('MixedContestSettings')+".set('answerPolicy','NEVER');true"); await sleep(100);
  assert.equal(await ev(find('CreateContest')+'.contest.mixed.paperId'), 2); assert.equal(await ev(find('CreateContest')+'.contest.mixed.answerPolicy'), 'NEVER'); await shot('mixed-contest-admin-settings', true);
  report.checks.push('Contest administration selects an existing mixed paper and configures contest answer/explanation release independently from practice.');
  await route('/contest/6/problems'); await until(() => ev("!!document.querySelector('.mixed-entry')"), 'mixed contest entry');
  await shot('mixed-contest-entry'); await ev(find('MixedContestEntry')+'.enter();true'); await until(() => ev(find('PaperRun')+'?.run?.contestId===6'), 'mixed contest run');
  assert.equal(await ev("document.querySelector('.run-toolbar').innerText.includes('返回比赛')"), true);
  assert.equal(await ev("document.querySelector('.paper-run').innerText.includes('距比赛截止')"), true);
  await checkObjectivePolling();
  await ev(find('PaperRun')+'.finish();true'); await until(() => ev("!!document.querySelector('.el-message-box')"), 'contest hand-in dialog');
  await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true"); await until(() => ev(find('PaperRun')+"?.run?.state==='FINISHED'"), 'contest hand-in');
  assert.equal(await ev(find('PaperRun')+'.question.answerVisible'), false); assert.equal(await ev(find('PaperRun')+'.question.correctAnswers'), undefined); await shot('mixed-contest-answers-hidden');
  contestEnd = Date.now() - 1000; await ev(find('PaperRun')+'.refresh();true'); await until(() => ev(find('PaperRun')+'.question.answerVisible'), 'post-contest answers'); await shot('mixed-contest-answers-released');
  await route('/contest/6/rank'); await until(() => ev("document.querySelectorAll('.mixed-rank .el-table__body tbody tr').length===2"), 'mixed ACM scoreboard'); await shot('mixed-contest-acm-rank');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true }); await sleep(250); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'), false); await shot('mixed-contest-rank-mobile');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  report.checks.push('Real contest routes open a single mixed answer sheet with countdown/back navigation, defer objective answers until the end, and display objective-first ACM standings on desktop/mobile.');
  uid='paper-bob';role='default_user'; await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");
  await route('/papers?tab=history'); await until(() => ev(find('PaperList')+'&&!'+find('PaperList')+'.loading'), 'new account history'); assert.equal(await ev(find('PaperList')+'.rows.length'),0);
  report.checks.push('Account switch clears mixed-paper state and loads only the new user history.');
  assert.deepEqual(report.errors, []);
  report.validated = true;
  fs.writeFileSync(path.join(output, 'paper-browser.json'), JSON.stringify(report, null, 2));
  console.log('Paper browser: ' + report.checks.length + ' workflows passed; ' + report.screenshots.length + ' screenshots; no runtime exceptions.');
})().catch(error => {
  report.failure = error.stack; fs.writeFileSync(path.join(output, 'paper-browser.json'), JSON.stringify(report, null, 2)); console.error(error); process.exitCode = 1;
}).finally(() => { if (ws) ws.close(); browser.kill(); });
