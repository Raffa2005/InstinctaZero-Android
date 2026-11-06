import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-game-load-preview';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const file=req.url==='/'?'index.html':req.url.slice(1);if(file.includes('..'))throw Error();const data=await readFile(new URL(file,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[file.split('.').at(-1)]||'application/octet-stream'}).end(data)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,...(process.env.PHONE_PREVIEW_CHROMIUM?{executablePath:process.env.PHONE_PREVIEW_CHROMIUM}:{})});const measurements=[];
try{for(const [width,height] of [[360,640],[390,780],[412,844]]){
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const cdp=await page.context().newCDPSession(page);await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});
 await page.addInitScript(()=>{
  let sequence=0;window.__bridge={requests:[],engineRequests:[],canceled:0,saves:[],saved:localStorage.getItem('study') || '{}',old:null,longTasks:[]};
  new PerformanceObserver(list=>window.__bridge.longTasks.push(...list.getEntries().map(e=>e.duration))).observe({type:'longtask',buffered:true});
  const emit=(id,data)=>window.InstinctaZero.onNativeRepertoire(id,JSON.stringify(data));
  window.__bridge.emit=emit;
  window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":true}',saveUiSettings:()=>{},cancelAnalysis:()=>{},
   startAnalysis:raw=>{window.__bridge.engineRequests.push(JSON.parse(raw));return 'engine-'+ ++sequence},
   getStudyState:()=>window.__bridge.saved,saveStudyState:raw=>{window.__bridge.saved=raw;window.__bridge.saves.push(JSON.parse(raw).gameId);localStorage.setItem('study',raw)},
   getRepertoireSettings:()=>'{"analysis":["r"],"_selected":["r"]}',saveRepertoireSettings:()=>{},
   cancelRepertoireLookup:()=>{window.__bridge.canceled++},
   requestRepertoire:raw=>{
    const r=JSON.parse(raw),id=String(++sequence);window.__bridge.requests.push({id,...r});
    if(r.action==='catalog'){setTimeout(()=>emit(id,{installed:true,repertoires:[{id:'r',name:'Test repertoire',side:'black'}]}),0);return id}
    const chess=new window.InstinctaZeroChessRules.Chess(r.fen+' 0 1');const move=chess.moves({verbose:true})[0];
    const data={results:[{id:'r',name:'Test repertoire',side:'black',fen:r.fen,theory:true,known:true,comments:[r.gameId==='GameOld1'?'Old game note':'New game note'],moves:move?[{uci:move.from+move.to,san:move.san,theory:true,comments:[],position:{fen:move.after.split(' ').slice(0,4).join(' '),theory:true,known:true,comments:[]}}]:[]}]};
    if(r.gameId==='GameOld1')window.__bridge.old={id,data};else setTimeout(()=>emit(id,data),10);
    return id;
   }};
  window.__game=(id,count,last)=>({id,moves:[...Array.from({length:count},(_,i)=>({uci:['g1f3','g8f6','f3g1','f6g8'][i%4]})),...(last?[{uci:last}]:[])],white:{name:id},black:{name:'Black'},mobile_orientation:'black'});
 });
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
 await page.waitForTimeout(60);assert.equal(await page.evaluate(()=>window.__bridge.requests.some(r=>r.action==='lookup')),false,'hidden warmup must not start the old study lookup');
 await page.evaluate(()=>window.InstinctaZero.loadArchivedGame({id:'GameOld1',moves:[{uci:'e2e4'},{uci:'e7e5'}],white:{name:'Old game'},black:{name:'Black'}}));
 await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));await page.waitForFunction(()=>!!window.__bridge.old);
 const result=await page.evaluate(async()=>{
  const start=performance.now(),canceled=window.__bridge.canceled;
  const first=window.InstinctaZero.loadArchivedGame(window.__game('GameLost',512));
  const second=window.InstinctaZero.loadArchivedGame(window.__game('GameNew1',160,'d2d4'));
  const loading=document.querySelector('#panel').textContent.includes('Loading game');
  window.InstinctaZero.openRepertoires();
  const done=await Promise.all([first,second]);return {done,loading,canceled:window.__bridge.canceled>canceled,ms:performance.now()-start};
 });
 assert.deepEqual(result.done,[false,true]);assert.equal(result.loading,true);assert.equal(result.canceled,true);
 await page.getByRole('button',{name:'Comment on played move',exact:true}).waitFor();assert.match(await page.locator('.rep-current-note').innerText(),/New game note/);
 await page.evaluate(()=>window.__bridge.emit(window.__bridge.old.id,window.__bridge.old.data));assert.doesNotMatch(await page.locator('#panel').innerText(),/Old game note/);
 assert.equal(await page.evaluate(()=>window.__bridge.saves.includes('GameLost')),false,'superseded import never writes a saved board');
 assert.equal(await page.evaluate(()=>JSON.parse(window.__bridge.saved).gameId),'GameNew1');
 assert.equal(await page.locator('.orientation-black').count(),1);
 const before=await page.evaluate(()=>window.__bridge.saved);
 assert.equal(await page.evaluate(async()=>{const pending=window.InstinctaZero.loadArchivedGame(window.__game('GameBack',512));window.InstinctaZero.handleAndroidBack();return await pending}),false);
 assert.equal(await page.evaluate(()=>window.__bridge.saved),before,'Back during import preserves the prior saved tree/cursor');
 assert.equal(await page.evaluate(()=>window.InstinctaZero.loadArchivedGame({id:'GameBad1',moves:[{uci:'a2a5'}]})),false);
 assert.equal(await page.evaluate(()=>window.__bridge.saved),before,'invalid PGN must not replace a saved game');
 assert.equal(await page.evaluate(async()=>{const pending=window.InstinctaZero.loadArchivedGame(window.__game('GameAcct',512));window.InstinctaZero.onAccountChanged();return await pending}),false);
 assert.equal(await page.evaluate(()=>JSON.parse(window.__bridge.saved).gameId),null,'account change cancels the importing archived game');
 await page.getByRole('button',{name:'Comment on position',exact:true}).waitFor();
 await page.evaluate(()=>window.InstinctaZero.loadArchivedGame(window.__game('GameNew1',160,'d2d4')));
 await page.evaluate(()=>window.InstinctaZero.openRepertoires());
 await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(false));const count=await page.evaluate(()=>window.__bridge.requests.length);
 await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));await page.waitForTimeout(30);
 assert.ok(await page.evaluate(n=>window.__bridge.requests.length>=n,count));
 await page.reload();await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));
 await page.getByRole('button',{name:'Comment on played move',exact:true}).waitFor();
 assert.equal(await page.evaluate(()=>JSON.parse(window.__bridge.saved).gameId),'GameNew1');
 assert.equal(await page.evaluate(()=>JSON.parse(window.__bridge.saved).cursor.length),161);
 await page.waitForFunction(()=>window.__bridge.engineRequests.at(-1)?.game_id==='GameNew1');
 assert.equal(await page.evaluate(()=>window.__bridge.engineRequests.some(r=>r.game_id==='GameLost'||r.game_id==='GameBack'||r.game_id==='GameAcct')),false);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
 await page.screenshot({path:`${output}/game-ready-${width}.png`});measurements.push({width,height,...result});
 console.log(`${width}×${height}: latest game wins, stale replies ignored, hidden reads stopped, Back/invalid import preserve state, resume/restart passed`);await page.close();
}}finally{await browser.close();await new Promise(r=>server.close(r))}
await writeFile(`${output}/timings.json`,JSON.stringify({environment:'Touch Chromium, 4x renderer CPU throttle; not phone timings',measurements}));
