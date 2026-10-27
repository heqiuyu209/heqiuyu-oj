// Explicit local opt-in. Random fixture users/contests only; no model or source-code POST.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path'),{execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_VIRTUAL_TEST!=='1')throw Error('Set HOJ_LOCAL_VIRTUAL_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(require('os').tmpdir(),'hoj-virtual-http');fs.mkdirSync(output,{recursive:true});
const users=['root','default_user','default_user'].map((role,index)=>({role,uid:crypto.randomBytes(16).toString('hex'),username:'virt_it_'+index+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(8).toString('hex')}));
const [teacher,alice,bob]=users,quote=v=>"'"+String(v).replace(/'/g,"''")+"'",uids=users.map(u=>quote(u.uid)).join(',');
const report={checks:[],cleaned:false,realModelCalled:false,realSourceCodePosted:false,expiryFixtureSimulated:false};
function sql(input){return execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
async function request(user,url,method='GET',data,status=200){let response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(30000)});let body=await response.json();if(user&&response.headers.get('Authorization'))user.token=response.headers.get('Authorization');assert([].concat(status).includes(body.status),url+': '+body.status+' '+body.msg);if(url.includes('/virtual-run')&&body.status!==401)assert.equal(response.headers.get('Cache-Control'),'no-store');return body;}
const runUrl=cid=>'/api/contests/'+cid+'/virtual-run',nonce=()=>crypto.randomBytes(16).toString('hex');
function contest(auth=0,future=false,empty=false){const cid=Number(sql(`INSERT INTO contest(uid,author,title,auth,status,start_time,end_time,duration,visible,is_group) VALUES(${quote(teacher.uid)},${quote(teacher.username)},'虚拟计时验证',${auth},${future?-1:1},${future?'DATE_ADD(NOW(),INTERVAL 1 HOUR)':'DATE_SUB(NOW(),INTERVAL 2 HOUR)'},${future?'DATE_ADD(NOW(),INTERVAL 2 HOUR)':'DATE_SUB(NOW(),INTERVAL 1 HOUR)'},3600,1,0);SELECT LAST_INSERT_ID();`));if(!empty)sql(`INSERT INTO contest_problem(cid,pid,display_id,display_title) SELECT ${cid},id,'A','fixture' FROM problem WHERE auth=1 AND is_group=0 LIMIT 1;`);return cid;}
(async()=>{
  assert.equal(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='contest_virtual_run';"),'1','Apply virtual-contests.sql before updating the backend.');
  for(const u of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(u.uid)},${quote(u.username)},${quote(crypto.createHash('md5').update(u.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(u.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(u.uid)},id FROM role WHERE role IN ('default_user',${quote(u.role)});`);await request(u,'/api/login','POST',{username:u.username,password:u.password});}
  const cid=contest(),other=contest(),privateCid=contest(1),protectedCid=contest(2),future=contest(0,true),empty=contest(0,false,true);
  await request(null,runUrl(cid),'GET',undefined,401);await request(null,runUrl(cid),'POST',{requestId:nonce()},401);
  await request(alice,runUrl(privateCid),'POST',{requestId:nonce()},403);await request(alice,runUrl(protectedCid),'POST',{requestId:nonce()},403);await request(alice,runUrl(future),'POST',{requestId:nonce()},403);await request(alice,runUrl(empty),'POST',{requestId:nonce()},400);
  assert.equal((await request(alice,runUrl(cid))).data.run,null);assert.equal((await request(alice,'/api/ai/settings')).data.contestBlocked,false);
  report.checks.push('Real authentication and private/protected contest access are enforced; future and empty contests cannot start; viewing an ended contest does not create virtual participation.');
  const key=nonce(),a=(await request(alice,runUrl(cid),'POST',{requestId:key})).data.run;
  assert.equal(a.endsAt-a.startedAt,3600000);assert.equal(a.state,'RUNNING');
  const retries=await Promise.all([request(alice,runUrl(cid),'POST',{requestId:key}),request(alice,runUrl(cid),'POST',{requestId:nonce()})]);
  assert(retries.every(r=>r.data.run.id===a.id&&r.data.run.endsAt===a.endsAt));assert.equal(sql(`SELECT COUNT(*) FROM contest_virtual_run WHERE uid=${quote(alice.uid)};`),'1');
  await request(alice,runUrl(other),'POST',{requestId:nonce()},409);await request(alice,runUrl(other),'POST',{requestId:key},409);
  assert.equal((await request(bob,runUrl(cid))).data.run,null);assert.equal((await request(bob,'/api/ai/settings')).data.contestBlocked,false);
  report.checks.push('The personal deadline equals the original duration and survives simultaneous retries without extension; another contest conflicts and another user cannot see or inherit this window.');
  assert.equal((await request(alice,'/api/ai/settings')).data.contestBlocked,true);
  await request(alice,'/api/ai/chat','POST',{messages:[{role:'user',content:'测试问题'}],includeProfile:false,uid:bob.uid,contestId:0},403);
  await request(alice,'/api/ai/recommendations?source=CODEFORCES','GET',undefined,403);await request(alice,'/api/ai/recommendations/explain','POST',{},403);
  await request(bob,'/api/ai/chat','POST',{messages:[{role:'user',content:'测试问题'}]},400);
  await request(alice,'/api/logout');await request(alice,'/api/login','POST',{username:alice.username,password:alice.password});
  assert.equal((await request(alice,runUrl(cid))).data.run.endsAt,a.endsAt);assert.equal((await request(alice,'/api/ai/settings')).data.contestBlocked,true);
  const deletion=await fetch('http://127.0.0.1:6688'+runUrl(cid),{method:'DELETE',headers:{Authorization:alice.token}});assert.equal(deletion.status,405);
  assert.equal((await request(alice,'/api/ai/settings')).data.contestBlocked,true);
  report.checks.push('All AI prompt/recommendation routes reject the virtual participant across forged page/UID parameters and re-login; there is no early-cancel endpoint. An unrelated user remains available.');
  const simultaneous=await Promise.all([request(bob,runUrl(cid),'POST',{requestId:nonce()},[200,409]),request(bob,runUrl(other),'POST',{requestId:nonce()},[200,409])]);
  assert.deepEqual(simultaneous.map(r=>r.status).sort(),[200,409]);assert.equal(sql(`SELECT COUNT(*) FROM contest_virtual_run WHERE uid=${quote(bob.uid)};`),'1');
  report.checks.push('Actual HTTP concurrency on two different contests creates exactly one personal window under the deployed MySQL transaction.');
  sql(`UPDATE contest_virtual_run SET started_at=DATE_SUB(NOW(3),INTERVAL 2 HOUR),ends_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE uid=${quote(alice.uid)};`);report.expiryFixtureSimulated=true;
  assert.equal((await request(alice,'/api/ai/settings')).data.contestBlocked,false);assert.equal((await request(alice,runUrl(cid),'POST',{requestId:key})).data.run.state,'ENDED');
  assert.equal(sql(`SELECT COUNT(*) FROM contest_virtual_run WHERE uid=${quote(alice.uid)};`),'1');
  const next=(await request(alice,runUrl(other),'POST',{requestId:nonce()})).data.run;assert.notEqual(next.id,a.id);assert.equal(next.endsAt-next.startedAt,3600000);
  assert.equal(sql(`SELECT COUNT(*) FROM contest_register WHERE uid IN (${uids});`),'0');assert.equal(sql(`SELECT COUNT(*) FROM judge WHERE uid IN (${uids});`),'0');
  report.checks.push('A fixture-expired personal clock restores AI; an old request cannot silently start another round, while an explicit new nonce can. No formal membership, judged code or contest rating is created.');
  report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{sql(`DELETE FROM contest WHERE uid IN (${uids});DELETE FROM ai_contest_entry WHERE uid IN (${uids});DELETE FROM ai_user_setting WHERE uid IN (${uids});`);for(const u of users)if(u.token)await request(u,'/api/logout').catch(()=>{});sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);assert.equal(sql(`SELECT (SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids}))+(SELECT COUNT(*) FROM contest_virtual_run WHERE uid IN (${uids}));`),'0');report.cleaned=true;}catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'virtual-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
