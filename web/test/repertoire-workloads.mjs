// Public move sequences only. Private corpus/results stay outside the repository.
// node web/test/repertoire-workloads.mjs /tmp/repertoire-workloads.json
import {Chess} from 'chess.js';
import {writeFile} from 'node:fs/promises';
const output=process.argv[2];
if(!output)throw Error('Supply an output JSON path');
const all=['alapin','qga','taimanov','ruy_lopez','jobava_london','symmetrical_english'];
const lines={
  initial:'', c4:'c4',
  'english-middle':'c4 c5 Nc3 Nc6 g3 g6 Bg2 Bg7 Nf3 d6 O-O e6',
  'english-later':'c4 c5 Nc3 Nc6 g3 g6 Bg2 Bg7 Nf3 d6 O-O e6 e3 Nge7 d4 O-O',
  'qga-transposed':'d4 d5 c4 dxc4 Nf3 Nf6 e3 e6 Bxc4 c5 O-O a6 dxc5 Bxc5 Qe2 Nbd7',
  'qga-rd1':'d4 d5 c4 dxc4 Nf3 Nf6 e3 e6 Bxc4 c5 O-O a6 dxc5 Bxc5 Qe2 Nbd7 Rd1',
  'outside-early':'a3 h6 a4',
  'outside-later':'c4 c5 Nc3 Nc6 g3 g6 Bg2 Bg7 Nf3 d6 O-O e6 e3 Nge7 d4 O-O Kh1',
  'same-outside-different-history':'c4 c5 Nc3 Nc6 g3 g6 Bg2 Bg7 Nf3 d6 O-O e6 Kh1 Nge7 e3 O-O d4'
};
function request(sans,selected) {
  const chess=new Chess(),root=chess.fen(),history=[],entries=[];
  for(const san of sans.split(' ').filter(Boolean)){
    const move=chess.move(san);history.push(move.from+move.to+(move.promotion||''));
    entries.push({san:move.san,fen:chess.fen().split(' ').slice(0,4).join(' ')});
  }
  return {root,fen:chess.fen().split(' ').slice(0,4).join(' '),history,entries,selected};
}
const cases=[];
for(const [name,sans] of Object.entries(lines))for(const selected of [[name.startsWith('qga')?'qga':'symmetrical_english'],all])
  cases.push({name:`${name}/${selected.length}`,request:request(sans,selected)});
const sans=lines['english-later'].split(' ');
for(const ply of [...Array.from({length:17},(_,i)=>i),...Array.from({length:16},(_,i)=>15-i),...Array.from({length:16},(_,i)=>i+1)])
  cases.push({name:`navigate-${cases.length}/ply-${ply}`,request:request(sans.slice(0,ply).join(' '),all)});
await writeFile(output,JSON.stringify(cases));
console.log(`${cases.length} real-navigation workloads written`);
