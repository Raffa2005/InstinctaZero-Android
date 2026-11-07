import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-input-book';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const name=req.url==='/'?'index.html':req.url.slice(1);if(name.includes('..'))throw Error();const body=await readFile(new URL(name,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[name.split('.').at(-1)]||'application/octet-stream'}).end(body)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,executablePath:process.env.PHONE_PREVIEW_CHROMIUM});
const results=[];
try{for(const [width,height] of [[360,640],[390,780],[412,844]]) {
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const cdp=await page.context().newCDPSession(page);await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});
 await page.addInitScript(()=>{
  let seq=0;window.__t={saving:true,analysis:[],books:[],ranks:0};
  window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":true}',getStudyState:()=>localStorage.getItem('study') || '{}',
   saveStudyState:raw=>{if(window.__t.saving)localStorage.setItem('study',raw);return true},getRepertoireSettings:()=>'{}',
   startAnalysis:raw=>{const id='engine-'+ ++seq;window.__t.analysis.push({id,...JSON.parse(raw)});return id},cancelAnalysis:()=>{},
   requestExplorer:raw=>{const id='book-'+ ++seq;window.__t.books.push({id,...JSON.parse(raw)});return id}};
 });
 const url=`http://127.0.0.1:${server.address().port}`;await page.goto(url);
 const load=async(fen,tab='moves',black=false)=>{
  await page.evaluate(({fen,tab,black})=>{window.__t.saving=false;localStorage.setItem('study',JSON.stringify({v:1,initialFen:fen,tree:[],cursor:[],tab,black,title:'Local analysis'}))},{fen,tab,black});
  await page.reload();await page.waitForFunction(()=>!!window.InstinctaZero && !document.documentElement.classList.contains('board-assets-loading'));
  await page.evaluate(()=>{window.InstinctaZero.setAnalysisActive(true);const rank=window.InstinctaClickChess.ranker.rank.bind(window.InstinctaClickChess.ranker);window.InstinctaClickChess.ranker.rank=(...args)=>{window.__t.ranks++;return rank(...args)}});
  await page.waitForFunction(()=>window.__t.analysis.length>0);
 };
 const stored=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('study')));
 const point=square=>page.evaluate(square=>{const b=document.querySelector('#board').getBoundingClientRect(),black=document.querySelector('#board-wrap').classList.contains('orientation-black'),f=square.charCodeAt(0)-97,r=Number(square[1])-1;return {x:b.x+(black?7-f:f)*b.width/8+b.width/16,y:b.y+(black?r:7-r)*b.height/8+b.height/16}},square);
 const tap=async square=>{const p=await point(square);await page.touchscreen.tap(p.x,p.y)};
 const drag=async(from,to)=>{const a=await point(from),b=await point(to);await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...a,id:1}]});await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{...b,id:1}]});await page.waitForTimeout(40);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]})};
 const emit=(data,id=null,event='lc0')=>page.evaluate(({data,id,event})=>window.InstinctaZero.onNativeAnalysis(id || window.__t.analysis.at(-1).id,{event,data}),{data,id,event});
 const bookMove=async(uci,san)=>{
  await page.waitForFunction(()=>window.__t.books.length>0);
  await page.evaluate(({uci,san})=>window.InstinctaZero.onNativeExplorer(window.__t.books.at(-1).id,{totals:{games:10,white:5,draws:3,black:2},moves:[{uci,san,games:10,white:5,draws:3,black:2}]}),{uci,san});
  await page.locator(`[data-uci="${uci}"]`).tap();
 };
 for(const turn of ['w','b'])for(const side of ['k','q']) {
  const rank=turn==='w'?'1':'8',fen=`r3k2r/8/8/8/8/8/8/R3K2R ${turn} KQkq - 0 1`;
  const raw=`e${rank}${side==='k'?'h':'a'}${rank}`,canonical=`e${rank}${side==='k'?'g':'c'}${rank}`;
  await load(fen,'book',turn==='b');await bookMove(raw,side==='k'?'O-O':'O-O-O');
  assert.deepEqual((await stored()).cursor,[canonical],`${turn} ${side}: explorer king-to-rook castle must play`);
  await page.getByRole('button',{name:'Previous move',exact:true}).tap();assert.deepEqual((await stored()).cursor,[]);
  await page.getByRole('button',{name:'Next move',exact:true}).tap();assert.deepEqual((await stored()).cursor,[canonical]);
  const game=await page.evaluate(()=>{const state=JSON.parse(localStorage.getItem('study')),c=new window.InstinctaZeroChessRules.Chess(state.initialFen);for(const u of state.cursor)c.move({from:u.slice(0,2),to:u.slice(2,4)});return c.fen()});
  assert.match(game,turn==='w'?(side==='k'?/R4RK1/:/2KR3R/):(side==='k'?/r4rk1/:/2kr3r/));
  await page.screenshot({path:`${output}/castle-${turn}-${side}-${width}.png`});
 }
 const start='rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
 await load(start,'book');await bookMove('e2e4','e4');assert.deepEqual((await stored()).cursor,['e2e4']);
 await page.getByRole('button',{name:'Previous move',exact:true}).tap();assert.deepEqual((await stored()).cursor,[]);
 // Root statistics can be usable before any line is eligible for display.
 await load(start);const rootId=await page.evaluate(()=>window.__t.analysis.at(-1).id);
 await emit({search_phase:'ready',root_move_stats:[{uci:'c2c3',visits:30,prior:.1},{uci:'b1c3',visits:2,prior:.9}],lines:[]});
 await tap('c3');assert.deepEqual((await stored()).cursor,['c2c3']);assert.equal(await page.evaluate(()=>window.__t.ranks),0);
 await tap('c3');assert.deepEqual((await stored()).cursor,['c2c3'],'tap alone never cycles');
 await drag('c3','c2');assert.deepEqual((await stored()).cursor,[]);assert.equal((await stored()).tree.length,0);
 // The original candidate order stays fixed through drag-back, even if new
 // evidence arrives. No preparatory action and no discarded leaf accumulation.
 await page.waitForFunction(id=>window.__t.analysis.at(-1).id!==id && window.__t.analysis.at(-1).history.length===0,rootId);
 await emit({root_move_stats:[{uci:'b1c3',visits:1000,prior:.9},{uci:'c2c3',visits:1,prior:.1}]});
 await tap('c3');assert.deepEqual((await stored()).cursor,['b1c3']);assert.equal((await stored()).tree.length,1);
 await drag('c3','b1');await tap('c3');assert.deepEqual((await stored()).cursor,['c2c3'],'cycle wraps in original ranking order');
 await page.getByRole('button',{name:'Previous move',exact:true}).tap();assert.equal((await stored()).tree.length,1,'ordinary Previous keeps the leaf');
 await tap('c3');assert.deepEqual((await stored()).cursor,['b1c3'],'Previous clears cycling; fresh tap uses matching cached engine data');
 // A late reply for the previous position must not be stamped onto this one.
 await page.waitForFunction(()=>window.__t.analysis.at(-1).history.length===1);
 await emit({root_move_stats:[{uci:'f7f6',visits:1000000,prior:1}]},rootId);
 await tap('f6');assert.equal(await page.evaluate(()=>window.__t.ranks),1,'wrong-position response cannot replace offline fallback');
 await load(start.replace(' w ',' b '),'moves',true);
 await emit({search_phase:'ready',root_move_stats:[{uci:'f7f6',visits:0,prior:.9},{uci:'g8f6',visits:0,prior:.1}],lines:[]});
 await tap('f6');assert.deepEqual((await stored()).cursor,['f7f6']);assert.equal(await page.evaluate(()=>window.__t.ranks),0,'policy-only current engine evidence is used');
 await load(start);await tap('c3');assert.equal(await page.evaluate(()=>window.__t.ranks),1,'without evidence the unchanged offline model runs');
 await load(start);await emit({final_snapshot:{root_move_stats:[{uci:'c2c3',visits:12}],lines:[]}},null,'done');
 await tap('c3');assert.deepEqual((await stored()).cursor,['c2c3'],'final snapshots also feed current-position input');
 const inputCache=await page.evaluate(()=>{
  const c=new window.InstinctaZeroChessRules.Chess(),legal=c.moves({verbose:true}),cache=new window.InstinctaMoveInput.EngineCache();
  const data={root_move_stats:legal.map((m,i)=>({uci:m.from+m.to,visits:i,prior:(i+1)/210}))},samples=[];
  for(let i=0;i<100;i++){const start=performance.now();cache.remember(c.fen(),'cpu',legal,data);cache.rank(c.fen(),'cpu',legal,'c3');samples.push(performance.now()-start);}
  samples.sort((a,b)=>a-b);return {median_ms:samples[50],p95_ms:samples[95],note:'desktop Chromium, 4x CPU throttle; not a phone benchmark'};
 });
 assert.deepEqual(errors,[]);results.push({width,height,inputCache,checks:'four explorer castles; ordinary history; engine visits/policy/cache/final snapshot; drag correction; stale/offline fallback'});await page.close();
}}finally{await browser.close();server.close();}
await writeFile(`${output}/results.json`,JSON.stringify(results,null,2));console.log(JSON.stringify(results));
