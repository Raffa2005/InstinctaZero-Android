// Actual native SQLite results for the same extension from informational/regular parents.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const fixture=JSON.parse(await readFile(process.env.REPERTOIRE_EDIT_PREVIEW || '/tmp/instinctazero-v086-picture.json','utf8'));
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-v086-unified';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const file=req.url==='/'?'index.html':req.url.slice(1);if(file.includes('..'))throw Error();const body=await readFile(new URL(file,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[file.split('.').at(-1)]||'application/octet-stream'}).end(body)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true,executablePath:process.env.PHONE_PREVIEW_CHROMIUM});
try{for(const [width,height]of [[360,640],[390,780],[412,844]]){
 const appearances=[];
 for(const regular of [false,true]){
  const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(({f,regular})=>{
   const initial=f.unified[regular?'regular_initial':'initial'],added=f.unified[regular?'regular_added':'added'];let seq=0;
   window.__test={saved:'{}',edits:[],added:false};
   const emit=value=>{const id='r'+ ++seq;setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,value),10);return id};
   window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":false}',saveUiSettings:()=>{},cancelAnalysis:()=>{},
    getStudyState:()=>window.__test.saved,saveStudyState:raw=>window.__test.saved=raw,
    getRepertoireSettings:()=>'{"_selected":["taimanov"],"analysis":["taimanov"]}',saveRepertoireSettings:()=>{},
    requestRepertoire:raw=>{
     const r=JSON.parse(raw);if(r.action==='catalog')return emit({installed:true,repertoires:[{id:'taimanov',name:'Taimanov',side:'black'}]});
     if(r.action==='edit'){window.__test.edits.push(r);window.__test.added=true;return emit({saved:true,undo:{token:'1',name:'Taimanov',label:'Added 5 moves'}})}
     const facts=(window.__test.added?added:initial).find(v=>v.fen===r.fen);
     return emit({results:facts?[facts]:[{id:'taimanov',name:'Taimanov',side:'black',fen:r.fen,theory:false,known:false,moves:[],comments:[]}]});
    }};
   window.__load=()=>window.InstinctaZero.loadArchivedGame({id:'Unified1',moves:f.extension.history.slice(0,17).map(uci=>({uci})),white:{name:'White'},black:{name:'Black'},mobile_orientation:'black'});
  },{f:fixture,regular});
  await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
  await page.evaluate(()=>window.__load());await page.evaluate(()=>window.InstinctaZero.openRepertoires());
  // Play onward on the actual board. No preparatory save at the informational parent.
  for(const [i,uci]of fixture.extension.history.slice(17).entries()){
   const bounds=await page.locator('#board').boundingBox();
   for(const square of [uci.slice(0,2),uci.slice(2,4)]){
    const x=7-(square.charCodeAt(0)-97),y=Number(square[1])-1;
    await page.touchscreen.tap(bounds.x+(x+.5)*bounds.width/8,bounds.y+(y+.5)*bounds.height/8);
   }
   await page.waitForFunction(n=>JSON.parse(window.__test.saved).cursor.length===n,18+i);
   await page.locator('[data-rep-action=quick-add]').waitFor();
   assert.match(await page.locator('[data-rep-action=quick-add]').innerText(),i===0?/Add move to repertoire/:/Add line to repertoire/);
   assert.equal(await page.evaluate(()=>window.__test.edits.length),0);
   assert.doesNotMatch(await page.locator('#panel').innerText(),/just.played|PC annotations|independent response/i);
  }
  await page.screenshot({path:`${output}/before-${regular?'regular':'information'}-${width}.png`});
  await page.locator('[data-rep-action=quick-add]').tap();await page.getByRole('button',{name:'Comment on added line'}).waitFor();
  const edits=await page.evaluate(()=>window.__test.edits);assert.equal(edits.length,1);assert.equal(edits[0].kind,'add');assert.deepEqual(edits[0].history,fixture.extension.history);
  assert.equal(edits[0].entries.length,22);assert.equal(edits[0].fen,fixture.extension.fen);
  const rows=[];
  for(let i=0;i<5;i++){
   await page.locator('[data-action=prev]').tap();await page.locator(`[data-rep-play="${fixture.extension.history[21-i]}"]`).waitFor();
   rows.push(await page.locator(`[data-rep-play="${fixture.extension.history[21-i]}"]`).evaluate(el=>({html:el.innerHTML,classes:el.parentElement.className,color:getComputedStyle(el).color,font:getComputedStyle(el).fontSize})));
   if(i<4)assert.equal(await page.locator('.repertoire-book-marker').evaluate(el=>el.hidden),false);
   assert.doesNotMatch(rows.at(-1).html,/informational|alternative/i);
  }
  appearances.push(rows);await page.waitForTimeout(200);await page.screenshot({path:`${output}/saved-${regular?'regular':'information'}-${width}.png`});
  assert.deepEqual(errors,[]);await page.close();
 }
 assert.deepEqual(appearances[0],appearances[1]);console.log(`${width}×${height}: identical Add move/line controls and saved move rows/markers; five touch-played plies, one ordinary save in both cases`);
}}finally{await browser.close();await new Promise(r=>server.close(r))}
