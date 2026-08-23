/* Offline study controller: chess.js owns every legal move and FEN; legacy Chessground owns rendering. */
(function () {
  'use strict';
  const FEN = 'r2q1rk1/6pp/p2br3/3p1p2/1PbP4/2P1QP2/6PP/2B1R1K1 b - - 0 24';
  const Chess = window.InstinctaZeroChessRules && window.InstinctaZeroChessRules.Chess;
  if (!Chess || !window.LegacyChessground) throw new Error('Offline chess assets did not load');
  const boardEl = document.getElementById('board');
  const panel = document.getElementById('panel');
  const title = document.getElementById('tab-title');
  const wrap = document.getElementById('board-wrap');
  const chess = new Chess(FEN);
  let tab = 'engine';
  let promotionPicker = null;
  let nodeId = 0;
  const root = { id: nodeId, fen: chess.fen(), san: null, move: null, number: chess.moveNumber(), color: chess.turn(), parent: null, children: [], selectedChild: null };
  let cursor = root;
  let restoring = false;

  function color() { return chess.turn() === 'w' ? 'white' : 'black'; }
  function dests() {
    const result = {};
    chess.moves({ verbose: true }).forEach(move => {
      (result[move.from] || (result[move.from] = [])).push(move.to);
    });
    return result;
  }
  function sync(lastMove) {
    ground.set({ fen: chess.fen(), turnColor: color(), dests: dests(), lastMove: lastMove || null });
  }
  function remember(move, number, mover) {
    let child = cursor.children.find(item => item.san === move.san && item.fen === chess.fen());
    if (!child) {
      child = {
        id: ++nodeId, fen: chess.fen(), san: move.san,
        move: { from: move.from, to: move.to, promotion: move.promotion || null },
        number: number, color: mover, parent: cursor, children: [], selectedChild: null
      };
      cursor.children.push(child);
    }
    cursor.selectedChild = child;
    cursor = child;
  }
  function commitMove(from, to, promotion) {
    const number = chess.moveNumber();
    const mover = chess.turn();
    let move;
    try { move = chess.move({ from: from, to: to, promotion: promotion }); } catch (_) { move = null; }
    if (!move) { sync(null); return; }
    remember(move, number, mover);
    sync([from, to]);
    renderPanel();
  }
  function choosePromotion(from, to, options) {
    if (promotionPicker) return;
    const picker = document.createElement('div');
    picker.className = 'promotion-picker';
    picker.setAttribute('role', 'dialog');
    picker.setAttribute('aria-modal', 'true');
    picker.setAttribute('aria-label', 'Choose promotion piece');
    const promotionNames = { q: 'Queen', r: 'Rook', b: 'Bishop', n: 'Knight' };
    const choices = new Set(options.map(move => move.promotion));
    picker.innerHTML = ['q', 'r', 'b', 'n'].filter(piece => choices.has(piece)).map(piece => '<button type="button" data-promotion="' + piece + '" aria-label="Promote to ' + promotionNames[piece] + '">' + piece.toUpperCase() + '</button>').join('') + '<button type="button" data-cancel aria-label="Cancel promotion">×</button>';
    const close = revert => { picker.remove(); promotionPicker = null; if (revert) { ground.cancelMove(); sync(null); } };
    picker.querySelectorAll('[data-promotion]').forEach(button => button.onclick = () => { const promotion = button.dataset.promotion; close(false); commitMove(from, to, promotion); });
    picker.querySelector('[data-cancel]').onclick = () => close(true);
    boardEl.appendChild(picker);
    promotionPicker = picker;
    const firstChoice = picker.querySelector('[data-promotion]');
    if (firstChoice) firstChoice.focus();
  }
  function onMove(from, to) {
    if (restoring || promotionPicker) { sync(null); return; }
    const options = chess.moves({ verbose: true }).filter(move => move.from === from && move.to === to);
    if (!options.length) { sync(null); return; }
    if (options.some(move => move.promotion)) choosePromotion(from, to, options);
    else commitMove(from, to);
  }
  const ground = window.LegacyChessground(boardEl, {
    fen: chess.fen(), orientation: 'black', turnColor: color(), coordinates: true,
    lastMove: ['d2', 'c1'], animation: { enabled: true, duration: 120 },
    movable: { color: 'both', free: false, dests: dests(), showDests: true },
    selectable: { enabled: true }, draggable: { enabled: true, magnified: true },
    events: { move: onMove }
  });
  function engine() {
    return '<div class="stats"><span><b>Depth:</b> 19/22</span><span><b>kn/s:</b> 405</span><span><b>nodes:</b> 5705k</span><span><b>time:</b> 14s</span></div>' +
      [['-4.4', '24... a5 25. Ba3 Qb8 26. Kh1 a4 27. Qg5 Bf4 28. Qh4 Bd2 29. Re7 Bxe1 30. Qxe1 Qd6 31. Qe5 Qxe5 32. Rxe5 Rae8 33. h4 f4 34. Rxe8 Rxe8'], ['-4.1', '24... f4 25. Qd2 a5 26. Ba3 Qd7 27. h4 Rfe8 28. Rxe8+ Rxe8 29. Bb2 a4 30. h5 Kf7 31. Rxe8 Qxe8 32. Qc2 g6'], ['-3.6', '24... Qd7 25. Qg5 Rae8 26. Rxe8 Rxe8 27. Qh4 Re6 28.']].map(row => '<div class="pv"><strong>' + row[0] + '</strong><span>' + row[1] + '</span></div>').join('');
  }
  function moves() {
    if (!root.children.length) return '<div class="empty">Tap or drag a legal move to begin a local line.</div>';
    // Nesting provides CSP-safe indentation: the shell forbids inline styles.
    const render = node => node.children.map(child => '<div class="branch"><button class="move ' + (child === cursor ? 'current' : '') + '" data-node="' + child.id + '"><span class="no">' + (child.color === 'w' ? child.number + '.' : child.number + '...') + '</span>' + child.san + '</button>' + render(child) + '</div>').join('');
    return '<div class="moves">' + render(root) + '</div>';
  }
  function heading() {
    const names = { engine: 'Local Stockfish 8 analyzing <i class="fa spinner" aria-hidden="true">&#xf110;</i>', moves: 'Moves', info: 'Game information', chart: 'Computer analysis', book: 'Opening book' };
    title.innerHTML = names[tab];
  }
  function renderPanel() {
    panel.innerHTML = tab === 'engine' ? engine() : tab === 'moves' ? moves() : tab === 'info' ? '<div class="empty">Offline local analysis. No game, account, engine, or board data leaves this device.</div>' : tab === 'chart' ? '<div class="empty">Evaluation chart</div>' : '<div class="empty">Opening book disabled offline.</div>';
    heading();
    panel.querySelectorAll('[data-node]').forEach(button => { button.onclick = () => restore(findNode(Number(button.dataset.node))); });
  }
  function findNode(id, node) {
    node = node || root;
    if (node.id === id) return node;
    for (const child of node.children) { const found = findNode(id, child); if (found) return found; }
    return null;
  }
  function restore(next) {
    if (!next) return;
    cursor = next;
    if (cursor.parent) cursor.parent.selectedChild = cursor;
    restoring = true;
    chess.load(cursor.fen);
    sync(null);
    restoring = false;
    renderPanel();
  }
  document.querySelectorAll('.tabs button').forEach(button => {
    button.onclick = () => { tab = button.dataset.tab; document.querySelectorAll('.tabs button').forEach(item => item.classList.toggle('selected', item === button)); renderPanel(); };
  });
  let hold;
  document.addEventListener('keydown', event => { if (event.key === 'Escape' && promotionPicker) { promotionPicker.querySelector('[data-cancel]').click(); } });
  document.querySelectorAll('.actions button').forEach(button => {
    const action = button.dataset.action;
    if (action === 'flip') button.onclick = () => { ground.toggleOrientation(); wrap.classList.toggle('orientation-black'); };
    else if (action === 'size') button.onclick = () => wrap.classList.toggle('expanded');
    else if (action === 'prev' || action === 'next') button.onpointerdown = event => {
      const go = () => restore(action === 'prev' ? cursor.parent : (cursor.selectedChild || cursor.children[0]));
      go(); hold = setInterval(go, 240);
      event.currentTarget.onpointerup = event.currentTarget.onpointerleave = event.currentTarget.onpointercancel = () => clearInterval(hold);
    };
    else button.onclick = () => { tab = action === 'settings' ? 'engine' : 'moves'; renderPanel(); };
  });
  renderPanel();
}());
