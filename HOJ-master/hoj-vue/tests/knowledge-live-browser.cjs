// Real local page/API verification. The caller owns temporary accounts/documents.
const fs=require('fs'),path=require('path'),os=require('os'),assert=require('assert').strict,{spawn}=require('child_process');
module.exports=async function reviewKnowledge({account,output,expectedId,version,pdfPage,pdfPageCount=3,expectedPdfText,videoTime,question='怎样用前缀和求区间和',expectedText='前缀',roleList=['default_user'],uploadMaterial,uploadTitle,pdfCorrection}){
  const profile=fs.mkdtempSync(path.join(os.tmpdir(),'hoj-knowledge-live-'));
  const browser=spawn(process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--no-first-run','--disable-extensions','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{windowsHide:true,stdio:['ignore','ignore','ignore']});
  const sleep=ms=>new Promise(r=>setTimeout(r,ms));let ws;
  try{
    let port;for(let i=0;i<100;i++){const file=path.join(profile,'DevToolsActivePort');if(fs.existsSync(file)){port=fs.readFileSync(file,'utf8').split('\n')[0];break;}await sleep(100);}assert(port);
    const pages=await(await fetch('http://127.0.0.1:'+port+'/json/list')).json();ws=new WebSocket(pages.find(p=>p.type==='page').webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});let id=0;const pending=new Map(),errors=[];
    const cdp=(method,params={})=>new Promise((resolve,reject)=>{const n=++id;pending.set(n,{resolve,reject});ws.send(JSON.stringify({id:n,method,params}));});
    ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id){const p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(m.error):p.resolve(m.result);}}else if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.text);};
    const ev=async expression=>{const r=await cdp('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result.value;};
    const app="(document.getElementById('app')&&document.getElementById('app').__vue__)";
    const component=name=>`(function find(v){if(!v)return null;if(v.$options.name===${JSON.stringify(name)})return v;for(const c of v.$children){const f=find(c);if(f)return f}})(${app})`;
    const wait=async expression=>{for(let i=0;i<150;i++){if(await ev(expression))return;await sleep(100);}throw Error('Real knowledge page did not reach the expected state');};
    await cdp('Page.enable');await cdp('Runtime.enable');await cdp('Emulation.setDeviceMetricsOverride',{width:1440,height:1080,deviceScaleFactor:1,mobile:false});
    const user={uid:account.uid,username:account.username,roleList,gender:'secrecy',avatar:''};
    await cdp('Page.addScriptToEvaluateOnNewDocument',{source:`localStorage.setItem('Web_Language','"zh-CN"');localStorage.setItem('token',${JSON.stringify(account.token)});localStorage.setItem('userInfo',${JSON.stringify(JSON.stringify(user))});`});
    if(uploadMaterial){
      await cdp('Page.navigate',{url:'http://127.0.0.1:8080/admin/knowledge'});await wait(`!!(${component('KnowledgeAdmin')}&&${component('KnowledgeAdmin')}.canMaintain&&!${component('KnowledgeAdmin')}.listLoading)`);
      await ev(`${component('KnowledgeAdmin')}.newDocument().then(()=>true)`);await wait(`${component('KnowledgeAdmin')}.formOpen`);
      const root=await cdp('DOM.getDocument'),input=await cdp('DOM.querySelector',{nodeId:root.root.nodeId,selector:'input.knowledge-file'});assert(input.nodeId);
      await cdp('DOM.setFileInputFiles',{nodeId:input.nodeId,files:[path.resolve(uploadMaterial)]});await wait(`!!${component('KnowledgeAdmin')}.pdfFile`);
      assert(await ev(`${component('KnowledgeAdmin')}.pdfFile.size>8388608&&${component('KnowledgeAdmin')}.pdfFile.size<=33554432&&${component('KnowledgeAdmin')}.contentLimit===300000&&!${component('KnowledgeAdmin')}.error`));
      await ev(`${component('KnowledgeAdmin')}.form.title=${JSON.stringify(uploadTitle)};${component('KnowledgeAdmin')}.form.visibility='ADMIN';true`);
      await ev(`${component('KnowledgeAdmin')}.save().then(()=>true)`);
      await wait(`!!(${component('KnowledgeAdmin')}.selectedId&&${component('KnowledgeAdmin')}.sourceInfo&&!${component('KnowledgeAdmin')}.saving)`);
      const uploaded=await ev(`(()=>{const v=${component('KnowledgeAdmin')};return {id:v.selectedId,pages:v.sourceInfo.pages,characters:v.contentLength,sourceBytes:v.sourceInfo.byteLength,error:v.error,publishedVersion:v.detail.publishedVersion};})()`);
      assert.match(uploaded.id,/^[a-f0-9]{32}$/);assert.equal(uploaded.error,'');assert.equal(uploaded.publishedVersion,null);assert(uploaded.characters>200000&&uploaded.characters<=300000);
      const screenshot=await cdp('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(output,'knowledge-real-book-upload.png'),Buffer.from(screenshot.data,'base64'));
      assert.equal(errors.length,0,JSON.stringify(errors));return {...uploaded,apiMocked:false,uploadedViaActualUi:true,screenshots:['knowledge-real-book-upload.png']};
    }
    if(pdfCorrection){
      await cdp('Page.navigate',{url:'http://127.0.0.1:8080/admin/knowledge'});await wait(`!!(${component('KnowledgeAdmin')}&&${component('KnowledgeAdmin')}.canMaintain&&!${component('KnowledgeAdmin')}.listLoading)`);
      await ev(`${component('KnowledgeAdmin')}.openDocument(${JSON.stringify(expectedId)},${version}).then(()=>true)`);await wait(`${component('KnowledgeAdmin')}.canReviewPdf&&!${component('KnowledgeAdmin')}.detailLoading`);
      const pages=pdfCorrection.split('\n\f\n');assert.equal(pages.length,pdfPageCount);
      const identity=await ev(`({requestId:${component('KnowledgeAdmin')}.nonce,expectedRevision:${component('KnowledgeAdmin')}.loadedRevision,title:${component('KnowledgeAdmin')}.form.title,publishedVersion:${component('KnowledgeAdmin')}.detail.publishedVersion,mode:${component('KnowledgeAdmin')}.sourceInfo.extractionMode})`);
      assert(await ev(`document.querySelectorAll('.knowledge-ocr-review textarea').length===${pdfPageCount} && !${component('KnowledgeAdmin')}.form.reviewedPdf`));
      await ev(`(()=>{const fields=[...document.querySelectorAll('.knowledge-ocr-review textarea')];${JSON.stringify(pages)}.forEach((text,i)=>{fields[i].value=text;fields[i].dispatchEvent(new Event('input',{bubbles:true}));});return true;})()`);
      assert(await ev(`${component('KnowledgeAdmin')}.form.reviewedPdf && ${component('KnowledgeAdmin')}.form.content===${JSON.stringify(pdfCorrection)} && ${component('KnowledgeAdmin')}.dirty`));
      await ev(`${component('KnowledgeAdmin')}.save().then(()=>true)`);await wait(`${component('KnowledgeAdmin')}.selectedVersion===${version+1}&&!${component('KnowledgeAdmin')}.saving`);
      const saved=await ev(`({version:${component('KnowledgeAdmin')}.selectedVersion,publishedVersion:${component('KnowledgeAdmin')}.detail.publishedVersion,mode:${component('KnowledgeAdmin')}.sourceInfo.extractionMode,content:${component('KnowledgeAdmin')}.form.content,error:${component('KnowledgeAdmin')}.error,dirty:${component('KnowledgeAdmin')}.dirty})`);
      assert.equal(saved.publishedVersion,identity.publishedVersion);assert.equal(saved.mode,identity.mode.startsWith('OCR')?'OCR_REVIEWED':'NATIVE_REVIEWED');assert.equal(saved.content,pdfCorrection);assert.equal(saved.error,'');assert.equal(saved.dirty,false);
      await ev(`document.querySelector('.knowledge-source-panel').scrollIntoView({block:'start'});true`);await sleep(250);
      const screenshot=await cdp('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(output,'knowledge-real-pdf-review.png'),Buffer.from(screenshot.data,'base64'));
      assert.equal(errors.length,0,JSON.stringify(errors));return {apiMocked:false,correctedViaActualUi:true,identity,...saved,screenshots:['knowledge-real-pdf-review.png']};
    }
    await cdp('Page.navigate',{url:'http://127.0.0.1:8080/knowledge'});await wait(`!!(${component('Knowledge')}&&${component('Knowledge')}.documents.length)`);
    assert(await ev(`!${component('Knowledge')}.allowModelExcerpts`));await ev(`${component('Knowledge')}.question=${JSON.stringify(question)};${component('Knowledge')}.submit()`);
    await wait(`!!${component('Knowledge')}.result`);assert(await ev(`!${component('Knowledge')}.result.modelUsed && ${component('Knowledge')}.result.items.some(i=>i.documentId===${JSON.stringify(expectedId)}&&i.version===${version})`));
    await sleep(250);let image=await cdp('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(output,'knowledge-real-search.png'),Buffer.from(image.data,'base64'));
    await ev(`(()=>{const link=[...document.querySelectorAll('.knowledge-results a[href]')].find(item=>{const url=new URL(item.href);return url.origin===location.origin&&url.pathname===${JSON.stringify('/knowledge/'+expectedId)}&&Number(url.searchParams.get('version'))===${version}${pdfPage?'&&Number(url.searchParams.get("page"))==='+pdfPage:''}${Number.isFinite(videoTime)?'&&Number(url.searchParams.get("t"))==='+videoTime:''};});if(!link)throw Error('Expected verified source link was not rendered');link.scrollIntoView({block:'center'});link.click();return true;})()`);await wait(`!!(${component('KnowledgeDocument')}&&${component('KnowledgeDocument')}.document)`);
    if(pdfPage){
      assert(await ev(`${component('KnowledgeDocument')}.document.version===${version} && ${component('KnowledgeDocument')}.document.format==='pdf'`));
      await wait(`document.querySelectorAll('[data-pdf-page]').length===${pdfPageCount} && document.activeElement.dataset.pdfPage==='${pdfPage}'`);
      assert(await ev(`!document.querySelector('[data-pdf-page="${pdfPage}"] h1')`));
      if(expectedPdfText)assert(await ev(`document.querySelector('[data-pdf-page="${pdfPage}"]').textContent.includes(${JSON.stringify(expectedPdfText)})`));
      await wait(`(()=>{const page=document.querySelector('[data-pdf-page="${pdfPage}"]');return page&&page.getBoundingClientRect().top>=72&&page.getBoundingClientRect().top<innerHeight-40;})()`);
      fs.writeFileSync(path.join(output,'knowledge-real-pdf-position.json'),JSON.stringify(await ev(`({page:${pdfPage},pageCount:document.querySelectorAll('[data-pdf-page]').length,top:document.querySelector('[data-pdf-page="${pdfPage}"]').getBoundingClientRect().top,height:innerHeight})`),null,2));
    }else if(Number.isFinite(videoTime)){
      await wait(`!!${component('KnowledgeVideo')}`);assert(await ev(`${component('KnowledgeVideo')}.seekAt===${videoTime}&&document.querySelector('.video-captions').textContent.includes('前缀')`));
      await ev(`${component('KnowledgeVideo')}.openVideo()`);await wait(`!!document.querySelector('video')&&document.querySelector('video').readyState>=1`);
      assert(await ev(`Math.abs(document.querySelector('video').currentTime-${videoTime})<0.3&&!document.querySelector('video').src.includes('token')`));
      await wait(`document.querySelector('video').readyState>=2&&!document.querySelector('video').seeking`);
      await ev(`(async()=>{const video=document.querySelector('video');video.muted=true;await video.play();return true;})()`);
      await wait(`document.querySelector('video').currentTime>${videoTime+0.1}`);await ev(`document.querySelector('video').pause();true`);
      const cookies=await cdp('Network.getCookies',{urls:['http://127.0.0.1:8080/api/knowledge/documents/'+expectedId+'/video']});
      assert(cookies.cookies.some(cookie=>cookie.name==='HOJ_KB_VIDEO'&&cookie.httpOnly&&cookie.sameSite==='Strict'&&cookie.path==='/api/knowledge/documents/'+expectedId+'/video'));
    }else {
      assert(await ev(`${component('KnowledgeDocument')}.document.version===${version} && document.querySelector('.knowledge-prose').textContent.includes(${JSON.stringify(expectedText)})`));
      if(await ev(`${component('KnowledgeDocument')}.document.format==='markdown' && typeof ${app}.$route.query.heading==='string'`)){
        let position;const visible=p=>p.headings.some(h=>h.margin==='120px'&&h.text===p.wanted.split(' / ').pop()&&h.top>=72&&h.top<p.height-40);
        for(let attempt=0;attempt<80;attempt++){
          position=await ev(`(()=>{const headings=[...document.querySelectorAll('.knowledge-prose h1,.knowledge-prose h2,.knowledge-prose h3,.knowledge-prose h4,.knowledge-prose h5,.knowledge-prose h6')];return {wanted:${app}.$route.query.heading,scrollY:window.scrollY,height:innerHeight,headings:headings.map(h=>({text:h.getAttribute('data-knowledge-heading'),top:h.getBoundingClientRect().top,margin:h.style.scrollMarginTop}))};})()`);
          if(visible(position))break;await sleep(100);
        }
        fs.writeFileSync(path.join(output,'knowledge-real-heading-position.json'),JSON.stringify(position,null,2));
        assert(visible(position),'The cited Markdown heading must be located and visible below the navigation');
      }
    }
    await sleep(250);image=await cdp('Page.captureScreenshot',{format:'png'});fs.writeFileSync(path.join(output,'knowledge-real-original.png'),Buffer.from(image.data,'base64'));
    assert.equal(errors.length,0,JSON.stringify(errors));return {apiMocked:false,pureRetrieval:true,modelCalled:false,citationLinkFollowed:true,...(Number.isFinite(videoTime)?{nativeVideoPlayed:true,citationSeconds:videoTime,httpOnlyScopedCookie:true}:{}),screenshots:['knowledge-real-search.png','knowledge-real-original.png']};
  }finally{if(ws)ws.close();browser.kill();if(browser.exitCode===null)await Promise.race([new Promise(resolve=>browser.once('exit',resolve)),sleep(3000)]);const target=path.resolve(profile),temporary=path.resolve(os.tmpdir())+path.sep;assert(target.startsWith(temporary)&&path.basename(target).startsWith('hoj-knowledge-live-'));fs.rmSync(target,{recursive:true,force:true,maxRetries:10,retryDelay:100});}
};
