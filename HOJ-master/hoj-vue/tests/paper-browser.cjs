// Isolated browser regression: all /api/* requests are fulfilled with fixtures.
// Start with CDUTETC_LOCAL_ASSETS=1, then set UI_REVIEW_URL to the dev server.
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const assert = require('assert').strict;
const base = process.env.UI_REVIEW_URL || 'http://127.0.0.1:8067';
const output = process.env.PAPER_REVIEW_OUTPUT || path.join(os.tmpdir(), 'hoj-paper-review');
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'hoj-paper-browser-'));
fs.mkdirSync(output, { recursive: true });
const report = { checks: [], screenshots: [], errors: [], requests: [] };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const clone = value => JSON.parse(JSON.stringify(value));
let uid = 'paper-alice', role = 'root', nextRun = 100, nextSubmission = 10;
let rejectNextQuestion = false, rejectNextPaper = false;
let rejectLearning = false;
const aiConfigurations = new Map();
let aiBlocked=false, aiDelay=0, aiReject=false;
let translationDelay=0, translationReject=false;
let aiModelRevision=0;
const virtualRuns = new Map();
let virtualDelay=0, virtualLostReply=false;
const mixedVirtualCase=process.env.PAPER_REVIEW_CASE==='mixed-virtual';
let recommendationDelay=0, recommendationReadDelay=0, recommendationReject=false;
let aiContextDelay=0,aiContextRejected=false,aiContextChatRejected=false;
let historyDelay=0,historyReject=false,historyRevision=0;
function problemHistoryFixture(url) {
  const entry=(source,key,rating)=>({source,problemKey:key,displayId:key,title:historyRevision?'新会话题目':'公开题面 <img src=x onerror=alert(1)>',solved:source!=='HOJ',handle:source==='HOJ'?null:'verified-fixture',rating,difficultyMetric:{HOJ:'HOJ_RATING',CODEFORCES:'CODEFORCES_PROBLEM_RATING',NOWCODER:'NOWCODER_SOURCE_RATING'}[source],url:source==='HOJ'?'/problem/CF-4A':source==='CODEFORCES'?'https://codeforces.com/problemset/problem/4/A':'https://ac.nowcoder.com/acm/problem/123',submissions:2,firstAttemptAt:null,lastAttemptAt:1800000000000,firstSolvedAt:source==='HOJ'?null:1800000000000,metadataAvailable:source!=='NOWCODER'});
  const combined={identity:'cf:contest:4:A',title:'公开题面 <img src=x onerror=alert(1)>',solved:true,recordCount:2,groups:[{code:'math',name:'数学'}],lastAttemptAt:1800000000000,entries:[entry('HOJ','cf:contest:4:A',1600),entry('CODEFORCES','cf:contest:4:A',800)]};
  const records=[combined,{identity:'nowcoder:acm:123',title:'没有来源评级',solved:true,recordCount:1,groups:[],lastAttemptAt:1700000000000,entries:[entry('NOWCODER','nowcoder:acm:123',null)]},...Array.from({length:23},(_,index)=>({identity:'hoj:'+index,title:'本地题目 '+index,solved:false,recordCount:1,groups:[],lastAttemptAt:1600000000000,entries:[{...entry('HOJ','hoj:'+index,null),title:'本地题目 '+index}]}))];
  let items=records.map(clone);const source=url.searchParams.get('source')||'ALL',status=url.searchParams.get('status')||'ALL',keyword=(url.searchParams.get('keyword')||'').toLowerCase(),group=url.searchParams.get('groupCode'),page=Number(url.searchParams.get('page')||1);
  items=items.map(row=>({...row,entries:row.entries.filter(e=>source==='ALL'||e.source===source)})).filter(row=>row.entries.length).map(row=>({...row,title:row.entries[0].title,solved:row.entries.some(e=>e.solved)})).filter(row=>(status==='ALL'||(status==='SOLVED')===row.solved)&&(!keyword||(row.title+' '+row.identity).toLowerCase().includes(keyword))&&(!group||row.groups.some(g=>g.code===group))&&(url.searchParams.get('onlyDuplicates')!=='true'||row.recordCount>1));
  return {uid,items:items.slice((page-1)*20,page*20),total:items.length,page,pageSize:20,totals:{attempted:25,solved:2,sourceRecords:26,solvedSourceRecords:2,duplicatesRemoved:0},groups:topicCatalogue.groups,asOf:1800000000000,verifiedAccounts:2,historyComplete:false,stale:true,assistanceTracked:false};
}
const trainingPlans=new Map();let trainingSaveLostReply=false,trainingReadDelay=0,trainingHidden=false,trainingProgress=false;
function trainingView(row){const result=clone(row);delete result.owner;delete result.nonce;delete result.command;result.items=result.items.map((item,index)=>trainingHidden&&index===1?{id:item.id,available:false,status:'UNAVAILABLE'}:{...item,available:true,status:trainingProgress&&index===0?'SOLVED':'NO_RECORD',savedReasons:item.reasons});result.solved=trainingProgress?1:0;return result;}
const externalLinks = new Map();
const credentialSettings = new Map();
let credentialDelay=0;
let externalReject=false, externalDelay=0;
let remoteRefresh=false, remoteDelay=0, remoteRevision=0, remoteSource=null, remoteTranslation=null, remoteImportState='FAILED';
let personalOptionDelay=0, personalStatusDelay=0, personalReject=0, personalNext=700, personalState='QUEUED';
const personalRequests=new Map();
const remoteKey='cf:contest:4:A';
const remoteRows=[{problemKey:remoteKey,displayId:'4A',title:'Watermelon',rating:800,tags:['brute force','math'],sourceUrl:'https://codeforces.com/problemset/problem/4/A',revision:1,localId:null,localAuth:null,importState:'FAILED',importError:'CF 暂时无法读取题面（HTTP 403），可在源站查看或由管理员录入原文'}, {problemKey:'cf:contest:20:A',displayId:'20A',title:'BerOS file system',rating:null,tags:['implementation'],sourceUrl:'https://codeforces.com/problemset/problem/20/A',revision:1,localId:null,localAuth:null}];
function remoteView(publicView=false){const stale=!!remoteTranslation&&remoteTranslation.sourceHash!==remoteSource?.contentHash;return {problemKey:remoteKey,sourceUrl:remoteRows[0].sourceUrl,source:clone(remoteSource),translation:publicView&&stale?null:clone(remoteTranslation),translationStale:stale,available:!!remoteSource,...(!publicView?{refresh:{enabled:remoteRefresh,lastCheckedAt:Date.now(),lastSuccessAt:Date.now(),nextCheckAt:remoteRefresh?Date.now()+86400000:null}}:{})};}
function externalSnapshot(target, owner) {
  const accounts=clone(externalLinks.get(target)||[]).filter(a=>owner||a.verified);
  if(!owner) accounts.forEach(a=>{delete a.proofCode;delete a.proofExpiresAt;delete a.lastError;if(a.contestContext)delete a.contestContext.lastError;if(a.practiceContext)delete a.practiceContext.lastError;});
  return {uid:target,accounts,summary:{localSolved:12,externalSolved:accounts.some(a=>a.verified)?8:0,uniqueSolved:accounts.some(a=>a.verified)?17:12,duplicatesRemoved:accounts.some(a=>a.verified)?3:0,complete:accounts.every(a=>a.historyComplete)},asOf:Date.now()};
}
function externalAccount(handle,platform='CODEFORCES') {
  return {id:1,platform,handle,profileUrl:(platform==='NOWCODER'?'https://ac.nowcoder.com/acm/contest/profile/':platform==='ATCODER'?'https://atcoder.jp/users/':'https://codeforces.com/profile/')+handle,verified:false,historyComplete:false,syncState:'QUEUED',attempted:null,solved:null,individualSolved:null,rating:null,maxRating:null,ratedSolved:0,representativeRating:null,lastSuccessAt:null,stale:true,ratedContests:null,coverage:[],ratingHistory:[],...(platform==='ATCODER'?{coverageAvailable:false,metadata:{problemsSyncedAt:Date.now(),modelsSyncedAt:null,problemsStale:false,modelsStale:true}}:{})};
}
function nowcoderContextFixture() {
  const end=Date.now()-86400000;
  const contest={contestId:99785,title:'牛客公开比赛 <img src=x>',startMs:end-7200000,endMs:end,team:false,participation:'OFFICIAL',rank:12,officialAccepted:4,detailState:'DONE',contestSolved:2,contestSubmissions:5,postContestSolved:1,url:'https://ac.nowcoder.com/acm/contest/99785'};
  return {available:true,listComplete:true,submissionCoverageComplete:false,syncState:'IDLE',stale:false,lastSuccessAt:Date.now(),totalContests:3,coveredContests:1,contests:[contest,{...contest,contestId:99786,title:'受限比赛',url:'https://ac.nowcoder.com/acm/contest/99786',detailState:'RESTRICTED',rank:null,officialAccepted:null,contestSolved:null,contestSubmissions:null,postContestSolved:null},{...contest,contestId:99787,title:'团队比赛',participation:'STAR',url:'https://ac.nowcoder.com/acm/contest/99787',team:true,detailState:'TEAM',contestSolved:null,contestSubmissions:null,postContestSolved:null}]};
}
function nowcoderPracticeFixture() {
  const special={paperId:'201',recordId:'102',title:'专项练习 <img src=x onerror=alert(1)>',kind:'SPECIAL',completed:false,completedAt:null,accuracy:null,score:null};
  return {available:true,paperListsComplete:true,attemptHistoryComplete:false,questionEvidenceAvailable:false,uniqueQuestionCount:null,syncState:'IDLE',stale:false,lastSuccessAt:Date.now(),restricted:false,totalPapers:38,completedPapers:22,specialPapers:15,completedSpecialPapers:12,sourceUrl:'https://www.nowcoder.com/users/12345/tests',records:[
    {...special,completedRecord:{...special,recordId:'101',completed:true,completedAt:1700000000000,accuracy:'0%'}},
    {...special,paperId:'202',recordId:'103',title:'完成的普通试卷',kind:'PAPER',completed:true,completedAt:1700000000000,score:0,completedRecord:null},
    {...special,paperId:'203',recordId:'104',title:'未完成的专项',accuracy:'0%',completedRecord:null},
    {...special,paperId:'204',recordId:'105',title:'面试题记录',kind:'INTERVIEW',completed:true,completedAt:1700000000000,score:99,completedRecord:null},
  ]};
}
const topicDefinitions = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../hoj-springboot/DataBackup/src/main/resources/algorithm-topics.json'), 'utf8'));
const topicGroups = Array.from(new Map(topicDefinitions.map(topic => [topic.groupCode, topic.groupName])));
const topicCatalogue = { version: 'xcpc-common-v1', groups: Object.fromEntries(topicGroups), topics: topicDefinitions.map((topic,index)=>({ ...topic,tagId:1000+index,categoryId:3000+index,classificationId:200+topicGroups.findIndex(group=>group[0]===topic.groupCode) })) };
const categoryFixtures = topicCatalogue.topics.map(topic=>({id:topic.categoryId,name:topic.name,color:'#217e9d'}));
categoryFixtures.push({id:9002,name:'自定义训练方向',color:'#409eff'});
function learningProfile(target) {
  const empty = target === 'empty';
  const axes = topicGroups.map(([code,name],index)=>({code,name,attempted:empty?0:10+index,solved:empty?0:6+index,objectiveAttempted:empty?0:3,objectiveSolved:empty?0:2,ratedSolved:empty?0:index<3?5:0,representativeRating:empty?null:index<3?1200+index*300:null,confidence:empty||index>=3?'INSUFFICIENT':'LIMITED'}));
  return {uid:target,source:'HOJ',version:'local-evidence-v1',scope:'PUBLIC_STANDALONE_PRACTICE',assistanceTracked:false,crossPlatformDeduplicated:false,asOf:Date.now(),axes,overall:{attempted:empty?0:50,solved:empty?0:35,objectiveAttempted:empty?0:10,objectiveSolved:empty?0:6,ratedSolved:empty?0:15,representativeRating:empty?null:1500,unratedProgrammingSolved:empty?0:20,unmappedSolved:0}};
}
function learningEvidence(target) {
  const local = { ...learningProfile(target), coverageOnly: false, coverageAvailable: true };
  const empty = target === 'empty';
  const combined = clone(local); combined.source = 'ALL'; combined.coverageOnly = true;
  const refreshState = target.startsWith('refresh-') ? target.slice(8) : 'IDLE';
  combined.syncState = refreshState; combined.stale = refreshState !== 'IDLE';
  combined.overall = { ...combined.overall, attempted: empty ? 0 : 65, solved: empty ? 0 : 45, ratedSolved: null, representativeRating: null, duplicatesRemoved: empty ? 0 : 3, unmappedSolved: empty ? 0 : 9 };
  const sources = [local];
  for (const source of ['CODEFORCES', 'ATCODER', 'NOWCODER']) {
    const view = clone(local); view.source = source; view.verifiedAccounts = empty ? 0 : 1; view.historyComplete = true; view.stale = false;
    view.syncState = source === 'CODEFORCES' ? refreshState : 'IDLE'; view.stale = view.syncState !== 'IDLE';
    view.coverageAvailable = source === 'CODEFORCES';
    view.overall = { ...view.overall, attempted: empty ? 0 : 12, solved: empty ? 0 : source === 'CODEFORCES' ? 10 : source === 'ATCODER' ? 7 : 4, objectiveAttempted: 0, objectiveSolved: 0, ratedSolved: empty ? 0 : 3, representativeRating: empty ? null : source === 'ATCODER' ? 1350 : 1600 };
    view.axes = view.axes.map(axis => ({ ...axis, objectiveAttempted: 0, objectiveSolved: 0 })); sources.push(view);
  }
  return { uid: target, version: 'verified-sources-v1', combined, sources };
}
function recommendationPlan(source='HOJ') {
  const rows = source==='HOJ' ? [{id:'PROGRAMMING:1',kind:'PROGRAMMING',source,displayId:'P1',title:'二分查找：边界练习',url:'/problem/P1',rating:1200,groups:[{code:'basics',name:'基础算法'}],reasons:['补充公开练习的覆盖']},{id:'OBJECTIVE:2',kind:'OBJECTIVE',source,displayId:'Q2',title:'复杂度概念练习',url:'/objective',questionId:2,rating:null,groups:[{code:'basics',name:'基础算法'}],reasons:['概念训练与编程成绩分别记录']}] : [{id:source+':fixture',kind:'REMOTE',source,displayId:source+'-1',title:'公开来源候选题',url:'https://codeforces.com/problemset/problem/4/A',rating:800,groups:[],reasons:['按当前来源难度选择']}];
  return {filters:{source,groupCode:'',minRating:null,maxRating:null,includeUnrated:true},fingerprint:(source==='HOJ'?'a':'b').repeat(64),basis:{automaticRange:true,minRating:800,maxRating:1600,classificationAvailable:source==='HOJ',stale:false},groups:topicCatalogue.groups,items:rows};
}
const user = () => ({ uid, username: uid, roleList: [role], avatar: '', solvedList: [], scoreList: [] });
const blank = { id: 4, title: '结果填空：路径与计数', source: '蓝桥杯 · 练习样卷', version: 1, questionType: 'FILL_BLANK', stem: '第一个空填写路径长度，第二个空填写计数结果。', blanks: [{ id: 'a', label: '路径长度', score: 3, answers: ['42', '0x2a'], trim: true, caseSensitive: false }, { id: 'b', label: '计数结果', score: 7, answers: ['8'], trim: true, caseSensitive: true }], options: [], correctAnswers: [], explanation: '逐空计算，每个结果独立计分。', published: true, practiceEnabled: true };
const choice = { id: 1, title: '二分查找', questionType: 'SINGLE_CHOICE', stem: '二分查找的最坏时间复杂度？', options: [{ id: 'A', text: 'O(log n)' }, { id: 'B', text: 'O(n)' }], correctAnswers: ['A'], blanks: [], published: true };
const multiple = { ...choice, id: 5, questionType: 'MULTIPLE_CHOICE', correctAnswers: ['A', 'B'] };
const judgment = { ...choice, id: 6, questionType: 'TRUE_FALSE', options: [{ id: 'TRUE', text: '正确' }, { id: 'FALSE', text: '错误' }], correctAnswers: ['TRUE'] };
const program = { id: 1, title: 'A+B', problemId: 'P1000', source: '蓝桥杯 · 编程练习', stem: '<p>读入两个整数，输出它们的和。</p>', input: '<p>一行两个整数。</p>', output: '<p>输出它们的和。</p>', examples: '<input>1 2</input><output>3</output>', timeLimit: 1000, memoryLimit: 256, languages: ['C++', 'Python3'], questionType: 'PROGRAMMING' };
const papers = [
  { id: 1, title: '蓝桥杯混合样卷', description: '填空与编程，在同一份试卷中完成。', version: 1, items: [{ kind: 'OBJECTIVE', referenceId: 4, score: 10 }, { kind: 'PROGRAMMING', referenceId: 1, score: 20 }, { kind: 'OBJECTIVE', referenceId: 1, score: 5 }], questionCount: 3, programMode: 'AFTER_FINISH', scoreRule: 'BEST', published: true, answerPolicy: 'AFTER_FINISH', explanationPolicy: 'AFTER_FINISH' },
  { id: 2, title: '编程即时练习卷', description: '即时判题与选择题。', version: 1, items: [{ kind: 'PROGRAMMING', referenceId: 1, score: 20 }, { kind: 'OBJECTIVE', referenceId: 1, score: 5 }], questionCount: 2, programMode: 'LIVE', scoreRule: 'BEST', published: true, answerPolicy: 'AFTER_FINISH', explanationPolicy: 'AFTER_FINISH' },
];
const runs = new Map(), starts = new Map();
let contestEnd = Date.now() + 3600000;
function contestView(run) {
  const window=run.virtualId?[...virtualRuns.values()].find(v=>v.id===run.virtualId):null,end=window?window.endsAt:contestEnd;
  if (Date.now() >= end) run.state = 'FINISHED';
  const result = view(run);
  result.contestId = 6; result.contestType = 0; result.endTime = end; result.serverNow = Date.now();
  if(window){result.virtual=true;result.virtualRunId=window.id;}
  result.answerPolicy = 'AFTER_END'; result.explanationPolicy = 'AFTER_END';
  if (Date.now() < end) result.items.forEach(q => { if(q.kind==='OBJECTIVE'){q.answerVisible = false;delete q.correctAnswers;delete q.correct;}if(window||q.kind==='OBJECTIVE'){q.explanationVisible = false;delete q.explanation;} });
  return result;
}
function view(run) {
  const finished = run.state === 'FINISHED';
  const rows = run.paper.items.map(item => {
    const key = item.kind + ':' + item.referenceId, saved = run.answers[key] || {};
    if (item.kind === 'PROGRAMMING') {
      const live = run.paper.programMode === 'LIVE'; const results = run.submissions || [];
      const grading = results.some(result => result.status === 5);
      return { ...clone(program), kind: item.kind, referenceId: item.referenceId, key, code: saved.code || '', language: saved.language || null, maxScore: item.score, grading, submissionCount: results.length, answerVisible: live || finished, explanationVisible: finished, ...(finished ? { explanation: '<p>将 a 与 b 相加。</p>' } : {}), ...((live || finished) ? { submissions: clone(results), ...(!grading ? { score: results.some(result => result.status === 0) ? item.score : 0 } : {}) } : {}) };
    }
    const q = [choice, blank, multiple, judgment].find(q => q.id === item.referenceId), selectedAnswers = saved.selectedAnswers || [], { correctAnswers, explanation, blanks, ...publicQuestion } = clone(q);
    const score = q.id === 4 ? (['42', '0x2a'].includes((selectedAnswers[0] || '').trim().toLowerCase()) ? 3 : 0) + (selectedAnswers[1] === '8' ? 7 : 0) : JSON.stringify(selectedAnswers.slice().sort()) === JSON.stringify(q.correctAnswers.slice().sort()) ? item.score : 0;
    return { ...publicQuestion, kind: item.kind, referenceId: item.referenceId, key, maxScore: item.score, blanks: blanks.map(({id, label, score}) => ({id, label, score})), selectedAnswers: clone(selectedAnswers), submitted: finished, answerVisible: finished, explanationVisible: finished, ...(finished ? { score, correct: score === item.score, correctAnswers: q.id === 4 ? ['42 / 0x2a', '8'] : correctAnswers, explanation } : {}) };
  });
  const gradingComplete = !rows.some(q => q.grading);
  return { id: run.id, paperId: run.paper.id, paperVersion: 1, title: run.paper.title, programMode: run.paper.programMode, scoreRule: run.paper.scoreRule, state: run.state, items: rows, maxScore: rows.reduce((s,q) => s + q.maxScore, 0), answeredCount: rows.filter(q => (q.selectedAnswers || []).some(Boolean) || q.code).length, gradingComplete, answerPolicy: 'AFTER_FINISH', explanationPolicy: 'AFTER_FINISH', ...(finished && gradingComplete ? { score: rows.reduce((s,q) => s + q.score, 0) } : {}) };
}
const nowcoderCase=['nowcoder-preflight','nowcoder-personal'].includes(process.env.PAPER_REVIEW_CASE);
const nowcoderSourceCase=process.env.PAPER_REVIEW_CASE==='nowcoder-sources';
let ncSource=null,ncTranslation=null,ncImportState=null,ncRefresh=false,ncLookupDelay=0;
const ncKey='nowcoder:acm:218144',ncRows=[{problemKey:ncKey,displayId:'NC-218144',title:'牛客中文题目',rating:null,sourceRating:null,tags:[],sourceUrl:'https://ac.nowcoder.com/acm/problem/218144'},{problemKey:'nowcoder:acm:324567',displayId:'NC-324567',title:'公开目录题目',rating:null,sourceRating:1900,tags:[],sourceUrl:'https://ac.nowcoder.com/acm/problem/324567'}];
function ncView(publicView=false){const stale=!!ncTranslation&&ncTranslation.sourceHash!==ncSource?.contentHash;return {platform:'NOWCODER',problemKey:ncKey,sourceLanguage:'zh-CN',translationLanguage:'en',sourceUrl:ncRows[0].sourceUrl,source:clone(ncSource),translation:publicView&&stale?null:clone(ncTranslation),translationStale:stale,available:!!ncSource,...(!publicView?{refresh:{enabled:ncRefresh,nextCheckAt:ncRefresh?Date.now()+86400000:null}}:{})};}
let nowcoderSupported=false,atRefresh=false,atCatalogDelay=0,atSource=null,atTranslation=null,atImportState=null;
const atKey='atcoder:task:abc086_a',atRows=[{problemKey:atKey,displayId:'abc086_a',title:'Product',rating:null,estimatedDifficulty:12,tags:[],sourceUrl:'https://atcoder.jp/contests/abc086/tasks/abc086_a'},{problemKey:'atcoder:task:abc319_a',displayId:'abc319_a',title:'Legendary Players',rating:null,estimatedDifficulty:null,tags:[],sourceUrl:'https://atcoder.jp/contests/abc319/tasks/abc319_a'}];
function atView(publicView=false){const stale=!!atTranslation&&atTranslation.sourceHash!==atSource?.contentHash;return {platform:'ATCODER',problemKey:atKey,sourceUrl:atRows[0].sourceUrl,source:clone(atSource),translation:publicView&&stale?null:clone(atTranslation),translationStale:stale,available:!!atSource,...(!publicView?{refresh:{enabled:atRefresh,lastCheckedAt:Date.now(),lastSuccessAt:Date.now(),nextCheckAt:atRefresh?Date.now()+86400000:null}}:{})};}
function fixture(request) {
  const url = new URL(request.url), p = url.pathname, method = request.method;
  const data = request.postData ? JSON.parse(request.postData) : {};
  report.requests.push({ path: p, method, data, query: Object.fromEntries(url.searchParams) }); const ok = data => ({ status: 200, data, msg: 'success' });
  if(process.env.PAPER_REVIEW_CASE==='ai-context'&&p==='/api/get-problem-detail')return ok({problem:{id:1,problemId:'P1000',title:'A+B',description:program.stem,input:program.input,output:program.output,examples:program.examples,hint:'SERVER_SOLUTION_NOT_FOR_AI',isRemote:false,isGroup:false,auth:1,type:0,rating:800,timeLimit:1000,memoryLimit:256,author:'teacher'},problemCount:{total:0,ac:0,wa:0,tle:0,mle:0,re:0,pe:0,ce:0,se:0},languages:['C++'],tags:[],codeTemplate:{}});
  if(process.env.PAPER_REVIEW_CASE==='ai-context'&&p==='/api/objective/runs/11')return uid!=='paper-alice'?{status:404,msg:'答题记录不存在'}:ok({id:11,setId:1,questionId:null,title:'概念训练',mode:'PRACTICE',state:'IN_PROGRESS',answeredCount:0,maxScore:2,answerPolicy:'NEVER',explanationPolicy:'NEVER',questions:[choice,multiple].map(original=>{const row={...clone(original),version:1,source:'算法课',blanks:[],selectedAnswers:[],submitted:false,answerVisible:false,explanationVisible:false};delete row.correctAnswers;return row;})});
  if(p==='/api/ai/problem-context'){
    if(aiBlocked)return {status:403,msg:'你正在参赛，AI 暂时停用'};
    if(aiContextRejected){aiContextRejected=false;return {status:403,msg:'题目已撤回，请重新选择'};}
    const original=data.kind==='PROGRAMMING'||data.questionKey==='PROGRAMMING:1'?program:data.questionKey==='5'?multiple:data.questionKey==='OBJECTIVE:4'?blank:choice;
    return ok({reference:clone(data),fingerprint:'c'.repeat(64),material:{title:original.title,source:'算法课',questionType:original.questionType||'PROGRAMMING',stem:original.stem+'\n<img src=x onerror=alert(1)>',input:original.input,output:original.output,examples:original.examples,options:(original.options||[]).map(row=>({id:row.id,text:row.text})),blanks:(original.blanks||[]).map(row=>({id:row.id,label:row.label}))}});
  }
  if(p==='/api/admin/remote-sources/translation'){
    assert.equal(data.allowModelSource,true);assert.equal(data.modelFingerprint,aiConfigurations.get(uid).fingerprint);
    const nc=data.problemKey.startsWith('nowcoder:'),at=data.problemKey.startsWith('atcoder:'),source=nc?ncSource:at?atSource:remoteSource;
    if(translationReject)return {status:502,msg:'模型译文不完整，当前编辑已保留'};
    if(!source || data.sourceHash!==source.contentHash)return {status:409,msg:'原文已更新，请重新打开后生成'};
    return ok({problemKey:data.problemKey,sourceHash:source.contentHash,sourceLanguage:nc?'zh-CN':'en',translationLanguage:nc?'en':'zh-CN',content:{...clone(source.content),title:nc?'Generated English statement':'生成的中文题面',description:nc?'<p>Given integer <var>n</var>.</p>':'<p>给定整数 <var>n</var>。</p>'},model:aiConfigurations.get(uid).model,draft:true,sentCharacters:100});
  }
  if(p==='/api/admin/remote-sources/nowcoder'){let rows=clone(ncRows);rows[0].importState=ncImportState;rows[0].localId=ncSource?101:null;rows[0].localAuth=ncSource?2:null;if(url.searchParams.get('keyword'))rows=rows.filter(r=>(r.title+r.problemKey+r.displayId).includes(url.searchParams.get('keyword')));return ok({records:rows,total:rows.length,status:{state:'RUNNING',total:50,reportedTotal:14793,scanPage:2,lastFullSuccessAt:null}});}
  if(p==='/api/admin/remote-sources/nowcoder/lookup')return ok({problemKey:ncKey,displayId:'NC-218144',title:ncRows[0].title});
  if(p==='/api/admin/remote-sources/nowcoder/sync')return ok({state:'QUEUED'});
  if(p==='/api/admin/remote-sources/nowcoder/imports'){ncImportState='QUEUED';return ok(ncView());}
  if(p==='/api/admin/remote-sources/nowcoder/statements')return ok(ncView());
  if(p==='/api/admin/remote-sources/nowcoder/refresh'){ncRefresh=data.autoRefresh;return ok(ncView());}
  if(p==='/api/admin/remote-sources/nowcoder/statements/zh'){ncRefresh=false;ncSource={id:++remoteRevision,language:'zh-CN',origin:'MANUAL',contentHash:String(remoteRevision).repeat(64),content:clone(data.content)};ncImportState='DONE';return ok(ncView());}
  if(p==='/api/admin/remote-sources/nowcoder/statements/en'){assert.equal(data.sourceHash,ncSource.contentHash);ncTranslation={id:++remoteRevision,language:'en',origin:'MANUAL',contentHash:String(remoteRevision).repeat(64),sourceHash:data.sourceHash,content:clone(data.content)};return ok(ncView());}
  if(p==='/api/admin/remote-sources/atcoder'){
let rows=clone(atRows);rows[0].importState=atImportState;rows[0].localId=atSource?101:null;rows[0].localAuth=atSource?2:null;if(url.searchParams.get('keyword'))rows=rows.filter(r=>(r.title+r.displayId).includes(url.searchParams.get('keyword')));return ok({records:rows,total:rows.length,status:{state:'IDLE',lastSuccessAt:Date.now(),total:12000}});}
  if(p==='/api/admin/remote-sources/atcoder/refresh'){atRefresh=data.autoRefresh;return ok(atView());}
  if(p==='/api/admin/remote-sources/atcoder/sync')return ok({state:'QUEUED'});
  if(p==='/api/admin/remote-sources/atcoder/imports'){assert.equal(data.problemKey,atKey);atImportState='QUEUED';return ok(atView());}
  if(p==='/api/admin/remote-sources/atcoder/statements')return ok(atView());
  if(p==='/api/admin/remote-sources/atcoder/statements/en'){atRefresh=false;atSource={id:++remoteRevision,origin:'MANUAL',contentHash:String(remoteRevision).repeat(64),createdAt:Date.now(),content:clone(data.content)};atImportState='DONE';return ok(atView());}
  if(p==='/api/admin/remote-sources/atcoder/statements/zh'){assert.equal(data.sourceHash,atSource.contentHash);atTranslation={id:++remoteRevision,origin:'MANUAL',contentHash:String(remoteRevision).repeat(64),sourceHash:data.sourceHash,createdAt:Date.now(),content:clone(data.content)};return ok(atView());}
  if(p==='/api/admin/remote-sources/codeforces'){

    let rows=clone(remoteRows);rows[0].importState=remoteImportState;rows[0].localId=remoteSource?101:null;rows[0].localAuth=remoteSource?2:null;if(remoteImportState!=='FAILED')rows[0].importError=null;
    if(url.searchParams.get('keyword'))rows=rows.filter(row=>(row.title+row.displayId).includes(url.searchParams.get('keyword')));
    return ok({records:rows,total:rows.length,status:{state:'IDLE',lastSuccessAt:Date.now(),nextRunAt:Date.now()+3600000,total:10500,added:12,changed:3,removed:0}});
  }
  if(p==='/api/admin/remote-sources/codeforces/refresh'){remoteRefresh=data.autoRefresh;return ok(remoteView());}
  if(p==='/api/admin/remote-sources/codeforces/sync')return ok({state:'QUEUED'});
  if(p==='/api/admin/remote-sources/codeforces/imports'){remoteImportState='QUEUED';return ok(remoteView());}
  if(p==='/api/admin/remote-sources/codeforces/statements')return ok(remoteView());
  if(p==='/api/admin/remote-sources/codeforces/statements/en'){remoteRefresh=false;remoteSource={id:++remoteRevision,origin:'MANUAL',contentHash:String(remoteRevision).repeat(64),createdAt:Date.now(),content:clone(data.content)};remoteImportState='DONE';return ok(remoteView());}
  if(p==='/api/admin/remote-sources/codeforces/statements/zh'){assert.equal(data.sourceHash,remoteSource.contentHash);remoteTranslation={id:++remoteRevision,origin:'MANUAL',contentHash:String(remoteRevision).repeat(64),sourceHash:data.sourceHash,createdAt:Date.now(),content:clone(data.content)};return ok(remoteView());}
  if(/^\/api\/problems\/\d+\/statements$/.test(p))return ok(nowcoderSourceCase?ncView(true):process.env.PAPER_REVIEW_CASE==='atcoder-sources'?atView(true):process.env.PAPER_REVIEW_CASE==='remote-sources'?remoteView(true):{available:false});
  if(['nowcoder-sources','atcoder-sources','remote-sources','personal-remote','nowcoder-preflight','nowcoder-personal'].includes(process.env.PAPER_REVIEW_CASE)&&p==='/api/get-problem-detail')return ok({problem:{id:101,problemId:nowcoderCase||nowcoderSourceCase?'NC-218144':process.env.PAPER_REVIEW_CASE==='atcoder-sources'?'AC-abc086_a':'CF-4A',title:process.env.PAPER_REVIEW_CASE==='remote-sources'?'题库手工编辑的标题':'Watermelon',description:process.env.PAPER_REVIEW_CASE==='remote-sources'?'<p>题库保留的说明</p>':'<p>将偶数拆成两个正偶数。</p>',input:'',output:'',hint:'',examples:'<input>8</input><output>YES</output>',isRemote:true,isGroup:false,auth:1,type:0,rating:800,timeLimit:1000,memoryLimit:256,author:'teacher'},problemCount:{total:0,ac:0,wa:0,tle:0,mle:0,re:0,pe:0,ce:0,se:0},languages:['C++'],tags:[],codeTemplate:{}});
  if(nowcoderCase&&p==='/api/personal-remote/accounts')return ok({platform:'NOWCODER',submissionSupported:nowcoderSupported,accounts:[{id:1,platform:'NOWCODER',handle:'12345',ownershipVerified:true,authorized:true}]});
  if(nowcoderCase&&p==='/api/personal-remote/options')return ok({offerId:'fixture-nc-offer',submissionSupported:nowcoderSupported,languages:{'2':'C++','11':'Python 3'},expiresIn:300});
  if(p==='/api/personal-remote/accounts')return ok({platform:'CODEFORCES',accounts:[1,2].map(id=>({id,platform:'CODEFORCES',handle:'fixture_'+id,ownershipVerified:true,authorized:true}))});
  if(p==='/api/personal-remote/options')return ok({offerId:'fixture-offer-'+data.accountId,languages:data.accountId===1?{'54':'GNU G++17','89':'GNU G++20'}:{'31':'Python 3'},expiresIn:300});
  if(p==='/api/personal-remote/submissions'&&method==='POST'){
    if(personalReject)return {status:personalReject,msg:personalReject===400?'代码校验失败':'暂时无法确认提交',data:null};
    if(!personalRequests.has(data.requestId))personalRequests.set(data.requestId,personalNext++);
    return ok({submitId:personalRequests.get(data.requestId),task:{state:'QUEUED',submit_attempts:0}});
  }
  if(p.startsWith('/api/personal-remote/requests/')){const id=personalRequests.get(p.split('/').pop());return id?ok({submitId:id,task:{state:personalState,submit_attempts:0},verdict:personalState==='COMPLETED'?0:5}):{status:404,msg:'原请求尚未建立',data:null};}
  if(/^\/api\/personal-remote\/submissions\/\d+/.test(p)){
    if(p.endsWith('/cancel'))personalState='CANCELLED';if(p.endsWith('/reconcile'))personalState='RECONCILING';
    return ok({submitId:Number(p.split('/')[4]),task:{state:personalState,submit_attempts:0},verdict:personalState==='COMPLETED'?0:5});
  }
  if (p === '/api/ai/settings') {
    if(method==='PUT')aiConfigurations.set(uid,{baseUrl:data.baseUrl,model:data.model,configured:true,hasKey:true});
    if(method==='PUT')aiConfigurations.get(uid).fingerprint=(++aiModelRevision).toString(16).padStart(64,'0');
    if(method==='DELETE')aiConfigurations.delete(uid);
    return ok({...aiConfigurations.get(uid)||{baseUrl:'',model:'',configured:false,hasKey:false},contestBlocked:aiBlocked||(virtualRuns.get(uid)?.endsAt>Date.now()),protocol:'OPENAI_COMPATIBLE_CHAT'});
  }
  if (p === '/api/ai/chat') { if(aiBlocked)return {status:403,msg:'当前有比赛进行，AI 助教暂时停用'};if(aiContextChatRejected){aiContextChatRejected=false;return {status:409,msg:'题面已变化，请刷新题面预览后重新提问'};}if(aiReject){aiReject=false;return {status:502,msg:'AI 服务暂时不可用'};}return ok({content:'先确定循环的边界，再考虑每一步保持什么条件。<img src=x onerror=alert(1)>',model:'fixture-model',modelFingerprint:data.modelFingerprint,problemContextIncluded:!!data.problemContext,problemFingerprint:data.problemContext?.fingerprint,profileIncluded:data.includeProfile}); }
  if(p.startsWith('/api/learning/training-plans')){
    if(aiBlocked)return {status:403,msg:'你正在参赛，训练建议暂时停用'};
    const id=p.split('/')[4];
    if(!id&&method==='GET'){const rows=[...trainingPlans.values()].filter(row=>row.owner===uid&&row.state===(url.searchParams.get('state')||'ACTIVE'));return ok({items:rows.map(trainingView),total:rows.length,page:1});}
    if(!id&&method==='POST'){
      let row=[...trainingPlans.values()].find(row=>row.owner===uid&&row.nonce===data.requestId);const command=JSON.stringify(data);
      if(row&&row.command!==command)return {status:409,msg:'保存标识已使用'};
      if(!row){const plan=recommendationPlan(data.selection.filters.source);row={id:String(trainingPlans.size+1).padStart(32,'a'),owner:uid,nonce:data.requestId,command,name:data.name,state:'ACTIVE',revision:1,createdAt:Date.now(),filters:plan.filters,items:plan.items};trainingPlans.set(row.id,row);}
      if(trainingSaveLostReply){trainingSaveLostReply=false;return {status:503,msg:'保存回执丢失，请重试'};}return ok(trainingView(row));
    }
    const row=trainingPlans.get(id);if(!row||row.owner!==uid||row.state==='DELETED')return {status:404,msg:'清单不存在'};
    if(method==='PUT'){if(row.revision!==data.expectedRevision)return {status:409,msg:'训练清单已更新，请刷新后重试'};row.state=data.state;if(data.name)row.name=data.name;row.revision++;}return ok(trainingView(row));
  }
  if(p==='/api/ai/recommendations'){if(aiBlocked)return {status:403,msg:'你正在参赛，所有 AI 入口暂时停用'};return ok(recommendationPlan(url.searchParams.get('source')||'HOJ'));}
  if(p==='/api/ai/recommendations/explain'){if(aiBlocked)return {status:403,msg:'比赛中暂时停用'};if(!aiConfigurations.has(uid))return {status:400,msg:'请先在 AI 助教中配置模型服务'};if(recommendationReject){recommendationReject=false;return {status:409,msg:'题目已变化，请刷新推荐'};}const plan=recommendationPlan(data.filters.source);return ok({fingerprint:plan.fingerprint,summary:'先练概念，再完成实现。<img src=x onerror=alert(1)>',items:plan.items.slice().reverse().map(row=>({...row,aiReason:'依据候选题练习，模型不新增题号。'})),model:'fixture-model'});}
  if(p==='/api/objective/runs'&&method==='POST'&&process.env.PAPER_REVIEW_CASE==='recommendations')return ok({id:901});
  if (p === '/api/learning/topics') return ok(clone(topicCatalogue));
  if (/^\/api\/learning\/users\/[^/]+\/external$/.test(p)) return ok(externalSnapshot(p.split('/')[4],false));
  if (p === '/api/learning/accounts') {
    if(method==='POST') {
      if(externalReject){externalReject=false;return {status:409,msg:'该用户名已经关联'};}
      const account=externalAccount(data.handle,data.platform),rows=externalLinks.get(uid)||[];
      if(account.platform==='NOWCODER'){account.contestContext={available:false,syncState:'QUEUED',contests:[]};account.practiceContext={available:false,syncState:'QUEUED',records:[]};}
      if(['atcoder','credentials'].includes(process.env.PAPER_REVIEW_CASE)){account.id=rows.length+1;externalLinks.set(uid,[...rows,account]);}else externalLinks.set(uid,[account]);
    }
    return ok(externalSnapshot(uid,true));
  }
  if(p.endsWith('/submission-credentials')) {
    const id=Number(p.split('/')[4]),key=uid+':'+id,isNowCoder=nowcoderCase||(externalLinks.get(uid)||[]).some(a=>a.id===id&&a.platform==='NOWCODER'),old=credentialSettings.get(key)||{accountId:id,configured:false,version:0,submissionAvailable:false,...(isNowCoder?{submissionSupported:true}:{})};
    if(method==='PUT'){assert(data.cookieHeader.startsWith(isNowCoder?'t=fake-':'REVEL_SESSION=fake-'));assert.equal(data.authorized,true);assert.equal(data.password,undefined);if(data.cookieHeader.includes('wrong-account'))return {status:403,msg:'会话登录账号与绑定账号不一致'};if(data.version!==old.version)return {status:409,msg:'凭据已被更新，请重新打开'};credentialSettings.set(key,{...old,configured:true,submissionAvailable:true,credentialType:'BROWSER_SESSION',reauthorizationRequired:false,version:old.version+1,updatedAt:Date.now(),sessionCheckedAt:Date.now()});}
    if(method==='DELETE'){if(Number(url.searchParams.get('version'))!==old.version)return {status:409,msg:'凭据已被更新'};credentialSettings.set(key,{...old,configured:false,credentialType:null,reauthorizationRequired:false,sessionCheckedAt:null,version:old.version+1});}
    return ok(clone(credentialSettings.get(key)||old));
  }
  if(p.startsWith('/api/learning/accounts/')) {
    const rows=externalLinks.get(uid)||[], account=rows.find(a=>a.id===Number(p.split('/')[4]));
    if(method==='DELETE')externalLinks.set(uid,rows.filter(a=>a!==account));
    else if(p.endsWith('/proof')){account.proofCode='HOJ-fixture-proof-for-owner-only';account.proofExpiresAt=Date.now()+1800000;}
    else if(p.endsWith('/verify')) {
      if(account.platform==='NOWCODER'){account.contestContext=nowcoderContextFixture();account.practiceContext=nowcoderPracticeFixture();}
      Object.assign(account,{verified:true,historyComplete:true,syncState:'IDLE',attempted:13,solved:8,individualSolved:7,rating:1780,maxRating:1900,ratedSolved:6,representativeRating:1600,lastSuccessAt:Date.now(),stale:false,ratedContests:4,coverage:topicGroups.map(([code,name],i)=>({code,name,solved:i<3?5-i:0})),ratingHistory:[{contestId:2000,title:'Codeforces Round 2000 (Div. 2)',contestRank:120,oldRating:1720,newRating:1780}]});delete account.proofCode;delete account.proofExpiresAt;
      if(account.platform==='NOWCODER')Object.assign(account,{displayName:'牛客测试选手',individualSolved:null,individualStatusVerified:false,coverageAvailable:false,coverage:[],ratedSolved:0,representativeRating:null,representativeScope:'ACCOUNT_ACCEPTED',rating:947,maxRating:1049,statisticsScope:'PUBLIC_PROFILE_CODING',ratingHistory:[{contestId:99785,contestUrl:'https://ac.nowcoder.com/acm/contest/99785',title:'牛客周赛测试',contestRank:1128,oldRating:907,newRating:947}]});
      if(account.platform==='ATCODER')Object.assign(account,{individualSolved:null,coverage:[],representativeScope:'ACCOUNT_ACCEPTED',rating:2100,maxRating:2250,representativeRating:1700,ratingHistory:[{contestId:'abc350',contestUrl:'https://atcoder.jp/contests/abc350',title:'AtCoder Beginner Contest 350',contestRank:200,oldRating:2050,newRating:2100}]});
    }
    return ok(externalSnapshot(uid,true));
  }
  if (p.startsWith('/api/learning/users/')) { if (rejectLearning) { rejectLearning=false; return {status:400,msg:'画像暂时不可用'}; } return ok(p.endsWith('/evidence') ? learningEvidence(p.split('/')[4]) : learningProfile(p.split('/')[4])); }
  if(p==='/api/learning/problem-history'){if(historyReject){historyReject=false;return {status:503,msg:'来源记录暂不可用'};}return ok(problemHistoryFixture(url));}
  if (['learning','recommendations','problem-history','rating'].includes(process.env.PAPER_REVIEW_CASE)) {
    if (p === '/api/get-user-home-info') return ok({...user(),uid:url.searchParams.get('uid')||uid,username:url.searchParams.get('uid')||uid,total:50,rating:null,contestRating:1600});
    if (p === '/api/get-user-calendar-heatmap') return ok({dataList:[],endDate:'2026-10-17'});
    if (p === '/api/get-problem-tags-and-classification') return ok([...topicGroups.map(([code,name],index)=>({classification:{id:200+index,name,oj:'ME'},tagList:topicCatalogue.topics.filter(topic=>topic.groupCode===code).map(topic=>({id:topic.tagId,name:topic.name,oj:'ME',tcid:topic.classificationId}))})),{classification:null,tagList:[{id:9001,name:'自定义新方向',oj:'ME',tcid:null}]}]);
    if (p === '/api/admin/tag/classification') return ok(topicGroups.map(([code,name],index)=>({id:200+index,name,oj:'ME'})));
    if (p === '/api/admin/tag') return ok({...data,id:data.id||9003});
    if (p === '/api/get-training-category') return ok(clone(categoryFixtures));
    if (p === '/api/admin/training/category') { const saved={...data,id:data.id||9004};const index=categoryFixtures.findIndex(row=>row.id===saved.id);if(index<0)categoryFixtures.push(saved);else categoryFixtures[index]=saved;return ok(saved); }
  }
  if (p === '/api/get-problem-tags-and-classification') return ok([]);
  if (p === '/api/get-user-problem-status') return ok(Object.fromEntries(data.pidList.map(pid => [pid, { status: -10 }])));
  if (p === '/api/get-problem-list') {
    const rows = [{ pid: 901, problemId: 'R901', title: '评级练习', rating: 1200 }, { pid: 902, problemId: 'R902', title: '进阶练习', rating: 1800 }, { pid: 903, problemId: 'R903', title: '未评级练习', rating: null }].map(row => ({ ...row, difficulty: 0, type: 0, tags: [], total: 0, ac: 0 }));
    const records = rows.filter(row => (!url.searchParams.has('ratingMin') || row.rating !== null && row.rating >= Number(url.searchParams.get('ratingMin'))) && (!url.searchParams.has('ratingMax') || row.rating !== null && row.rating <= Number(url.searchParams.get('ratingMax'))));
    return ok({ records, total: records.length });
  }
  if (p === '/api/rating/rank') {
    const type = url.searchParams.get('type') || 'contest', keyword = url.searchParams.get('searchUser') || '';
    if(type !== 'contest')return {status:400,msg:'仅支持比赛 Rating'};
    const rows = Array.from({ length: 31 }, (_, index) => ({ uid: 'rating-' + index, username: 'student' + index, nickname: '', avatar: '', rank: index + 1, rating: 1600 - index, peak: 1700, delta: index ? -12 : 25, ratedCount: 3 })).filter(row => row.username.includes(keyword));
    const page = Number(url.searchParams.get('currentPage') || 1), limit = Number(url.searchParams.get('limit') || 30);
    return ok({ records: rows.slice((page - 1) * limit, page * limit), total: rows.length, updatedAt: Date.now() });
  }
  if (p.startsWith('/api/rating/users/')) return ok({ contestRating: 1600, history: [{ type: 'contest', title: '正式计分比赛', occurredAt: Date.now(), oldRating: 1375, rating: 1400, delta: 25 }] });
  if (p === '/api/contests/6/virtual-run') {
    let run=virtualRuns.get(uid);
    if(method==='POST'){
      if(!run||run.endsAt<=Date.now()){
        run={id:nextRun++,contestId:6,title:'算法训练赛',startedAt:Date.now(),endsAt:Date.now()+3600000};virtualRuns.set(uid,run);
        if(mixedVirtualCase){const paper=clone(papers[1]);paper.programMode='LIVE';paper.items.push(...[4,5,6].map(referenceId=>({kind:'OBJECTIVE',referenceId,score:referenceId===4?10:5})));const sheet={id:nextRun++,contestId:6,virtualId:run.id,uid,paper,answers:{},submissions:[],state:'IN_PROGRESS',polls:0};runs.set(sheet.id,sheet);run.paperRunId=sheet.id;run.kind='MIXED';}
      }
      if(virtualLostReply){virtualLostReply=false;return {status:503,msg:'响应暂时丢失，请重试原请求'};}
    }
    return ok({run:run?{...run,serverNow:Date.now(),state:run.endsAt>Date.now()?'RUNNING':'ENDED'}:null});
  }
  if (p === '/api/get-contest-info') return ok(['virtual','mixed-virtual'].includes(process.env.PAPER_REVIEW_CASE)?{id:6,mixedPaperId:mixedVirtualCase?2:null,title:'算法训练赛',author:'teacher',type:0,auth:0,status:1,startTime:new Date(Date.now()-3660000).toISOString(),endTime:new Date(Date.now()-60000).toISOString(),now:new Date().toISOString(),sealRank:false,duration:3600,rankShowName:'username'}:{ id: 6, mixedPaperId: 2, title: '蓝桥杯混合赛', author: 'teacher', type: 0, auth: 0, status: 0, startTime: new Date(Date.now() - 60000).toISOString(), endTime: new Date(contestEnd).toISOString(), now: new Date().toISOString(), sealRank: false, duration: 3660, rankShowName: 'username' });
  if (p === '/api/get-contest-rank') return ok({total:0,records:[]});
  if (p === '/api/contests/6/mixed/run') {
    let run = [...runs.values()].find(r => r.contestId === 6 && !r.virtualId && r.uid === uid);
    if (!run) {
      const paper = clone(papers[1]);
      paper.items.push(...[4, 5, 6].map(referenceId => ({ kind: 'OBJECTIVE', referenceId, score: referenceId === 4 ? 10 : 5 })));
      run = { id: ++nextRun, contestId: 6, uid, paper, answers: {}, submissions: [], state: 'IN_PROGRESS', polls: 0 }; runs.set(run.id, run);
    }
    return ok(contestView(run));
  }
  if (p === '/api/contests/6/mixed/rank') return ok({ type: 0, sealed: false, records: [{ rank: 1, name: '参赛者 A', objectiveScore: 10, ac: 1, penalty: 1260, finished: true }, { rank: 2, name: '参赛者 B', objectiveScore: 0, ac: 2, penalty: 300, finished: true }] });
  if (p.includes('website-config')) return ok({ name: 'HOJ', shortName: 'HOJ', openPublicDiscussion: true, openGroupDiscussion: true, openContestComment: true });
  if (p === '/api/get-user-auth-info') return ok({ roles: [role] });
  if (p === '/api/get-user-home-info') return ok(['external','atcoder','credentials'].includes(process.env.PAPER_REVIEW_CASE) ? {...user(),uid:url.searchParams.get('uid')||uid,username:url.searchParams.get('uid')||uid} : user());
  if (p === '/api/get-user-calendar-heatmap') return ok({dataList:[],endDate:'2026-10-17'});
  if (p === '/api/msg/unread') return ok({ comment: 0, reply: 0, like: 0, sys: 0, mine: 0 });
  if (p === '/api/get-all-languages') return ok([{ name: 'C++', contentType: 'text/x-c++src' }, { name: 'Python3', contentType: 'text/x-python' }]);
  if (p === '/api/papers') return ok({ records: clone(papers), total: papers.length });
  if (p === '/api/papers/runs' && method === 'POST') {
    let run = starts.get(uid + data.requestId); if (!run) { run = { id: ++nextRun, uid, paper: clone(papers.find(paper => paper.id === data.paperId)), answers: {}, submissions: [], state: 'IN_PROGRESS', polls: 0 }; runs.set(run.id, run); starts.set(uid + data.requestId, run); } return ok(view(run));
  }
  if (p === '/api/papers/runs') { const own = [...runs.values()].filter(r => r.uid === uid).map(r => ({ id: r.id, title: r.paper.title, state: r.state, createdAt: Date.now() })); return ok({ records: own, total: own.length }); }
  const match = p.match(/^\/api\/papers\/runs\/(\d+)(\/answers|\/program-submissions|\/finish)?$/);
  if (match) {
    const run = runs.get(Number(match[1])); if (!run || run.uid !== uid) return { status: 404, data: null, msg: '答卷不存在' };
    if (match[2] === '/answers') { assert.equal(run.state, 'IN_PROGRESS'); run.answers[data.key] = clone(data); }
    if (match[2] === '/program-submissions') { assert.equal(run.paper.programMode, 'LIVE'); run.answers[data.key] = clone(data); run.submissions.push({ id: ++nextSubmission, status: 5 }); run.polls = 0; }
    if (match[2] === '/finish') { if (run.state !== 'FINISHED' && run.paper.programMode === 'AFTER_FINISH' && Object.values(run.answers).some(a => a.code)) run.submissions.push({ id: ++nextSubmission, status: 5 }); run.state = 'FINISHED'; }
    if (!match[2] && ++run.polls >= 2) run.submissions.forEach(result => { result.status = 0; result.score = 20; });
    return ok(run.contestId ? contestView(run) : view(run));
  }
  if (p === '/api/admin/papers/program-questions') return ok([clone(program)]);
  if (p === '/api/admin/papers/collections') return ok({ records: [...clone(papers).map(paper => ({ ...paper, source: 'PAPER', mode: 'PAPER' })), { id: 3, title: '旧版逐题训练集', source: 'OBJECTIVE', mode: 'PRACTICE', questionCount: 1, published: false }], total: papers.length + 1 });
  if (p === '/api/languages') return ok([{ id: 1, name: 'C++', contentType: 'text/x-c++src', oj: 'ME', isSpj: false }]);
  if (p === '/api/admin/problem' && method === 'POST') return ok({ ...data.problem, id: 20 });
  if (p === '/api/admin/problem/get-problem-list') return ok({ records: [], total: 0 });
  if (p === '/api/admin/papers' && method === 'GET') return ok({ records: clone(papers), total: papers.length });
  if (p === '/api/admin/papers') { if (rejectNextPaper) { rejectNextPaper = false; return { status: 400, msg: '试卷保存失败，请重试' }; } assert(data.items.some(item => item.kind === 'PROGRAMMING')); assert(data.items.some(item => item.kind === 'OBJECTIVE')); const paper = { ...data, id: data.id || 9, version: (data.version || 0) + 1, questionCount: data.items.length }; papers.push(paper); return ok(paper); }
  if (p.match(/^\/api\/admin\/papers\/\d+$/)) return ok(clone(papers.find(paper => paper.id === Number(p.split('/').pop()))));
  if (p === '/api/admin/objective/questions' && method === 'GET') return ok({ records: [clone(blank), clone(choice)], total: 2 });
  if (p === '/api/admin/objective/questions') { if (rejectNextQuestion) { rejectNextQuestion = false; return { status: 400, msg: '题目保存失败，请重试' }; } return ok({ ...data, id: 10, version: 1 }); }
  if (p.match(/^\/api\/admin\/objective\/questions\/\d+$/)) return ok(clone(Number(p.split('/').pop()) === 4 ? blank : choice));
  if (p === '/api/admin/objective/sets/3') return ok({ id: 3, title: '旧版逐题训练集', mode: 'PRACTICE', questionIds: [1], version: 1, published: false, answerPolicy: 'AFTER_ANSWER', explanationPolicy: 'NEVER' });
  if (p === '/api/admin/objective/sets' && ['POST', 'PUT'].includes(method)) return ok({ ...data, id: data.id || 11, version: 1 });
  return ok([]);
}
let ws, stderr = '';
const browser = spawn(process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', ['--headless=new', '--disable-gpu', '--no-first-run', '--disable-extensions', '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
browser.stderr.on('data', data => { stderr = (stderr + data).slice(-2000); });
async function until(check, label, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await check()) return; await sleep(150); }
  throw Error('Timeout: ' + label);
}
(async () => {
  const portFile = path.join(profile, 'DevToolsActivePort');
  await until(() => { if (browser.exitCode !== null) throw Error(stderr); return fs.existsSync(portFile); }, 'Chrome startup', 15000);
  const port = fs.readFileSync(portFile, 'utf8').split('\n')[0];
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
  ws = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let seq = 0;
  const pending = new Map();
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++seq;
    const timer = setTimeout(() => { pending.delete(id); reject(Error('CDP timeout: ' + method)); }, 30000);
    pending.set(id, { resolve, reject, timer }); ws.send(JSON.stringify({ id, method, params }));
  });
  ws.onmessage = async event => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const call = pending.get(message.id); clearTimeout(call.timer); pending.delete(message.id);
      if (message.error) call.reject(Error(message.error.message)); else call.resolve(message.result);
    } else if (message.method === 'Runtime.exceptionThrown') report.errors.push(message.params.exceptionDetails.exception?.description || message.params.exceptionDetails.text);
    else if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') { report.errors.push(message.params.args.map(a => a.description || a.value).join(' ')); }
    else if (message.method === 'Fetch.requestPaused') {
      try {
        const body = fixture(message.params.request);
        if (message.params.request.url.includes('/api/learning/users/stale/')) await sleep(1200);
        if (message.params.request.url.endsWith('/api/ai/chat') && aiDelay) await sleep(aiDelay);
        if (message.params.request.url.endsWith('/api/ai/problem-context') && aiContextDelay) await sleep(aiContextDelay);
        if (new URL(message.params.request.url).pathname==='/api/learning/problem-history' && historyDelay) await sleep(historyDelay);
        if (message.params.request.url.endsWith('/api/admin/remote-sources/translation') && translationDelay) await sleep(translationDelay);
        if (message.params.request.url.endsWith('/virtual-run') && virtualDelay) await sleep(virtualDelay);
        if (message.params.request.url.includes('/api/ai/recommendations/explain') && recommendationDelay) await sleep(recommendationDelay);
        if (new URL(message.params.request.url).pathname==='/api/ai/recommendations' && recommendationReadDelay) await sleep(recommendationReadDelay);
        if (new URL(message.params.request.url).pathname==='/api/learning/training-plans' && message.params.request.method==='GET' && trainingReadDelay) await sleep(trainingReadDelay);
        if (message.params.request.url.endsWith('/api/learning/accounts') && externalDelay) await sleep(externalDelay);
        if (message.params.request.url.includes('/submission-credentials') && credentialDelay) await sleep(credentialDelay);
        if (message.params.request.url.includes('/api/admin/remote-sources/codeforces/statements?') && remoteDelay) await sleep(remoteDelay);
        if (new URL(message.params.request.url).pathname==='/api/admin/remote-sources/nowcoder/lookup' && ncLookupDelay) await sleep(ncLookupDelay);
        if (new URL(message.params.request.url).pathname==='/api/admin/remote-sources/atcoder' && atCatalogDelay) await sleep(atCatalogDelay);
        if (message.params.request.url.endsWith('/api/personal-remote/options') && personalOptionDelay) await sleep(personalOptionDelay);
        if (/\/api\/personal-remote\/submissions\/\d+$/.test(message.params.request.url) && personalStatusDelay) await sleep(personalStatusDelay);
        await cdp('Fetch.fulfillRequest', { requestId: message.params.requestId, responseCode: 200, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body: Buffer.from(JSON.stringify(body)).toString('base64') });
      } catch (error) { report.errors.push(error.message); await cdp('Fetch.failRequest', { requestId: message.params.requestId, errorReason: 'Failed' }); }
    }
  };
  const ev = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result.value;
  };
  const find = name => "(function find(vm){if(!vm)return;if(vm.$options.name===" + JSON.stringify(name) + ")return vm;for(const child of vm.$children||[]){const result=find(child);if(result)return result;}})(document.getElementById('app')?.__vue__)";
  const route = async location => { await ev("document.getElementById('app').__vue__.$router.push(" + JSON.stringify(location) + ").then(()=>true,()=>false)"); await sleep(400); };
  const shot = async (name, viewport = false) => {
    // Let user feedback expire and dialog transitions settle before layout QA.
    await sleep(4600);
    if (name === 'objective-admin-policies') await ev("document.querySelectorAll('.objective-editor-dialog .el-dialog__body').forEach(body=>{if(body.getBoundingClientRect().height)body.scrollTop=body.scrollHeight});true");
    const result = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: !viewport });
    const file = path.join(output, name + '.png'); fs.writeFileSync(file, Buffer.from(result.data, 'base64')); report.screenshots.push(file);
  };
  const checkObjectivePolling = async () => {
    const cases = [
      { key: 'OBJECTIVE:1', selected: ['A'], selector: '.el-radio' },
      { key: 'OBJECTIVE:5', selected: ['A', 'B'], selector: '.el-checkbox' },
      { key: 'OBJECTIVE:6', selected: ['TRUE'], selector: '.el-radio' },
      { key: 'OBJECTIVE:4', selected: ['42', '8'] },
    ];
    for (const test of cases) {
      await ev(find('PaperRun') + '.select(' + JSON.stringify(test.key) + ');true');
      await until(() => ev(find('PaperRun') + '.selectedKey===' + JSON.stringify(test.key)), 'select ' + test.key);
      if (test.selector) {
        for (let index = 0; index < test.selected.length; index++) await ev("document.querySelectorAll('.objective-answer " + test.selector + "')[" + index + "].click();true");
      }
      else await ev("document.querySelectorAll('.blank-answer input').forEach((n,i)=>{n.value=" + JSON.stringify(test.selected) + "[i];n.dispatchEvent(new Event('input',{bubbles:true}))});true");
      assert.deepEqual(await ev(find('ObjectiveAnswer') + '.selected'), test.selected);
      const started = Date.now();
      await until(() => ev(find('PaperRun') + '.run.serverNow>' + (started + 3500)), 'two result polls for ' + test.key, 12000);
      assert.deepEqual(await ev(find('ObjectiveAnswer') + '.selected'), test.selected, test.key + ' must keep unsaved answers across clock ticks and result polls');
      assert.equal(await ev(find('ObjectiveAnswer') + '.dirty'), true);
      if (test.selector) assert.equal(await ev("document.querySelectorAll('.objective-answer " + test.selector + ".is-checked').length"), test.selected.length);
      await ev(find('PaperRun') + ".select('PROGRAMMING:1');true");
      await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'unsaved answer guard');
      await ev("document.querySelector('.el-message-box__btns .el-button:not(.el-button--primary)').click();true");
      assert.equal(await ev(find('PaperRun') + '.selectedKey'), test.key);
      await ev("document.querySelector('.answer-footer button').click();true");
      await until(() => ev('!' + find('PaperRun') + '.saving&&!'+find('ObjectiveAnswer')+'.dirty'), 'save ' + test.key);
      const runId = await ev(find('PaperRun') + '.run.id');
      assert.deepEqual(runs.get(runId).answers[test.key].selectedAnswers, test.selected);
    }
    // An intentional refresh still discards edits after the user confirms it.
    await ev("const n=document.querySelector('.blank-answer input');n.value='99';n.dispatchEvent(new Event('input',{bubbles:true}));"+find('PaperRun')+'.refresh();true');
    await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'refresh discard guard');
    await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");
    await until(() => ev('!' + find('PaperRun') + '.loading&&!'+find('ObjectiveAnswer')+'.dirty'), 'restore saved answers on refresh');
    assert.deepEqual(await ev(find('ObjectiveAnswer') + '.selected'), ['42', '8']);
    await ev(find('PaperRun') + ".select('OBJECTIVE:1');true");
    await until(() => ev(find('ObjectiveAnswer') + '.single===\'A\''), 'restore saved choice after switching');
    report.checks.push('Unsaved single/multiple/true-false/fill answers survive countdown ticks and two result polls; guards, save, switching and confirmed refresh retain their expected behavior.');
  };
  await cdp('Runtime.enable'); await cdp('Page.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.setItem('token','objective-fixture-token');localStorage.setItem('Web_Language','\"zh-CN\"');localStorage.setItem('userInfo'," + JSON.stringify(JSON.stringify(user())) + ");" });
  await cdp('Page.navigate', { url: base + '/papers' });
  await until(() => ev("document.querySelectorAll('.paper-card').length===2"), 'paper list');
  if(process.env.PAPER_REVIEW_CASE==='problem-history') {
    const panel=find('LearningProblemHistory'),requests=()=>report.requests.filter(r=>r.path==='/api/learning/problem-history');
    await route('/user-home?uid='+uid);await until(()=>ev(panel+'?.owner&&!'+panel+'.expanded'),'own collapsed history');assert.equal(requests().length,0);
    await ev("document.querySelector('.learning-history header .el-button').click();true");await until(()=>ev(panel+'.result?.items.length===20&&!'+panel+'.loading'),'lazy first page');
    assert.equal(await ev("document.querySelector('.history-freshness').textContent.includes('历史仍在补全')&&document.querySelector('.history-freshness').textContent.includes('待刷新')"),true);
    await ev("document.querySelector('.history-task summary').click();document.querySelector('.learning-history').scrollIntoView();true");assert.equal(await ev("document.querySelectorAll('.history-entry').length"),21);assert.equal(await ev("!!document.querySelector('.history-task img')"),false);
    assert.equal(await ev("document.querySelector('.history-task').textContent.includes('本站题目 Rating 1600')&&document.querySelector('.history-task').textContent.includes('CF 题目 Rating 800')&&document.querySelector('.history-task').textContent.includes('时间未提供')"),true);
    await shot('problem-history-desktop',true);report.checks.push('History is own-only and lazy; original source records retain separate ratings, status and unknown times, stale/incomplete coverage is visible and titles are escaped.');
    await ev(panel+'.pageChanged(2);true');await until(()=>ev(panel+'.result?.items.length===5&&!'+panel+'.loading'),'second page');assert.equal(requests().at(-1).query.page,'2');
    await ev(panel+'.filters.source="HOJ";'+panel+'.filters.status="ATTEMPTED";'+panel+'.filters.onlyDuplicates=true;'+panel+'.changed();'+panel+'.search();true');await until(()=>ev(panel+'.result?.total===1&&!'+panel+'.loading'),'filtered original entry');assert.equal(await ev(panel+'.result.items[0].solved'),false);assert.equal(await ev(panel+'.result.items[0].entries.length'),1);assert.equal(await ev("document.querySelector('.history-task').textContent.includes('本次展示 1 条')"),true);
    report.checks.push('Pagination sends page 2; source/status/duplicate filters reset page and distinguish the selected source status from the global canonical group.');
    await ev(panel+'.filters.source="NOWCODER";'+panel+'.filters.status="ALL";'+panel+'.filters.onlyDuplicates=false;'+panel+'.changed();'+panel+'.search();true');await until(()=>ev(panel+'.result?.total===1&&!'+panel+'.loading'),'unrated source');assert.equal(await ev("document.querySelector('.history-task').textContent.includes('牛客来源 Rating 未提供')"),true);
    historyReject=true;await ev(panel+'.load();true');await until(()=>ev(panel+'.error.includes("暂不可用")'),'failed read');assert.equal(await ev(panel+'.result'),null);await ev("document.querySelector('.learning-history .el-alert button').click();true");await until(()=>ev('!!'+panel+'.result&&!'+panel+'.loading'),'retry');
    report.checks.push('Missing source ratings stay unknown; a failed refresh clears old data and offers an explicit retry.');
    historyDelay=900;let before=requests().length;await ev(panel+'.load();true');await until(()=>requests().length>before,'delayed history');await ev(panel+'.toggle();true');await sleep(1100);assert.equal(await ev(panel+'.result'),null);assert.equal(await ev(panel+'.expanded'),false);
    historyDelay=0;await ev(panel+'.toggle();true');await until(()=>ev('!!'+panel+'.result&&!'+panel+'.loading'),'reopened history');historyDelay=900;before=requests().length;await ev(panel+'.load();true');await until(()=>requests().length>before,'previous session read');historyDelay=0;historyRevision=1;
    await ev("document.getElementById('app').__vue__.$store.commit('changeUserToken','history-new-session');true");await until(()=>ev('!'+panel+'.expanded'),'new session reset');await ev(panel+'.toggle();true');await until(()=>ev(panel+'.result?.items[0].title==="新会话题目"'),'new session history');await sleep(1100);assert.equal(await ev(panel+'.result.items[0].title'),'新会话题目');
    report.checks.push('Closing the ledger ignores delayed data; even the same UID with a new authentication epoch cannot receive a late previous-session response.');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await ev("document.documentElement.dataset.theme='dark';document.querySelector('.learning-history').scrollIntoView();true");await shot('problem-history-mobile-dark',true);assert.equal(await ev("(()=>{const r=document.querySelector('.learning-history').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth})()"),true);
    assert.equal(await ev("document.querySelectorAll('.history-method a').length"),2);const visits=requests().length;await route('/user-home?uid=another-user');assert.equal(await ev("!!document.querySelector('.learning-history')"),false);assert.equal(requests().length,visits);assert.equal(report.requests.some(r=>r.path==='/api/ai/chat'||r.path==='/api/ai/recommendations/explain'),false);
    report.checks.push('Mobile dark layout fits the viewport; objective/paper records retain their own links and another user page makes no own-history request or model call.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'problem-history-browser.json'),JSON.stringify(report,null,2));console.log('Problem history browser: '+report.checks.length+' workflows / '+report.screenshots.length+' screenshots.');return;
  }
  if(process.env.PAPER_REVIEW_CASE==='personal-remote'){
    const panel=find('PersonalRemoteSubmit'),problem=find('ProblemDetails');
    await route('/problem/CF-4A');await until(()=>ev(panel+'?.accounts.length===2'),'personal source accounts');
    assert.equal(await ev(panel+'.platformName'),'Codeforces');assert.equal(await ev(panel+'.canSubmit'),false);
    const vault=find('RemoteCredentialSettings');await ev(vault+'.open();true');await until(()=>ev(vault+'?.visible&&'+vault+'?.settings&&!'+vault+'.loading'),'open session authorization from problem');await ev(vault+'.visible=false;true');await sleep(300);
    personalOptionDelay=900;await ev(panel+'.loadLanguages();true');await sleep(100);await ev(panel+'.accountId=2;'+panel+'.accountChanged();true');await sleep(1050);
    assert.equal(await ev(panel+'.offerId'),'');personalOptionDelay=0;await ev(panel+'.loadLanguages();true');await until(()=>ev(panel+".languageId==='31'"),'second account language');
    await ev(panel+'.accountId=1;'+panel+'.accountChanged();'+panel+'.loadLanguages();true');await until(()=>ev(panel+".languageId==='54'"),'first account language');
    await ev(problem+".code='int main() { return 0; }';true");await until(()=>ev(panel+'.canSubmit'),'personal submit enabled');
    personalReject=503;await ev(panel+'.submit();true');await until(()=>ev('!'+panel+'.busy&&'+panel+'.pending!==null'),'uncertain submission');
    const original=await ev(panel+'.pending.requestId');await ev(problem+".code='changed source';true");assert.equal(await ev(panel+'.canSubmit'),false);
    await ev(problem+".code='int main() { return 0; }';true");personalReject=0;await ev(panel+'.submit();true');await until(()=>ev(panel+'.submitId===700'),'retry recovered id');
    assert.equal(await ev(panel+'.pending.requestId'),original);assert.equal(personalRequests.size,1);assert.equal(report.requests.some(r=>r.path==='/api/submit-problem-judge'),false);
    report.checks.push('Personal language offers ignore late account responses; uncertain submissions retain the original request and source draft; retries create one task and never call legacy submission.');
    await shot('personal-remote-desktop',true);
    personalStatusDelay=1000;await ev(panel+'.check();true');await sleep(100);await ev(panel+'.cancel();true');await until(()=>ev(panel+".task.state==='CANCELLED'&&!"+panel+'.busy'),'cancel task');await sleep(1200);
    assert.equal(await ev(panel+'.task.state'),'CANCELLED');personalStatusDelay=0;
    personalReject=400;await ev(panel+'.submit();true');await until(()=>ev('!'+panel+'.busy'),'invalid source');assert.equal(await ev(panel+'.pending'),null);personalReject=0;
    personalState='NEEDS_REVIEW';await ev(panel+'.submit();true');await until(()=>ev(panel+".task?.state==='NEEDS_REVIEW'"),'review required');await ev(panel+'.reconcile();true');await until(()=>ev(panel+".task.state==='RECONCILING'"),'readonly reconciliation');
    personalState='COMPLETED';await ev(panel+'.check();true');await until(()=>ev(panel+".progressLabel==='Accepted'"),'accepted verdict');
    report.checks.push('Late polling cannot undo cancellation; definite validation failures release the draft; manual reconciliation and completed verdicts render correctly.');
    await cdp('Page.reload');await sleep(1000);await until(()=>ev(panel+'?.submitId===701&&'+panel+'?.accounts.length===2'),'reload restores task');assert.equal(await ev(panel+'?.pending?.requestId'),[...personalRequests.keys()][1]);
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await ev("document.querySelector('.personal-remote-submit').scrollIntoView({block:'start'});true");await sleep(200);
    assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);await shot('personal-remote-mobile',true);await ev("document.documentElement.dataset.theme='dark';true");await shot('personal-remote-dark',true);
    await ev(panel+".mode='public';"+panel+'.modeChanged();true');assert.equal(await ev(problem+'.personalRemoteActive'),false);
    report.checks.push('Refreshing recovers only the signed-in user and current problem task; mobile and dark mode remain usable; public-account submission stays an explicit separate choice.');
    personalOptionDelay=900;await ev(panel+".mode='personal';"+panel+'.loadLanguages();true');await sleep(100);uid='paper-bob';role='default_user';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await sleep(1100);
    assert.equal(await ev(panel+'.actor'),'paper-bob');assert.equal(await ev(panel+'.pending'),null);assert.equal(await ev(panel+'.submitId'),null);assert.equal(await ev(panel+'.offerId'),'');
    report.checks.push('Switching HOJ users clears previous task state and ignores the late language response from the old user.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'personal-remote-browser.json'),JSON.stringify(report,null,2));console.log('Personal remote browser: '+report.checks.length+' workflows passed.');return;
  }
  if(nowcoderSourceCase){
    const panel=find('RemoteSources'),language=find('RemoteLanguageSwitch'),problem=find('ProblemDetails');
    await route('/admin/problems?type=remote');await until(()=>ev(panel+'?.records.length===2'),'initial catalogue');await ev(panel+".changePlatform('nowcoder');true");await until(()=>ev(panel+".platform==='nowcoder'&&"+panel+".records[0]?.problemKey==='"+ncKey+"'"),'NowCoder catalogue');assert.equal(await ev(panel+'.sourceLanguage'),'zh');await shot('nowcoder-catalogue-desktop',true);
    await ev(panel+".sourceId='NC218144';"+panel+'.lookup();true');await until(()=>ev(panel+'.records.length===1&&!'+panel+'.discovering'),'explicit source lookup');await ev(panel+'.request('+panel+'.records[0]);true');await until(()=>ev(panel+".records[0].importState==='QUEUED'"),'Chinese import queued');
    report.checks.push('NowCoder source tab shows partial/full progress and independent source rating; explicit task lookup and import use fixed NowCoder endpoints.');
    await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'),'Chinese native editor');assert.equal(await ev(panel+'.language'),'zh');assert.equal(await ev("document.querySelector('.remote-editor-heading').textContent.includes('中文原文')"),true);
    await ev(panel+".form.title='中文原题';"+panel+".form.description='<p>计算 $a+b$。</p>';"+panel+".form.examples=[{input:'1 2',output:'3'}];"+panel+'.save();true');await until(()=>ev(panel+'.detail.source&&!'+panel+'.saving'),'Chinese source save');assert.equal(report.requests.filter(r=>r.path.includes('/nowcoder/statements/')).pop().path,'/api/admin/remote-sources/nowcoder/statements/zh');
    await ev(panel+".changeLanguage('en');true");await until(()=>ev(panel+".language==='en'"),'English translation editor');await ev(panel+".form.title='English translation';"+panel+".form.description='<p>Compute the sum.</p>';"+panel+'.save();true');await until(()=>ev(panel+'.detail.translation&&!'+panel+'.saving'),'reviewed English save');await shot('nowcoder-english-editor',true);await ev(panel+'.closeEditor();true');
    await route('/problem/NC-218144');await until(()=>ev(language+"?.detail?.sourceLanguage==='zh-CN'&&"+language+'?.detail?.translation'),'native language metadata');assert.equal(await ev(language+'.sourceCode'),'zh');assert.equal(await ev("document.querySelector('.remote-language-switch a').textContent.includes('牛客')"),true);await ev(problem+".code='int main(){}';"+language+".select('zh');true");assert.equal(await ev(problem+'.problemData.problem.title'),'中文原题');await ev(language+".select('en');true");assert.equal(await ev(problem+'.problemData.problem.title'),'English translation');assert.equal(await ev(problem+'.code'),'int main(){}');await shot('nowcoder-student-english',true);
    report.checks.push('Chinese is the native version, English is reviewed translation; both save and render with correct language labels without changing code.');
    await route('/admin/problems?type=remote');await until(()=>ev(panel+'?.records.length===2'),'directory remount');await ev(panel+".changePlatform('nowcoder');true");await until(()=>ev(panel+".records[0]?.problemKey==='"+ncKey+"'"),'NC source again');await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'),'source update');await ev(panel+".form.description='<p>修改后的中文原文。</p>';"+panel+'.save();true');await until(()=>ev(panel+'.detail.translationStale&&!'+panel+'.saving'),'English stale');await ev(panel+'.closeEditor();true');await route('/problem/NC-218144');await until(()=>ev(language+'?.detail?.translationStale'),'student stale translation');assert.equal(await ev(language+'.detail.translation'),null);await ev(language+".select('en');true");assert.equal(await ev(language+'.language'),'local');await ev(language+".select('zh');true");assert.equal(await ev(language+'.language'),'zh');
    report.checks.push('Updating native Chinese hides stale English; native Chinese remains selectable when no reviewed English is available.');
    await route('/admin/problems?type=remote');await until(()=>ev(panel+'?.records.length===2'),'return directory');await ev(panel+".changePlatform('nowcoder');true");await until(()=>ev(panel+".records[0]?.problemKey==='"+ncKey+"'"),'NC mobile directory');await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(250);assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);await shot('nowcoder-catalogue-mobile',true);await ev("document.documentElement.dataset.theme='dark';true");await shot('nowcoder-catalogue-dark',true);
    ncLookupDelay=900;await ev(panel+".sourceId='218144';"+panel+'.lookup();true');await sleep(100);await ev(panel+".changePlatform('codeforces');true");await sleep(1200);assert.equal(await ev(panel+'.records[0].problemKey'),remoteKey);
    report.checks.push('Mobile/dark controls fit; a late lookup result cannot overwrite the newly selected source platform.');assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'nowcoder-source-browser.json'),JSON.stringify(report,null,2));console.log('NowCoder source browser: '+report.checks.length+' groups; '+report.screenshots.length+' screenshots.');return;
  }
  if(process.env.PAPER_REVIEW_CASE==='atcoder-sources'){
    const panel=find('RemoteSources'),language=find('RemoteLanguageSwitch'),problem=find('ProblemDetails');
    await route('/admin/problems?type=remote');await until(()=>ev(panel+'?.records.length===2'),'initial CF directory');
    await ev(panel+".changePlatform('atcoder');true");await until(()=>ev(panel+"?.platform==='atcoder'&&"+panel+"?.records[0]?.problemKey==='"+atKey+"'"),'AtCoder source switch');
    assert.equal(await ev("document.querySelector('.remote-heading').textContent.includes('AtCoder')"),true);assert.equal(await ev(panel+'.records[1].estimatedDifficulty'),null);await shot('atcoder-catalogue-desktop',true);
    await ev(panel+".keyword='abc086_a';"+panel+'.search();true');await until(()=>ev(panel+'.records.length===1'),'AtCoder task search');await ev(panel+'.request('+panel+'.records[0]);true');await until(()=>ev(panel+".records[0].importState==='QUEUED'"),'AtCoder import queue');
    report.checks.push('Source tabs, task search, explicit third-party difficulty and its unknown state, and AtCoder-specific import routing work.');
    await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'),'AtCoder editor');await ev(panel+".form.description='<p>Multiply two integers.</p>';"+panel+".form.examples=[{input:'3 4',output:'Even'}];"+panel+'.save();true');await until(()=>ev(panel+'.detail.source&&!'+panel+'.saving'),'English source saved');
    await ev(panel+".changeLanguage('zh');true");await until(()=>ev(panel+".language==='zh'"),'Chinese source editor');await ev(panel+".form.title='乘积';"+panel+".form.description='<p>判断两数乘积的奇偶性。</p>';"+panel+'.save();true');await until(()=>ev(panel+'.detail.translation&&!'+panel+'.saving'),'Chinese saved');await ev(panel+'.setRefresh(true);true');await until(()=>ev(panel+'.detail.refresh.enabled&&!'+panel+'.policySaving'),'daily refresh enabled');
    await ev(panel+".form.title='unsaved Chinese';"+panel+'.setRefresh(false);true');await until(()=>ev('!'+panel+'.detail.refresh.enabled&&!'+panel+'.policySaving'),'daily refresh disabled');assert.equal(await ev(panel+'.form.title'),'unsaved Chinese');await ev(panel+".form.title='乘积';true");
    report.checks.push('Daily source checking toggles use the platform endpoint, show next check time and preserve unsaved translation edits.');await shot('atcoder-translation-desktop',true);await ev(panel+'.closeEditor();true');
    await route('/problem/AC-abc086_a');await until(()=>ev(language+'?.detail?.translation'),'AtCoder student languages');assert.equal(await ev("document.querySelector('.remote-language-switch a').textContent.includes('AtCoder')"),true);await ev(language+".select('zh');true");assert.equal(await ev(problem+'.problemData.problem.title'),'乘积');await shot('atcoder-student-chinese',true);
    report.checks.push('English and reviewed Chinese save through AtCoder endpoints; students see the correct source link and selected statement.');
    await route('/admin/problems?type=remote');await until(()=>ev(panel+'?.records.length===2'),'directory remount');await ev(panel+".changePlatform('atcoder');true");await until(()=>ev(panel+".records[0]?.problemKey==='"+atKey+"'"),'AtCoder directory restored');
    await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'),'platform discard editor');await ev(panel+".form.title='unsaved draft';"+panel+".changePlatform('codeforces');true");await until(()=>ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"),'switch discard dialog');await ev("document.querySelector('.el-message-box__btns .el-button:not(.el-button--primary)').click();true");assert.equal(await ev(panel+'.platform'),'atcoder');assert.equal(await ev(panel+'.form.title'),'unsaved draft');
    await ev(panel+".changePlatform('codeforces');true");await until(()=>ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"),'second discard dialog');await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");await until(()=>ev(panel+".platform==='codeforces'&&!"+panel+".visible&&"+panel+".records[0]?.problemKey==='"+remoteKey+"'"),'CF switch with discard');
    await ev(panel+".changePlatform('atcoder');true");await until(()=>ev(panel+".records[0]?.problemKey==='"+atKey+"'"),'AT before stale response');atCatalogDelay=900;await ev(panel+'.load(false);true');await sleep(100);await ev(panel+".changePlatform('codeforces');true");await sleep(1200);assert.equal(await ev(panel+'.records[0].problemKey'),remoteKey);atCatalogDelay=0;
    report.checks.push('Platform switching protects unfinished edits; delayed AtCoder catalogue replies cannot overwrite Codeforces results.');
    await ev(panel+".changePlatform('atcoder');true");await until(()=>ev(panel+".records[0]?.problemKey==='"+atKey+"'"),'mobile AtCoder catalogue');await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(250);assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);await shot('atcoder-catalogue-mobile',true);await ev("document.documentElement.dataset.theme='dark';true");await shot('atcoder-catalogue-dark',true);
    report.checks.push('Mobile and dark source selection, difficulty notes and import controls fit without horizontal overflow.');assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'atcoder-source-browser.json'),JSON.stringify(report,null,2));console.log('AtCoder source browser: '+report.checks.length+' groups; '+report.screenshots.length+' screenshots.');return;
  }
  if(process.env.PAPER_REVIEW_CASE==='translation'){
    const panel=find('RemoteSources'),helper=find('RemoteTranslationDraft'),assistant=find('AiAssistant');
    const content={title:'Given task',description:'<p>Given integer <var>n</var>.</p>',input:'<p>An integer.</p>',output:'<p>The result.</p>',hint:'',examples:'<input> 1  2\n</input><output>3\n</output>',timeLimit:1000,memoryLimit:256};
    remoteSource={id:1,origin:'SOURCE',contentHash:'a'.repeat(64),createdAt:Date.now(),content:clone(content)};remoteImportState='DONE';
    ncSource={...clone(remoteSource),contentHash:'c'.repeat(64)};atSource={...clone(remoteSource),contentHash:'d'.repeat(64)};
    await route('/admin/problems?type=remote');await until(()=>ev(panel+'?.records.length===2'),'translation catalogue');await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'),'source editor');
    await ev(panel+".changeLanguage('zh');true");await until(()=>ev(helper+'?.settings&&!'+helper+'.loading'),'translation settings');assert.equal(await ev(helper+'.usable'),false);
    await ev(helper+'.configure();true');await until(()=>ev(assistant+"?.open&&"+assistant+".tab==='settings'&&"+assistant+'.ready'),'existing model settings');
    await ev(assistant+".form={baseUrl:'https://translator.fixture.test/v1',model:'translator-one',apiKey:'synthetic-ui-only-key'};"+assistant+'.saveSettings();true');
    await until(()=>ev(helper+'.usable&&!'+helper+'.loading&&!'+assistant+'.saving'),'configured translator');assert.equal(await ev(assistant+'.form.apiKey'),'');await ev(assistant+'.close();true');
    const requests=()=>report.requests.filter(r=>r.path==='/api/admin/remote-sources/translation');let before=requests().length;await ev(helper+'.generate();true');assert.equal(requests().length,before);
    report.checks.push('Translation uses existing personal model settings; unconfigured or unconsented generation makes no model request.');
    const originalForm=await ev('JSON.stringify('+panel+'.form)');await ev(helper+'.consent=true;'+helper+'.generate();true');await until(()=>ev(helper+'.draft&&!'+helper+'.pending'),'generated draft');
    assert.equal(await ev('JSON.stringify('+panel+'.form)'),originalForm);assert.equal(remoteTranslation,null);assert.equal(requests().pop().data.sourceHash,remoteSource.contentHash);await shot('translation-draft-desktop',true);
    await ev(panel+'.applyTranslation('+helper+'.draft);true');await until(()=>ev(panel+".form.title==='生成的中文题面'&&!"+panel+'.applyingTranslation'),'apply draft');assert.equal(await ev(helper+'.applied'),true);assert.equal(remoteTranslation,null);
    await ev(panel+'.save();true');await until(()=>ev('!'+panel+'.saving'),'reviewed save');assert.equal(remoteTranslation.content.examples,content.examples);assert.equal(remoteTranslation.sourceHash,remoteSource.contentHash);
    report.checks.push('Generating and applying never publish; explicit reviewed save uses the original hash and retains exact sample bytes.');
    for(const platform of ['nowcoder','atcoder']){
      await ev(panel+'.closeEditor();true');await until(()=>ev('!'+panel+'.visible'),'close clean editor');await ev(panel+'.changePlatform('+JSON.stringify(platform)+');true');await until(()=>ev(panel+'.platform==='+JSON.stringify(platform)+'&&'+panel+'.records.length===2'),'switch source');
      await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'),'source source');await ev(panel+'.changeLanguage('+panel+'.translationLanguage);true');await until(()=>ev(helper+'?.usable&&!'+helper+'.loading'),'source model');
      await ev(helper+'.consent=true;'+helper+'.generate();true');await until(()=>ev(helper+'.draft&&!'+helper+'.pending'),'source draft');assert.equal(await ev(helper+'.draft.translationLanguage'),platform==='nowcoder'?'en':'zh-CN');
      await ev(panel+'.applyTranslation('+helper+'.draft);true');await until(()=>ev('!'+panel+'.applyingTranslation&&'+helper+'.applied'),'source apply');await ev(panel+'.save();true');await until(()=>ev('!'+panel+'.saving'),'source reviewed save');
      const saved=platform==='nowcoder'?ncTranslation:atTranslation;assert.equal(saved.content.examples,content.examples);
    }
    report.checks.push('CF and AtCoder generate Chinese drafts, NowCoder generates English drafts, and each saves through its existing reviewed-language endpoint.');
    translationDelay=900;await ev(helper+'.consent=true;'+helper+'.generate();true');await until(()=>ev(helper+'.pending')&&requests().length>before,'pending translation');await ev(panel+".form.title='保留我的手工修改';true");await until(()=>ev(helper+'.draft&&!'+helper+'.pending'),'delayed draft');translationDelay=0;assert.equal(await ev(panel+'.form.title'),'保留我的手工修改');
    await ev(panel+'.applyTranslation('+helper+'.draft);true');await until(()=>ev("!!document.querySelector('.el-message-box__btns')"),'replacement confirmation');await ev("document.querySelector('.el-message-box__btns .el-button:not(.el-button--primary)').click();true");await until(()=>ev('!'+panel+'.applyingTranslation'),'cancel apply');assert.equal(await ev(panel+'.form.title'),'保留我的手工修改');
    translationReject=true;await ev(helper+'.generate();true');await until(()=>ev(helper+'.error&&!'+helper+'.pending'),'model failure');translationReject=false;assert.equal(await ev(panel+'.form.title'),'保留我的手工修改');
    report.checks.push('Late generation cannot overwrite edits; replacing an edited draft requires confirmation and upstream errors retain working content.');
    aiConfigurations.get(uid).fingerprint='e'.repeat(64);before=requests().length;await ev(helper+'.generate();true');await until(()=>ev('!'+helper+'.pending&&!'+helper+'.consent'),'changed model consent');assert.equal(requests().length,before);
    await ev(helper+'.consent=true;'+helper+'.generate();true');await until(()=>ev(helper+'.draft&&!'+helper+'.pending'),'new model draft');atSource.contentHash='b'.repeat(64);
    await ev(panel+'.applyTranslation('+helper+'.draft);true');await until(()=>ev("!!document.querySelector('.el-message-box__btns')"),'stale draft replacement');await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");await until(()=>ev('!'+panel+'.applyingTranslation'),'changed source blocked');assert.equal(await ev(panel+'.form.title'),'保留我的手工修改');
    report.checks.push('A model configuration change clears consent before POST; a changed source is re-read before applying and leaves the edited form intact.');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await ev("document.querySelector('.translation-helper').scrollIntoView();true");await shot('translation-mobile',true);await ev("document.documentElement.dataset.theme='dark';true");await shot('translation-mobile-dark',true);assert.equal(await ev("(()=>{const r=document.querySelector('.translation-helper').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth})()"),true);
    report.checks.push('Consent, generation status and draft actions remain usable on mobile and in dark mode.');
    await ev(panel+'.closeEditor();true');await until(()=>ev("!!document.querySelector('.el-message-box__btns')"),'draft loss guard');await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");await until(()=>ev('!'+panel+'.visible'),'close stale editor');
    await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'),'reopen source');await ev(panel+".changeLanguage('zh');true");await until(()=>ev(helper+'?.usable&&!'+helper+'.loading'),'new source helper');
    translationDelay=1000;before=requests().length;await ev(helper+'.consent=true;'+helper+'.generate();true');await until(()=>requests().length>before,'old auth request');
    await ev("document.getElementById('app').__vue__.$store.commit('changeUserToken','fixture-next-session');true");await sleep(1200);translationDelay=0;assert.equal(await ev(panel+'.visible'),false);assert.equal(await ev(panel+'.form'),null);
    report.checks.push('Same-user re-login closes the editor and discards delayed translation results from the previous auth epoch.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'translation-browser.json'),JSON.stringify(report,null,2));console.log('Translation browser: '+report.checks.length+' groups; '+report.screenshots.length+' screenshots.');return;
  }
  if(process.env.PAPER_REVIEW_CASE==='remote-sources'){
    const panel=find('RemoteSources'), language=find('RemoteLanguageSwitch'), problem=find('ProblemDetails');
    await route('/admin/problems?type=remote');await until(()=>ev(panel+'?.records.length===2'), 'remote catalogue');
    assert.equal(await ev("document.querySelector('.remote-table').textContent.includes('未评级')"),true);assert.equal(await ev("document.querySelector('.remote-error').textContent.includes('403')"),true);await shot('remote-catalogue-desktop');
    await ev(panel+".keyword='4A';"+panel+'.search();true');await until(()=>ev(panel+'.records.length===1'), 'catalogue search');
    await ev(panel+'.request('+panel+'.records[0]);true');await until(()=>ev(panel+".records[0].importState==='QUEUED'"), 'source import queued');assert.equal(report.requests.filter(r=>r.path.endsWith('/imports')).pop().data.problemKey,remoteKey);
    report.checks.push('Admin catalogue shows source IDs, unknown ratings, sync freshness, search, explicit 403 guidance and queued imports.');
    await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'), 'manual source editor');
    await ev(panel+".form.title='Watermelon';"+panel+".form.description='<p>Can an even watermelon be split?</p>';"+panel+".form.examples=[{input:'8',output:'YES'}];true");
    await ev(panel+'.closeEditor();true');await until(()=>ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'source discard guard');
    await ev("document.querySelector('.el-message-box__btns .el-button:not(.el-button--primary)').click();true");assert.equal(await ev(panel+'.visible'),true);
    await ev(panel+'.save();true');await until(()=>ev(panel+'.detail.source&&!'+panel+'.saving'), 'manual English saved');assert.equal(remoteSource.content.examples,'<input>8</input><output>YES</output>');
    await ev(panel+".changeLanguage('zh');true");await until(()=>ev(panel+".language==='zh'"), 'Chinese editing');
    await ev(panel+".form.title='西瓜';"+panel+".form.description='<p>能否分成两块重量为正偶数的西瓜？</p>';true");await shot('remote-chinese-editor');
    await ev(panel+'.save();true');await until(()=>ev(panel+'.detail.translation&&!'+panel+'.saving'), 'Chinese saved');assert.equal(remoteTranslation.sourceHash,remoteSource.contentHash);
    report.checks.push('Manual English creates a hidden question, samples retain their format, unsaved edits are guarded, and reviewed Chinese binds the exact source version.');
    await ev(panel+'.closeEditor();true');await route('/problem/CF-4A');await until(()=>ev(language+'?.detail?.translation'), 'student languages');
    assert.equal(await ev(problem+'.problemData.problem.title'),'题库手工编辑的标题');
    await ev(problem+".code='int main(){ return 0; }';true");
    await ev(language+".select('en');true");assert.equal(await ev(problem+'.problemData.problem.title'),'Watermelon');assert.equal(await ev(problem+'.problemData.problem.examples[0].output'),'YES');
    await ev(language+".select('zh');true");assert.equal(await ev(problem+'.problemData.problem.title'),'西瓜');await shot('remote-student-chinese');
    await ev(language+".select('local');true");assert.equal(await ev(problem+'.problemData.problem.description'),'<p>题库保留的说明</p>');
    assert.equal(await ev(problem+'.code'),'int main(){ return 0; }');
    report.checks.push('Student defaults to the independently edited library statement and explicitly switches English/Chinese without changing code, source identity or samples.');
    await route('/admin/problems?type=remote');await until(()=>ev(panel+'?.records.length===2'), 'return catalogue');await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'), 'reopen source');
    await ev(panel+".form.description='<p>The source has changed.</p>';"+panel+'.save();true');await until(()=>ev(panel+'.detail.translationStale&&!'+panel+'.saving'), 'stale translation');
    await ev(panel+'.closeEditor();true');await route('/problem/CF-4A');await until(()=>ev(language+'?.detail?.translationStale'), 'student stale notice');assert.equal(await ev(language+'.detail.translation'),null);assert.equal(await ev("document.querySelector('.remote-language-switch').textContent.includes('核对中')"),true);
    report.checks.push('Refreshing English hides stale Chinese from students and provides an explicit review notice.');
    await route('/admin/problems?type=remote');await until(()=>ev(panel+'?.records.length===2'), 'mobile catalogue');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(250);assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);await shot('remote-catalogue-mobile',true);
    await ev("document.documentElement.dataset.theme='dark';true");await shot('remote-catalogue-dark',true);await ev("document.documentElement.dataset.theme='light';true");
    await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'), 'mobile source editor');assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);assert.equal(await ev("document.querySelector('.remote-statement-dialog .el-dialog__footer').getBoundingClientRect().bottom<=innerHeight"),true);await shot('remote-editor-mobile',true);await ev(panel+'.closeEditor();true');
    await cdp('Emulation.setDeviceMetricsOverride',{width:1440,height:1100,deviceScaleFactor:1,mobile:false});
    remoteDelay=1200;await ev(panel+'.open('+panel+'.records[0]);true');await sleep(180);uid='paper-bob';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await sleep(1400);assert.equal(await ev(panel+'.visible'),false);assert.equal(await ev(panel+'.form'),null);remoteDelay=0;
    await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'), 'pending discard editor');await ev(panel+".form.title='Bob draft';"+panel+'.closeEditor();true');await until(()=>ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'pending discard prompt');uid='paper-alice';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await until(()=>ev('!'+panel+'.visible&&'+panel+'.form===null&&'+panel+'.records.length===2'), 'actor switch cleared editor');await ev(panel+'.open('+panel+'.records[0]);true');await until(()=>ev(panel+'.form&&!'+panel+'.opening'), 'new actor editor');await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");await sleep(150);assert.equal(await ev(panel+'.visible'),true);assert.equal(await ev(panel+'.form.title'),'Watermelon');await ev(panel+'.closeEditor();true');
    report.checks.push('Mobile cards, a visible editor footer and dark layouts remain usable; account switching clears private drafts and ignores delayed replies and stale discard confirmations.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'paper-browser.json'),JSON.stringify(report,null,2));console.log('Remote sources browser: '+report.checks.length+' workflows; '+report.screenshots.length+' screenshots.');return;
  }
  if(nowcoderCase){
    const panel=find('PersonalRemoteSubmit'),problem=find('ProblemDetails');await route('/problem/NC-218144');await until(()=>ev(panel+'?.accounts.length===1'),'NowCoder account');assert.equal(await ev(panel+'.platformName'),'牛客');assert.equal(await ev("document.querySelectorAll('.personal-remote-submit .el-radio-group').length"),0);
    await ev(panel+'.loadLanguages();true');await until(()=>ev(panel+".languageId==='2'&&!"+panel+'.loadingLanguages'),'NowCoder source languages');await ev(problem+".code='int main(){}';true");assert.equal(await ev(panel+'.canSubmit'),false);assert.equal(await ev("document.querySelector('.remote-actions>.el-button').textContent.includes('暂未开放')"),true);report.checks.push('The NC submit button honors the server capability during a rolling update and does not expose legacy/public account mode.');
    nowcoderSupported=true;await ev(panel+'.refreshAccounts();true');await until(()=>ev(panel+'.submissionSupported&&!'+panel+'.loadingAccounts'),'NC server capability opened');await ev(panel+'.loadLanguages();true');await until(()=>ev(panel+".languageId==='2'&&!"+panel+'.loadingLanguages'),'current NC languages');assert.equal(await ev(panel+".languages['11']"),'Python 3');assert.equal(await ev(panel+'.canSubmit'),true);report.checks.push('Enabled NC capability and current account-scoped source language offers allow the owner to submit.');
    personalReject=503;await ev(panel+'.submit();true');await until(()=>ev('!'+panel+'.busy&&'+panel+'.pending!==null'),'NC uncertain request');const original=await ev(panel+'.pending.requestId');personalReject=0;await ev(panel+'.submit();true');await until(()=>ev(panel+'.submitId===700'),'NC original request recovered');assert.equal(await ev(panel+'.pending.requestId'),original);assert.equal(personalRequests.size,1);assert.equal(report.requests.some(r=>r.path==='/api/submit-problem-judge'),false);await ev("document.querySelector('.personal-remote-submit').scrollIntoView({block:'start'});true");await shot('nowcoder-personal-desktop',true);report.checks.push('A lost local response keeps the original request ID and code draft; retry recovers one server task and never uses legacy dispatch.');
    const vault=find('RemoteCredentialSettings');await ev(vault+'.open();true');await until(()=>ev(vault+'.settings&&!'+vault+'.loading'),'NC authorization');assert.equal(await ev(vault+'.platformName'),'牛客');assert.equal(await ev("document.querySelector('.credential-login').href"),'https://ac.nowcoder.com/login');await ev(vault+'.visible=false;true');await sleep(300);report.checks.push('The problem panel opens the correct owner session settings and fixed source login without showing saved cookie contents.');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await ev("document.querySelector('.personal-remote-submit').scrollIntoView({block:'start'});true");await sleep(200);assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);await shot('nowcoder-personal-mobile',true);await ev("document.documentElement.dataset.theme='dark';true");await shot('nowcoder-personal-dark',true);report.checks.push('Mobile and dark layouts keep source account, language, submission and task status controls usable without overflow.');assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'personal-remote-browser.json'),JSON.stringify(report,null,2));console.log('NowCoder personal browser: '+report.checks.length+' groups.');return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'nowcoder') {
    const panel=find('ExternalAccounts');await route('/user-home?uid='+uid);await until(()=>ev(panel+'?.snapshot?.uid==='+JSON.stringify(uid)), 'NowCoder account panel');
    await ev("const select=document.querySelector('.external-link select');select.value='NOWCODER';select.dispatchEvent(new Event('change',{bubbles:true}));true");
    assert.equal(await ev("document.querySelector('.external-link input').placeholder.includes('数字用户 ID')"),true);
    await ev(panel+".handle='12345';"+panel+'.link();true');await until(()=>ev(panel+'.snapshot.accounts.length===1&&!'+panel+'.busy'), 'NowCoder link');
    assert.equal(report.requests.find(r=>r.path==='/api/learning/accounts'&&r.method==='POST').data.platform,'NOWCODER');assert.equal(await ev(panel+'.snapshot.accounts[0].solved'),null);
    await ev(panel+".act('externalProof',1);true");await until(()=>ev("!!document.querySelector('.source-proof')"),'NowCoder proof');assert.equal(await ev("document.querySelector('.source-proof').textContent.includes('公开简介')"),true);assert.equal(await ev("document.querySelector('.source-proof').textContent.includes('Organization')"),false);
    await ev("document.querySelector('.external-accounts').scrollIntoView();true");await shot('nowcoder-proof-desktop');report.checks.push('Numeric NowCoder binding sends its platform, unknown counts remain unknown, and ownership instructions identify the public biography.');
    await ev(panel+".act('externalVerify',1);true");await until(()=>ev(panel+'.snapshot.accounts[0].verified&&!'+panel+'.busy'),'NowCoder verified');await ev("document.querySelector('.source-details').open=true;true");
    assert.equal(await ev("document.querySelector('.source-heading').textContent.includes('牛客测试选手')"),true);assert.equal(await ev("document.querySelector('.source-heading a').href"),'https://ac.nowcoder.com/acm/contest/profile/12345');assert.equal(await ev("document.querySelector('.source-history a').href"),'https://ac.nowcoder.com/acm/contest/99785');
    assert.equal(await ev("document.querySelector('.source-numbers').textContent.includes('CF Rating')"),false);assert.equal(await ev("document.querySelector('.source-numbers').textContent.includes('暂无可靠')"),true);assert.equal(await ev("document.querySelector('.external-source').textContent.includes('专项与试卷记录')"),true);assert.equal(await ev("document.querySelectorAll('.source-coverage meter').length"),0);assert.equal(await ev("document.querySelectorAll('.remote-credential-settings').length"),1);
    await ev("document.querySelector('.practice-context').open=true;true");
    assert.deepEqual(await ev("Array.from(document.querySelectorAll('.practice-context dd')).map(n=>n.textContent.trim())"),['22 / 38 份','12 / 15 份']);
    assert.equal(await ev("document.querySelector('.practice-context li:first-child').textContent.includes('重做中，已有完成记录')"),true);
    assert.equal(await ev("document.querySelector('.practice-context li:first-child').textContent.includes('0%')"),true);
    assert.equal(await ev("document.querySelector('.practice-context li:nth-child(2)').textContent.includes('来源分数 0')"),true);
    assert.equal(await ev("document.querySelector('.practice-context li:nth-child(3) .paper-result')===null"),true);
    assert.equal(await ev("document.querySelector('.practice-context li:nth-child(4)').textContent.includes('来源分数')"),false);
    assert.equal(await ev("document.querySelector('.practice-context img')===null"),true);
    assert.equal(await ev("document.querySelector('.practice-source').href"),'https://www.nowcoder.com/users/12345/tests');
    assert.equal(await ev(panel+'.snapshot.summary.uniqueSolved'),17);
    await ev("document.querySelector('.practice-context').scrollIntoView();window.scrollBy(0,-100);true");await shot('nowcoder-practice-desktop',true);
    report.checks.push('Paper and special projections stay separate from solved programming counts; redoing keeps the earlier completion, true0% and0 scores are visible, unfinished percentages and interview scores stay hidden, and titles are escaped.');
    externalLinks.get(uid)[0].practiceContext={...nowcoderPracticeFixture(),available:false,restricted:true};await ev(panel+'.load(false);true');await until(()=>ev(panel+'.snapshot.accounts[0].practiceContext.restricted&&!'+panel+'.loading'),'practice privacy revoked');
    assert.equal(await ev("document.querySelectorAll('.practice-context li').length"),0);
    assert.equal(await ev("document.querySelector('.practice-context').textContent.includes('未公开')"),true);
    externalLinks.get(uid)[0].practiceContext=nowcoderPracticeFixture();await ev(panel+'.load(false);true');await until(()=>ev(panel+'.snapshot.accounts[0].practiceContext.available&&!'+panel+'.loading'),'practice public again');
    report.checks.push('A known source privacy revocation hides paper records even if a stale fixture still carries their array.');
    await ev("document.querySelector('.contest-context').open=true;true");
    assert.equal(await ev("document.querySelectorAll('.contest-context li').length"),3);assert.equal(await ev("document.querySelector('.contest-context img')===null"),true);
    assert.deepEqual(await ev("Array.from(document.querySelectorAll('.contest-context li:first-child dd')).map(n=>n.textContent.trim())"),['12','4','2 / 5','1']);
    assert.deepEqual(await ev("Array.from(document.querySelectorAll('.contest-context li:nth-child(2) dd')).map(n=>n.textContent.trim())"),['—','—','— / —','—']);
    assert.equal(await ev("document.querySelector('.contest-context').textContent.includes('不再累加')"),true);assert.equal(await ev("document.querySelector('.contest-context').textContent.includes('团队成绩不作为个人')"),true);
    assert.equal(await ev("document.querySelector('.contest-context li:nth-child(3)').textContent.includes('打星参赛')"),true);
    report.checks.push('Contest context separates source rank/accepted, exact-source contest evidence and same-problem later passes; restricted/team evidence remains unknown and titles are escaped.');
    externalLinks.get(uid)[0].contestContext.syncState='RUNNING';await ev(panel+'.load(false);true');await until(()=>ev(panel+'.snapshot.accounts[0].contestContext.syncState===\'RUNNING\'&&!'+panel+'.loading'),'context refresh');assert.equal(await ev(panel+'.timer!==null'),true);
    externalLinks.get(uid)[0].contestContext.syncState='IDLE';await ev(panel+'.load(false);true');await until(()=>ev('!'+panel+'.loading'),'context complete');assert.equal(await ev(panel+'.timer'),null);
    report.checks.push('Owner polling continues for context work after coding sync is idle and stops when both are complete.');
    externalLinks.get(uid)[0].practiceContext.syncState='RUNNING';await ev(panel+'.load(false);true');await until(()=>ev(panel+'.snapshot.accounts[0].practiceContext.syncState===\'RUNNING\'&&!'+panel+'.loading'),'practice polling');assert.equal(await ev(panel+'.timer!==null'),true);
    externalLinks.get(uid)[0].practiceContext.syncState='IDLE';await ev(panel+'.load(false);true');await until(()=>ev('!'+panel+'.loading'),'practice complete');assert.equal(await ev(panel+'.timer'),null);
    report.checks.push('Owner polling also follows independent paper work after coding and contests are idle.');
    externalLinks.get(uid)[0].contestContext.contests[0].title='旧会话比赛';externalDelay=900;await ev(panel+'.load(false);true');await sleep(150);
    externalLinks.get(uid)[0].contestContext.contests[0].title='新会话比赛';externalDelay=0;await ev("document.getElementById('app').__vue__.$store.commit('changeUserToken','fixture-new-session');true");
    await until(()=>ev(panel+'.snapshot?.accounts[0]?.contestContext.contests[0].title===\'新会话比赛\''),'new session context');await sleep(1100);assert.equal(await ev(panel+'.snapshot.accounts[0].contestContext.contests[0].title'),'新会话比赛');
    report.checks.push('Re-login with the same UID clears old context and rejects late responses from the previous auth epoch.');
    const vault=find('RemoteCredentialSettings');await ev(vault+'.open();true');await until(()=>ev(vault+'?.visible&&'+vault+'?.settings&&!'+vault+'.loading'),'NowCoder session settings');assert.equal(await ev("document.querySelector('.credential-login').href"),'https://ac.nowcoder.com/login');assert.equal(await ev("document.querySelector('.credential-pending')===null"),true);
    await ev(vault+".cookieHeader='t=fake-wrong-account';"+vault+'.authorized=true;'+vault+'.save();true');await until(()=>ev(vault+'.error&&!'+vault+'.busy'),'wrong NowCoder session');assert.equal(await ev(vault+'.settings.configured'),false);assert.equal(await ev(vault+'.cookieHeader'),'');
    await ev(vault+".cookieHeader='t=fake-nowcoder-session';"+vault+'.authorized=true;'+vault+'.save();true');await until(()=>ev(vault+'.settings.configured&&!'+vault+'.busy'),'NowCoder session saved');assert.equal(await ev(vault+'.settings.submissionAvailable'),true);assert.equal(await ev(vault+'.settings.submissionSupported'),true);assert.equal(await ev(vault+'.cookieHeader'),'');await sleep(800);await shot('nowcoder-session-desktop');await ev(vault+'.visible=false;true');await sleep(300);report.checks.push('NowCoder authorization links to its fixed source login, rejects the wrong account, clears the secret input, and exposes the enabled source capability without exposing the saved session.');
    await ev("document.querySelector('.contest-context').open=true;document.querySelector('.contest-context').scrollIntoView();window.scrollBy(0,-100);true");await shot('nowcoder-records-desktop',true);report.checks.push('Verified display separates nickname and stable numeric ID, official rating/history and public-coding scope; unavailable difficulty and classification remain unknown.');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(250);assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);await ev("document.querySelector('.external-accounts').scrollIntoView();true");await shot('nowcoder-mobile',true);await ev("document.documentElement.dataset.theme='dark';true");await shot('nowcoder-dark',true);
    await ev("document.querySelector('.contest-context').scrollIntoView();window.scrollBy(0,-100);true");await shot('nowcoder-context-mobile-dark',true);
    await ev("document.querySelector('.practice-context').open=true;document.querySelector('.practice-context').scrollIntoView();window.scrollBy(0,-100);true");await shot('nowcoder-practice-mobile-dark',true);
    assert.equal(await ev("(()=>{const r=document.querySelector('.practice-context').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth})()"),true);
    assert.equal(await ev("(()=>{const r=document.querySelector('.contest-context').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth})()"),true);
    report.checks.push('Mobile and dark layouts keep the platform selector, source scope and contest evidence accessible without horizontal overflow.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'paper-browser.json'),JSON.stringify(report,null,2));console.log('NowCoder browser: '+report.checks.length+' workflows; '+report.screenshots.length+' screenshots.');return;
  }
  if (['atcoder','credentials'].includes(process.env.PAPER_REVIEW_CASE)) {
    const panel=find('ExternalAccounts');await route('/user-home?uid='+uid);await until(()=>ev(panel+'?.snapshot?.uid==='+JSON.stringify(uid)), 'AtCoder account panel');
    await ev("const select=document.querySelector('.external-link select');select.value='ATCODER';select.dispatchEvent(new Event('change',{bubbles:true}));true");await ev(panel+".handle='chokudai';"+panel+'.link();true');await until(()=>ev(panel+'.snapshot.accounts.length===1&&!'+panel+'.busy'), 'AtCoder link');
    assert.equal(report.requests.find(r=>r.path==='/api/learning/accounts'&&r.method==='POST').data.platform,'ATCODER');assert.equal(await ev("document.querySelector('.source-platform').textContent"),'AtCoder');assert.equal(await ev(panel+'.snapshot.accounts[0].solved'),null);
    await ev(panel+".act('externalProof',1);true");await until(()=>ev("!!document.querySelector('.source-proof')"),'AtCoder ownership directions');assert.equal(await ev("document.querySelector('.source-proof').textContent.includes('Affiliation')"),true);assert.equal(await ev("document.querySelector('.source-proof').textContent.includes('永久用户编号')"),true);await ev("document.querySelector('.external-accounts').scrollIntoView();true");await shot('atcoder-proof-desktop');
    report.checks.push('The platform selector submits ATCODER; unknown counts stay unknown and private proof instructions identify AtCoder Affiliation and the identity limitation.');
    await ev(panel+".act('externalVerify',1);true");await until(()=>ev(panel+'.snapshot.accounts[0].verified&&!'+panel+'.busy'),'AtCoder verified');await ev("document.querySelector('.source-details').open=true;true");
    assert.equal(await ev("document.querySelectorAll('.source-coverage meter').length"),0);assert.equal(await ev("document.querySelector('.source-coverage').textContent.includes('暂不绘制')"),true);assert.equal(await ev("document.querySelector('.source-history a').href"),'https://atcoder.jp/contests/abc350');assert.equal(await ev("document.querySelector('.source-numbers').textContent.includes('账号通过估计难度')"),true);assert.equal(await ev("document.querySelector('.external-source').textContent.includes('第三方')"),true);await shot('atcoder-records-desktop');
    report.checks.push('Official algorithm rating, third-party estimate provenance, model freshness and an official contest link are shown; unsupported knowledge coverage is disclosed instead of charted as zero.');
    if(process.env.PAPER_REVIEW_CASE==='credentials') {
      credentialSettings.set(uid+':1',{accountId:1,configured:true,credentialType:'LEGACY_PASSWORD',reauthorizationRequired:true,version:0,submissionAvailable:false});
      const vault=find('RemoteCredentialSettings');await ev(vault+'.open();true');await until(()=>ev(vault+'.settings&&!'+vault+'.loading'),'credential settings');assert.equal(await ev("document.querySelector('#remote-submit-session').type"),'password');assert.equal(await ev("document.querySelector('.remote-credential-dialog').textContent.includes('做题页主动点击提交后')"),true);
      assert.equal(await ev("document.querySelector('.credential-login').href"),'https://atcoder.jp/login?lang=en');assert.equal(await ev("document.querySelector('.credential-status').textContent.includes('旧密码凭据需要重新授权')"),true);
      const beforeConsent=report.requests.filter(r=>r.method==='PUT'&&r.path.endsWith('/submission-credentials')).length;await ev(vault+".cookieHeader='REVEL_SESSION=fake-browser-session';"+vault+'.save();true');await sleep(150);assert.equal(report.requests.filter(r=>r.method==='PUT'&&r.path.endsWith('/submission-credentials')).length,beforeConsent);
      await ev(vault+'.authorized=true;'+vault+'.save();true');await until(()=>ev(vault+".settings.credentialType==='BROWSER_SESSION'&&!"+vault+'.busy'),'encrypted credential save');assert.equal(await ev(vault+'.cookieHeader'),'');assert.equal(await ev(vault+'.authorized'),false);await shot('credentials-desktop');
      report.checks.push('Legacy password metadata requests reauthorization, login opens the fixed source, and no session is sent without explicit consent.');
      await ev(vault+".cookieHeader='REVEL_SESSION=fake-wrong-account';"+vault+'.authorized=true;'+vault+'.save();true');await until(()=>ev(vault+'.error&&!'+vault+'.busy'),'wrong session identity');assert.equal(await ev(vault+'.settings.version'),1);assert.equal(await ev(vault+'.cookieHeader'),'');assert.equal(await ev(vault+'.error.includes("账号不一致")'),true);
      report.checks.push('A mismatched source account displays a specific error, clears the entered session and preserves the previous authorization.');
      await ev(vault+'.visible=false;true');await sleep(220);await ev(vault+'.open();true');await until(()=>ev(vault+'.settings&&!'+vault+'.loading'),'reopened metadata only');assert.equal(await ev(vault+'.cookieHeader'),'');
      report.checks.push('Owner session cookies are masked, cleared immediately after sending and never refilled; saved state does not imply remote submission availability.');
      credentialSettings.get(uid+':1').version++;await ev(vault+".cookieHeader='REVEL_SESSION=fake-conflicting-session';"+vault+'.authorized=true;'+vault+'.save();true');await until(()=>ev(vault+'.error&&!'+vault+'.busy'),'credential version conflict');assert.equal(await ev(vault+'.cookieHeader'),'');assert.equal(await ev(vault+'.settings.version'),1);await ev(vault+'.visible=false;true');await sleep(220);await ev(vault+'.open();true');await until(()=>ev(vault+'.settings?.version===2'),'reload latest credential version');
      await ev(vault+'.remove();true');await until(()=>ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"),'delete credential confirmation');await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");await until(()=>ev('!'+vault+'.settings.configured&&!'+vault+'.busy'),'credential deletion');assert.equal(await ev(vault+'.settings.version'),3);
      report.checks.push('A stale credential save displays the server conflict and keeps no plaintext; reopening permits deleting the current version after a confirmation.');
      await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(220);assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);assert.equal(await ev("document.querySelector('.remote-credential-dialog .el-dialog__footer').getBoundingClientRect().bottom<innerHeight"),true);await shot('credentials-mobile',true);await ev("document.documentElement.dataset.theme='dark';true");await shot('credentials-dark',true);await ev("document.documentElement.dataset.theme='light';true");
      report.checks.push('Credential dialog and its actions fit mobile and dark themes without overflow.');
      await ev(vault+'.visible=false;true');await sleep(220);credentialDelay=1000;await ev(vault+'.open();true');await sleep(150);uid='paper-bob';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await route('/user-home?uid='+uid);await sleep(1250);credentialDelay=0;assert.equal(await ev("!!document.querySelector('.remote-credential-dialog')"),false);assert.equal(await ev("document.body.textContent.includes('fake-browser-session')"),false);
      report.checks.push('Switching accounts destroys the old credential dialog and ignores delayed credential metadata without exposing plaintext.');
      assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'paper-browser.json'),JSON.stringify(report,null,2));console.log('Credential browser: '+report.checks.length+' workflows; '+report.screenshots.length+' screenshots.');return;
    }
    await ev(panel+".platform='CODEFORCES';"+panel+".handle='tourist';"+panel+'.link();true');await until(()=>ev(panel+'.snapshot.accounts.length===2&&!'+panel+'.busy'),'CF and AtCoder coexist');assert.deepEqual(await ev("Array.from(document.querySelectorAll('.source-platform')).map(e=>e.textContent)"),['AtCoder','Codeforces']);
    report.checks.push('AtCoder and CF accounts coexist in one profile with their own labels and separate rating scales.');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(250);assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);await ev("document.querySelector('.external-accounts').scrollIntoView();true");await shot('atcoder-mobile',true);await ev("document.documentElement.dataset.theme='dark';true");await shot('atcoder-dark',true);await ev("document.documentElement.dataset.theme='light';true");
    report.checks.push('Both source cards and the platform selector fit 390px mobile and dark mode without horizontal page overflow.');
    uid='paper-bob';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await route('/user-home?uid=paper-alice');await until(()=>ev(panel+'?.snapshot?.accounts.length===1&&!'+panel+'.loading'),'public mixed-source profile');assert.equal(await ev(panel+'.owner'),false);assert.equal(await ev(panel+'.snapshot.accounts[0].platform'),'ATCODER');assert.equal(await ev("!!document.querySelector('.external-link')||!!document.querySelector('.source-actions')||!!document.querySelector('.source-proof')"),false);
    report.checks.push('Public viewing shows only the verified AtCoder association and omits all account controls and ownership codes.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'paper-browser.json'),JSON.stringify(report,null,2));console.log('AtCoder browser: '+report.checks.length+' workflows; '+report.screenshots.length+' screenshots.');return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'external') {
    const panel=find('ExternalAccounts');
    await route('/user-home?uid='+uid);await until(()=>ev(panel+'?.snapshot?.uid==='+JSON.stringify(uid)), 'own external account panel');
    assert.equal(await ev(panel+'.owner'),true);assert.equal(await ev("document.querySelectorAll('.external-source').length"),0);
    await ev(panel+".handle='tourist';"+panel+'.link();true');await until(()=>ev(panel+'.snapshot.accounts.length===1&&!'+panel+'.busy'), 'link CF');
    assert.equal(await ev(panel+'.snapshot.accounts[0].solved'),null);assert.equal(await ev("document.querySelector('.source-numbers').textContent.includes('—')"),true);
    await ev(panel+".act('externalProof',1);true");await until(()=>ev("!!document.querySelector('.source-proof code')"), 'ownership instructions');
    assert.equal(await ev("document.querySelector('.source-proof code').textContent"),'HOJ-fixture-proof-for-owner-only');
    await shot('external-proof-desktop');report.checks.push('Owner can link CF and generate a private ownership code; unsynced counts remain unknown rather than zero.');
    await ev(panel+".act('externalVerify',1);true");await until(()=>ev(panel+'.snapshot.accounts[0].verified&&!'+panel+'.busy'), 'verify completed');
    assert.equal(await ev("!!document.querySelector('.source-proof')"),false);assert.equal(await ev(panel+'.snapshot.summary.uniqueSolved'),17);
    await ev("document.querySelector('.source-details').open=true;true");await shot('external-verified-desktop');
    assert.equal(await ev("document.querySelectorAll('.source-coverage meter').length"),8);assert.equal(await ev("document.querySelectorAll('.source-history li').length"),1);
    report.checks.push('Verified records show source counts, explicit dedup totals, rated samples, eight knowledge directions and recent rating events.');
    externalReject=true;await ev(panel+".handle='duplicate';"+panel+'.link();true');await until(()=>ev(panel+'.error.length>0&&!'+panel+'.busy'), 'failed link');assert.equal(await ev(panel+'.handle'),'duplicate');
    externalLinks.get(uid)[0].lastError='CF 暂时不可用，保留已有记录';externalLinks.get(uid)[0].syncState='BACKOFF';externalLinks.get(uid)[0].stale=true;
    await ev(panel+'.load(false);true');await until(()=>ev(panel+'.snapshot.accounts[0].stale&&!'+panel+'.loading'), 'stale cache');assert.equal(await ev(panel+'.snapshot.accounts[0].solved'),8);
    report.checks.push('Failed linking preserves input and previous records; source failures retain cached totals and disclose stale status.');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(200);
    assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);await ev("document.querySelector('.external-accounts').scrollIntoView();true");await shot('external-mobile',true);
    await ev("document.documentElement.dataset.theme='dark';true");await shot('external-dark',true);await ev("document.documentElement.dataset.theme='light';true");
    report.checks.push('Source panel, actions, rating history and coverage fit mobile and dark themes without horizontal page overflow.');
    await ev(panel+'.unlink('+panel+'.snapshot.accounts[0]);true');await until(()=>ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'unlink confirmation');
    const beforeDelete=report.requests.filter(r=>r.method==='DELETE'&&r.path.startsWith('/api/learning/accounts')).length;
    externalDelay=1200;await ev(panel+'.load(false);true');uid='paper-bob';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await route('/user-home?uid='+uid);
    await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");await sleep(1400);externalDelay=0;
    assert.equal(await ev(panel+'.snapshot.uid'),'paper-bob');assert.equal(await ev(panel+'.snapshot.accounts.length'),0);assert.equal(report.requests.filter(r=>r.method==='DELETE'&&r.path.startsWith('/api/learning/accounts')).length,beforeDelete);
    await route('/user-home?uid=paper-alice');await until(()=>ev(panel+'?.snapshot?.uid==="paper-alice"'), 'public verified profile');
    assert.equal(await ev(panel+'.owner'),false);assert.equal(await ev("!!document.querySelector('.external-link')"),false);assert.equal(await ev("!!document.querySelector('.source-actions')"),false);
    assert.equal(await ev(panel+'.snapshot.accounts.length'),1);assert.equal(await ev("document.querySelector('.external-accounts').textContent.includes('HOJ-fixture')"),false);
    report.checks.push('A delayed old-user reply and pending unlink confirmation cannot expose private proof or mutate after account switching; public views expose verified records only.');
    uid='paper-alice';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await until(()=>ev(panel+'.owner&&'+panel+'.snapshot?.accounts.length===1'), 'owner restored');
    await ev(panel+'.unlink('+panel+'.snapshot.accounts[0]);true');await until(()=>ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'leaving-page confirmation');
    await route('/papers');await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");await sleep(250);
    assert.equal(report.requests.filter(r=>r.method==='DELETE'&&r.path.startsWith('/api/learning/accounts')).length,beforeDelete);
    report.checks.push('Confirming a stale unlink dialog after leaving the profile cannot send a mutation from a destroyed component.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'paper-browser.json'),JSON.stringify(report,null,2));console.log('External browser: '+report.checks.length+' workflows passed; '+report.screenshots.length+' screenshots.');return;
  }
  if (mixedVirtualCase) {
    const entrance=find('VirtualPractice'),sheet=find('PaperRun'),assistant=find('AiAssistant');
    await route('/contest/6/problems');await until(()=>ev(entrance+'?.eligible&&!'+entrance+'.loading'),'ended mixed virtual entrance');
    assert.equal(report.requests.filter(r=>r.path==='/api/contests/6/mixed/run').length,0);
    await ev(entrance+'.start();true');await until(()=>ev(sheet+'?.run?.virtual===true'),'new virtual answer sheet');
    const id=await ev(sheet+'.run.id');assert.equal(await ev(sheet+'.run.contestId'),6);assert.equal(await ev(sheet+'.run.virtualRunId'),virtualRuns.get(uid).id);
    assert.equal(report.requests.filter(r=>r.path==='/api/contests/6/mixed/run').length,0);
    assert.equal(await ev("document.body.textContent.includes('虚拟参赛答卷')"),true);
    report.checks.push('The ended mixed entry starts a personal window and routes to its separate answer sheet without calling the formal-contest start endpoint.');
    await checkObjectivePolling();
    await ev(sheet+".select('OBJECTIVE:1');true");await sleep(100);await ev("document.querySelector('.paper-run').scrollIntoView({block:'start'});true");await shot('mixed-virtual-desktop',true);
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await ev("document.documentElement.dataset.theme='dark';document.querySelector('.paper-run').scrollIntoView({block:'start'});true");await shot('mixed-virtual-mobile-dark',true);
    assert.equal(await ev("(()=>{const r=document.querySelector('.paper-run').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth})()"),true);
    report.checks.push('Virtual identity, personal deadline, question navigation and disclosure rules remain visible on desktop and mobile dark layouts.');
    await ev(sheet+'.finish();true');await until(()=>ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"),'confirm virtual hand-in');await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");await until(()=>ev(sheet+'.run.state==="FINISHED"&&!'+sheet+'.saving'),'virtual early hand-in');
    assert.equal(await ev(sheet+'.run.items.filter(i=>i.kind==="OBJECTIVE").some(i=>i.correctAnswers||i.explanationVisible)'),false);
    await ev(assistant+'.show();true');await until(()=>ev(assistant+'.ready&&'+assistant+'.blocked'),'virtual AI remains blocked after hand-in');await ev(assistant+'.close();true');
    report.checks.push('Early hand-in locks the saved answers but keeps standard answers, explanations and AI unavailable until the personal clock ends.');
    virtualRuns.get(uid).endsAt=Date.now()-1;await ev(sheet+'.refresh();true');await until(()=>ev(sheet+'.run.items.filter(i=>i.kind==="OBJECTIVE").every(i=>i.answerVisible)'),'personal deadline releases feedback');
    await route('/contest/6/problems');await until(()=>ev(entrance+'?.run?.paperRunId==='+id+'&&!'+entrance+'.loading'),'virtual result entrance');
    const before=report.requests.filter(r=>r.path.endsWith('/virtual-run')&&r.method==='POST').length;await ev(entrance+'.openPaper();true');await until(()=>ev(sheet+'?.run?.id==='+id),'reopen personal result');assert.equal(report.requests.filter(r=>r.path.endsWith('/virtual-run')&&r.method==='POST').length,before);
    report.checks.push('After the personal deadline feedback is released and the result entrance reopens the same sheet without creating a new window.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'mixed-virtual-browser.json'),JSON.stringify(report,null,2));console.log('Mixed virtual browser: '+report.checks.length+' workflows passed; '+report.screenshots.length+' screenshots.');return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'virtual') {
    const panel=find('VirtualPractice'),assistant=find('AiAssistant');
    aiConfigurations.set(uid,{baseUrl:'https://fixture.example.com/v1',model:'fixture-model',configured:true,hasKey:true});
    await route('/contest/6');await until(()=>ev('!!'+panel+'&&!'+panel+'.loading&&'+panel+'.eligible'),'virtual practice entrance');
    assert.equal(await ev(panel+'.run'),null);assert.equal(report.requests.filter(r=>r.path.endsWith('/virtual-run')&&r.method==='POST').length,0);
    await ev(assistant+'.show();true');await until(()=>ev(assistant+'.ready&&!'+assistant+'.blocked'),'spectator AI available');
    report.checks.push('An ended programming contest shows a deliberate virtual-practice entrance; reading the page does not start a personal clock or block spectator AI.');
    aiDelay=1800;await ev(assistant+".question='开始前的问题';"+assistant+'.send();true');
    virtualLostReply=true;await ev(panel+'.start();true');await until(()=>ev(panel+'.error.includes("丢失")&&!'+panel+'.starting'),'lost start response');
    const originalEndsAt=virtualRuns.get(uid).endsAt;
    await ev(panel+'.start();true');await until(()=>ev(panel+'.active&&'+assistant+'.blocked'),'retry start and invalidate assistant');await sleep(1900);
    const starts=report.requests.filter(r=>r.path.endsWith('/virtual-run')&&r.method==='POST');assert.equal(starts.length,2);assert.equal(starts[0].data.requestId,starts[1].data.requestId);assert.equal(virtualRuns.get(uid).endsAt,originalEndsAt);assert.equal(await ev(assistant+'.messages.length'),0);assert.equal(await ev(assistant+'.pending'),false);
    report.checks.push('A lost start response is retried with the same nonce and fixed deadline; starting clears pending AI context and discards its late reply.');
    await ev(assistant+'.close();true');await route('/papers');await route('/contest/6');await until(()=>ev(panel+'?.active&&!'+panel+'.loading'),'restore active clock');
    assert.equal(await ev(panel+'.run.endsAt'),originalEndsAt);assert.equal(await ev("Array.from(document.querySelectorAll('.virtual-practice button')).some(b=>b.textContent.includes('取消'))"),false);
    await ev("document.querySelector('.virtual-practice').scrollIntoView({block:'center'});true");await shot('virtual-practice-desktop',true);
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await ev("document.documentElement.dataset.theme='dark';document.querySelector('.virtual-practice').scrollIntoView({block:'center'});true");await shot('virtual-practice-mobile-dark',true);
    assert.equal(await ev("(()=>{const r=document.querySelector('.virtual-practice').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth})()"),true);
    report.checks.push('Leaving and reopening the contest preserves its personal deadline; the persistent clock and policy text fit desktop and mobile dark layouts.');
    virtualDelay=800;await ev(panel+'.load();true');uid='paper-bob';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await sleep(1000);assert.equal(await ev(panel+'.run'),null);assert.equal(await ev(assistant+'.open'),false);
    report.checks.push('Changing identity clears the previous virtual clock and ignores delayed reads from the prior account.');
    virtualDelay=0;uid='paper-alice';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await until(()=>ev(panel+'.active&&!'+panel+'.loading'),'return to personal clock');
    virtualRuns.get(uid).endsAt=Date.now()-1;await ev(panel+'.load();true');await until(()=>ev('!'+panel+'.active&&!'+panel+'.loading'),'fixed deadline ends');await ev(assistant+'.show();true');await until(()=>ev(assistant+'.ready&&!'+assistant+'.blocked'),'AI restored after server deadline');
    assert.equal(await ev("document.querySelector('.virtual-practice').textContent.includes('本轮计时已结束')"),true);
    await ev(panel+'.$set('+panel+'.contest,"mixedPaperId",2);true');await sleep(100);assert.equal(await ev("!!document.querySelector('.virtual-practice')"),false);
    report.checks.push('After the server deadline AI becomes available again; switching contest mode tears down the old entrance and its reads.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'virtual-browser.json'),JSON.stringify(report,null,2));console.log('Virtual browser: '+report.checks.length+' workflows passed; '+report.screenshots.length+' screenshots.');return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'ai-context') {
    role='default_user';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");
    aiConfigurations.set(uid,{configured:true,hasKey:true,baseUrl:'https://fixture.example.com/v1',model:'fixture-model',fingerprint:'a'.repeat(64)});
    const assistant=find('AiAssistant');await route('/problem/P1000');await until(()=>ev("!!document.querySelector('.ai-problem-help')"),'program help entrance');
    await ev("document.querySelector('.ai-problem-help').click();true");await until(()=>ev(assistant+'.open&&'+assistant+'.ready&&!!'+assistant+'.availableProblem'),'current problem descriptor');
    assert.equal(await ev(assistant+'.includeProblem'),false);assert.equal(report.requests.filter(row=>row.path==='/api/ai/problem-context').length,0);
    await ev(assistant+".question='只讨论通用概念';"+assistant+'.send();true');await until(()=>ev(assistant+'.messages.length===2&&!'+assistant+'.pending'),'generic chat');assert.equal(report.requests.filter(row=>row.path==='/api/ai/chat').pop().data.problemContext,undefined);
    report.checks.push('The program page has a help entrance, but opening it does not read or send a statement. Generic chat works with no context attached.');
    await ev("document.querySelector('.ai-problem-context .el-checkbox').click();true");await until(()=>ev('!!'+assistant+'.contextPreview&&!'+assistant+'.contextLoading'),'program preview');assert.equal(await ev(assistant+'.messages.length'),0);
    await ev("document.querySelector('.ai-problem-context summary').click();true");assert.equal(await ev("document.querySelector('.ai-problem-context img')===null&&document.querySelector('.ai-problem-context pre').textContent.includes('<img')"),true);
    await ev(assistant+".question='解释这道题的输入';"+assistant+'.send();true');await until(()=>ev(assistant+'.messages.length===2&&!'+assistant+'.pending'),'program context reply');
    const submitted=report.requests.filter(row=>row.path==='/api/ai/chat').pop().data;assert.equal(submitted.problemContext.reference.kind,'PROGRAMMING');assert.equal(submitted.problemContext.reference.problemId,'P1000');assert.deepEqual(Object.keys(submitted.problemContext).sort(),['fingerprint','reference']);assert.equal(JSON.stringify(submitted).includes('SERVER_SOLUTION_NOT_FOR_AI'),false);
    await shot('ai-problem-context-desktop',true);report.checks.push('Opt-in clears old history; the preview escapes markup and chat sends only the verified reference/fingerprint, never browser statement text or hidden solution fields.');
    await route('/objective/run/11');await until(()=>ev(find('ObjectiveRun')+'?.run?.questions.length===2&&'+assistant+'.availableProblem?.reference.kind==="OBJECTIVE_RUN"'),'objective descriptor');
    assert.equal(await ev(assistant+'.messages.length'),0);aiContextDelay=900;await ev(assistant+'.setIncludeProblem(true);true');await until(()=>ev(assistant+'.contextLoading'),'pending objective preview');
    await ev("document.querySelectorAll('.question-nav .el-button')[1].click();true");await until(()=>ev(assistant+'.availableProblem?.reference.questionKey==="5"'),'same-route question changes');await sleep(1100);aiContextDelay=0;assert.equal(await ev(assistant+'.contextPreview'),null);assert.equal(await ev(assistant+'.includeProblem'),false);
    await ev(assistant+'.setIncludeProblem(true);true');await until(()=>ev(assistant+'.contextPreview?.reference.questionKey==="5"'),'second objective preview');
    aiDelay=900;await ev(assistant+".question='这道多选如何理解';"+assistant+'.send();true');await until(()=>ev(assistant+'.pending'),'pending objective chat');await ev("document.querySelectorAll('.question-nav .el-button')[0].click();true");await sleep(1100);aiDelay=0;assert.equal(await ev(assistant+'.messages.length'),0);assert.equal(await ev(assistant+'.contextPreview'),null);
    report.checks.push('Within one objective run, switching questions clears consent/history and discards both a delayed preview and a delayed model response.');
    runs.set(501,{id:501,uid,paper:clone(papers[0]),answers:{},submissions:[],state:'IN_PROGRESS',polls:0});await route('/papers/run/501');await until(()=>ev(find('PaperRun')+'?.run?.id===501&&'+assistant+'.availableProblem?.reference.questionKey==="OBJECTIVE:4"'),'paper objective context');
    await ev(assistant+'.setIncludeProblem(true);true');await until(()=>ev('!!'+assistant+'.contextPreview'),'paper fill-blank preview');assert.equal(await ev(assistant+'.contextPreview.material.blanks.length'),2);
    await ev("document.querySelectorAll('.question-numbers .el-button')[1].click();true");await until(()=>ev(assistant+'.availableProblem?.reference.questionKey==="PROGRAMMING:1"'),'paper program context');assert.equal(await ev(assistant+'.includeProblem'),false);
    await ev(assistant+'.setIncludeProblem(true);true');await until(()=>ev('!!'+assistant+'.contextPreview'),'paper program preview');await ev(assistant+".question='分析这道编程题';"+assistant+'.send();true');await until(()=>ev(assistant+'.messages.length===2&&!'+assistant+'.pending'),'paper program reply');await sleep(1400);assert.equal(await ev(assistant+'.messages.length'),2);
    report.checks.push('Mixed-paper fill blanks and programming items expose their own run/item references; switching items clears old context, while ordinary result polling preserves the current conversation.');
    aiContextChatRejected=true;await ev(assistant+".question='变化后的题面';"+assistant+'.send();true');await until(()=>ev(assistant+'.error.includes("题面已变化")&&!'+assistant+'.pending'),'stale context rejected');assert.equal(await ev(assistant+'.contextPreview'),null);assert.equal(await ev(assistant+'.includeProblem'),false);assert.equal(await ev(assistant+'.messages.length'),0);assert.equal(await ev(assistant+'.question'),'变化后的题面');
    await ev(assistant+'.setIncludeProblem(true);true');await until(()=>ev('!!'+assistant+'.contextPreview'),'fresh context after conflict');await ev("document.querySelector('.ai-context-refresh').click();true");await until(()=>ev('!!'+assistant+'.contextPreview&&!'+assistant+'.contextLoading'),'explicit context refresh');await ev(assistant+'.send();true');await until(()=>ev(assistant+'.messages.length===2&&!'+assistant+'.pending'),'resend after explicit refresh');
    report.checks.push('A context conflict removes stale material/history and preserves the question without retrying; the explicit refresh action obtains fresh consent material before a new send.');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await ev("document.documentElement.dataset.theme='dark';document.querySelector('.ai-problem-context summary').click();true");await shot('ai-problem-context-mobile-dark',true);assert.equal(await ev("(()=>{const r=document.querySelector('.ai-popup').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight})()"),true);
    assert.equal(await ev("(()=>{const b=document.querySelector('.ai-popup header button'),r=b.getBoundingClientRect();return document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)?.closest('.ai-popup')!==null})()"),true);
    aiContextRejected=true;await ev(assistant+'.setIncludeProblem(false);'+assistant+'.setIncludeProblem(true);true');await until(()=>ev(assistant+'.error.includes("撤回")&&!'+assistant+'.contextLoading'),'withdrawn preview');assert.equal(await ev(assistant+'.includeProblem'),false);
    aiBlocked=true;await ev(assistant+'.loadSettings(true);true');await until(()=>ev(assistant+'.blocked'),'contest restriction');assert.equal(await ev(assistant+'.contextPreview'),null);assert.equal(await ev("document.querySelector('.ai-problem-context .el-checkbox').classList.contains('is-disabled')"),true);
    uid='paper-bob';await ev("document.getElementById('app').__vue__.$store.commit('changeUserToken','new-context-session');document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await until(()=>ev('!'+assistant+'.open'),'account context cleared');assert.equal(await ev(assistant+'.contextPreview'),null);assert.equal(await ev(assistant+'.includeProblem'),false);assert.equal(await ev(assistant+'.messages.length'),0);
    report.checks.push('The attachment fits mobile/dark layouts; withdrawn previews release loading, contest restrictions clear/disable sharing, and a new login cannot inherit old context or history.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'ai-problem-context-browser.json'),JSON.stringify(report,null,2));console.log('Problem-context browser: '+report.checks.length+' workflows / '+report.screenshots.length+' screenshots.');return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'ai') {
    const assistant=find('AiAssistant');
    await ev(assistant+'.show();true');await until(()=>ev(assistant+'.ready'), 'AI settings load');
    assert.equal(await ev(assistant+'.tab'),'settings');
    await ev(assistant+".form={baseUrl:'https://fixture.example.com/v1',model:'fixture-model',apiKey:'fake-browser-only-api-key'};"+assistant+'.saveSettings();true');
    await until(()=>ev(assistant+'.settings.configured&&'+assistant+'.tab===\'chat\''),'save AI model');
    assert.equal(await ev(assistant+'.form.apiKey'),'');
    assert.equal(await ev("JSON.stringify({...localStorage,...sessionStorage}).includes('fake-browser-only-api-key')"),false);
    report.checks.push('Model setup clears the key after saving and never places it in browser storage.');
    assert.equal(await ev(assistant+'.includeExternalProfile'),false);
    await ev(assistant+".question='循环边界如何判断？';"+assistant+'.includeProfile=true;'+assistant+'.includeExternalProfile=true;'+assistant+'.send();true');
    await until(()=>ev(assistant+'.messages.length===2&&!'+assistant+'.pending'),'AI reply');
    assert.equal(await ev("document.querySelector('.ai-history img')===null"),true);
    assert.equal(report.requests.filter(row=>row.path==='/api/ai/chat').pop().data.includeProfile,true);
    assert.equal(report.requests.filter(row=>row.path==='/api/ai/chat').pop().data.includeExternalProfile,true);
    await shot('ai-learning-dialog',true);
    aiReject=true;await ev(assistant+".question='失败后保留问题';"+assistant+'.send();true');
    await until(()=>ev(assistant+'.error.includes(\'暂时不可用\')&&!'+assistant+'.pending'),'provider failure');
    assert.equal(await ev(assistant+'.question'),'失败后保留问题');assert.equal(await ev(assistant+'.messages.length'),2);
    report.checks.push('Chat submits only bounded text plus opt-in self profile, renders returned markup as text, and preserves the draft after failure.');
    const currentModel=aiConfigurations.get(uid);const previousFingerprint=currentModel.fingerprint;
    await ev(assistant+'.loadSettings(true);true');await until(()=>ev(assistant+'.ready'),'same model quiet read');assert.equal(await ev(assistant+'.messages.length'),2);
    aiDelay=900;await ev(assistant+".question='旧模型尚未完成';"+assistant+'.send();true');await until(()=>ev(assistant+'.pending'),'in-flight model call');
    currentModel.model='changed-from-another-page';currentModel.fingerprint=(++aiModelRevision).toString(16).padStart(64,'0');
    await ev(assistant+'.loadSettings(true);true');await until(()=>ev(assistant+'.settings.model==="changed-from-another-page"&&!'+assistant+'.pending'),'configuration refresh');await sleep(1100);aiDelay=0;
    assert.equal(await ev(assistant+'.messages.length'),0);assert.equal(await ev(assistant+'.includeProfile'),false);assert.equal(await ev(assistant+'.includeExternalProfile'),false);assert.equal(await ev(assistant+'.form.apiKey'),'');assert.equal(await ev(assistant+'.error.includes("配置已更新")'),true);
    assert.equal(report.requests.filter(row=>row.path==='/api/ai/chat').pop().data.modelFingerprint,previousFingerprint);
    await ev(assistant+".question='按新配置开始';"+assistant+'.send();true');await until(()=>ev(assistant+'.messages.length===2&&!'+assistant+'.pending'),'new configuration chat');assert.equal(report.requests.filter(row=>row.path==='/api/ai/chat').pop().data.messages.length,1);
    report.checks.push('Quiet settings reads preserve same-model history; changing configuration clears old history and profile consent, suppresses an in-flight reply, and starts the next request with the newly confirmed fingerprint.');
    await route('/contest/6/rank');await until(()=>ev(assistant+'.ready'), 'spectator AI settings');
    assert.equal(await ev(assistant+'.blocked'),false);
    await ev(assistant+".question='旁观期间的学习问题';"+assistant+'.send();true');await until(()=>ev(assistant+'.messages.length===2&&!'+assistant+'.pending'), 'spectator AI reply');
    await route('/papers');await until(()=>ev(assistant+'.ready'), 'return to practice');
    report.checks.push('An unrelated spectator can use AI on a contest route; restrictions follow the server participant flag rather than the current page.');
    aiBlocked=true;await ev(assistant+'.loadSettings(true);true');await until(()=>ev(assistant+'.blocked'), 'server competition restriction');
    assert.equal(await ev("document.querySelector('.ai-popup textarea').disabled"),true);assert.equal(await ev(assistant+'.messages.length'),0);
    aiBlocked=false;await ev(assistant+'.loadSettings(true);true');await until(()=>ev('!'+assistant+'.blocked'),'competition ends');
    aiDelay=1200;await ev(assistant+".question='旧题问题';"+assistant+'.send();true');await route('/problem');await sleep(1300);assert.equal(await ev(assistant+'.messages.length'),0);
    await ev(assistant+".question='旧账号问题';"+assistant+'.send();true');uid='paper-bob';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");await sleep(1300);
    assert.equal(await ev(assistant+'.open'),false);assert.equal(await ev(assistant+'.messages.length'),0);assert.equal(await ev(assistant+'.form.apiKey'),'');assert.equal(await ev(assistant+'.includeExternalProfile'),false);
    report.checks.push('Server competition restrictions clear the conversation; delayed replies are discarded after a route or identity change.');
    aiDelay=0;await ev(assistant+'.show();true');await until(()=>ev(assistant+'.ready'), 'second identity settings');assert.equal(await ev(assistant+'.settings.configured'),false);
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
    await shot('ai-settings-mobile',true);assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);
    await ev("document.documentElement.dataset.theme='dark';true");await shot('ai-settings-dark',true);
    assert.equal(await ev("(()=>{const r=document.querySelector('.ai-popup').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight})()"),true);
    report.checks.push('Separate users receive separate settings; model configuration and controls fit mobile and dark mode.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'paper-browser.json'),JSON.stringify(report,null,2));console.log('AI browser: '+report.checks.length+' workflows passed; '+report.screenshots.length+' screenshots.');return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'recommendations') {
    uid='rec-user';role='default_user';await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");
    const panel=find('LearningRecommendations');await route('/user-home?uid='+uid);await until(()=>ev(panel+'?.plan?.items.length===2'),'own recommendations');
    assert.equal(await ev("document.querySelectorAll('.recommendation-list li').length"),2);await ev(panel+'.explain();true');await until(()=>ev(panel+'.aiError.includes("配置")&&!'+panel+'.pending'),'model-free rules stay available');assert.equal(await ev(panel+'.plan.items.length'),2);
    report.checks.push('Own-page rule candidates work without a model; missing configuration leaves the real candidate list usable.');
    const savedPanel=find('SavedTrainingPlans');
    trainingSaveLostReply=true;const modelCalls=report.requests.filter(r=>r.path==='/api/ai/recommendations/explain').length;
    await ev(panel+'.saveName="本周二分与复杂度";'+panel+'.save();true');await until(()=>ev(panel+'.saveError.includes("回执")&&!'+panel+'.saving'),'lost save receipt');
    await ev(panel+'.save();true');await until(()=>ev(savedPanel+'.items.length===1&&!'+panel+'.saving'),'saved plan retry');
    assert.equal(trainingPlans.size,1);const saves=report.requests.filter(r=>r.path==='/api/learning/training-plans'&&r.method==='POST');assert.equal(saves[0].data.requestId,saves[1].data.requestId);assert.equal(report.requests.filter(r=>r.path==='/api/ai/recommendations/explain').length,modelCalls);
    assert.deepEqual(saves[0].data.selection.candidateIds,['PROGRAMMING:1','OBJECTIVE:2']);assert.equal(await ev("document.querySelectorAll('.saved-training-list li').length"),2);
    report.checks.push('Saving does not invoke a model; retrying a lost receipt preserves its nonce and produces one persisted candidate set in its original order.');
    await route('/user-home?uid=another-user');await route('/user-home?uid='+uid);await until(()=>ev(savedPanel+'?.items.length===1'),'saved list reopens');
    trainingProgress=true;trainingHidden=true;await ev(savedPanel+'.load();true');await until(()=>ev(savedPanel+'.items[0]?.solved===1'),'recorded progress');
    assert.equal(await ev("document.querySelectorAll('.saved-training-list a,.saved-training-list button').length"),1);assert.equal(await ev("document.querySelector('.saved-training-list').textContent.includes('复杂度概念练习')"),false);
    report.checks.push('Saved plans survive navigation, refresh recorded progress and hide both metadata and links for withdrawn tasks.');
    await ev(savedPanel+'.rename('+savedPanel+'.items[0]);true');await until(()=>ev("!!document.querySelector('.el-message-box__input input')"),'rename dialog');
    await ev("(()=>{let input=document.querySelector('.el-message-box__input input');input.value='重新命名的训练';input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('.el-message-box__btns .el-button--primary').click()})()");
    await until(()=>ev(savedPanel+'.items[0]?.name==="重新命名的训练"'),'renamed plan');
    await ev(savedPanel+'.change('+savedPanel+'.items[0],"ARCHIVED");true');await until(()=>ev('!'+savedPanel+'.busy&&'+savedPanel+'.items.length===0'),'archive');
    await ev(savedPanel+'.switchState("ARCHIVED");true');await until(()=>ev(savedPanel+'.items.length===1'),'archived tab');await ev(savedPanel+'.change('+savedPanel+'.items[0],"ACTIVE");true');await until(()=>ev('!'+savedPanel+'.busy'),'restore');
    await ev(savedPanel+'.switchState("ACTIVE");true');await until(()=>ev(savedPanel+'.items.length===1'),'active restored');
    const stored=[...trainingPlans.values()][0];stored.revision++;await ev(savedPanel+'.change('+savedPanel+'.items[0],"ARCHIVED");true');await until(()=>ev(savedPanel+'.error.includes("刷新")'),'revision conflict');assert.equal(stored.state,'ACTIVE');await ev(savedPanel+'.load();true');await until(()=>ev(savedPanel+'.items[0]?.revision==='+stored.revision),'conflict refresh');
    report.checks.push('Rename uses its dialog; archive and restore keep the candidate set, and revision conflicts require fresh state.');
    trainingReadDelay=900;stored.name='旧会话内容';const beforeRead=report.requests.length;await ev(savedPanel+'.load();true');await until(()=>report.requests.slice(beforeRead).some(r=>r.path==='/api/learning/training-plans'),'delayed list requested');
    stored.name='新会话清单';trainingReadDelay=0;await ev("document.getElementById('app').__vue__.$store.commit('changeUserToken','training-new-session');true");await until(()=>ev(savedPanel+'.items[0]?.name==="新会话清单"'),'same-user auth epoch');await sleep(1100);assert.equal(await ev(savedPanel+'.items[0].name'),'新会话清单');
    report.checks.push('A late saved-list response from a previous authentication epoch cannot replace the same user’s new session state.');
    await until(()=>ev(panel+'.plan&&!'+panel+'.loading'),'current-session recommendations after read cooldown');
    aiConfigurations.set(uid,{configured:true,hasKey:true,baseUrl:'https://fixture.example.com/v1',model:'fixture-model',fingerprint:'c'.repeat(64)});await ev(panel+'.explain();true');await until(()=>ev('!!'+panel+'.explanation&&!'+panel+'.pending'),'explanation');
    assert.equal(await ev("document.querySelector('.recommendation-summary img')===null"),true);assert.equal(report.requests.filter(r=>r.path==='/api/ai/recommendations/explain').pop().data.includeProfile,false);await shot('recommendations-desktop');
    await ev(panel+'.includeProfile=true;'+panel+'.includeExternalProfile=true;'+panel+'.explain();true');await until(()=>ev('!'+panel+'.pending'),'explicit profile consent');const explained=report.requests.filter(r=>r.path==='/api/ai/recommendations/explain').pop().data;assert.equal(explained.includeExternalProfile,true);assert.deepEqual(explained.candidateIds,['PROGRAMMING:1','OBJECTIVE:2']);
    report.checks.push('AI explanations preserve server candidate identities, display text safely and send external evidence only after explicit selection.');
    recommendationReject=true;await ev(panel+'.explain();true');await until(()=>ev(panel+'.aiError.includes("刷新")&&!'+panel+'.pending'),'stale candidate error');assert.equal(await ev(panel+'.plan.items.length'),2);
    recommendationDelay=1000;await ev(panel+'.explain();true');await ev(panel+'.filters.source="CODEFORCES";'+panel+'.sourceChanged();'+panel+'.load();true');await until(()=>ev(panel+'.plan?.filters.source==="CODEFORCES"'),'new source');await sleep(1200);recommendationDelay=0;assert.equal(await ev(panel+'.explanation'),null);assert.equal(await ev(panel+'.pending'),false);
    report.checks.push('Stale-plan errors keep rule candidates; changing filters cancels old explanation presentation and releases the loading state.');
    await ev(panel+'.filters.source="HOJ";'+panel+'.sourceChanged();'+panel+'.load();true');await until(()=>ev(panel+'.plan?.items.length===2'),'objective recommendation');
    await ev("window.__recommendationPush=document.getElementById('app').__vue__.$router.push;document.getElementById('app').__vue__.$router.push=(value)=>{window.__recommendationRoute=value;return Promise.resolve()};"+panel+'.start('+panel+'.plan.items[1]);true');await until(()=>ev(panel+'.starting===null&&window.__recommendationRoute==="/objective/run/901"'),'objective practice route');await ev("document.getElementById('app').__vue__.$router.push=window.__recommendationPush;true");assert.equal(report.requests.filter(r=>r.path==='/api/objective/runs').pop().data.questionId,2);
    report.checks.push('Objective recommendations use the existing idempotent practice-start endpoint and navigate to that run.');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await ev("document.querySelector('.learning-recommendations').scrollIntoView();true");await shot('recommendations-mobile',true);await ev("document.documentElement.dataset.theme='dark';true");await shot('recommendations-dark',true);assert.equal(await ev("(()=>{let r=document.querySelector('.learning-recommendations').getBoundingClientRect();return r.left>=0&&r.right<=innerWidth})()"),true);
    report.checks.push('The filters, candidate rows and model controls fit a 390px viewport in light and dark mode.');
    await ev("document.querySelector('.saved-training').scrollIntoView();true");await shot('saved-training-mobile-dark',true);assert.equal(await ev("document.querySelector('.saved-training').getBoundingClientRect().right<=innerWidth"),true);
    await ev(savedPanel+'.remove('+savedPanel+'.items[0]);true');await until(()=>ev("!!document.querySelector('.el-message-box')"),'delete dialog');await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");await until(()=>ev(savedPanel+'.items.length===0&&!'+savedPanel+'.busy'),'deleted plan');assert.equal(stored.state,'DELETED');
    report.checks.push('Mobile dark layout fits its viewport and deleting a plan requires the named confirmation.');
    recommendationReadDelay=900;await ev(panel+'.load();true');await route('/user-home?uid=another-user');await sleep(1100);recommendationReadDelay=0;assert.equal(await ev("!!document.querySelector('.learning-recommendations')"),false);
    await route('/user-home?uid='+uid);await until(()=>ev(panel+'?.plan?.items.length===2'),'restore owner');aiBlocked=true;await ev(panel+'.load();true');await until(()=>ev(panel+'.error.includes("参赛")'),'server competition denial');assert.equal(await ev(panel+'.plan'),null);
    report.checks.push('Other users never receive own recommendation controls, late reads are ignored after navigation and server competition denials cannot be bypassed by changing source.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'recommendation-browser.json'),JSON.stringify(report,null,2));console.log('Recommendations browser: '+report.checks.length+' workflows passed; '+report.screenshots.length+' screenshots.');return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'learning') {
    for (const [state, label] of [['RUNNING', '正在同步'], ['BACKOFF', '稍后重试'], ['IDLE', '']]) {
      await route('/user-home?uid=refresh-' + state);
      await until(() => ev(find('AbilityProfile') + '?.profile?.uid===' + JSON.stringify('refresh-' + state)), 'refresh state ' + state);
      assert.equal(await ev(find('AbilityProfile') + '.profile.overall.solved'), 45);
      if (label) assert.equal(await ev("document.querySelector('.ability-scope').textContent.includes(" + JSON.stringify(label) + ')'), true);
      else assert.equal(await ev("/正在同步|稍后重试/.test(document.querySelector('.ability-scope').textContent)"), false);
      await ev(find('AbilityProfile') + '.selectSource("CODEFORCES");true');
      assert.equal(await ev(find('AbilityProfile') + '.profile.overall.solved'), 10);
      if (label) assert.equal(await ev("document.querySelector('.ability-scope').textContent.includes(" + JSON.stringify(label) + ')'), true);
      if (state === 'RUNNING') await shot('learning-refresh-in-progress');
    }
    report.checks.push('Combined and source profiles retain cached counts while showing refresh progress or backoff, then clear the warning after completion.');
    await route('/user-home?uid=alice');
    await until(() => ev(find('AbilityProfile')+'?.profile?.uid===\'alice\''), 'learning profile');
    assert.equal(await ev(find('AbilityProfile')+'.source'),'ALL');
    assert.equal(await ev("document.querySelector('.ability-modes button').disabled"),true);
    assert.equal(await ev(find('AbilityProfile')+'.profile.overall.solved'),45);
    assert.equal(await ev("document.querySelector('.ability-evidence thead').textContent.includes('难度 P75')"),false);
    await shot('learning-combined-desktop');
    await ev(find('AbilityProfile')+'.selectSource("CODEFORCES");true');
    assert.equal(await ev(find('AbilityProfile')+'.profile.overall.solved'),10);
    assert.equal(await ev("document.querySelector('.ability-scope').textContent.includes('个人提交')"),true);
    await shot('learning-cf-desktop');
    await ev(find('AbilityProfile')+'.selectSource("ATCODER");true');
    assert.equal(await ev("!!document.querySelector('.ability-content svg')"),false);
    assert.equal(await ev("document.querySelector('.ability-unmapped').textContent.includes('算法分类')"),true);
    assert.equal(await ev(find('AbilityProfile')+'.profile.overall.representativeRating'),1350);
    await shot('learning-atcoder-unmapped');
    report.checks.push('Combined coverage disables mixed-platform difficulty; individual source views retain their own metrics and unknown taxonomy is shown without zero-value radar claims.');
    await ev(find('AbilityProfile')+'.selectSource("HOJ");true');
    assert.equal(await ev("document.querySelectorAll('.ability-profile tbody tr').length"),8);
    assert.equal(await ev("document.querySelectorAll('.radar-point').length"),3);
    assert.equal(await ev("!!document.querySelector('.radar-area')"),false);
    assert.equal(await ev("document.querySelectorAll('.axis-value').length"),8);
    assert.equal(await ev("document.querySelector('.ability-profile').textContent.includes('评级样本不足')"),true);
    await shot('learning-profile-desktop');
    await ev("Array.from(document.querySelectorAll('.ability-modes button')).find(n=>n.textContent==='编程覆盖').click();true");
    assert.equal(await ev("document.querySelectorAll('.radar-point').length"),8);
    assert.equal(await ev("!!document.querySelector('.radar-area')"),true);
    report.checks.push('Eight-axis profile distinguishes known difficulty from missing ratings, shows real sample counts and separate objective evidence, and switches to programming coverage.');
    await route('/user-home?uid=stale');
    await until(() => report.requests.some(request=>request.path==='/api/learning/users/stale/evidence'), 'delayed profile');
    await route('/user-home?uid=empty');
    await until(() => ev("!!document.querySelector('.ability-empty')"), 'empty profile');
    await sleep(1300);assert.equal(await ev(find('AbilityProfile')+'.profile.uid'),'empty');
    assert.equal(await ev("!!document.querySelector('.ability-content svg')"),false);
    rejectLearning=true;await ev(find('AbilityProfile')+'.load();true');
    await until(() => ev(find('AbilityProfile')+'.error.length>0'), 'profile failure');
    await ev(find('AbilityProfile')+'.load();true');await until(() => ev(find('AbilityProfile')+'.profile?.uid===\'empty\''), 'profile retry');
    report.checks.push('No-evidence profiles show an empty state; delayed previous-user data is ignored, and failed requests offer a working retry.');
    await route('/admin/problem/tag');
    await until(() => ev(find('ProblemTag')+'?.catalogReady&&'+find('ProblemTag')+'.tagsAndClassificationList.length===9'), 'preset tag catalogue');
    await ev(find('ProblemTag')+'.activeTagClassificationIdList=[-1,...Array.from({length:8},(_,i)=>200+i)];true');
    await sleep(250);
    assert.equal(await ev("document.querySelectorAll('.preset-tag').length"),94);
    assert.equal(await ev("document.querySelectorAll('.preset-tag .el-tag__close').length"),0);
    assert.equal(await ev("document.querySelectorAll('.preset-label').length"),8);
    await ev(find('ProblemTag')+'.openTagDialog("update",{id:1000,name:"算法基础"});true');
    assert.equal(await ev(find('ProblemTag')+'.addTagDialogVisible'),false);
    await ev(find('ProblemTag')+'.openTagDialog("add",null);'+find('ProblemTag')+'.tag.name="自定义拓展";'+find('ProblemTag')+'.upsertTag();true');
    await until(() => ev('!'+find('ProblemTag')+'.addTagDialogVisible'), 'new custom tag');
    assert(report.requests.some(request=>request.path==='/api/admin/tag'&&request.method==='POST'&&request.data.name==='自定义拓展'));
    await shot('learning-problem-tags');
    report.checks.push('All 94 preset problem tags and eight classes have no destructive UI; custom tag creation remains available.');
    await route('/admin/training/category');
    await until(() => ev(find('TrainingCategory')+'?.catalogReady&&'+find('TrainingCategory')+'.categoryList.length===95'), 'training catalogue');
    assert.equal(await ev("document.querySelectorAll('.preset-group').length"),8);
    assert.equal(await ev("document.querySelectorAll('.preset-category').length"),94);
    assert.equal(await ev("document.querySelectorAll('.preset-category .el-tag__close').length"),0);
    await ev(find('TrainingCategory')+'.openCategoryDialog("update",{id:3000,name:"算法基础"});true');
    assert.equal(await ev(find('TrainingCategory')+'.addCategoryDialogVisible'),false);
    await ev(find('TrainingCategory')+'.openCategoryDialog("update",'+find('TrainingCategory')+'.customCategories[0]);'+find('TrainingCategory')+'.category.name="更新自定义训练";'+find('TrainingCategory')+'.upsertCategory();true');
    await until(() => ev('!'+find('TrainingCategory')+'.addCategoryDialogVisible'), 'update custom training category');
    assert.equal(categoryFixtures.find(category=>category.id===9002).name,'更新自定义训练');
    await shot('learning-training-tags');
    report.checks.push('Training categories share the same eight groups and 94 protected topics while existing custom categories remain editable.');
    await route('/user-home?uid=alice');await until(() => ev(find('AbilityProfile')+'?.profile?.uid===\'alice\''), 'return to profile');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
    await sleep(300);assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);
    assert.equal(await ev("(()=>{const card=document.querySelector('.ability-profile').getBoundingClientRect(),chart=document.querySelector('.ability-content svg').getBoundingClientRect();return chart.left>=card.left&&chart.right<=card.right})()"),true,'mobile chart stays inside the card');
    await ev("document.querySelector('.ability-profile').scrollIntoView();true");await shot('learning-profile-mobile',true);
    await ev("document.documentElement.dataset.theme='dark';true");await shot('learning-profile-dark',true);
    assert.equal(await ev("document.querySelector('.ability-table-wrap').scrollWidth>=document.querySelector('.ability-table-wrap').clientWidth"),true);
    report.checks.push('The learning chart, mode controls and evidence table render on mobile and in dark mode without page overflow.');
    assert.deepEqual(report.errors,[]);report.validated=true;fs.writeFileSync(path.join(output,'paper-browser.json'),JSON.stringify(report,null,2));console.log('Learning browser: '+report.checks.length+' workflows passed; '+report.screenshots.length+' screenshots.');return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'authoring-steps') {
    const click = async (selector, label) => {
      assert.equal(await ev("(()=>{const button=Array.from(document.querySelectorAll(" + JSON.stringify(selector + ' button') + ")).find(n=>n.getBoundingClientRect().height&&!n.disabled&&n.textContent.trim()===" + JSON.stringify(label) + ");if(!button)return false;button.click();return true})()"), true, 'visible button: ' + label);
      await sleep(100);
    };
    const unloadProtected = () => ev("(()=>{const e=new Event('beforeunload',{cancelable:true});window.dispatchEvent(e);return e.defaultPrevented})()");
    const confirm = async discard => {
      await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'unsaved editor confirmation');
      await click('.el-message-box__btns', discard ? '放弃修改' : '继续编辑');
      await until(() => ev("!document.querySelector('.el-message-box__btns')?.getBoundingClientRect().height"), 'close confirmation');
    };
    await route('/admin/problems');
    await until(() => ev(find('QuestionBank') + '&&!'+find('ProblemList')+'.loading'), 'question bank');
    const bank = find('QuestionBank'), objective = bank + '.$refs.creator.$refs.objective';
    await ev(bank + ".$refs.creator.select('TRUE_FALSE');true");
    assert.equal(await ev(objective + '.hasUnsavedAuthoring()'), false);
    await ev(objective + '.closeQuestion();true');
    await until(() => ev('!' + objective + '.questionDialog'), 'close unchanged editor');
    assert.equal(await ev("!!document.querySelector('.el-message-box__btns')"), false);
    await ev(bank + ".$refs.creator.select('TRUE_FALSE');true");
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    assert.equal(await ev(objective + '.questionStep'), 0);
    assert.equal(await ev(objective + '.formError'), '请填写标题和题干。');
    await ev(objective + ".question.title='分步判断题';" + objective + ".question.stem='二分查找要求有序。';true");
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    assert.equal(await ev(objective + '.questionStep'), 1);
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    assert.equal(await ev(objective + '.questionStep'), 1);
    assert.equal(await ev(objective + '.formError'), '请选择标准答案。');
    await ev(objective + ".question.correctAnswers=['TRUE'];true");
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    assert.equal(await ev(objective + '.questionStep'), 2);
    assert.equal(await ev(objective + '.standalone'), false);
    await click('.objective-editor-dialog .authoring-footer', '上一步');
    assert.deepEqual(await ev(objective + '.question.correctAnswers'), ['TRUE']);
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    assert.equal(await unloadProtected(), true);
    await ev(objective + '.closeQuestion();true'); await confirm(false);
    assert.equal(await ev(objective + '.question.title'), '分步判断题');
    assert.equal(await ev(objective + '.questionDialog'), true);
    await shot('authoring-step-publication');
    rejectNextQuestion = true;
    await click('.objective-editor-dialog .authoring-footer', '保存到题库');
    await until(() => ev('!'+objective+'.saving&&!!'+objective+'.formError'), 'failed question save');
    assert.equal(await ev(objective + '.questionDialog'), true);
    assert.deepEqual(await ev(objective + '.question.correctAnswers'), ['TRUE']);
    await click('.objective-editor-dialog .authoring-footer', '保存到题库');
    await until(() => ev('!'+objective+'.questionDialog'), 'retry question save');
    assert.equal(await unloadProtected(), false);
    const questionPayload = report.requests.filter(r=>r.path==='/api/admin/objective/questions'&&r.method==='POST').pop().data;
    assert.equal(questionPayload.published, false); assert.equal(questionPayload.practiceEnabled, false);
    report.checks.push('Objective steps validate missing content/answers, preserve back navigation, default to confidential, protect cancel/reload, and retain input after a failed save.');

    await ev(bank + ".$refs.creator.select('MULTIPLE_CHOICE');true");
    await ev(objective + ".question.title='不丢失的录题内容';true");
    await ev("void document.getElementById('app').__vue__.$router.push('/admin/papers');true");
    await confirm(false);
    assert.equal(await ev("document.getElementById('app').__vue__.$route.path"), '/admin/problems');
    assert.equal(await ev(objective + '.question.title'), '不丢失的录题内容');
    await ev("void document.getElementById('app').__vue__.$router.push('/admin/papers');true");
    await confirm(true);
    await until(() => ev('!!'+find('CollectionAdmin')), 'confirmed route change');
    assert.equal(await unloadProtected(), false);
    report.checks.push('Switching admin pages asks before discarding edits; cancelling retains the form and confirming unregisters its unload guard.');

    const paper = find('PaperAdmin');
    await ev(paper+'.create();true');
    await click('.paper-editor-dialog .authoring-footer', '下一步');
    assert.equal(await ev(paper+'.step'), 0);
    await ev(paper+".form.title='分步混合试卷';"+paper+".form.description='保留顺序、分值与内嵌新题';true");
    await click('.paper-editor-dialog .authoring-footer', '下一步');
    await click('.paper-editor-dialog .authoring-footer', '下一步');
    assert.equal(await ev(paper+'.step'), 1);
    const creator = paper+'.$refs.creator', inPaper = creator+'.$refs.objective';
    await ev(creator+".select('FILL_BLANK');true");
    await ev(inPaper+".question.title='逐空计分';"+inPaper+".question.stem='1+2=____';true");
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    assert.equal(await ev(inPaper+'.questionStep'), 1);
    await ev(inPaper+".question.blanks[0].answers=['3'];"+inPaper+'.question.blanks[0].score=5;true');
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    await click('.objective-editor-dialog .authoring-footer', '保存到题库');
    await until(() => ev(paper+'.form.items.length===1'), 'add inline blank');
    await ev(creator+".select('PROGRAMMING');true");
    const programEditor = creator+'.$refs.program';
    await until(() => ev(programEditor+'?.authoringReady&&'+programEditor+'.allLanguage.length===1'), 'program editor initialization');
    await sleep(400);
    assert.equal(await ev(programEditor+'.hasUnsavedAuthoring()'), false);
    await ev(programEditor+".problem.title='未保存编程题';"+creator+'.closeProgram();true'); await confirm(false);
    assert.equal(await ev(creator+'.programDialog'), true);
    await ev(creator+'.closeProgram();true'); await confirm(true);
    await until(() => ev('!'+creator+'.programDialog'), 'discard inline program');
    await ev(creator+".select('PROGRAMMING');true");
    await until(() => ev(programEditor+'?.authoringReady&&'+programEditor+'.allLanguage.length===1'), 'new program editor');
    await ev(programEditor+".problem.problemId='STEP20';"+programEditor+".problem.title='分步组卷加法';"+programEditor+".problem.description='输出 a+b';"+programEditor+".problem.input='两个整数';"+programEditor+".problem.output='两数之和';"+programEditor+'.problem.isUploadCase=false;'+programEditor+".problemSamples=[{input:'1 2',output:'3',score:100}];"+programEditor+'.problemLanguages='+programEditor+'.allLanguage.map(language=>language.name);'+programEditor+'.submit();true');
    await until(() => ev(paper+'.form.items.length===2&&!'+creator+'.programDialog'), 'add inline program');
    assert.equal(await ev(paper+'.form.items[0].score'), 5);
    assert.equal(await ev(paper+'.form.title'), '分步混合试卷');
    await ev(paper+'.form.items[1].score=25;'+paper+'.move(1,-1);true');
    await click('.paper-editor-dialog .authoring-footer', '下一步');
    await shot('authoring-step-paper-rules');
    await click('.paper-editor-dialog .authoring-footer', '上一步');
    assert.deepEqual(await ev(paper+'.form.items.map(item=>item.score)'), [25,5]);
    await click('.paper-editor-dialog .authoring-footer', '下一步');
    await click('.paper-editor-dialog .authoring-footer', '预览试卷');
    assert.equal(await ev(paper+'.preview'), true);
    await shot('authoring-step-paper-preview');
    rejectNextPaper = true;
    await click('.paper-editor-dialog .authoring-footer', '保存试卷');
    await until(() => ev('!'+paper+'.saving&&!!'+paper+'.formError'), 'failed paper save');
    assert.equal(await ev(paper+'.dialog'), true);
    assert.equal(await ev(paper+'.form.items.length'), 2);
    await click('.paper-editor-dialog .authoring-footer', '保存试卷');
    await until(() => ev('!'+paper+'.dialog'), 'retry paper save');
    const paperPayload=report.requests.filter(r=>r.path==='/api/admin/papers'&&r.method==='POST').pop().data;
    assert.equal(paperPayload.published, false);
    assert.deepEqual(paperPayload.items.map(item=>item.kind), ['PROGRAMMING','OBJECTIVE']);
    report.checks.push('Mixed-paper steps preserve inline blank/program creation, protect nested program cancel, retain order and scores across steps/preview, and recover from failed saves.');

    const sets = find('CollectionAdmin')+'.$refs.sets';
    await ev(find('CollectionAdmin')+'.edit({id:3,source:"OBJECTIVE"});true');
    await until(() => ev(sets+'.setDialog'), 'existing set');
    assert.equal(await ev(sets+'.hasUnsavedAuthoring()'), false);
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    assert.equal(await ev(sets+'.setStep'), 2);
    await ev(sets+".problemSet.description='更新说明';"+sets+'.closeSet();true'); await confirm(false);
    await click('.objective-editor-dialog .authoring-footer', '保存题集与规则');
    await until(() => ev('!'+sets+'.setDialog'), 'save old set');
    assert.deepEqual(report.requests.filter(r=>r.path==='/api/admin/objective/sets'&&r.method==='PUT').pop().data.questionIds,[1]);
    report.checks.push('Existing sets use the same steps and unsaved protection while keeping their IDs, selected questions and release rules.');

    await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await route('/admin/problems'); await until(() => ev('!!'+find('QuestionBank')), 'mobile bank');
    await ev(bank+".$refs.creator.select('MULTIPLE_CHOICE');true");
    await ev(objective+".question.title='手机多选';"+objective+".question.stem='选择所有正确项';"+objective+".question.options[0].text='选项 A';"+objective+".question.options[1].text='选项 B';true");
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    await ev(objective+".question.correctAnswers=['A','B'];true");
    await click('.objective-editor-dialog .authoring-footer', '下一步');
    await shot('authoring-step-mobile', true);
    assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);
    assert.equal(await ev("Array.from(document.querySelectorAll('.authoring-footer button')).filter(n=>n.getBoundingClientRect().height).every(n=>{const r=n.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.bottom<=innerHeight})"),true);
    await ev(objective+'.closeQuestion();true'); await confirm(true);
    assert.equal(await unloadProtected(),false);
    report.checks.push('Mobile step navigation, publication summary and all footer actions fit the viewport and retain multi-select answers.');

    await route('/admin/questions/create');
    await until(() => ev('!!'+find('QuestionCreate')+'.$refs.creator'), 'standalone question creation');
    const standaloneCreator=find('QuestionCreate')+'.$refs.creator', standaloneProgram=standaloneCreator+'.$refs.program';
    await ev(standaloneCreator+".select('PROGRAMMING');true");
    await until(() => ev(standaloneProgram+'?.authoringReady&&'+standaloneProgram+'.allLanguage.length===1'), 'standalone program editor');
    await ev(standaloneProgram+".problem.problemId='STEP_STANDALONE';"+standaloneProgram+".problem.title='独立创建编程题';"+standaloneProgram+".problem.description='输出 a+b';"+standaloneProgram+".problem.input='两个整数';"+standaloneProgram+".problem.output='两数之和';"+standaloneProgram+'.problem.isUploadCase=false;'+standaloneProgram+".problemSamples=[{input:'1 2',output:'3',score:100}];"+standaloneProgram+'.problemLanguages='+standaloneProgram+'.allLanguage.map(language=>language.name);'+standaloneProgram+'.submit();true');
    await until(() => ev("document.getElementById('app').__vue__.$route.path==='/admin/problems'&&!!"+bank), 'saved program returns to bank');
    assert.equal(await unloadProtected(),false);
    report.checks.push('Successful standalone programming creation returns to the bank without an incorrect unsaved or saving prompt.');

    await ev(bank+".$refs.creator.select('TRUE_FALSE');"+objective+".question.title='旧身份未保存内容';true");
    uid='paper-charlie';
    await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");
    await until(() => ev('!'+objective+'.questionDialog&&'+objective+'.question===null'), 'clear old identity form');
    assert.equal(await unloadProtected(),false);
    await ev(bank+".$refs.creator.select('TRUE_FALSE');"+objective+".question.title='新身份的内容';"+objective+'.closeQuestion();true');
    await confirm(false);
    assert.equal(await ev(objective+'.questionDialog'),true);
    assert.equal(await ev(objective+'.question.title'),'新身份的内容');
    await ev(objective+'.closeQuestion();true'); await confirm(true);
    report.checks.push('Changing account clears the previous editor and correctly protects fresh edits under the new identity.');
    assert.deepEqual(report.errors, []); report.validated=true;
    fs.writeFileSync(path.join(output,'paper-browser.json'),JSON.stringify(report,null,2));
    console.log('Authoring steps: '+report.checks.length+' workflows passed; '+report.screenshots.length+' screenshots.'); return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'authoring') {
    await route('/admin/problems');
    await until(() => ev(find('QuestionBank') + '&&!'+find('ProblemList')+'.loading'), 'unified question bank');
    await ev(find('QuestionBank')+'.$refs.creator.open();true');
    await until(() => ev("document.querySelectorAll('.type-grid button').length===5"), 'question type picker');
    await shot('authoring-question-types');
    await ev(find('QuestionBank')+".$refs.creator.select('TRUE_FALSE');true");
    const objective = find('QuestionBank') + '.$refs.creator.$refs.objective';
    assert.equal(await ev(objective+'.standalone'), false);
    await ev(objective+".question.title='保密判断题';"+objective+".question.stem='二分查找要求序列有序。';"+objective+".question.correctAnswers=['TRUE'];"+objective+'.preview=true;true');
    await shot('authoring-question-preview');
    await ev(objective+'.saveQuestion();true');
    await until(() => ev(find('QuestionBank')+".tab==='objective'"), 'created question in objective bank');
    const savedQuestion = report.requests.filter(r=>r.path==='/api/admin/objective/questions'&&r.method==='POST').pop().data;
    assert.equal(savedQuestion.published, false); assert.equal(savedQuestion.practiceEnabled, false);
    report.checks.push('Unified bank offers five types; new objective questions default to confidential, preview safely, and return to the correct bank.');
    await route('/admin/papers');
    await until(() => ev(find('CollectionAdmin')+'.rows.length===3'), 'combined collection catalogue');
    await shot('authoring-collections');
    await ev(find('PaperAdmin')+'.create();'+find('PaperAdmin')+".form.title='保密蓝桥杯试卷';"+find('PaperAdmin')+".form.description='组卷中录题，不丢失已填写的内容。';true");
    await until(() => ev('!!'+find('PaperAdmin')+'.$refs.creator'), 'in-paper question creator');
    const creator = find('PaperAdmin')+'.$refs.creator', inPaper = creator+'.$refs.objective';
    await ev(creator+".select('FILL_BLANK');true");
    await ev(inPaper+".question.title='填空：加法';"+inPaper+".question.stem='1+2=____';"+inPaper+".question.blanks[0].answers=['3'];"+inPaper+'.question.blanks[0].score=5;'+inPaper+'.saveQuestion();true');
    await until(() => ev(find('PaperAdmin')+'.form.items.length===1'), 'inline objective added');
    assert.equal(await ev(find('PaperAdmin')+'.form.title'), '保密蓝桥杯试卷');
    assert.equal(await ev(find('PaperAdmin')+'.form.items[0].score'), 5);
    await ev(creator+".select('PROGRAMMING');true");
    const programEditor = creator+'.$refs.program';
    await until(() => ev(programEditor+'?.allLanguage?.length===1&&!!'+programEditor+'.problem.problemId===false'), 'inline programming editor');
    assert.equal(await ev(programEditor+'.problem.auth'), 2);
    await ev("document.querySelector('.program-create-dialog .el-switch').click();true"); assert.equal(await ev(programEditor+'.problem.auth'), 1);
    await ev("document.querySelector('.program-create-dialog .el-switch').click();true"); assert.equal(await ev(programEditor+'.problem.auth'), 2);
    await shot('authoring-program-editor', true);
    await ev(programEditor+".problem.problemId='AUTHORING20';"+programEditor+".problem.title='组卷中录入的加法';"+programEditor+".problem.description='输出 a+b';"+programEditor+".problem.input='两个整数';"+programEditor+".problem.output='两数之和';"+programEditor+'.problem.isUploadCase=false;'+programEditor+".problemSamples=[{input:'1 2',output:'3',score:100}];"+programEditor+'.problemLanguages='+programEditor+'.allLanguage.map(language=>language.name);'+programEditor+'.submit();true');
    await until(() => ev(find('PaperAdmin')+'.form.items.length===2&&!'+creator+'.programDialog'), 'inline programming added');
    assert.equal(await ev(find('PaperAdmin')+'.form.title'), '保密蓝桥杯试卷');
    assert.equal(await ev("document.getElementById('app').__vue__.$route.path"), '/admin/papers');
    await ev(find('PaperAdmin')+'.preview=true;true'); await shot('authoring-paper-preview');
    await ev(find('PaperAdmin')+'.save();true'); await until(() => ev('!'+find('PaperAdmin')+'.dialog'), 'private paper save');
    const paperPayload = report.requests.filter(r=>r.path==='/api/admin/papers'&&r.method==='POST').pop().data;
    assert.equal(paperPayload.published, false); assert.deepEqual(paperPayload.items.map(item=>item.kind), ['OBJECTIVE','PROGRAMMING']);
    report.checks.push('Inline objective and programming creation preserves paper fields, derives blank scores, adds both saved IDs, and saves a private mixed paper without navigation.');
    await ev(find('CollectionAdmin')+'.edit({id:3,source:"OBJECTIVE"});true');
    const sets = find('CollectionAdmin')+'.$refs.sets';
    await until(() => ev(sets+'.setDialog&&'+sets+'.problemSet.id===3'), 'legacy collection editor');
    await ev(sets+'.$refs.setCreator.select("SINGLE_CHOICE");true');
    const setQuestion = sets+'.$refs.setCreator.$refs.objective';
    await ev(setQuestion+".question.title='题集中新增';"+setQuestion+".question.stem='1+1=?';"+setQuestion+".question.options=[{id:'A',text:'2'},{id:'B',text:'3'}];"+setQuestion+".question.correctAnswers=['A'];"+setQuestion+'.standalone=true;'+setQuestion+'.saveQuestion();true');
    await until(() => ev(sets+'.problemSet.questionIds.length===2'), 'new question auto-added to legacy set');
    assert.equal(await ev(sets+'.problemSet.title'), '旧版逐题训练集');
    await ev(sets+'.saveSet();true'); await until(() => ev('!'+sets+'.setDialog'), 'save legacy set');
    report.checks.push('Existing objective sets remain editable in the unified catalogue; inline creation preserves old references and explicit standalone publishing.');
    await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    await shot('authoring-collections-mobile'); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);
    assert.equal(await ev("document.querySelector('.mobile-collections article .el-button').getBoundingClientRect().width>0"),true);
    await route('/admin/questions/create'); await until(() => ev("document.querySelectorAll('.type-grid button').length===5"), 'mobile type chooser');
    await shot('authoring-types-mobile', true); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);
    report.checks.push('Unified administration and type selection fit mobile without horizontal overflow.');
    assert.deepEqual(report.errors, []); report.validated=true; fs.writeFileSync(path.join(output,'paper-browser.json'),JSON.stringify(report,null,2)); console.log('Authoring browser: '+report.checks.length+' workflows passed; '+report.screenshots.length+' screenshots.'); return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'rating') {
    for(const alias of ['/rank','/rank/practice','/acm-rank','/oi-rank']) {
      await route('/problem');await route(alias);await until(()=>ev("document.querySelectorAll('.rating-table .el-table__body tbody tr').length===30"),'contest rating redirect');
      assert.equal(await ev("document.getElementById('app').__vue__.$route.path"),'/rank/contest');
    }
    assert.equal(await ev("document.querySelector('.site-rank h1').textContent"),'比赛 Rating');
    assert.equal(await ev("document.querySelector('.site-rank').textContent.includes('平时')||!!document.querySelector('.rank-tabs')"),false);
    assert.equal(await ev("document.querySelector('.nav-links .el-menu-item.is-active')?.textContent.includes('排名')"),true);
    assert.equal(await ev("!!document.querySelector('#header a[href=\"/rank/practice\"]')"),false);
    await shot('site-rating-contest-desktop');
    await ev("document.querySelector('.rank-footer .btn-next').click();true");await until(()=>ev(find('SiteRank')+'.rows[0]?.rank===31'),'global rank on page two');
    await ev("const n=document.querySelector('.rank-search input');n.value='student0';n.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('.rank-search').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));true");
    await until(()=>ev(find('SiteRank')+'.rows.length===1&&'+find('SiteRank')+'.page===1'),'search resets pagination');
    assert.equal(await ev(find('SiteRank')+'.rows[0].rank'),1);
    await ev("document.querySelector('.rating-table .el-button').click();true");await until(()=>ev(find('SiteRank')+'.historyRows.length===1'),'contest rating history');
    assert.equal(await ev(find('SiteRank')+'.historyRows[0].type'),'contest');
    assert.equal(report.requests.filter(request=>request.path.startsWith('/api/rating/users/')).pop().query.type,'contest');
    await ev(find('SiteRank')+'.dialog=false;true');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(250);
    assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);await shot('site-rating-mobile');
    await ev(find('SiteRank')+".keyword='no-such-user';"+find('SiteRank')+'.search();true'); await until(() => ev('!'+find('SiteRank')+'.loading'), 'empty search');
    assert.equal(await ev(find('SiteRank')+'.rows.length'),0);
    await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
    await route('/problem'); await until(() => ev(find('ProblemList')+'?.problemList?.length===3'), 'numeric task ratings');
    assert.equal(await ev(find('ProblemList')+".$el.textContent.includes('简单')"), false);
    assert.equal(await ev("Array.from(document.querySelectorAll('.rating-chip')).some(n=>n.textContent.includes('未评级'))"), true);
    await ev(find('ProblemList')+'.query.ratingMin=1300;'+find('ProblemList')+'.query.ratingMax=2000;document.querySelector(\'.rating-filter button.el-button\').click();true');
    await until(() => ev(find('ProblemList')+'.problemList.length===1&&'+find('ProblemList')+'.problemList[0].rating===1800'), 'task rating interval reaches backend');
    const query = report.requests.filter(request => request.path === '/api/get-problem-list').pop().query;
    assert.equal(query.ratingMin, '1300'); assert.equal(query.ratingMax, '2000'); assert(!query.difficulty);
    await shot('task-rating-range');
    await route('/admin/objective'); await until(() => ev("!!document.querySelector('.objective-admin')"), 'objective rating editor');
    await ev(find('ObjectiveAdmin')+'.newQuestion();true');
    await ev(find('ObjectiveAdmin')+".question.title='评级选择题';"+find('ObjectiveAdmin')+".question.stem='选择 A';"+find('ObjectiveAdmin')+".question.options[0].text='正确';"+find('ObjectiveAdmin')+".question.options[1].text='错误';"+find('ObjectiveAdmin')+".question.correctAnswers=['A'];"+find('ObjectiveAdmin')+'.question.rating=1700;true');
    await ev(find('ObjectiveAdmin')+'.saveQuestion();true'); await until(() => ev('!'+find('ObjectiveAdmin')+'.questionDialog'), 'save objective rating');
    assert.equal(report.requests.filter(request => request.path === '/api/admin/objective/questions' && request.method === 'POST').pop().data.rating, 1700);
    await route('/admin/contest/create'); await until(() => ev(find('CreateContest')+'?.contest'), 'rated contest editor');
    const ratedSwitch = "Array.from(document.querySelectorAll('.el-form-item')).find(n=>n.querySelector('label')?.textContent.includes('计入比赛 Rating')).querySelector('.el-switch')";
    await ev(find('CreateContest')+'.contest.id=9;'+find('CreateContest')+'.contest.startTime=new Date(Date.now()+3600000).toISOString();true');
    assert.equal(await ev(ratedSwitch+".classList.contains('is-disabled')"), false);
    await ev(ratedSwitch+'.click();true'); assert.equal(await ev(find('CreateContest')+'.contest.rated'), true);
    await ev(find('CreateContest')+'.contest.startTime=new Date(Date.now()-3600000).toISOString();true');
    assert.equal(await ev(ratedSwitch+".classList.contains('is-disabled')"), true);
    await route('/user-home?uid='+uid);await until(()=>ev("document.querySelector('.rating-summary strong')?.textContent==='1600'"),'contest-only home summary');
    assert.equal(await ev("document.querySelector('.rating-summary').textContent.includes('平时')"),false);
    assert.equal(await ev("document.querySelectorAll('.rating-summary > div').length"),1);await shot('contest-rating-user-home');
    assert(report.requests.filter(row=>row.path==='/api/rating/rank'||row.path.startsWith('/api/rating/users/')).every(row=>row.query.type==='contest'));
    report.checks.push('Only contest Rating is shown on the board and user home; navigation is direct, all old rank URLs redirect, pagination/search and contest history still work on desktop/mobile.');
    report.checks.push('Task list shows numeric/unrated ratings without difficulty labels and sends rating interval filters to the backend.');
    report.checks.push('Admin objective rating reaches save payload; upcoming contests can enable rating and started contests lock the switch.');
    assert.deepEqual(report.errors, []); report.validated = true;
    fs.writeFileSync(path.join(output, 'paper-browser.json'), JSON.stringify(report, null, 2));
    console.log('Rating browser regression passed; desktop/mobile screenshots saved.'); return;
  }
  if (process.env.PAPER_REVIEW_CASE === 'objective-poll') {
    await route('/contest/6/problems'); await until(() => ev("!!document.querySelector('.mixed-entry')"), 'mixed contest entry');
    await ev(find('MixedContestEntry')+'.enter();true'); await until(() => ev(find('PaperRun')+'?.run?.contestId===6'), 'mixed contest run');
    await checkObjectivePolling(); await shot('objective-options-retained');
    assert.deepEqual(report.errors, []); report.validated = true;
    fs.writeFileSync(path.join(output, 'paper-browser.json'), JSON.stringify(report, null, 2));
    console.log('Objective polling regression passed.'); return;
  }
  assert.equal(await ev("document.querySelector('.problem-navigation a[aria-current=\"page\"]')?.getAttribute('href')"), '/papers');
  assert.equal(await ev("document.querySelectorAll('.problem-navigation a').length"), 3);
  await shot('paper-list-desktop');
  await ev(find('PaperList') + '.open(' + find('PaperList') + '.rows[0]);true');
  await until(() => ev(find('PaperRun') + '?.run?.paperId===1'), 'mixed run');
  assert.equal(await ev("document.querySelectorAll('.workspace-card').length"), 2);
  assert.equal(await ev("document.querySelectorAll('.blank-answer input').length"), 2);
  assert.equal(await ev("document.querySelector('.origin').textContent.includes('蓝桥杯')"), true);
  assert.equal(await ev("document.querySelector('.objective-feedback')===null"), true);
  const dockVisible = () => ev("(()=>{const n=document.querySelector('.question-dock'),r=n.getBoundingClientRect();return getComputedStyle(n).position==='fixed'&&r.top>=0&&r.bottom<=innerHeight&&r.left>=0&&r.right<=innerWidth&&[...n.querySelectorAll('button')].every(b=>{const x=b.getBoundingClientRect();return x.height>=44&&!!document.elementFromPoint(x.left+x.width/2,x.top+x.height/2)?.closest('.question-dock')})})()");
  assert.equal(await ev("document.querySelector('.question-dock .previous-question').disabled"), true);
  await ev("window.scrollTo(0,document.querySelector('.workspace-grid').getBoundingClientRect().top+scrollY-12);true");
  await sleep(350); assert.equal(await ev("document.querySelector('.question-numbers').getBoundingClientRect().bottom<0"), true);
  assert.equal(await dockVisible(), true); await shot('paper-navigation-scrolled', true);
  await ev("document.querySelector('[data-panel=\"editor\"]').closest('.workspace-card').querySelector('button[aria-label=\"展开卡片\"]').click();true");
  await until(() => ev("!!document.querySelector('.workspace-card.expanded')"), 'expanded answer card');
  assert.equal(await dockVisible(), true);
  assert.equal(await ev("document.querySelector('.workspace-card.expanded').getBoundingClientRect().bottom<document.querySelector('.question-dock').getBoundingClientRect().top"), true);
  await ev("document.querySelector('.question-dock .next-question').click();true");
  await until(() => ev(find('PaperRun') + '.index===1'), 'next question while expanded');
  assert.equal(await ev("!!document.querySelector('.workspace-card.expanded .paper-code .CodeMirror')"), true);
  assert.equal(await dockVisible(), true); await shot('paper-navigation-expanded', true);
  await ev("document.querySelector('.question-dock .next-question').click();true");
  await until(() => ev(find('PaperRun') + '.index===2'), 'last question');
  assert.equal(await ev("document.querySelector('.question-dock .next-question').disabled"), true);
  await ev("document.querySelector('.question-dock .previous-question').click();true");
  await until(() => ev(find('PaperRun') + '.index===1'), 'previous from last question');
  await ev("document.querySelector('.question-dock .previous-question').click();true");
  await until(() => ev(find('PaperRun') + '.index===0'), 'return to first question');
  await ev("document.querySelector('.workspace-card.expanded button[aria-label=\"恢复卡片\"]').click();window.scrollTo(0,0);true");
  report.checks.push('Question dock remains visible and clickable after scrolling and expanding; switching across fill/program/choice keeps the expanded card; first/last boundaries disable correctly.');
  await ev("document.querySelectorAll('.blank-answer input').forEach((n,i)=>{n.value=i?'8':'42';n.dispatchEvent(new Event('input',{bubbles:true}))});true");
  assert.equal(await ev(find('ObjectiveAnswer') + '.dirty'), true);
  await ev("document.querySelector('.answer-footer button').click();true");
  await until(() => ev(find('PaperRun') + '.run.answeredCount===1&&!'+find('PaperRun')+'.saving'), 'save blanks');
  assert.equal(await ev(find('ObjectiveAnswer') + '.dirty'), false);
  await shot('paper-fill-desktop');
  await ev(find('PaperRun') + ".select('PROGRAMMING:1');true");
  await until(() => ev("!!document.querySelector('.paper-code .CodeMirror')"), 'code editor');
  assert.equal(await ev("document.querySelector('.paper-code').textContent.includes('提交判题')"), false);
  assert.equal(await ev("document.querySelector('.paper-statement .examples pre').textContent"), '1 2');
  await ev(find('PaperProgramAnswer') + ".code=" + JSON.stringify("#include <iostream>\nint main(){int a,b;std::cin>>a>>b;std::cout<<a+b;}") + ";true");
  await ev(find('PaperRun') + '.saveProgram(' + find('PaperProgramAnswer') + '.payload);true');
  await until(() => ev('!'+find('PaperRun')+'.saving'), 'save code');
  assert.equal(report.requests.filter(r => r.path.endsWith('/program-submissions')).length, 0);
  await shot('paper-program-desktop');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await sleep(250); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'), false); await shot('paper-program-mobile');
  await ev("window.scrollTo(0,document.querySelector('.paper-code').getBoundingClientRect().top+scrollY-80);true"); await sleep(350);
  assert.equal(await dockVisible(), true); await shot('paper-navigation-mobile', true);
  await ev("window.scrollTo(0,0);true");
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await ev(find('PaperRun') + ".select('OBJECTIVE:1');true");
  await until(() => ev("document.querySelectorAll('.objective-answer .el-radio').length===2"), 'mixed choice');
  await ev("document.querySelector('.objective-answer .el-radio').click();true");
  await ev('window.finishing='+find('PaperRun')+'.finish();true');
  await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'finish confirmation');
  await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true");
  await ev('window.finishing.then(()=>true)');
  await until(() => ev(find('PaperRun') + '.run.state===\'FINISHED\'&&'+find('PaperRun')+'.run.score===35'), 'deferred grading completes', 12000);
  assert.deepEqual(runs.values().next().value.answers['OBJECTIVE:1'].selectedAnswers, ['A']);
  report.checks.push('Mixed fill/choice/program paper saves independently, judges deferred code only after finish, polls asynchronously, and grades 35/35.');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await sleep(300); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'), false); await shot('paper-result-mobile');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await route('/papers'); await ev(find('PaperList') + '.open('+find('PaperList')+'.rows[1]);true');
  await until(() => ev(find('PaperRun')+'?.run?.paperId===2'), 'live paper');
  await ev(find('PaperProgramAnswer')+".code='int main(){return 0;}';true");
  await ev(find('PaperRun')+'.submitProgram('+find('PaperProgramAnswer')+'.payload);true');
  await until(() => ev('!'+find('PaperRun')+'.saving&&'+find('PaperRun')+'.question.submissionCount===1'), 'live submission');
  await ev(find('PaperProgramAnswer')+".code='int main(){return 1;}';true");
  await until(() => ev(find('PaperRun')+'.question.score===20'), 'live poll', 12000);
  assert.equal(await ev(find('PaperProgramAnswer')+'.code'), 'int main(){return 1;}'); assert.equal(await ev(find('PaperProgramAnswer')+'.dirty'), true);
  assert.equal(await ev(find('PaperRun')+'.run.score===undefined'), true);
  await ev(find('PaperRun')+".select('OBJECTIVE:1');true");
  await until(() => ev("!!document.querySelector('.el-message-box__btns .el-button--primary')"), 'unsaved guard');
  await ev("document.querySelector('.el-message-box__btns .el-button:not(.el-button--primary)').click();true");
  assert.equal(await ev(find('PaperRun')+'.selectedKey'), 'PROGRAMMING:1');
  await ev(find('PaperRun')+'.saveProgram('+find('PaperProgramAnswer')+'.payload);true'); await until(() => ev('!'+find('PaperRun')+'.saving'), 'save modified code');
  await ev("document.documentElement.dataset.theme='dark';true"); await shot('paper-program-dark'); await ev("document.documentElement.dataset.theme='light';true");
  report.checks.push('Live verdicts appear before finish without revealing total score; polling preserves unsaved code and navigation guards protect drafts.');
  await route('/admin/papers'); await until(() => ev("!!document.querySelector('.paper-admin')"), 'admin paper');
  await ev(find('PaperAdmin')+'.create();true'); await until(() => ev(find('PaperAdmin')+'.choices.length===2'), 'paper picker');
  await ev(find('PaperAdmin')+'.candidate=4;'+find('PaperAdmin')+'.add();true'); await until(() => ev(find('PaperAdmin')+'.form.items.length===1'), 'add fill');
  assert.equal(await ev(find('PaperAdmin')+'.form.items[0].score'), 10);
  await ev(find('PaperAdmin')+'.goStep(1);true');
  await ev(find('PaperAdmin')+".kind='PROGRAMMING';"+find('PaperAdmin')+".searchChoices('');true"); await until(() => ev(find('PaperAdmin')+'.choices.length===1&&!'+find('PaperAdmin')+'.choiceLoading'), 'program picker');
  await ev(find('PaperAdmin')+'.candidate=1;'+find('PaperAdmin')+'.add();true'); await until(() => ev(find('PaperAdmin')+'.form.items.length===2'), 'add program');
  await ev(find('PaperAdmin')+".form.title='蓝桥杯组合练习';"+find('PaperAdmin')+".form.explanationPolicy='NEVER';"+find('PaperAdmin')+".form.answerPolicy='AT_TIME';"+find('PaperAdmin')+'.form.answerReleaseAt=2000000000000;true');
  await ev(find('PaperAdmin')+'.goStep(2);true'); await shot('paper-admin-editor'); await ev(find('PaperAdmin')+'.save();true'); await until(() => ev('!'+find('PaperAdmin')+'.dialog'), 'save paper');
  const payload = report.requests.filter(r => r.path==='/api/admin/papers'&&r.method==='POST').pop().data;
  assert.deepEqual(payload.items.map(item=>item.kind), ['OBJECTIVE','PROGRAMMING']); assert.equal(payload.answerReleaseAt,2000000000000); assert.equal(payload.explanationPolicy,'NEVER');
  await ev(find('PaperAdmin') + '.edit(1);true'); await until(() => ev(find('PaperAdmin')+'.form?.id===1&&'+find('PaperAdmin')+'.dialog'), 'edit existing mixed paper');
  assert.equal(await ev(find('PaperAdmin')+'.form.items[0].score'), 10);
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await sleep(250); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'), false); await shot('paper-admin-mobile');
  await ev(find('PaperAdmin')+'.close();true');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  report.checks.push('Admin references both banks, preserves order, derives blank-point totals, edits existing papers and saves independent release policies on desktop/mobile.');
  await route('/admin/objective'); await until(() => ev("!!document.querySelector('.objective-admin')"), 'blank administration');
  await ev(find('ObjectiveAdmin')+".newQuestion();"+find('ObjectiveAdmin')+".question.questionType='FILL_BLANK';"+find('ObjectiveAdmin')+'.changeQuestionType();true');
  await ev(find('ObjectiveAdmin')+".question.title='多空结果';"+find('ObjectiveAdmin')+".question.stem='填写计算结果';"+find('ObjectiveAdmin')+".question.blanks[0].answers=['42','0x2a'];"+find('ObjectiveAdmin')+'.question.blanks[0].score=3;'+find('ObjectiveAdmin')+'.addBlank();'+find('ObjectiveAdmin')+".question.blanks[1].answers=['8'];true");
  await ev(find('ObjectiveAdmin')+'.goQuestionStep(1);true');
  assert.equal(await ev("document.querySelectorAll('.blank-editor').length"), 2); await shot('blank-admin-editor');
  await ev(find('ObjectiveAdmin')+'.saveQuestion();true'); await until(() => ev('!'+find('ObjectiveAdmin')+'.questionDialog'), 'save blank question');
  assert.equal(report.requests.filter(r=>r.path==='/api/admin/objective/questions'&&r.method==='POST').pop().data.blanks.length,2);
  report.checks.push('Fill editor supports multiple blanks, equivalent answers and per-blank points.');
  await route('/admin/contest/create'); await until(() => ev("!!document.querySelector('.mixed-settings')"), 'mixed contest configuration');
  await ev(find('MixedContestSettings')+".toggle(true);true"); await sleep(100);
  await ev(find('MixedContestSettings')+".set('paperId',2);true"); await sleep(100);
  await ev(find('MixedContestSettings')+".set('answerPolicy','NEVER');true"); await sleep(100);
  assert.equal(await ev(find('CreateContest')+'.contest.mixed.paperId'), 2); assert.equal(await ev(find('CreateContest')+'.contest.mixed.answerPolicy'), 'NEVER'); await shot('mixed-contest-admin-settings', true);
  report.checks.push('Contest administration selects an existing mixed paper and configures contest answer/explanation release independently from practice.');
  await route('/contest/6/problems'); await until(() => ev("!!document.querySelector('.mixed-entry')"), 'mixed contest entry');
  await shot('mixed-contest-entry'); await ev(find('MixedContestEntry')+'.enter();true'); await until(() => ev(find('PaperRun')+'?.run?.contestId===6'), 'mixed contest run');
  assert.equal(await ev("document.querySelector('.run-toolbar').innerText.includes('返回比赛')"), true);
  assert.equal(await ev("document.querySelector('.paper-run').innerText.includes('距比赛截止')"), true);
  await checkObjectivePolling();
  await ev(find('PaperRun')+'.finish();true'); await until(() => ev("!!document.querySelector('.el-message-box')"), 'contest hand-in dialog');
  await ev("document.querySelector('.el-message-box__btns .el-button--primary').click();true"); await until(() => ev(find('PaperRun')+"?.run?.state==='FINISHED'"), 'contest hand-in');
  assert.equal(await ev(find('PaperRun')+'.question.answerVisible'), false); assert.equal(await ev(find('PaperRun')+'.question.correctAnswers'), undefined); await shot('mixed-contest-answers-hidden');
  contestEnd = Date.now() - 1000; await ev(find('PaperRun')+'.refresh();true'); await until(() => ev(find('PaperRun')+'.question.answerVisible'), 'post-contest answers'); await shot('mixed-contest-answers-released');
  await route('/contest/6/rank'); await until(() => ev("document.querySelectorAll('.mixed-rank .el-table__body tbody tr').length===2"), 'mixed ACM scoreboard'); await shot('mixed-contest-acm-rank');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true }); await sleep(250); assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'), false); await shot('mixed-contest-rank-mobile');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  report.checks.push('Real contest routes open a single mixed answer sheet with countdown/back navigation, defer objective answers until the end, and display objective-first ACM standings on desktop/mobile.');
  uid='paper-bob';role='default_user'; await ev("document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:"+JSON.stringify(user())+"});true");
  await route('/papers?tab=history'); await until(() => ev(find('PaperList')+'&&!'+find('PaperList')+'.loading'), 'new account history'); assert.equal(await ev(find('PaperList')+'.rows.length'),0);
  report.checks.push('Account switch clears mixed-paper state and loads only the new user history.');
  assert.deepEqual(report.errors, []);
  report.validated = true;
  fs.writeFileSync(path.join(output, 'paper-browser.json'), JSON.stringify(report, null, 2));
  console.log('Paper browser: ' + report.checks.length + ' workflows passed; ' + report.screenshots.length + ' screenshots; no runtime exceptions.');
})().catch(error => {
  report.failure = error.stack; fs.writeFileSync(path.join(output, 'paper-browser.json'), JSON.stringify(report, null, 2)); console.error(error); process.exitCode = 1;
}).finally(() => { if (ws) ws.close(); browser.kill(); });
