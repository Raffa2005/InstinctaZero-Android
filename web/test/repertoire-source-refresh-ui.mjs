// Private real-source refresh response, rendered with the installed release assets.
import assert from 'node:assert/strict';
import {readFile,mkdir} from 'node:fs/promises';
import {createServer} from 'node:http';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const fixture=JSON.parse(await readFile(process.env.REPERTOIRE_CLARIFIED_PREVIEW,'utf8')).positions[0];
const assets=pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/');
const output=process.env.PHONE_PREVIEW_OUTPUT;await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const p=req.url==='/'?'index.html':req.url.slice(1);if(p.includes('..'))throw Error();const data=await readFile(new URL(p,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[p.split('.').at(-1)]||'application/octet-stream'}).end(data)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true,executablePath:process.env.PHONE_PREVIEW_CHROMIUM});
try {for(const [width,height]of [[360,640],[390,780],[412,844]]) {
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(f=>{
  let sequence=0;window.__saved=localStorage.getItem('study') || '{}';
  window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":false}',saveUiSettings:()=>{},cancelAnalysis:()=>{},cancelRepertoireLookup:()=>{},getStudyState:()=>window.__saved,saveStudyState:v=>{window.__saved=v;localStorage.setItem('study',v)},getRepertoireSettings:()=>JSON.stringify({_selected:[f.result.id]}),saveRepertoireSettings:()=>{},
   requestRepertoire:raw=>{const r=JSON.parse(raw),id=String(++sequence);const data=r.action==='catalog'?{installed:true,repertoires:[{id:f.result.id,name:f.result.name,side:f.result.side}]}:{results:r.fen===f.request.fen?[f.result]:[]};setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,data),10);return id;}};
  window.__open=()=>window.InstinctaZero.loadArchivedGame({id:'Refresh1',initial_fen:f.request.root,moves:f.request.history.map(uci=>({uci})),white:{name:'White'},black:{name:'Black'},mobile_orientation:'black'});
 },fixture);
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
 await page.evaluate(()=>{window.__open();window.InstinctaZero.openRepertoires()});
 for(let pass=0;pass<2;pass++) {
  await page.locator('[data-rep-comment=current]').waitFor();await page.locator('[data-rep-comment=current]').tap();
  const text=await page.locator('.rep-comments').innerText();assert.ok(text.includes('My current personal note.'));assert.ok(text.includes('Source note'));
  for(const comment of fixture.result.source_comments)assert.ok(text.includes(comment),'Full updated source comment, no truncation');
  await page.waitForTimeout(180);await page.screenshot({path:`${output}/source-and-personal-${width}-${pass}.png`});
  await page.locator('[data-rep-note-edit]').first().tap();assert.equal(await page.locator('[data-rep-comment-input]').inputValue(),'My current personal note.');
  if(!pass){await page.reload();await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));}
 }
 assert.deepEqual(errors,[]);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
 console.log(`${width}×${height}: actual refreshed source clarification and unchanged personal editor/reopen passed`);await page.close();
}}finally{await browser.close();await new Promise(r=>server.close(r));}
