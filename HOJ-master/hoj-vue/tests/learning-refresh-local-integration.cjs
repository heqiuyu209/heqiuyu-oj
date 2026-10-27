// Opt-in local HTTP/MySQL regression. All source records are synthetic and jobs stay fenced.
// No source account authorization, source request, code submission or model call occurs.
const assert = require('assert').strict, crypto = require('crypto'), fs = require('fs'), path = require('path');
const { execFileSync } = require('child_process');
if (process.env.HOJ_LOCAL_LEARNING_REFRESH_TEST !== '1') throw Error('Set HOJ_LOCAL_LEARNING_REFRESH_TEST=1.');
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(require('os').tmpdir(), 'hoj-learning-refresh-http');
fs.mkdirSync(output, { recursive: true });
const target = path.join(output, 'learning-refresh-local-integration.json');
if (fs.existsSync(target)) throw Error('Preserve the previous integration report.');
const nonce = crypto.randomBytes(8).toString('hex');
const user = { uid: crypto.randomBytes(16).toString('hex'), username: 'rf_' + nonce, password: crypto.randomBytes(8).toString('hex') };
const handle = 'fixture_' + nonce, key = 'cf:contest:' + (90000000 + crypto.randomInt(9000000)) + ':A';
const quote = value => "'" + String(value).replace(/'/g, "''") + "'";
const report = { checks: [], states: [], testUserId: user.uid, testSourceKey: key, sourceRequests: 0, chatModelCalls: 0, realPlatformAuthorization: false, cleaned: false };
let accountId, ownsProblem = false, nextRecommendationAt = 0;
function sql(input) {
  return execFileSync('docker', ['exec', '-i', 'hoj-master-hoj-mysql-1', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'], { input, encoding: 'utf8', windowsHide: true, timeout: 30000 }).trim();
}
async function request(url, method = 'GET', data) {
  const response = await fetch('http://127.0.0.1:6688' + url, { method, headers: { 'Content-Type': 'application/json', ...(user.token ? { Authorization: user.token } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(30000) });
  const body = await response.json(); if (response.headers.get('Authorization')) user.token = response.headers.get('Authorization');
  assert.equal(body.status, 200, url + ': ' + body.msg); return body.data;
}
const evidence = () => request('/api/learning/users/' + user.uid + '/evidence');
(async () => {
  assert.equal(sql(`SELECT COUNT(*) FROM external_problem WHERE problem_key=${quote(key)};`), '0');
  sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(user.uid)},${quote(user.username)},${quote(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(user.uid)},id FROM role WHERE role='default_user';`);
  await request('/api/login', 'POST', { username: user.username, password: user.password }); assert(user.token);
  accountId = Number(sql(`START TRANSACTION;INSERT INTO external_account(uid,platform,handle,handle_key,verified_identity,history_complete,last_success_at,last_requested_at,created_at) VALUES(${quote(user.uid)},'CODEFORCES',${quote(handle)},${quote(handle)},${quote(handle)},1,NOW(3),NOW(3),NOW(3));SET @refresh_account=LAST_INSERT_ID();INSERT INTO external_sync_job(account_id,scan_token,stage,next_run_at) VALUES(@refresh_account,${quote(nonce)},'DONE','2999-01-01');SELECT @refresh_account;COMMIT;`));
  assert(Number.isSafeInteger(accountId) && accountId > 0);
  sql(`INSERT INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(${quote(key)},'CODEFORCES','Local refresh fixture',800,'["math"]',NOW(3));`); ownsProblem = true;
  sql(`INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(${accountId},1,${quote(key)},'OK',1700000000,1,${quote(nonce)});`);
  for (const state of ['IDLE', 'QUEUED', 'RUNNING', 'BACKOFF', 'IDLE']) {
    const stage = state === 'IDLE' ? 'DONE' : 'STATUS';
    sql(`UPDATE external_sync_job SET stage=${quote(stage)},failures=${state === 'BACKOFF' ? 1 : 0},lease_until=${state === 'RUNNING' ? "'2999-01-01'" : 'NULL'},next_run_at='2999-01-01' WHERE account_id=${accountId};`);
    const bundle = await evidence(), source = bundle.sources.find(row => row.source === 'CODEFORCES');
    assert.equal(source.syncState, state); assert.equal(source.stale, state !== 'IDLE'); assert.equal(source.historyComplete, true);
    assert.equal(source.overall.solved, 1); assert.equal(bundle.combined.overall.solved, 1);
    assert.equal(bundle.combined.syncState, state); assert.equal(bundle.combined.stale, state !== 'IDLE');
    for (const platform of ['CODEFORCES', 'HOJ']) {
      await new Promise(resolve => setTimeout(resolve, Math.max(0, nextRecommendationAt - Date.now())));
      const plan = await request('/api/ai/recommendations?source=' + platform); nextRecommendationAt = Date.now() + 5200;
      if (platform === 'CODEFORCES') { assert.equal(plan.basis.syncState, state); assert.equal(plan.basis.stale, source.stale); assert.equal(plan.basis.historyComplete, true); }
      assert.equal(plan.basis.coverageSyncState, state); assert.equal(plan.basis.coverageStale, state !== 'IDLE');
      report.states.push({ state, platform, sourceSolved: source.overall.solved, combinedSolved: bundle.combined.overall.solved, recommendationSyncState: plan.basis.syncState, coverageSyncState: plan.basis.coverageSyncState });
    }
    console.log('Local evidence and rule recommendations: ' + state);
  }
  assert.equal((await evidence()).sources.find(row => row.source === 'ATCODER').syncState, 'UNLINKED');
  assert.equal(sql(`SELECT next_run_at='2999-01-01' FROM external_sync_job WHERE account_id=${accountId};`), '1');
  report.checks.push('Actual HTTP profiles retain cached counts and completed-history meaning across idle, queued, running, backoff and completed refresh states.');
  report.checks.push('Actual authenticated rule recommendations carry the same source freshness without model settings or provider calls; unrelated platforms remain unlinked.');
  report.validated = true;
})().catch(error => { report.failure = error.stack; process.exitCode = 1; }).finally(async () => {
  try {
    sql(`DELETE FROM external_account WHERE uid=${quote(user.uid)};`);
    if (ownsProblem) sql(`DELETE FROM external_problem WHERE problem_key=${quote(key)} AND NOT EXISTS(SELECT 1 FROM external_submission s WHERE s.problem_key=${quote(key)});`);
    if (user.token) await request('/api/logout').catch(() => {});
    sql(`DELETE FROM session WHERE uid=${quote(user.uid)};DELETE FROM user_info WHERE uuid=${quote(user.uid)};`);
    assert.equal(sql(`SELECT (SELECT COUNT(*) FROM user_info WHERE uuid=${quote(user.uid)}),(SELECT COUNT(*) FROM external_account WHERE uid=${quote(user.uid)});`), '0\t0');
    if (ownsProblem) assert.equal(sql(`SELECT COUNT(*) FROM external_problem WHERE problem_key=${quote(key)};`), '0');
    report.cleaned = true;
  } catch (error) { report.cleanupFailure = error.stack; process.exitCode = 1; }
  fs.writeFileSync(target, JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
});
