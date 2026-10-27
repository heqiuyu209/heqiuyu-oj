// Explicit opt-in: real local upload -> ASR -> BGE/Qdrant -> publication -> private byte ranges.
const assert=require('assert').strict,crypto=require('crypto'),fs=require('fs'),path=require('path'),{execFileSync}=require('child_process');
const reviewPage=require('./knowledge-live-browser.cjs');
if(process.env.HOJ_LOCAL_KNOWLEDGE_VIDEO_TEST!=='1')throw Error('Set HOJ_LOCAL_KNOWLEDGE_VIDEO_TEST=1.');
const output=process.env.PAPER_REVIEW_OUTPUT,fixture=process.env.HOJ_VIDEO_FIXTURE;
if(!output||!fixture)throw Error('Set output and synthetic video fixture.');fs.mkdirSync(output,{recursive:true});
const raw=fs.readFileSync(fixture),hash=value=>crypto.createHash('sha256').update(value).digest('hex'),nonce=()=>crypto.randomBytes(16).toString('hex');
const users=['root','default_user','default_user'].map(role=>({role,uid:nonce(),username:'vid_it_'+crypto.randomBytes(5).toString('hex'),password:crypto.randomBytes(8).toString('hex'),token:''}));
const [teacher,alice,bob]=users,quote=value=>"'"+String(value).replace(/'/g,"''")+"'",uids=users.map(user=>quote(user.uid)).join(',');
const root='/api/admin/knowledge',base=root+'/documents/',report={checks:[],realLocalAsr:true,realLocalEmbeddings:true,externalModelCalls:0,cleaned:false};
let baseline,pointsBefore;const docs=[],assets=new Set(),cleanupFiles=new Set(),sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function sql(input){return execFileSync('docker',['compose','-p','hoj-master','-f',path.resolve(__dirname,'../../compose.yaml'),'exec','-T','hoj-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=hoj --batch --skip-column-names --default-character-set=utf8mb4'],{input,encoding:'utf8',windowsHide:true,timeout:30000}).trim();}
function points(){return Number(execFileSync('docker',['exec','hoj-master-hoj-rag-1','python','-c',"import sqlite3;c=sqlite3.connect('file:/data/qdrant/collection/hoj_knowledge_v1/storage.sqlite?mode=ro',uri=True);print(c.execute('SELECT COUNT(*) FROM points').fetchone()[0]);c.close()"],{encoding:'utf8',windowsHide:true,timeout:15000}).trim());}
function mediaPath(name){assert.match(name,/^(?:[a-f0-9]{32}\.bin|staged-[a-f0-9]{32}\.part|gc-[a-f0-9]{32}-[a-f0-9]{32}\.bin)$/);return '/hoj/knowledge-media/'+name;}
function backendFile(command,...names){return execFileSync('docker',['exec','hoj-master-hoj-backend-1','sh','-c',command,'sh',...names.map(mediaPath)],{encoding:'utf8',windowsHide:true,timeout:10000}).trim();}
function mediaExists(name){try{backendFile('test -f "$1"',name);return true;}catch(error){if(error.status===1)return false;throw error;}}
function counts(){return sql('SELECT (SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM objective_question),(SELECT COUNT(*) FROM knowledge_document),(SELECT COUNT(*) FROM knowledge_video_import),(SELECT COUNT(*) FROM knowledge_video_source),(SELECT active_count FROM knowledge_video_quota WHERE owner=\'_all\');');}
async function fetchAs(user,url,options={}){const response=await fetch('http://127.0.0.1:6688'+url,{...options,headers:{...options.headers,...(user?.token?{Authorization:user.token}:{})},signal:AbortSignal.timeout(125000)});if(user&&response.headers.get('authorization'))user.token=response.headers.get('authorization');return response;}
async function request(user,url,method='GET',data,expected=200){const response=await fetchAs(user,url,{method,headers:{'Content-Type':'application/json'},...(data===undefined?{}:{body:JSON.stringify(data)})});const body=await response.json();assert.equal(body.status,expected,url+': '+body.msg);return body.data;}
async function upload(user,metadata,expected=200){const response=await fetchAs(user,root+'/video-imports?'+new URLSearchParams(metadata),{method:'POST',headers:{'Content-Type':'video/mp4'},body:raw});const body=await response.json();assert.equal(body.status,expected,body.msg);return body.data;}
const detail=id=>request(teacher,base+id),task=id=>request(teacher,root+'/video-imports/'+id);
async function command(id,action,extra={}){const current=await detail(id);return request(teacher,base+id+'/'+action,'POST',{expectedRevision:current.revision,...extra});}
async function ready(id){for(let i=0;i<250;i++){const current=await task(id);if(current.state==='READY')return current;if(current.state==='FAILED')throw Error('ASR failed: '+current.lastError);await sleep(1000);}throw Error('Video transcription timed out');}
async function indexed(id,version){for(let i=0;i<120;i++){const current=await detail(id),item=current.versions.find(value=>value.version===version);if(item.state==='INDEXED')return current;if(item.jobState==='FAILED')throw Error('Video indexing failed: '+item.lastError);await sleep(1000);}throw Error('Video index timed out');}
async function grant(user,url,expected=200,cookie){const response=await fetchAs(user,url,{method:'POST',headers:{...(cookie?{Cookie:cookie}:{})}});const body=await response.json();assert.equal(body.status,expected,url+': '+body.msg);if(expected!==200)return;const headers=response.headers.getSetCookie();report.cookieNames=[...new Set(headers.map(value=>value.split('=')[0]))];const header=headers.find(value=>value.startsWith('HOJ_KB_VIDEO='));assert(header?.includes('HttpOnly')&&header.includes('SameSite=Strict')&&header.includes('Max-Age=120'));assert(!body.data.url.includes('token'));return {url:body.data.url,cookie:header.split(';')[0]};}
async function media(session,range,expected=206,url=session.url){const response=await fetchAs(null,url,{headers:{Cookie:session.cookie,...(range?{Range:range}:{})}});assert.equal(response.status,expected,url);assert.equal(response.headers.get('cache-control'),'no-store');return response;}
async function query(id){await sleep(2100);return request(alice,'/api/knowledge/answer','POST',{search:{query:'前缀和的区间求和方法',documentIds:[id],topK:5},allowModelExcerpts:false});}
(async()=>{
  baseline=counts();pointsBefore=points();
  for(const user of users){sql(`INSERT INTO user_info(uuid,username,password) VALUES(${quote(user.uid)},${quote(user.username)},${quote(crypto.createHash('md5').update(user.password).digest('hex'))});INSERT INTO user_record(uid) VALUES(${quote(user.uid)});INSERT INTO user_role(uid,role_id) SELECT ${quote(user.uid)},id FROM role WHERE role IN ('default_user',${quote(user.role)});`);await request(user,'/api/login','POST',{username:user.username,password:user.password});}
  const metadata={title:'前缀和与最短路视频验收',visibility:'PUBLIC',fileName:'算法课堂.mp4',language:'auto',requestId:nonce()};
  await upload(null,metadata,401);await upload(alice,metadata,403);
  const first=await upload(teacher,metadata),importId=first.id;assert.equal(first.state,'QUEUED');assert.equal((await upload(teacher,metadata)).id,importId);await upload(teacher,{...metadata,title:'changed'},409);
  const done=await ready(importId),transcript=JSON.parse(done.content);assert.equal(transcript.schema,1);assert.equal(transcript.durationMs,80000);assert(transcript.segments.some(value=>value.text.includes('前缀')));assert(transcript.segments.some(value=>/weight/i.test(value.text)));assert(!Object.hasOwn(done,'assetId'));
  fs.writeFileSync(path.join(output,'transcript-original.json'),done.content);report.asr={verified:true,durationMs:transcript.durationMs,segments:transcript.segments.length};
  if(process.env.HOJ_VIDEO_CLEANUP_TEST==='1'){
    const original=sql(`SELECT asset_id FROM knowledge_video_import WHERE id=${quote(importId)};`)+'.bin';mediaPath(original);
    const old=[nonce()+'.bin','staged-'+nonce()+'.part','gc-'+nonce()+'-'+nonce()+'.bin'],fresh=nonce()+'.bin';
    for(const name of [...old,fresh]){cleanupFiles.add(name);backendFile('test ! -e "$2" && cp -- "$1" "$2"',original,name);if(name!==fresh)backendFile('touch -m -d "2 days ago" -- "$1"',name);}
    backendFile('touch -m -d "2 days ago" -- "$1"',original);
    let reclaimed=false;for(let i=0;i<100;i++){if(old.every(name=>!mediaExists(name))){reclaimed=true;break;}await sleep(1000);}
    assert(reclaimed,'Scheduled orphan cleanup did not finish');assert(mediaExists(fresh));assert(mediaExists(original));assert.equal((await task(importId)).state,'READY');
    report.cleanupValidation={scheduled:true,agedOrphansRemoved:true,quarantineResumed:true,freshGracePreserved:true,readyImportPreserved:true};
    report.checks.push('Scheduled cleanup removes aged unreferenced originals/partial files and resumes quarantined garbage, while retaining a fresh orphan for its grace period and an aged READY import original.');
  }
  const draft=await grant(teacher,root+'/video-imports/'+importId+'/video/access');
  const part=await media(draft,'bytes=12-75');assert.equal(part.headers.get('content-range'),'bytes 12-75/'+raw.length);assert.deepEqual(Buffer.from(await part.arrayBuffer()),raw.subarray(12,76));
  const suffix=await media(draft,'bytes=-16');assert.deepEqual(Buffer.from(await suffix.arrayBuffer()),raw.subarray(raw.length-16));const unsatisfied=await media(draft,'bytes='+raw.length+'-',416);assert.equal(unsatisfied.headers.get('content-range'),'bytes */'+raw.length);
  const outside=await fetchAs(null,root,{headers:{Cookie:draft.cookie}});assert.equal((await outside.json()).status,401);
  const mutation=await fetchAs(null,root+'/documents',{method:'POST',headers:{Cookie:draft.cookie,'Content-Type':'application/json'},body:'{}'});assert.equal((await mutation.json()).status,401);
  report.checks.push('Real raw upload is admin-only and nonce-idempotent; persistent worker completes offline Chinese/English ASR. Opaque HttpOnly scoped cookies serve exact byte ranges and cannot authorize other APIs or mutations.');
  const saved=await request(teacher,root+'/video-imports/'+importId+'/save','POST',{title:metadata.title,visibility:'PUBLIC',requestId:nonce(),importRevision:done.revision,reviewedVideo:false,content:'forged browser text'}),id=saved.id;docs.push(id);assert.equal(saved.publishedVersion,null);
  const version=await request(teacher,base+id+'/versions/1');assert.equal(version.format,'video');assert.equal(version.source.hash,hash(raw));assert.equal(version.source.extractionMode,'ASR');assert.equal(version.content,done.content);
  await grant(teacher,root+'/video-imports/'+importId+'/video/access',409);await grant(alice,'/api/knowledge/documents/'+id+'/video/access?version=1',404);await grant(alice,base+id+'/versions/1/video/access',403);
  await indexed(id,1);assert.equal((await detail(id)).publishedVersion,null);await command(id,'publish',{version:1});
  const access=await grant(alice,'/api/knowledge/documents/'+id+'/video/access?version=1');const renewed=await grant(alice,'/api/knowledge/documents/'+id+'/video/access?version=1',200,access.cookie);assert.equal(renewed.cookie,access.cookie);
  const original=await media(access,undefined,200);assert.equal(hash(Buffer.from(await original.arrayBuffer())),hash(raw));
  const found=await query(id);assert.equal(found.modelUsed,false);assert(found.items.some(value=>value.excerpt.includes('前缀')));assert(found.items.every(value=>Number.isInteger(value.startMs)&&value.startMs>=0&&value.endMs>value.startMs&&value.endMs<=80000&&value.url.includes('&t=')));
  report.checks.push('Saving creates an unpublished immutable video version from server ASR. Real BGE/Qdrant indexing and explicit publication enable verified timed citations and original-byte playback; authenticated renewal preserves the active media handle.');
  if(process.env.HOJ_KNOWLEDGE_RESTART_TEST==='1'){
    const before=points();execFileSync('docker',['restart','hoj-master-hoj-rag-1'],{encoding:'utf8',windowsHide:true,timeout:30000});let healthy=false;
    for(let i=0;i<150;i++){if(execFileSync('docker',['inspect','hoj-master-hoj-rag-1','--format','{{.State.Health.Status}}'],{encoding:'utf8',windowsHide:true,timeout:10000}).trim()==='healthy'){healthy=true;break;}await sleep(1000);}
    assert(healthy);assert.equal(points(),before);assert((await query(id)).items.length);assert.equal(hash(Buffer.from(await(await media(access,undefined,200)).arrayBuffer())),hash(raw));assert.equal((await detail(id)).versions.find(value=>value.version===1).attempts,1);report.restart={healthy:true,vectorsPreserved:true,originalPreserved:true,noReingestionRequired:true};
  }
  const corrected=JSON.parse(done.content);corrected.segments[0].text='管理员校订：'+corrected.segments[0].text;
  const edit={title:metadata.title,visibility:'PUBLIC',requestId:nonce(),expectedRevision:(await detail(id)).revision,format:'video',sourceVersion:1,reviewedVideo:true,content:JSON.stringify(corrected)};
  assert.equal((await request(teacher,base+id+'/versions','POST',edit)).requestedVersion,2);assert.equal((await request(teacher,base+id+'/versions','POST',edit)).requestedVersion,2);
  assert.equal((await detail(id)).publishedVersion,1);assert.equal((await request(teacher,base+id+'/versions/1')).content,done.content);const reviewed=await request(teacher,base+id+'/versions/2');assert.equal(reviewed.source.extractionMode,'ASR_REVIEWED');assert.equal(reviewed.source.hash,hash(raw));
  await indexed(id,2);await command(id,'publish',{version:2});assert.equal((await(await media(access,undefined,404)).json()).status,404);
  const current=await grant(alice,'/api/knowledge/documents/'+id+'/video/access?version=2');assert.equal(hash(Buffer.from(await(await media(current,undefined,200)).arrayBuffer())),hash(raw));
  report.checks.push('Explicit caption correction creates ASR_REVIEWED with the same original and stable retry identity; old publication remains until publish, after which old-version media and citations are rejected.');
  // Source clock is independent of the rendered URL. A manually chosen seek
  // time only tests the player and can hide a missing citation parameter.
  const laterSpeech=corrected.segments.find(segment=>segment.startMs>=60000);assert(laterSpeech,'Expected later speech in the actual stored transcript');
  report.browser=await reviewPage({account:bob,output,expectedId:id,version:2,videoTime:laterSpeech.startMs/1000});
  await request(teacher,base+id+'/visibility','PUT',{expectedRevision:(await detail(id)).revision,visibility:'ADMIN'});assert.equal((await(await media(current,undefined,404)).json()).status,404);
  await request(teacher,base+id+'/visibility','PUT',{expectedRevision:(await detail(id)).revision,visibility:'PUBLIC'});
  const cid=Number(sql(`INSERT INTO contest(uid,author,title,auth,status,start_time,end_time,duration,visible) VALUES(${quote(teacher.uid)},${quote(teacher.username)},'temporary video policy contest',0,0,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 HOUR),7200,1);SELECT LAST_INSERT_ID();INSERT INTO contest_register(cid,uid,status) VALUES(LAST_INSERT_ID(),${quote(alice.uid)},0);`));
  assert.equal((await(await media(current,undefined,403)).json()).status,403);await grant(alice,'/api/knowledge/documents/'+id+'/video/access?version=2',403);
  sql(`DELETE FROM contest_register WHERE cid=${cid};UPDATE contest SET start_time=DATE_SUB(NOW(),INTERVAL 2 HOUR),end_time=DATE_SUB(NOW(),INTERVAL 1 HOUR) WHERE id=${cid};INSERT INTO contest_virtual_run(uid,cid,request_id,title,started_at,ends_at) VALUES(${quote(alice.uid)},${cid},${quote(nonce())},'temporary video virtual fixture',NOW(3),DATE_ADD(NOW(3),INTERVAL 60 SECOND));`);
  assert.equal((await(await media(current,undefined,403)).json()).status,403);sql(`UPDATE contest_virtual_run SET ends_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE uid=${quote(alice.uid)} AND cid=${cid};`);await grant(alice,'/api/knowledge/documents/'+id+'/video/access?version=2');sql(`DELETE FROM contest_virtual_run WHERE cid=${cid};DELETE FROM contest WHERE id=${cid};`);
  sql(`DELETE ur FROM user_role ur JOIN role r ON r.id=ur.role_id WHERE ur.uid=${quote(teacher.uid)} AND r.role='root';`);await grant(teacher,base+id+'/versions/2/video/access',403);sql(`INSERT INTO user_role(uid,role_id) SELECT ${quote(teacher.uid)},id FROM role WHERE role='root';`);
  await command(id,'unpublish');assert.equal((await(await media(current,undefined,404)).json()).status,404);
  report.checks.push('Actual visibility changes, formal/virtual participation, expiry, admin role revocation and withdrawal are enforced server-side for grants and native cookie media requests.');report.validated=true;
})().catch(error=>{report.failure=error.stack;process.exitCode=1;}).finally(async()=>{
  try{
    sql(`INSERT INTO user_role(uid,role_id) SELECT u.uuid,r.id FROM user_info u JOIN role r ON r.role='root' WHERE u.uuid=${quote(teacher.uid)} AND NOT EXISTS(SELECT 1 FROM user_role ur WHERE ur.uid=u.uuid AND ur.role_id=r.id);`);
    for(const id of sql(`SELECT id FROM knowledge_video_import WHERE requested_by IN (${uids}) AND state NOT IN ('SAVED','CANCELLED');`).split(/\s+/).filter(Boolean)){
      for(let i=0;i<10;i++){const current=await task(id);if(['SAVED','CANCELLED'].includes(current.state))break;try{await request(teacher,root+'/video-imports/'+id+'/cancel','POST',{expectedRevision:current.revision});break;}catch(error){if(i===9)throw error;await sleep(200);}}
    }
    for(const id of sql(`SELECT id FROM knowledge_document WHERE created_by IN (${uids});`).split(/\s+/).filter(Boolean)){assert.match(id,/^[a-f0-9]{32}$/);if(!docs.includes(id))docs.push(id);}
    for(const value of sql(`SELECT DISTINCT asset_id FROM knowledge_video_import WHERE requested_by IN (${uids});`).split(/\s+/).filter(Boolean)){assert.match(value,/^[a-f0-9]{32}$/);assets.add(value);}
    for(const id of docs){const current=await detail(id);if(current.state==='ACTIVE')await command(id,'delete');}
    if(docs.length){const ids=docs.map(quote).join(',');for(let i=0;i<90;i++){if(sql(`SELECT COUNT(*) FROM knowledge_job WHERE document_id IN (${ids}) AND operation='DELETE' AND state<>'DONE';`)==='0')break;await sleep(1000);}assert.equal(sql(`SELECT COUNT(*) FROM knowledge_job WHERE document_id IN (${ids}) AND operation='DELETE' AND state<>'DONE';`),'0');sql(`DELETE FROM knowledge_job WHERE document_id IN (${ids});DELETE FROM knowledge_command WHERE document_id IN (${ids});DELETE FROM knowledge_version WHERE document_id IN (${ids});DELETE FROM knowledge_document WHERE id IN (${ids});`);}
    sql(`DELETE FROM knowledge_video_import WHERE requested_by IN (${uids});DELETE FROM knowledge_video_quota WHERE owner IN (${uids});DELETE FROM contest_virtual_run WHERE uid IN (${uids});DELETE FROM contest_register WHERE uid IN (${uids});DELETE FROM contest WHERE uid IN (${uids});DELETE FROM session WHERE uid IN (${uids});DELETE FROM user_info WHERE uuid IN (${uids});`);
    for(const asset of assets)execFileSync('docker',['exec','hoj-master-hoj-backend-1','sh','-c','rm -f -- "$1"','sh','/hoj/knowledge-media/'+asset+'.bin'],{encoding:'utf8',windowsHide:true,timeout:10000});
    for(const name of cleanupFiles)backendFile('rm -f -- "$1"',name);
    assert.equal(counts(),baseline);assert.equal(points(),pointsBefore);report.cleaned=true;
  }catch(error){report.cleanupFailure=error.stack;process.exitCode=1;}
  fs.writeFileSync(path.join(output,'knowledge-video-local-integration.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
});
