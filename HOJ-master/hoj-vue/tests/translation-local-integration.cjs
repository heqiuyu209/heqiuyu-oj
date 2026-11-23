// Explicit local HTTP/MySQL checks. Never sends a model request; the configured
// fixture host is reserved .invalid and all generation attempts must fail before transport.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path'),{execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_TRANSLATION_TEST!=='1')throw Error('Set HOJ_LOCAL_TRANSLATION_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(require('os').tmpdir(),'hoj-translation-http');fs.mkdirSync(output,{recursive:true});
const users=['admin','admin','default_user'].map(role=>({role,uid:crypto.randomBytes(16).toString('hex'),username:'tr_ai_'+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(10).toString('hex')}));
const [alice,bob,student]=users,quote=v=>"'"+String(v).replace(/'/g,"''")+"'",uids=users.map(u=>quote(u.uid)).join(',');
const contest='991'+Date.now(),key='cf:contest:'+contest+':A',base='/api/admin/remote-sources/codeforces',endpoint='/api/admin/remote-sources/translation';
const report={checks:[],cleaned:false,realModelCalled:false,realSourceCodePosted:false};let pid,cid,originalProblems;
function sql(input){return execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
async function request(user,url,method='GET',data,status=200){
  const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',Accept:'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(30000)});
  const body=await response.json();if(user&&response.headers.get('Authorization'))user.token=response.headers.get('Authorization');assert.equal(body.status,status,url+': '+body.msg);return {data:body.data,headers:response.headers};
}
(async()=>{
  originalProblems=sql('SELECT COUNT(*) FROM problem;');
  const payload={problemKey:key,sourceHash:'a'.repeat(64),modelFingerprint:'f'.repeat(64),allowModelSource:true};
  await request(null,endpoint,'POST',payload,401);
  for(const user of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(user.uid)},${quote(user.username)},${quote(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(user.uid)},id FROM role WHERE role IN ('default_user',${quote(user.role)});`);await request(user,'/api/login','POST',{username:user.username,password:user.password});assert(user.token);}
  await request(student,endpoint,'POST',payload,403);await request(alice,endpoint,'POST',{...payload,allowModelSource:false},400);
  report.checks.push('Real Shiro HTTP rejects anonymous/ordinary users; explicit source opt-in is required.');
  sql(`INSERT INTO remote_catalog_problem(problem_key,contest_id,problem_index,title,rating,tags_json,content_hash,seen_token,last_seen_at,changed_at) VALUES(${quote(key)},${contest},'A','Synthetic translation fixture',NULL,'[]',${quote(crypto.createHash('sha256').update(key).digest('hex'))},'synthetic-translation',NOW(3),NOW(3));`);
  const content={title:'Synthetic English statement',description:'<p>Given <var>n</var>, find the sum.</p>',input:'<p>An integer.</p>',output:'<p>The sum.</p>',hint:'',examples:'<input> 1  2\n</input><output>3\n</output>',timeLimit:1000,memoryLimit:256};
  const view=(await request(alice,base+'/statements/en','POST',{problemKey:key,sourceHash:null,content})).data;pid=Number(view.import[0].problem_id);payload.sourceHash=view.source.contentHash;
  await request(bob,endpoint,'POST',payload,403);const noConfig=await request(alice,endpoint,'POST',payload,400);assert.equal(noConfig.headers.get('cache-control'),'no-store');
  const configured=(await request(alice,'/api/ai/settings','PUT',{baseUrl:'https://translation-fixture.invalid/v1',model:'synthetic-translator',apiKey:'synthetic-never-dispatched-key'})).data;
  assert(configured.fingerprint.match(/^[a-f0-9]{64}$/));assert.equal(configured.apiKey,undefined);assert.equal(configured.key_ciphertext,undefined);assert.equal((await request(bob,'/api/ai/settings')).data.configured,false);
  await request(alice,endpoint,'POST',payload,409);payload.modelFingerprint=configured.fingerprint;
  await request(alice,endpoint,'POST',{...payload,sourceHash:'b'.repeat(64)},409);
  report.checks.push('Own source maintenance and own model settings are enforced; stale model/source fingerprints fail before provider transport and no key is returned.');
  cid=Number(sql(`INSERT INTO contest(uid,author,title,auth,status,start_time,end_time,duration,visible,is_group) VALUES(${quote(alice.uid)},${quote(alice.username)},'Synthetic translation guard',0,1,DATE_SUB(NOW(),INTERVAL 2 HOUR),DATE_SUB(NOW(),INTERVAL 1 HOUR),3600,0,0);SELECT LAST_INSERT_ID();`));
  sql(`INSERT INTO contest_virtual_run(uid,cid,request_id,title,started_at,ends_at) VALUES(${quote(alice.uid)},${cid},${quote('translation_guard_'+crypto.randomBytes(8).toString('hex'))},'Synthetic virtual guard',NOW(3),DATE_ADD(NOW(3),INTERVAL 1 HOUR));`);
  await request(alice,endpoint,'POST',payload,403);assert.equal((await request(alice,'/api/ai/settings')).data.contestBlocked,true);
  sql(`DELETE FROM contest_virtual_run WHERE uid=${quote(alice.uid)};DELETE FROM user_role WHERE uid=${quote(alice.uid)} AND role_id IN (SELECT id FROM role WHERE role='admin');`);
  await request(alice,endpoint,'POST',payload,403);
  assert.equal(sql(`SELECT COUNT(*) FROM remote_statement_version WHERE problem_key=${quote(key)};`),'1');assert.equal(sql(`SELECT COUNT(*) FROM remote_statement_current WHERE problem_key=${quote(key)} AND translation_version_id IS NOT NULL;`),'0');
  report.checks.push('A synthetic persisted virtual window and later administration revocation block translation on the server; every denied request leaves the sole source version and untranslated pointer intact.');
  report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    sql(`DELETE FROM contest_virtual_run WHERE uid IN (${uids});${cid?`DELETE FROM contest WHERE id=${cid} AND uid=${quote(alice.uid)};`:''}DELETE FROM remote_statement_current WHERE problem_key=${quote(key)};DELETE FROM remote_statement_version WHERE problem_key=${quote(key)};DELETE FROM remote_problem_import WHERE problem_key=${quote(key)};${pid?`DELETE FROM problem WHERE id=${pid} AND author=${quote(alice.username)};`:''}DELETE FROM remote_catalog_problem WHERE problem_key=${quote(key)};DELETE FROM ai_user_setting WHERE uid IN (${uids});`);
    for(const user of users)if(user.token)await request(user,'/api/logout').catch(()=>{});
    sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`),'0');if(originalProblems!==undefined)assert.equal(sql('SELECT COUNT(*) FROM problem;'),originalProblems);report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'translation-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
