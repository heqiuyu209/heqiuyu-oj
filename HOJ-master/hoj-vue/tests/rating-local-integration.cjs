// Opt-in local API/DB integration. Only generated test accounts and owned content are removed.
const assert = require('assert').strict;
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');
if (process.env.HOJ_LOCAL_RATING_TEST !== '1') throw Error('Set HOJ_LOCAL_RATING_TEST=1 for local integration.');
const base = 'http://127.0.0.1:6688', compose = path.resolve(__dirname, '../../compose.yaml');
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-rating-review');
fs.mkdirSync(output, { recursive: true });
const report = { checks: [], cleaned: false };
const accounts = Array.from({ length: 3 }, () => ({ uid: crypto.randomBytes(16).toString('hex'), username: 'rat_' + crypto.randomBytes(4).toString('hex'), password: crypto.randomBytes(8).toString('hex'), token: '' }));
const [teacher, a, b] = accounts;
const quote = s => "'" + String(s).replace(/'/g, "''") + "'", uids = accounts.map(a => quote(a.uid)).join(',');
let questionId, paperId, contestId, programId;
function sql(input) { return execFileSync('docker', ['compose', '-p', 'hoj-master', '-f', compose, 'exec', '-T', 'hoj-mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'], { input, encoding: 'utf8', windowsHide: true, timeout: 30000 }).trim(); }
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function request(account, endpoint, method = 'GET', data, denied = false) {
  const response = await fetch(base + endpoint, { method, headers: { 'Content-Type': 'application/json', ...(account && account.token ? { Authorization: account.token } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(20000) });
  const body = await response.json(); if (account && response.headers.get('authorization')) account.token = response.headers.get('authorization');
  if (denied) { assert.notEqual(body.status, 200, endpoint + ' must deny'); return body; }
  assert.equal(body.status, 200, endpoint + ': ' + body.msg); return body.data;
}
async function until(check, label, timeout = 100000) { const end = Date.now() + timeout; while (Date.now() < end) { const value = await check(); if (value) return value; await sleep(1500); } throw Error('Timeout: ' + label); }
async function practice(account, answer) {
  const run = await request(account, '/api/objective/runs', 'POST', { questionId, requestId: crypto.randomBytes(16).toString('hex') });
  return request(account, '/api/objective/runs/' + run.id + '/answers', 'PUT', { questionId, selectedAnswers: [answer] });
}
(async () => {
  for (let index = 0; index < accounts.length; index++) {
    const account = accounts[index], hash = crypto.createHash('md5').update(account.password).digest('hex');
    sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(account.uid)},${quote(account.username)},${quote(hash)}); INSERT INTO user_record(uid) VALUES(${quote(account.uid)}); INSERT INTO user_role(uid,role_id) SELECT ${quote(account.uid)},id FROM role WHERE role IN (${index === 0 ? "'default_user','problem_admin'" : "'default_user'"});`);
    await request(account, '/api/login', 'POST', { username: account.username, password: account.password }); assert(account.token);
  }
  const question = await request(teacher, '/api/admin/objective/questions', 'POST', { title: 'Rating 临时选择题', questionType: 'SINGLE_CHOICE', stem: '选择 A', source: '临时验证', options: [{ id: 'A', text: '正确' }, { id: 'B', text: '错误' }], blanks: [], correctAnswers: ['A'], explanation: 'A', tagIds: [], published: true, practiceEnabled: true, answerPolicy: 'AFTER_ANSWER', explanationPolicy: 'AFTER_ANSWER', rating: 1200 });
  questionId = question.id; assert.equal(question.rating, 1200);
  await request(teacher, '/api/admin/objective/questions', 'PUT', { ...question, rating: 5001 }, true);
  await practice(a, 'B'); await practice(a, 'A'); await practice(a, 'A'); await practice(b, 'A');
  programId = Number(sql(`INSERT INTO problem(problem_id,title,author,type,auth,is_group,rating) VALUES(${quote('RATING_' + crypto.randomBytes(4).toString('hex'))},'Rating 临时编程题',${quote(teacher.username)},0,1,0,1600); SELECT LAST_INSERT_ID();`));
  const tasks = await request(null, '/api/get-problem-list?oj=Mine&ratingMin=1500&ratingMax=1700&limit=100&currentPage=1');
  assert(tasks.records.some(row => row.pid === programId && row.rating === 1600)); assert(tasks.records.every(row => row.rating >= 1500 && row.rating <= 1700));
  const paper = await request(teacher, '/api/admin/papers', 'POST', { title: 'Rating 临时试卷', description: '', published: true, programMode: 'LIVE', scoreRule: 'BEST', answerPolicy: 'AFTER_FINISH', explanationPolicy: 'AFTER_FINISH', items: [{ kind: 'OBJECTIVE', referenceId: questionId, score: 10 }] });
  paperId = paper.id;
  const title = 'Rating 临时计分赛 ' + crypto.randomBytes(4).toString('hex');
  const data = { title, description: '临时回归，自动清理', uid: teacher.uid, author: teacher.username, type: 0, auth: 0, startTime: new Date(Date.now() - 10000).toISOString(), endTime: new Date(Date.now() + 18000).toISOString(), duration: 28, rated: true, visible: true, source: 0, sealRank: false, autoRealRank: true, openPrint: false, openAccountLimit: false, allowEndSubmit: false, starAccount: [], awardType: 0, oiRankScoreType: 'Highest', rankShowName: 'username', openRank: true, mixed: { paperId, scoreRule: 'BEST', answerPolicy: 'AFTER_END', explanationPolicy: 'AFTER_END' } };
  await request(teacher, '/api/admin/contest', 'POST', data);
  contestId = Number(sql(`SELECT id FROM contest WHERE uid=${quote(teacher.uid)} AND title=${quote(title)}`)); assert(contestId > 0);
  const admin = await request(teacher, '/api/admin/contest?cid=' + contestId); assert.equal(admin.rated, true);
  await request(teacher, '/api/admin/contest', 'PUT', { ...admin, rated: false }, true);
  const runA = await request(a, '/api/contests/' + contestId + '/mixed/run', 'POST');
  const runB = await request(b, '/api/contests/' + contestId + '/mixed/run', 'POST');
  await request(a, '/api/papers/runs/' + runA.id + '/answers', 'PUT', { key: 'OBJECTIVE:' + questionId, selectedAnswers: ['A'] });
  await request(b, '/api/papers/runs/' + runB.id + '/answers', 'PUT', { key: 'OBJECTIVE:' + questionId, selectedAnswers: ['B'] });
  // Leave both sheets open: the deadline job must finish and score them without browser activity.
  const result = await until(async () => { const profile = await request(null, '/api/rating/users/' + a.uid); return profile.contestRating !== null ? profile : false; }, 'scheduled contest settlement');
  const other = await request(null, '/api/rating/users/' + b.uid);
  assert(!Object.keys(result).some(key=>key.startsWith('practice')));assert(!Object.keys(other).some(key=>key.startsWith('practice')));
  assert.equal(sql(`SELECT COUNT(*) FROM site_rating_profile WHERE uid IN (${uids}) AND type='practice'`),'0');
  assert(result.contestRating > 1400); assert(other.contestRating < 1400);
  assert.equal(result.contestCount, 1); assert.equal(other.contestCount, 1);
  const home = await request(null, '/api/get-user-home-info?uid=' + a.uid); assert(!Object.hasOwn(home,'practiceRating')); assert.equal(home.contestRating, result.contestRating);
  const rank = await request(null, '/api/rating/rank?type=contest&searchUser=' + a.username); assert.equal(rank.records.length, 1); assert.equal(rank.records[0].rating, result.contestRating);
  const typed=await request(null,'/api/rating/users/'+a.uid+'?type=contest');assert.equal(typed.history.length,1);assert.equal(typed.history[0].type,'contest');assert.deepEqual(result.history,typed.history);
  await request(null,'/api/rating/rank?type=practice','GET',undefined,true);await request(null,'/api/rating/users/'+a.uid+'?type=practice','GET',undefined,true);
  await request(null, '/api/rating/users/' + a.uid + '?type=invalid', 'GET', undefined, true);
  const finalRank = await request(a, '/api/contests/' + contestId + '/mixed/rank'); assert.equal(finalRank.records.find(row => row.uid === a.uid).objectiveScore, 10);
  report.checks.push('Admin task rating and interval filters remain available; repeated practice creates no practice Rating. Formal contest gains/losses, automatic deadline hand-in, contest-only history, rejected retired/invalid types, public rank and user-home integration remain correct.');
  report.ratings = { winner: result.contestRating, loser: other.contestRating };
  report.validated = true;
})().catch(error => { report.failure = error.stack; process.exitCode = 1; }).finally(async () => {
  try {
    for (const account of accounts) if (account.token) await request(account, '/api/logout').catch(() => {});
    sql(`START TRANSACTION;SELECT id FROM site_rating_lock WHERE id=1 FOR UPDATE;DELETE FROM site_rating_history WHERE uid IN (${uids}); DELETE FROM site_rating_profile WHERE uid IN (${uids}); DELETE FROM paper_run WHERE uid IN (${uids}); DELETE FROM objective_run WHERE uid IN (${uids}); ${contestId ? `DELETE FROM contest WHERE id=${contestId} AND uid=${quote(teacher.uid)};` : ''} ${paperId ? `DELETE FROM paper WHERE id=${paperId} AND created_by=${quote(teacher.uid)};` : ''} ${questionId ? `DELETE FROM objective_question_version WHERE question_id=${questionId}; DELETE FROM objective_question WHERE id=${questionId} AND created_by=${quote(teacher.uid)};` : ''} ${programId ? `DELETE FROM problem WHERE id=${programId} AND author=${quote(teacher.username)};` : ''} DELETE FROM session WHERE uid IN (${uids}); DELETE FROM user_info WHERE uuid IN (${uids});COMMIT;`);
    assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids})`), '0'); report.cleaned = true;
  } catch (error) { report.cleanupFailure = error.message; process.exitCode = 1; }
  fs.writeFileSync(path.join(output, 'rating-local-integration.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
});
