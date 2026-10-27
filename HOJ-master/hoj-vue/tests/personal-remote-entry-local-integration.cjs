// Opt-in HTTP/DB/Redis integration for creation only. Requires the source worker STOPPED.
// A synthetic offer avoids all source IO. Every created task is cancelled and removed before returning.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path');
const {execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_PERSONAL_ENTRY_TEST!=='1')throw Error('Set HOJ_LOCAL_PERSONAL_ENTRY_TEST=1.');
const compose=path.resolve(__dirname,'../../compose.yaml'),output=process.env.PAPER_REVIEW_OUTPUT||path.join(require('os').tmpdir(),'hoj-personal-entry-review');
fs.mkdirSync(output,{recursive:true});const report={checks:[],cleaned:false};
const random=crypto.randomBytes(16).toString('hex'),uid=random,other=crypto.randomBytes(16).toString('hex');
const actor={username:'entry_it_'+random.slice(0,10),password:crypto.randomBytes(8).toString('hex'),token:''},handle='entry_it_'+random.slice(0,12),remote='CF-'+(900000000+crypto.randomInt(90000000))+'A';
const quote=v=>"'"+String(v).replace(/'/g,"''")+"'",field=v=>{const text=v==null?'':String(v);return text.length+':'+text;},hash=v=>crypto.createHash('sha256').update(v).digest('hex');
function sql(input){return execFileSync('docker',['compose','-p','hoj-master','-f',compose,'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
function redis(command,key,value){return execFileSync('docker',['compose','-p','hoj-master','-f',compose,'exec','-T','hoj-redis','sh','-c','exec redis-cli --no-auth-warning '+(command==='SET'?'-x EVAL \'return redis.call("SET",KEYS[1],ARGV[1],"EX",300)\' 1 "$1"':'DEL "$1"'),'sh',key],{input:value,encoding:'utf8',windowsHide:true,timeout:10000}).trim();}
async function request(url,expected,method='GET',data,authenticated=true){const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(authenticated&&actor.token?{Authorization:actor.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(20000)});const body=await response.json();if(authenticated&&response.headers.get('authorization'))actor.token=response.headers.get('authorization');assert.equal(body.status,expected,url+': '+body.msg);if(authenticated&&url.startsWith('/api/personal-remote/'))assert.equal(response.headers.get('cache-control'),'no-store');return body.data;}
let pid,accountId,offerKey,ncPid,ncOfferKey;
const ncHandle=String(900000000000+crypto.randomInt(10000000000)),ncProblem=String(800000000000+crypto.randomInt(10000000000)),ncRemote='NC-'+ncProblem;
(async()=>{
  assert.equal(execFileSync('docker',['inspect','hoj-master-hoj-judgeserver-1','--format','{{.State.Running}}'],{encoding:'utf8',windowsHide:true}).trim(),'false','Stop JudgeServer before synthetic creation checks.');
  assert.equal(sql("SELECT COUNT(*) FROM personal_remote_task WHERE state IN ('QUEUED','PREPARING','SUBMITTING','RECONCILING','POLLING')"),'0');
  const routes=[['accounts','POST'],['options','POST'],['submissions','POST'],['submissions/1','GET'],['requests/request_00000001','GET'],['submissions/1/reconcile','POST'],['submissions/1/cancel','POST']];
  for(const [route,method]of routes)await request('/api/personal-remote/'+route,401,method,method==='POST'?{}:undefined,false);
  report.checks.push('All seven real HTTP routes deny anonymous access.');
  sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(uid)},${quote(actor.username)},${quote(crypto.createHash('md5').update(actor.password).digest('hex'))}),(${quote(other)},${quote('entry_b_'+random.slice(0,10))},'synthetic'); INSERT INTO user_record(uid) VALUES(${quote(uid)}); INSERT INTO user_role(uid,role_id) SELECT ${quote(uid)},id FROM role WHERE role='default_user';`);
  await request('/api/login',200,'POST',{username:actor.username,password:actor.password});assert(actor.token);
  pid=Number(sql(`INSERT INTO problem(problem_id,title,author,is_remote,auth,is_group) VALUES(${quote(remote)},'temporary personal-entry fixture',${quote(actor.username)},TRUE,1,FALSE); SELECT LAST_INSERT_ID();`));
  accountId=Number(sql(`INSERT INTO external_account(uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(${quote(uid)},'CODEFORCES',${quote(handle)},${quote(handle)},${quote(handle)},NOW(3),NOW(3)); SELECT LAST_INSERT_ID();`));
  const scope={pid:remote},input={...scope,accountId,languageId:'54',requestId:'entry_'+random,code:'synthetic source - never send to any platform'};
  const accounts=await request('/api/personal-remote/accounts',200,'POST',scope);assert.equal(accounts.accounts.length,1);assert.equal(accounts.accounts[0].authorized,false);
  await request('/api/personal-remote/options',409,'POST',{...scope,accountId});
  report.checks.push('Real permission preparation lists only the owner account and rejects missing authorization before any source IO.');
  sql(`INSERT INTO remote_user_credential VALUES(${accountId},'s1:synthetic-local-entry-only',1,NOW(3));`);
  const offerId=crypto.randomUUID();offerKey='personal:remote:offer:'+offerId;input.offerId=offerId;
  const scopeKey=[remote,0,0,0].map(field).join(''),context=hash(scopeKey+[pid,null,remote,remote.slice(3,-1)].map(field).join(''));
  assert.equal(redis('SET',offerKey,JSON.stringify({uid,accountId,version:1,context,problem:remote,contest:remote.slice(3,-1),platform:'CODEFORCES',languages:{54:'GNU G++17'}})),'OK');
  const created=await request('/api/personal-remote/submissions',200,'POST',input),id=created.submitId;assert(id);assert.equal(created.task.state,'QUEUED');assert.equal(created.task.remote_submit_id,undefined);
  const repeated=await request('/api/personal-remote/submissions',200,'POST',input);assert.equal(repeated.submitId,id);await request('/api/personal-remote/submissions',409,'POST',{...input,code:'changed source'});
  assert.equal(sql(`SELECT COUNT(*) FROM judge WHERE uid=${quote(uid)};`),'1');assert.equal(sql(`SELECT COUNT(*) FROM personal_remote_task WHERE owner_uid=${quote(uid)};`),'1');
  const status=await request('/api/personal-remote/requests/'+input.requestId,200);assert.equal(status.submitId,id);assert.equal(status.verdict,5);assert.equal(JSON.stringify(status).includes('synthetic'),false);
  sql(`UPDATE personal_remote_task SET owner_uid=${quote(other)} WHERE submit_id=${id};`);await request('/api/personal-remote/submissions/'+id,409);sql(`UPDATE personal_remote_task SET owner_uid=${quote(uid)} WHERE submit_id=${id};`);
  await request('/api/personal-remote/submissions/'+id+'/cancel',200,'POST');assert.equal(sql(`SELECT CONCAT(state,':',submit_attempts) FROM personal_remote_task WHERE submit_id=${id};`),'CANCELLED:0');
  report.checks.push('Real judge creation, request recovery, idempotent replay, changed-payload rejection, owner-only status and pre-POST cancellation pass. No source request was executed.');
  const invalid={...input,requestId:'missing_'+random,offerId:'expired_'+random};await request('/api/personal-remote/submissions',409,'POST',invalid);assert.equal(sql(`SELECT COUNT(*) FROM judge WHERE uid=${quote(uid)};`),'1');
  report.checks.push('An expired language offer creates no orphan judge or task.');
  ncPid=Number(sql(`INSERT INTO problem(problem_id,title,author,is_remote,auth,is_group) VALUES(${quote(ncRemote)},'temporary NC entry fixture',${quote(actor.username)},TRUE,1,FALSE); SELECT LAST_INSERT_ID();`));
  const ncAccount=Number(sql(`START TRANSACTION;INSERT INTO external_account(uid,platform,handle,handle_key,verified_identity,last_requested_at,created_at) VALUES(${quote(uid)},'NOWCODER',${quote(ncHandle)},${quote(ncHandle)},${quote(ncHandle)},NOW(3),NOW(3));SET @fixture_account=LAST_INSERT_ID();INSERT INTO external_nowcoder_context_job(account_id,scan_token,next_run_at) VALUES(@fixture_account,${quote(crypto.randomUUID())},'2999-01-01');INSERT INTO external_nowcoder_practice_job(account_id,scan_token,next_run_at) VALUES(@fixture_account,${quote(crypto.randomUUID())},'2999-01-01');SELECT @fixture_account;COMMIT;`));
  sql(`INSERT INTO remote_user_credential VALUES(${ncAccount},'s1:synthetic-local-entry-only',1,NOW(3));`);
  const ncOfferId=crypto.randomUUID(),sourceContext={questionId:'1234567',problemId:ncProblem,contestId:''};ncOfferKey='personal:remote:offer:'+ncOfferId;
  const ncContext=hash([ncRemote,0,0,0].map(field).join('')+[ncPid,null,ncRemote,''].map(field).join(''));
  assert.equal(redis('SET',ncOfferKey,JSON.stringify({uid,accountId:ncAccount,version:1,context:ncContext,problem:ncRemote,contest:'',platform:'NOWCODER',languages:{2:'C++'},sourceContext})),'OK');
  const ncInput={pid:ncRemote,accountId:ncAccount,offerId:ncOfferId,languageId:'2',requestId:'nowcoder_'+random,code:'synthetic NC source - never dispatched'};
  // Respect the user's real submission cooldown rather than weakening production throttling.
  await new Promise(resolve=>setTimeout(resolve,21000));
  const ncCreated=await request('/api/personal-remote/submissions',200,'POST',ncInput),ncId=ncCreated.submitId;assert(ncId);assert.equal(ncCreated.task.state,'QUEUED');
  assert.equal((await request('/api/personal-remote/submissions',200,'POST',ncInput)).submitId,ncId);
  assert.equal((await request('/api/personal-remote/requests/'+ncInput.requestId,200)).submitId,ncId);
  await request('/api/personal-remote/submissions',409,'POST',{...ncInput,code:'changed NC source'});
  const snapshot=JSON.parse(sql(`SELECT context_json FROM personal_remote_nowcoder WHERE submit_id=${ncId};`));assert.deepEqual(snapshot,{userId:ncHandle,...sourceContext,languageId:'2',languageName:'C++'});
  assert.equal(sql(`SELECT COUNT(*) FROM personal_remote_nowcoder n JOIN personal_remote_task t USING(submit_id) WHERE t.owner_uid=${quote(uid)} AND n.backend_job_id IS NULL AND n.source_submission_id IS NULL;`),'1');
  assert.equal(JSON.stringify(ncCreated).includes('context_json'),false);assert.equal(JSON.stringify(ncCreated).includes('synthetic'),false);
  await request('/api/personal-remote/submissions/'+ncId+'/cancel',200,'POST');assert.equal(sql(`SELECT CONCAT(state,':',submit_attempts) FROM personal_remote_task WHERE submit_id=${ncId};`),'CANCELLED:0');
  assert.equal(sql(`SELECT COUNT(*) FROM personal_remote_lane WHERE platform='NOWCODER' AND handle_key=${quote(ncHandle)} AND active_submit_id IS NOT NULL;`),'0');
  report.checks.push('Real NC enqueue atomically freezes the six source fields, replays one task, rejects changed code, keeps source internals private and cancels before POST. JudgeServer remains stopped; no source IO.');report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{if(offerKey)redis('DEL',offerKey);if(ncOfferKey)redis('DEL',ncOfferKey);sql(`DELETE FROM judge WHERE uid=${quote(uid)}; DELETE FROM external_account WHERE uid=${quote(uid)}; DELETE FROM personal_remote_lane WHERE ((platform='CODEFORCES' AND handle_key=${quote(handle)}) OR (platform='NOWCODER' AND handle_key=${quote(ncHandle)})) AND active_submit_id IS NULL;`);for(const createdPid of [pid,ncPid])if(createdPid)sql(`DELETE FROM problem WHERE id=${createdPid} AND author=${quote(actor.username)};`);if(actor.token)await request('/api/logout',200,'GET').catch(()=>{});sql(`DELETE FROM session WHERE uid IN(${quote(uid)},${quote(other)}); DELETE FROM user_info WHERE uuid IN(${quote(uid)},${quote(other)});`);assert.equal(sql(`SELECT COUNT(*) FROM personal_remote_task WHERE owner_uid IN(${quote(uid)},${quote(other)});`),'0');report.cleaned=true;}catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'personal-remote-entry-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
