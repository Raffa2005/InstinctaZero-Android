// Render the actual packaged page. Changing the palette must not move any existing control.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-palette';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const name=req.url==='/'?'index.html':req.url.slice(1);if(name.includes('..'))throw Error();const body=await readFile(new URL(name,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[name.split('.').at(-1)]||'application/octet-stream'}).end(body)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,executablePath:process.env.PHONE_PREVIEW_CHROMIUM});
const results=[];
try{for(const [width,height] of [[320,568],[360,640],[390,780],[412,844]]){
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  let seq=0;window.__test={};window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":true}',saveUiSettings:()=>{},cancelAnalysis:()=>{},
   getStudyState:()=>localStorage.getItem('study') || '{}',saveStudyState:raw=>{localStorage.setItem('study',raw);return true},
   getRepertoireSettings:()=>'{"_selected":[],"analysis":[]}',saveRepertoireSettings:()=>{},
   startAnalysis:()=>{window.__test.engine='engine'+ ++seq;return window.__test.engine},
   requestExplorer:()=>{const id='book'+ ++seq;setTimeout(()=>window.InstinctaZero.onNativeExplorer(id,{totals:{games:100,white:40,draws:30,black:30},moves:[{uci:'e2e4',san:'e4',games:60,white:25,draws:20,black:15},{uci:'d2d4',san:'d4',games:40,white:15,draws:10,black:15}]}),0);return id},
  };
 });
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>window.InstinctaZero && !document.documentElement.classList.contains('board-assets-loading'));
 await page.evaluate(()=>document.fonts.ready);
 await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));await page.waitForFunction(()=>window.__test.engine);
 await page.evaluate(()=>window.InstinctaZero.onNativeAnalysis(window.__test.engine,{event:'lc0',data:{search_phase:'final',progress:{visits:1300,target:2000},move_stats:[{uci:'e2e4',visits:1000,q:.18,prior:.5},{uci:'d2d4',visits:300,q:.15,prior:.3}],lines:[{pv:['e2e4','e7e5','g1f3'],san:['e4','e5','Nf3'],white_cp:27,visits:1000,nodes:1000,q:.18,prior:.5},{pv:['d2d4','d7d5'],san:['d4','d5'],white_cp:18,visits:300,nodes:300,q:.15,prior:.3}]}}));
 await page.waitForFunction(()=>document.querySelectorAll('.pv').length===2 && document.querySelectorAll('.analysis-arrow').length>0);
 const check=async name=>{
  const result=await page.evaluate(()=>{
   const palette=[...document.styleSheets].find(s=>s.href?.endsWith('/palette.css'));
   const geometry=()=>[...document.querySelectorAll('body *')].filter(el=>el instanceof HTMLElement && el.getClientRects().length && !el.closest('.spinner')).map(el=>{const r=el.getBoundingClientRect();return [el.tagName,el.className,...[r.x,r.y,r.width,r.height].map(n=>Math.round(n*100)/100)]});
   const semantic=()=>[...document.querySelectorAll('.cg-board,piece,li-coord,.analysis-arrow,.result-bar rect,.result-bar text')].map(el=>{const s=getComputedStyle(el);return el.matches('.cg-board')?[s.backgroundColor,s.backgroundImage]:el.matches('piece')?[s.backgroundImage]:el.matches('li-coord')?[s.color]:[s.fill,s.stroke,s.opacity,s.strokeWidth]});
   const current=geometry(),currentSemantic=semantic();palette.disabled=true;
   const previous=geometry(),previousSemantic=semantic();palette.disabled=false;
   const background=getComputedStyle(document.body).backgroundColor;
   return {current,previous,currentSemantic,previousSemantic,background};
  });
  assert.deepEqual(result.current,result.previous,`${name}: existing layout must not change`);
  assert.deepEqual(result.currentSemantic,result.previousSemantic,`${name}: board, arrows and result colours must not change`);
  assert.equal(result.background,'rgb(9, 9, 13)');
  await page.evaluate(()=>Promise.all(document.getAnimations().filter(a=>a.effect?.getComputedTiming().endTime!==Infinity).map(a=>a.finished.catch(()=>{}))));
  await page.screenshot({path:`${output}/${name}-${width}.png`});
 };
 await check('engine');
 await page.getByRole('button',{name:'Settings',exact:true}).tap();await check('engine-settings');
 await page.getByRole('button',{name:'Book',exact:true}).tap();await page.locator('.book-move').first().waitFor();await check('book');
 await page.getByRole('button',{name:'Settings',exact:true}).tap();await check('book-settings');
 await page.getByRole('button',{name:'Moves',exact:true}).tap();await check('notation');
 await page.getByRole('button',{name:'More',exact:true}).tap();await check('analysis-menu');
 await page.evaluate(()=>window.InstinctaZero.openBoardEditor());await page.locator('#position-editor').waitFor();await check('editor');
 await page.getByRole('button',{name:'State',exact:true}).tap();await check('editor-state');
 assert.deepEqual(errors,[]);results.push({width,height,layout:'unchanged',boardAndSemanticColours:'unchanged',errors});await page.close();
}}finally{await browser.close();server.close();}
await writeFile(`${output}/results.json`,JSON.stringify(results,null,2));console.log(JSON.stringify(results));
