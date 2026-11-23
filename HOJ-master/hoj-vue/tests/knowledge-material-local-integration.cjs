// Opt-in real local lecture verification. Original materials stay unchanged; no model calls.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path'),{execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_KNOWLEDGE_MATERIAL_TEST!=='1')throw Error('Set HOJ_LOCAL_KNOWLEDGE_MATERIAL_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT;assert(output,'Set PAPER_REVIEW_OUTPUT.');fs.mkdirSync(output,{recursive:true});
const material=process.env.HOJ_KNOWLEDGE_MATERIAL;assert(material,'Set HOJ_KNOWLEDGE_MATERIAL to the existing game-theory lecture.');
const raw=fs.readFileSync(material),content=raw.toString('utf8').replace(/\r\n?/g,'\n'),hash=value=>crypto.createHash('sha256').update(value).digest('hex');
const goldens=[
  {question:'普通 Nim 游戏中，各堆石子异或为零时先手必胜还是必败？',evidence:['X = 0 → **必败**']},
  {question:'巴什博弈每次取一到 m 个石子，取走最后一个赢，什么情况下先手必败？',evidence:['n % (m+1) == 0']},
  {question:'反 Nim 游戏取走最后一个输，如果每堆都只有一个石子，如何判断先手胜负？',evidence:['偶数必胜，奇数必败']},
  {question:'Sequential Nim 中第一个大于一的石子堆的位置怎样决定先手是否获胜？',evidence:['return first_big % 2 == 0;']},
];
for(const entry of goldens)for(const evidence of entry.evidence)assert(content.includes(evidence),'The supplied lecture does not match the fixed evidence.');
const report={material:path.basename(material),materialHash:hash(raw),goldens,results:[],checks:[],validated:false,cleaned:false,modelCalls:0,sourceCalls:0,realEmbeddings:true,apiMocked:false};
// Persist labels before uploading or observing any retrieval result.
fs.writeFileSync(path.join(output,'lecture-goldens.json'),JSON.stringify({materialHash:report.materialHash,goldens},null,2));
const users=['root','default_user'].map(role=>({role,uid:crypto.randomBytes(16).toString('hex'),username:'kbm_'+crypto.randomBytes(5).toString('hex'),password:crypto.randomBytes(8).toString('hex')})),[admin,student]=users;
const q=value=>"'"+String(value).replace(/'/g,"''")+"'",uids=users.map(user=>q(user.uid)).join(','),base='/api/admin/knowledge/documents/';
let id,baseline,beforePoints;
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function sql(input){return execFileSync('docker',['exec','-i','hoj-master-hoj-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
function points(){return Number(execFileSync('docker',['exec','hoj-master-hoj-rag-1','python','-c',"import sqlite3;c=sqlite3.connect('file:/data/qdrant/collection/hoj_knowledge_v1/storage.sqlite?mode=ro',uri=True);print(c.execute('SELECT COUNT(*) FROM points').fetchone()[0]);c.close()"],{encoding:'utf8',windowsHide:true,timeout:15000}).trim());}
async function request(user,url,method='GET',data,status=200){const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(60000)}),body=await response.json();if(user&&response.headers.get('Authorization'))user.token=response.headers.get('Authorization');assert.equal(body.status,status,url+': '+body.msg);return body.data;}
const detail=()=>request(admin,base+id);
async function command(action,extra={}){const row=await detail();return request(admin,base+id+'/'+action,'POST',{expectedRevision:row.revision,...extra});}
async function readable(){const remaining=Number(execFileSync('docker',['exec','hoj-master-hoj-redis-1','redis-cli','--raw','PTTL','ai:knowledge-read:'+admin.uid],{encoding:'utf8',windowsHide:true,timeout:10000}).trim());assert.notEqual(remaining,-1);if(remaining>0)await sleep(remaining+150);}
async function search(user,question){if(user===admin)await readable();return request(user,'/api/knowledge/answer','POST',{search:{query:question,documentIds:[id],topK:5},allowModelExcerpts:false});}
(async()=>{
  baseline=sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),(SELECT COUNT(*) FROM knowledge_document);');beforePoints=points();
  for(const user of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${q(user.uid)},${q(user.username)},${q(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${q(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${q(user.uid)},id FROM role WHERE role IN ('default_user',${q(user.role)});`);await request(user,'/api/login','POST',{username:user.username,password:user.password});}
  const created=await request(admin,'/api/admin/knowledge/documents','POST',{requestId:crypto.randomBytes(16).toString('hex'),title:'真实资料核对：博弈论（一）Nim 游戏',content:raw.toString('utf8'),format:'markdown',visibility:'ADMIN'});id=created.id;
  for(let attempt=0;attempt<150;attempt++){const row=await detail(),version=row.versions.find(item=>item.version===1);if(version.state==='INDEXED')break;if(version.jobState==='FAILED')throw Error(version.lastError);if(attempt===149)throw Error('Lecture indexing did not finish');await sleep(1000);}
  assert.equal((await detail()).publishedVersion,null);assert.equal((await search(admin,goldens[0].question)).items.length,0);
  await command('publish',{version:1});assert(points()>beforePoints);
  report.checks.push('The existing lecture is ingested through the product and real local BGE-M3, with ADMIN visibility and explicit publication; unpublished material is not retrieved.');
  for(const golden of goldens){const started=Date.now(),result=await search(admin,golden.question);assert.equal(result.modelUsed,false);assert.equal(result.content,null);for(const item of result.items){assert.equal(item.documentId,id);assert.equal(item.version,1);assert(content.includes(item.excerpt),'Citation must be an exact excerpt of the normalized original');assert.match(item.chunkId,/^[a-f0-9]{64}$/);const target=new URL(item.url,'http://127.0.0.1:8080');assert.equal(target.pathname,'/knowledge/'+id);assert.equal(target.searchParams.get('version'),'1');assert.equal(target.searchParams.get('chunk'),item.chunkId);}
    const ranks=golden.evidence.map(evidence=>result.items.findIndex(item=>item.excerpt.includes(evidence))+1);report.results.push({question:golden.question,ranks,ms:Date.now()-started,hits:result.items});}
  assert(report.results.every(row=>row.ranks.every(rank=>rank>0)),'Expected complete original evidence is missing from the top five');
  report.checks.push('Four questions fixed before ingestion retrieve their complete evidence in the top five; original excerpts/version/hash are checked, with no generated answer claimed.');
  assert.equal((await search(student,'Nim 游戏')).items.length,0);await request(student,'/api/knowledge/documents/'+id,'GET',undefined,404);
  report.checks.push('An ordinary account cannot retrieve or read the ADMIN lecture, including when supplying its document ID.');
  if(process.env.HOJ_LOCAL_KNOWLEDGE_MATERIAL_BROWSER==='1'){await readable();report.browser=await require('./knowledge-live-browser.cjs')({account:admin,output,expectedId:id,version:1,question:goldens[0].question,expectedText:'res ^= x;',roleList:['root','default_user']});}
  assert.equal(hash(fs.readFileSync(material)),report.materialHash);report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    const owned=sql(`SELECT id FROM knowledge_document WHERE created_by IN (${uids});`).split(/\s+/).filter(Boolean);
    for(const document of owned){assert.match(document,/^[a-f0-9]{32}$/);id=document;const row=await detail();if(row.state==='ACTIVE')await command('delete');}
    if(owned.length){const list=owned.map(q).join(',');for(let attempt=0;attempt<100;attempt++){if(sql(`SELECT COUNT(*) FROM knowledge_job WHERE document_id IN (${list}) AND operation='DELETE' AND state<>'DONE';`)==='0')break;await sleep(1000);}assert.equal(sql(`SELECT COUNT(*) FROM knowledge_job WHERE document_id IN (${list}) AND operation='DELETE' AND state<>'DONE';`),'0');sql(`DELETE FROM knowledge_job WHERE document_id IN (${list});DELETE FROM knowledge_command WHERE document_id IN (${list});DELETE FROM knowledge_source WHERE document_id IN (${list});DELETE FROM knowledge_version WHERE document_id IN (${list});DELETE FROM knowledge_document WHERE id IN (${list});`);}
    for(const user of users)if(user.token)await request(user,'/api/logout').catch(()=>{});sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),(SELECT COUNT(*) FROM knowledge_document);'),baseline);assert.equal(points(),beforePoints);assert.equal(hash(fs.readFileSync(material)),report.materialHash);report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'knowledge-material-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({validated:report.validated,cleaned:report.cleaned,checks:report.checks.length,ranks:report.results.map(row=>row.ranks),failure:report.failure||null,cleanupFailure:report.cleanupFailure||null}));
});
