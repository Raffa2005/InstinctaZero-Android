import assert from 'node:assert/strict';
import test from 'node:test';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {Chess} from 'chess.js';
const context={};vm.runInNewContext(await readFile(new URL('../../app/src/main/assets/analysis/move-input.js',import.meta.url),'utf8'),context);
const {EngineCache,normalizeUci}=context.InstinctaMoveInput;
const chess=new Chess(),fen=chess.fen(),legal=chess.moves({verbose:true});
test('visits precede policy; complete N=0 root policy works without display lines',()=>{
 const cache=new EngineCache();
 cache.remember(fen,'cpu',legal,{root_move_stats:[{uci:'c2c3',visits:20,prior:.1},{uci:'b1c3',visits:5,prior:.9}]});
 assert.equal(cache.rank(fen,'cpu',legal,'c3')[0].from,'c2');
 cache.remember(fen,'cpu',legal,{move_stats:[{uci:'c2c3',visits:30}],root_move_stats:[{uci:'c2c3',visits:0,prior:.1},{uci:'b1c3',visits:0,prior:.9}]});
 assert.equal(cache.rank(fen,'cpu',legal,'c3')[0].from,'b1');
 assert.equal(cache.rank(fen,'cpu',legal,'e4'),null,'other destinations retain offline fallback');
});
test('position/backend matching and provisional/inherited/invalid statistics fail closed',()=>{
 const cache=new EngineCache(),data={root_move_stats:[{uci:'c2c3',visits:10,prior:.8}]};
 for(const extra of [{search_phase:'provisional'},{inherited:true},{input_fen:fen.replace(' w ',' b ')}]) {
  cache.remember(fen,'cpu',legal,{...data,...extra});assert.equal(cache.rank(fen,'cpu',legal,'c3'),null);
 }
 cache.remember(fen,'cpu',legal,{move_stats:[{uci:'c2c3',visits:Infinity,prior:NaN},{uci:'b1c3',visits:-1,prior:0},{uci:'a1c3',visits:1e9}]});
 assert.equal(cache.rank(fen,'cpu',legal,'c3'),null);
 cache.remember(fen,'cpu',legal,data);
 for(const changed of [fen.replace(' w ',' b '),fen.replace('KQkq','-'),fen.replace('0 1','1 1')])assert.equal(cache.rank(changed,'cpu',legal,'c3'),null);
 assert.equal(cache.rank(fen,'sycl',legal,'c3'),null);
 assert.equal(cache.rank(fen,'cpu',legal.filter(m=>m.from!=='c2'),'c3'),null,'stale illegal origin never offered');
});
test('cache is bounded, position matched, and keeps only copied legal input statistics',()=>{
 const cache=new EngineCache(2),data={root_move_stats:[{uci:'c2c3',visits:10,prior:.8}],lines:[{pv:Array(1000).fill('e2e4')}]};
 cache.remember(fen,'cpu',legal,data);data.root_move_stats[0].visits=0;data.root_move_stats[0].prior=0;
 assert.equal(cache.rank(fen,'cpu',legal,'c3')[0].visits,10);
 for(let i=2;i<=3;i++)cache.remember(fen.replace('0 1',`0 ${i}`),'cpu',legal,{root_move_stats:[{uci:'c2c3',visits:i}]});
 assert.equal(cache.entries.size,2);assert.equal(cache.rank(fen,'cpu',legal,'c3'),null);
 assert.equal(cache.rank(fen.replace('0 1','0 2'),'cpu',legal,'c3')[0].visits,2);
 assert.ok([...cache.entries.values()].every(rows=>rows.size<=legal.length && [...rows.values()].every(row=>Object.keys(row).join(',')==='visits,prior')));
});
test('both castle encodings normalize only when the actual castle is legal, for both colors',()=>{
 for(const turn of ['w','b'])for(const side of ['k','q']) {
  const rank=turn==='w'?'1':'8',board=new Chess(`r3k2r/8/8/8/8/8/8/R3K2R ${turn} KQkq - 0 1`),moves=board.moves({verbose:true});
  const canonical=`e${rank}${side==='k'?'g':'c'}${rank}`,external=`e${rank}${side==='k'?'h':'a'}${rank}`;
  assert.equal(normalizeUci(external,moves),canonical);assert.equal(normalizeUci(canonical,moves),canonical);
  const cache=new EngineCache();cache.remember(board.fen(),'cpu',moves,{root_move_stats:[{uci:external,visits:20}]});
  assert.equal(cache.rank(board.fen(),'cpu',moves,canonical.slice(2))[0].from,`e${rank}`);
  assert.equal(normalizeUci(external,new Chess(board.fen().replace('KQkq','-')).moves({verbose:true})),null);
 }
 assert.equal(normalizeUci('e1h1',legal),null,'blocked castling remains illegal');
 assert.equal(normalizeUci('e1h1',new Chess('4kr2/8/8/8/8/8/8/4K2R w K - 0 1').moves({verbose:true})),null,'castling through check stays illegal');
 assert.equal(normalizeUci('e2e4',legal),'e2e4');assert.equal(normalizeUci('e2e4q',legal),null);
 const promotions=new Chess('7k/P7/8/8/8/8/8/7K w - - 0 1').moves({verbose:true});
 assert.equal(normalizeUci('a7a8',promotions),null);assert.equal(normalizeUci('a7a8n',promotions),'a7a8n');
 const cache=new EngineCache();cache.remember('promotion','cpu',promotions,{root_move_stats:[{uci:'a7a8q',visits:30},{uci:'a7a8n',visits:2}]});
 assert.equal(cache.rank('promotion','cpu',promotions,'a8').length,1);assert.equal(cache.rank('promotion','cpu',promotions,'a8')[0].moves.length,4);
});
