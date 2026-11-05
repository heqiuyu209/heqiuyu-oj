// PDF size, content type and error responses must match the 32 MiB upload contract.
const fs=require('fs'),vm=require('vm'),assert=require('assert').strict,path=require('path');
const source=fs.readFileSync(path.join(__dirname,'../src/common/knowledgeDownload.js'),'utf8').replaceAll('export async function','async function').replaceAll('export function','function');
const context={Blob,module:{exports:{}},setTimeout};vm.runInNewContext(source+';module.exports={knowledgePdfBlob};',context);
const {knowledgePdfBlob}=context.module.exports;
const response=(size,type='application/pdf',prefix='%PDF-')=>({status:200,headers:{'content-type':type},data:new Blob([prefix,new Uint8Array(Math.max(0,size-prefix.length))],{type})});
(async()=>{
 for(const size of [8*1024**2+1,32*1024**2])assert.equal((await knowledgePdfBlob(response(size))).size,size);
 await assert.rejects(knowledgePdfBlob(response(32*1024**2+1)),/响应无效/);
 await assert.rejects(knowledgePdfBlob(response(100,'application/pdf','<html')),/响应无效/);
 await assert.rejects(knowledgePdfBlob({status:403,headers:{'content-type':'application/json'},data:new Blob([JSON.stringify({msg:'资料已撤回'})],{type:'application/json'})}),/资料已撤回/);
 await assert.rejects(knowledgePdfBlob(response(100,'text/html')),/不可用/);
 console.log('PDF download checks passed: 8+ MiB, 32 MiB, oversize, signature, permission and MIME');
})().catch(e=>{console.error(e);process.exitCode=1;});
