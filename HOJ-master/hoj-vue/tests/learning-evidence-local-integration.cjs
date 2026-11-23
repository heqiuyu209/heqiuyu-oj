// LOCAL opt-in only. Synthetic verified rows test aggregation, not real platform ownership.
// Source jobs have no due time. This script never logs in to or submits code to an external OJ.
const assert = require('assert').strict, crypto = require('crypto'), fs = require('fs'), path = require('path'), os = require('os');
const { execFileSync } = require('child_process');
if (process.env.HOJ_LOCAL_LEARNING_EVIDENCE_TEST !== '1') throw Error('Set HOJ_LOCAL_LEARNING_EVIDENCE_TEST=1.');
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-learning-evidence-http');
fs.mkdirSync(output, { recursive: true });
const suffix = crypto.randomBytes(5).toString('hex');
const users = ['a', 'b'].map(n => ({ uid: crypto.randomBytes(16).toString('hex'), username: 'ev_it_' + n + suffix, password: crypto.randomBytes(8).toString('hex') }));
const [alice, bob] = users, author = bob.username;
const contest = String(90000000 + crypto.randomInt(9000000)), native = String(940000000000 + crypto.randomInt(1000000000));
const cf = 'cf:contest:' + contest + ':A', team = 'cf:contest:' + contest + ':B', unverified = 'cf:contest:' + contest + ':C';
const ordinary = 'nowcoder:acm:' + native, ncContest = 'nowcoder:contest:' + contest + ':A', at = 'atcoder:task:abc086_a';
const ownedKeys = [cf, team, unverified, ordinary, ncContest];
const accounts = [], pids = [], createdExternal = [], createdCatalogue = [], report = { checks: [], cleaned: false, realPlatformAuthorization: false, realSourceCodePosted: false };
const q = value => "'" + String(value).replace(/'/g, "''") + "'", uids = users.map(u => q(u.uid)).join(','), keys = ownedKeys.map(q).join(',');
function sql(input) { return execFileSync('docker', ['compose', '-p', 'hoj-master', '-f', path.resolve(__dirname, '../../compose.yaml'), 'exec', '-T', 'hoj-mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'], { input, encoding: 'utf8', windowsHide: true, timeout: 30000 }).trim(); }
async function request(user, url, method = 'GET', data, status = 200) {
  const r = await fetch('http://127.0.0.1:6688' + url, { method, headers: { 'Content-Type': 'application/json', ...(user?.token ? { Authorization: user.token } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(30000) });
  const body = await r.json(); if (user && r.headers.get('Authorization')) user.token = r.headers.get('Authorization');
  assert.equal(body.status, status, url + ': ' + body.msg); if (url.endsWith('/evidence')) assert.equal(r.headers.get('Cache-Control'), 'no-store'); return body.data;
}
const endpoint = '/api/learning/users/' + alice.uid;
const evidence = () => request(null, endpoint + '/evidence');
const source = (view, code) => view.sources.find(s => s.source === code);
const overall = view => view.overall;
function account(user, platform, verified, sequence) {
  const handle = platform === 'NOWCODER' ? native : 'fixture_' + suffix + sequence;
  const ncJobs = platform === 'NOWCODER' ? `INSERT INTO external_nowcoder_profile(account_id,display_name) VALUES(@fixture_account,'Synthetic local account');INSERT INTO external_nowcoder_context_job(account_id,scan_token,next_run_at) VALUES(@fixture_account,${q(suffix)},'2999-01-01');INSERT INTO external_nowcoder_practice_job(account_id,scan_token,next_run_at) VALUES(@fixture_account,${q(suffix)},'2999-01-01');` : '';
  const id = Number(sql(`START TRANSACTION;INSERT INTO external_account(uid,platform,handle,handle_key,verified_identity,history_complete,last_success_at,last_requested_at,created_at,proof_code) VALUES(${q(user.uid)},${q(platform)},${q(handle)},${q(handle)},${verified ? q(handle) : 'NULL'},1,NOW(3),NOW(3),NOW(3),'PRIVATE_SYNTHETIC_PROOF');SET @fixture_account=LAST_INSERT_ID();INSERT INTO external_sync_job(account_id,scan_token,stage,next_run_at) VALUES(@fixture_account,${q(suffix)},'DONE',NULL);${ncJobs}SELECT @fixture_account;COMMIT;`)); accounts.push(id);
  return id;
}
function submission(id, sid, key, individual, accepted = true) { sql(`INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(${id},${sid},${q(key)},${q(accepted ? 'OK' : 'WRONG_ANSWER')},1700000000,${individual ? 1 : 0},${q(suffix)});`); }
(async () => {
  assert.equal(sql(`SELECT COUNT(*) FROM external_problem WHERE problem_key IN (${keys});`), '0', 'Existing metadata must be preserved.');
  assert.equal(sql(`SELECT COUNT(*) FROM remote_nowcoder_problem WHERE problem_key IN (${q(ordinary)},${q(ncContest)});`), '0', 'Existing catalogue rows must be preserved.');
  assert.equal(sql(`SELECT COUNT(*) FROM problem WHERE problem_id IN (${['CF-' + contest + 'A', 'AC-ABC086_A', 'NC-' + contest + '-A'].map(q).join(',')});`), '0', 'Existing local problems must be preserved.');
  assert.equal(sql(`SELECT COUNT(*) FROM external_atcoder_problem WHERE problem_key=${q(at)} AND available=1;`), '1', 'The existing trusted AtCoder catalogue is required.');
  assert.equal(sql(`SELECT COUNT(*) FROM external_problem WHERE problem_key=${q(at)};`), '1');
  for (const u of users) {
    sql(`INSERT INTO user_info(uuid,username,password) VALUES(${q(u.uid)},${q(u.username)},${q(crypto.createHash('md5').update(u.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${q(u.uid)});INSERT INTO user_role(uid,role_id) SELECT ${q(u.uid)},id FROM role WHERE role='default_user';`);
    await request(u, '/api/login', 'POST', { username: u.username, password: u.password });
  }
  let view = await evidence(); assert.equal(view.combined.overall.attempted, 0); assert.equal(view.crossPlatformDeduplicated, true);
  await request(null, '/api/learning/users/missing-source-fixture/evidence', 'GET', undefined, 404);
  report.checks.push('Anonymous evidence uses no-store, rejects unavailable users and starts empty; synthetic ownership is not treated as real platform verification.');
  const aCf = account(alice, 'CODEFORCES', true, 1), aCf2 = account(alice, 'CODEFORCES', true, 2), aAt = account(alice, 'ATCODER', true, 3), aNc = account(alice, 'NOWCODER', true, 4), aPrivate = account(alice, 'CODEFORCES', false, 5), bCf = account(bob, 'CODEFORCES', true, 6);
  for (const [key, platform, rating, tags] of [[cf, 'CODEFORCES', 800, '["math"]'], [team, 'CODEFORCES', 3000, '["graphs"]'], [unverified, 'CODEFORCES', 9000, '["dp"]'], [ordinary, 'NOWCODER', null, '[]'], [ncContest, 'NOWCODER', null, '[]']]) { sql(`INSERT INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(${q(key)},${q(platform)},'Synthetic local evidence',${rating === null ? 'NULL' : rating},${q(tags)},NOW(3));`); createdExternal.push(key); }
  submission(aCf, 1, cf, true); submission(aCf, 2, cf, true, false); submission(aCf, 3, team, false); submission(aCf2, 1, cf, true); submission(aAt, 1, at, false); submission(aNc, 1, ordinary, false); submission(aNc, 2, ncContest, false); submission(aPrivate, 1, unverified, true); submission(bCf, 1, unverified, true);
  for (const key of [ordinary, ncContest]) { sql(`INSERT INTO remote_nowcoder_problem(problem_key,native_problem_id,question_id,title,source_rating,content_hash,last_seen_at,changed_at) VALUES(${q(key)},${native},'998877','Synthetic local catalogue',${key === ordinary ? 1250 : 'NULL'},${q(crypto.createHash('sha256').update(key).digest('hex'))},NOW(3),NOW(3));`); createdCatalogue.push(key); }
  const topics = await request(null, '/api/learning/topics'), math = topics.topics.find(t => t.groupCode === 'math'); assert(math?.tagId);
  for (const [display, tag] of [['CF-' + contest + 'A', true], ['AC-ABC086_A', false], ['NC-' + contest + '-A', true]]) {
    const pid = Number(sql(`INSERT INTO problem(problem_id,title,author,auth,is_remote,rating) VALUES(${q(display)},'Synthetic local practice',${q(author)},1,1,2100);SELECT LAST_INSERT_ID();`)); pids.push(pid);
    if (tag) sql(`INSERT INTO problem_tag(pid,tid) VALUES(${pid},${math.tagId});`);
    sql(`INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code) VALUES(${pid},${q(display)},${q(alice.uid)},${q(alice.username)},NOW(),0,'Local final-record fixture; never dispatched');`);
  }
  view = await evidence(); assert.equal(view.combined.overall.solved, 3); assert.equal(view.combined.overall.duplicatesRemoved, 5); assert.equal(view.combined.overall.representativeRating, null);
  assert.equal(source(view, 'CODEFORCES').overall.solved, 1); assert.equal(source(view, 'CODEFORCES').overall.representativeRating, 800); assert.equal(source(view, 'CODEFORCES').verifiedAccounts, 2);
  assert.equal(source(view, 'NOWCODER').overall.solved, 1); assert.equal(source(view, 'NOWCODER').overall.representativeRating, 1250); assert.equal(source(view, 'ATCODER').overall.solved, 1); assert.equal(source(view, 'ATCODER').coverageAvailable, false);
  assert.equal(source(view, 'HOJ').overall.representativeRating, 2100); assert.equal(JSON.stringify(view).includes('PRIVATE_SYNTHETIC_PROOF'), false); assert.equal(JSON.stringify(view).includes('correctAnswers'), false);
  const old = await request(null, endpoint + '/profile'); assert.equal(old.overall.solved, 3); assert.equal(old.crossPlatformDeduplicated, false); assert.equal(old.sources, undefined);
  const summary = (await request(null, endpoint + '/external')).summary; assert.equal(summary.uniqueSolved, 4); assert.equal(summary.duplicatesRemoved, 5);
  report.checks.push('Real MySQL merges local/CF duplicates, repeated verified accounts, known AtCoder case and official-native NC aliases; CF team-only records remain in account totals but not personal ability.');
  report.checks.push('Source difficulty remains 2100 local / 800 CF / 1250 NC; combined percentile is null, missing AT taxonomy is explicit, proof/answer content is absent and the original local AI profile contract is unchanged.');
  if (process.env.HOJ_LOCAL_EVIDENCE_BROWSER_SCRIPT) {
    execFileSync(process.execPath, [process.env.HOJ_LOCAL_EVIDENCE_BROWSER_SCRIPT], { input: JSON.stringify({ uid: alice.uid, username: alice.username, token: alice.token, output }), encoding: 'utf8', windowsHide: true, timeout: 120000 });
    report.browser = JSON.parse(fs.readFileSync(path.join(output, 'learning-evidence-real-browser.json'), 'utf8'));
    assert.equal(report.browser.validated, true);
  }
  sql(`UPDATE external_sync_job SET failures=1 WHERE account_id=${aCf};`); view = await evidence(); assert.equal(source(view, 'CODEFORCES').historyComplete, true); assert.equal(source(view, 'CODEFORCES').stale, true);
  sql(`UPDATE external_sync_job SET failures=0 WHERE account_id=${aCf};UPDATE external_account SET history_complete=0 WHERE id=${aCf};`); view = await evidence(); assert.equal(source(view, 'CODEFORCES').historyComplete, false);
  sql(`UPDATE external_account SET history_complete=1 WHERE id=${aCf};`);
  report.checks.push('A failed refresh retains already complete cached history and marks stale; a genuinely unfinished history remains distinct.');
  sql(`UPDATE external_account SET verified_identity=NULL WHERE id IN (${aCf},${aCf2});`); view = await evidence(); assert.equal(source(view, 'CODEFORCES').overall.attempted, 0); assert.equal(view.combined.overall.solved, 3);
  sql(`UPDATE external_account SET verified_identity=handle_key WHERE id IN (${aCf},${aCf2});UPDATE external_submission SET verdict='WRONG_ANSWER' WHERE account_id IN (${aCf},${aCf2}) AND problem_key=${q(cf)};UPDATE judge SET status=-1 WHERE uid=${q(alice.uid)} AND pid=${pids[0]};`); view = await evidence(); assert.equal(view.combined.overall.solved, 2); assert.equal(source(view, 'CODEFORCES').overall.solved, 0);
  report.checks.push('Revoking verification immediately removes external contribution without hiding valid local practice; rejudged AC-to-WA records remove solved evidence.');
  sql(`UPDATE remote_nowcoder_problem SET question_id=NULL WHERE problem_key=${q(ncContest)};`); view = await evidence(); assert.equal(view.combined.overall.solved, 3); assert.equal(source(view, 'NOWCODER').overall.solved, 2);
  sql(`UPDATE remote_nowcoder_problem SET question_id='998877' WHERE problem_key=${q(ncContest)};UPDATE problem SET auth=2 WHERE id=${pids[2]};`); view = await evidence(); assert.equal(source(view, 'NOWCODER').coverageAvailable, false); assert.equal(view.combined.axes.find(a => a.code === 'math').solved, 0);
  report.checks.push('Removing trusted alias evidence stops deduplication; private local classifications cannot label otherwise unclassified external NC tasks.');
  report.validated = true;
})().catch(error => { report.failure = error.stack; process.exitCode = 1; }).finally(async () => {
  try {
    sql(`DELETE FROM judge WHERE uid IN (${uids});DELETE FROM problem WHERE author=${q(author)};DELETE FROM external_account WHERE uid IN (${uids});`);
    if (createdExternal.length) sql(`DELETE FROM external_problem WHERE problem_key IN (${createdExternal.map(q).join(',')}) AND NOT EXISTS(SELECT 1 FROM external_submission s WHERE s.problem_key=external_problem.problem_key);`);
    if (createdCatalogue.length) sql(`DELETE FROM remote_nowcoder_problem WHERE problem_key IN (${createdCatalogue.map(q).join(',')});`);
    for (const u of users) if (u.token) await request(u, '/api/logout').catch(() => {});
    sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql(`SELECT (SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids}))+(SELECT COUNT(*) FROM problem WHERE author=${q(author)});`), '0');
    if (createdExternal.length) assert.equal(sql(`SELECT COUNT(*) FROM external_problem WHERE problem_key IN (${createdExternal.map(q).join(',')});`), '0'); report.cleaned = true;
  } catch (error) { report.cleanupFailure = error.stack; process.exitCode = 1; }
  fs.writeFileSync(path.join(output, 'learning-evidence-local-integration.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
});
