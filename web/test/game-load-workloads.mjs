import {Chess} from 'chess.js';
import {writeFile} from 'node:fs/promises';
const output=process.argv[2];if(!output)throw Error('Supply an output path');
const all=['alapin','caro_kann_fantasy','french_steinitz','jobava_london','qga','ruy_lopez','smith_morra','symmetrical_english','taimanov'];
function game(plies,seed) {
 const chess=new Chess(),root=chess.fen(),history=[],entries=[];
 for(let i=0;i<plies;i++){
  const moves=chess.moves({verbose:true});let played=null;
  seed=(Math.imul(seed,1664525)+1013904223)>>>0;
  for(let j=0;j<moves.length;j++) {
   const candidate=moves[(seed+j)%moves.length];chess.move(candidate);
   if(chess.moves().length){played=candidate;break}chess.undo();
  }
  if(!played)break;
  history.push(played.from+played.to+(played.promotion||''));entries.push({san:played.san,fen:chess.fen()});
 }
 return {root,fen:chess.fen().split(' ').slice(0,4).join(' '),history,entries};
}
const cases=[];
for(const plies of [40,80,160])for(const selected of [['taimanov'],all])cases.push({name:`new-game-${plies}/${selected.length}`,request:{...game(plies,plies+47),selected}});
const chess=new Chess(),root=chess.fen(),history=[],entries=[];
for(const san of 'e4 c5 Nf3 e6 d4 cxd4 Nxd4 Nc6 Nc3 Qc7'.split(' ')){const m=chess.move(san);history.push(m.from+m.to);entries.push({san:m.san,fen:chess.fen()})}
for(const selected of [['taimanov'],all])cases.push({name:`edited-position/${selected.length}`,request:{root,fen:chess.fen().split(' ').slice(0,4).join(' '),history,entries,selected}});
await writeFile(output,JSON.stringify(cases));console.log(`${cases.length} public-move game-load workloads written`);
