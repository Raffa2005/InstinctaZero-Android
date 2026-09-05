/* Pure, offline PGN and repertoire helpers. No network or storage access. */
(function (scope) {
  'use strict';
  const MAX_NODES = 4096, MAX_DEPTH = 512;
  function pgnStudyName(filename, event) {
    const name=String(filename || '').split(/[\\/]/).pop().replace(/[\u0000-\u001f\u007f]/g,'').trim().replace(/\.pgn$/i,'').trim();
    return (name || (event && event !== '?' ? String(event) : 'Imported study')).slice(0,255);
  }
  function positionKey(fen) { return fen.split(/\s+/).slice(0, 4).join(' '); }
  function parsePgn(text, Chess) {
    if (typeof text !== 'string' || text.length > 1048576) throw Error('PGN exceeds the 1 MB import limit.');
    const tokens = text.replace(/^\uFEFF/, '').match(/\[(?:[^"\]]|"(?:\\.|[^"\\])*")*\]|\{[^}]*\}|;[^\r\n]*|\(|\)|\$\d+|[^\s(){};\[\]]+/g) || [];
    const games = []; let headers = {}, root, current, chess, stack = [], count = 0, ended = false;
    function start() { chess = new Chess(headers.FEN || undefined); root = { c:[], comment:'' }; current = root; stack = []; }
    function finish() {
      if (!root) { if (!Object.keys(headers).length) return; start(); }
      if (stack.length) throw Error('Unclosed PGN variation.');
      games.push({ headers, initialFen:new Chess(headers.FEN || undefined).fen(), tree:root.c, rootComment:root.comment });
      if (games.length > 100) throw Error('Import at most 100 chapters at once.');
      headers = {}; root = null; ended = false;
    }
    for (let token of tokens) {
      if (token[0] === '[') {
        if (root) finish();
        const tag = token.match(/^\[(\w+)\s+"((?:\\.|[^"\\])*)"\]$/);
        if (!tag) throw Error('Malformed PGN header.');
        headers[tag[1]] = tag[2].replace(/\\(["\\])/g, '$1'); continue;
      }
      if (ended && token[0] !== '{' && token[0] !== ';') finish();
      if (!root) start();
      if (token[0] === '{' || token[0] === ';') { const comment = token[0] === '{' ? token.slice(1,-1) : token.slice(1); current.comment = [current.comment, comment.trim()].filter(Boolean).join(' '); continue; }
      if (token === '(') {
        if (!current.parent || stack.length >= 64) throw Error('Invalid or excessively nested PGN variation.');
        stack.push(current); current = current.parent; chess.load(current.fen || new Chess(headers.FEN || undefined).fen()); continue;
      }
      if (token === ')') { if (!stack.length) throw Error('Unexpected end of variation.'); current = stack.pop(); chess.load(current.fen); continue; }
      if (/^\$\d+$/.test(token)) { (current.nags || (current.nags=[])).push(token); continue; }
      token = token.replace(/^\d+\.(?:\.\.)?/, '');
      if (!token || /^\.+$/.test(token) || /^e\.p\.$/.test(token)) continue;
      if (/^(1-0|0-1|1\/2-1\/2|\*)$/.test(token)) { if (!stack.length) { headers.Result = token; ended = true; } continue; }
      let move; try { move = chess.move(token); } catch (_) { throw Error('Illegal PGN move: ' + token.slice(0,32)); }
      if (!move) throw Error('Illegal PGN move.');
      if (++count > MAX_NODES) throw Error('Import at most 4096 moves at once.');
      const u = move.from + move.to + (move.promotion || '');
      let child = current.c.find(n => n.u === u);
      if (!child) { child = { u, c:[], parent:current, fen:chess.fen(), depth:(current.depth || 0)+1 }; current.c.push(child); }
      if (child.depth > MAX_DEPTH) throw Error('A line may contain at most 512 plies.');
      const annotation=token.match(/(!!|\?\?|!\?|\?!|!|\?)$/);
      if(annotation) (child.nags || (child.nags=[])).push({'!':'$1','?':'$2','!!':'$3','??':'$4','!?':'$5','?!':'$6'}[annotation[1]]);
      current = child;
    }
    finish();
    if (!games.length) throw Error('No PGN games found.');
    function wire(node) { const out = { u:node.u, c:node.c.map(wire) }; if (node.comment) out.comment=node.comment; if (node.nags) out.nags=node.nags; return out; }
    return games.map(g => Object.assign(g, {tree:g.tree.map(wire)}));
  }
  function exportPgn(state, Chess) {
    const headers = Object.assign({}, state.headers || {}), initial = state.initialFen || new Chess().fen();
    if (initial !== new Chess().fen()) { headers.SetUp='1'; headers.FEN=initial; }
    if (!headers.Event) headers.Event=state.chapterTitle || state.title || 'Analysis';
    headers.Result=headers.Result || '*';
    const comment = s => s ? ' {' + String(s).replace(/[{}]/g,'') + '}' : '';
    function branch(node, fen, depth) {
      if (depth > MAX_DEPTH) throw Error('Line too long.');
      const board=new Chess(fen), number=board.moveNumber(), turn=board.turn();
      const move=board.move({from:node.u.slice(0,2),to:node.u.slice(2,4),promotion:node.u[4]});
      return number+(turn==='w'?'. ':'... ')+move.san+(node.nags || []).filter(n=>/^\$\d+$/.test(n)).map(n=>' '+n).join('')+comment(node.comment)+continuation(node.c,board.fen(),depth+1);
    }
    function continuation(children,fen,depth) {
      if (!children || !children.length) return '';
      const main=children[0], board=new Chess(fen), number=board.moveNumber(),turn=board.turn();
      const move=board.move({from:main.u.slice(0,2),to:main.u.slice(2,4),promotion:main.u[4]});
      return ' '+number+(turn==='w'?'. ':'... ')+move.san+(main.nags || []).filter(n=>/^\$\d+$/.test(n)).map(n=>' '+n).join('')+comment(main.comment)+children.slice(1).map(n=>' ('+branch(n,fen,depth)+')').join('')+continuation(main.c,board.fen(),depth+1);
    }
    return Object.entries(headers).filter(([key])=>/^\w+$/.test(key)).map(([k,v])=>'['+k+' "'+String(v).replace(/\\/g,'\\\\').replace(/"/g,'\\"').replace(/[\r\n]/g,' ')+'"]' ).join('\n')+'\n\n'+(comment(state.rootComment)+continuation(state.tree,initial,0)).trim()+' '+headers.Result+'\n';
  }
  function repertoireIndex(states, Chess) {
    const index={w:{},b:{}};
    for (const state of states) {
      const side=state.repertoireColor; if (!index[side]) continue;
      function visit(children,fen,depth) {
        if (depth>MAX_DEPTH) return;
        const key=positionKey(fen), edges=index[side][key] || (index[side][key]=[]);
        for (const node of children || []) {
          const board=new Chess(fen); try { board.move({from:node.u.slice(0,2),to:node.u.slice(2,4),promotion:node.u[4]}); } catch (_) { continue; }
          if (!edges.includes(node.u)) edges.push(node.u);
          visit(node.c,board.fen(),depth+1);
        }
      }
      visit(state.tree,state.initialFen || new Chess().fen(),0);
    }
    return index;
  }
  function deviation(index, side, path, initialFen, Chess) {
    const positions=index[side] || {}, board=new Chess(initialFen); let first=null;
    for (let i=0;i<path.length;i++) {
      const edges=positions[positionKey(board.fen())], mover=board.turn(), u=path[i];
      if (!first && edges && edges.length && !edges.includes(u)) first={ply:i+1,who:mover===side?'You':'Opponent'};
      try { board.move({from:u.slice(0,2),to:u.slice(2,4),promotion:u[4]}); } catch (_) { break; }
    }
    const next=positions[positionKey(board.fen())];
    return {first,known:!!next,next:next || [],transposed:!!next && !!first};
  }
  const api={pgnStudyName,parsePgn,exportPgn,positionKey,repertoireIndex,deviation,MAX_NODES,MAX_DEPTH};
  if (typeof module !== 'undefined') module.exports=api; else scope.InstinctaZeroStudyTools=api;
}(typeof window === 'undefined' ? globalThis : window));
