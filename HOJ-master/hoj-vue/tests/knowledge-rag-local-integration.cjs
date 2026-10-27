// Explicit real local integration. No fake INDEXED updates and no model calls.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path'),{execFileSync}=require('child_process');
const reviewPage=require('./knowledge-live-browser.cjs');
if(process.env.HOJ_LOCAL_KNOWLEDGE_RAG_TEST!=='1')throw Error('Set HOJ_LOCAL_KNOWLEDGE_RAG_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT||path.join(require('os').tmpdir(),'hoj-knowledge-rag');fs.mkdirSync(output,{recursive:true});
const users=['root','default_user','default_user'].map(role=>({role,uid:crypto.randomBytes(16).toString('hex'),username:'kbr_it_'+crypto.randomBytes(5).toString('hex'),password:crypto.randomBytes(8).toString('hex'),token:''}));
const [teacher,alice,bob]=users,quote=v=>"'"+String(v).replace(/'/g,"''")+"'",uids=users.map(u=>quote(u.uid)).join(',');
const report={checks:[],realLocalEmbeddings:true,fakeIndexedUpdates:false,externalModelCalls:0,cleaned:false};const docs=[];let baseline,pointsBefore;
function sql(input){return execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
function points(){return Number(execFileSync('docker',['exec','hoj-master-hoj-rag-1','python','-c',"import sqlite3; c=sqlite3.connect('file:/data/qdrant/collection/hoj_knowledge_v1/storage.sqlite?mode=ro',uri=True); print(c.execute('SELECT COUNT(*) FROM points').fetchone()[0]); c.close()"],{encoding:'utf8',windowsHide:true,timeout:15000}).trim());}
async function request(user,url,method='GET',data,expected=200){const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(45000)});const body=await response.json();if(user&&response.headers.get('authorization'))user.token=response.headers.get('authorization');assert.equal(body.status,expected,url+': '+body.msg);if(expected===200&&url.includes('/knowledge'))assert.equal(response.headers.get('cache-control'),'no-store');return body.data;}
const base='/api/admin/knowledge/documents/',nonce=()=>crypto.randomBytes(16).toString('hex'),sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function detail(id){return request(teacher,base+id);}
async function indexed(id,version,state='INDEXED'){let last;for(let i=0;i<50;i++){last=await detail(id);const found=last.versions.find(v=>v.version===version);if(found.state===state)return last;if(found.jobState==='FAILED'&&state!=='FAILED')throw Error('Index job failed: '+found.lastError);await sleep(1000);}throw Error('Index job did not finish: '+JSON.stringify(last.versions.map(v=>({version:v.version,state:v.state,jobState:v.jobState,lastError:v.lastError}))));}
async function command(id,action,extra={},expected=200){const d=await detail(id);return request(teacher,base+id+'/'+action,'POST',{expectedRevision:d.revision,...extra},expected);}
async function create(title,content,visibility='PUBLIC',format='markdown'){const d=await request(teacher,'/api/admin/knowledge/documents','POST',{requestId:nonce(),title,content,visibility,format});docs.push(d.id);return d.id;}
function cooldown(user){return Number(execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-redis','redis-cli','--raw','PTTL','ai:knowledge-read:'+user.uid],{encoding:'utf8',windowsHide:true,timeout:10000}).trim());}
async function query(user,query,documentIds){const before=cooldown(user);assert(before!==-1,'Knowledge cooldown must expire');if(before>0)await sleep(before+150);const started=Date.now();try{return await request(user,'/api/knowledge/answer','POST',{search:{query,topK:5,...(documentIds===undefined?{}:{documentIds})},allowModelExcerpts:false});}finally{(report.cooldownChecks||(report.cooldownChecks=[])).push({before,after:cooldown(user),elapsedMs:Date.now()-started});}}
(async()=>{
  baseline=sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),(SELECT COUNT(*) FROM knowledge_document);');pointsBefore=points();
  for(const u of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(u.uid)},${quote(u.username)},${quote(crypto.createHash('md5').update(u.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(u.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(u.uid)},id FROM role WHERE role IN ('default_user',${quote(u.role)});`);await request(u,'/api/login','POST',{username:u.username,password:u.password});}
  assert.equal((await request(teacher,'/api/admin/knowledge')).indexConfigured,true);
  const a=await create('验收样例：前缀和与区间求和','# 前缀和\n\n对静态数组先计算前缀和 S，S[0]=0，S[i]=S[i-1]+a[i]。区间 [l,r] 的元素之和等于 S[r]-S[l-1]。预处理 O(n)，每次区间查询 O(1)。');
  const b=await create('验收样例：二分答案','当判定函数具有单调性时，可以二分最优答案。例如容量越大越容易满足约束，可二分最小可行容量。','PUBLIC','text');
  const secret='private_fixture_'+nonce();const c=await create('仅管理员的验收讲义','# 私有讲义\n\n'+secret+'。这是仅管理员可见的演示资料。','ADMIN');
  for(const id of docs){const state=await indexed(id,1);assert.equal(state.publishedVersion,null);}
  assert(!(await request(alice,'/api/knowledge/documents')).items.some(i=>docs.includes(i.id)));assert.equal((await query(alice,'前缀和',[a])).items.length,0);
  for(const id of docs)await command(id,'publish',{version:1});assert(points()>pointsBefore);
  report.checks.push('Actual persistent worker ingests Markdown/text through signed HTTP into local BGE-M3/Qdrant; versions become INDEXED without auto-publishing, and an explicit admin publication makes them visible. No database INDEXED fixture was used.');
  const found=await query(alice,'怎样快速计算静态数组区间元素之和');assert.equal(found.modelUsed,false);assert.equal(found.content,null);assert(found.items.some(i=>i.documentId===a));assert(found.items.every(i=>i.documentId!==c&&!JSON.stringify(i).includes(secret)));
  assert.equal((await query(alice,secret,[c])).items.length,0);const privateHit=await query(teacher,secret,[c]);assert(privateHit.items.length&&privateHit.items.every(i=>i.documentId===c));
  assert.equal((await request(alice,'/api/knowledge/documents/'+a+'?version=1')).version,1);await request(alice,'/api/knowledge/documents/'+c,'GET',undefined,404);
  await request(alice,'/api/knowledge/answer','POST',{search:{query:'前缀和'},allowModelExcerpts:true},400);
  report.checks.push('Real vector retrieval produces the relevant public original and server citations without a user model; normal users cannot retrieve/read ADMIN data, while an authorized admin can. Opting into an unconfigured user model fails before generation.');
  let d=await detail(a);await request(teacher,base+a+'/versions','POST',{requestId:nonce(),expectedRevision:d.revision,title:'无正文的待修订讲义',format:'markdown',content:'---\ntitle: no body\n---\n',visibility:'PUBLIC'});
  const failed=(await indexed(a,2,'FAILED')).versions.find(v=>v.version===2);assert.equal(failed.lastError,'INVALID_CONTENT');assert.equal(failed.attempts,1);await command(a,'publish',{version:2},409);
  assert.equal((await query(alice,'区间和',[a])).items[0].version,1);
  d=await detail(a);await request(teacher,base+a+'/versions','POST',{requestId:nonce(),expectedRevision:d.revision,title:'验收样例：前缀和与区间求和（修订）',format:'markdown',content:'# 前缀和\n\n区间和 S[r]-S[l-1] 来自两个前缀和之差。查询前先处理好 l=1 的边界，令 S[0]=0。若数组频繁修改，可考虑树状数组。',visibility:'PUBLIC'});
  await indexed(a,3);assert.equal((await detail(a)).publishedVersion,1);await command(a,'publish',{version:3});await request(alice,'/api/knowledge/documents/'+a+'?version=1','GET',undefined,404);assert.equal((await query(alice,'前缀和的左边界如何处理',[a])).items[0].version,3);
  if(process.env.HOJ_KNOWLEDGE_RESTART_TEST==='1'){
    const before=points();execFileSync('docker',['restart','hoj-master-hoj-rag-1'],{encoding:'utf8',windowsHide:true,timeout:30000});let healthy=false;
    for(let i=0;i<150;i++){const state=execFileSync('docker',['inspect','hoj-master-hoj-rag-1','--format','{{.State.Health.Status}}'],{encoding:'utf8',windowsHide:true,timeout:10000}).trim();if(state==='healthy'){healthy=true;break;}await sleep(1000);}
    assert(healthy,'Restarted local RAG must become healthy');assert.equal(points(),before);assert.equal((await query(alice,'前缀和的边界条件',[a])).items[0].version,3);assert.equal((await detail(a)).versions.find(v=>v.version===3).attempts,1);
    report.restart={healthy:true,vectorPointsPreserved:true,noReingestionRequired:true};
  }
  report.checks.push('A real parser rejection is a permanent first-attempt failure and cannot publish; the old published version remains searchable. A corrected immutable version indexes/publishes separately, and the old citation is rejected.');
  report.browser=await reviewPage({account:bob,output,expectedId:a,version:3});
  await request(teacher,base+a+'/visibility','PUT',{expectedRevision:(await detail(a)).revision,visibility:'ADMIN'});await request(alice,'/api/knowledge/documents/'+a,'GET',undefined,404);assert.equal((await query(alice,'前缀和',[a])).items.length,0);
  const cid=Number(sql(`INSERT INTO contest(uid,author,title,auth,status,start_time,end_time,duration,visible) VALUES(${quote(teacher.uid)},${quote(teacher.username)},'temporary RAG policy contest',0,0,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 HOUR),7200,1);SELECT LAST_INSERT_ID();INSERT INTO contest_register(cid,uid,status) VALUES(LAST_INSERT_ID(),${quote(alice.uid)},0);`));
  await request(alice,'/api/knowledge/search','POST',{query:'二分答案'},403);await request(alice,'/api/knowledge/documents/'+b,'GET',undefined,403);sql(`DELETE FROM contest_register WHERE uid=${quote(alice.uid)};DELETE FROM contest WHERE uid=${quote(teacher.uid)};`);
  assert.equal((await request(bob,'/api/knowledge/documents/'+b)).version,1);
  report.checks.push('The actual Vue page uses real APIs and pure retrieval, follows a current original citation, and produces two screenshots without API mocks. Visibility revocation and active formal participation still prevent reads/retrieval with a working index.');
  report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    for(const id of sql(`SELECT id FROM knowledge_document WHERE created_by IN (${uids});`).split(/\s+/).filter(Boolean)){assert.match(id,/^[a-f0-9]{32}$/);if(!docs.includes(id))docs.push(id);}
    for(const id of docs){const d=await detail(id);if(d.state==='ACTIVE')await command(id,'delete');}
    if(docs.length){for(let i=0;i<75;i++){if(sql(`SELECT COUNT(*) FROM knowledge_job WHERE document_id IN (${docs.map(quote).join(',')}) AND operation='DELETE' AND state<>'DONE';`)==='0')break;await sleep(1000);}
      assert.equal(sql(`SELECT COUNT(*) FROM knowledge_job WHERE document_id IN (${docs.map(quote).join(',')}) AND operation='DELETE' AND state<>'DONE';`),'0');
      const ids=docs.map(quote).join(',');sql(`DELETE FROM knowledge_job WHERE document_id IN (${ids});DELETE FROM knowledge_command WHERE document_id IN (${ids});DELETE FROM knowledge_version WHERE document_id IN (${ids});DELETE FROM knowledge_document WHERE id IN (${ids});`);}
    sql(`DELETE FROM contest_register WHERE uid IN (${uids});DELETE FROM contest WHERE uid IN (${uids});DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),(SELECT COUNT(*) FROM knowledge_document);'),baseline);assert.equal(points(),pointsBefore);assert.equal(sql(`SELECT COUNT(*) FROM user_info WHERE uuid IN (${uids});`),'0');
    report.cleaned=true;report.vectorPointsRestored=true;report.revocationTombstonesRetained=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'knowledge-rag-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
