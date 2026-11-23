// Real local learning flow. The caller owns and cleans temporary accounts/questions/plans.
const fs=require('fs'),path=require('path'),os=require('os'),assert=require('assert').strict,{spawn}=require('child_process');
module.exports=async function reviewTrainingPlan({account,output,planId,questionId,taskRating}){
  const profile=fs.mkdtempSync(path.join(os.tmpdir(),'hoj-training-live-'));
  const browser=spawn(process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--disable-extensions','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{windowsHide:true,stdio:['ignore','ignore','ignore']});
  const sleep=ms=>new Promise(r=>setTimeout(r,ms));let ws;
  try{
    let port;for(let i=0;i<100;i++){const file=path.join(profile,'DevToolsActivePort');if(fs.existsSync(file)){port=fs.readFileSync(file,'utf8').split('\n')[0];break;}await sleep(100);}assert(port);
    const pages=await(await fetch('http://127.0.0.1:'+port+'/json/list')).json();ws=new WebSocket(pages.find(p=>p.type==='page').webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});let id=0;const pending=new Map(),errors=[],requests=[];
    const cdp=(method,params={})=>new Promise((resolve,reject)=>{const n=++id;pending.set(n,{resolve,reject});ws.send(JSON.stringify({id:n,method,params}));});
    ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id){const p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(m.error):p.resolve(m.result);}}else if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.text);else if(m.method==='Network.requestWillBeSent'){const url=new URL(m.params.request.url);if(url.pathname.startsWith('/api/'))requests.push({path:url.pathname,method:m.params.request.method});}};
    const ev=async expression=>{const r=await cdp('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result.value;};
    const app="(document.getElementById('app')&&document.getElementById('app').__vue__)";
    const component=name=>`(function find(v){if(!v)return null;if(v.$options.name===${JSON.stringify(name)})return v;for(const c of v.$children){const f=find(c);if(f)return f}})(${app})`;
    const wait=async expression=>{for(let i=0;i<150;i++){if(await ev(expression))return;await sleep(100);}throw Error('Real training page did not reach the expected state: '+expression);};
    await cdp('Page.enable');await cdp('Runtime.enable');await cdp('Emulation.setDeviceMetricsOverride',{width:1440,height:1080,deviceScaleFactor:1,mobile:false});
    const user={uid:account.uid,username:account.username,roleList:['default_user'],gender:'secrecy',avatar:''};
    await cdp('Page.addScriptToEvaluateOnNewDocument',{source:`localStorage.setItem('Web_Language','"zh-CN"');localStorage.setItem('token',${JSON.stringify(account.token)});localStorage.setItem('userInfo',${JSON.stringify(JSON.stringify(user))});`});
    const report={apiMocked:false,realModelCalled:false,checks:[],screenshots:[]};
    const home='/user-home?uid='+encodeURIComponent(account.uid),recommendations=component('LearningRecommendations'),saved=component('SavedTrainingPlans'),sheet=component('ObjectiveRun');
    const capture=async name=>{await sleep(250);const image=await cdp('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(output,name),Buffer.from(image.data,'base64'));report.screenshots.push(name);};
    await cdp('Network.enable');
    // The recommendation endpoint has a per-owner five-second read cooldown.
    await sleep(5300);await cdp('Page.navigate',{url:'http://127.0.0.1:8080'+home});
    await wait(`${recommendations}?.plan&&!${recommendations}.loading&&${saved}?.items.some(p=>p.id===${JSON.stringify(planId)})`);
    await ev(`${recommendations}.filters.groupCode='geometry';${recommendations}.filters.minRating=${taskRating};${recommendations}.filters.maxRating=${taskRating};${recommendations}.filters.includeUnrated=false;${recommendations}.changed();true`);
    await sleep(5300);await ev(`document.querySelector('.recommendation-filters').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));true`);
    await wait(`${recommendations}.plan?.items.some(row=>row.id==='OBJECTIVE:${questionId}')&&!${recommendations}.loading`);
    assert.equal(await ev(`${recommendations}.plan.items.find(row=>row.id==='OBJECTIVE:${questionId}').rating`),taskRating);
    assert.equal(await ev(`${saved}.items.find(p=>p.id===${JSON.stringify(planId)}).items.find(row=>row.id==='OBJECTIVE:${questionId}').rating`),taskRating);
    assert.equal(await ev(`document.querySelector('.saved-training-list').textContent.includes('Rating ${taskRating}')`),true);
    await ev("document.querySelector('.learning-recommendations').scrollIntoView();window.scrollBy(0,-100);true");await capture('training-real-recommendation.png');
    report.checks.push('Actual Vue requests use strict numeric filters without unrated tasks; rated objective questions appear with the same numeric difficulty in recommendations and the saved list.');
    await ev("document.querySelector('.saved-training-question button').scrollIntoView({block:'center'});document.querySelector('.saved-training-question button').click();true");
    await wait(`${sheet}?.selectedQuestion?.id===${questionId}&&!${sheet}.loading`);
    assert.equal(await ev(`${sheet}.selectedQuestion.rating`),taskRating);
    await ev("document.querySelector('.objective-answer .el-radio').click();true");await sleep(1300);
    assert.equal(await ev("document.querySelector('.objective-answer .el-radio').classList.contains('is-checked')"),true);
    await ev("document.querySelector('.answer-footer .el-button').click();true");await wait(`${sheet}.selectedQuestion.submitted&&!${sheet}.saving`);
    assert.equal(await ev(`${sheet}.selectedQuestion.answerVisible`),false);assert.equal(await ev(`${sheet}.selectedQuestion.correct`),undefined);
    await ev("window.scrollTo(0,0);true");await capture('training-real-objective.png');
    report.checks.push('The saved objective entrance starts a real owned run; the option remains selected, submission succeeds, the task rating agrees, and NEVER feedback stays hidden.');
    await ev(`${app}.$router.push(${JSON.stringify(home)});true`);
    await wait(`${saved}?.items.some(p=>p.id===${JSON.stringify(planId)})&&!${saved}.loading`);
    await wait(`${recommendations}?.plan&&!${recommendations}.loading`);assert.equal(await ev(`${recommendations}.error`),'');
    assert.equal(await ev("!!document.querySelector('.el-notification.error,.el-message--error')"),false);
    assert.equal(await ev(`${saved}.items.find(p=>p.id===${JSON.stringify(planId)}).items.find(row=>row.id==='OBJECTIVE:${questionId}').status`),'ATTEMPTED');
    await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await ev("document.documentElement.dataset.theme='dark';true");await sleep(350);
    await ev("document.querySelector('.saved-training').scrollIntoView({block:'start'});window.scrollBy(0,-100);true");await sleep(350);
    assert.equal(await ev("(()=>{const rect=document.querySelector('.saved-training-list').getBoundingClientRect();return rect.top>=0&&rect.top<innerHeight&&rect.bottom>0})()"),true);
    assert.equal(await ev(`document.querySelector('.saved-training-list').textContent.includes('Rating ${taskRating}')`),true);await capture('training-real-mobile-dark.png');
    assert.equal(await ev('document.documentElement.scrollWidth>innerWidth'),false);
    report.checks.push('Returning to the profile reloads actual saved progress as attempted, without revealing hidden correctness or triggering a rate-limit error; task difficulty and saved rows remain visible on mobile dark layout.');
    assert(!requests.some(row=>row.path==='/api/ai/chat'||row.path.includes('/explain')||row.path.includes('/translate')));
    assert.equal(errors.length,0,JSON.stringify(errors));report.requests=requests;report.validated=true;return report;
  }finally{if(ws)ws.close();browser.kill();if(browser.exitCode===null)await Promise.race([new Promise(resolve=>browser.once('exit',resolve)),sleep(3000)]);const target=path.resolve(profile),temporary=path.resolve(os.tmpdir())+path.sep;assert(target.startsWith(temporary)&&path.basename(target).startsWith('hoj-training-live-'));fs.rmSync(target,{recursive:true,force:true,maxRetries:10,retryDelay:100});}
};
