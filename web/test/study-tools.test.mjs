import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {Chess} from 'chess.js';
import {readFile} from 'node:fs/promises';
const require=createRequire(import.meta.url);
const tools=require('../../app/src/main/assets/analysis/study-tools.js');
const pgn='[Event "Training"]\n[White "Rafael"]\n[Black "Opponent"]\n\n1. e4 {Main idea} e5 (1... c5 2. Nf3 (2. Nc3) d6) (1... e6) 2. Nf3 $1 Nc6 *';
test('study names prefer real PGN filenames and safely fall back for shared text',()=>{
  assert.equal(tools.pgnStudyName('My Sicilian.PGN','Wrong event'),'My Sicilian');
  assert.equal(tools.pgnStudyName('folder/White repertoire.pgn','?'),'White repertoire');
  assert.equal(tools.pgnStudyName('C:\\chess\\Black.pgn','?'),'Black');
  assert.equal(tools.pgnStudyName(null,'Training'),'Training');
  assert.equal(tools.pgnStudyName('', '?'),'Imported study');
  assert.equal(tools.pgnStudyName('Übung "e4".pgn','?'),'Übung "e4"');
});
test('PGN imports branches in canonical order and preserves comments, NAGs and headers',()=>{
  const [state]=tools.parsePgn(pgn,Chess);
  assert.deepEqual(state.tree[0].c.map(n=>n.u),['e7e5','c7c5','e7e6']);
  assert.equal(state.tree[0].comment,'Main idea');
  const [again]=tools.parsePgn(tools.exportPgn(state,Chess),Chess);
  assert.deepEqual(again,state);
});
test('multi-game PGN becomes independent chapters including custom FEN and black to move',()=>{
  const result=tools.parsePgn(pgn+'\n[Event "Endgame"]\n[SetUp "1"]\n[FEN "8/8/8/8/8/2k5/8/K7 b - - 0 1"]\n1... Kb3 *',Chess);
  assert.equal(result.length,2);assert.equal(result[1].tree[0].u,'c3b3');
  assert.deepEqual(tools.parsePgn(tools.exportPgn(result[1],Chess),Chess)[0],result[1]);
});
test('imports reject illegal moves, unclosed variations and oversized input without partial results',()=>{
  for(const invalid of ['1. e5 *','1. e4 (1. d4','1. e4 )','x'.repeat(1048577)])assert.throws(()=>tools.parsePgn(invalid,Chess));
});
test('symbolic annotations survive PGN export as standard numeric annotations',()=>{
  const [state]=tools.parsePgn('1. e4! e5?! *',Chess);
  assert.deepEqual(state.tree[0].nags,['$1']);assert.deepEqual(state.tree[0].c[0].nags,['$6']);
  assert.deepEqual(tools.parsePgn(tools.exportPgn(state,Chess),Chess)[0].tree,state.tree);
});
test('repertoire transpositions ignore clocks but keep turn, castling and en-passant rights',()=>{
  const [state]=tools.parsePgn('1. Nf3 d5 2. d4 Nf6 3. c4 *',Chess);state.repertoireColor='w';
  const index=tools.repertoireIndex([state],Chess);
  const result=tools.deviation(index,'w',['d2d4','d7d5','g1f3','g8f6'],new Chess().fen(),Chess);
  assert.equal(result.known,true);assert.equal(result.transposed,true);assert.equal(result.first.who,'You');assert.deepEqual(result.next,['c2c4']);
  const key=tools.positionKey(new Chess().fen());
  assert.equal(key,tools.positionKey(new Chess().fen().replace('0 1','8 20')));
  assert.notEqual(key,tools.positionKey(new Chess().fen().replace(' w ',' b ')));
  assert.notEqual(key,tools.positionKey(new Chess().fen().replace(' KQkq ',' - ')));
});
test('opponent deviation and deleted repertoire lines are reflected by the local graph',()=>{
  const [state]=tools.parsePgn('1. e4 e5 (1... c5) 2. Nf3 *',Chess);state.repertoireColor='w';
  let graph=tools.repertoireIndex([state],Chess);
  assert.equal(tools.deviation(graph,'w',['e2e4','c7c5'],state.initialFen,Chess).first,null);
  state.tree[0].c.splice(1,1);graph=tools.repertoireIndex([state],Chess);
  assert.equal(tools.deviation(graph,'w',['e2e4','c7c5'],state.initialFen,Chess).first.who,'Opponent');
});
test('promote up one reorders one sibling without changing ordinary forward navigation',async()=>{
  const source=await readFile(new URL('../../app/src/main/assets/analysis/analysis.js',import.meta.url),'utf8');
  const promote=source.slice(source.indexOf('  function promoteVariation('),source.indexOf('  function deleteVariation('));
  const a={},b={},c={},parent={children:[a,b,c]};c.parent=parent;
  const run=new Function('variationTarget','mainlineChild','saveStudyNow','renderPanel','closePanelView','library','studyContext',`let panelView;${promote};promoteVariation(true);`);
  run(c,n=>n.children[0],()=>{},()=>{},()=>{},null,{});
  assert.deepEqual(parent.children,[a,c,b]);assert.equal(parent.selectedChild,a);
});
