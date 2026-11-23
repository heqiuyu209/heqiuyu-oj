// Opt-in LOCAL integration test. Creates two random users and removes only their records in finally.
// Requires the running Compose project hoj-master, migrated schema, rebuilt backend and judge.
const assert = require('assert').strict;
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
if (process.env.HOJ_LOCAL_PAPER_TEST !== '1') throw Error('Set HOJ_LOCAL_PAPER_TEST=1 to explicitly enable local temporary-account integration.');
const base = 'http://127.0.0.1:6688';
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-paper-review');
fs.mkdirSync(output, { recursive: true });
const report = { checks: [], cleaned: false };
const compose = path.resolve(__dirname, '../../compose.yaml');
const accounts = [0, 1].map(() => ({ uid: crypto.randomBytes(16).toString('hex'), username: 'paper_it_' + crypto.randomBytes(4).toString('hex'), password: crypto.randomBytes(8).toString('hex'), token: '' }));
const sqlString = value => "'" + String(value).replace(/'/g, "''") + "'";
const uids = accounts.map(account => sqlString(account.uid)).join(',');
function sql(input) { return execFileSync('docker', ['compose', '-p', 'hoj-master', '-f', compose, 'exec', '-T', 'hoj-mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'], { input, encoding: 'utf8', windowsHide: true, timeout: 30000 }).trim(); }
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function request(account, endpoint, method = 'GET', data, expected = 200) {
  const response = await fetch(base + endpoint, { method, headers: { 'Content-Type': 'application/json', ...(account && account.token ? { Authorization: account.token } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(20000) });
  const body = await response.json();
  if (account && response.headers.get('authorization')) account.token = response.headers.get('authorization');
  if (expected === 'denied') { assert.notEqual(body.status, 200, endpoint + ' must deny'); return body; }
  assert.equal(body.status, expected, endpoint + ': ' + body.msg); return body.data;
}
async function until(check, label, limit = 90000) { const end = Date.now() + limit; while (Date.now() < end) { const result = await check(); if (result) return result; await sleep(1000); } throw Error('Timeout: ' + label); }
const [teacher, student] = accounts;
const source = '#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b<<"\\n";return 0;}';
(async () => {
  // Never modify existing accounts or change settings to get a test to pass.
  assert.equal(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('paper','paper_run','paper_program_snapshot')"), '3');
  for (let i = 0; i < accounts.length; i++) {
    const a = accounts[i], hash = crypto.createHash('md5').update(a.password).digest('hex');
    sql(`INSERT INTO user_info(uuid,username,password) VALUES(${sqlString(a.uid)},${sqlString(a.username)},${sqlString(hash)}); INSERT INTO user_record(uid) VALUES(${sqlString(a.uid)}); INSERT INTO user_role(uid,role_id) SELECT ${sqlString(a.uid)},id FROM role WHERE role IN (${i === 0 ? "'default_user','problem_admin'" : "'default_user'"});`);
    await request(a, '/api/login', 'POST', { username: a.username, password: a.password }); assert(a.token);
  }
  await request(null, '/api/papers', 'GET', undefined, 'denied'); await request(student, '/api/admin/papers', 'GET', undefined, 'denied');
  report.checks.push('Real Shiro denies anonymous reads and student management writes.');
  const q = await request(teacher, '/api/admin/objective/questions', 'POST', { title: '临时混合卷填空测试', source: '自动回归，完成后清理', questionType: 'FILL_BLANK', stem: '填写两个结果', blanks: [{ id: 'a', label: '结果一', score: 3, answers: ['42', '0x2a'], trim: true, caseSensitive: false }, { id: 'b', label: '结果二', score: 7, answers: ['8'], trim: true, caseSensitive: true }], explanation: '临时答案解析', tagIds: [], published: true, practiceEnabled: true });
  const choices = await request(teacher, '/api/admin/papers/program-questions'); assert(choices.length, 'Need a public local standard programming question'); const program = choices.find(p => p.problemId === '1') || choices[0];
  let paper = await request(teacher, '/api/admin/papers', 'POST', { title: '临时混合卷集成测试', description: '', items: [{ kind: 'OBJECTIVE', referenceId: q.id, score: 10 }, { kind: 'PROGRAMMING', referenceId: program.id, score: 20 }], programMode: 'AFTER_FINISH', scoreRule: 'BEST', answerPolicy: 'NEVER', explanationPolicy: 'NEVER', published: true });
  const key = 'OBJECTIVE:' + q.id, programKey = 'PROGRAMMING:' + program.id;
  const nonce = crypto.randomBytes(16).toString('hex'); const run = await request(teacher, '/api/papers/runs', 'POST', { paperId: paper.id, requestId: nonce }); const id = run.id;
  assert.equal((await request(teacher, '/api/papers/runs', 'POST', { paperId: paper.id, requestId: nonce })).id, id);
  await request(student, '/api/papers/runs/' + id, 'GET', undefined, 'denied');
  const publicBlank = run.items[0].blanks[0]; assert.deepEqual(Object.keys(publicBlank).sort(), ['id', 'label', 'score']); assert(!('correctAnswers' in run.items[0]));
  await request(teacher, '/api/papers/runs/' + id + '/answers', 'PUT', { key, selectedAnswers: [' 0X2A ', 'wrong'] });
  await request(teacher, '/api/papers/runs/' + id + '/answers', 'PUT', { key: programKey, language: 'C++', code: source });
  assert.equal(sql(`SELECT COUNT(*) FROM judge WHERE paper_run_id=${id}`), '0');
  const standalone = await request(teacher, '/api/objective/runs', 'POST', { questionId: q.id, requestId: crypto.randomBytes(16).toString('hex') });
  const practiced = await request(teacher, '/api/objective/runs/' + standalone.id + '/answers', 'PUT', { questionId: q.id, selectedAnswers: ['42', '8'] }); assert.equal(practiced.score, 10);
  assert.equal((await request(teacher, '/api/papers/runs/' + id)).items[0].selectedAnswers[1], 'wrong');
  q.title = '管理员修改后的题目'; q.blanks[0].score = 30; q.blanks[0].answers = ['different']; await request(teacher, '/api/admin/objective/questions', 'PUT', q);
  await request(teacher, '/api/papers/runs/' + id + '/finish', 'POST'); await request(teacher, '/api/papers/runs/' + id + '/finish', 'POST');
  assert.equal(sql(`SELECT COUNT(*) FROM judge WHERE paper_run_id=${id}`), '1');
  const status = await until(() => { const s = Number(sql(`SELECT status FROM judge WHERE paper_run_id=${id}`)); return ![5,6,7,9,15].includes(s) ? { s } : false; }, 'deferred local judge'); assert.equal(status.s, 0, 'Deferred code should AC');
  const hidden = await request(teacher, '/api/papers/runs/' + id); assert(!('score' in hidden)); assert(!('score' in hidden.items[1])); assert(!('correctAnswers' in hidden.items[0])); assert.equal(hidden.items[0].maxScore, 10); assert.equal(hidden.items[0].title, '临时混合卷填空测试');
  const submitId = Number(sql(`SELECT submit_id FROM judge WHERE paper_run_id=${id}`));
  await request(teacher, '/api/get-submission-detail?submitId=' + submitId, 'GET', undefined, 'denied'); await request(null, '/api/get-all-case-result?submitId=' + submitId, 'GET', undefined, 'denied');
  const polled = await request(null, '/api/check-submissions-status', 'POST', { submitIds: [submitId] }); assert(!polled[submitId]);
  assert.equal(sql(`SELECT COUNT(*) FROM user_acproblem WHERE uid=${sqlString(teacher.uid)}`), '0');
  const generic = await request(teacher, '/api/get-submission-list?onlyMine=true&currentPage=1&limit=30'); assert.equal(generic.total, 0);
  paper.answerPolicy = 'AFTER_FINISH'; paper.explanationPolicy = 'AFTER_FINISH'; paper.programMode = 'LIVE'; paper = await request(teacher, '/api/admin/papers', 'POST', paper);
  const result = await request(teacher, '/api/papers/runs/' + id); assert.equal(result.programMode, 'AFTER_FINISH'); assert.equal(result.score, 23); assert.equal(result.items[0].score, 3); assert.equal(result.items[0].maxScore, 10);
  report.checks.push('Real mixed paper: independent standalone practice; partial blanks; frozen revision/points; deferred AC once; hidden feedback cannot bypass public endpoints or update practice AC/statistics.');
  const live = await request(teacher, '/api/papers/runs', 'POST', { paperId: paper.id, requestId: crypto.randomBytes(16).toString('hex') });
  await sleep(9000); const liveNonce = crypto.randomBytes(16).toString('hex');
  await request(teacher, '/api/papers/runs/' + live.id + '/program-submissions', 'POST', { key: programKey, language: 'C++', code: source, requestId: liveNonce });
  await request(teacher, '/api/papers/runs/' + live.id + '/program-submissions', 'POST', { key: programKey, language: 'C++', code: source, requestId: liveNonce });
  const liveView = await until(async () => { const r = await request(teacher, '/api/papers/runs/' + live.id); return r.gradingComplete ? r : false; }, 'live paper AC'); assert.equal(liveView.items[1].score, 20); assert(!('score' in liveView)); assert.equal(liveView.items[1].submissionCount, 1);
  await request(teacher, '/api/papers/runs/' + live.id + '/finish', 'POST'); assert.equal((await request(teacher, '/api/papers/runs/' + live.id)).score, 20);
  report.checks.push('Real live code uses idempotent submissions, shows AC before finish, and withholds the total until finish.');
  for (const [name, code, expected] of [['AC', source, 0], ['WA', '#include <iostream>\nint main(){std::cout<<"definitely wrong answer";return 0;}', -1], ['TLE', '#include <iostream>\nint main(){volatile unsigned long long x=0;while(true){++x;}return 0;}', 1]]) {
    await sleep(9000);
    const judge = await request(teacher, '/api/submit-problem-judge', 'POST', { pid: program.problemId, cid: 0, language: 'C++', code, isRemote: false });
    const judged = await until(async () => { const data = await request(teacher, '/api/get-submission-detail?submitId=' + judge.submitId); return ![5,6,7,9,15].includes(data.submission.status) ? data.submission : false; }, name);
    assert.equal(judged.status, expected, 'Ordinary programming ' + name); report.checks.push('Original programming ' + name + ' passed.');
  }
  assert.equal(sql(`SELECT COUNT(*) FROM user_acproblem WHERE uid=${sqlString(teacher.uid)}`), '1');
  report.validated = true;
})().catch(e => { report.failure = e.stack; process.exitCode = 1; }).finally(async () => {
  try {
    await until(() => Number(sql(`SELECT COUNT(*) FROM judge WHERE uid IN (${uids}) AND status IN (5,6,7,9,15)`)) === 0, 'test jobs drain before cleanup');
    for (const account of accounts) if (account.token) await request(account, '/api/logout').catch(() => {});
    sql(`DELETE FROM user_acproblem WHERE uid IN (${uids}); DELETE FROM judge WHERE uid IN (${uids}); DELETE FROM paper_program_snapshot WHERE uid IN (${uids}); DELETE FROM paper_run WHERE uid IN (${uids}); DELETE FROM paper WHERE created_by IN (${uids}); DELETE FROM objective_run WHERE uid IN (${uids}); DELETE v FROM objective_question_version v JOIN objective_question q ON q.id=v.question_id WHERE q.created_by IN (${uids}); DELETE FROM objective_question WHERE created_by IN (${uids}); DELETE FROM session WHERE uid IN (${uids}); DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids})`), '0'); report.cleaned = true;
  } catch (e) { report.cleanupFailure = e.message; process.exitCode = 1; }
  fs.writeFileSync(path.join(output, 'paper-local-integration.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
});
