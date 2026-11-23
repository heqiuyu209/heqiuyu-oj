// Opt-in local end-to-end test. Cleans only generated accounts, contests and their runs.
const assert = require('assert').strict;
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
if (process.env.HOJ_LOCAL_MIXED_CONTEST_TEST !== '1') throw Error('Set HOJ_LOCAL_MIXED_CONTEST_TEST=1 for local integration.');
const base = 'http://127.0.0.1:6688', compose = path.resolve(__dirname, '../../compose.yaml');
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-mixed-contest-review');
fs.mkdirSync(output, { recursive: true });
const report = { checks: [], cleaned: false }, ids = [];
const accounts = [0, 1].map(() => ({ uid: crypto.randomBytes(16).toString('hex'), username: 'mix_' + crypto.randomBytes(4).toString('hex'), password: crypto.randomBytes(8).toString('hex'), token: '' }));
const [teacher, student] = accounts;
const quote = s => "'" + String(s).replace(/'/g, "''") + "'", uids = accounts.map(a => quote(a.uid)).join(',');
function sql(input) { return execFileSync('docker', ['compose', '-p', 'hoj-master', '-f', compose, 'exec', '-T', 'hoj-mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'], { input, encoding: 'utf8', windowsHide: true, timeout: 30000 }).trim(); }
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function waitForFixtureSubmitSlot(account) {
  // Observe only this generated user's existing limiter; never clear or shorten it.
  for (;;) {
    const raw=execFileSync('docker',['compose','-p','hoj-master','-f',compose,'exec','-T','hoj-redis','redis-cli','--raw','GET','submit_non_contest_lock:'+account.uid],{encoding:'utf8',windowsHide:true,timeout:10000}).trim();
    if(!raw)return;const next=Number(JSON.parse(raw));assert(Number.isFinite(next),'Expected a numeric JSON fixture submit deadline');
    const delay=next-Date.now()+250;if(delay<=0)return;await sleep(Math.min(delay,30000));
  }
}
async function request(account, endpoint, method = 'GET', data, expected = 200) {
  const response = await fetch(base + endpoint, { method, headers: { 'Content-Type': 'application/json', ...(account && account.token ? { Authorization: account.token } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(20000) });
  const body = await response.json(); if (account && response.headers.get('authorization')) account.token = response.headers.get('authorization');
  if (expected === 'denied') { assert.notEqual(body.status, 200, endpoint + ' must deny'); return body; }
  assert.equal(body.status, expected, endpoint + ': ' + body.msg); return body.data;
}
async function until(check, label, timeout = 90000) { const end = Date.now() + timeout; while (Date.now() < end) { const result = await check(); if (result) return result; await sleep(1000); } throw Error('Timeout: ' + label); }
async function create(type, seconds = 3600, auth = 0) {
  const title = '混合赛临时回归 ' + crypto.randomBytes(5).toString('hex');
  const data = { title, description: '临时测试，验证后自动清理', uid: teacher.uid, author: teacher.username, type, auth, pwd: auth ? 'mixed_test_password' : '', startTime: new Date(Date.now() - 10000).toISOString(), endTime: new Date(Date.now() + seconds * 1000).toISOString(), duration: seconds + 10, visible: true, source: 0, sealRank: false, autoRealRank: true, openPrint: false, openAccountLimit: false, starAccount: [], awardType: 0, oiRankScoreType: 'Highest', rankShowName: 'username', openRank: false, mixed: { paperId: 3, scoreRule: 'BEST', answerPolicy: 'AFTER_END', explanationPolicy: 'AFTER_END' } };
  await request(teacher, '/api/admin/contest', 'POST', data);
  const id = Number(sql(`SELECT id FROM contest WHERE uid=${quote(teacher.uid)} AND title=${quote(title)}`)); assert(id > 0); ids.push(id); return id;
}
(async () => {
  assert.equal(sql("SELECT COUNT(*) FROM paper WHERE id=3"), '1', 'Requires the local Lanqiao demo paper.');
  for (let i = 0; i < accounts.length; i++) {
    const a = accounts[i], hash = crypto.createHash('md5').update(a.password).digest('hex');
    sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(a.uid)},${quote(a.username)},${quote(hash)}); INSERT INTO user_record(uid) VALUES(${quote(a.uid)}); INSERT INTO user_role(uid,role_id) SELECT ${quote(a.uid)},id FROM role WHERE role IN (${i === 0 ? "'default_user','problem_admin'" : "'default_user'"});`);
    await request(a, '/api/login', 'POST', { username: a.username, password: a.password }); assert(a.token);
  }
  const acm = await create(0);
  await request(null, '/api/contests/' + acm + '/mixed/run', 'POST', undefined, 'denied');
  const parallel = await Promise.all([request(student, '/api/contests/' + acm + '/mixed/run', 'POST'), request(student, '/api/contests/' + acm + '/mixed/run', 'POST')]);
  const run = parallel[0]; assert.equal(run.id, parallel[1].id); assert.equal(run.items.length, 7); assert.equal(run.programMode, 'LIVE');
  const choice = run.items.find(q => q.questionType === 'SINGLE_CHOICE'), program = run.items.find(q => q.kind === 'PROGRAMMING');
  const prefix = '/api/papers/runs/' + run.id;
  await request(student, prefix + '/answers', 'PUT', { key: choice.key, selectedAnswers: ['B'] });
  const code = '#include <iostream>\nint main(){long long a,b;std::cin>>a>>b;std::cout<<a+b;}', nonce = crypto.randomBytes(16).toString('hex');
  await request(student, prefix + '/program-submissions', 'POST', { key: program.key, language: 'C++', code, requestId: nonce });
  await request(student, prefix + '/program-submissions', 'POST', { key: program.key, language: 'C++', code, requestId: nonce });
  const judged = await until(async () => { const r = await request(student, prefix); return r.gradingComplete ? r : false; }, 'real contest programming judge');
  const result = judged.items.find(q => q.key === program.key); assert.equal(result.submissionCount, 1); assert.equal(result.submissions[0].status, 0); assert.equal(result.score, 25);
  const finished = await request(student, prefix + '/finish', 'POST'); assert.equal(finished.score, 35); assert(!finished.items.find(q => q.key === choice.key).correctAnswers);
  await request(teacher, prefix, 'GET', undefined, 'denied');
  await request(student, '/api/get-submission-detail?submitId=' + result.submissions[0].id, 'GET', undefined, 'denied');
  assert.equal((await request(student, '/api/papers/runs?currentPage=1')).total, 0);
  const rank = await request(student, '/api/contests/' + acm + '/mixed/rank'); assert.equal(rank.records.length, 1); assert.equal(rank.records[0].objectiveScore, 10); assert.equal(rank.records[0].ac, 1);
  report.checks.push('Real mixed ACM: concurrent start idempotent, seven frozen questions, immediate AC once, objective-first standings, hidden solutions and isolated ordinary submissions/history.');
  const admin = await request(teacher, '/api/admin/contest?cid=' + acm); admin.type = 1; await request(teacher, '/api/admin/contest', 'PUT', admin, 'denied');
  const oi = await create(1, 35, 2);
  await request(student, '/api/contests/' + oi + '/mixed/run', 'POST', undefined, 'denied');
  await request(student, '/api/register-contest', 'POST', { cid: oi, password: 'wrong' }, 'denied');
  await request(student, '/api/register-contest', 'POST', { cid: oi, password: 'mixed_test_password' });
  const timed = await request(student, '/api/contests/' + oi + '/mixed/run', 'POST');
  const timedChoice = timed.items.find(q => q.questionType === 'SINGLE_CHOICE');
  await request(student, '/api/papers/runs/' + timed.id + '/answers', 'PUT', { key: timedChoice.key, selectedAnswers: ['B'] });
  await until(() => sql(`SELECT state FROM paper_run WHERE id=${timed.id}`) === 'FINISHED', 'server deadline job without browser', 50000);
  await request(student, '/api/papers/runs/' + timed.id + '/answers', 'PUT', { key: timedChoice.key, selectedAnswers: ['A'] }, 'denied');
  const ended = await request(student, '/api/papers/runs/' + timed.id); assert.equal(ended.score, 10); assert(ended.items.find(q => q.key === timedChoice.key).correctAnswers);
  const oiRank = await request(student, '/api/contests/' + oi + '/mixed/rank'); assert.equal(oiRank.records[0].score, 10); assert.equal(oiRank.type, 1);
  report.checks.push('Real protected OI: registration enforced, server deadline job locks and hands in without browser activity, later writes denied, solutions released after end and total-score ranking correct.');
  const practice = await request(student, '/api/papers/runs', 'POST', { paperId: 3, requestId: crypto.randomBytes(16).toString('hex') }); assert.notEqual(practice.id, run.id); assert(!practice.contestId);
  report.checks.push('The same paper remains available for independent daily practice.');
  if (process.env.HOJ_LOCAL_MIXED_VIRTUAL_TEST === '1') {
    // Move only this fixture's original clock into the past, preserving its original submissions.
    sql(`UPDATE contest SET start_time=DATE_SUB(NOW(),INTERVAL 1 HOUR),end_time=DATE_SUB(NOW(),INTERVAL 1 SECOND),status=1 WHERE id=${acm} AND uid=${quote(teacher.uid)};`);
    const original = await request(student,prefix), originalRank=(await request(student,'/api/contests/'+acm+'/mixed/rank')).records;
    const key=crypto.randomBytes(16).toString('hex'),virtualUrl='/api/contests/'+acm+'/virtual-run';
    const started=await Promise.all([request(student,virtualUrl,'POST',{requestId:key}),request(student,virtualUrl,'POST',{requestId:key})]);
    const personal=started[0].run;assert.equal(personal.kind,'MIXED');assert.equal(personal.paperRunId,started[1].run.paperRunId);assert.notEqual(personal.paperRunId,run.id);assert.notEqual(personal.paperRunId,practice.id);
    const virtualPrefix='/api/papers/runs/'+personal.paperRunId;let virtual=await request(student,virtualPrefix);assert.equal(virtual.virtual,true);assert.equal(virtual.endTime,personal.endsAt);assert.equal(virtual.items.length,7);
    assert.equal(sql(`SELECT COUNT(*) FROM paper_run WHERE virtual_run_id=${personal.id} AND contest_id IS NULL;`),'1');await request(teacher,virtualPrefix,'GET',undefined,'denied');
    report.checks.push('Real mixed virtual start atomically creates one independent frozen answer sheet despite parallel retries; formal and practice answer sheets and ownership remain separate.');
    const virtualChoice=virtual.items.find(q=>q.key===choice.key),virtualProgram=virtual.items.find(q=>q.key===program.key);
    await request(student,virtualPrefix+'/answers','PUT',{key:virtualChoice.key,selectedAnswers:['B']});
    for(const [label,source,expected] of [['AC',code,0],['WA','#include <iostream>\nint main(){std::cout<<0;}',-1],['TLE','int main(){volatile long long n=0;while(true){n++;}}',1]]){
      await waitForFixtureSubmitSlot(student);const requestId=crypto.randomBytes(16).toString('hex');await request(student,virtualPrefix+'/program-submissions','POST',{key:virtualProgram.key,language:'C++',code:source,requestId});
      virtual=await until(async()=>{const r=await request(student,virtualPrefix);const q=r.items.find(i=>i.key===virtualProgram.key);return q.submissions.length&&q.submissions[q.submissions.length-1].status===expected?r:false;},'real virtual '+label);
    }
    assert.deepEqual(virtual.items.find(q=>q.key===virtualProgram.key).submissions.map(s=>s.status),[0,-1,1]);
    const ended=await request(student,virtualPrefix+'/finish','POST');assert.equal(ended.score,35);assert(!ended.items.find(q=>q.key===virtualChoice.key).correctAnswers);assert.equal((await request(student,'/api/ai/settings')).contestBlocked,true);
    await request(student,virtualPrefix+'/answers','PUT',{key:virtualChoice.key,selectedAnswers:['A']},'denied');
    assert.deepEqual((await request(student,prefix)).items.find(q=>q.key===choice.key).selectedAnswers,original.items.find(q=>q.key===choice.key).selectedAnswers);
    assert.deepEqual((await request(student,'/api/contests/'+acm+'/mixed/rank')).records,originalRank);assert.equal((await request(student,'/api/papers/runs?currentPage=1')).total,1);
    report.checks.push('Real Java 8 virtual judging returns AC/WA/TLE, preserves BEST scoring, locks early hand-in and leaves formal standings and practice history unchanged; early hand-in does not release solutions or AI.');
    sql(`UPDATE contest_virtual_run SET started_at=DATE_SUB(NOW(3),INTERVAL 2 HOUR),ends_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=${personal.id} AND uid=${quote(student.uid)};`);
    assert((await request(student,virtualPrefix)).items.find(q=>q.key===virtualChoice.key).correctAnswers);assert.equal((await request(student,'/api/ai/settings')).contestBlocked,false);
    const policy=await request(teacher,'/api/admin/contest?cid='+acm);policy.mixed={paperId:3,scoreRule:'BEST',answerPolicy:'NEVER',explanationPolicy:'NEVER'};await request(teacher,'/api/admin/contest','PUT',policy);assert(!(await request(student,virtualPrefix)).items.find(q=>q.key===virtualChoice.key).correctAnswers);
    policy.mixed.answerPolicy='AFTER_END';policy.mixed.explanationPolicy='AFTER_END';await request(teacher,'/api/admin/contest','PUT',policy);
    const next=(await request(student,virtualUrl,'POST',{requestId:crypto.randomBytes(16).toString('hex')})).run;assert.notEqual(next.paperRunId,personal.paperRunId);
    await request(student,'/api/papers/runs/'+next.paperRunId+'/answers','PUT',{key:virtualChoice.key,selectedAnswers:['B']});
    sql(`UPDATE contest_virtual_run SET started_at=DATE_SUB(NOW(3),INTERVAL 2 HOUR),ends_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=${next.id} AND uid=${quote(student.uid)};`);
    await until(()=>sql(`SELECT state FROM paper_run WHERE id=${next.paperRunId}`)==='FINISHED','virtual deadline job without a browser',15000);
    await request(student,'/api/papers/runs/'+next.paperRunId+'/answers','PUT',{key:virtualChoice.key,selectedAnswers:['A']},'denied');
    assert((await request(student,'/api/papers/runs/'+next.paperRunId)).items.find(q=>q.key===virtualChoice.key).correctAnswers);
    report.checks.push('Personal expiry restores feedback and AI; current administrative NEVER revokes feedback, an explicit new round gets new snapshots, and the deadline job finishes it without a browser.');
    report.virtualExpiryFixtureSimulated=true;report.realRemoteSourceCodePosted=false;report.realModelCalled=false;
  }
  report.validated = true;
  if (process.env.HOJ_SEED_MIXED_DEMO === '1' && !Number(sql("SELECT COUNT(*) FROM contest WHERE title='蓝桥杯风格 · 混合赛（演示）'"))) {
    const demo = await create(0, 7 * 86400);
    sql(`UPDATE contest SET title='蓝桥杯风格 · 混合赛（演示）',description='自编蓝桥杯风格演示，非官方真题。选择、判断、填空与编程题混合；先按客观题得分，再按编程 AC 数与罚时排名。',uid='1',author='root' WHERE id=${demo} AND uid=${quote(teacher.uid)};`);
    ids.splice(ids.indexOf(demo), 1); report.demoContestId = demo;
  }
})().catch(e => { report.failure = e.stack; process.exitCode = 1; }).finally(async () => {
  try {
    await until(() => Number(sql(`SELECT COUNT(*) FROM judge WHERE uid IN (${uids}) AND status IN (5,6,7,9,15)`)) === 0, 'test submissions drain before cleanup', 60000);
    for (const account of accounts) if (account.token) await request(account, '/api/logout').catch(() => {});
    sql(`DELETE FROM judge WHERE uid IN (${uids}); DELETE FROM paper_program_snapshot WHERE uid IN (${uids}); DELETE FROM paper_run WHERE uid IN (${uids}); ${ids.length ? `DELETE FROM contest WHERE id IN (${ids.join(',')}) AND uid=${quote(teacher.uid)};` : ''} DELETE FROM session WHERE uid IN (${uids}); DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids})`), '0'); report.cleaned = true;
  } catch (e) { report.cleanupFailure = e.message; process.exitCode = 1; }
  fs.writeFileSync(path.join(output, 'mixed-contest-local-integration.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
});
