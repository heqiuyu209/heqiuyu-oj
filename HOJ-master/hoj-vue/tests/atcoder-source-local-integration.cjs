// Opt-in local HTTP/MySQL checks. Only named synthetic fixtures are removed.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path');
const {execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_ATCODER_SOURCE_TEST!=='1')throw Error('Set HOJ_LOCAL_ATCODER_SOURCE_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(require('os').tmpdir(),'hoj-atcoder-source-integration');fs.mkdirSync(output,{recursive:true});
const users=['admin','admin','default_user'].map(role=>({role,uid:crypto.randomBytes(16).toString('hex'),username:'atsource_it_'+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(8).toString('hex')}));
const [alice,bob,student]=users,report={checks:[],cleaned:false};
const contest='codexit_'+Date.now(),task=contest+'_a',key='atcoder:task:'+task,display='AC-'+task,base='/api/admin/remote-sources/atcoder';
const quote=v=>"'"+String(v).replace(/'/g,"''")+"'",uids=users.map(u=>quote(u.uid)).join(',');let pid;
let liveCreated=false;
function sql(input){return execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
async function request(user,url,method='GET',data,status=200){const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(30000)});const body=await response.json();if(user&&response.headers.get('Authorization'))user.token=response.headers.get('Authorization');assert.equal(body.status,status,url+': '+body.msg);return body.data;}
const content=title=>({title,description:'<p>Synthetic statement with <var>n</var> for local verification.</p><script>unsafe()</script>',input:'<p>n</p>',output:'<p>answer</p>',examples:'<input>3</input><output>6</output>',hint:'<p>Fixture, not an official AtCoder problem.</p>',timeLimit:1000,memoryLimit:256});
(async()=>{
  const operations=[['','GET'],['/sync','POST'],['/refresh','POST'],['/imports','POST'],['/statements?problemKey='+key,'GET'],['/statements/en','POST'],['/statements/zh','POST']];
  for(const [url,method] of operations)await request(null,base+url,method,method==='POST'?{problemKey:key}:undefined,401);
  for(const user of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(user.uid)},${quote(user.username)},${quote(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(user.uid)},id FROM role WHERE role=${quote(user.role)};`);await request(user,'/api/login','POST',{username:user.username,password:user.password});assert(user.token);}
  for(const [url,method] of operations)await request(student,base+url,method,method==='POST'?{problemKey:key}:undefined,403);
  report.checks.push('All seven administrative source operations reject anonymous and ordinary users through real Shiro HTTP authorization.');
  assert(Number(sql("SELECT COUNT(*) FROM language WHERE oj IN ('AC','ME');"))>0,'An editor language configuration is required');
  sql(`INSERT INTO external_atcoder_problem(problem_key,task_id,contest_id,problem_index,title,estimated_difficulty,content_hash,seen_token,last_seen_at,changed_at) VALUES(${quote(key)},${quote(task)},${quote(contest)},'A','Synthetic source fixture',1200,${quote(crypto.createHash('sha256').update(key).digest('hex'))},'synthetic-fixture',NOW(3),NOW(3));`);
  const catalogue=await request(alice,base+'?keyword='+contest);assert.equal(catalogue.records[0].problemKey,key);assert.equal(catalogue.records[0].rating,null);assert.equal(catalogue.records[0].estimatedDifficulty,1200);
  let view=await request(alice,base+'/statements/en','POST',{problemKey:key,sourceHash:null,content:content('Synthetic English A')});
  const firstHash=view.source.contentHash;pid=Number(view.import[0].problem_id);assert(pid>0);assert(!JSON.stringify(view.source.content).includes('<script>'));assert.equal(sql(`SELECT auth FROM problem WHERE id=${pid};`),'2');assert(Number(sql(`SELECT COUNT(*) FROM problem_language WHERE pid=${pid};`))>0);
  assert(view.source.content.description.includes('$n$'));assert.equal(view.platform,'ATCODER');assert.equal(sql(`SELECT rating IS NULL FROM problem WHERE id=${pid};`),'1');
  await request(null,'/api/problems/'+pid+'/statements','GET',undefined,404);
  await request(bob,base+'/statements?problemKey='+key,'GET',undefined,403);await request(bob,base+'/imports','POST',{problemKey:key},403);
  assert.equal(view.refresh.enabled,false);
  await request(bob,base+'/refresh','POST',{problemKey:key,autoRefresh:true},403);
  view=await request(alice,base+'/refresh','POST',{problemKey:key,autoRefresh:true});assert.equal(view.refresh.enabled,true);assert(view.refresh.nextCheckAt);assert.equal(sql(`SELECT auto_refresh FROM remote_problem_import WHERE problem_key=${quote(key)};`),'1');
  view=await request(alice,base+'/refresh','POST',{problemKey:key,autoRefresh:false});assert.equal(view.refresh.enabled,false);assert.equal(view.refresh.nextCheckAt,null);
  await request(alice,base+'/refresh','POST',{problemKey:key},400);
  report.checks.push('Real policy endpoint enforces owner and explicit boolean, schedules a daily check and cancels it without changing source versions.');
  report.checks.push('Manual source save creates a hidden remote question with real language/tag links and safe HTML; another administrator cannot maintain its private source.');
  sql(`UPDATE problem SET auth=1,title='Preserved library title',description='Preserved library statement' WHERE id=${pid};`);
  view=await request(alice,base+'/statements/zh','POST',{problemKey:key,sourceHash:firstHash,content:content('合成中文题面')});assert.equal(view.translationStale,false);
  let published=await request(null,'/api/problems/'+pid+'/statements');assert(published.available);assert.equal(published.translation.content.title,'合成中文题面');
  view=await request(alice,base+'/statements/en','POST',{problemKey:key,sourceHash:firstHash,content:content('Synthetic English B')});assert.notEqual(view.source.contentHash,firstHash);
  published=await request(null,'/api/problems/'+pid+'/statements');assert.equal(published.translationStale,true);assert.equal(published.translation,null);
  await request(alice,base+'/statements/zh','POST',{problemKey:key,sourceHash:firstHash,content:content('Stale translation')},409);
  await request(alice,base+'/statements/en','POST',{problemKey:key,sourceHash:firstHash,content:content('Stale overwrite')},409);
  assert.equal(sql(`SELECT title FROM problem WHERE id=${pid};`),'Preserved library title');assert.equal(sql(`SELECT description FROM problem WHERE id=${pid};`),'Preserved library statement');
  assert.equal(sql(`SELECT COUNT(*) FROM remote_statement_version WHERE problem_key=${quote(key)};`),'3');
  report.checks.push('Anonymous bilingual statements require public standalone visibility; updating English preserves library edits, hides stale Chinese and rejects stale saves with 409.');
  sql(`UPDATE problem SET is_group=1 WHERE id=${pid};`);await request(null,'/api/problems/'+pid+'/statements','GET',undefined,404);sql(`UPDATE problem SET is_group=0,auth=3 WHERE id=${pid};`);await request(null,'/api/problems/'+pid+'/statements','GET',undefined,404);sql(`UPDATE problem SET auth=1 WHERE id=${pid};UPDATE remote_problem_import SET last_requested_at=DATE_SUB(NOW(3),INTERVAL 2 MINUTE) WHERE problem_key=${quote(key)};`);
  // Publish revocation and this synthetic queued task atomically. A POST followed by a
  // separate revocation races an already-running read and may legitimately enter BACKOFF.
  sql(`START TRANSACTION;DELETE FROM user_role WHERE uid=${quote(alice.uid)};UPDATE remote_problem_import SET state='QUEUED',next_run_at=NOW(3),lease_token=NULL,lease_until=NULL WHERE problem_key=${quote(key)} AND requested_by=${quote(alice.uid)};COMMIT;`);
  const deadline=Date.now()+25000;let state;
  do{await new Promise(resolve=>setTimeout(resolve,1000));state=sql(`SELECT state FROM remote_problem_import WHERE problem_key=${quote(key)};`);}while(state!=='FAILED'&&Date.now()<deadline);
  assert.equal(state,'FAILED');assert.equal(sql(`SELECT COUNT(*) FROM remote_statement_version WHERE problem_key=${quote(key)};`),'3');
  report.checks.push('Group/private variants never expose bilingual content; an atomically queued revoked fixture is cancelled by the running Java 8 worker before any source fetch or new version.');
  const status=await request(bob,base+'?keyword=abc086_a');report.catalogue={state:status.status.state,total:status.status.total,lastSuccessAt:status.status.lastSuccessAt,lastError:status.status.lastError};
  assert(status.status.lastSuccessAt,'Live catalogue worker has not completed its first successful sync');assert(status.status.total>1000,'Live AtCoder catalogue is unexpectedly small');
  report.checks.push('Running worker fetched the real AtCoder Problems public catalogue; synthetic rows are not counted as live source evidence.');
  if(process.env.HOJ_ATCODER_STATEMENT_LIVE_TEST==='1'){
    const liveKey='atcoder:task:abc086_a';
    assert.equal(sql("SELECT (SELECT COUNT(*) FROM remote_problem_import WHERE problem_key='atcoder:task:abc086_a')+(SELECT COUNT(*) FROM problem WHERE problem_id='AC-abc086_a');"),'0','Existing live task must be preserved; choose another isolated fixture before running.');liveCreated=true;
    await request(bob,base+'/imports','POST',{problemKey:liveKey});let live;const deadline=Date.now()+100000;
    do{await new Promise(r=>setTimeout(r,1500));live=await request(bob,base+'/statements?problemKey='+liveKey);}while(!live.source&&!['FAILED'].includes(live.import[0]?.state)&&Date.now()<deadline);
    assert(live.source,'Official AtCoder import did not publish: '+JSON.stringify(live.import));assert.equal(live.source.origin,'SOURCE');assert.equal(live.refresh.enabled,true);assert(live.refresh.lastSuccessAt);assert(live.refresh.nextCheckAt);assert.equal(live.source.content.title,'Product');assert.equal(live.source.content.timeLimit,2000);assert(live.source.content.examples.includes('<input>3 4</input><output>Even</output>'));assert.equal(sql("SELECT auth FROM problem WHERE problem_id='AC-abc086_a';"),'2');report.live={problemKey:liveKey,origin:live.source.origin,contentHash:live.source.contentHash};
    report.checks.push('Running Java 8 worker fetched the official English Product page, parsed limits and examples, and created a private AC-abc086_a question. No remote login or code submission.');
    const checked=Date.parse(live.refresh.lastCheckedAt);sql(`UPDATE remote_problem_import SET next_run_at=NOW(3) WHERE problem_key='atcoder:task:abc086_a' AND requested_by=${quote(bob.uid)} AND state='DONE' AND auto_refresh=1;`);
    const refreshDeadline=Date.now()+100000;let repeated;
    do{await new Promise(r=>setTimeout(r,1500));repeated=await request(bob,base+'/statements?problemKey='+liveKey);}while(!(Date.parse(repeated.refresh.lastCheckedAt)>checked&&repeated.import[0].state==='DONE')&&Date.now()<refreshDeadline);
    assert(Date.parse(repeated.refresh.lastCheckedAt)>checked,'The actual scheduled refresh did not run');assert.equal(repeated.source.contentHash,live.source.contentHash);assert.equal(sql("SELECT COUNT(*) FROM remote_statement_version WHERE problem_key='atcoder:task:abc086_a';"),'1');assert(repeated.refresh.enabled);report.live.periodicRefreshVerified=true;
    report.checks.push('The actual daily-check scheduler re-fetched the same official page after this fixture alone became due, advanced check/success times and created no duplicate version.');
    // A deliberately synthetic earlier source/copy exercises the changed-content branch;
    // only the following refresh is evidence of an actual official-source fetch.
    const older=await request(bob,base+'/statements/en','POST',{problemKey:liveKey,sourceHash:repeated.source.contentHash,content:{...repeated.source.content,title:'Synthetic earlier source for copy-sync verification'}});
    assert.equal(older.source.origin,'MANUAL');assert.notEqual(older.source.contentHash,live.source.contentHash);
    const literal=value=>value==null?'NULL':String(value)===''?"''":`CONVERT(0x${Buffer.from(String(value),'utf8').toString('hex')} USING utf8mb4)`;
    const columns={title:'title',description:'description',input:'input',output:'output',hint:'hint',examples:'examples',time_limit:'timeLimit',memory_limit:'memoryLimit'};
    sql(`UPDATE problem SET ${Object.entries(columns).map(([column,field])=>column+'='+literal(older.source.content[field])).join(',')},auth=1,rating=1777 WHERE problem_id='AC-abc086_a' AND author=${quote(bob.username)};`);
    await request(bob,base+'/statements/zh','POST',{problemKey:liveKey,sourceHash:older.source.contentHash,content:{...older.source.content,title:'合成旧版译文'}});
    await request(bob,base+'/refresh','POST',{problemKey:liveKey,autoRefresh:true});
    sql(`UPDATE remote_problem_import SET next_run_at=NOW(3) WHERE problem_key='atcoder:task:abc086_a' AND requested_by=${quote(bob.uid)} AND state='DONE' AND auto_refresh=1;`);
    const copyDeadline=Date.now()+100000;let updated;
    do{await new Promise(r=>setTimeout(r,1500));updated=await request(bob,base+'/statements?problemKey='+liveKey);}while(!(updated.source.contentHash===live.source.contentHash&&updated.import[0].state==='DONE')&&Date.now()<copyDeadline);
    assert.equal(updated.source.contentHash,live.source.contentHash,'The worker must replace the synthetic prior source with the official source');assert.equal(updated.source.origin,'SOURCE');
    for(const [column,field] of Object.entries(columns)){
      const expected=Buffer.from(String(updated.source.content[field]),'utf8').toString('hex').toUpperCase();
      assert.equal(sql(`SELECT HEX(CAST(${column} AS CHAR CHARACTER SET utf8mb4)) FROM problem WHERE problem_id='AC-abc086_a';`),expected,'Library field did not follow the official refresh: '+field);
    }
    assert.equal(sql("SELECT CONCAT(auth,':',rating) FROM problem WHERE problem_id='AC-abc086_a';"),'1:1777');
    const local=await request(null,'/api/get-problem-detail?problemId=AC-abc086_a');assert.equal(local.problem.title,'Product');assert.equal(local.problem.rating,1777);
    const submission=await request(student,'/api/personal-remote/accounts','POST',{pid:'AC-abc086_a'});
    assert.equal(submission.platform,'ATCODER');assert.equal(submission.submissionSupported,true);assert.deepEqual(submission.accounts,[]);
    const latest=await request(null,'/api/problems/'+Number(updated.import[0].problem_id)+'/statements');assert(latest.translationStale);assert.equal(latest.translation,null);
    assert.equal(sql("SELECT COUNT(*) FROM remote_statement_version WHERE problem_key='atcoder:task:abc086_a';"),'3');
    report.live.libraryCopyRefreshVerified=true;report.live.previousCopy='SYNTHETIC_MANUAL_SOURCE_AND_MATCHING_LIBRARY';
    report.checks.push('After an explicitly synthetic previous source/copy, the actual worker re-fetches official Product, updates all eight library fields byte-exactly, keeps site Rating/visibility and hides the stale translation; the normal public problem endpoint returns the updated title.');
    report.checks.push('A normal user can inspect personal-submission availability for the actual imported AtCoder task using its canonical catalogue mapping; an empty own-account list neither invents credentials nor contacts the source.');

  }
  report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    sql(`DELETE FROM remote_statement_current WHERE problem_key=${quote(key)};DELETE FROM remote_statement_version WHERE problem_key=${quote(key)};DELETE FROM remote_problem_import WHERE problem_key=${quote(key)};DELETE FROM external_atcoder_problem WHERE problem_key=${quote(key)};${pid?`DELETE FROM problem WHERE id=${pid};`:''}`);
    if(liveCreated)sql(`START TRANSACTION;SELECT problem_key FROM external_atcoder_problem WHERE problem_key='atcoder:task:abc086_a' FOR UPDATE;DELETE FROM remote_problem_import WHERE problem_key='atcoder:task:abc086_a' AND requested_by=${quote(bob.uid)};DELETE c FROM remote_statement_current c JOIN remote_statement_version v ON v.id=c.version_id WHERE c.problem_key='atcoder:task:abc086_a' AND v.created_by=${quote(bob.uid)};DELETE FROM remote_statement_version WHERE problem_key='atcoder:task:abc086_a' AND created_by=${quote(bob.uid)};DELETE FROM problem WHERE problem_id='AC-abc086_a' AND author=${quote(bob.username)};COMMIT;`);
    for(const user of users)if(user.token)await request(user,'/api/logout').catch(()=>{});
    sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`),'0');report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'atcoder-source-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
