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
