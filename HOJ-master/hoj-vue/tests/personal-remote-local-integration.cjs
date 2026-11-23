// Opt-in local runtime checks. Only stopped/review tasks are seeded; no task can POST code.
const assert = require('assert').strict;
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
if (process.env.HOJ_LOCAL_PERSONAL_REMOTE_TEST !== '1') throw Error('Set HOJ_LOCAL_PERSONAL_REMOTE_TEST=1.');
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(require('os').tmpdir(), 'hoj-personal-remote-review');
fs.mkdirSync(output, { recursive: true });
const report = { checks: [], cleaned: false };
const uid = crypto.randomBytes(16).toString('hex');
const actor = { username: 'remote_it_' + uid.slice(0, 10), password: crypto.randomBytes(8).toString('hex'), token: '' };
const handle = 'remote_it_' + uid.slice(0, 16);
const remote = 'CF-' + (900000000 + crypto.randomInt(90000000)) + 'A';
const contest = remote.slice(3, -1), code = 'synthetic local stopped-task fixture; never send to any source';
const quote = value => "'" + String(value).replace(/'/g, "''") + "'";
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const field = value => { const text = value == null ? '' : String(value); return text.length + ':' + text; };
const compose = path.resolve(__dirname, '../../compose.yaml');
function sql(input) {
  return execFileSync('docker', ['compose', '-p', 'hoj-master', '-f', compose, 'exec', '-T', 'hoj-mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'], { input, encoding: 'utf8', windowsHide: true, timeout: 30000 }).trim();
}
async function request(url, expected, method = 'GET', data) {
  const response = await fetch('http://127.0.0.1:6688' + url, { method, headers: { 'Content-Type': 'application/json', ...(actor.token ? { Authorization: actor.token } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(20000) });
  const body = await response.json();
  if (response.headers.get('authorization')) actor.token = response.headers.get('authorization');
  if (expected === 'denied') assert.notEqual(body.status, 200, url + ' must deny');
  else assert.equal(body.status, expected, url + ': ' + body.msg);
  return body;
}
let pid, accountId;
const ids = [];
(async () => {
  assert.equal(sql("SELECT COUNT(*) FROM personal_remote_task WHERE state IN ('QUEUED','PREPARING','SUBMITTING','RECONCILING','POLLING')"), '0', 'Run this isolated check without live personal submissions.');
  sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(uid)},${quote(actor.username)},${quote(crypto.createHash('md5').update(actor.password).digest('hex'))}); INSERT INTO user_record(uid) VALUES(${quote(uid)}); INSERT INTO user_role(uid,role_id) SELECT ${quote(uid)},id FROM role WHERE role IN ('default_user','root');`);
  await request('/api/login', 200, 'POST', { username: actor.username, password: actor.password });
  assert(actor.token);
  pid = Number(sql(`INSERT INTO problem(problem_id,title,author,is_remote,auth) VALUES(${quote(remote)},'temporary stopped-task runtime fixture',${quote(actor.username)},TRUE,2); SELECT LAST_INSERT_ID();`));
  accountId = Number(sql(`INSERT INTO external_account(uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(${quote(uid)},'CODEFORCES',${quote(handle)},${quote(handle)},${quote(handle)},NOW(3),NOW(3)); SELECT LAST_INSERT_ID();`));
  sql(`INSERT INTO personal_remote_lane(platform,handle_key,active_submit_id) VALUES('CODEFORCES',${quote(handle)},NULL);`);
  const payload = hash([accountId, remote, contest, '54', pid, 0, null, 'C++', 0, code].map(field).join(''));
  for (const state of ['CANCELLED', 'FAILED', 'NEEDS_REVIEW']) {
    const id = Number(sql(`INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code,language) VALUES(${pid},${quote(remote)},${quote(uid)},${quote(actor.username)},NOW(),5,${quote(code)},'C++'); SELECT LAST_INSERT_ID();`));
    ids.push(id);
    sql(`INSERT INTO personal_remote_task(submit_id,owner_uid,account_id,account_ref,platform,handle_key,credential_version,request_id,remote_problem,source_contest,language_id,code_hash,payload_hash,state,submit_attempts,error_code,baseline_id,post_started_at,created_at,updated_at) VALUES(${id},${quote(uid)},${accountId},${accountId},'CODEFORCES',${quote(handle)},1,${quote(uid + '_' + id)},${quote(remote)},${quote(contest)},'54',${quote(hash(code))},${quote(payload)},${quote(state)},${state === 'NEEDS_REVIEW' ? 1 : 0},${state === 'CANCELLED' ? 'NULL' : "'AUTH_REQUIRED'"},${state === 'NEEDS_REVIEW' ? 100 : 'NULL'},${state === 'NEEDS_REVIEW' ? 'NOW(3)' : 'NULL'},NOW(3),NOW(3));`);
  }
  sql(`UPDATE personal_remote_lane SET active_submit_id=${ids[2]} WHERE platform='CODEFORCES' AND handle_key=${quote(handle)};`);
  const before = sql(`SELECT status,version FROM judge WHERE submit_id=${ids[2]};`);
  let response = await request('/api/resubmit?submitId=' + ids[2], 403);
  assert.match(response.msg, /个人远程提交/);
  report.checks.push('Real owner resubmit rejects a personal task before changing the judge or entering legacy dispatch.');
  for (const route of ['rejudge', 'manual-judge', 'cancel-judge']) {
    response = await request('/api/admin/judge/' + route + '?submitId=' + ids[2] + (route === 'manual-judge' ? '&status=0&score=100' : ''), 'denied');
    assert.match(response.msg, /个人远程提交/, 'The manager guard, not only role permissions, must deny ' + route);
  }
  assert.equal(sql(`SELECT status,version FROM judge WHERE submit_id=${ids[2]};`), before);
  report.checks.push('Real root rejudge, manual verdict and legacy cancellation reject personal task IDs without mutation.');
  response = await request('/api/admin/judge/rejudge-contest-problem?cid=0&pid=' + pid, 'denied');
  assert.match(response.msg, /无提交/);
  report.checks.push('Bulk legacy rejudge excludes personal task IDs in SQL.');
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline && sql(`SELECT GROUP_CONCAT(status ORDER BY submit_id) FROM judge WHERE submit_id IN(${ids.slice(0, 2).join(',')});`) !== '-4,10') await new Promise(resolve => setTimeout(resolve, 500));
  assert.equal(sql(`SELECT GROUP_CONCAT(status ORDER BY submit_id) FROM judge WHERE submit_id IN(${ids.slice(0, 2).join(',')});`), '-4,10');
  assert.equal(sql(`SELECT judger FROM judge WHERE submit_id=${ids[0]};`), 'PersonalRemote');
  assert.equal(sql(`SELECT active_submit_id FROM personal_remote_lane WHERE platform='CODEFORCES' AND handle_key=${quote(handle)};`), String(ids[2]));
  assert.equal(sql(`SELECT CONCAT(state,':',submit_attempts) FROM personal_remote_task WHERE submit_id=${ids[2]};`), 'NEEDS_REVIEW:1');
  assert.equal(sql(`SELECT COUNT(*) FROM user_acproblem WHERE uid=${quote(uid)};`), '0');
  assert.equal(sql(`SELECT COUNT(*) FROM remote_user_credential WHERE account_id=${accountId};`), '0');
  report.checks.push('The actual JudgeServer scheduler settles pre-POST cancellation/failure and preserves the uncertain identity lane. No credentials or executable tasks were created.');
  report.validated = true;
})().catch(error => { report.failure = error.stack; process.exitCode = 1; }).finally(async () => {
  try {
    if (actor.token) await request('/api/logout', 200).catch(() => {});
    sql(`DELETE FROM judge WHERE uid=${quote(uid)}; DELETE FROM personal_remote_lane WHERE platform='CODEFORCES' AND handle_key=${quote(handle)} AND active_submit_id IS NULL; DELETE FROM external_account WHERE uid=${quote(uid)}; DELETE FROM session WHERE uid=${quote(uid)};`);
    if (pid) sql(`DELETE FROM problem WHERE id=${pid} AND author=${quote(actor.username)};`);
    sql(`DELETE FROM user_info WHERE uuid=${quote(uid)};`);
    assert.equal(sql(`SELECT COUNT(*) FROM personal_remote_task WHERE owner_uid=${quote(uid)};`), '0');
    assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid=${quote(uid)};`), '0');
    report.cleaned = true;
  } catch (error) { report.cleanupFailure = error.stack; process.exitCode = 1; }
  fs.writeFileSync(path.join(output, 'personal-remote-local-integration.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
});
