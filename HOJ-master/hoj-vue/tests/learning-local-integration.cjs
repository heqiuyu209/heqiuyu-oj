// Opt-in: temporary MySQL fixtures exercise the running HTTP service; all are removed.
const assert = require('assert').strict;
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
if (process.env.HOJ_LOCAL_LEARNING_TEST !== '1') throw Error('Set HOJ_LOCAL_LEARNING_TEST=1.');
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(require('os').tmpdir(), 'hoj-learning-review');
fs.mkdirSync(output, {recursive:true});
const accounts=['root','default_user'].map(role=>({role,uid:crypto.randomBytes(16).toString('hex'),username:'learn_it_'+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(8).toString('hex'),token:''}));
const [root,student]=accounts, report={checks:[],cleaned:false}, custom={};
const quote=value=>"'"+String(value).replace(/'/g,"''")+"'", uids=accounts.map(account=>quote(account.uid)).join(','), authors=accounts.map(account=>quote(account.username)).join(',');
function sql(input) { return execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim(); }
async function request(account,endpoint,method='GET',data,expected=200) {
  const response=await fetch('http://127.0.0.1:6688'+endpoint,{method,headers:{'Content-Type':'application/json',...(account?.token?{Authorization:account.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(20000)});
  const body=await response.json();if(account&&response.headers.get('authorization'))account.token=response.headers.get('authorization');
  if(endpoint.endsWith('/evidence'))assert.equal(response.headers.get('Cache-Control'),'no-store');
  if(expected==='preset'){assert.notEqual(body.status,200);assert(body.msg.includes('固定保留'),endpoint+': '+body.msg);return body;}
  assert.equal(body.status,expected,endpoint+': '+body.msg);return body.data;
}
(async()=>{
  const catalogue=await request(null,'/api/learning/topics');assert.equal(catalogue.topics.length,94);const topic=catalogue.topics.find(topic=>topic.groupCode==='dp');assert(topic.tagId&&topic.categoryId&&topic.classificationId);
  for(const account of accounts){const password=crypto.createHash('md5').update(account.password).digest('hex');sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(account.uid)},${quote(account.username)},${quote(password)});INSERT INTO user_record(uid) VALUES(${quote(account.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(account.uid)},id FROM role WHERE role IN ('default_user',${quote(account.role)});`);await request(account,'/api/login','POST',{username:account.username,password:account.password});assert(account.token);}
  await request(null,'/api/admin/tag?tid='+topic.tagId,'DELETE',undefined,401);
  await request(root,'/api/admin/tag','PUT',{id:topic.tagId,name:'must not rename'},'preset');
  await request(root,'/api/admin/tag?tid='+topic.tagId,'DELETE',undefined,'preset');
  await request(root,'/api/admin/tag/classification','PUT',{id:topic.classificationId,name:'must not rename'},'preset');
  await request(root,'/api/admin/tag/classification?tcid='+topic.classificationId,'DELETE',undefined,'preset');
  await request(root,'/api/admin/training/category','PUT',{id:topic.categoryId,name:'must not rename'},'preset');
  await request(root,'/api/admin/training/category?cid='+topic.categoryId,'DELETE',undefined,'preset');
  report.checks.push('Anonymous mutation is denied; root cannot rename/delete preset tags, groups or training categories through HTTP.');
  const suffix=crypto.randomBytes(5).toString('hex');
  custom.tag=await request(root,'/api/admin/tag','POST',{name:'自定义知识点_'+suffix,oj:'ME',tcid:topic.classificationId,color:'#217e9d'});
  await request(root,'/api/admin/tag','PUT',{...custom.tag,name:'自定义知识点更新_'+suffix});
  custom.category=await request(root,'/api/admin/training/category','POST',{name:'自定义训练_'+suffix,color:'#217e9d'});
  await request(root,'/api/admin/training/category','PUT',{...custom.category,name:'自定义训练更新_'+suffix});
  report.checks.push('Custom topic and training category creation and updates remain available beside protected presets.');
  const problemId='LEARN_IT_'+suffix;
  const pid=Number(sql(`INSERT INTO problem(problem_id,title,author,auth,rating) VALUES(${quote(problemId)},'temporary evidence',${quote(root.username)},1,1600);SELECT LAST_INSERT_ID();`));
  sql(`INSERT INTO problem_tag(pid,tid) VALUES(${pid},${topic.tagId});INSERT INTO judge(pid,display_pid,uid,username,submit_time,status,code) VALUES(${pid},${quote(problemId)},${quote(student.uid)},${quote(student.username)},NOW(),0,'temporary fixture'),(${pid},${quote(problemId)},${quote(student.uid)},${quote(student.username)},NOW(),0,'temporary duplicate');`);
  let profile=await request(null,'/api/learning/users/'+student.uid+'/profile');assert.equal(profile.overall.solved,1);assert.equal(profile.axes.find(axis=>axis.code==='dp').representativeRating,1600);assert.equal(profile.assistanceTracked,false);assert.equal(profile.crossPlatformDeduplicated,false);
  let evidence=await request(null,'/api/learning/users/'+student.uid+'/evidence');assert.equal(evidence.combined.overall.solved,1);assert.equal(evidence.combined.overall.representativeRating,null);assert.equal(evidence.sources.find(source=>source.source==='HOJ').overall.representativeRating,1600);
  sql(`UPDATE problem SET auth=2 WHERE id=${pid};`);profile=await request(null,'/api/learning/users/'+student.uid+'/profile');assert.equal(profile.overall.attempted,0);sql(`UPDATE problem SET auth=1 WHERE id=${pid};`);
  report.checks.push('Real MySQL programming aggregation counts duplicate passes once and removes private evidence from anonymous profiles.');
  let question=await request(root,'/api/admin/objective/questions','POST',{title:'临时画像客观题',stem:'1+2=?',questionType:'SINGLE_CHOICE',options:[{id:'A',text:'3'},{id:'B',text:'4'}],correctAnswers:['A'],explanation:'相加得到 3',tagIds:[topic.tagId],published:true,practiceEnabled:true,answerPolicy:'NEVER',explanationPolicy:'NEVER'});
  const run=await request(student,'/api/objective/runs','POST',{questionId:question.id,requestId:crypto.randomBytes(16).toString('hex')});
  await request(student,'/api/objective/runs/'+run.id+'/answers','PUT',{questionId:question.id,selectedAnswers:['A']});
  profile=await request(null,'/api/learning/users/'+student.uid+'/profile');assert.equal(profile.overall.objectiveAttempted,0);
  evidence=await request(null,'/api/learning/users/'+student.uid+'/evidence');assert.equal(evidence.combined.overall.objectiveAttempted,0);
  question=await request(root,'/api/admin/objective/questions','PUT',{...question,answerPolicy:'AFTER_ANSWER'});
  profile=await request(null,'/api/learning/users/'+student.uid+'/profile');assert.equal(profile.overall.objectiveAttempted,1);assert.equal(profile.overall.objectiveSolved,1);assert.equal(profile.overall.solved,1);assert(!JSON.stringify(profile).includes('correctAnswers'));
  evidence=await request(null,'/api/learning/users/'+student.uid+'/evidence');assert.equal(evidence.combined.overall.objectiveAttempted,1);assert.equal(evidence.combined.overall.objectiveSolved,1);assert(!JSON.stringify(evidence).includes('correctAnswers'));assert(!JSON.stringify(evidence).includes('explanation'));
  report.checks.push('The combined evidence endpoint reuses objective feedback release policy and preserves the separate original local-only AI profile.');
  await request(null,'/api/learning/users/missing-user/profile','GET',undefined,404);
  report.checks.push('An actual objective answer is absent from the public profile while feedback is locked and appears as separate concept evidence after release, without exposing answer content.');
  await request(root,'/api/admin/tag?tid='+custom.tag.id,'DELETE');custom.tag=null;
  await request(root,'/api/admin/training/category?cid='+custom.category.id,'DELETE');custom.category=null;
  assert.equal((await request(null,'/api/learning/topics')).topics.length,94);report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    if(custom.tag)await request(root,'/api/admin/tag?tid='+custom.tag.id,'DELETE');if(custom.category)await request(root,'/api/admin/training/category?cid='+custom.category.id,'DELETE');
    sql(`DELETE FROM site_rating_history WHERE uid IN (${uids});DELETE FROM site_rating_profile WHERE uid IN (${uids});DELETE FROM judge WHERE uid IN (${uids});DELETE FROM problem WHERE author IN (${authors});DELETE FROM objective_run WHERE uid IN (${uids});DELETE v FROM objective_question_version v JOIN objective_question q ON q.id=v.question_id WHERE q.created_by IN (${uids});DELETE FROM objective_question WHERE created_by IN (${uids});`);
    for(const account of accounts)if(account.token)await request(account,'/api/logout').catch(()=>{});
    sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`),'0');report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'learning-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
