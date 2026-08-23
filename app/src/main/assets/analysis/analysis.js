/* Local study tree + native authenticated analysis bridge. Credentials never enter this page. */
(function () {
  'use strict';
  const Chess = window.InstinctaZeroChessRules && window.InstinctaZeroChessRules.Chess;
  if (!Chess || !window.LegacyChessground) throw new Error('Offline chess assets did not load');
  const boardEl = document.getElementById('board');
  const panel = document.getElementById('panel');
  const title = document.getElementById('tab-title');
  const wrap = document.getElementById('board-wrap');
  const arrows = document.getElementById('arrows');
  wrap.classList.add('orientation-white');
  const chess = new Chess();
  const START_FEN = chess.fen();
  const safe = value => String(value == null ? '' : value).replace(/[&<>'"]/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;' })[c]);
  const native = () => window.InstinctaZeroNative || null;
  const settings = { nodes: 1000, multipv: 5, enabled: true, showArrows: true };
  let tab = 'engine', overlay = null, promotionPicker = null, nodeId = 0, restoring = false, hold = null;
  const root = { id: 0, fen: START_FEN, san: null, move: null, number: 1, color: 'w', parent: null, children: [], selectedChild: null };
  let cursor = root;
  const engine = { requestId: null, timer: null, status: 'idle', lastGood: null, lines: [], stats: null, progress: null, error: '', selectedPv: null };
  const book = { requestId: null, loading: false, data: null, error: '' };

  function color() { return chess.turn() === 'w' ? 'white' : 'black'; }
  function history() { const result = []; for (let n = cursor; n && n.move; n = n.parent) result.unshift(n.move.from + n.move.to + (n.move.promotion || '')); return result; }
  function legalDests() { const result = {}; chess.moves({ verbose: true }).forEach(move => (result[move.from] || (result[move.from] = [])).push(move.to)); return result; }
  function sync(lastMove) { ground.set({ fen: chess.fen(), turnColor: color(), dests: legalDests(), lastMove: lastMove || null }); }
  function remember(move, number, mover) {
    let child = cursor.children.find(item => item.san === move.san && item.fen === chess.fen());
    if (!child) { child = { id: ++nodeId, fen: chess.fen(), san: move.san, move: { from: move.from, to: move.to, promotion: move.promotion || null }, number, color: mover, parent: cursor, children: [], selectedChild: null }; cursor.children.push(child); }
    cursor.selectedChild = child; cursor = child;
  }
  function cancelBookRequest() { if (book.requestId && native() && native().cancelAnalysis) native().cancelAnalysis(book.requestId); book.requestId = null; book.loading = false; }
  function resetTransport() { if (engine.timer) clearTimeout(engine.timer); engine.timer = null; if (engine.requestId && native()) native().cancelAnalysis(engine.requestId); engine.requestId = null; cancelBookRequest(); }
  function clearEngine(nextStatus) { if (engine.timer) clearTimeout(engine.timer); engine.timer = null; if (engine.requestId && native()) native().cancelAnalysis(engine.requestId); engine.requestId = null; engine.status = nextStatus || 'idle'; engine.lastGood = null; engine.lines = []; engine.stats = null; engine.progress = null; engine.error = ''; engine.selectedPv = null; renderArrows([]); }
  function commitMove(from, to, promotion) {
    const number = chess.moveNumber(), mover = chess.turn(); let move;
    try { move = chess.move({ from, to, promotion }); } catch (_) { move = null; }
    if (!move) { sync(null); return; }
    remember(move, number, mover); sync([from, to]); onPositionChanged();
  }
  function choosePromotion(from, to, options) {
    if (promotionPicker) return;
    const names = { q:'Queen', r:'Rook', b:'Bishop', n:'Knight' }, choices = new Set(options.map(move => move.promotion));
    const picker = document.createElement('div'); picker.className = 'promotion-picker'; picker.setAttribute('role','dialog'); picker.setAttribute('aria-modal','true'); picker.setAttribute('aria-label','Choose promotion piece');
    picker.innerHTML = ['q','r','b','n'].filter(piece => choices.has(piece)).map(piece => '<button type="button" data-promotion="' + piece + '" aria-label="Promote to ' + names[piece] + '">' + piece.toUpperCase() + '</button>').join('') + '<button type="button" data-cancel aria-label="Cancel promotion">×</button>';
    const close = revert => { picker.remove(); promotionPicker = null; if (revert) { ground.cancelMove(); sync(null); } };
    picker.querySelectorAll('[data-promotion]').forEach(button => button.onclick = () => { const promotion = button.dataset.promotion; close(false); commitMove(from, to, promotion); });
    picker.querySelector('[data-cancel]').onclick = () => close(true); boardEl.appendChild(picker); promotionPicker = picker; picker.querySelector('[data-promotion]').focus();
  }
  function onMove(from, to) { if (restoring || promotionPicker) { sync(null); return; } const options = chess.moves({ verbose:true }).filter(move => move.from === from && move.to === to); if (!options.length) { sync(null); return; } if (options.some(move => move.promotion)) choosePromotion(from, to, options); else commitMove(from, to); }
  const ground = window.LegacyChessground(boardEl, { fen: chess.fen(), orientation:'white', turnColor:color(), coordinates:true, animation:{ enabled:true, duration:120 }, movable:{ color:'both', free:false, dests:legalDests(), showDests:true }, selectable:{ enabled:true }, draggable:{ enabled:true, magnified:true }, events:{ move:onMove } });

  function refreshBoardBounds(runtime, element, redraw) {
    runtime = runtime || ground;
    element = element || boardEl;
    requestAnimationFrame(() => {
      runtime.setBounds(element.getBoundingClientRect());
      runtime.redrawSync();
      if (redraw) redraw(); else renderArrows(engineArrowLines());
    });
  }
  function loadUiSettings() { if (!native() || !native().getUiSettings) return; try { const saved = JSON.parse(native().getUiSettings() || '{}'); settings.nodes = Math.max(100, Math.min(100000, Number(saved.nodes) || settings.nodes)); settings.multipv = Math.max(1, Math.min(8, Number(saved.multipv) || settings.multipv)); if (typeof saved.leelaEnabled === 'boolean') settings.enabled = saved.leelaEnabled; if (typeof saved.arrowsEnabled === 'boolean') settings.showArrows = saved.arrowsEnabled; } catch (_) {} }
  function saveUiSettings() { if (!native() || !native().saveUiSettings) return; native().saveUiSettings(JSON.stringify({ nodes: settings.nodes, multipv: settings.multipv, leelaEnabled: settings.enabled, arrowsEnabled: settings.showArrows, appearance: 'brown' })); }
  function studyRequest() { return { history: history(), nodes: settings.nodes, multipv: settings.multipv }; }
  function scheduleAnalysis() { if (!settings.enabled) { clearEngine('off'); renderPanel(); return; } if (!native()) { engine.status = 'disconnected'; renderPanel(); return; } if (engine.timer) clearTimeout(engine.timer); if (engine.requestId) native().cancelAnalysis(engine.requestId); engine.requestId = null; engine.status = 'starting'; engine.error = ''; renderPanel(); engine.timer = setTimeout(() => { engine.timer = null; try { engine.requestId = native().startAnalysis(JSON.stringify(studyRequest())); } catch (_) { engine.status = 'disconnected'; renderPanel(); } }, 120); }
  function requestBook() { if (tab !== 'book' || !native()) { cancelBookRequest(); renderPanel(); return; } cancelBookRequest(); book.loading = true; book.error = ''; renderPanel(); try { book.requestId = native().requestExplorer(JSON.stringify(Object.assign(studyRequest(), { source:'lichess' }))); } catch (_) { book.loading = false; book.error = 'Connection unavailable'; renderPanel(); } }
  function onPositionChanged() { resetTransport(); engine.status = 'idle'; engine.lines = []; engine.stats = null; engine.progress = null; engine.selectedPv = null; renderArrows([]); scheduleAnalysis(); if (tab === 'book') requestBook(); renderPanel(); }
  function score(line) { return line.score == null ? '—' : line.score; }
  function enginePanel() {
    const status = engine.progress ? ' ' + safe(engine.progress.visits || 0) + '/' + safe(engine.progress.target || settings.nodes) : engine.status === 'starting' ? ' starting' : engine.status === 'disconnected' ? ' disconnected' : '';
    const stats = engine.stats || {}; const rows = engine.lines.length ? engine.lines.map((line, index) => '<button class="pv' + (engine.selectedPv === index ? ' selected' : '') + '" data-pv="' + index + '"><strong>' + safe(score(line)) + '</strong><span>' + safe((line.san || []).join(' ') || (line.pv || []).join(' ')) + '</span></button>').join('') : '<div class="empty">' + (engine.error ? safe(engine.error) : 'Waiting for Leela…') + '</div>';
    const progress = engine.progress || {}, visits = progress.visits || 0;
    return '<div class="stats"><span><b>visits:</b> ' + safe(visits) + '/' + safe(progress.target || settings.nodes) + '</span><span><b>nodes:</b> ' + safe(stats.total_nodes || stats.nodes || visits) + '</span><span><b>n/s:</b> ' + safe(stats.nps || progress.nps || 0) + '</span><span><b>time:</b> ' + safe(stats.elapsed_ms != null ? Math.round(stats.elapsed_ms / 1000) + 's' : '—') + '</span></div>' + rows;
  }
  function renderMoves(node) { return node.children.map(child => '<div class="branch"><button class="move ' + (child === cursor ? 'current' : '') + '" data-node="' + child.id + '"><span class="no">' + (child.color === 'w' ? child.number + '.' : child.number + '...') + '</span>' + safe(child.san) + '</button>' + renderMoves(child) + '</div>').join(''); }
  function movesPanel() { return root.children.length ? '<div class="moves">' + renderMoves(root) + '</div>' : '<div class="empty">Tap or drag a legal move to begin a local line.</div>'; }
  function bookPanel() { if (book.loading) return '<div class="empty">Opening book…</div>'; if (book.error) return '<div class="empty">' + safe(book.error) + '</div>'; if (!book.data) return '<div class="empty">Opening book is ready when connected.</div>'; const total = book.data.totals || {}; const moves = (book.data.moves || []).map(move => '<button class="book-move" data-uci="' + safe(move.uci) + '"><b>' + safe(move.san || move.uci) + '</b><span>' + safe(move.games) + ' games · ' + safe(move.white_pct) + '%</span></button>').join(''); return '<div class="book-total"><b>' + safe(total.games || 0) + '</b> games</div><div class="book-moves">' + moves + '</div>'; }
  function heading() { title.innerHTML = tab === 'engine' ? 'Local Leela' + (engine.status === 'starting' ? ' analyzing <i class="fa spinner" aria-hidden="true">&#xf110;</i>' : '') : ({moves:'Moves',info:'Study information',chart:'',book:'Opening book'})[tab]; }
  function renderPanel() { const preserveEngine = tab === 'engine' ? { scrollTop: panel.scrollTop, focusedPv: document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.pv : null } : null; const info = '<div class="empty"><b>Turn:</b> ' + (chess.turn() === 'w' ? 'White' : 'Black') + '<br><b>FEN:</b> ' + safe(chess.fen()) + '<br><b>Leela:</b> ' + safe(engine.status) + '<br>Paired PC credentials remain in the native shell.</div>'; document.querySelector('.game-title small').textContent = (cursor.san || 'Starting position') + ' · ' + (engine.status === 'disconnected' ? 'PC disconnected' : chess.turn() === 'w' ? 'White to move' : 'Black to move'); panel.innerHTML = tab === 'engine' ? enginePanel() : tab === 'moves' ? movesPanel() : tab === 'book' ? bookPanel() : tab === 'info' ? info : '<div class="chart-blank" aria-label="Chart intentionally empty"></div>'; heading(); panel.querySelectorAll('[data-node]').forEach(button => button.onclick = () => restore(findNode(Number(button.dataset.node)))); panel.querySelectorAll('[data-uci]').forEach(button => button.onclick = () => playUci(button.dataset.uci)); panel.querySelectorAll('[data-pv]').forEach(button => button.onclick = () => { engine.selectedPv = Number(button.dataset.pv); renderArrows(engineArrowLines()); renderPanel(); }); if (preserveEngine) { panel.scrollTop = preserveEngine.scrollTop; if (preserveEngine.focusedPv != null) { const focused = panel.querySelector('[data-pv="' + preserveEngine.focusedPv + '"]'); if (focused) { focused.focus({ preventScroll:true }); panel.scrollTop = preserveEngine.scrollTop; } } } const prev = document.querySelector('[data-action="prev"]'), next = document.querySelector('[data-action="next"]'); prev.disabled = !cursor.parent; next.disabled = !(cursor.selectedChild || cursor.children[0]); }
  function findNode(id, node) { node = node || root; if (node.id === id) return node; for (const child of node.children) { const found = findNode(id, child); if (found) return found; } return null; }
  function restore(next) { if (!next) return; cursor = next; if (cursor.parent) cursor.parent.selectedChild = cursor; restoring = true; chess.load(cursor.fen); sync(null); restoring = false; onPositionChanged(); }
  function playUci(uci) { if (!uci || uci.length < 4) return; commitMove(uci.slice(0,2), uci.slice(2,4), uci.length > 4 ? uci[4] : undefined); }
  function flipBoard() { ground.toggleOrientation(); wrap.classList.toggle('orientation-white'); wrap.classList.toggle('orientation-black'); renderArrows(engineArrowLines()); }
  function squarePoint(square) { const f = square.charCodeAt(0) - 97, r = Number(square[1]) - 1, white = !wrap.classList.contains('orientation-black'); return [(white ? f : 7 - f) * 64 + 32, (white ? 7 - r : r) * 64 + 32]; }
  function engineArrowLines() { return engine.selectedPv != null && engine.lines[engine.selectedPv] ? [engine.lines[engine.selectedPv]] : engine.lines; }
  function renderArrows(lines) { const defs = arrows.querySelector('defs').outerHTML; if (!settings.showArrows) { arrows.innerHTML = defs; return; } const strokes = lines.slice(0, 3).map((line, index) => { const pv = line.pv || []; const move = Array.isArray(pv) ? pv[0] : String(pv).trim().split(/\s+/)[0]; if (!/^[a-h][1-8][a-h][1-8]/.test(move || '')) return ''; const a = squarePoint(move.slice(0,2)), b = squarePoint(move.slice(2,4)), kind = index ? 'blue' : 'green'; return '<line class="analysis-arrow ' + kind + '" x1="' + a[0] + '" y1="' + a[1] + '" x2="' + b[0] + '" y2="' + b[1] + '" marker-end="url(#arrow-' + kind + ')"/>'; }).join(''); arrows.innerHTML = defs + strokes; }
  function showOverlay(kind) { if (overlay) overlay.remove(); overlay = document.createElement('div'); overlay.className = 'study-overlay'; if (kind === 'settings') overlay.innerHTML = '<div class="overlay-card"><button data-close class="overlay-close">×</button><b>Leela · CPU</b><div data-connection>PC status unavailable</div><label>Nodes <input data-nodes type="number" min="100" max="100000" value="' + settings.nodes + '"></label><label>Lines <input data-multipv type="number" min="1" max="8" value="' + settings.multipv + '"></label><label>Leela <input data-enabled type="checkbox"' + (settings.enabled ? ' checked' : '') + '></label><label>Arrows <input data-arrows type="checkbox"' + (settings.showArrows ? ' checked' : '') + '></label><label>Pair code <input data-code inputmode="text"></label><button data-apply>Apply</button><button data-pair>Pair PC</button></div>'; else overlay.innerHTML = '<div class="overlay-card"><button data-close class="overlay-close">×</button><b>Study</b><button data-start>Start position</button><button data-flip>Flip board</button></div>'; overlay.querySelector('[data-close]').onclick = () => { overlay.remove(); overlay = null; }; if (kind === 'settings') { try { const state = JSON.parse(native().getConnectionState() || '{}'); overlay.querySelector('[data-connection]').textContent = state.deviceName || state.state || state.error || 'PC not paired'; } catch (_) {} overlay.querySelector('[data-apply]').onclick = () => { settings.nodes = Math.min(100000, Math.max(100, Number(overlay.querySelector('[data-nodes]').value) || 1000)); settings.multipv = Math.min(8, Math.max(1, Number(overlay.querySelector('[data-multipv]').value) || 5)); settings.enabled = overlay.querySelector('[data-enabled]').checked; settings.showArrows = overlay.querySelector('[data-arrows]').checked; saveUiSettings(); overlay.remove(); overlay = null; if (settings.enabled) scheduleAnalysis(); else { clearEngine('off'); renderPanel(); } }; overlay.querySelector('[data-pair]').onclick = () => { const code = overlay.querySelector('[data-code]').value.trim(); if (code && native()) native().pair(code, 'InstinctaZero Android'); }; } else { overlay.querySelector('[data-start]').onclick = () => restore(root); overlay.querySelector('[data-flip]').onclick = flipBoard; } document.body.appendChild(overlay); }
  function showAppearance() { if (overlay) overlay.remove(); overlay = document.createElement('div'); overlay.className = 'study-overlay'; const orientation = wrap.classList.contains('orientation-black') ? 'Black' : 'White'; const size = wrap.classList.contains('expanded') ? 'Full' : 'Compact'; overlay.innerHTML = '<div class="overlay-card"><button data-close class="overlay-close">×</button><b>Appearance</b><div>Orientation: <span data-orientation>' + orientation + '</span></div><div>Board: <span data-size-label>' + size + '</span></div><button data-flip>Flip board</button><button data-size>' + (size === 'Full' ? 'Compact board' : 'Full board') + '</button></div>'; overlay.querySelector('[data-close]').onclick = () => { overlay.remove(); overlay = null; }; overlay.querySelector('[data-flip]').onclick = () => { flipBoard(); overlay.querySelector('[data-orientation]').textContent = wrap.classList.contains('orientation-black') ? 'Black' : 'White'; }; overlay.querySelector('[data-size]').onclick = () => { const expanded = wrap.classList.toggle('expanded'); overlay.querySelector('[data-size]').textContent = expanded ? 'Compact board' : 'Full board'; overlay.querySelector('[data-size-label]').textContent = expanded ? 'Full' : 'Compact'; refreshBoardBounds(); }; document.body.appendChild(overlay); }
  function deleteCurrentBranch() { if (!cursor.parent) return; const parent = cursor.parent; parent.children = parent.children.filter(child => child !== cursor); if (parent.selectedChild === cursor) parent.selectedChild = parent.children[0] || null; restore(parent); }
  function resetStudy() { root.children = []; root.selectedChild = null; nodeId = 0; restore(root); }
  function showMenu() { if (overlay) overlay.remove(); overlay = document.createElement('div'); overlay.className = 'study-overlay'; overlay.innerHTML = '<div class="overlay-card"><button data-close class="overlay-close">×</button><b>Study</b><button data-reset>New / reset</button><button data-delete' + (cursor.parent ? '' : ' disabled') + '>Delete current branch</button></div>'; overlay.querySelector('[data-close]').onclick = () => { overlay.remove(); overlay = null; }; overlay.querySelector('[data-reset]').onclick = () => { overlay.remove(); overlay = null; resetStudy(); }; const remove = overlay.querySelector('[data-delete]'); if (!remove.disabled) remove.onclick = () => { overlay.innerHTML = '<div class="overlay-card"><b>Delete this branch?</b><button data-confirm-delete>Delete</button><button data-cancel-delete>Keep</button></div>'; overlay.querySelector('[data-confirm-delete]').onclick = () => { overlay.remove(); overlay = null; deleteCurrentBranch(); }; overlay.querySelector('[data-cancel-delete]').onclick = () => showMenu(); }; document.body.appendChild(overlay); }

  window.InstinctaZero = window.InstinctaZero || {};
  window.InstinctaZero.onNativeAnalysis = function (id, payloadJson) { if (id !== engine.requestId) return; let payload; try { payload = typeof payloadJson === 'string' ? JSON.parse(payloadJson) : payloadJson; } catch (_) { return; } const data = payload.data || payload; if (payload.event === 'engine-error' || payload.event === 'error' || data.error) { engine.error = data.error || payload.message || 'Engine unavailable'; engine.status = 'error'; renderPanel(); return; } if (payload.event === 'done') { engine.status = 'done'; renderPanel(); return; } if (data.lines) { engine.lastGood = data; engine.lines = data.lines.slice(0, settings.multipv); if (engine.selectedPv != null && !engine.lines[engine.selectedPv]) engine.selectedPv = null; engine.stats = data; engine.progress = data.progress || null; engine.status = 'running'; renderArrows(engineArrowLines()); renderPanel(); } else if (data.progress) { engine.progress = data.progress; renderPanel(); } };
  window.InstinctaZero.onNativeExplorer = function (id, payloadJson) {
    if (id !== book.requestId) return;
    try {
      const payload = typeof payloadJson === 'string' ? JSON.parse(payloadJson) : payloadJson;
      if (payload && payload.event === 'error') {
        book.data = null;
        book.error = payload.message || 'Opening book unavailable';
      } else {
        book.data = payload;
        book.error = '';
      }
    } catch (_) {
      book.data = null;
      book.error = 'Opening book unavailable';
    }
    book.loading = false;
    renderPanel();
  };
  window.InstinctaZero.onNativeConnectionState = function (payloadJson) { let state = {}; try { state = typeof payloadJson === 'string' ? JSON.parse(payloadJson) : payloadJson || {}; } catch (_) {} if (overlay) { const status = overlay.querySelector('[data-connection]'); if (status) status.textContent = state.error || state.deviceName || state.state || (state.paired ? 'Paired' : 'PC not paired'); } if (settings.enabled) scheduleAnalysis(); if (tab === 'book') requestBook(); };

  function setTab(next) { if (tab === 'book' && next !== 'book') cancelBookRequest(); tab = next; document.querySelectorAll('.tabs button').forEach(item => item.classList.toggle('selected', item.dataset.tab === tab)); renderPanel(); if (tab === 'book') requestBook(); }
  document.querySelectorAll('.tabs button').forEach(button => button.onclick = () => setTab(button.dataset.tab));
  document.addEventListener('keydown', event => { if (event.key === 'Escape' && promotionPicker) promotionPicker.querySelector('[data-cancel]').click(); });
  document.addEventListener('visibilitychange', () => { if (document.hidden) resetTransport(); else if (settings.enabled) scheduleAnalysis(); }); window.addEventListener('pagehide', resetTransport);
  document.querySelector('.back').onclick = () => { if (promotionPicker) { promotionPicker.querySelector('[data-cancel]').click(); return; } if (overlay) { overlay.remove(); overlay = null; return; } if (native() && native().exitStudy) native().exitStudy(); };
  document.querySelector('.board-icon').onclick = showAppearance;
  document.querySelectorAll('.actions button').forEach(button => { const action = button.dataset.action; if (action === 'flip') button.onclick = flipBoard; else if (action === 'size') button.onclick = () => { const expanded = wrap.classList.toggle('expanded'); button.setAttribute('aria-label', expanded ? 'Reduce board' : 'Expand board'); refreshBoardBounds(); }; else if (action === 'prev' || action === 'next') button.onpointerdown = event => { if (button.disabled) return; const go = () => restore(action === 'prev' ? cursor.parent : (cursor.selectedChild || cursor.children[0])); go(); hold = setInterval(go, 240); event.currentTarget.onpointerup = event.currentTarget.onpointerleave = event.currentTarget.onpointercancel = () => clearInterval(hold); }; else button.onclick = () => action === 'settings' ? showOverlay('settings') : showMenu(); });
  loadUiSettings(); renderPanel(); refreshBoardBounds(); scheduleAnalysis();
}());
