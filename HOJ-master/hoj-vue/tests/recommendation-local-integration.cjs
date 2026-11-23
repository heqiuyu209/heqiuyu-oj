// LOCAL opt-in. Named temporary records only; no model or remote OJ code request is sent.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path'),os=require('os'),{execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_RECOMMENDATION_TEST!=='1')throw Error('Set HOJ_LOCAL_RECOMMENDATION_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(os.tmpdir(),'hoj-recommendation-http');fs.mkdirSync(output,{recursive:true});
const users=['root','default_user','default_user'].map((role,index)=>({role,uid:crypto.randomBytes(16).toString('hex'),username:'rec_it_'+index+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(8).toString('hex')}));
const [teacher,alice,bob]=users,q=v=>"'"+String(v).replace(/'/g,"''")+"'",uids=users.map(u=>q(u.uid)).join(','),authors=users.map(u=>q(u.username)).join(',');
const report={checks:[],cleaned:false,realOwnerAuthorizationVerified:false,realModelCalled:false,realSourceCodePosted:false},pids=[],questions=[];let cid,linked,lastRead=0;
const secret='UNRELEASED_RECOMMENDATION_'+crypto.randomBytes(8).toString('hex'),suffix=crypto.randomBytes(4).toString('hex');
function sql(input){return execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
async function request(user,url,method='GET',data,status=200){let r=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(90000)}),body=await r.json();if(user&&r.headers.get('Authorization'))user.token=r.headers.get('Authorization');assert.equal(body.status,status,url+': '+body.msg);if(url.startsWith('/api/ai')&&status!==401)assert.equal(r.headers.get('Cache-Control'),'no-store');return body.data;}
const wait=ms=>new Promise(r=>setTimeout(r,ms));
async function plan(filters,status=200){await wait(Math.max(0,lastRead+5200-Date.now()));lastRead=Date.now();return request(alice,'/api/ai/recommendations?'+new URLSearchParams(filters),'GET',undefined,status);}
const explanation=p=>({filters:p.filters,fingerprint:p.fingerprint,candidateIds:p.items.map(i=>i.id),includeProfile:false,includeExternalProfile:false});
async function question(title,extra={}){const data=await request(teacher,'/api/admin/objective/questions','POST',{title,stem:'公开的概念样例，不读取答案。',questionType:'SINGLE_CHOICE',options:[{id:'A',text:'选项 A'},{id:'B',text:'选项 B'}],correctAnswers:['A'],explanation:secret,tagIds:[topic.tagId],published:true,practiceEnabled:true,answerPolicy:'NEVER',explanationPolicy:'NEVER',...extra});questions.push(data.id);return data;}
let topic;
(async()=>{
  await request(null,'/api/ai/recommendations','GET',undefined,401);await request(null,'/api/ai/recommendations/explain','POST',{},401);
  for(const u of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${q(u.uid)},${q(u.username)},${q(crypto.createHash('md5').update(u.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${q(u.uid)});INSERT INTO user_role(uid,role_id) SELECT ${q(u.uid)},id FROM role WHERE role IN ('default_user',${q(u.role)});`);await request(u,'/api/login','POST',{username:u.username,password:u.password});}
  topic=(await request(null,'/api/learning/topics')).topics.find(t=>t.groupCode==='geometry');assert(topic?.tagId);
  for(const [name,owner,auth,isGroup,remote]of [['eligible',teacher,1,0,false],['attempted',teacher,1,0,false],['own',alice,1,0,false],['private',teacher,2,0,false],['group',teacher,1,1,false],['CF-4A',teacher,1,0,true]]){
    const display=remote?name:'REC_'+suffix+'_'+name;assert.equal(sql(`SELECT COUNT(*) FROM problem WHERE problem_id=${q(display)};`),'0','Existing source problems must be preserved.');
    const id=Number(sql(`INSERT INTO problem(problem_id,title,author,auth,is_group,is_remote,rating) VALUES(${q(display)},${q('推荐验收 '+name)},${q(owner.username)},${auth},${isGroup},${remote?1:0},7777);SELECT LAST_INSERT_ID();`));pids.push(id);sql(`INSERT INTO problem_tag(pid,tid) VALUES(${id},${topic.tagId});`);
  }
  sql(`INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code) VALUES(${pids[1]},${q('REC_'+suffix+'_attempted')},${q(alice.uid)},${q(alice.username)},NOW(),-1,'Final fixture, never dispatched');`);
  sql("INSERT IGNORE INTO external_problem(problem_key,platform,title,rating,tags_json,synced_at) SELECT problem_key,'CODEFORCES',title,rating,tags_json,NOW(3) FROM remote_catalog_problem WHERE problem_key='cf:contest:4:A';");
  linked=Number(sql(`INSERT INTO external_account(uid,platform,handle,handle_key,verified_identity,history_complete,last_success_at,last_requested_at,created_at) VALUES(${q(alice.uid)},'CODEFORCES',${q('fixture_'+suffix)},${q('fixture_'+suffix)},${q('fixture_'+suffix)},1,NOW(3),NOW(3),NOW(3));SELECT LAST_INSERT_ID();`));
  sql(`INSERT INTO external_sync_job(account_id,scan_token,stage,next_run_at) VALUES(${linked},${q(suffix)},'DONE',NULL);INSERT INTO external_submission(account_id,external_id,problem_key,verdict,submitted_at,individual,seen_scan) VALUES(${linked},1,'cf:contest:4:A','WRONG_ANSWER',1700000000,1,${q(suffix)});`);
  const eligible=await question('推荐验收：公开概念'),done=await question('推荐验收：已看概念'),owned=await question('推荐验收：本人题目');
  sql(`UPDATE objective_question SET created_by=${q(alice.uid)} WHERE id=${owned.id};`);await question('推荐验收：未发布',{published:false});await question('推荐验收：未开放练习',{practiceEnabled:false});
  const run=await request(alice,'/api/objective/runs','POST',{questionId:done.id,requestId:crypto.randomBytes(16).toString('hex')});
  const filters={source:'HOJ',groupCode:'geometry',minRating:7777,maxRating:7777,includeUnrated:true};let p=await plan(filters);
  assert.deepEqual(new Set(p.items.map(i=>i.id)),new Set(['PROGRAMMING:'+pids[0],'OBJECTIVE:'+eligible.id]));
  for(const value of ['correctAnswers','explanation',secret,'stem'])assert.equal(JSON.stringify(p).includes(value),false,value);
  await request(alice,'/api/ai/recommendations?source=HOJ','GET',undefined,429);
  report.checks.push('Actual MySQL candidates include only public unattempted programming/concept tasks; own, private, group, disabled and previously viewed questions are excluded. No unreleased content enters the response; read cooldown and anonymous access are enforced.');
  const fingerprint=p.fingerprint;await request(alice,'/api/objective/runs/'+run.id+'/answers','PUT',{questionId:done.id,selectedAnswers:['A']});p=await plan(filters);assert.equal(p.fingerprint,fingerprint,'Locked correctness must not change recommendation evidence.');
  report.checks.push('An objective answer hidden by NEVER policy does not change the recommendation fingerprint or reveal correctness.');
  let cf=await plan({source:'CODEFORCES',groupCode:'math',minRating:800,maxRating:800,includeUnrated:false});assert(cf.items.length);assert.equal(cf.items.some(i=>i.id==='cf:contest:4:A'),false);assert.equal(cf.basis.difficultyMetric,'CODEFORCES_PROBLEM_RATING');
  p=await plan({...filters,includeUnrated:false});assert.deepEqual(p.items.map(i=>i.id),['PROGRAMMING:'+pids[0]]);
  report.checks.push('Verified source attempts exclude matching local and CF identities; platform rating remains separate and the unrated toggle filters concepts without fabricating scores.');
  for(const source of ['ATCODER','NOWCODER']){const remote=await plan({source,includeUnrated:true});assert(remote.items.length);assert(remote.items.every(i=>i.kind==='REMOTE'&&i.source===source));assert.equal(remote.basis.difficultyMetric,source==='ATCODER'?'ATCODER_PROBLEMS_ESTIMATE':'NOWCODER_SOURCE_RATING');}
  report.checks.push('Actual AtCoder and NC public catalogues produce current source candidates with their separate difficulty metrics; known NC contexts share native difficulty only when the mapping is confirmed.');
  p=await plan(filters);await request(alice,'/api/ai/recommendations/explain','POST',explanation(p),400);
  await request(alice,'/api/ai/recommendations/explain','POST',{...explanation(p),candidateIds:['INVENTED']},409);
  sql(`UPDATE problem SET auth=2 WHERE id=${pids[0]};`);await request(alice,'/api/ai/recommendations/explain','POST',explanation(p),409);sql(`UPDATE problem SET auth=1 WHERE id=${pids[0]};`);
  report.checks.push('No model settings are required for rules; explanation rejects missing configuration, forged candidate IDs and a visibility change before any provider request.');
  p=await plan(filters);
  if(process.env.HOJ_LOCAL_RECOMMENDATION_BROWSER_SCRIPT){await wait(5300);execFileSync(process.execPath,[process.env.HOJ_LOCAL_RECOMMENDATION_BROWSER_SCRIPT],{input:JSON.stringify({uid:alice.uid,username:alice.username,token:alice.token,output,topic:'geometry',programId:'PROGRAMMING:'+pids[0],questionId:eligible.id}),encoding:'utf8',windowsHide:true,timeout:120000});report.browser=JSON.parse(fs.readFileSync(path.join(output,'recommendation-real-browser.json'),'utf8'));assert(report.browser.validated);}
  if(!report.browser){const started=await request(alice,'/api/objective/runs','POST',{questionId:eligible.id,requestId:crypto.randomBytes(16).toString('hex')});assert(started.id);}
  cid=Number(sql(`INSERT INTO contest(uid,author,title,auth,status,start_time,end_time,duration,visible) VALUES(${q(teacher.uid)},${q(teacher.username)},'temporary recommendation policy contest',0,0,DATE_SUB(NOW(),INTERVAL 1 MINUTE),DATE_ADD(NOW(),INTERVAL 1 HOUR),3660,1);SELECT LAST_INSERT_ID();`));
  sql(`INSERT INTO contest_register(cid,uid,status) VALUES(${cid},${q(alice.uid)},0);`);
  await request(alice,'/api/ai/recommendations?source=CODEFORCES','GET',undefined,403);await request(alice,'/api/ai/recommendations/explain','POST',explanation(p),403);
  const other=await request(bob,'/api/ai/recommendations?'+new URLSearchParams(filters));assert(other.items.length);
  report.checks.push('Existing objective practice start remains usable; a formal participant is denied across both new AI routes while an unrelated authenticated user still receives rules.');
  report.validated=true;
})().catch(e=>{report.failure=e.stack;process.exitCode=1;}).finally(async()=>{
  try{
    if(cid)sql(`DELETE FROM contest WHERE id=${cid} AND uid=${q(teacher.uid)};`);
    sql(`DELETE FROM ai_contest_entry WHERE uid IN (${uids});DELETE FROM judge WHERE uid IN (${uids});DELETE FROM problem WHERE author IN (${authors});DELETE FROM objective_run WHERE uid IN (${uids});`);
    if(questions.length)sql(`DELETE FROM objective_question_version WHERE question_id IN (${questions.join(',')});DELETE FROM objective_question WHERE id IN (${questions.join(',')}) AND created_by IN (${uids});`);
    sql(`DELETE FROM external_account WHERE uid IN (${uids});DELETE FROM ai_user_setting WHERE uid IN (${uids});`);
    for(const u of users)if(u.token)await request(u,'/api/logout').catch(()=>{});sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql(`SELECT (SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids}))+(SELECT COUNT(*) FROM problem WHERE author IN (${authors}));`),'0');report.cleaned=true;
  }catch(e){report.cleanupFailure=e.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'recommendation-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
