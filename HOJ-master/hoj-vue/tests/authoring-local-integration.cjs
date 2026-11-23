// Opt-in local regression. Creates isolated accounts/questions/containers and cleans only those records.
const assert = require('assert').strict;
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
if (process.env.HOJ_LOCAL_AUTHORING_TEST !== '1') throw Error('Set HOJ_LOCAL_AUTHORING_TEST=1 for local authoring integration.');
const base = 'http://127.0.0.1:6688', compose = path.resolve(__dirname, '../../compose.yaml');
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-authoring-review');
fs.mkdirSync(output, { recursive: true });
const accounts = ['admin', 'admin', 'default_user', 'root'].map(role => ({ role, uid: crypto.randomBytes(16).toString('hex'), username: 'author_it_' + crypto.randomBytes(4).toString('hex'), password: crypto.randomBytes(8).toString('hex'), token: '' }));
const [teacher, other, student, root] = accounts, report = { checks: [], cleaned: false };
const quote = value => "'" + String(value).replace(/'/g, "''") + "'", uids = accounts.map(a => quote(a.uid)).join(',');
const programIds = [], contestIds = [];
function sql(input) { return execFileSync('docker', ['compose', '-p', 'hoj-master', '-f', compose, 'exec', '-T', 'hoj-mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'], { input, encoding: 'utf8', windowsHide: true, timeout: 30000 }).trim(); }
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function request(account, endpoint, method = 'GET', data, expected = 200) {
  const response = await fetch(base + endpoint, { method, headers: { 'Content-Type': 'application/json', ...(account && account.token ? { Authorization: account.token } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(20000) });
  const body = await response.json();
  if (account && response.headers.get('authorization')) account.token = response.headers.get('authorization');
  if (expected === 'denied') { assert.notEqual(body.status, 200, endpoint + ' must deny'); return body; }
  assert.equal(body.status, expected, endpoint + ': ' + body.msg); return body.data;
}
async function until(check, label, limit = 60000) { const end = Date.now() + limit; while (Date.now() < end) { const value = await check(); if (value) return value; await sleep(1000); } throw Error('Timeout: ' + label); }
const nonce = () => crypto.randomBytes(16).toString('hex');
(async () => {
  for (const a of accounts) {
    const hash = crypto.createHash('md5').update(a.password).digest('hex');
    sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(a.uid)},${quote(a.username)},${quote(hash)}); INSERT INTO user_record(uid) VALUES(${quote(a.uid)}); INSERT INTO user_role(uid,role_id) SELECT ${quote(a.uid)},id FROM role WHERE role IN ('default_user',${quote(a.role)});`);
    await request(a, '/api/login', 'POST', { username: a.username, password: a.password }); assert(a.token);
  }
  const languages = await request(teacher, '/api/languages?all=false'); const cpp = languages.find(l => l.name === 'C++' && l.oj === 'ME'); assert(cpp);
  const problem = { problemId: 'AUTHOR_' + nonce().slice(0, 8), title: '临时保密加法', description: '给定 a,b，输出 a+b。', input: '两个整数', output: '整数和', examples: '', timeLimit: 1000, memoryLimit: 256, stackLimit: 128, difficulty: 0, type: 0, auth: 2, author: teacher.username, isRemote: false, isGroup: false, isUploadCase: false, judgeMode: 'default', judgeCaseMode: 'default', codeShare: false, openCaseResult: false, isRemoveEndBlank: true, isFileIO: false, source: '临时集成回归' };
  const program = await request(teacher, '/api/admin/problem', 'POST', { problem, languages: [cpp], samples: [{ input: '1 2', output: '3', status: 0 }], tags: [], codeTemplates: [], isUploadTestCase: false, judgeMode: 'default', changeModeCode: false, changeJudgeCaseMode: false });
  assert(Number.isSafeInteger(program.id)); programIds.push(program.id); assert.equal(program.auth, 2);
  const q = await request(teacher, '/api/admin/objective/questions', 'POST', { title: '临时保密客观题', questionType: 'SINGLE_CHOICE', stem: '1+2=?', options: [{ id: 'A', text: '3' }, { id: 'B', text: '4' }], correctAnswers: ['A'], explanation: '相加得到 3。', tagIds: [], published: false, practiceEnabled: false });
  assert((await request(teacher, '/api/admin/papers/program-questions?keyword=' + encodeURIComponent(problem.title))).some(p => p.id === program.id));
  assert(!(await request(other, '/api/admin/papers/program-questions?keyword=' + encodeURIComponent(problem.title))).some(p => p.id === program.id));
  const config = { title: '临时保密混合卷', description: '', items: [{ kind: 'OBJECTIVE', referenceId: q.id, score: 5 }, { kind: 'PROGRAMMING', referenceId: program.id, score: 20 }], programMode: 'LIVE', scoreRule: 'BEST', answerPolicy: 'AFTER_FINISH', explanationPolicy: 'AFTER_FINISH', published: false };
  // Separate foreign-objective and foreign-program checks, including a direct ID bypass attempt.
  await request(other, '/api/admin/papers', 'POST', { ...config, items: [config.items[0]] }, 'denied');
  await request(other, '/api/admin/papers', 'POST', { ...config, items: [config.items[1]] }, 'denied');
  let paper = await request(teacher, '/api/admin/papers', 'POST', config);
  const catalogue = await request(teacher, '/api/admin/papers/collections?kind=PAPER'); assert(catalogue.records.some(p => p.id === paper.id && p.source === 'PAPER' && !p.published));
  assert.equal((await request(other, '/api/admin/papers/collections')).total, 0);
  await request(student, '/api/admin/papers/collections', 'GET', undefined, 'denied');
  await request(student, '/api/papers/runs', 'POST', { paperId: paper.id, requestId: nonce() }, 'denied');
  await request(student, '/api/get-problem-detail?problemId=' + program.problemId, 'GET', undefined, 'denied');
  await request(student, '/api/objective/runs', 'POST', { questionId: q.id, requestId: nonce() }, 'denied');
  report.checks.push('Real authoring returns saved program IDs; ordinary admins can reference their own private questions, foreign IDs are denied, catalogue is scoped, unpublished papers and standalone sources remain hidden.');
  // An unpublished paper is usable by an authorized contest without opening its daily practice entry.
  const now = Date.now(), contest = { title: '临时保密组卷比赛', description: '自动回归', uid: teacher.uid, type: 0, auth: 0, author: teacher.username, startTime: new Date(now - 10000).toISOString(), endTime: new Date(now + 3600000).toISOString(), duration: 3610, visible: true, source: 0, sealRank: false, autoRealRank: true, openPrint: false, openAccountLimit: false, starAccount: [], awardType: 0, oiRankScoreType: 'Highest', rankShowName: 'username', openRank: false, mixed: { paperId: paper.id, scoreRule: 'BEST', answerPolicy: 'AFTER_END', explanationPolicy: 'AFTER_END' } };
  await request(teacher, '/api/admin/contest', 'POST', contest);
  const cid = Number(sql(`SELECT id FROM contest WHERE uid=${quote(teacher.uid)} AND title=${quote(contest.title)}`)); assert(cid > 0); contestIds.push(cid);
  const contestRun = await request(student, '/api/contests/' + cid + '/mixed/run', 'POST'); assert.equal(contestRun.items.length, 2); assert(!contestRun.items[0].correctAnswers);
  paper.published = true; paper = await request(teacher, '/api/admin/papers', 'POST', paper);
  const run = await request(student, '/api/papers/runs', 'POST', { paperId: paper.id, requestId: nonce() });
  assert.equal(run.items[0].title, q.title); assert(!run.items[0].correctAnswers);
  const prefix = '/api/papers/runs/' + run.id;
  await request(student, prefix + '/answers', 'PUT', { key: 'OBJECTIVE:' + q.id, selectedAnswers: ['A'] });
  const code = '#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b;}', programKey = 'PROGRAMMING:' + program.id;
  await request(student, prefix + '/program-submissions', 'POST', { key: programKey, language: 'C++', code, requestId: nonce() });
  const judged = await until(async () => { const view = await request(student, prefix); return view.items.find(item => item.key === programKey).submissionCount && view.gradingComplete ? view : false; }, 'private programming AC');
  assert.equal(judged.items[1].submissions[0].status, 0);
  const finished = await request(student, prefix + '/finish', 'POST'); assert.equal(finished.score, 25); assert.deepEqual(finished.items[0].correctAnswers, ['A']);
  await request(student, '/api/objective/runs', 'POST', { questionId: q.id, requestId: nonce() }, 'denied');
  await request(student, '/api/get-problem-detail?problemId=' + program.problemId, 'GET', undefined, 'denied');
  report.checks.push('A private paper enters a real mixed contest; publishing daily practice then allows student-scoped frozen answers and real AC judging while both standalone question entries stay closed.');
  const set = await request(teacher, '/api/admin/objective/sets', 'POST', { title: '临时保密题集', mode: 'PRACTICE', questionIds: [q.id], published: true, answerPolicy: 'AFTER_ANSWER', explanationPolicy: 'AFTER_ANSWER' });
  const setRun = await request(student, '/api/objective/runs', 'POST', { setId: set.id, requestId: nonce() }); assert.equal(setRun.questions[0].title, q.title);
  await request(teacher, '/api/admin/objective/questions', 'PUT', { ...q, published: true, practiceEnabled: true });
  const standalone = await request(student, '/api/objective/runs', 'POST', { questionId: q.id, requestId: nonce() }); assert(standalone.id);
  assert.equal((await request(student, prefix)).state, 'FINISHED');
  report.checks.push('Published legacy sets also accept private references; independently opening standalone practice later keeps existing paper progress intact.');
  report.validated = true;
})().catch(e => { report.failure = e.stack; process.exitCode = 1; }).finally(async () => {
  try {
    await until(() => Number(sql(`SELECT COUNT(*) FROM judge WHERE uid IN (${uids}) AND status IN (5,6,7,9,15)`)) === 0, 'jobs drain before cleanup');
    sql(`DELETE FROM user_acproblem WHERE uid IN (${uids}); DELETE FROM judge WHERE uid IN (${uids}); DELETE FROM paper_program_snapshot WHERE uid IN (${uids}); DELETE FROM paper_run WHERE uid IN (${uids}); DELETE FROM contest WHERE uid IN (${uids}); DELETE FROM paper WHERE created_by IN (${uids}); DELETE FROM objective_run WHERE uid IN (${uids}); DELETE FROM objective_set WHERE created_by IN (${uids}); DELETE v FROM objective_question_version v JOIN objective_question q ON q.id=v.question_id WHERE q.created_by IN (${uids}); DELETE FROM objective_question WHERE created_by IN (${uids});`);
    for (const id of programIds) await request(root, '/api/admin/problem?pid=' + id, 'DELETE');
    for (const a of accounts) if (a.token) await request(a, '/api/logout').catch(() => {});
    sql(`DELETE FROM session WHERE uid IN (${uids}); DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids})`), '0');
    assert.equal(sql(`SELECT COUNT(*) FROM problem WHERE author IN (${accounts.map(a => quote(a.username)).join(',')})`), '0'); report.cleaned = true;
  } catch (e) { report.cleanupFailure = e.stack; process.exitCode = 1; }
  fs.writeFileSync(path.join(output, 'authoring-local-integration.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
});
