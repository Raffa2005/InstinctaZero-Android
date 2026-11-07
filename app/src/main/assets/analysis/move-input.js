/* Local input assistance only; no search, transport or study-state changes. */
(function(scope) {
  'use strict';
  const uciOf = move => move.from + move.to + (move.promotion || '');
  function aliases(legal) {
    const result=new Map();
    for(const move of legal) {
      const uci=uciOf(move);result.set(uci,uci);
      // Explorer UCI uses king-to-rook castling. Convert only a castle which
      // the rules engine already declares legal, never by coordinates alone.
      if(move.piece==='k' && (move.flags==='k' || move.flags==='q'))
        result.set(move.from+(move.flags==='k'?'h':'a')+move.from[1],uci);
    }
    return result;
  }
  function normalizeUci(uci,legal) {return aliases(legal).get(uci) || null;}
  const metric=value=>value!==null && value!==undefined && value!=='' && Number.isFinite(Number(value)) && Number(value)>=0 ? Number(value) : null;
  class EngineCache {
    constructor(limit=24) {this.limit=Math.max(1,Math.min(64,limit));this.entries=new Map();}
    key(fen,backend) {return backend+'|'+fen;}
    remember(fen,backend,legal,data) {
      if(!data || data.inherited || data.search_phase==='provisional' || (data.input_fen && data.input_fen!==fen))return;
      const moves=Array.isArray(data.move_stats)?data.move_stats:[],root=Array.isArray(data.root_move_stats)?data.root_move_stats:[];
      if(!moves.length && !root.length)return;
      const mapping=aliases(legal),rows=new Map();
      // The complete root statistics take precedence over display-filtered rows.
      for(const source of [moves,root])for(const stat of source) {
        const uci=mapping.get(stat?.uci);if(!uci)continue;
        const previous=rows.get(uci) || {visits:0,prior:0};
        rows.set(uci,{visits:metric(stat.visits) ?? previous.visits,prior:metric(stat.prior) ?? previous.prior});
      }
      if(![...rows.values()].some(row=>row.visits>0 || row.prior>0))return;
      const key=this.key(fen,backend);this.entries.delete(key);this.entries.set(key,rows);
      while(this.entries.size>this.limit)this.entries.delete(this.entries.keys().next().value);
    }
    rank(fen,backend,legal,to) {
      const key=this.key(fen,backend),evidence=this.entries.get(key);if(!evidence)return null;
      const origins=new Map();
      for(const move of legal)if(move.to===to) {
        const row=origins.get(move.from) || {from:move.from,moves:[],visits:0,prior:0};
        const uci=uciOf(move),stat=evidence.get(uci);row.moves.push(uci);
        if(stat){row.visits=Math.max(row.visits,stat.visits);row.prior=Math.max(row.prior,stat.prior);}
        origins.set(move.from,row);
      }
      const rows=[...origins.values()];if(!rows.some(row=>row.visits>0 || row.prior>0))return null;
      this.entries.delete(key);this.entries.set(key,evidence);
      return rows.sort((a,b)=>b.visits-a.visits || b.prior-a.prior);
    }
  }
  scope.InstinctaMoveInput={normalizeUci,EngineCache};
})(typeof window==='object'?window:globalThis);
