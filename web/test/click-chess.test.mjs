import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {Chess} from 'chess.js';
const source=await readFile(new URL('../../app/src/main/assets/analysis/click-chess.js',import.meta.url),'utf8');
const context={Uint8Array,TextDecoder,atob};vm.runInNewContext(source,context);
const model=context.InstinctaClickChess.ranker;
vm.runInNewContext(await readFile(new URL('../../app/src/main/assets/analysis/move-input.js',import.meta.url),'utf8'),context);
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
  const listeners={},documentListeners={},moves=[],undos=[];let clears=0,enabled=true,correction=null,cancels=0;
  context.document={addEventListener:(name,fn)=>documentListeners[name]=fn};
  const target={closest:()=>true};
  const element={addEventListener:(name,fn)=>listeners[name]=fn,contains:t=>t===target,getBoundingClientRect:()=>({left:0,top:0,width:800,height:800})};
  const ground={state:{orientation:'white',selected:null},cancelMove:()=>cancels++};
  const api=context.InstinctaClickChess.bindDestinationInput({element,ground,enabled:()=>enabled,canTap:()=>true,tap:s=>moves.push(s),correction:()=>correction,undo:(...s)=>undos.push(s),clear:()=>clears++});
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
  ground.state.orientation='white';correction={from:'b1',to:'c3'};const b1=touch(150,750);
  assert.equal(fire('touchstart',[c3]).stopped,undefined,'Chessground draws the correction drag');
  assert.equal(fire('touchmove',[b1]).stopped,undefined);
  assert.ok(fire('touchend',[],[b1]).stopped,'the reverse drop never reaches chess move entry');
  assert.deepEqual(undos,[['c3','b1']]);assert.equal(cancels,1);
  fire('touchstart',[c3]);fire('touchend',[],[c3]);assert.equal(undos.length,1,'tap does not undo');
  fire('touchstart',[c3]);fire('touchmove',[b1]);fire('touchcancel',[]);fire('touchend',[],[b1]);assert.equal(undos.length,1);
  fire('touchstart',[c3]);fire('touchmove',[b1]);api.cancel();fire('touchend',[],[b1]);assert.equal(undos.length,1,'position change invalidates pending drag');
  fire('touchstart',[c3]);fire('touchstart',[c3,touch(350,550,2)]);fire('touchend',[],[b1]);assert.equal(undos.length,1,'multitouch cannot undo');
});
async function controller(fen=new Chess().fen()) {
  const source=await readFile(new URL('../../app/src/main/assets/analysis/analysis.js',import.meta.url),'utf8');
  const take=(name,end)=>source.slice(source.indexOf('  function '+name),source.indexOf(end,source.indexOf('  function '+name)+1));
  const functions=['color','history','legalMoves','legalDests','sync','mainlineChild','remember','commitMove','clearClickChoice','recentClickHistory','destinationTap','clickCorrection'].map(name=>take(name,'\n  function ')).join('\n')+take('undoDestinationGuess','\n  if(window.InstinctaClickChess)');
  const make=new Function('Chess','model','fen','EngineCache',`
    const chess=new Chess(fen),root={id:0,fen:chess.fen(),children:[],parent:null};
    let cursor=root,legalCache=null,clickChoice=null,clickInput=null,nodeId=0;
    const dirtyBranches=new Map(),nodeIndex=new Map([[0,root]]),settings={nodes:1000},engine={},saves=[],inputEvidence=new EngineCache();
    const ranks=[];let promotion=null;const window={InstinctaClickChess:{ranker:{rank:(...args)=>{ranks.push(args);return model.rank(...args);}}}};
    const ground={set:()=>{},cancelMove:()=>{}},loadFailed=false,gameLoading=false,editingPosition=()=>false,
      heading=()=>{},onPositionChanged=()=>{},projectAnalysisToChild=()=>null,
      changedBranch=parent=>{for(const child of parent.children)nodeIndex.set(child.id,child);},
      saveStudyNow=()=>{saves.push({cursor:history(),children:root.children.map(n=>({uci:n.move.from+n.move.to,children:n.children.length}))});},
      choosePromotion=(from,to,options,apply)=>{promotion=apply;};
    ${functions}
    return {tap:destinationTap,commit:commitMove,root,saves,ranks,undo:undoDestinationGuess,
      choice:()=>clickChoice,fen:()=>chess.fen(),history,recent:recentClickHistory,
      current:()=>cursor,promote:p=>promotion(p),
      evidence:data=>inputEvidence.remember(chess.fen(),settings.backend,legalMoves(),data),
      back:(next=root)=>{clearClickChoice();cursor=next;chess.load(cursor.fen);},
      clear:clearClickChoice};
  `);
  return make(Chess,model,fen,context.InstinctaMoveInput.EngineCache);
}
const undoGuess=study=>{const {from,to}=study.current().move;assert.equal(study.undo(to,from),true);};
test('engine-ranked corrections keep existing continuations and replace only leaves',async()=>{
  const study=await controller();study.commit('b1','c3');study.commit('e7','e5');study.back();
  study.evidence({root_move_stats:[{uci:'b1c3',visits:50},{uci:'c2c3',visits:2}]});study.tap('c3');
  assert.equal(study.current().move.from,'b1');assert.equal(study.ranks.length,0);
  undoGuess(study);assert.equal(study.root.children[0].children.length,1);
  study.tap('c3');assert.equal(study.current().move.from,'c2');undoGuess(study);
  assert.equal(study.root.children.length,1);assert.equal(study.root.children[0].children[0].move.to,'e5');
});
test('only undo-and-click advances candidates, and undo itself is durably saved',async()=>{
  const study=await controller();study.tap('c3');const first=study.history()[0];
  study.tap('c3');assert.equal(study.history()[0],first,'a tap never cycles');
  assert.equal(study.undo('c3','a3'),false,'wrong square is not an undo');
  undoGuess(study);assert.deepEqual(study.saves.at(-1),{cursor:[],children:[]});
  study.tap('c3');assert.notEqual(study.history()[0],first);
  undoGuess(study);study.tap('c3');assert.equal(study.history()[0],first);
  assert.equal(study.ranks.length,1);assert.equal(study.saves.length,5);
  assert.ok(study.saves.every(s=>s.children.length<=1),'correction never accumulates discarded leaves');
});
test('all candidates cycle; known leaves are removable but existing nonleaf continuations survive',async()=>{
  const study=await controller('3R4/7k/8/8/8/2B2N2/8/K2R4 w - - 0 1');
  study.tap('d4');const order=study.choice().ranking.map(r=>r.from);
  assert.equal(order.length,4);
  for(let i=1;i<=4;i++){undoGuess(study);study.tap('d4');assert.equal(study.current().move.from,order[i%4]);assert.equal(study.root.children.length,1);}
  const existing=await controller();existing.commit('b1','c3');existing.commit('e7','e5');existing.back();existing.commit('c2','c3');existing.back();
  const main=existing.root.children[0],reply=main.children[0];
  existing.tap('c3');undoGuess(existing);existing.tap('c3');undoGuess(existing);
  assert.deepEqual(existing.root.children,[main]);assert.equal(main.children[0],reply);
});
test('capture, en passant, castling and promotion corrections restore complete parent state',async()=>{
  for(const [fen,to] of [
    ['4k3/8/8/8/3p4/2P1P3/8/4K3 w - - 12 24','d4'],
    ['4k3/8/8/2PpP3/8/8/8/4K3 w - d6 0 14','d6'],
    ['r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 18 40','g1'],
    ['1r5k/P1P5/8/8/8/8/8/7K w - - 0 17','b8'],
  ]) {
    const study=await controller(fen);study.tap(to);const count=study.choice().ranking.length;
    for(let i=0;i<count;i++) {
      if(study.current()===study.root){assert.deepEqual(study.history(),[]);study.promote('n');}
      const before=study.choice().parent.fen;undoGuess(study);
      assert.equal(study.fen(),before);assert.deepEqual(study.history(),[]);
      assert.equal(study.root.children.length,0);
      if(i+1<count)study.tap(to);
    }
  }
});
test('a same-square tap can recapture normally; navigation or explicit moves discard cycling state',async()=>{
  const capture=await controller('7k/8/8/4p3/3p4/2P1P3/8/7K w - - 0 1');
  capture.tap('d4');capture.tap('d4');assert.equal(capture.history().length,2);
  assert.equal(capture.history()[1],'e5d4');assert.equal(capture.ranks.length,2);
  const study=await controller();study.commit('e2','e4');study.commit('e7','e5');const parent=study.current();
  study.tap('f3');const history=study.ranks[0][3];undoGuess(study);assert.deepEqual(study.recent(),history);
  study.tap('f3');assert.equal(study.ranks.length,1);
  study.back(parent);assert.equal(study.choice(),null);study.tap('f3');assert.equal(study.ranks.length,2);
  study.commit('b8','c6');assert.equal(study.choice(),null);assert.equal(study.undo('f3','g1'),false);
});
test('model remains offline and the two-ply context comes from the saved study path',async()=>{
  assert.doesNotMatch(source,/\bfetch\s*\(|XMLHttpRequest|WebSocket/);
  const study=await controller();study.commit('d2','d4');study.commit('d7','d5');study.commit('c2','c4');
  const parent=study.current();study.back(parent);study.tap('f6');
  assert.deepEqual(study.ranks[0][3],[{from:'d7',to:'d5',promotion:null},{from:'c2',to:'c4',promotion:null}]);
});
