/* Current-position evidence only. Candidate cycling belongs to the study controller. */
(function(scope) {
  'use strict';
  const key = fen => String(fen || '').split(' ').slice(0,4).join(' ');
  function rank(fen, legal, destination, evidence) {
    if(!evidence || key(evidence.input_fen)!==key(fen))return null;
    const rows=new Map();
    for(const move of legal)if(move.to===destination) {
      if(!rows.has(move.from))rows.set(move.from,{from:move.from,moves:[],visits:0,prior:0,line:Infinity});
      rows.get(move.from).moves.push(move.from+move.to+(move.promotion || ''));
    }
    let usable=false;
    const stats=[...(evidence.root_move_stats || []),...(evidence.move_stats || [])];
    for(const row of rows.values()) {
      for(const stat of stats)if(row.moves.includes(stat.uci)) {
        row.visits=Math.max(row.visits,Number(stat.visits)||0);
        row.prior=Math.max(row.prior,Number(stat.prior)||0);
      }
      (evidence.lines || []).forEach((line,index)=>{if(row.moves.includes(line?.pv?.[0]))row.line=Math.min(row.line,index);});
      if(row.visits>0 || row.prior>0 || Number.isFinite(row.line))usable=true;
    }
    return usable ? [...rows.values()].sort((a,b)=>b.visits-a.visits || b.prior-a.prior || a.line-b.line) : null;
  }
  scope.InstinctaEngineInput={rank,key};
})(typeof window==='object'?window:globalThis);
