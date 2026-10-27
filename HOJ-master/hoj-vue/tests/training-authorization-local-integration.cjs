// Opt-in local integration: actual Shiro/Java/MySQL, temporary users and trainings only.
const assert = require('assert').strict;
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

if (process.env.HOJ_LOCAL_TRAINING_AUTH_TEST !== '1') {
  throw Error('Set HOJ_LOCAL_TRAINING_AUTH_TEST=1');
}
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(require('os').tmpdir(), 'hoj-training-auth');
fs.mkdirSync(output, { recursive: true });
const suffix = crypto.randomBytes(5).toString('hex');
const users = ['root', 'problem_admin', 'admin', 'problem_admin', 'default_user'].map((role, index) => ({
  role, uid: crypto.randomBytes(16).toString('hex'), username: 'trauth_' + index + suffix,
  password: crypto.randomBytes(8).toString('hex')
}));
const [root, owner, admin, other, student] = users;
const ids = [];
const q = value => "'" + String(value).replace(/'/g, "''") + "'";
const report = { validated: false, cleaned: false, checks: [], failures: [],
  apiMocked: false, realSourceCalled: false, realModelCalled: false, judgingDispatched: false };
let baseline;

function sql(input) {
  return execFileSync('docker', ['exec', '-i', 'hoj-master-hoj-mysql-1', 'sh', '-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],
  { input, encoding: 'utf8', windowsHide: true, timeout: 30000 }).trim();
}
function counts() {
  return sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),'
    + '(SELECT COUNT(*) FROM judge),(SELECT COUNT(*) FROM training),(SELECT COUNT(*) FROM training_category),'
    + '(SELECT COUNT(*) FROM mapping_training_category),(SELECT COUNT(*) FROM tag),'
    + '(SELECT COUNT(*) FROM tag_classification),(SELECT COUNT(*) FROM user_info);');
}
async function request(user, url, method = 'GET', data) {
  const response = await fetch('http://127.0.0.1:6688' + url, {
    method, headers: { 'Content-Type': 'application/json', ...(user?.token ? { Authorization: user.token } : {}) },
    ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(30000)
  });
  const body = await response.json();
  if (user && response.headers.get('Authorization')) user.token = response.headers.get('Authorization');
  return body;
}
async function check(name, action) {
  try { await action(); report.checks.push(name); }
  catch (error) { report.failures.push({ name, failure: error.message }); }
}
function state(id) {
  return sql(`SELECT t.id,t.title,t.author,t.auth,IFNULL(t.private_pwd,'NULL'),t.status,`
    + `IFNULL(m.cid,'NULL') FROM training t LEFT JOIN mapping_training_category m ON m.tid=t.id WHERE t.id=${id};`);
}
async function create(user, name, category) {
  const title = '临时权限训练_' + suffix + '_' + name;
  const body = await request(user, '/api/admin/training', 'POST', {
    training: { title, description: '权限回归临时数据，结束后清理。', author: user.username,
      rank: 1000, auth: 'Private', privatePwd: 'local-only', status: false, isGroup: false },
    trainingCategory: { id: category }
  });
  assert.equal(body.status, 200, body.msg);
  const id = Number(sql(`SELECT id FROM training WHERE author=${q(user.username)} AND title=${q(title)};`));
  assert(id > 0); ids.push(id);
  const loaded = await request(root, '/api/admin/training?tid=' + id);
  assert.equal(loaded.status, 200, loaded.msg);
  return loaded.data;
}
function statusUrl(id, author, status) {
  return '/api/admin/training/change-training-status?tid=' + id + '&author='
    + encodeURIComponent(author) + '&status=' + status;
}

