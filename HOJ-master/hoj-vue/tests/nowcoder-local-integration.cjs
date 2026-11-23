// Opt-in local HTTP/MySQL checks. Identity/result fixtures are explicitly synthetic.
// The optional live check only reads an unverified public account; it never edits the remote profile or submits code.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path');
const {execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_NOWCODER_TEST!=='1')throw Error('Set HOJ_LOCAL_NOWCODER_TEST=1.');
// HTTP linking must be tested against an isolated backend with its worker disabled;
// fencing after the response leaves a window where an invented source ID can be claimed.
const testBackend=process.env.HOJ_TEST_BACKEND_CONTAINER;
const testMysql=process.env.HOJ_TEST_MYSQL_CONTAINER;
if(!testBackend||!testMysql||testBackend==='hoj-master-hoj-backend-1'||testMysql==='hoj-master-hoj-mysql-1')throw Error('Use an isolated backend and disposable MySQL; set HOJ_TEST_BACKEND_CONTAINER and HOJ_TEST_MYSQL_CONTAINER.');
if(process.env.HOJ_NOWCODER_LIVE_TEST==='1'||process.env.HOJ_NOWCODER_CONTEXT_LIVE_TEST==='1')throw Error('This synthetic linking test disables source workers. Run live source validation separately.');
const backend=JSON.parse(execFileSync('docker',['inspect',testBackend],{encoding:'utf8',windowsHide:true,timeout:30000}))[0];
const database=JSON.parse(execFileSync('docker',['inspect',testMysql],{encoding:'utf8',windowsHide:true,timeout:30000}))[0];
assert(backend.State.Running&&backend.Config.Env.includes('HOJ_EXTERNAL_SYNC_ENABLED=false'),'The isolated backend must disable public-source synchronization.');
assert(database.State.Running&&backend.Config.Env.includes('MYSQL_HOST='+testMysql),'The backend must use the specified disposable MySQL.');
const fixtureId=backend.Config.Labels?.['hoj.test.id'];
assert(/^[a-f0-9]{32}$/.test(fixtureId||'')&&database.Config.Labels?.['hoj.test.id']===fixtureId,'Both containers must belong to the same isolated test fixture.');
assert(!Object.keys(database.HostConfig.PortBindings||{}).length&&database.HostConfig.Tmpfs?.['/var/lib/mysql']&&!database.Mounts.some(m=>m.Type==='volume'),'The test database must use private tmpfs with no published port or persistent volume.');
const dbNetworks=Object.keys(database.NetworkSettings.Networks);assert.equal(dbNetworks.length,1);
const network=JSON.parse(execFileSync('docker',['network','inspect',dbNetworks[0]],{encoding:'utf8',windowsHide:true,timeout:30000}))[0];
assert(network.Internal&&Object.keys(network.Containers).length===2&&network.Containers[backend.Id]&&network.Containers[database.Id],'Only this backend and database may join the internal database network.');
const backendPort=backend.NetworkSettings.Ports['6688/tcp']?.[0]?.HostPort;
assert(backendPort,'The isolated backend must publish its API port.');
const apiBase='http://127.0.0.1:'+backendPort;
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(require('os').tmpdir(),'hoj-nowcoder-integration');fs.mkdirSync(output,{recursive:true});
const users=Array.from({length:2},()=>({uid:crypto.randomBytes(16).toString('hex'),username:'nc_it_'+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(8).toString('hex')}));
const [alice,bob]=users,report={checks:[],cleaned:false},sourceId=String(800000000000+crypto.randomInt(100000000000)),sourceKey='nowcoder:acm:'+sourceId;
const quote=v=>"'"+String(v).replace(/'/g,"''")+"'",uids=users.map(u=>quote(u.uid)).join(',');let problem,metadataCreated=false;
function sql(input){return execFileSync('docker',['exec','-i',testMysql,'sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
async function request(user,url,method='GET',data,status=200){
  const response=await fetch(apiBase+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(30000)});
  const body=await response.json();if(user&&response.headers.get('Authorization'))user.token=response.headers.get('Authorization');assert.equal(body.status,status,url+': '+body.msg);return body.data;
}
(async()=>{
  for(const user of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(user.uid)},${quote(user.username)},${quote(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(user.uid)},id FROM role WHERE role='default_user';`);await request(user,'/api/login','POST',{username:user.username,password:user.password});assert(user.token);}
  await request(alice,'/api/learning/accounts','POST',{platform:'NOWCODER',handle:'username'},400);await request(alice,'/api/learning/accounts','POST',{platform:'NOWCODER',handle:'00123'},400);
  let own=await request(alice,'/api/learning/accounts','POST',{platform:'NOWCODER',handle:sourceId});const id=own.accounts[0].id;sql(`UPDATE external_sync_job SET next_run_at=NULL,lease_token=NULL,lease_until=NULL WHERE account_id=${Number(id)};`);
  own=await request(alice,`/api/learning/accounts/${id}/proof`,'POST',{});assert(own.accounts[0].proofCode.startsWith('HOJ-'));assert.equal(own.accounts[0].solved,null);assert.equal(own.accounts[0].statisticsScope,'PUBLIC_PROFILE_CODING');
  const endpoint='/api/learning/users/'+alice.uid+'/external';assert.deepEqual((await request(null,endpoint)).accounts,[]);await request(bob,`/api/learning/accounts/${id}/proof`,'POST',{},404);await request(bob,`/api/learning/accounts/${id}`,'DELETE',undefined,404);
  await request(alice,'/api/learning/accounts','POST',{platform:'NOWCODER',handle:sourceId},409);await request(alice,`/api/learning/accounts/${id}/sync`,'POST',{},429);
  report.checks.push('Real HTTP/MySQL validates numeric identity, private unknown counts/proof, owner-only mutation, duplicates and cooldown.');
  // Synthetic published fixture tests the API projection only, not source scanning.
  // Seed before history_complete to keep the background worker off this invented ID.
  sql(`INSERT INTO external_nowcoder_context_job(account_id,stage,scan_token,published_token,next_run_at,last_success_at) VALUES(${Number(id)},'DONE','synthetic-context','synthetic-context',DATE_ADD(NOW(3),INTERVAL 1 DAY),NOW(3));`);
  problem=Number(sql(`INSERT INTO problem(problem_id,title,author,auth,is_remote) VALUES(${quote('NC-'+sourceId)},'synthetic NowCoder evidence',${quote(bob.username)},1,1);SELECT LAST_INSERT_ID();`));metadataCreated=true;
  sql(`INSERT INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) VALUES(${quote(sourceKey)},'NOWCODER','synthetic NowCoder task',NULL,'[]',NOW(3));UPDATE external_account SET verified_identity=handle_key,verified_at=NOW(3),history_complete=1,last_success_at=NOW(3),rating=947,max_rating=1049,rated_contests=1 WHERE id=${Number(id)};UPDATE external_nowcoder_profile SET display_name='synthetic nickname',challenged_count=1,solved_count=1,submission_count=1 WHERE account_id=${Number(id)};UPDATE external_sync_job SET stage='DONE',next_run_at=NULL WHERE account_id=${Number(id)};INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(${Number(id)},987654321,${quote(sourceKey)},'OK',1000,0,'synthetic');INSERT INTO external_rating_event(account_id,contest_id,title,contest_rank,old_rating,new_rating,occurred_at) VALUES(${Number(id)},99785,'synthetic history',1128,907,947,1737111600);INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code) VALUES(${problem},${quote('NC-'+sourceId)},${quote(alice.uid)},${quote(alice.username)},NOW(),0,'synthetic fixture');`);
  sql(`INSERT INTO external_nowcoder_contest(account_id,scan_token,contest_id,title,start_ms,end_ms,team,participation,restricted,official_accepted,contest_rank,detail_state) VALUES(${Number(id)},'synthetic-context',99785,'synthetic public contest',900000,1500000,0,'OFFICIAL',0,1,12,'DONE'),(${Number(id)},'synthetic-context',99786,'synthetic restricted contest',900000,1500000,0,'OFFICIAL',1,NULL,NULL,'RESTRICTED'),(${Number(id)},'synthetic-context',99787,'synthetic team contest',900000,1500000,1,'OFFICIAL',0,3,9,'TEAM');INSERT INTO external_nowcoder_contest_problem VALUES(${Number(id)},'synthetic-context',99785,'A',${quote(sourceKey)},'synthetic task');INSERT INTO external_nowcoder_contest_submission VALUES(${Number(id)},'synthetic-context',99785,987654321,${quote(sourceKey)},'A','OK',1000000);INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(${Number(id)},987654322,${quote(sourceKey)},'OK',2000,0,'synthetic');`);
  let view=await request(null,endpoint),account=view.accounts[0];assert.equal(account.platform,'NOWCODER');assert.equal(account.displayName,'synthetic nickname');assert.equal(account.proofCode,undefined);assert.equal(account.solved,1);assert.equal(account.individualSolved,null);assert.equal(account.individualStatusVerified,false);assert.equal(account.coverageAvailable,false);assert.deepEqual(account.coverage,[]);assert.equal(account.representativeRating,null);assert.equal(account.difficultySource,'UNAVAILABLE');assert.equal(account.identityContinuity,'NUMERIC_USER_ID');assert.equal(account.ratingHistory[0].contestUrl,'https://ac.nowcoder.com/acm/contest/99785');assert.equal(view.summary.uniqueSolved,1);assert.equal(view.summary.duplicatesRemoved,1);
  assert.equal(account.contestContext.totalContests,3);assert.equal(account.contestContext.coveredContests,1);assert.equal(account.contestContext.submissionCoverageComplete,false);
  const context=account.contestContext.contests.find(c=>c.contestId===99785);assert.equal(context.contestSolved,1);assert.equal(context.postContestSolved,1);assert.equal(context.contestSubmissions,1);assert.equal(context.url,'https://ac.nowcoder.com/acm/contest/99785');
  for(const row of account.contestContext.contests.filter(c=>c.contestId!==99785)){assert.equal(row.contestSolved,null);assert.equal(row.postContestSolved,null);}
  assert.equal(account.contestContext.lastError,undefined);assert(!JSON.stringify(account.contestContext).includes('synthetic-context'));
  sql(`UPDATE external_nowcoder_context_job SET failures=1,last_error='synthetic context failure' WHERE account_id=${Number(id)};`);
  assert.equal((await request(null,endpoint)).accounts[0].contestContext.lastError,undefined);assert.equal((await request(alice,'/api/learning/accounts')).accounts[0].contestContext.lastError,'synthetic context failure');
  report.checks.push('Synthetic context projection separates contest and post-contest evidence without inflating canonical totals; team/restricted details stay null, errors remain owner-only and internal scan tokens are absent.');
  assert.equal((await request(alice,`/api/learning/accounts/${id}/submission-credentials`)).submissionAvailable,false);
  await request(alice,`/api/learning/accounts/${id}/submission-credentials`,'PUT',{cookieHeader:'unused-fixture',version:0,authorized:true},400);
  report.checks.push('Synthetic verified evidence validates explicit NC source dedup, official rating URLs, scope and unavailable difficulty/independent evidence; malformed cookies are rejected server-side and code dispatch remains unavailable.');
  sql(`UPDATE external_sync_job SET failures=1,last_error='synthetic source failure' WHERE account_id=${Number(id)};`);account=(await request(null,endpoint)).accounts[0];assert.equal(account.solved,1);assert.equal(account.stale,true);assert.equal(account.lastError,undefined);
  await request(alice,`/api/learning/accounts/${id}`,'DELETE');for(const table of ['external_nowcoder_profile','external_rating_event','external_submission','external_nowcoder_context_job','external_nowcoder_contest','external_nowcoder_contest_problem','external_nowcoder_contest_submission'])assert.equal(sql(`SELECT COUNT(*) FROM ${table} WHERE account_id=${Number(id)};`),'0');assert.equal(sql(`SELECT COUNT(*) FROM judge WHERE uid=${quote(alice.uid)};`),'1');
  report.checks.push('Source failure retains cached counts; unlink cascades selected source evidence while preserving local results.');
  if(process.env.HOJ_NOWCODER_LIVE_TEST==='1'){
    own=await request(alice,'/api/learning/accounts','POST',{platform:'NOWCODER',handle:'399269849'});const live=own.accounts.find(a=>a.platform==='NOWCODER').id;let account,last='',end=Date.now()+240000;
    do{await new Promise(r=>setTimeout(r,4000));account=(await request(alice,'/api/learning/accounts')).accounts.find(a=>a.id===live);const progress=JSON.stringify({state:account.syncState,attempted:account.attempted,solved:account.solved});if(progress!==last){console.log('NowCoder read-only sync: '+progress);last=progress;}if(account.historyComplete||account.syncState==='BACKOFF')break;}while(Date.now()<end);
    report.live={uid:account.handle,verified:account.verified,syncState:account.syncState,profileReceived:!!account.profileSyncedAt,historyComplete:account.historyComplete,attempted:account.attempted,solved:account.solved,rating:account.rating,ratedContests:account.ratedContests,lastError:account.lastError||null,statisticsScope:account.statisticsScope,counts:account.metadata};delete report.live.counts.displayName;
    assert.equal(account.verified,false);assert.deepEqual((await request(null,endpoint)).accounts,[]);assert.equal(account.historyComplete,true,'Live regular-practice history must finish; failures remain visible.');assert(account.rating>0);assert(account.solved>0);assert.equal(account.solved,account.metadata.solvedCount);assert.equal(account.attempted,account.metadata.challengedCount);
    report.checks.push('Running Java 8 worker read official public profile/rating, all regular-practice pages and the stable-head recheck; unverified data remained private and is not ownership proof.');
    if(process.env.HOJ_NOWCODER_CONTEXT_LIVE_TEST==='1'){
      const contextDeadline=Date.now()+600000;let previous='';
      do{
        account=(await request(alice,'/api/learning/accounts')).accounts.find(a=>a.id===live);
        const progress=JSON.stringify(account.contestContext);if(progress!==previous){console.log('NowCoder public contest context: '+progress);previous=progress;}
        if(account.contestContext.available || account.contestContext.syncState==='BACKOFF')break;
        await new Promise(r=>setTimeout(r,4000));
      }while(Date.now()<contextDeadline);
      report.live.contestContext=account.contestContext;assert.equal(account.contestContext.available,true,'Actual source context scan must finish; a seeded API fixture is not sufficient.');
      assert.equal(account.contestContext.listComplete,true);assert(account.contestContext.totalContests>=5);
      const official=account.contestContext.contests.find(c=>c.contestId===99785);assert(official);assert.equal(official.detailState,'DONE');assert.equal(official.contestSubmissions,11);
      for(const row of account.contestContext.contests.filter(c=>[98246,95480].includes(c.contestId))){assert.equal(row.detailState,'RESTRICTED');assert.equal(row.contestSolved,null);assert.equal(row.postContestSolved,null);}
      assert.equal(Number(sql(`SELECT COUNT(*) FROM external_submission WHERE account_id=${Number(live)};`)),account.metadata.submissionCount);assert.deepEqual((await request(null,endpoint)).accounts,[]);
      report.checks.push('Actual Java 8 worker completes public joined-history/mapping/filtered-status scans without cookies: 99785 has 11 contest submissions, known restricted contests expose no individual detail, canonical ledger count stays source-exact and the unverified account remains private.');
    }
  }
  report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    sql(`DELETE FROM external_account WHERE uid IN (${uids});DELETE FROM site_rating_history WHERE uid IN (${uids});DELETE FROM site_rating_profile WHERE uid IN (${uids});DELETE FROM judge WHERE uid IN (${uids});${problem?`DELETE FROM problem WHERE id=${problem} AND author=${quote(bob.username)};`:''}`);
    if(metadataCreated)sql(`DELETE FROM external_problem WHERE problem_key=${quote(sourceKey)} AND NOT EXISTS (SELECT 1 FROM external_submission WHERE problem_key=${quote(sourceKey)});`);
    for(const user of users)if(user.token)await request(user,'/api/logout').catch(()=>{});sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`),'0');report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'nowcoder-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
