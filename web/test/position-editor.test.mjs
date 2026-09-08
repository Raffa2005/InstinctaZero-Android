import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const sandbox={};sandbox.window=sandbox;
for(const file of ['chess-rules.js','position-editor.js'])vm.runInNewContext(await readFile(new URL('../../app/src/main/assets/analysis/'+file,import.meta.url),'utf8'),sandbox);
const P=sandbox.InstinctaZeroPosition,Chess=sandbox.InstinctaZeroChessRules.Chess;
test('FEN round trips preserve side, castling, EP and both counters',()=>{
 for(const fen of [P.START,'r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 23 99','4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 17']){
  const state=P.parse(fen);assert.equal(P.fen(state),fen);assert.equal(P.validate(state).fen,fen);
 }
 assert.equal(P.fen(P.parse('4k3/8/8/8/8/8/8/4K3 w - -')),'4k3/8/8/8/8/8/8/4K3 w - - 0 1');
 const ep=new Chess(P.validate(P.parse('4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 17')).fen);
 assert.equal(ep.move('exd6').flags,'e');assert.equal(ep.get('d5'),undefined);assert.equal(ep.moveNumber(),17);
 const blackEp=new Chess(P.validate(P.parse('4k3/8/8/8/3Pp3/8/8/4K3 b - d3 0 17')).fen);
 assert.equal(blackEp.move('exd3').flags,'e');assert.equal(blackEp.get('d4'),undefined);assert.equal(blackEp.moveNumber(),18);
 for(const [turn,move] of [['w','O-O'],['b','O-O-O']]) {
  const castle=new Chess(P.validate(P.parse('r3k2r/8/8/8/8/8/8/R3K2R '+turn+' KQkq - 0 1')).fen);
  assert.equal(castle.move(move).san,move);
 }
});
test('incomplete setups stay editable but cannot be analysed',()=>{
 for(const [fen,error] of [['8/8/8/8/8/8/8/8 w - - 0 1',/White king/],['4k3/4K3/8/8/8/8/8/8 w - - 0 1',/just moved/],['4k3/8/8/8/8/8/8/4K2P w - - 0 1',/Pawns/],['4k3/8/8/8/8/8/8/4K3 w K - 0 1',/Castling/],['4k3/8/8/8/8/8/8/4K3 w - d6 0 1',/En-passant/],['4k3/8/8/3pP3/8/8/8/4K3 w - d6 7 1',/En-passant/]])assert.match(P.validate(P.parse(fen)).error,error);
 assert.throws(()=>P.parse('invalid FEN'));assert.throws(()=>P.parse('x'.repeat(201)));assert.throws(()=>P.parse('8/8/8/8/8/8/8/44 w - - 0 1'));
});
test('editing pieces removes obsolete rights without inventing castling rights',()=>{
 const state=P.parse(P.START);state.pieces.h3=state.pieces.h1;delete state.pieces.h1;P.changed(state);assert.equal(state.rights,'Qkq');
 state.pieces.h1=state.pieces.h3;delete state.pieces.h3;P.changed(state);assert.equal(state.rights,'Qkq');assert.equal(P.canCastle(state,'K'),true);
 const ep=P.parse('4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 17');P.changed(ep);assert.equal(ep.ep,'-');assert.equal(ep.full,17);
});
test('non-capturable but genuine EP square is preserved and checkmate is valid',()=>{
 const fen='4k3/8/8/3p4/8/8/8/4K3 w - d6 0 1';assert.equal(P.validate(P.parse(fen)).fen,fen);
 assert.ok(P.validate(P.parse('7k/6Q1/6K1/8/8/8/8/8 b - - 0 1')).fen);
});
