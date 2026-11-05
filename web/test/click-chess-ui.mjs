import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-click-preview';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const file=req.url==='/'?'index.html':req.url.slice(1);if(file.includes('..'))throw Error();const data=await readFile(new URL(file,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[file.split('.').at(-1)]||'application/octet-stream'}).end(data)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,...(process.env.PHONE_PREVIEW_CHROMIUM?{executablePath:process.env.PHONE_PREVIEW_CHROMIUM}:{})});
const startFen='rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
const measurements=[],traces=[];
try { for(const [width,height] of [[360,640],[390,780],[412,844]]) {
  const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const cdp=await page.context().newCDPSession(page);await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});
  await page.addInitScript(()=>{
    window.__saves=[];window.__deltas=[];window.__failSave=false;
    window.InstinctaZeroNative={
      getUiSettings:()=>localStorage.getItem('settings') || '{"leelaEnabled":false}',saveUiSettings:raw=>localStorage.setItem('settings',raw),
      getStudyState:()=>localStorage.getItem('study') || '{}',getStudyRevision:()=>JSON.parse(localStorage.getItem('study') || '{}').revision || 0,
      cancelAnalysis:()=>{},getRepertoireSettings:()=>'{}',
      saveStudyDelta:raw=>{
        if(window.__failSave)return '{"saved":false}';
        const d=JSON.parse(raw),old=JSON.parse(localStorage.getItem('study') || '{}');
        if(d.revision!==(old.revision || 0))throw Error('Revision conflict');
        const nodes=new Map((d.replace?[]:old.nodes || []).map(n=>[n.id,n]));
        for(const b of d.branches){for(const n of nodes.values())if(n.p===b.parent)nodes.delete(n.id);b.children.forEach((n,o)=>nodes.set(n.id,{...n,p:b.parent,o}));}
        const keep=new Set([0]),queue=[0];while(queue.length){const p=queue.pop();for(const n of nodes.values())if(n.p===p){if(keep.has(n.id))throw Error('Cycle');keep.add(n.id);queue.push(n.id);}}
        let cursor=0;for(const u of d.meta.cursor){const n=[...nodes.values()].find(n=>n.p===cursor && n.u===u);if(!n)throw Error('Detached saved cursor');cursor=n.id;}
        const saved={...d.meta,revision:d.revision+1,nodes:[...nodes.values()].filter(n=>keep.has(n.id))};
        localStorage.setItem('study',JSON.stringify(saved));window.__saves.push(saved);window.__deltas.push(d);return JSON.stringify({saved:true,revision:saved.revision});
      }
    };
  });
  const url=`http://127.0.0.1:${server.address().port}`;
  await page.goto(url);
  let initial;
  const load=async(fen=startFen,{black=false,tree=[],cursor=[]}={})=>{
    // The old page saves on pagehide. Disable only its mock write before replacing
    // the fixture, otherwise a revision-zero old board can overwrite the new one.
    initial={v:1,initialFen:fen,tree,cursor,black,tab:'moves',title:'Local analysis',subtitle:'Starting position'};
    await page.evaluate(state=>{window.__failSave=true;localStorage.setItem('study',JSON.stringify(state));},initial);
    await page.reload();await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
    await page.evaluate(()=>{
      window.InstinctaZero.setAnalysisActive(true);window.__ranks=[];window.__rankTimes=[];
      const model=window.InstinctaClickChess.ranker,rank=model.rank.bind(model);
      model.rank=(...args)=>{const t=performance.now(),rows=rank(...args);window.__rankTimes.push(performance.now()-t);window.__ranks.push({fen:args[0],history:args[3],rows});return rows;};
    });
    assert.equal(await page.evaluate(()=>window.InstinctaZero.persistStudy()),true);
    assert.deepEqual(await page.evaluate(()=>JSON.parse(localStorage.getItem('study')).cursor),cursor,'fixture cursor actually restored');
  };
  const stored=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('study')));
  const trace=async name=>traces.push({name,width,initial,...await page.evaluate(()=>({requests:window.__deltas,expected:JSON.parse(localStorage.getItem('study'))}))});
  const point=async square=>page.evaluate(square=>{
    const b=document.querySelector('#board').getBoundingClientRect(),black=document.querySelector('#board-wrap').classList.contains('orientation-black');
    const f=square.charCodeAt(0)-97,r=Number(square[1])-1;return {x:b.left+(black?7-f:f)*b.width/8+b.width/16,y:b.top+(black?r:7-r)*b.height/8+b.height/16};
  },square);
  const tap=async square=>{const p=await point(square);await page.touchscreen.tap(p.x,p.y);};
  const gesture=async(from,to,cancel=false)=>{
    const a=await point(from),b=await point(to);
    await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...a,id:1}]});
    await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{...b,id:1}]});
    await page.waitForTimeout(40);
    await cdp.send('Input.dispatchTouchEvent',{type:cancel?'touchCancel':'touchEnd',touchPoints:[]});
  };
  const undo=async()=>{
    const before=await stored(),uci=before.cursor.at(-1);assert.ok(uci);
    await gesture(uci.slice(2,4),uci.slice(0,2));
    if((await stored()).cursor.length===before.cursor.length) {
      await page.screenshot({path:`${output}/failed-undo-${width}.png`});
      console.log('Failed undo',uci,await page.locator('.game-title small').textContent(),await page.evaluate(()=>document.querySelector('#board').outerHTML));
    }
    assert.deepEqual((await stored()).cursor,before.cursor.slice(0,-1),'drag back restores the parent immediately');
  };
  await load();await tap('c3');
  let ranking=await page.evaluate(()=>window.__ranks[0].rows);
  assert.equal(ranking.length,2);assert.deepEqual((await stored()).cursor,[ranking[0].from+'c3']);
  await tap('c3');assert.deepEqual((await stored()).cursor,[ranking[0].from+'c3'],'tap alone must not cycle');
  await gesture('c3','a3');assert.deepEqual((await stored()).cursor,[ranking[0].from+'c3'],'unrelated drag is not an undo');
  await undo();assert.equal((await stored()).nodes.length,0);await trace('undone leaf');
  await tap('c3');assert.deepEqual((await stored()).cursor,[ranking[1].from+'c3']);
  assert.equal((await stored()).nodes.length,1,'correction does not leave an unwanted variation');
  assert.equal(await page.evaluate(()=>window.__ranks.length),1,'repeat reuses original ranking');
  await undo();await tap('c3');assert.deepEqual((await stored()).cursor,[ranking[0].from+'c3']);await trace('corrected leaf');
  await page.reload();await page.waitForFunction(()=>!!window.InstinctaZero);assert.deepEqual((await stored()).cursor,[ranking[0].from+'c3']);
  await gesture('c3',ranking[0].from);assert.deepEqual((await stored()).cursor,[ranking[0].from+'c3'],'reopening does not revive a stale correction');
  // Existing branches and descendants must survive cycling through them.
  await load(startFen,{tree:[{u:'b1c3',c:[{u:'e7e5',c:[]}]},{u:'c2c3',c:[]}]});
  await tap('c3');await undo();await tap('c3');await undo();assert.equal((await stored()).nodes.length,2);
  assert.ok((await stored()).nodes.some(n=>n.u==='e7e5'));
  await trace('existing subtree preserved');
  await page.getByRole('button',{name:'Next move',exact:true}).click();assert.deepEqual((await stored()).cursor,['b1c3'],'canonical mainline survives');
  await load('3R4/7k/8/8/8/2B2N2/8/K2R4 w - - 0 1');await tap('d4');
  ranking=await page.evaluate(()=>window.__ranks[0].rows);assert.equal(ranking.length,4);
  for(let i=1;i<=4;i++){await undo();await tap('d4');assert.deepEqual((await stored()).cursor,[ranking[i%4].from+'d4']);assert.equal((await stored()).nodes.length,1);}
  assert.equal(await page.evaluate(()=>window.__ranks.length),1);
  // Explicit selection and dragging always win over the model.
  await load();await tap('c2');await tap('c3');await page.waitForTimeout(30);
  assert.deepEqual((await stored()).cursor,['c2c3']);assert.equal(await page.evaluate(()=>window.__ranks.length),0);
  await load();await gesture('b1','c3');await page.waitForTimeout(30);
  assert.deepEqual((await stored()).cursor,['b1c3']);assert.equal(await page.evaluate(()=>window.__ranks.length),0);
  await load();await gesture('c3','c4');assert.deepEqual((await stored()).cursor,[]);
  await gesture('c3','c3',true);assert.deepEqual((await stored()).cursor,[]);
  // A real two-finger sequence cannot accidentally enter a destination.
  const a=await point('c3'),b=await point('d3');
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...a,id:1}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...a,id:1},{...b,id:2}]});
  await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});assert.deepEqual((await stored()).cursor,[]);
  // Two-ply context is derived from the restored study, not chess.js's empty stack.
  await load(startFen,{black:true,tree:[{u:'e2e4',c:[{u:'e7e5',c:[]}]}],cursor:['e2e4','e7e5']});
  await tap('f3');assert.deepEqual(await page.evaluate(()=>window.__ranks[0].history),[{from:'e2',to:'e4',promotion:null},{from:'e7',to:'e5',promotion:null}]);
  await page.getByRole('button',{name:'Previous move',exact:true}).click();await tap('f3');
  assert.equal(await page.evaluate(()=>window.__ranks.length),2,'navigation invalidates correction');
  // Black-side ranks, capture, castling, pinned en passant and underpromotion.
  await load(startFen.replace(' w ',' b '),{black:true});await tap('c6');await undo();await tap('c6');
  assert.equal((await stored()).cursor.length,1);assert.equal((await stored()).nodes.length,1);
  await load('4k3/8/8/8/3p4/2P1P3/8/4K3 w - - 0 1');await tap('d4');await undo();
  await page.waitForTimeout(160);assert.equal(await page.locator('#board .cg-board piece.black.pawn').count(),1,'captured piece restored');
  await tap('d4');assert.equal((await stored()).nodes.length,1);
  await load('7k/8/8/4p3/3p4/2P1P3/8/7K w - - 0 1');await tap('d4');await tap('d4');assert.equal((await stored()).cursor.length,2,'same-square tap plays a recapture instead of cycling');
  await load('r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1');await tap('g1');
  if((await stored()).cursor[0]!=='e1g1'){await undo();await tap('g1');}assert.deepEqual((await stored()).cursor,['e1g1']);
  await undo();await page.waitForTimeout(160);assert.equal(await page.locator('#board .cg-board piece.white.rook').count(),2,'castling undo restores rook');
  await load('4r1k1/8/8/3pP3/8/8/8/4K3 w - d6 0 1');await tap('d6');assert.deepEqual((await stored()).cursor,[]);
  await load('4k3/8/8/2PpP3/8/8/8/4K3 w - d6 0 1');await tap('d6');await undo();
  await page.waitForTimeout(160);assert.equal(await page.locator('#board .cg-board piece.black.pawn').count(),1,'en passant victim restored');
  await tap('d6');assert.equal((await stored()).nodes.length,1);
  await load('7k/P7/8/8/8/8/8/7K w - - 0 1');await tap('a8');assert.deepEqual((await stored()).cursor,[]);
  await page.getByRole('button',{name:'Cancel promotion',exact:true}).click();assert.deepEqual((await stored()).cursor,[]);
  await tap('a8');await page.getByRole('button',{name:'Promote to Knight',exact:true}).click();assert.deepEqual((await stored()).cursor,['a7a8n']);
  await undo();await tap('a8');assert.equal(await page.getByRole('dialog').count(),1,'promotion choice stays explicit after undo');
  await page.getByRole('button',{name:'Promote to Bishop',exact:true}).click();assert.deepEqual((await stored()).cursor,['a7a8b']);await trace('promotion correction');
  await load('1r5k/P1P5/8/8/8/8/8/7K w - - 0 17');await tap('b8');
  await page.getByRole('button',{name:'Promote to Rook',exact:true}).click();const promoting=(await stored()).cursor[0];
  await undo();await tap('b8');await page.getByRole('button',{name:'Promote to Knight',exact:true}).click();assert.notEqual((await stored()).cursor[0].slice(0,2),promoting.slice(0,2));
  // Failed durable writes stay visibly retryable; never report a saved board.
  await load();await page.evaluate(()=>window.__failSave=true);await tap('c3');
  assert.equal(await page.locator('#study-save-error').count(),1);assert.deepEqual((await stored()).cursor,[]);
  await page.evaluate(()=>window.__failSave=false);await page.locator('#study-save-error').click();assert.equal((await stored()).cursor.length,1);
  await load();await tap('c3');const guessed=(await stored()).cursor[0];await page.evaluate(()=>window.__failSave=true);await gesture(guessed.slice(2,4),guessed.slice(0,2));
  assert.equal(await page.locator('#study-save-error').count(),1);assert.deepEqual((await stored()).cursor,[guessed],'failed undo leaves last durable state intact');
  await page.evaluate(()=>window.__failSave=false);await page.locator('#study-save-error').click();assert.deepEqual((await stored()).cursor,[]);
  await page.reload();await page.waitForFunction(()=>!!window.InstinctaZero);assert.equal((await stored()).nodes.length,0,'retried undo survives reopening');
  // Preference round trip disables only destination input, retaining old gestures.
  await load();await page.getByRole('button',{name:'Settings',exact:true}).click();
  await page.getByRole('switch',{name:'One tap',exact:true}).click();await page.reload();await page.waitForFunction(()=>!!window.InstinctaZero);
  await tap('c3');assert.deepEqual((await stored()).cursor,[]);
  await tap('b1');await tap('c3');await page.waitForTimeout(30);assert.deepEqual((await stored()).cursor,['b1c3']);
  await page.evaluate(()=>localStorage.removeItem('settings'));await load();
  await page.context().setOffline(true);await tap('c3');await undo();await tap('c3');assert.equal((await stored()).nodes.length,1,'all inference and correction work offline');await page.context().setOffline(false);
  await load();
  // Measure the actual input handler (ranking + legal rules + tree/save bridge +
  // synchronous UI work), and cached corrections, under 4x CPU throttling.
  const timings=await page.evaluate(async()=>{
    const board=document.querySelector('#board .cg-board'),b=board.getBoundingClientRect();
    const point=s=>({x:b.left+(s.charCodeAt(0)-97+.5)*b.width/8,y:b.top+(8-Number(s[1])+.5)*b.height/8});
    const dispatch=(from,to=from)=>{const a=point(from),z=point(to),t=new Touch({identifier:10,target:board,clientX:a.x,clientY:a.y}),end=new Touch({identifier:10,target:board,clientX:z.x,clientY:z.y});
      board.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[t],targetTouches:[t],changedTouches:[t]}));
      if(from!==to)board.dispatchEvent(new TouchEvent('touchmove',{bubbles:true,cancelable:true,touches:[end],targetTouches:[end],changedTouches:[end]}));
      board.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],changedTouches:[end]}));};
    let t=performance.now();dispatch('c3');const first=performance.now()-t;
    const repeat=[];for(let i=0;i<30;i++){await new Promise(requestAnimationFrame);const uci=JSON.parse(localStorage.getItem('study')).cursor.at(-1);t=performance.now();dispatch('c3',uci.slice(0,2));dispatch('c3');repeat.push(performance.now()-t);}
    return {first,repeat,rank:window.__rankTimes};
  });
  measurements.push({width,height,...timings});
  assert.equal((await stored()).nodes.length,1);assert.deepEqual(errors,[]);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.waitForTimeout(200); // Inspect the settled board, not a move animation frame.
  await page.screenshot({path:`${output}/click-chess-${width}.png`});
  console.log(`${width}x${height}: offline touch, drag-back correction, same-square recapture, durable leaves/subtrees, restart, explicit input, history, flipped orientation, special moves, cancel/multitouch, save failure and settings passed`);
  await page.close();
}} finally {await browser.close();await new Promise(r=>server.close(r));}
await writeFile(`${output}/timings.json`,JSON.stringify({environment:'Headless touch Chromium; 4x CPU throttle. Synthetic synchronous native-save mock; not physical Android/device storage timings.',measurements},null,2));
await writeFile(`${output}/study-traces.json`,JSON.stringify(traces));
