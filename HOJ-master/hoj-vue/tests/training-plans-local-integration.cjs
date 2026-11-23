// Opt-in local HTTP/MySQL regression. Only random, named fixtures are created/removed.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path'),os=require('os'),{execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_TRAINING_PLAN_TEST!=='1')throw Error('Set HOJ_LOCAL_TRAINING_PLAN_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(os.tmpdir(),'hoj-training-plan-http');fs.mkdirSync(output,{recursive:true});
const users=['root','default_user','default_user'].map((role,index)=>({role,uid:crypto.randomBytes(16).toString('hex'),username:'plan_it_'+index+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(8).toString('hex')}));
const [teacher,alice,bob]=users,q=value=>"'"+String(value).replace(/'/g,"''")+"'",uids=users.map(user=>q(user.uid)).join(','),authors=users.map(user=>q(user.username)).join(',');
const report={checks:[],validated:false,cleaned:false,realModelCalled:false,realSourceCodePosted:false,progressFixture:'Synthetic finalized practice row; not a JudgeServer AC/WA run.'};
const secret='LOCKED_PLAN_'+crypto.randomBytes(16).toString('hex');let pid,question,cid,base,taskRating;
function sql(input){return execFileSync('docker',['exec','-i','hoj-master-hoj-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
async function request(user,url,method='GET',data,status=200){const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(90000)}),body=await response.json();if(user&&response.headers.get('Authorization'))user.token=response.headers.get('Authorization');assert.equal(body.status,status,url+': '+body.msg);if(url.startsWith('/api/learning/training-plans')&&status!==401)assert.equal(response.headers.get('Cache-Control'),'no-store');return body.data;}
function counts(){return sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),(SELECT COUNT(*) FROM learning_training_plan);');}
const item=(plan,kind)=>plan.items.find(row=>row.id===(kind==='OBJECTIVE'?'OBJECTIVE:'+question.id:'PROGRAMMING:'+pid));
(async()=>{
  base=counts();await request(null,'/api/learning/training-plans','GET',undefined,401);
  for(const user of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${q(user.uid)},${q(user.username)},${q(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${q(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${q(user.uid)},id FROM role WHERE role IN ('default_user',${q(user.role)});`);await request(user,'/api/login','POST',{username:user.username,password:user.password});}
  const topic=(await request(null,'/api/learning/topics')).topics.find(row=>row.groupCode==='geometry');assert(topic);
  const usedRatings=new Set(sql("SELECT rating FROM problem WHERE rating IS NOT NULL UNION SELECT JSON_EXTRACT(content_json,'$.rating') FROM objective_question;").split(/\r?\n/).map(Number));taskRating=Array.from({length:1001},(_,index)=>5000-index).find(value=>!usedRatings.has(value));assert.notEqual(taskRating,undefined,'No isolated valid rating band remains.');
  const display='PLAN_'+crypto.randomBytes(8).toString('hex');pid=Number(sql(`INSERT INTO problem(problem_id,title,author,auth,is_group,is_remote,rating) VALUES(${q(display)},'训练清单：编程样例',${q(teacher.username)},1,0,0,${taskRating});SELECT LAST_INSERT_ID();`));sql(`INSERT INTO problem_tag(pid,tid) VALUES(${pid},${topic.tagId});`);
  const definition={title:'训练清单：保密概念',stem:'选择 A',questionType:'SINGLE_CHOICE',options:[{id:'A',text:'选项 A'},{id:'B',text:'选项 B'}],correctAnswers:['A'],explanation:secret,tagIds:[topic.tagId],published:true,practiceEnabled:true,rating:taskRating,answerPolicy:'NEVER',explanationPolicy:'NEVER'};
  question=await request(teacher,'/api/admin/objective/questions','POST',definition);
  const recommended=await request(alice,'/api/ai/recommendations?'+new URLSearchParams({source:'HOJ',groupCode:'geometry',minRating:taskRating,maxRating:taskRating,includeUnrated:false}));
  assert(item(recommended,'PROGRAMMING'));assert.equal(item(recommended,'OBJECTIVE').rating,taskRating);assert.equal(recommended.items.length,2);
  const command={requestId:crypto.randomBytes(16).toString('hex'),name:'HTTP 训练清单',selection:{filters:recommended.filters,fingerprint:recommended.fingerprint,candidateIds:recommended.items.map(row=>row.id)}};
  await request(alice,'/api/learning/training-plans','POST',{...command,requestId:crypto.randomBytes(16).toString('hex'),selection:{...command.selection,candidateIds:['PROGRAMMING:0']}},409);
  let saved=await request(alice,'/api/learning/training-plans','POST',command);const id=saved.id;
  assert.deepEqual(saved.items.map(row=>row.id),command.selection.candidateIds);assert.equal(saved.solved,0);assert.equal((await request(alice,'/api/learning/training-plans','POST',command)).id,id);
  assert.equal((await request(alice,'/api/learning/training-plans')).total,1);assert.equal((await request(bob,'/api/learning/training-plans')).total,0);
  await request(bob,'/api/learning/training-plans/'+id,'GET',undefined,404);await request(bob,'/api/learning/training-plans/'+id,'PUT',{expectedRevision:1,state:'DELETED'},404);
  assert.equal(item(saved,'OBJECTIVE').rating,taskRating);
  if(process.env.HOJ_LOCAL_TRAINING_PLAN_BROWSER==='1'){report.browser=await require('./training-plans-live-browser.cjs')({account:alice,output,planId:id,questionId:question.id,taskRating});assert.equal(report.browser.apiMocked,false);assert(report.browser.validated);}
  report.checks.push('Actual authenticated API saves the exact verified set/order, rejects tampered candidates, deduplicates retries and isolates owners.');
  const run=await request(alice,'/api/objective/runs','POST',{questionId:question.id,requestId:crypto.randomBytes(16).toString('hex')});
  await request(alice,'/api/objective/runs/'+run.id+'/answers','PUT',{questionId:question.id,selectedAnswers:['A']});
  saved=await request(alice,'/api/learning/training-plans/'+id);assert.equal(item(saved,'OBJECTIVE').status,'ATTEMPTED');assert.equal(saved.solved,0);
  for(const value of [secret,'correctAnswers','explanation','stem'])assert.equal(JSON.stringify(saved).includes(value),false);
  question=await request(teacher,'/api/admin/objective/questions','PUT',{...definition,id:question.id,version:question.version,answerPolicy:'AFTER_ANSWER'});
  saved=await request(alice,'/api/learning/training-plans/'+id);assert.equal(item(saved,'OBJECTIVE').status,'SOLVED');
  sql(`INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code) VALUES(${pid},${q(display)},${q(alice.uid)},${q(alice.username)},NOW(),0,'Synthetic finalized progress fixture; never dispatched');`);
  saved=await request(alice,'/api/learning/training-plans/'+id);assert.equal(item(saved,'PROGRAMMING').status,'SOLVED');assert.equal(saved.solved,2);assert.equal((await request(alice,'/api/learning/training-plans','POST',command)).id,id);
  report.checks.push('Progress follows finalized records, objective NEVER policy does not reveal correctness, and opening feedback exposes the frozen result without returning answers or explanations. A synthetic finalized code row verifies projection, not judging.');
  sql(`UPDATE problem SET title='训练清单：当前题名' WHERE id=${pid};`);saved=await request(alice,'/api/learning/training-plans/'+id);assert.equal(item(saved,'PROGRAMMING').title,'训练清单：当前题名');
  question=await request(teacher,'/api/admin/objective/questions','PUT',{...question,rating:taskRating-1});saved=await request(alice,'/api/learning/training-plans/'+id);assert.equal(item(saved,'OBJECTIVE').rating,taskRating-1);assert.equal(saved.solved,2);
  report.checks.push('Rated objective tasks remain in strict numeric recommendations with includeUnrated=false; saved lists show the current question rating after recalibration without changing progress.');
  sql(`UPDATE problem SET auth=2 WHERE id=${pid};UPDATE objective_question SET practice_enabled=0 WHERE id=${question.id};`);saved=await request(alice,'/api/learning/training-plans/'+id);
  for(const row of [item(saved,'PROGRAMMING'),item(saved,'OBJECTIVE')])assert.deepEqual(Object.keys(row).sort(),['available','id','status']);assert.equal(saved.solved,0);
  report.checks.push('Current titles are projected; private or practice-disabled tasks lose all saved metadata, links and visible solved counts.');
  const archived=await request(alice,'/api/learning/training-plans/'+id,'PUT',{state:'ARCHIVED',expectedRevision:saved.revision,name:'已归档训练'});
  await request(alice,'/api/learning/training-plans/'+id,'PUT',{state:'ACTIVE',expectedRevision:saved.revision},409);assert.equal((await request(alice,'/api/learning/training-plans')).total,0);assert.equal((await request(alice,'/api/learning/training-plans?state=ARCHIVED')).total,1);
  saved=await request(alice,'/api/learning/training-plans/'+id,'PUT',{state:'ACTIVE',expectedRevision:archived.revision});
  await request(alice,'/api/learning/training-plans/'+id,'PUT',{state:'DELETED',expectedRevision:saved.revision});
  assert.deepEqual(await request(alice,'/api/learning/training-plans','POST',command),{id,state:'DELETED'});await request(alice,'/api/learning/training-plans/'+id,'GET',undefined,404);
  assert.equal(sql(`SELECT snapshot_json FROM learning_training_plan WHERE id=${q(id)};`),'{}');
  report.checks.push('Archive/rename/restore require the current revision. Deletion clears snapshot metadata and retrying the old save cannot resurrect a deleted plan.');
  cid=Number(sql(`INSERT INTO contest(uid,author,title,auth,status,start_time,end_time,duration,visible) VALUES(${q(teacher.uid)},${q(teacher.username)},'temporary training-plan policy contest',0,0,DATE_SUB(NOW(),INTERVAL 1 MINUTE),DATE_ADD(NOW(),INTERVAL 1 HOUR),3660,1);SELECT LAST_INSERT_ID();`));sql(`INSERT INTO contest_register(cid,uid,status) VALUES(${cid},${q(alice.uid)},0);`);
  for(const [url,method,data]of [['/api/learning/training-plans','GET'],['/api/learning/training-plans/'+id,'GET'],['/api/learning/training-plans','POST',command],['/api/learning/training-plans/'+id,'PUT',{state:'ACTIVE',expectedRevision:1}]])await request(alice,url,method,data,403);
  assert.equal((await request(bob,'/api/learning/training-plans')).total,0);
  report.checks.push('All four live plan operations enforce the existing formal-contest guard; an unrelated user remains able to read their own list.');report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    if(cid)sql(`DELETE FROM contest WHERE id=${cid} AND uid=${q(teacher.uid)};`);
    sql(`DELETE FROM learning_training_plan WHERE uid IN (${uids});DELETE FROM ai_contest_entry WHERE uid IN (${uids});DELETE FROM judge WHERE uid IN (${uids});DELETE FROM problem WHERE author IN (${authors});DELETE FROM objective_run WHERE uid IN (${uids});`);
    if(question)sql(`DELETE FROM objective_question_version WHERE question_id=${question.id};DELETE FROM objective_question WHERE id=${question.id} AND created_by=${q(teacher.uid)};`);
    for(const user of users)if(user.token)await request(user,'/api/logout').catch(()=>{});
    sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`),'0');assert.equal(counts(),base);report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'training-plans-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
