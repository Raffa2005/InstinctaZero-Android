import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {Chess} from 'chess.js';
const scope={TextEncoder,setTimeout,performance,InstinctaZeroChessRules:{Chess}};
for(const name of ['engine-input','pgn-import'])vm.runInNewContext(await readFile(new URL('../../app/src/main/assets/analysis/'+name+'.js',import.meta.url),'utf8'),scope);
const {rank}=scope.InstinctaEngineInput,pgn=scope.InstinctaPgn,plain=x=>JSON.parse(JSON.stringify(x));
test('current engine visits, policy and lines outrank fallback; stale and unusable evidence do not',()=>{
  const chess=new Chess(),fen=chess.fen(),moves=chess.moves({verbose:true});
  const evidence={input_fen:fen,move_stats:[{uci:'b1c3',visits:3,prior:.8},{uci:'c2c3',visits:20,prior:.1}],lines:[{pv:['b1c3']}]};
  assert.equal(rank(fen,moves,'c3',evidence)[0].from,'c2');
  evidence.move_stats.forEach(m=>m.visits=0);assert.equal(rank(fen,moves,'c3',evidence)[0].from,'b1');
  evidence.move_stats=[];evidence.lines=[{pv:['c2c3']}];assert.equal(rank(fen,moves,'c3',evidence)[0].from,'c2');
  evidence.lines=[];assert.equal(rank(fen,moves,'c3',evidence),null);
  evidence.move_stats=[{uci:'c2c3',visits:9}];chess.move('e4');assert.equal(rank(chess.fen(),moves,'c3',evidence),null);
  assert.equal(rank(fen.replace('0 1','8 5'),moves,'c3',evidence)[0].from,'c2','same position cache counts');
});
test('engine promotions remain grouped origins with an explicit choice',()=>{
  const chess=new Chess('1r5k/P1P5/8/8/8/8/8/7K w - - 0 1'),fen=chess.fen();
  const rows=rank(fen,chess.moves({verbose:true}),'b8',{input_fen:fen,move_stats:[{uci:'a7b8n',visits:40},{uci:'c7b8q',visits:4}]});
  assert.equal(rows[0].from,'a7');assert.equal(rows[0].moves.length,4);
});
const doc='[Event "First"]\n[White "White"]\n[Black "Black"]\n[Result "1-0"]\n\n{Root comment} 1.e4 {Main comment} (1.d4 d5 {Variation note}) e5 2.Nf3 $1 Nc6 1-0\n\n[Event "Second"]\n[Result "*"]\n\n1.c4 c5 *';
test('multi-game index, comments, variations, headers and untouched original survive parse/export',async()=>{
  const index=await pgn.index(doc);assert.equal(index.length,2);assert.equal(index[1].headers.Event,'Second');
  const original=doc.slice(index[0].start,index[0].end),parsed=await pgn.parse(original,Chess,'en');
  assert.equal(parsed.original,original);assert.equal(parsed.issues.length,0);assert.equal(parsed.count,6);
  assert.equal(parsed.root.children.length,2);assert.equal(parsed.root.children[0].comment,'Main comment');
  assert.equal(parsed.root.children[1].children[0].comment,'Variation note');
  const exported=pgn.exportPgn(parsed.root,parsed.headers),again=await pgn.parse(exported,Chess,'en');
  assert.equal(again.issues.length,0);assert.equal(again.count,6);assert.equal(again.root.comment,'Root comment');
  assert.equal(again.root.children[1].children[0].comment,'Variation note');
});
test('localized notation, figurines, zero-castling, LAN and glued move numbers repair legally',async()=>{
  for(const [text,lang] of [['1.e4 e5 2.Sf3 Sc6 3.Lc4 Sf6 4.0-0','de'],['1.e4 e5 2.Cf3 Cc6 3.Fc4 Cf6 4.0-0','fr'],['1.e2-e4 e7e5 2.♘f3 ♞c6 3.♗c4 ♞f6 4.0-0','auto']]) {
    const parsed=await pgn.parse(text,Chess,lang);assert.equal(parsed.issues.length,0,text);assert.equal(parsed.count,7);assert.ok(parsed.repairs.length>0);
  }
});
test('ambiguous and illegal tokens stop their branch, retain the original, and never invent moves',async()=>{
  const fen='7k/8/8/8/8/8/4K3/R7 w - - 0 1',chess=new Chess(fen);
  assert.equal(pgn.resolveMove(chess,'Rd1','auto').error,'Ambiguous move');
  assert.equal(pgn.resolveMove(chess,'Rd1','en').move.from,'a1');
  assert.equal(pgn.resolveMove(chess,'Rd1','fr').move.from,'e2');
  const text='1.e4 (1.d4 nonsense c5) e5 2.Nf3';const parsed=await pgn.parse(text,Chess);
  assert.equal(parsed.issues.length,1);assert.equal(parsed.count,4);assert.equal(parsed.original,text);
  assert.equal(parsed.root.children[1].children.length,0);
});
test('documents are bounded without truncation and parser is cancelable',async()=>{
  await assert.rejects(pgn.index('x'.repeat(pgn.LIMIT+1)),/8 MiB/);
  await assert.rejects(pgn.parse('1.e4 e5',Chess,'en',()=>true),/cancelled/);
  const indexed=await pgn.index('1.e4 e5 *\n1.d4 d5 *');assert.equal(indexed.length,2);
});
