import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {Chess} from 'chess.js';
const source=await readFile(new URL('../../app/src/main/assets/analysis/click-chess.js',import.meta.url),'utf8');
const context={Uint8Array,TextDecoder,atob};vm.runInNewContext(source,context);
const model=context.InstinctaClickChess.ranker;
test('packaged binary model matches all 305 upstream JSON-model fixtures',async()=>{
  const fixtures=JSON.parse(await readFile(new URL('click-chess-fixtures.json',import.meta.url),'utf8'));
  for(const f of fixtures){
    const rows=model.rank(f.fen,new Chess(f.fen).moves({verbose:true}),f.destination,f.history);
    assert.deepEqual(JSON.parse(JSON.stringify(rows)),f.ranking);
    assert.deepEqual([...rows.map(r=>r.from)].sort(),f.origins.slice().sort());
    assert.ok(rows.every(r=>Number.isFinite(r.probability)));
  }
});
test('special moves stay legal, promotions grouped but never silently selected',()=>{
  for(const [fen,to,origins] of [
    ['r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1','g1',['e1','h1']],
    ['4k3/8/8/2PpP3/8/8/8/4K3 w - d6 0 1','d6',['c5','e5']],
    ['4r1k1/8/8/3pP3/8/8/8/4K3 w - d6 0 1','d6',[]],
    ['7k/P7/8/8/8/8/8/7K w - - 0 1','a8',['a7']],
  ]){
    const legal=new Chess(fen).moves({verbose:true});const rows=model.rank(fen,legal,to);
    assert.deepEqual([...rows.map(r=>r.from)].sort(),origins);
    for(const row of rows)for(const u of row.moves)assert.ok(legal.some(m=>m.from+m.to+(m.promotion||'')===u));
    if(to==='a8')assert.deepEqual([...rows[0].moves].sort(),['a7a8b','a7a8n','a7a8q','a7a8r']);
  }
});
test('destination gestures preserve selection, cancel movement/multitouch and map both orientations',()=>{
  const listeners={},documentListeners={},moves=[];let clears=0,enabled=true;
  context.document={addEventListener:(name,fn)=>documentListeners[name]=fn};
  const target={closest:()=>true};
  const element={addEventListener:(name,fn)=>listeners[name]=fn,contains:t=>t===target,getBoundingClientRect:()=>({left:0,top:0,width:800,height:800})};
  const ground={state:{orientation:'white',selected:null}};
  const api=context.InstinctaClickChess.bindDestinationInput({element,ground,enabled:()=>enabled,canTap:()=>true,tap:s=>moves.push(s),clear:()=>clears++});
  const touch=(x,y,id=1)=>({clientX:x,clientY:y,identifier:id});
  const fire=(name,touches,changedTouches=touches)=>{
    const event={target,touches,changedTouches,preventDefault(){this.prevented=true;},stopImmediatePropagation(){this.stopped=true;}};
    listeners[name](event);return event;
  };
  const c3=touch(250,550);
  assert.ok(fire('touchstart',[c3]).stopped);assert.equal(moves.length,0,'commit on release, not press');
  fire('touchend',[],[c3]);assert.deepEqual(moves,['c3']);
  ground.state.orientation='black';fire('touchstart',[c3]);fire('touchend',[],[c3]);assert.deepEqual(moves,['c3','f6']);
  ground.state.selected='b1';assert.equal(fire('touchstart',[c3]).stopped,undefined,'selected origin goes to Chessground');
  fire('touchend',[],[c3]);assert.equal(moves.length,2);ground.state.selected=null;
  fire('touchstart',[c3]);fire('touchmove',[touch(280,550)]);fire('touchend',[],[c3]);assert.equal(moves.length,2);
  fire('touchstart',[c3]);fire('touchstart',[c3,touch(350,550,2)]);fire('touchend',[],[c3]);assert.equal(moves.length,2);
  fire('touchstart',[c3]);fire('touchcancel',[]);fire('touchend',[],[c3]);assert.equal(moves.length,2);
  fire('touchstart',[c3]);api.cancel();fire('touchend',[],[c3]);assert.equal(moves.length,2);
  fire('touchstart',[c3]);enabled=false;fire('touchend',[],[c3]);assert.equal(moves.length,2);
  enabled=true;fire('touchstart',[c3]);documentListeners.pointerdown({target:{}});fire('touchend',[],[c3]);assert.equal(moves.length,2);
  assert.ok(clears>=4);
});
test('study corrections replace one new leaf, preserve existing descendants and save atomically',async()=>{
  const source=await readFile(new URL('../../app/src/main/assets/analysis/analysis.js',import.meta.url),'utf8');
  const take=(name,end)=>source.slice(source.indexOf('  function '+name),source.indexOf(end,source.indexOf('  function '+name)+1));
  const functions=['color','history','legalMoves','legalDests','sync','mainlineChild','remember','commitMove','clearClickChoice','recentClickHistory'].map(name=>take(name,'\n  function ')).join('\n')+take('destinationTap','\n  if(window.InstinctaClickChess)');
  const make=new Function('Chess','model',`
    const chess=new Chess(),root={id:0,fen:chess.fen(),children:[],parent:null};
    let cursor=root,legalCache=null,clickChoice=null,clickInput=null,nodeId=0;
    const dirtyBranches=new Map(),nodeIndex=new Map([[0,root]]),settings={nodes:1000},engine={},saves=[];
    let ranks=0;const window={InstinctaClickChess:{ranker:{rank:(...args)=>{ranks++;return model.rank(...args);}}}};
    const ground={set:()=>{},cancelMove:()=>{}},loadFailed=false,gameLoading=false,editingPosition=()=>false,
      heading=()=>{},onPositionChanged=()=>{},projectAnalysisToChild=()=>null,
      changedBranch=parent=>{for(const child of parent.children)nodeIndex.set(child.id,child);},
      saveStudyNow=()=>{saves.push({cursor:history(),children:root.children.map(n=>({uci:n.move.from+n.move.to,children:n.children.length}))});},
      choosePromotion=()=>{throw Error('unexpected promotion');};
    ${functions}
    return {tap:destinationTap,commit:commitMove,root,saves,ranks:()=>ranks,
      back:()=>{clearClickChoice();cursor=root;chess.load(root.fen);}};
  `);
  const study=make(Chess,model);study.tap('c3');const first=study.saves.at(-1).cursor[0];
  study.tap('c3');assert.notEqual(study.saves.at(-1).cursor[0],first);
  study.tap('c3');assert.equal(study.saves.at(-1).cursor[0],first);
  assert.equal(study.ranks(),1);assert.equal(study.saves.length,3);
  assert.ok(study.saves.every(s=>s.cursor.length===1 && s.children.length===1),'no intermediate rewind or ghost branch is saved');
  const existing=make(Chess,model);existing.commit('b1','c3');existing.commit('e7','e5');existing.back();existing.commit('c2','c3');existing.back();
  const original=existing.root.children.slice();existing.tap('c3');existing.tap('c3');existing.tap('c3');
  assert.deepEqual(existing.root.children,original);assert.equal(original[0].children.length,1);
});
