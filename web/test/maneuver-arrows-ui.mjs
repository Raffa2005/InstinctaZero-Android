import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-v087-maneuvers';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const file=req.url==='/'?'index.html':req.url.slice(1);if(file.includes('..'))throw Error();const body=await readFile(new URL(file,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[file.split('.').at(-1)]||'application/octet-stream'}).end(body)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true,executablePath:process.env.PHONE_PREVIEW_CHROMIUM});const metrics=[];
try{for(const [width,height]of [[360,640],[390,780],[412,844]]){
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const cdp=await page.context().newCDPSession(page);await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});
 await page.addInitScript(()=>{
  const settings=JSON.parse(localStorage.getItem('settings') || '{"leelaEnabled":true,"arrowCount":8}');let seq=0;
  window.__test={requests:[],cancelled:[],saved:localStorage.getItem('study') || '{}',settings};
  window.InstinctaZeroNative={getDisplayPrivacy:()=>'{"enabled":true,"alias":"Player","names":[]}',
   getUiSettings:()=>JSON.stringify(settings),saveUiSettings:raw=>{Object.assign(settings,JSON.parse(raw));localStorage.setItem('settings',raw)},
   getStudyState:()=>window.__test.saved,saveStudyState:raw=>{window.__test.saved=raw;localStorage.setItem('study',raw)},
   getRepertoireSettings:()=>'{"_selected":[],"analysis":[]}',saveRepertoireSettings:()=>{},
   startAnalysis:raw=>{const id='engine'+ ++seq;window.__test.requests.push({id,...JSON.parse(raw)});return id},cancelAnalysis:id=>window.__test.cancelled.push(id)};
  const pv=['g1f3','g8f6','f3h4','f6g4','h4f5','g4h6','f5d6'];
  window.__emit=(id=window.__test.requests.at(-1).id)=>{
   const request=window.__test.requests.find(r=>r.id===id),plies=request.history.length;
   const chess=new window.InstinctaZeroChessRules.Chess();for(const uci of request.history)chess.move({from:uci.slice(0,2),to:uci.slice(2,4)});
   const line=(moves,visits,q)=>{const replay=new window.InstinctaZeroChessRules.Chess(chess.fen());const san=moves.map(uci=>replay.move({from:uci.slice(0,2),to:uci.slice(2,4)}).san);return {pv:moves,san,white_cp:20,nodes:visits,visits,q}};
   const lines=[line(pv.slice(plies),1000,.2)];
   if(!plies)lines.push(line(['b1c3'],300,.18),line(['e2e4'],80,-.4),line(['d2d4'],15,.16));
   window.InstinctaZero.onNativeAnalysis(id,{event:'lc0',data:{search_phase:'final',lines,move_stats:lines.map(l=>({uci:l.pv[0],visits:l.visits,q:l.q,prior:.2})),progress:{visits:1395,target:1000}}});
  };
 });
 const start=async()=>{await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));await page.waitForFunction(()=>window.__test.requests.length>0);await page.evaluate(()=>window.__emit())};
 const geometry=async(expected,black=false)=>{
  await page.waitForFunction(n=>document.querySelectorAll('.analysis-arrow.blue').length===n,expected.length);
  const actual=await page.locator('.analysis-arrow.blue').evaluateAll(els=>els.map(el=>['x1','y1','x2','y2'].map(a=>Number(el.getAttribute(a)))));
  const point=s=>[(black?7-(s.charCodeAt(0)-97):s.charCodeAt(0)-97)*64+32,(black?Number(s[1])-1:8-Number(s[1]))*64+32];
  expected.forEach((uci,i)=>{const a=point(uci.slice(0,2)),b=point(uci.slice(2,4)),length=Math.hypot(b[0]-a[0],b[1]-a[1]);
   const value=[...a,b[0]-(b[0]-a[0])/length*10,b[1]-(b[1]-a[1])/length*10];value.forEach((n,k)=>assert.ok(Math.abs(n-actual[i][k])<.000001,`${uci}: orientation/endpoints differ`));});
 };
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));await start();
 await geometry(['g1f3']);assert.equal(await page.locator('.analysis-arrow.grey').count(),2);
 const grey=await page.locator('.analysis-arrow.grey').evaluateAll(es=>es.map(e=>e.getAttribute('stroke-width')));
 await page.locator('[data-action=settings]').tap();const requests=await page.evaluate(()=>window.__test.requests.length);
 await page.getByRole('button',{name:'Best + maneuver',exact:true}).tap();await geometry(['g1f3','f3h4','h4f5']);
 assert.equal(await page.evaluate(()=>window.__test.requests.length),requests,'display mode must not restart the engine');
 assert.deepEqual(await page.locator('.analysis-arrow.grey').evaluateAll(es=>es.map(e=>e.getAttribute('stroke-width'))),grey);
 assert.equal(await page.getByRole('button',{name:'Best + maneuver',exact:true}).getAttribute('aria-pressed'),'true');
 const panel=await page.locator('#panel').boundingBox();
 for(const box of await page.locator('.compact-settings button,.compact-settings input').evaluateAll(es=>es.map(e=>{const r=e.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right}}))){assert.ok(box.top>=panel.y && box.bottom<=panel.y+panel.height+1,'settings control must fit without scrolling');assert.ok(box.left>=0 && box.right<=width)}
 await page.screenshot({path:`${output}/settings-${width}.png`});
 const count=async value=>{await page.locator('[data-arrow-count]').evaluate((el,v)=>{el.value=v;el.dispatchEvent(new Event('input'));el.dispatchEvent(new Event('change'))},String(value));};
 await count(2);await geometry(['g1f3','f3h4']);assert.equal(await page.locator('.analysis-arrow.grey').count(),0);
 await count(1);await geometry(['g1f3']);await count(8);
 await page.getByRole('switch',{name:'Arrows',exact:true}).tap();assert.equal(await page.locator('.analysis-arrow').count(),0);
 await page.getByRole('switch',{name:'Arrows',exact:true}).tap();await geometry(['g1f3','f3h4','h4f5']);
 await page.getByRole('switch',{name:'Leela',exact:true}).tap();assert.equal(await page.locator('.analysis-arrow').count(),0);
 const off=await page.evaluate(()=>window.__test.requests.length);await page.getByRole('switch',{name:'Leela',exact:true}).tap();await page.waitForFunction(n=>window.__test.requests.length>n,off);await page.evaluate(()=>window.__emit());
 await page.locator('[data-action=settings]').tap();await geometry(['g1f3','f3h4','h4f5']);await page.screenshot({path:`${output}/white-${width}.png`});
 await page.locator('[data-action=flip]').tap();await geometry(['g1f3','f3h4','h4f5'],true);await page.screenshot({path:`${output}/black-${width}.png`});
 const stale=await page.evaluate(()=>window.__test.requests.at(-1).id);
 // Actual touch moves pass through the arrows. Projected PV shows the opponent's
 // maneuver immediately, before any new engine response is supplied.
 const box=await page.locator('#board').boundingBox();for(const square of ['g1','f3']){
  await page.touchscreen.tap(box.x+(7-(square.charCodeAt(0)-97)+.5)*box.width/8,box.y+(Number(square[1])-.5)*box.height/8);
 }
 await page.waitForFunction(()=>JSON.parse(window.__test.saved).cursor?.join(' ')==='g1f3');
 await geometry(['g8f6','f6g4','g4h6'],true);
 await page.evaluate(id=>window.__emit(id),stale);await geometry(['g8f6','f6g4','g4h6'],true);
 await page.waitForFunction(id=>window.__test.requests.at(-1).id!==id,stale);await page.evaluate(()=>window.__emit());await geometry(['g8f6','f6g4','g4h6'],true);
 const rapid=await page.evaluate(()=>{const start=performance.now();for(let i=0;i<16;i++){const b=document.querySelector('[data-action='+(i%2?'next':'prev')+']');b.dispatchEvent(new PointerEvent('pointerdown',{pointerId:77,pointerType:'touch'}));b.dispatchEvent(new PointerEvent('pointerup',{pointerId:77,pointerType:'touch'}));}return performance.now()-start});
 await geometry(['g8f6','f6g4','g4h6'],true);await page.waitForTimeout(160);
 assert.deepEqual(await page.evaluate(()=>window.__test.requests.at(-1).history),['g1f3']);await page.evaluate(()=>window.__emit());
 // A new position without cached analysis clears old arrows immediately.
 await page.locator('[data-action=prev]').tap();await geometry(['g1f3','f3h4','h4f5'],true);
 const origin=await page.evaluate(()=>window.__test.requests.at(-1).id);
 for(const square of ['a2','a3'])await page.touchscreen.tap(box.x+(7-(square.charCodeAt(0)-97)+.5)*box.width/8,box.y+(Number(square[1])-.5)*box.height/8);
 await page.waitForFunction(()=>JSON.parse(window.__test.saved).cursor?.join(' ')==='a2a3');
 assert.equal(await page.locator('.analysis-arrow').count(),0);await page.evaluate(id=>window.__emit(id),origin);assert.equal(await page.locator('.analysis-arrow').count(),0);
 await page.locator('[data-action=prev]').tap();await page.waitForTimeout(180);
 const saved=await page.evaluate(()=>JSON.parse(window.__test.saved));await page.reload();await start();
 await geometry(['g1f3','f3h4','h4f5'],true);assert.deepEqual(await page.evaluate(()=>JSON.parse(window.__test.saved).tree),saved.tree);
 await page.locator('[data-action=settings]').tap();await page.getByRole('button',{name:'Best moves',exact:true}).tap();await geometry(['g1f3'],true);
 await page.reload();await start();await geometry(['g1f3'],true);assert.equal(await page.evaluate(()=>window.__test.settings.arrowMode),'best');
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
 metrics.push({width,height,rapid16NavigationMs:rapid});console.log(`${width}×${height}: web maneuvers, weighted alternatives/cap, touch controls, both orientations, inherited/stale/cleared positions, restart and rapid navigation passed`);await page.close();
}}finally{await browser.close();await new Promise(r=>server.close(r))}
await writeFile(`${output}/timings.json`,JSON.stringify({environment:'Touch Chromium with 4× CPU throttle and mocked engine transport; not phone timings',metrics},null,2));
