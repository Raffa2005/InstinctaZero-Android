import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
import {Chess} from 'chess.js';

const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-large-study';await mkdir(output,{recursive:true});
const baseline=process.env.STUDY_BASELINE==='1';
let total=0;
const build=(fen,depth)=>{if(!depth)return [];const c=new Chess(fen);return c.moves({verbose:true}).slice(0,3).map(m=>{total++;return {u:m.from+m.to+(m.promotion||''),x:0,c:build(m.after,depth-1)}})};
const tree=build(new Chess().fen(),7); // 3,279 legal nodes, not repeated/invalid filler.
const initial={v:1,initialFen:new Chess().fen(),gameId:null,title:'Local analysis',subtitle:'Starting position',cursor:[],tree,tab:'moves',black:false};
const server=createServer(async(req,res)=>{try{const file=req.url==='/'?'index.html':req.url.slice(1);if(file.includes('..'))throw Error();let data=await readFile(new URL(file,assets));
  // Private harness access only. Production assets are unmodified. The bypass is used
  // solely to compare full-sized live trees, not the separate real restart/loss test.
  if(file==='analysis.js')data=Buffer.from(data.toString().replace('  loadUiSettings();',`  window.__studyTest={count:()=>{let n=0,s=[root];while(s.length){const x=s.pop();n+=x.children.length;s.push(...x.children);}return n;},navigate:restore,root:()=>root,play:playUci,installAll:raw=>{resetRoot(START_FEN,studyContext);rebuildTree(raw,root,{count:-1000000});renderPanel();},persist:saveStudyNow};\n  loadUiSettings();`));
  res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[file.split('.').at(-1)]||'application/octet-stream'}).end(data);
}catch{res.writeHead(404).end()}});await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,executablePath:process.env.PHONE_PREVIEW_CHROMIUM});const measurements=[];
try{for(const width of [360,390,412]) {
  const page=await browser.newPage({viewport:{width,height:780},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const cdp=await page.context().newCDPSession(page);await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});
  await page.addInitScript(initial=>{
    const saved=()=>JSON.parse(localStorage.getItem('study') || JSON.stringify(initial));
    window.__writes=[];window.__failSave=false;
    window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":false}',getStudyState:()=>JSON.stringify(saved()),getStudyRevision:()=>saved().revision||0,
      saveStudyState:raw=>{if(window.__failSave)return false;localStorage.setItem('study',raw);window.__writes.push(raw.length);return true},
      saveStudyDelta:raw=>{
        window.__writes.push(raw.length);if(window.__failSave)return '{"saved":false}';
        const patch=JSON.parse(raw),old=saved(),nodes=new Map((patch.replace?[]:old.nodes||[]).map(n=>[n.id,n]));
        for(const branch of patch.branches){const ids=new Set(branch.children.map(n=>n.id));let remove=[...nodes.values()].filter(n=>n.p===branch.parent&&!ids.has(n.id)).map(n=>n.id);
          while(remove.length){const id=remove.pop();remove.push(...[...nodes.values()].filter(n=>n.p===id).map(n=>n.id));nodes.delete(id);}
          branch.children.forEach((n,o)=>nodes.set(n.id,{...n,p:branch.parent,o}));}
        const state={...patch.meta,nodes:[...nodes.values()],revision:(old.revision||0)+1};localStorage.setItem('study',JSON.stringify(state));return JSON.stringify({saved:true,revision:state.revision});
      },getRepertoireSettings:()=>'{"_selected":[]}',saveRepertoireSettings:()=>{},cancelRepertoireLookup:()=>{},requestRepertoire:()=>'',cancelAnalysis:()=>{},saveUiSettings:()=>{}};
  },initial);
  await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>window.__studyTest);const restored=await page.evaluate(()=>window.__studyTest.count());
  assert.equal(restored,baseline?512:total,'restart must preserve the whole tree');
  if(baseline)await page.evaluate(tree=>window.__studyTest.installAll(tree),tree);
  const metrics=await page.evaluate(()=>{
    const api=window.__studyTest;api.persist();window.__writes=[];const times=[];let node=api.root();
    for(let i=0;i<80;i++){node=i%8===0?api.root():(node.children[0]||api.root());const start=performance.now();api.navigate(node);times.push(performance.now()-start);}
    node=api.root();while(node.children.length)node=node.children[0];api.navigate(node);
    const chess=new window.InstinctaZeroChessRules.Chess(node.fen),m=chess.moves({verbose:true})[0],start=performance.now();api.play(m.from+m.to+(m.promotion||''));
    return {times,edit_ms:performance.now()-start,bridge_bytes:window.__writes.slice(0,80),count:api.count()};
  });
  assert.equal(metrics.count,total+1);
  if(!baseline){await page.reload();assert.equal(await page.evaluate(()=>window.__studyTest.count()),total+1);
    const durable=await page.evaluate(()=>localStorage.getItem('study'));
    await page.evaluate(()=>{window.__failSave=true;const root=window.__studyTest.root();window.__studyTest.navigate(root);window.__studyTest.play('e2e4');});
    await page.getByRole('alert').waitFor();assert.equal(await page.evaluate(()=>localStorage.getItem('study')),durable,'failed save never acknowledges or replaces persisted tree');
    await page.evaluate(()=>window.__failSave=false);await page.getByRole('alert').click();assert.equal(await page.getByRole('alert').count(),0);
    await page.reload();assert.equal(await page.evaluate(()=>window.__studyTest.count()),total+2);
  }
  assert.deepEqual(errors,[]);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.screenshot({path:`${output}/large-study-${width}.png`});measurements.push({width,total,restored,...metrics});await page.close();
}}finally{await browser.close();await new Promise(r=>server.close(r))}
await writeFile(`${output}/timings.json`,JSON.stringify({environment:'Chromium touch viewport, 4x renderer slowdown; in-memory native bridge mock, not phone storage timings',baseline,measurements}));
console.log(measurements.map(m=>({width:m.width,restored:m.restored,navigation_median_ms:[...m.times].sort((a,b)=>a-b)[40],edit_ms:m.edit_ms,bridge_bytes:m.bridge_bytes[0]})));
