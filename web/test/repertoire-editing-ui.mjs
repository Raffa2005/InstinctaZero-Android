// Real native-corpus response fixtures; touch renderer only, never a USB phone.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const fixture=JSON.parse(await readFile(process.env.REPERTOIRE_EDIT_PREVIEW || '/tmp/instinctazero-v086-picture.json','utf8'));
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-v085-ui';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const file=req.url==='/'?'index.html':req.url.slice(1);if(file.includes('..'))throw Error();const body=await readFile(new URL(file,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[file.split('.').at(-1)]||'application/octet-stream'}).end(body)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({headless:true,executablePath:process.env.PHONE_PREVIEW_CHROMIUM});const metrics=[];
try{for(const [width,height]of [[360,640],[390,780],[412,844]]){
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const cdp=await page.context().newCDPSession(page);await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});
 await page.addInitScript(f=>{
  let sequence=0,mode='initial',undo=null;const settings={_selected:['taimanov'],analysis:['taimanov']};
  window.__test={saved:'{}',requests:[],engines:[],cancelled:[],delay:25,markers:[],full:[],f};
  const send=(id,result,delay=10)=>setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,result),delay);
  window.InstinctaZeroNative={getDisplayPrivacy:()=>JSON.stringify({enabled:true,names:['HiddenOwner'],alias:'Player'}),getUiSettings:()=>'{"leelaEnabled":true}',saveUiSettings:()=>{},
   getStudyState:()=>window.__test.saved,saveStudyState:raw=>window.__test.saved=raw,getRepertoireSettings:()=>JSON.stringify(settings),saveRepertoireSettings:raw=>Object.assign(settings,JSON.parse(raw)),
   cancelAnalysis:id=>window.__test.cancelled.push(id),cancelRepertoireLookup:()=>{},openRepertoireBackups:()=>window.__test.backups=true,
   startAnalysis:raw=>{const id='engine'+ ++sequence;window.__test.engines.push({id,...JSON.parse(raw)});return id;},
   requestRepertoire:raw=>{
    const r=JSON.parse(raw),id='rep'+ ++sequence;window.__test.requests.push(r);
    if(r.action==='catalog'){send(id,{installed:true,repertoires:[{id:'taimanov',name:'Taimanov',side:'black'}],undo});return id;}
    if(r.action==='edit'){undo={token:String(sequence),name:'Taimanov',label:'Repertoire edit',before:mode};mode=r.kind==='delete'?'deleted':r.kind==='restore_move'?'added':r.kind==='add'?'added':mode;send(id,{saved:true,undo});return id;}
    if(r.action==='undo'){mode=undo.before;undo=null;send(id,{saved:true,undo});return id;}
    let rep;
    if(r.fen===f.extension.fen)rep=structuredClone(mode==='initial'?f.unified.initial.at(-1):f.continuation);
    else if(r.fen===f.request.fen)rep=structuredClone(mode==='initial'?f.initial:mode==='deleted'?f.deleted_current:f.added);
    else if(r.fen===f.parent.fen)rep=structuredClone(mode==='deleted'?f.deleted_parent:f.parent);
    else rep={id:'taimanov',name:'Taimanov',side:'black',fen:r.fen,theory:true,known:true,moves:[],comments:[],intersection:-1};
    setTimeout(()=>{window.__test.markers.push(performance.now());window.InstinctaZero.onNativeRepertoireMarker(id,{results:[{id:rep.id,fen:rep.fen,theory:rep.theory,end_of_line:rep.end_of_line}]})},8);
    setTimeout(()=>{window.__test.full.push(performance.now());window.InstinctaZero.onNativeRepertoire(id,{results:[rep]})},window.__test.delay);return id;
   }};
  window.__load=()=>window.InstinctaZero.loadArchivedGame({id:'Picture1',moves:f.extension.history.map(uci=>({uci})),white:{name:'Opponent'},black:{name:'HiddenOwner'},mobile_orientation:'black'});
  window.__finish=(id,wrong=false)=>{
   const request=window.__test.engines.find(r=>r.id===id),chess=new window.InstinctaZeroChessRules.Chess();for(const uci of request.history)chess.move({from:uci.slice(0,2),to:uci.slice(2,4),promotion:uci[4]});
   const m=chess.moves({verbose:true})[0],uci=m.from+m.to+(m.promotion || '');
   window.InstinctaZero.onNativeAnalysis(id,{event:'done',data:{final_snapshot:{search_phase:'final',move_stats:[{uci,visits:100,prior:.5}],lines:[{pv:[uci],san:[wrong?'STALE':m.san],white_cp:31}],progress:{visits:100,target:1000}}}});
  };
 },fixture);
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
 await page.evaluate(()=>window.__load());await page.evaluate(()=>window.InstinctaZero.openRepertoires());
 await page.locator('[data-rep-action=quick-add]').waitFor();assert.match(await page.locator('#panel').innerText(),/Add line to repertoire/);
 assert.equal(await page.locator('.orientation-black').count(),1);assert.doesNotMatch(await page.locator('body').innerText(),/HiddenOwner/);
 assert.doesNotMatch(await page.locator('#panel').innerText(),/just.played/i);await page.waitForTimeout(180);await page.screenshot({path:`${output}/pictured-add-${width}.png`});
 await page.locator('[data-rep-action=quick-add]').tap();await page.waitForTimeout(100);
 assert.equal(await page.evaluate(()=>window.__test.requests.filter(r=>r.action==='edit').at(-1).kind),'add');
 assert.deepEqual(await page.evaluate(()=>window.__test.requests.filter(r=>r.action==='edit').at(-1).history),fixture.extension.history);
 for(let i=0;i<4;i++)await page.locator('[data-action=prev]').tap();await page.locator('[data-rep-play=c3e2]').waitFor();
 await page.locator('[data-action=prev]').tap();await page.locator('[data-rep-edit=b5b4]').waitFor();await page.locator('[data-rep-edit=b5b4]').tap();
 await page.locator('[data-rep-action=delete-move]').tap();assert.match(await page.locator('#panel').innerText(),/Dependent lines lose this route/);
 await page.screenshot({path:`${output}/delete-${width}.png`});await page.locator('[data-rep-action=confirm-delete]').tap();
 await page.waitForTimeout(100);if(!await page.locator('[data-panel-tab]').isVisible())await page.locator('[data-action=settings]').tap();await page.locator('[data-rep-action=restore-move]').waitFor();
 await page.screenshot({path:`${output}/restore-move-${width}.png`});await page.locator('[data-rep-action=restore-move]').tap();
 await page.waitForTimeout(100);if(await page.locator('[data-panel-tab]').isVisible())await page.locator('[data-action=settings]').tap();
 await page.locator('[data-action=next]').tap();await page.locator('[data-rep-play=c3e2]').waitFor();
 await page.getByRole('button',{name:'Moves',exact:true}).tap();assert.equal(await page.locator('[data-action=mainline]').isEnabled(),false);
 await page.getByRole('button',{name:'Repertoires',exact:true}).tap();await page.waitForTimeout(100);
 const target=fixture.added.intersection;assert.ok(target>=0 && target<18);await page.locator('[data-action=mainline]').tap();
 assert.equal(await page.evaluate(()=>JSON.parse(window.__test.saved).cursor.length),target);
 // Last-visited notation has no role in selecting the next ordinary move.
 await page.locator('[data-action=next]').tap();assert.equal(await page.evaluate(()=>JSON.parse(window.__test.saved).cursor.length),target+1);
 await page.getByRole('button',{name:'Engine',exact:true}).tap();await page.waitForTimeout(180);
 const obsolete=await page.evaluate(()=>window.__test.engines.at(-1).id);
 const nav=await page.evaluate(()=>{const start=performance.now();for(let i=0;i<16;i++){const button=document.querySelector('[data-action='+(i%2?'next':'prev')+']');button.dispatchEvent(new PointerEvent('pointerdown',{pointerId:77,pointerType:'touch'}));button.dispatchEvent(new PointerEvent('pointerup',{pointerId:77,pointerType:'touch'}));}return performance.now()-start});
 await page.waitForTimeout(250);await page.evaluate(id=>window.__finish(id,true),obsolete);assert.doesNotMatch(await page.locator('#panel').innerText(),/STALE/);
 const stopped=await page.evaluate(()=>{const id=window.__test.engines.at(-1).id;window.InstinctaZero.onNativeAnalysis(id,{event:'stream-ended',data:{}});return id});
 await page.waitForFunction(id=>window.__test.engines.at(-1).id!==id,stopped);await page.evaluate(()=>window.__finish(window.__test.engines.at(-1).id));
 await page.locator('.pv').waitFor();assert.equal(await page.locator('#arrows .analysis-arrow').count(),1);assert.doesNotMatch(await page.locator('#panel').innerText(),/STALE/);
 const settled=await page.evaluate(()=>({history:window.__test.engines.at(-1).history,cursor:JSON.parse(window.__test.saved).cursor}));assert.deepEqual(settled.history,settled.cursor);
 await page.screenshot({path:`${output}/analysis-recovered-${width}.png`});
 // First-stage marker must paint before deliberately delayed comments/continuations.
 await page.evaluate(()=>{window.__test.delay=450;window.InstinctaZero.onNativeRepertoireRestored()});await page.waitForTimeout(30);
 await page.locator('[data-action=prev]').tap();await page.waitForTimeout(35);
 const staged=await page.evaluate(()=>({visible:!document.querySelector('.repertoire-book-marker').hidden,markers:window.__test.markers.length,full:window.__test.full.length}));
 assert.ok(staged.markers>staged.full);assert.equal(staged.visible,true);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
 metrics.push({width,height,rapid16NavigationMs:nav,stagedMarkerBeforeFullResponse:true});console.log(`${width}×${height}: pictured add/delete/restore, repertoire return, privacy, stale-stream rejection and visible recovery passed`);await page.close();
}}finally{await browser.close();await new Promise(r=>server.close(r))}
await writeFile(`${output}/timings.json`,JSON.stringify({environment:'Touch Chromium, 4× CPU throttle; mocked transport delay, not handset timings',metrics}));
