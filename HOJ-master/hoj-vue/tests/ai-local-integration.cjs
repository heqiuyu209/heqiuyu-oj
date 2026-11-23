// Opt-in local integration. Only random temporary accounts and their fixtures are mutated.
// No request is sent to a model provider, and no real API key is required.
const assert = require('assert').strict;
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
if (process.env.HOJ_LOCAL_AI_TEST !== '1') throw Error('Set HOJ_LOCAL_AI_TEST=1.');
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(require('os').tmpdir(), 'hoj-ai-review');
fs.mkdirSync(output, { recursive: true });
const accounts = ['root', 'default_user', 'default_user'].map(role => ({ role, uid: crypto.randomBytes(16).toString('hex'), username: 'ai_it_' + crypto.randomBytes(5).toString('hex'), password: crypto.randomBytes(8).toString('hex'), token: '' }));
const [teacher, alice, bob] = accounts, report = { checks: [], cleaned: false };
const quote = value => "'" + String(value).replace(/'/g, "''") + "'", uids = accounts.map(account => quote(account.uid)).join(',');
const chat = { messages: [{ role: 'user', content: '如何理解循环边界？' }], includeProfile: false };
function sql(input) { return execFileSync('docker', ['compose', '-p', 'hoj-master', '-f', path.resolve(__dirname, '../../compose.yaml'), 'exec', '-T', 'hoj-mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'], { input, encoding: 'utf8', windowsHide: true, timeout: 30000 }).trim(); }
async function request(account, endpoint, method = 'GET', data, expected = 200) {
  const response = await fetch('http://127.0.0.1:6688' + endpoint, { method, headers: { 'Content-Type': 'application/json', ...(account?.token ? { Authorization: account.token } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(20000) });
  const body = await response.json(); if (account && response.headers.get('authorization')) account.token = response.headers.get('authorization');
  assert.equal(body.status, expected, endpoint + ': ' + body.msg);
  if (endpoint.startsWith('/api/ai') && expected === 200) { assert.equal(response.headers.get('cache-control'), 'no-store'); assert(!JSON.stringify(body).includes('key_ciphertext')); assert(!JSON.stringify(body).includes('fake-integration-only-key')); }
  return body.data;
}
function contest(auth = 0, future = false) {
  return Number(sql(`INSERT INTO contest(uid,author,title,auth,status,start_time,end_time,duration,visible) VALUES(${quote(teacher.uid)},${quote(teacher.username)},'temporary AI policy contest',${auth},0,${future ? 'DATE_ADD(NOW(),INTERVAL 1 DAY)' : 'DATE_SUB(NOW(),INTERVAL 1 HOUR)'},DATE_ADD(NOW(),INTERVAL 2 DAY),172800,1);SELECT LAST_INSERT_ID();`));
}
(async () => {
  for (const account of accounts) {
    const hash = crypto.createHash('md5').update(account.password).digest('hex');
    sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(account.uid)},${quote(account.username)},${quote(hash)});INSERT INTO user_record(uid) VALUES(${quote(account.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(account.uid)},id FROM role WHERE role IN ('default_user',${quote(account.role)});`);
    await request(account, '/api/login', 'POST', { username: account.username, password: account.password }); assert(account.token);
  }
  for (const [endpoint, method, data] of [['/api/ai/settings', 'GET'], ['/api/ai/settings', 'PUT', {}], ['/api/ai/settings', 'DELETE'], ['/api/ai/chat', 'POST', chat]]) await request(null, endpoint, method, data, 401);
  const config = { baseUrl: 'https://models.example.com/v1', model: 'test-model', apiKey: 'fake-integration-only-key' };
  const originalModel=await request(alice, '/api/ai/settings', 'PUT', config);assert.equal(originalModel.configured, true);
  assert.equal((await request(bob, '/api/ai/settings')).configured, false);
  const cipher = sql(`SELECT key_ciphertext FROM ai_user_setting WHERE uid=${quote(alice.uid)};`); assert(cipher.startsWith('v1:')); assert(!cipher.includes(config.apiKey));
  const currentModel=await request(alice, '/api/ai/settings', 'PUT', { ...config, model: 'updated-model', apiKey: '' });assert.notEqual(originalModel.fingerprint,currentModel.fingerprint);
  await request(alice,'/api/ai/chat','POST',{...chat,modelFingerprint:originalModel.fingerprint},409);
  await request(alice,'/api/knowledge/answer','POST',{search:{query:'前缀和'},allowModelExcerpts:true,modelFingerprint:originalModel.fingerprint},409);
  await request(alice,'/api/ai/recommendations/explain','POST',{filters:{source:'HOJ'},fingerprint:'a'.repeat(64),candidateIds:['PROGRAMMING:1'],modelFingerprint:originalModel.fingerprint},409);
  await request(alice,'/api/ai/chat','POST',chat,400);
  await request(alice,'/api/knowledge/answer','POST',{search:{query:'前缀和'},allowModelExcerpts:true},400);
  report.checks.push('All three actual model routes reject stale settings before provider work; chat and excerpt sharing require a confirmed fingerprint. Fake keys are never sent to a model service.');
  await request(alice, '/api/ai/settings', 'PUT', { ...config, baseUrl: 'https://other.example.com/v1', apiKey: '' }, 400);
  await request(alice, '/api/ai/settings', 'PUT', { ...config, baseUrl: 'http://localhost/v1' }, 400);
  await request(alice, '/api/ai/chat', 'POST', { messages: [{ role: 'system', content: 'invalid' }] }, 400);
  await request(bob, '/api/ai/settings', 'DELETE'); assert.equal(sql(`SELECT COUNT(*) FROM ai_user_setting WHERE uid=${quote(alice.uid)};`), '1');
  report.checks.push('Real Shiro rejects anonymous access; settings and deletion are user-scoped, keys are encrypted and never returned, unsafe URLs and key reuse on a new endpoint are rejected.');
  const cid = contest(); const pid = Number(sql('SELECT id FROM problem WHERE auth=1 AND is_group=0 LIMIT 1;')); assert(pid, 'Need an existing public programming problem');
  sql(`INSERT INTO contest_problem(cid,pid,display_id,display_title) VALUES(${cid},${pid},'A','temporary policy question');`);
  await request(bob, '/api/get-contest-problem?cid=' + cid); assert.equal((await request(bob, '/api/ai/settings')).contestBlocked, false);
  await request(teacher, `/api/get-contest-problem-details?cid=${cid}&displayId=A`); assert.equal((await request(teacher, '/api/ai/settings')).contestBlocked, false);
  for (let i = 0; i < 2; i++) await request(alice, `/api/get-contest-problem-details?cid=${cid}&displayId=A`);
  assert.equal(sql(`SELECT COUNT(*) FROM ai_contest_entry WHERE uid=${quote(alice.uid)} AND cid=${cid};`), '1');
  assert.equal((await request(alice, '/api/ai/settings')).contestBlocked, true);
  await request(alice, '/api/ai/chat', 'POST', { ...chat, uid: bob.uid, contestId: 0 }, 403);
  await request(bob, '/api/ai/chat', 'POST', chat, 400); // Not configured: policy admitted the unrelated account without contacting a provider.
  sql(`UPDATE contest SET end_time=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=${cid};`); assert.equal((await request(alice, '/api/ai/settings')).contestBlocked, false);
  report.checks.push('Opening a public contest statement establishes participation exactly once. Contest-list spectators and inspecting organizers remain available; participants cannot bypass via practice requests or forged uid/contestId and recover after the end.');
  const scheduled = contest(1, true); sql(`INSERT INTO contest_register(cid,uid,status) VALUES(${scheduled},${quote(alice.uid)},0);`);
  assert.equal((await request(alice, '/api/ai/settings')).contestBlocked, false);
  sql(`UPDATE contest SET start_time=DATE_SUB(NOW(),INTERVAL 1 HOUR) WHERE id=${scheduled};`); assert.equal((await request(alice, '/api/ai/settings')).contestBlocked, true);
  await request(alice, '/api/ai/chat', 'POST', chat, 403);
  sql(`UPDATE contest SET end_time=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=${scheduled};`);
  report.checks.push('A registered user is admitted before the start, blocked once the contest starts, and unrelated accounts stay available.');
  const question = await request(teacher, '/api/admin/objective/questions', 'POST', { title: 'AI policy temporary question', questionType: 'TRUE_FALSE', stem: '1+1=2', correctAnswers: ['TRUE'], explanation: '', tagIds: [], published: false, practiceEnabled: false });
  const paper = await request(teacher, '/api/admin/papers', 'POST', { title: 'AI policy temporary mixed paper', description: '', items: [{ kind: 'OBJECTIVE', referenceId: question.id, score: 10 }], programMode: 'LIVE', scoreRule: 'BEST', answerPolicy: 'NEVER', explanationPolicy: 'NEVER', published: false });
  const now = Date.now(), mixedTitle = 'temporary AI policy mixed ' + crypto.randomBytes(4).toString('hex');
  await request(teacher, '/api/admin/contest', 'POST', { title: mixedTitle, description: '', uid: teacher.uid, author: teacher.username, type: 0, auth: 0, startTime: new Date(now - 10000).toISOString(), endTime: new Date(now + 3600000).toISOString(), duration: 3610, visible: true, source: 0, sealRank: false, autoRealRank: true, openPrint: false, openAccountLimit: false, starAccount: [], awardType: 0, oiRankScoreType: 'Highest', rankShowName: 'username', openRank: false, mixed: { paperId: paper.id, scoreRule: 'BEST', answerPolicy: 'AFTER_END', explanationPolicy: 'AFTER_END' } });
  const mixed = Number(sql(`SELECT id FROM contest WHERE uid=${quote(teacher.uid)} AND title=${quote(mixedTitle)};`));
  const run = await request(bob, `/api/contests/${mixed}/mixed/run`, 'POST'); assert.equal((await request(bob, '/api/ai/settings')).contestBlocked, true);
  await request(bob, '/api/ai/chat', 'POST', chat, 403); await request(bob, '/api/papers/runs/' + run.id + '/finish', 'POST');
  assert.equal((await request(bob, '/api/ai/settings')).contestBlocked, true);
  sql(`UPDATE contest SET end_time=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=${mixed};`); assert.equal((await request(bob, '/api/ai/settings')).contestBlocked, false);
  report.checks.push('Starting a real mixed-contest answer sheet blocks all AI entrances, including after early hand-in, until the contest ends.');
  await request(alice, '/api/ai/settings', 'DELETE'); report.validated = true;
})().catch(error => { report.failure = error.stack; process.exitCode = 1; }).finally(async () => {
  try {
    for (const account of accounts) if (account.token) await request(account, '/api/logout').catch(() => {});
    sql(`DELETE FROM ai_user_setting WHERE uid IN (${uids});DELETE FROM ai_contest_entry WHERE uid IN (${uids});DELETE FROM paper_program_snapshot WHERE uid IN (${uids});DELETE FROM paper_run WHERE uid IN (${uids});DELETE FROM contest WHERE uid IN (${uids});DELETE FROM paper WHERE created_by IN (${uids});DELETE FROM objective_run WHERE uid IN (${uids});DELETE v FROM objective_question_version v JOIN objective_question q ON q.id=v.question_id WHERE q.created_by IN (${uids});DELETE FROM objective_question WHERE created_by IN (${uids});DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`), '0'); report.cleaned = true;
  } catch (error) { report.cleanupFailure = error.stack; process.exitCode = 1; }
  fs.writeFileSync(path.join(output, 'ai-local-integration.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
});
