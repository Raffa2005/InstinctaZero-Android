// Touch UI over responses exported by the real native book tests. No handset used.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {chromium} from 'playwright';
const fixture=JSON.parse(await readFile(process.env.REPERTOIRE_REENTRY_PREVIEW || '/tmp/instinctazero-v088-reentry.json','utf8'));
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-v088-reentry-ui';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const file=req.url==='/'?'index.html':req.url.slice(1);if(file.includes('..'))throw Error();const body=await readFile(new URL(file,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[file.split('.').at(-1)]||'application/octet-stream'}).end(body)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true,executablePath:process.env.PHONE_PREVIEW_CHROMIUM});
try { for(const [width,height] of [[360,640],[390,780],[412,844]]) {
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(f=>{
  let seq=0;window.__test={saved:'{}',requests:[]};const settings={_selected:['book']};
  window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":false}',saveUiSettings:()=>{},getStudyState:()=>window.__test.saved,saveStudyState:v=>window.__test.saved=v,getRepertoireSettings:()=>JSON.stringify(settings),saveRepertoireSettings:v=>Object.assign(settings,JSON.parse(v)),cancelRepertoireLookup:()=>{},cancelAnalysis:()=>{},
   requestRepertoire:raw=>{const r=JSON.parse(raw),id=String(++seq);window.__test.requests.push(r);let data;
    if(r.action==='catalog')data={installed:true,repertoires:[{id:'book',name:'Fixture',side:'black'}]};
    else {const entry=f.positions.find(e=>e.request.fen===r.fen);data={results:entry?[structuredClone(entry.result)]:[]};}
    setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,data),15);return id;}};
  window.__open=()=>window.InstinctaZero.loadArchivedGame({id:'Reentry1',moves:f.positions[0].request.history.map(uci=>({uci})),white:{name:'White'},black:{name:'Black'},mobile_orientation:'black'});
 },fixture);
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
 await page.evaluate(()=>{window.__open();window.InstinctaZero.openRepertoires()});
 await page.locator('[data-rep-play=g8f6]').waitFor();await page.locator('[data-rep-play=e7e6]').waitFor();
 assert.equal(await page.locator('[data-rep-play=g8f6]').count(),1);assert.equal(await page.locator('.orientation-black').count(),1);
 assert.equal(await page.locator('.repertoire-book-marker.end-of-line').count(),0);
 await page.waitForTimeout(180);await page.screenshot({path:`${output}/union-black-${width}.png`});
 await page.locator('[data-rep-play=g8f6]').tap();await page.locator('[data-rep-play=c2c4]').waitFor();
 assert.match(await page.locator('#panel').innerText(),/My private note/);
 await page.locator('[data-rep-comment=current]').tap();
 assert.match(await page.locator('.rep-comments').innerText(),/Source note/);
 assert.match(await page.locator('.rep-comments').innerText(),/Destination source note/);
 assert.equal(await page.locator('.repertoire-book-marker.end-of-line').count(),0);
 await page.locator('[data-action=flip]').tap();await page.waitForTimeout(180);
 await page.screenshot({path:`${output}/destination-white-${width}.png`});
 // Source clarifications coexist with the personal note; editing targets only personal text.
 await page.locator('[data-rep-note-edit="book:current"]').tap();
 assert.equal(await page.locator('[data-rep-comment-input]').inputValue(),'My private note.');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
 console.log(`${width}×${height}: missing-edge union, touch play, destination comments/continuation and both orientations passed`);await page.close();
}} finally { await browser.close();await new Promise(r=>server.close(r)); }
