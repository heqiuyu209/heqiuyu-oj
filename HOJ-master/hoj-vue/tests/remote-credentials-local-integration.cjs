// Opt-in local HTTP/MySQL checks; source sessions are synthetic and never sent to a source.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path');
const {execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_CREDENTIAL_TEST!=='1')throw Error('Set HOJ_LOCAL_CREDENTIAL_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(require('os').tmpdir(),'hoj-session-review');fs.mkdirSync(output,{recursive:true});
const users=Array.from({length:2},()=>({uid:crypto.randomBytes(16).toString('hex'),username:'cred_it_'+crypto.randomBytes(4).toString('hex'),password:crypto.randomBytes(8).toString('hex')}));
const [alice,bob]=users,report={checks:[],cleaned:false},quote=v=>"'"+String(v).replace(/'/g,"''")+"'",uids=users.map(u=>quote(u.uid)).join(',');
function sql(input){return execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
async function request(user,url,method='GET',data,status=200){const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(30000)});const body=await response.json();if(user&&response.headers.get('Authorization'))user.token=response.headers.get('Authorization');assert.equal(body.status,status,url+': '+body.msg);if(status===200&&url.includes('submission-credentials'))assert.equal(response.headers.get('Cache-Control'),'no-store');return body.data;}
(async()=>{
  const endpoint=id=>'/api/learning/accounts/'+id+'/submission-credentials';
  const session={cookieHeader:'JSESSIONID=synthetic-never-dispatched',version:0,authorized:true};
  for(const [method,data] of [['GET',undefined],['PUT',session],['DELETE',undefined]])await request(null,endpoint(1)+'?version=0',method,data,401);
  for(const user of users){sql('INSERT INTO user_info(uuid,username,password) VALUES('+quote(user.uid)+','+quote(user.username)+','+quote(crypto.createHash('md5').update(user.password).digest('hex'))+');INSERT INTO user_record(uid) VALUES('+quote(user.uid)+');INSERT INTO user_role(uid,role_id) SELECT '+quote(user.uid)+",id FROM role WHERE role='default_user';");await request(user,'/api/login','POST',{username:user.username,password:user.password});assert(user.token);}
  const handle='fixture_'+crypto.randomBytes(5).toString('hex');let own=await request(alice,'/api/learning/accounts','POST',{platform:'CODEFORCES',handle});const cf=own.accounts[0].id;
  own=await request(alice,'/api/learning/accounts','POST',{platform:'ATCODER',handle});const ac=own.accounts.find(a=>a.platform==='ATCODER').id;
  sql('UPDATE external_sync_job SET next_run_at=NULL,lease_token=NULL,lease_until=NULL WHERE account_id IN(SELECT id FROM external_account WHERE uid='+quote(alice.uid)+');');
  assert.equal((await request(alice,endpoint(cf))).configured,false);await request(alice,endpoint(cf),'PUT',session,403);
  await request(bob,endpoint(cf),'GET',undefined,404);await request(bob,endpoint(cf),'PUT',session,404);await request(bob,endpoint(cf)+'?version=0','DELETE',undefined,404);
  report.checks.push('Real Shiro and owner checks reject anonymous, cross-user and unverified session operations before source access.');
  // Deliberate local-only ownership fixtures; no remote profile or authenticated session is used.
  sql('UPDATE external_account SET verified_identity=handle_key,verified_at=NOW(3) WHERE uid='+quote(alice.uid)+';');
  await request(alice,endpoint(cf),'PUT',{...session,authorized:false},400);
  await request(alice,endpoint(cf),'PUT',{...session,cookieHeader:'JSESSIONID=bad\r\nInjected: value'},400);
  await request(alice,endpoint(cf),'PUT',{...session,cookieHeader:'REVEL_SESSION=wrong-platform'},400);
  await request(alice,endpoint(cf),'PUT',{password:'synthetic-old-api',version:0},400);
  assert.equal(sql('SELECT COUNT(*) FROM remote_user_credential WHERE account_id IN('+Number(cf)+','+Number(ac)+');'),'0');
  report.checks.push('Missing consent, injected headers, wrong-platform cookies and the old password API are rejected without saving or authenticating.');
  // Metadata/tombstone fixtures intentionally contain no decryptable credential; encryption and successful
  // authenticated HTML verification are covered by isolated backend tests, not claimed by this HTTP check.
  const legacy='v1:synthetic-not-a-real-password-cipher';
  sql('INSERT INTO remote_user_credential VALUES('+Number(cf)+','+quote(legacy)+',1,NOW(3));');
  let saved=await request(alice,endpoint(cf));assert.equal(saved.credentialType,'LEGACY_PASSWORD');assert.equal(saved.reauthorizationRequired,true);assert.equal(saved.sessionCheckedAt,null);assert.equal(saved.submissionAvailable,false);assert(!JSON.stringify(saved).includes(legacy));
  const publicView=await request(null,'/api/learning/users/'+alice.uid+'/external');assert(!JSON.stringify(publicView).includes(legacy));assert(!JSON.stringify(publicView).includes('credential'));
  await request(alice,endpoint(cf),'PUT',session,409);await request(alice,endpoint(cf)+'?version=0','DELETE',undefined,409);assert.equal(sql('SELECT credential_ciphertext FROM remote_user_credential WHERE account_id='+Number(cf)+';'),legacy);
  report.checks.push('Legacy rows explicitly require session reauthorization; metadata and public profiles never expose stored contents, and stale writes leave them intact.');
  saved=await request(alice,endpoint(cf)+'?version=1','DELETE');assert.equal(saved.configured,false);assert.equal(saved.version,2);assert.equal(saved.credentialType,null);
  await request(alice,endpoint(cf),'PUT',session,409);assert.equal(sql('SELECT credential_ciphertext IS NULL FROM remote_user_credential WHERE account_id='+Number(cf)+';'),'1');
  report.checks.push('Revocation leaves a monotonic tombstone that rejects an old authorization request after deletion.');
  sql('UPDATE remote_user_credential SET credential_ciphertext=\'s1:synthetic-session-metadata-only\',credential_version=3,updated_at=NOW(3) WHERE account_id='+Number(cf)+';INSERT INTO remote_user_credential VALUES('+Number(ac)+", 's1:synthetic-other-metadata-only',1,NOW(3));");
  saved=await request(alice,endpoint(cf));assert.equal(saved.credentialType,'BROWSER_SESSION');assert.equal(saved.reauthorizationRequired,false);assert(saved.sessionCheckedAt);assert.equal(saved.submissionAvailable,true);assert.equal(saved.cookieHeader,undefined);assert.equal(saved.credentialCiphertext,undefined);
  await request(alice,'/api/learning/accounts/'+cf,'DELETE');assert.equal(sql('SELECT COUNT(*) FROM remote_user_credential WHERE account_id='+Number(cf)+';'),'0');assert.equal((await request(alice,endpoint(ac))).configured,true);
  report.checks.push('Session metadata is allowlisted and unlink removes only its own credential. No remote authentication or source code submission was performed.');
  report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{sql('DELETE FROM external_account WHERE uid IN('+uids+');');for(const user of users)if(user.token)await request(user,'/api/logout').catch(()=>{});sql('DELETE FROM session WHERE uid IN('+uids+');DELETE FROM user_info WHERE uuid IN('+uids+');');assert.equal(sql('SELECT COUNT(*) FROM user_info WHERE uuid IN('+uids+');'),'0');report.cleaned=true;}catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'remote-credentials-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
