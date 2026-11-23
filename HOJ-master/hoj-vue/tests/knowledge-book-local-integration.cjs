// Opt-in real whole-book upload, persistent worker, retrieval and source verification.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path'),{execFileSync}=require('child_process');
if(process.env.HOJ_LOCAL_KNOWLEDGE_BOOK_TEST!=='1')throw Error('Set HOJ_LOCAL_KNOWLEDGE_BOOK_TEST=1.');
const material=process.env.HOJ_KNOWLEDGE_BOOK_PATH,labels=process.env.HOJ_KNOWLEDGE_BOOK_GOLDENS,output=process.env.PAPER_REVIEW_OUTPUT;
assert(material&&labels&&output,'Set the existing PDF path, frozen labels and new output directory.');fs.mkdirSync(output,{recursive:true});
assert(!fs.existsSync(path.join(output,'book-local-integration.json')),'Use a new evidence directory.');
const raw=fs.readFileSync(material),gold=JSON.parse(fs.readFileSync(labels,'utf8')),hash=value=>crypto.createHash('sha256').update(value).digest('hex'),nonce=()=>crypto.randomBytes(16).toString('hex');
assert.equal(hash(raw),gold.sourceHash);assert(raw.length>8388608&&raw.length<=33554432);assert.equal(gold.goldens.length,4);
fs.writeFileSync(path.join(output,'book-goldens.json'),JSON.stringify(gold,null,2));
const report={validated:false,ingested:false,retrievalValidated:false,cleaned:false,apiMocked:false,externalModelCalls:0,sourceCalls:0,sourceHash:gold.sourceHash,checks:[],results:[],qualityGap:gold.qualityGap};
const users=['root','default_user'].map(role=>({role,uid:nonce(),username:'bkb_'+nonce().slice(0,10),password:nonce().slice(0,16)})),[admin,student]=users;
const quote=value=>"'"+String(value).replace(/'/g,"''")+"'",uids=users.map(user=>quote(user.uid)).join(','),base='/api/admin/knowledge/documents/';
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));let id,baseline,beforePoints,seedId,seedChunks=0;
const checkAvailability=process.env.HOJ_KNOWLEDGE_BOOK_AVAILABILITY_TEST==='1';
function sql(input){return execFileSync('docker',['exec','-i','hoj-master-hoj-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
function points(){return Number(execFileSync('docker',['exec','hoj-master-hoj-rag-1','python','-c',"import sqlite3;c=sqlite3.connect('file:/data/qdrant/collection/hoj_knowledge_v1/storage.sqlite?mode=ro',uri=True);print(c.execute('SELECT COUNT(*) FROM points').fetchone()[0]);c.close()"],{encoding:'utf8',windowsHide:true,timeout:15000}).trim());}
function indexVersionState(){assert.match(id,/^[a-f0-9]{32}$/);return execFileSync('docker',['exec','hoj-master-hoj-rag-1','python','-c',"import sqlite3,sys;c=sqlite3.connect('file:/data/state.sqlite3?mode=ro',uri=True);r=c.execute('SELECT state FROM version_manifest WHERE namespace=? AND doc_id=? AND version=? AND content_hash=?',('hoj',sys.argv[1],1,sys.argv[2])).fetchone();print(r[0] if r else 'MISSING');c.close()",id,gold.contentHash],{encoding:'utf8',windowsHide:true,timeout:15000}).trim();}
async function request(user,url,method='GET',data,expected=200){const response=await fetch('http://127.0.0.1:6688'+url,{method,headers:{'Content-Type':'application/json',...(user?.token?{Authorization:user.token}:{})},...(data===undefined?{}:{body:JSON.stringify(data)}),signal:AbortSignal.timeout(60000)});if(user&&response.headers.get('authorization'))user.token=response.headers.get('authorization');const body=await response.json();assert.equal(body.status,expected,url+': '+body.msg);return body.data;}
const detail=()=>request(admin,base+id);
async function command(action,extra={}){const row=await detail();return request(admin,base+id+'/'+action,'POST',{expectedRevision:row.revision,...extra});}
async function readable(){const remaining=Number(execFileSync('docker',['exec','hoj-master-hoj-redis-1','redis-cli','--raw','PTTL','ai:knowledge-read:'+admin.uid],{encoding:'utf8',windowsHide:true,timeout:10000}).trim());assert.notEqual(remaining,-1);if(remaining>0)await sleep(remaining+150);}
async function search(user,query,documentIds=[id]){if(user===admin)await readable();return request(user,'/api/knowledge/answer','POST',{search:{query,documentIds,topK:5},allowModelExcerpts:false});}
function checkpoint(){fs.writeFileSync(path.join(output,'book-live-progress.json'),JSON.stringify(report,null,2));}
(async()=>{
  baseline=sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),(SELECT COUNT(*) FROM knowledge_document),(SELECT COUNT(*) FROM knowledge_source);');beforePoints=points();
  for(const user of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(user.uid)},${quote(user.username)},${quote(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(user.uid)},id FROM role WHERE role IN ('default_user',${quote(user.role)});`);await request(user,'/api/login','POST',{username:user.username,password:user.password});}
  if(checkAvailability){
    const seed=await request(admin,'/api/admin/knowledge/documents','POST',{requestId:nonce(),title:'入库期间检索验收：前缀和',format:'text',visibility:'PUBLIC',content:'静态数组先计算前缀和 S，令 S[0]=0。区间 [l,r] 的元素和为 S[r]-S[l-1]。'});seedId=seed.id;
    const deadline=Date.now()+120000;let ready;
    while(Date.now()<deadline){const row=await request(admin,base+seedId);const state=row.versions.find(item=>item.version===1);if(state.state==='INDEXED'){ready=row;seedChunks=state.chunks;break;}assert.notEqual(state.jobState,'FAILED');await sleep(1000);}
    assert(ready,'Independent published fixture did not index');assert(seedChunks>0);
    await request(admin,base+seedId+'/publish','POST',{expectedRevision:ready.revision,version:1});
    report.availabilitySeedId=seedId;checkpoint();
  }
  const title='真实资料核对：算法竞赛中的初等数论';
  report.upload=await require('./knowledge-live-browser.cjs')({account:admin,output,roleList:['root','default_user'],uploadMaterial:material,uploadTitle:title});id=report.upload.id;report.documentId=id;checkpoint();
  const version=await request(admin,base+id+'/versions/1');assert.equal(version.format,'pdf');assert.equal(version.source.pages,gold.pages);assert.equal(version.source.hash,gold.sourceHash);assert.equal(version.source.extractionMode,'NATIVE');assert.equal(hash(version.content),gold.contentHash);assert.equal(Array.from(version.content).length,gold.characters);
  const pages=version.content.split('\n\f\n');assert.equal(pages.length,178);assert(pages[6].includes('if(x % i == 0)')&&pages[6].includes('i * i <= x; ++ i'));
  const source=await fetch('http://127.0.0.1:6688'+base+id+'/versions/1/original',{headers:{Authorization:admin.token},signal:AbortSignal.timeout(60000)});assert.equal(source.status,200);assert.equal(source.headers.get('content-type'),'application/pdf');assert.equal(hash(Buffer.from(await source.arrayBuffer())),gold.sourceHash);
  assert.equal((await detail()).publishedVersion,null);assert.equal((await search(admin,gold.goldens[0].question)).items.length,0);
  report.checks.push('The actual admin UI accepts and uploads the original 27 MiB PDF; server extraction has 178 physical pages, over 200k characters, exact source/hash, preserved checked operators and no implicit publication.');checkpoint();
  const started=Date.now(),deadline=started+1260000;let indexed=false,lastProgress=-1;
  while(Date.now()<deadline){const row=await detail(),state=row.versions.find(item=>item.version===1);assert(Number(state.attempts||0)<=1,'Whole-book ingestion must complete in a single attempt');if(state.state==='INDEXED'){assert(Number.isInteger(state.chunks)&&state.chunks>0&&state.chunks<=400);report.indexedChunks=state.chunks;assert.equal(state.attempts,1);indexed=true;break;}if(state.jobState==='FAILED')throw Error('Whole-book job failed: '+state.lastError);
    if(checkAvailability&&!report.queryDuringIngest&&state.jobState==='RUNNING'&&indexVersionState()==='WRITING'){
      const queryStarted=Date.now(),found=await search(student,'静态数组怎样求区间和',[seedId]);
      const requestElapsedMs=Date.now()-queryStarted;
      assert.equal(found.modelUsed,false);assert.equal(found.content,null);assert(found.items.length&&found.items.every(item=>item.documentId===seedId&&item.version===1&&item.excerpt.includes('S[r]-S[l-1]')));
      const after=(await detail()).versions.find(item=>item.version===1);assert.equal(after.jobState,'RUNNING');assert.notEqual(after.state,'INDEXED');
      assert.equal(indexVersionState(),'WRITING','The Python index must still be writing the exact book version after retrieval');
      report.queryDuringIngest={elapsedMs:Date.now()-queryStarted,requestElapsedMs,bookJobStillRunning:true,pythonStateBefore:'WRITING',pythonStateAfter:'WRITING',authorizedPublishedDocument:true,realBackendHttp:true,modelUsed:false};checkpoint();console.log(JSON.stringify(report.queryDuringIngest));
    }
    const minute=Math.floor((Date.now()-started)/60000);if(minute!==lastProgress){lastProgress=minute;report.indexProgress={elapsedSeconds:Math.round((Date.now()-started)/1000),state:state.state,jobState:state.jobState,attempts:state.attempts};checkpoint();console.log(JSON.stringify(report.indexProgress));await request(null,'/api/get-website-config');}await sleep(3000);}
  assert(indexed,'Whole-book indexing exceeded its bounded deadline');if(checkAvailability)assert(report.queryDuringIngest,'Authorized nonempty retrieval was not tested during the running book job');report.ingested=true;report.indexSeconds=Math.round((Date.now()-started)/1000);assert.equal(points(),beforePoints+seedChunks+report.indexedChunks);checkpoint();
  await command('publish',{version:1});
  for(const entry of gold.goldens){const result=await search(admin,entry.question);assert.equal(result.modelUsed,false);assert.equal(result.content,null);for(const item of result.items){assert.equal(item.documentId,id);assert.equal(item.version,1);assert(Number.isInteger(item.page)&&item.page>=1&&item.page<=178);assert(pages[item.page-1].includes(item.excerpt));assert.match(item.chunkId,/^[a-f0-9]{64}$/);const url=new URL(item.url,'http://127.0.0.1:8080');assert.equal(url.pathname,'/knowledge/'+id);assert.equal(Number(url.searchParams.get('page')),item.page);assert.equal(url.searchParams.get('chunk'),item.chunkId);}
    report.results.push({question:entry.question,ranks:entry.evidence.map(evidence=>result.items.findIndex(item=>entry.pages.includes(item.page)&&item.excerpt.includes(evidence))+1),hits:result.items});checkpoint();}
  report.retrievalValidated=report.results.every(row=>row.ranks.every(rank=>rank>0));
  assert.equal((await search(student,'数论')).items.length,0);await request(student,'/api/knowledge/documents/'+id,'GET',undefined,404);
  report.checks.push('Persistent local BGE-M3 indexing completes once under the PDF lease; all returned quotes are exact physical-page text and ADMIN access is enforced.');
  assert(report.retrievalValidated,'One or more frozen original-page evidence groups are missing from top five');
  await readable();report.reader=await require('./knowledge-live-browser.cjs')({account:admin,output,expectedId:id,version:1,pdfPage:7,pdfPageCount:178,expectedPdfText:'if(x % i == 0)',question:gold.goldens[0].question,roleList:['root','default_user']});
  report.checks.push('Four frozen questions retrieve complete text/code evidence in top five; an actual source link opens the original physical page with preserved operators. This does not prove full formula or generated-answer quality.');
  assert.equal(hash(fs.readFileSync(material)),gold.sourceHash);report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    const owned=sql(`SELECT id FROM knowledge_document WHERE created_by IN (${uids});`).split(/\s+/).filter(Boolean);
    for(const document of owned){assert.match(document,/^[a-f0-9]{32}$/);id=document;if((await detail()).state==='ACTIVE')await command('delete');}
    if(owned.length){const ids=owned.map(quote).join(','),deadline=Date.now()+1320000;let deleted=false;while(Date.now()<deadline){if(sql(`SELECT COUNT(*) FROM knowledge_job WHERE document_id IN (${ids}) AND operation='DELETE' AND state<>'DONE';`)==='0'){deleted=true;break;}await sleep(3000);}assert(deleted,'Owned revocations have not completed; retain metadata until worker finishes');sql(`DELETE FROM knowledge_job WHERE document_id IN (${ids});DELETE FROM knowledge_command WHERE document_id IN (${ids});DELETE FROM knowledge_source WHERE document_id IN (${ids});DELETE FROM knowledge_version WHERE document_id IN (${ids});DELETE FROM knowledge_document WHERE id IN (${ids});`);}
    for(const user of users)if(user.token)await request(user,'/api/logout').catch(()=>{});sql(`DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    assert.equal(sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),(SELECT COUNT(*) FROM knowledge_document),(SELECT COUNT(*) FROM knowledge_source);'),baseline);assert.equal(points(),beforePoints);assert.equal(hash(fs.readFileSync(material)),gold.sourceHash);report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'book-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({validated:report.validated,ingested:report.ingested,cleaned:report.cleaned,indexSeconds:report.indexSeconds,ranks:report.results.map(row=>row.ranks),failure:report.failure||null,cleanupFailure:report.cleanupFailure||null}));
});
