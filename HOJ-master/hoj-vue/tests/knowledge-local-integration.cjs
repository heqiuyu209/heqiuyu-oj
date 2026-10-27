// Opt-in local HTTP/ACL verification while the internal index is unconfigured.
// INDEXED receipts below are explicit fixtures, not real embedding evidence.
// Real Java -> Python -> BGE-M3/Qdrant is verified by the separate RAG harness.
const assert = require('assert').strict;
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
if (process.env.HOJ_LOCAL_KNOWLEDGE_TEST !== '1') throw Error('Set HOJ_LOCAL_KNOWLEDGE_TEST=1.');
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(require('os').tmpdir(), 'hoj-knowledge-review');
fs.mkdirSync(output, { recursive: true });
const report = { checks: [], indexMode: 'UNCONFIGURED_WITH_EXPLICIT_INDEXED_FIXTURE', externalModelCalls: 0, cleaned: false };
const accounts = ['root', 'admin', 'problem_admin', 'default_user', 'default_user'].map(role => ({ role, uid: crypto.randomBytes(16).toString('hex'), username: 'kb_it_' + crypto.randomBytes(5).toString('hex'), password: crypto.randomBytes(8).toString('hex'), token: '' }));
const [teacher, admin, problemAdmin, alice, bob] = accounts;
const quote = value => "'" + String(value).replace(/'/g, "''") + "'";
const uids = accounts.map(a => quote(a.uid)).join(',');
let baseline;
function sql(input) { return execFileSync('docker', ['compose', '-p', 'hoj-master', '-f', path.resolve(__dirname, '../../compose.yaml'), 'exec', '-T', 'hoj-mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'], { input, encoding: 'utf8', windowsHide: true, timeout: 30000 }).trim(); }
async function request(account, endpoint, method = 'GET', data, expected = 200) {
  const response = await fetch('http://127.0.0.1:6688' + endpoint, { method, headers: { 'Content-Type': 'application/json', ...(account?.token ? { Authorization: account.token } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(25000) });
  const body = await response.json(); if (account && response.headers.get('authorization')) account.token = response.headers.get('authorization');
  assert.equal(body.status, expected, endpoint + ': ' + body.msg);
  if (expected === 200 && endpoint.includes('/knowledge')) {
    assert.equal(response.headers.get('cache-control'), 'no-store');
    for (const key of ['lease_token', 'creation_hash', 'key_ciphertext', 'apiKey']) assert(!JSON.stringify(body).includes('"' + key + '"'));
  }
  return body.data;
}
const base = '/api/admin/knowledge/documents/';
const nonce = () => crypto.randomBytes(16).toString('hex');
const pause = () => new Promise(resolve => setTimeout(resolve, 2100));
const doc = { requestId: nonce(), title: '本地接口验证：前缀和', format: 'markdown', content: '# 前缀和\n\n区间和等于两个前缀和之差。', visibility: 'PUBLIC' };
async function detail(id) { return request(teacher, base + id); }
async function change(id, suffix, extra = {}, who = teacher) { const current = await detail(id); return request(who, base + id + suffix, suffix === '/visibility' ? 'PUT' : 'POST', { expectedRevision: current.revision, ...extra }); }

(async () => {
  baseline = sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),(SELECT COUNT(*) FROM knowledge_document);');
  for (const account of accounts) {
    const hash = crypto.createHash('md5').update(account.password).digest('hex');
    sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(account.uid)},${quote(account.username)},${quote(hash)});INSERT INTO user_record(uid) VALUES(${quote(account.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(account.uid)},id FROM role WHERE role IN ('default_user',${quote(account.role)});`);
    await request(account, '/api/login', 'POST', { username: account.username, password: account.password }); assert(account.token);
  }
  assert.equal((await request(teacher, '/api/admin/knowledge')).indexConfigured, false, 'This fixture must not run against a configured index.');
  await request(null, '/api/knowledge/documents', 'GET', undefined, 401);
  await request(alice, '/api/admin/knowledge', 'GET', undefined, 403);
  await request(problemAdmin, '/api/admin/knowledge/documents', 'POST', { ...doc, requestId: nonce() }, 403);
  const created = await request(teacher, '/api/admin/knowledge/documents', 'POST', doc);
  const id = created.id; assert.match(id, /^[a-f0-9]{32}$/); assert.equal(created.publishedVersion, null);
  assert.equal((await request(teacher, '/api/admin/knowledge/documents', 'POST', doc)).id, id);
  await request(teacher, '/api/admin/knowledge/documents', 'POST', { ...doc, content: 'changed' }, 409);
  await request(teacher, base + id + '/publish', 'POST', { expectedRevision: created.revision, version: 1 }, 409);
  report.checks.push('Real login and method-level Shiro: anonymous and ordinary/problem administrators cannot maintain knowledge; root upload is idempotent, conflicts are rejected, and queued versions cannot publish.');

  const search = { query: '如何计算区间和', documentIds: [id], topK: 5 };
  assert.equal((await request(alice, '/api/knowledge/search', 'POST', search)).items.length, 0);
  await pause(); const noModel = await request(alice, '/api/knowledge/answer', 'POST', { search }); assert.equal(noModel.modelUsed, false);
  await request(alice, '/api/knowledge/answer', 'POST', { search, allowModelExcerpts: true }, 400);
  assert.equal(sql(`SELECT COUNT(*) FROM ai_user_setting WHERE uid=${quote(alice.uid)};`), '0');
  report.checks.push('Unpublished data is absent from search; omitted consent still permits pure retrieval without model settings, while consent without a configured user model fails before generation.');

  // A controlled completion fixture allows testing publication/read ACL with no
  // long-running external service. It is not counted as an embedding test.
  sql(`UPDATE knowledge_version SET state='INDEXED',chunks=1,indexed_at=NOW(3) WHERE document_id=${quote(id)} AND version=1;UPDATE knowledge_job SET state='DONE' WHERE document_id=${quote(id)} AND operation='INGEST';`);
  const published = await change(id, '/publish', { version: 1 }, admin);
  assert.equal(published.publishedVersion, 1);
  const directory = await request(alice, '/api/knowledge/documents');
  assert(directory.items.some(item => item.id === id)); assert(!JSON.stringify(directory).includes(doc.content));
  assert.equal((await request(alice, '/api/knowledge/documents/' + id + '?version=1')).content, doc.content);
  await request(alice, '/api/knowledge/documents/' + id + '?version=2', 'GET', undefined, 404);
  await pause(); await request(alice, '/api/knowledge/search', 'POST', search, 503);
  report.checks.push('An explicit INDEXED fixture can be published by another site administrator; directory exposes metadata, the current original is readable, stale version access fails, and an unconfigured index returns a truthful 503 instead of silently calling a model.');

  const revision = (await detail(id)).revision;
  const newer = await request(teacher, base + id + '/versions', 'POST', { ...doc, requestId: nonce(), expectedRevision: revision, content: '# 前缀和\n\n未发布的新版本。', title: '未发布的新标题' });
  assert.equal(newer.latestVersion, 2); assert.equal(newer.publishedVersion, 1);
  assert.equal((await request(alice, '/api/knowledge/documents/' + id)).title, doc.title);
  await request(teacher, base + id + '/unpublish', 'POST', { expectedRevision: revision }, 409);
  await change(id, '/visibility', { visibility: 'ADMIN' });
  await request(alice, '/api/knowledge/documents/' + id, 'GET', undefined, 404);
  assert(!(await request(alice, '/api/knowledge/documents')).items.some(item => item.id === id));
  assert.equal((await request(teacher, '/api/knowledge/documents/' + id)).content, doc.content);
  report.checks.push('A new draft preserves the old published title/body; stale mutations fail, changing visibility immediately revokes normal-user directory and original access.');

  await change(id, '/visibility', { visibility: 'PUBLIC' });
  const cid = Number(sql(`INSERT INTO contest(uid,author,title,auth,status,start_time,end_time,duration,visible) VALUES(${quote(teacher.uid)},${quote(teacher.username)},'temporary knowledge policy contest',0,0,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 HOUR),7200,1);SELECT LAST_INSERT_ID();`));
  sql(`INSERT INTO contest_register(cid,uid,status) VALUES(${cid},${quote(alice.uid)},0);`);
  for (const endpoint of ['/api/knowledge/documents', '/api/knowledge/documents/' + id]) await request(alice, endpoint, 'GET', undefined, 403);
  await request(alice, '/api/knowledge/search', 'POST', search, 403);
  await request(alice, '/api/knowledge/answer', 'POST', { search, allowModelExcerpts: true }, 403);
  assert.equal((await request(bob, '/api/knowledge/documents/' + id)).content, doc.content);
  sql(`DELETE FROM contest_register WHERE cid=${cid} AND uid=${quote(alice.uid)};UPDATE contest SET start_time=DATE_SUB(NOW(),INTERVAL 2 HOUR),end_time=DATE_SUB(NOW(),INTERVAL 1 HOUR) WHERE id=${cid};INSERT INTO contest_virtual_run(uid,cid,request_id,title,started_at,ends_at) VALUES(${quote(alice.uid)},${cid},${quote(nonce())},'temporary virtual knowledge fixture',NOW(3),DATE_ADD(NOW(3),INTERVAL 60 SECOND));`);
  await request(alice, '/api/knowledge/documents/' + id, 'GET', undefined, 403);
  sql(`UPDATE contest_virtual_run SET ends_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE uid=${quote(alice.uid)} AND cid=${cid};`);
  assert.equal((await request(alice, '/api/knowledge/documents/' + id)).content, doc.content);
  report.checks.push('Formal and persisted virtual participants cannot use knowledge directory, original, retrieval or model-answer endpoints; unrelated users remain allowed and expired virtual windows restore access.');

  await change(id, '/unpublish'); await request(alice, '/api/knowledge/documents/' + id, 'GET', undefined, 404);
  await change(id, '/delete');
  assert.equal(sql(`SELECT COUNT(*) FROM knowledge_job WHERE document_id=${quote(id)} AND operation='DELETE' AND state='QUEUED';`), '2');
  assert.equal(sql(`SELECT COUNT(*) FROM knowledge_job WHERE document_id=${quote(id)} AND operation='INGEST' AND state IN ('RUNNING','QUEUED');`), '0');
  report.checks.push('Unpublish revokes citations immediately; logical delete cancels pending ingestion and persists one cleanup job per immutable version.');
  report.validated = true;
})().catch(error => { report.failure = error.stack; process.exitCode = 1; }).finally(() => {
  try {
    const ids = sql(`SELECT id FROM knowledge_document WHERE created_by IN (${uids});`).split(/\s+/).filter(Boolean);
    assert(ids.every(id => /^[a-f0-9]{32}$/.test(id)));
    if (ids.length) { const list = ids.map(quote).join(','); sql(`DELETE FROM knowledge_job WHERE document_id IN (${list});DELETE FROM knowledge_command WHERE document_id IN (${list});DELETE FROM knowledge_version WHERE document_id IN (${list});DELETE FROM knowledge_document WHERE id IN (${list});`); }
    sql(`DELETE FROM contest_virtual_run WHERE uid IN (${uids});DELETE FROM contest_register WHERE uid IN (${uids});DELETE FROM contest WHERE uid IN (${uids});DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`), '0');
    assert.equal(sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),(SELECT COUNT(*) FROM knowledge_document);'), baseline);
    report.cleaned = true;
  } catch (error) { report.cleanupFailure = error.stack; process.exitCode = 1; }
  fs.writeFileSync(path.join(output, 'knowledge-local-integration.json'), JSON.stringify(report, null, 2)); console.log(JSON.stringify(report, null, 2));
});
