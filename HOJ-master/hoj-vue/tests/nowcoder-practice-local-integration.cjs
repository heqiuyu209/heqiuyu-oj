// Opt-in local HTTP/MySQL validation; the source account is public and remains unverified.
// No source login, profile edit, code submission or model request is made.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path');
const {execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_NOWCODER_PRACTICE_TEST!=='1')throw Error('Set HOJ_LOCAL_NOWCODER_PRACTICE_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(require('os').tmpdir(),'hoj-nowcoder-practice-integration');
fs.mkdirSync(output,{recursive:true});
const target=path.join(output,'nowcoder-practice-local-integration.json');if(fs.existsSync(target))throw Error('Preserve the previous integration report.');
const users=Array.from({length:2},()=>({uid:crypto.randomBytes(16).toString('hex'),username:'ncp_it_'+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(8).toString('hex')}));
const [alice,bob]=users,report={checks:[],sourceUserId:'4980952',sourceAccountVerified:false,sourceCookiesSent:false,sourceCodeSent:false,chatModelCalls:0,cleaned:false};
const quote=v=>"'"+String(v).replace(/'/g,"''")+"'",uids=users.map(u=>quote(u.uid)).join(',');
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let accountId=null,originalKeys=new Set();const cleanupKeys=new Set();
function sql(input){return execFileSync('docker',['exec','-i','hoj-master-hoj-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
async function request(user,url,method='GET',data,expected=200){
  const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(30000)});
  const body=await response.json();if(user&&response.headers.get('Authorization'))user.token=response.headers.get('Authorization');
  assert.equal(body.status,expected,url+': '+body.msg);return body.data;
}
function fence(id){
  assert(Number.isSafeInteger(Number(id))&&Number(id)>0);
  const value=sql(`START TRANSACTION;SELECT id FROM external_account WHERE id=${Number(id)} AND uid IN (${uids}) FOR UPDATE;
    UPDATE external_sync_job SET next_run_at=NULL,lease_token=NULL,lease_until=NULL WHERE account_id=${Number(id)};
    UPDATE external_nowcoder_context_job SET next_run_at='2999-01-01',lease_token=NULL,lease_until=NULL WHERE account_id=${Number(id)};
    UPDATE external_nowcoder_practice_job SET next_run_at='2999-01-01',lease_token=NULL,lease_until=NULL WHERE account_id=${Number(id)};
    SELECT DISTINCT problem_key FROM external_submission WHERE account_id=${Number(id)};COMMIT;`);
  for(const key of value.split('\n').slice(1))if(key&&!originalKeys.has(key))cleanupKeys.add(key);
}
(async()=>{
  originalKeys=new Set(sql('SELECT problem_key FROM external_problem;').split('\n').filter(Boolean));
  for(const user of users){
    assert(/^[a-f0-9]{32}$/.test(user.uid));
    sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(user.uid)},${quote(user.username)},${quote(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(user.uid)},id FROM role WHERE role='default_user';`);
    await request(user,'/api/login','POST',{username:user.username,password:user.password});assert(user.token);
  }
  await request(null,'/api/learning/accounts','GET',undefined,401);
  let own=await request(alice,'/api/learning/accounts','POST',{platform:'NOWCODER',handle:report.sourceUserId});
  const linked=own.accounts.find(a=>a.platform==='NOWCODER'&&a.handle===report.sourceUserId);assert(linked);accountId=Number(linked.id);
  assert.equal(linked.verified,false);assert(linked.practiceContext);const originalSolved=own.summary.uniqueSolved;
  const publicPath='/api/learning/users/'+alice.uid+'/external';assert.deepEqual((await request(null,publicPath)).accounts,[]);
  await request(bob,`/api/learning/accounts/${accountId}/sync`,'POST',{},404);
  await request(bob,`/api/learning/accounts/${accountId}`,'DELETE',undefined,404);
  report.checks.push('Real HTTP rejects anonymous private access and other-user mutations; an unverified source remains absent from the public profile.');
  const deadline=Date.now()+360000;let previous='',current;
  while(true){
    own=await request(alice,'/api/learning/accounts');current=own.accounts.find(a=>a.id===accountId);assert(current);
    const progress={state:current.practiceContext.syncState,available:current.practiceContext.available,stage:sql(`SELECT CONCAT(stage,':',next_page) FROM external_nowcoder_practice_job WHERE account_id=${accountId};`)||'WAITING'};
    const serialized=JSON.stringify(progress);if(previous!==serialized){console.log('Public paper sync '+serialized);previous=serialized;}
    if(current.practiceContext.available&&current.practiceContext.syncState==='IDLE')break;
    if(Date.now()>=deadline)throw Error('Actual paper sync did not finish: '+current.practiceContext.syncState+' '+(current.practiceContext.lastError||''));
    await sleep(3000);
  }
  const context=current.practiceContext;
  assert.equal(current.verified,false);assert.equal(context.paperListsComplete,true);assert.equal(context.attemptHistoryComplete,false);
  assert.equal(context.questionEvidenceAvailable,false);assert.equal(context.uniqueQuestionCount,null);
  assert(context.totalPapers>0&&context.completedPapers>0&&context.completedPapers<=context.totalPapers);
  assert(context.specialPapers>0&&context.completedSpecialPapers<=context.specialPapers);
  assert(context.records.length>0&&context.records.length<=20);assert.equal(own.summary.uniqueSolved,originalSolved);
  assert.equal(Number(sql(`SELECT COUNT(*) FROM external_nowcoder_practice_record WHERE account_id=${accountId} AND projection='ALL';`)),context.totalPapers);
  assert.equal(Number(sql(`SELECT COUNT(*) FROM external_nowcoder_practice_record WHERE account_id=${accountId} AND projection='FINISHED';`)),context.completedPapers);
  assert.equal(context.sourceUrl,'https://www.nowcoder.com/users/'+report.sourceUserId+'/tests');
  assert(!JSON.stringify(context).includes('scan_token'));assert(!JSON.stringify(context).includes('record_hash'));
  assert.deepEqual((await request(null,publicPath)).accounts,[]);
  report.live={totalPapers:context.totalPapers,completedPapers:context.completedPapers,specialPapers:context.specialPapers,completedSpecialPapers:context.completedSpecialPapers,visibleRecords:context.records.length,codingHistoryComplete:current.historyComplete,codingSyncState:current.syncState,uniqueSolvedBefore:originalSolved,uniqueSolvedAfter:own.summary.uniqueSolved};
  if(process.env.HOJ_NOWCODER_PRACTICE_SAMPLES){
    const samples=process.env.HOJ_NOWCODER_PRACTICE_SAMPLES;
    const expected=[];
    for(const [projection,prefix] of [['ALL','practice-4980952-p'],['FINISHED','practice-4980952-finished-p']])for(let page=1;page<=2;page++)for(const row of JSON.parse(fs.readFileSync(path.join(samples,prefix+page+'.json'),'utf8')).data.records)expected.push(projection+'\t'+row.paperId+'\t'+row.id);
    const actual=sql(`SELECT projection,paper_id,record_id FROM external_nowcoder_practice_record WHERE account_id=${accountId};`).split('\n').filter(Boolean);
    report.archivedIdentitySets={actual:actual.sort(),expected:expected.sort()};
    report.archivedIdentitySetMatches=JSON.stringify(report.archivedIdentitySets.actual)===JSON.stringify(report.archivedIdentitySets.expected);
    assert.equal(report.archivedIdentitySetMatches,true,'Actual public paper identities differ from the archived source sample.');
  }
  report.checks.push('Running Java8 worker completed both public paper projections, full page rechecks, count rechecks and final visibility; actual records stay separate from programming solved counts and expose no internal tokens.');
  fence(accountId);await request(alice,`/api/learning/accounts/${accountId}`,'DELETE');
  for(const table of ['external_nowcoder_practice_job','external_nowcoder_practice_record'])assert.equal(sql(`SELECT COUNT(*) FROM ${table} WHERE account_id=${accountId};`),'0');
  assert.deepEqual((await request(alice,'/api/learning/accounts')).accounts,[]);
  report.checks.push('Real owner unlink cascades the paper job and both projections after in-flight callbacks are fenced.');
  report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    const ids=sql(`SELECT id FROM external_account WHERE uid IN (${uids});`).split('\n').filter(Boolean);for(const id of ids)fence(Number(id));
    sql(`DELETE FROM external_account WHERE uid IN (${uids});`);
    if(cleanupKeys.size)sql(`DELETE FROM external_problem WHERE problem_key IN (${[...cleanupKeys].map(quote).join(',')}) AND NOT EXISTS (SELECT 1 FROM external_submission s WHERE s.problem_key=external_problem.problem_key);`);
    for(const user of users)if(user.token)await request(user,'/api/logout').catch(()=>{});
    sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`),'0');
    report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(target,JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
