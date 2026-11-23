// Opt-in real HTTP/MySQL checks. Ownership/result fixtures are synthetic; never modify a remote platform.
const assert=require('assert').strict, crypto=require('crypto'),fs=require('fs'),path=require('path');
const {execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_EXTERNAL_TEST!=='1')throw Error('Set HOJ_LOCAL_EXTERNAL_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(require('os').tmpdir(),'hoj-external-review');fs.mkdirSync(output,{recursive:true});
const users=Array.from({length:2},()=>({uid:crypto.randomBytes(16).toString('hex'),username:'ext_it_'+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(8).toString('hex')}));
const [alice,bob]=users, report={checks:[],cleaned:false};let problem,liveAccount,metadataCreated=false;
const sourceContest='990'+Date.now(),sourceKey='cf:contest:'+sourceContest+':A',sourceDisplay='CF-'+sourceContest+'A';
const quote=v=>"'"+String(v).replace(/'/g,"''")+"'",uids=users.map(u=>quote(u.uid)).join(',');
function sql(input){return execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
async function request(user,url,method='GET',data,status=200){
  const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(30000)});
  const body=await response.json();if(user&&response.headers.get('Authorization'))user.token=response.headers.get('Authorization');assert.equal(body.status,status,url+': '+body.msg);return body.data;
}
(async()=>{
  for(const [url,method] of [['/accounts','GET'],['/accounts','POST'],['/accounts/1','DELETE'],['/accounts/1/proof','POST'],['/accounts/1/verify','POST'],['/accounts/1/sync','POST']])await request(null,'/api/learning'+url,method,method==='POST'?{}:undefined,401);
  report.checks.push('All six account management operations reject anonymous HTTP calls.');
  for(const user of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(user.uid)},${quote(user.username)},${quote(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(user.uid)},id FROM role WHERE role='default_user';`);await request(user,'/api/login','POST',{username:user.username,password:user.password});assert(user.token);}
  const handle='fixture_'+crypto.randomBytes(5).toString('hex');let own=await request(alice,'/api/learning/accounts','POST',{platform:'CODEFORCES',handle});const id=own.accounts[0].id;
  // Hold this fictional account's job so synthetic tests never depend on a remote response.
  sql(`UPDATE external_sync_job SET next_run_at=NULL,lease_token=NULL,lease_until=NULL WHERE account_id=${Number(id)};`);
  own=await request(alice,`/api/learning/accounts/${id}/proof`,'POST',{});const code=own.accounts[0].proofCode;assert(code?.startsWith('HOJ-'));
  const endpoint='/api/learning/users/'+alice.uid+'/external';assert.deepEqual((await request(null,endpoint)).accounts,[]);
  await request(bob,`/api/learning/accounts/${id}/proof`,'POST',{},404);await request(bob,`/api/learning/accounts/${id}`,'DELETE',undefined,404);
  await request(alice,'/api/learning/accounts','POST',{platform:'CODEFORCES',handle:handle.toUpperCase()},409);
  await request(alice,`/api/learning/accounts/${id}/sync`,'POST',{},429);
  report.checks.push('Unverified associations and ownership codes are private; server identity, case-insensitive duplicate constraint and request cooldown apply in MySQL.');
  problem=Number(sql(`INSERT INTO problem(problem_id,title,author,auth,is_remote) VALUES(${quote(sourceDisplay)},'synthetic external evidence',${quote(bob.username)},1,1);SELECT LAST_INSERT_ID();`));
  metadataCreated=true;
  sql(`UPDATE external_account SET verified_identity=handle_key,verified_at=NOW(3),history_complete=1,last_success_at=NOW(3),rating=1700 WHERE id=${Number(id)};UPDATE external_sync_job SET stage='DONE',next_run_at=NULL WHERE account_id=${Number(id)};INSERT INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(${quote(sourceKey)},'CODEFORCES','synthetic evidence',1400,'["dp"]',NOW(3));INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(${Number(id)},987654321,${quote(sourceKey)},'OK',1000,1,'synthetic');INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code) VALUES(${problem},${quote(sourceDisplay)},${quote(alice.uid)},${quote(alice.username)},NOW(),0,'synthetic fixture');`);
  let publicView=await request(null,endpoint);assert.equal(publicView.accounts.length,1);assert.equal(publicView.accounts[0].proofCode,undefined);assert.equal(publicView.accounts[0].solved,1);assert.equal(publicView.summary.localSolved,1);assert.equal(publicView.summary.externalSolved,1);assert.equal(publicView.summary.uniqueSolved,1);assert.equal(publicView.summary.duplicatesRemoved,1);
  assert.equal(publicView.accounts[0].representativeRating,1400);assert.equal(publicView.accounts[0].coverage.find(a=>a.code==='dp').solved,1);
  report.checks.push('Explicitly synthetic ownership/result fixtures verify real anonymous filtering, HOJ/CF source-ID dedup and individual rated coverage; no real account ownership is claimed.');
  sql(`UPDATE external_sync_job SET failures=1,last_error='temporary source failure' WHERE account_id=${Number(id)};`);publicView=await request(null,endpoint);assert.equal(publicView.accounts[0].solved,1);assert.equal(publicView.accounts[0].stale,true);assert.equal(publicView.accounts[0].lastError,undefined);
  await request(alice,`/api/learning/accounts/${id}`,'DELETE');assert.equal(sql(`SELECT COUNT(*) FROM external_submission WHERE account_id=${Number(id)};`),'0');assert.deepEqual((await request(null,endpoint)).accounts,[]);
  report.checks.push('Source failure preserves cached totals and unlink cascades own evidence without deleting HOJ results.');
  if(process.env.HOJ_CF_LIVE_TEST==='1'){
    own=await request(alice,'/api/learning/accounts','POST',{platform:'CODEFORCES',handle:'tourist'});liveAccount=own.accounts[0].id;
    const end=Date.now()+240000;let account,lastProgress='';
    do{await new Promise(r=>setTimeout(r,4000));account=(await request(alice,'/api/learning/accounts')).accounts.find(a=>a.id===liveAccount);const progress=JSON.stringify({state:account.syncState,attempted:account.attempted,solved:account.solved});if(progress!==lastProgress){console.log('CF read-only sync: '+progress);lastProgress=progress;}if(account.historyComplete||account.syncState==='BACKOFF')break;}while(Date.now()<end);
    report.live={handle:'tourist',verified:account.verified,syncState:account.syncState,profileReceived:!!account.profileSyncedAt,historyComplete:account.historyComplete,attempted:account.attempted,solved:account.solved,ratedSolved:account.ratedSolved,lastError:account.lastError||null};
    assert.equal(account.verified,false);assert.deepEqual((await request(null,endpoint)).accounts,[]);
    if(!account.profileSyncedAt)report.live.unverifiedReason='Public API unavailable from this local backend; cached failure state validated, live data not verified.';
    else report.checks.push('Running Java 8 worker fetched CF public data without credentials; unverified live association remains private.');
  }
  report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    sql(`DELETE FROM external_account WHERE uid IN (${uids});DELETE FROM site_rating_history WHERE uid IN (${uids});DELETE FROM site_rating_profile WHERE uid IN (${uids});DELETE FROM judge WHERE uid IN (${uids});${problem?`DELETE FROM problem WHERE id=${problem};`:''}`);
    if(metadataCreated)sql(`DELETE FROM external_problem WHERE problem_key=${quote(sourceKey)} AND NOT EXISTS (SELECT 1 FROM external_submission WHERE problem_key=${quote(sourceKey)});`);
    for(const user of users)if(user.token)await request(user,'/api/logout').catch(()=>{});
    sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`),'0');report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'external-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