(async () => {
  baseline = counts();
  const topics = await request(null, '/api/learning/topics');
  assert.equal(topics.status, 200); const category = topics.data.topics[0].categoryId;
  for (const user of users) {
    sql(`INSERT INTO user_info(uuid,username,password) VALUES(${q(user.uid)},${q(user.username)},`
      + `${q(crypto.createHash('md5').update(user.password).digest('hex'))});`
      + `INSERT INTO user_record(uid) VALUES(${q(user.uid)});`
      + `INSERT INTO user_role(uid,role_id) SELECT ${q(user.uid)},id FROM role WHERE role IN ('default_user',${q(user.role)});`);
    const logged = await request(user, '/api/login', 'POST', { username: user.username, password: user.password });
    assert.equal(logged.status, 200, logged.msg);
  }
  for (const actor of [admin, other]) {
    const dto = await create(root, actor.role + '_edit', category), id = dto.training.id, original = state(id);
    await check(actor.role + ' cannot edit another owner by forging body author', async () => {
      const body = await request(actor, '/api/admin/training', 'PUT', {
        ...dto, training: { ...dto.training, author: actor.username, title: 'Forbidden_' + suffix, privatePwd: 'forged' }
      });
      const stored = state(id);
      assert.deepEqual({ status: body.status, unchanged: stored === original }, { status: 403, unchanged: true });
    });
    const toggled = await create(root, actor.role + '_status', category), toggleId = toggled.training.id, before = state(toggleId);
    await check(actor.role + ' cannot change another owner status by forging query author', async () => {
      const body = await request(actor, statusUrl(toggleId, actor.username, true), 'PUT');
      assert.deepEqual({ status: body.status, unchanged: state(toggleId) === before }, { status: 403, unchanged: true });
    });
  }
  const own = await create(owner, 'owner', category), id = own.training.id;
  await check('owner can edit own training and keep its classification', async () => {
    own.training.title += '_updated';
    assert.equal((await request(owner, '/api/admin/training', 'PUT', own)).status, 200);
    const loaded = await request(owner, '/api/admin/training?tid=' + id);
    assert.equal(loaded.status, 200); assert.equal(loaded.data.training.title, own.training.title);
    assert.equal(loaded.data.trainingCategory.id, category);
  });
  await check('status permission uses stored owner even when query author is wrong', async () => {
    assert.equal((await request(owner, statusUrl(id, root.username, true), 'PUT')).status, 200);
    assert.equal(sql(`SELECT status FROM training WHERE id=${id};`), '1');
  });
  await check('root can edit and change status of another owner training', async () => {
    own.training.title += '_root';
    assert.equal((await request(root, '/api/admin/training', 'PUT', own)).status, 200);
    assert.equal((await request(root, statusUrl(id, 'untrusted', false), 'PUT')).status, 200);
    assert.equal(sql(`SELECT author,status FROM training WHERE id=${id};`), owner.username + '\t0');
  });
  for (const [actor, expected, label] of [[null, 401, 'anonymous'], [student, 403, 'student']]) {
    await check(label + ' is denied both protected mutation routes', async () => {
      const original = state(id);
      assert.equal((await request(actor, '/api/admin/training', 'PUT', own)).status, expected);
      assert.equal((await request(actor, statusUrl(id, owner.username, true), 'PUT')).status, expected);
      assert.equal(state(id), original);
    });
  }
  await check('foreign admin cannot read a private training edit DTO', async () => {
    assert.equal((await request(admin, '/api/admin/training?tid=' + id)).status, 403);
  });
  // Pick an absent, bounded id and clean it too, so a regressed upsert never leaves a fixture behind.
  const missing = Number(sql('SELECT COALESCE(MAX(id),0)+100000 FROM training;'));
  assert.equal(sql(`SELECT COUNT(*) FROM training WHERE id=${missing};`), '0'); ids.push(missing);
  await check('missing training status update is rejected without inserting a row', async () => {
    const body = await request(root, statusUrl(missing, root.username, true), 'PUT');
    assert.equal(body.status, 400, body.msg); assert.equal(state(missing), '');
  });
  await check('missing or null update id returns a business error without writes', async () => {
    for (const trainingId of [missing, null]) {
      const body = await request(owner, '/api/admin/training', 'PUT', {
        ...own, training: { ...own.training, id: trainingId }
      });
      assert.equal(body.status, 400, body.msg);
    }
  });
  assert.deepEqual(report.failures, []); report.validated = true;
})().catch(error => { report.failure = error.stack; process.exitCode = 1; }).finally(async () => {
  try {
    if (ids.length) sql(`DELETE FROM training WHERE id IN (${ids.join(',')});`);
    for (const user of users) if (user.token) await request(user, '/api/logout').catch(() => {});
    const uids = users.map(user => q(user.uid)).join(',');
    sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(counts(), baseline); report.originalCounts = baseline; report.cleaned = true;
  } catch (error) { report.cleanupFailure = error.stack; process.exitCode = 1; }
  fs.writeFileSync(path.join(output, 'training-authorization-http.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
});
