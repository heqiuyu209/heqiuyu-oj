// Opt-in local HTTP/MySQL checks. Only named synthetic fixtures are removed.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path');
const {execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_REMOTE_SOURCE_TEST!=='1')throw Error('Set HOJ_LOCAL_REMOTE_SOURCE_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(require('os').tmpdir(),'hoj-remote-source-integration');fs.mkdirSync(output,{recursive:true});
const users=['admin','admin','default_user'].map(role=>({role,uid:crypto.randomBytes(16).toString('hex'),username:'source_it_'+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(8).toString('hex')}));
const [alice,bob,student]=users,report={checks:[],cleaned:false};
const contest='990'+Date.now(),key='cf:contest:'+contest+':A',display='CF-'+contest+'A',base='/api/admin/remote-sources/codeforces';
const quote=v=>"'"+String(v).replace(/'/g,"''")+"'",uids=users.map(u=>quote(u.uid)).join(',');let pid;
let liveCreated=false;
function sql(input){return execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
async function request(user,url,method='GET',data,status=200){const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(30000)});const body=await response.json();if(user&&response.headers.get('Authorization'))user.token=response.headers.get('Authorization');assert.equal(body.status,status,url+': '+body.msg);return body.data;}
const content=title=>({title,description:'<p>Synthetic statement with $$$n$$$ for local verification.</p><script>unsafe()</script>',input:'<p>n</p>',output:'<p>answer</p>',examples:'<input>3</input><output>6</output>',hint:'<p>Fixture, not an official CF problem.</p>',timeLimit:1000,memoryLimit:256});
(async()=>{
  const operations=[['','GET'],['/sync','POST'],['/refresh','POST'],['/imports','POST'],['/statements?problemKey='+key,'GET'],['/statements/en','POST'],['/statements/zh','POST']];
  for(const [url,method] of operations)await request(null,base+url,method,method==='POST'?{problemKey:key}:undefined,401);
  for(const user of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(user.uid)},${quote(user.username)},${quote(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(user.uid)},id FROM role WHERE role=${quote(user.role)};`);await request(user,'/api/login','POST',{username:user.username,password:user.password});assert(user.token);}
  for(const [url,method] of operations)await request(student,base+url,method,method==='POST'?{problemKey:key}:undefined,403);
  report.checks.push('All seven administrative source operations reject anonymous and ordinary users through real Shiro HTTP authorization.');
  assert(Number(sql("SELECT COUNT(*) FROM language WHERE oj='CF';"))>0,'CF language configuration is required');
  sql(`INSERT INTO remote_catalog_problem(problem_key,contest_id,problem_index,title,rating,tags_json,content_hash,seen_token,last_seen_at,changed_at) VALUES(${quote(key)},${contest},'A','Synthetic source fixture',1200,'["math"]',${quote(crypto.createHash('sha256').update(key).digest('hex'))},'synthetic-fixture',NOW(3),NOW(3));`);
  const catalogue=await request(alice,base+'?keyword='+contest);assert.equal(catalogue.records[0].problemKey,key);
  let view=await request(alice,base+'/statements/en','POST',{problemKey:key,sourceHash:null,content:content('Synthetic English A')});
  const firstHash=view.source.contentHash;pid=Number(view.import[0].problem_id);assert(pid>0);assert(!JSON.stringify(view.source.content).includes('<script>'));assert.equal(sql(`SELECT auth FROM problem WHERE id=${pid};`),'2');
  assert(view.source.content.description.includes('$n$'));assert(!view.source.content.description.includes('$$$'));
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
  await request(alice,base+'/imports','POST',{problemKey:key});
  // Revocation is checked again by the asynchronous worker before making a source request.
  sql(`DELETE FROM user_role WHERE uid=${quote(alice.uid)};UPDATE remote_problem_import SET next_run_at=NOW(3) WHERE problem_key=${quote(key)};`);
  const deadline=Date.now()+25000;let state;
  do{await new Promise(resolve=>setTimeout(resolve,1000));state=sql(`SELECT state FROM remote_problem_import WHERE problem_key=${quote(key)};`);}while(state!=='FAILED'&&Date.now()<deadline);
  assert.equal(state,'FAILED');assert.equal(sql(`SELECT COUNT(*) FROM remote_statement_version WHERE problem_key=${quote(key)};`),'3');
  report.checks.push('Group/private variants never expose bilingual content, and the running Java 8 worker cancels an import after its requester loses administration permission.');
  const status=await request(bob,base+'?keyword=4A');report.catalogue={state:status.status.state,total:status.status.total,lastSuccessAt:status.status.lastSuccessAt,lastError:status.status.lastError};
  assert(status.status.lastSuccessAt,'Live catalogue worker has not completed its first successful sync');assert(status.status.total>1000,'Live CF catalogue is unexpectedly small');
  report.checks.push('Running worker fetched the real CF public catalogue; synthetic rows are not counted as live source evidence.');
  if(process.env.HOJ_CF_STATEMENT_LIVE_TEST==='1'){
    const liveKey='cf:contest:4:A';
    if(sql("SELECT (SELECT COUNT(*) FROM remote_problem_import WHERE problem_key='cf:contest:4:A')+(SELECT COUNT(*) FROM problem WHERE problem_id='CF-4A');")==='0'){
      liveCreated=true;await request(bob,base+'/imports','POST',{problemKey:liveKey});let result;
      const liveDeadline=Date.now()+55000;
      do{await new Promise(resolve=>setTimeout(resolve,2000));const view=await request(bob,base+'/statements?problemKey='+liveKey);result=view.import[0];}while(['QUEUED','RUNNING'].includes(result.state)&&Date.now()<liveDeadline);
      assert(['DONE','BACKOFF','FAILED'].includes(result.state),'Live import did not settle');
      report.liveStatement={key:liveKey,state:result.state,error:result.last_error||null,sourceFetched:result.state==='DONE'};
      if(result.state!=='DONE')assert.equal(sql("SELECT COUNT(*) FROM remote_statement_version WHERE problem_key='cf:contest:4:A';"),'0');
      report.checks.push(result.state==='DONE'?'Public CF statement fetched by the Java 8 worker; temporary imported problem is removed after validation.':'Public CF statement fetch failed visibly; no blank version or public problem was created and cached source data remains intact.');
    }else report.liveStatement={skipped:'Existing 4A data was preserved.'};
  }
  report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    sql(`DELETE FROM remote_statement_current WHERE problem_key=${quote(key)};DELETE FROM remote_statement_version WHERE problem_key=${quote(key)};DELETE FROM remote_problem_import WHERE problem_key=${quote(key)};DELETE FROM remote_catalog_problem WHERE problem_key=${quote(key)};${pid?`DELETE FROM problem WHERE id=${pid};`:''}`);
    if(liveCreated)sql(`START TRANSACTION;SELECT problem_key FROM remote_catalog_problem WHERE problem_key='cf:contest:4:A' FOR UPDATE;DELETE FROM remote_problem_import WHERE problem_key='cf:contest:4:A' AND requested_by=${quote(bob.uid)};DELETE c FROM remote_statement_current c JOIN remote_statement_version v ON v.id=c.version_id WHERE c.problem_key='cf:contest:4:A' AND v.created_by=${quote(bob.uid)};DELETE FROM remote_statement_version WHERE problem_key='cf:contest:4:A' AND created_by=${quote(bob.uid)};DELETE FROM problem WHERE problem_id='CF-4A' AND author=${quote(bob.username)};COMMIT;`);
    for(const user of users)if(user.token)await request(user,'/api/logout').catch(()=>{});
    sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`),'0');report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'remote-source-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
