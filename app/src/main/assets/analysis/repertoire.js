/* A small view over the native, indexed repertoire store. No corpus or credentials in JS. */
(function () {
  'use strict';
  const escape = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const labels = { alternative:'Alternative', analysis:'Informational', refutation:'Refutation', model_game:'Model game' };
  const icon = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v15M3 4c4-1 6 0 9 2 3-2 5-3 9-2v14c-4-1-6 0-9 2-3-2-5-3-9-2Z"/></svg>';
  window.createRepertoirePanel = function (api) {
    const pending = new Map();
    let catalog = [], catalogReady = false, installed = false, settings = {}, results = [], error = '', busy = false;
    let help = false, adjusting = null, expanded = '', generation = 0, lookupId = null, commentEditor = null, addedTo = '';
    const cache = new Map();
    let cacheBytes = 0, editing = false, addedMessage = '', lastChange = null;
    const native = () => window.InstinctaZeroNative;
    try { settings = JSON.parse(native()?.getRepertoireSettings?.() || '{}') || {}; } catch (_) {}
    const contextKey = () => api.key() || 'analysis';
    // Keep explicit per-game choices (including none); new games inherit the last selection.
    const selected = () => {
      const ids = settings[contextKey()] ?? settings._selected ?? settings.analysis ?? [];
      return Array.isArray(ids) ? ids.filter(id => catalog.some(rep => rep.id === id)) : [];
    };
    const focus = () => selected().includes(settings._focus) ? settings._focus : '';
    const visible = () => results.filter(rep => !focus() || rep.id === focus());
    const markerEnabled = () => settings._bookMarker !== false;
    function save() { native()?.saveRepertoireSettings?.(JSON.stringify(settings)); }
    function updateMarker() {
      const kind = results.some(rep => rep.theory) ? 'theory' : '';
      const active = results.filter(rep => rep.theory);
      api.marker?.(markerEnabled() && api.hasMoves() ? kind : '', active.length > 0 && active.every(rep => rep.end_of_line));
    }
    const cacheKey = (context, ids) => JSON.stringify([context.root.split(' ').slice(0,4).join(' '), [...ids].sort(), context.history]);
    function remember(key, data, complete) {
      if (cache.get(key)?.complete && !complete) return;
      const bytes = JSON.stringify(data).length * 2;
      if (cache.has(key)) { cacheBytes -= cache.get(key).bytes; cache.delete(key); }
      if (bytes > 2 * 1024 * 1024) return;
      cache.set(key, {data, complete, bytes}); cacheBytes += bytes;
      while (cache.size > 96 || cacheBytes > 2 * 1024 * 1024) {
        const oldest = cache.keys().next().value;
        cacheBytes -= cache.get(oldest).bytes; cache.delete(oldest);
      }
    }
    function invalidate() {
      cache.clear(); cacheBytes = 0; ++generation;
      if (lookupId) pending.delete(lookupId);
    }
    function cacheResult(context, ids, data) {
      // Child-position coverage/comments already merge every source move order. Project
      // those facts immediately for board moves and navigation without rewriting the tree.
      const moves = new Set(data.flatMap(rep => (rep.moves || []).map(move => move.uci)));
      for (const uci of moves) {
        const next = {...context, history:[...context.history,uci]};
        const projected = data.map(rep => {
          const child = (rep.moves || []).find(move => move.uci === uci);
          if (child?.position) return {...child.position,id:rep.id,name:rep.name,side:rep.side,moves:[],pending:true};
          return {id:rep.id, name:rep.name, side:rep.side, theory:!!child?.theory,
            alternative:!!child?.alternative, kind:child?.kind || 'unknown',
            deviation:child?.deviation ?? (rep.deviation || next.history.length),
            end_of_line:!!child?.end_of_line,
            starting_comments:child?.starting_comments || [],
            comments:child?.comments || (child?.comment ? [child.comment] : []), moves:[], pending:true};
        });
        remember(cacheKey(next,ids), projected, false);
      }
      remember(cacheKey(context,ids), data, true);
    }
    function request(payload, done, download) {
      if (!native()?.requestRepertoire) { error = 'Repertoire storage is unavailable in this preview.'; busy = false; editing = false; api.render(); return; }
      try { const id = download ? native().downloadRepertoires() : native().requestRepertoire(JSON.stringify(payload)); pending.set(id, done); return id; }
      catch (_) { error = 'Repertoire request could not start.'; busy = false; editing = false; api.render(); }
    }
    window.InstinctaZero = window.InstinctaZero || {};
    window.InstinctaZero.onNativeRepertoire = (id, raw) => {
      const done = pending.get(id); if (!done) return; pending.delete(id);
      try { done(typeof raw === 'string' ? JSON.parse(raw) : raw); }
      catch (_) { error = 'The saved repertoire could not be read.'; busy = false; editing = false; api.render(); }
    };
    function loadCatalog(download = false, openedId = null) {
      busy = download; error = ''; api.render();
      request({action:'catalog'}, data => {
        busy = false;
        if (data.event === 'error') { error = data.message; api.render(); return; }
        catalog = data.repertoires || []; catalogReady = true; installed = data.installed;
        lastChange = data.undo || null;
        invalidate();
        if (openedId && catalog.some(rep => rep.id === openedId)) {
          settings[contextKey()] = [openedId]; settings._selected = [openedId]; settings._focus = openedId; save();
        }
        refresh();
      }, download);
    }
    function refresh() {
      const current = ++generation;
      if (lookupId) pending.delete(lookupId);
      const context = api.context(), ids = selected(), key = cacheKey(context,ids), cached = cache.get(key);
      results = cached?.data || []; adjusting = null; expanded = ''; addedMessage = ''; updateMarker();
      api.render(); // Clear old play/edit targets; known markers appear before the native call.
      if (!ids.length || cached?.complete) {
        if (cached) { cache.delete(key); cache.set(key,cached); }
        return;
      }
      lookupId = request({...context, selected:ids, action:'lookup'}, data => {
        if (current !== generation) return;
        if (data.event === 'error') error = data.message;
        else { error = ''; results = data.results || []; cacheResult(context,ids,results); }
        updateMarker(); api.render();
      });
    }
    const button = (action, text, extra = '') => '<button class="rep-button" data-rep-action="' + action + '" ' + extra + '>' + text + '</button>';
    function badge(move) {
      const text = move.theory ? move.kind === 'alternative' ? 'Alternative' : move.alternative ? 'Optional line' : '' : labels[move.kind] || 'Informational';
      return text ? '<span class="rep-badge ' + (move.theory ? 'optional' : 'info') + '">' + escape(text) + '</span>' : '';
    }
    function status(rep) {
      if (rep.theory) return rep.alternative ? 'In repertoire · optional line' : 'In repertoire';
      if (rep.deviation) {
        const root = api.root().split(' '), firstWhite = root[1] === 'w', white = rep.deviation % 2 ? firstWhite : !firstWhite;
        const number = Number(root[5] || 1) + Math.floor((rep.deviation - 1 + (firstWhite ? 0 : 1)) / 2);
        return (white === (rep.side === 'white') ? 'Your' : 'Opponent’s') + ' deviation · ' + number + (white ? '.' : '…');
      }
      return 'Outside this repertoire';
    }
    function selection() {
      return '<div class="rep-section-label">Compare repertoires</div>' + catalog.map(rep => {
        const on = selected().includes(rep.id);
        return '<button class="rep-card ' + (on ? 'checked' : '') + '" data-rep-select="' + escape(rep.id) + '" role="checkbox" aria-checked="' + on + '"><span class="rep-check" aria-hidden="true">' + (on ? '✓' : '+') + '</span><span><b>' + escape(rep.name) + '</b><small>' + (rep.side === 'white' ? 'White' : 'Black') + '</small></span></button>';
      }).join('') + '<p class="rep-hint">Saved for this board or game. New games start with your latest selection.</p>';
    }
    function helpHtml() {
      return '<div class="rep-guide"><h3>Comments as you go</h3><p>The position’s note appears above the continuations. Tap it to read the full comment, or tap a comment bubble beside a move to read ahead without playing it. Distinct source comments are kept, including notes reached through other move orders.</p><h3>Theory and alternatives</h3><p>The book follows the board position, not your move order. Transpositions automatically show all recorded continuations and position comments; your played game stays unchanged. Main repertoire moves have no extra label. Alternatives are valid choices by your repertoire colour. Opponent replies keep regular labels. Purely informational analysis, refutations and model-game tails remain readable but do not count as theory. A move covered elsewhere at the same position is still a book move.</p><h3>Book markers</h3><p>A book marks a covered position in a selected repertoire, including transpositions. A small finish flag means there are no further active book moves from that position, even if informational moves remain. Known positions show their marker immediately; visited positions are cached. Turn markers off here if you prefer a plain board.</p><h3>Separate or combined</h3><p>Choose one or several repertoires. Combined shows each move once; comments and adjustments retain their source. The focused view only filters the move list; board markers check all selected repertoires.</p><h3>Adjust safely</h3><p>Use the sliders beside a move to make an active own-side choice optional, exclude it, or restore its original label. New adjustments apply to that position and move in the chosen repertoire, regardless of move order. A later transposition can rejoin an independently covered position. To extend a line, play a new move or sequence on the board, then tap <b>Add move</b> or <b>Add line</b> below the continuations. The closest covered repertoire is chosen automatically; a tie still asks. The destination is shown before adding. Only the missing continuation is added. Create and rename your own repertoires in Home → Repertoire library. Use Write / edit comment beside an adjusted move or in position settings. Comments follow the position through transpositions; Restore source text brings back the unchanged PGN notes. Edits save on this phone, not to the PC. Tap <b>Undo</b> after a change, or <b>Undo last repertoire change</b> in settings later—even after restarting. It reverses the whole last edit without moving the analysis board. One step, no redo; changes made before v0.7.3 have no undo record. Source PGNs and archived Lichess games are never rewritten. Purely informational source lines must be reviewed in the PC annotations before becoming active.</p></div>';
    }
    function commentList(entries) {
      const unique = new Map();
      for (const {rep, move} of entries) {
        const comments = Array.isArray(move.comments) ? move.comments : [move.comment];
        for (const [role, notes] of [['Before move', move.starting_comments || []], ['', comments]]) for (const text of notes) if (typeof text === 'string' && text.trim()) {
          const key = JSON.stringify([role,text]);
          if (!unique.has(key)) unique.set(key, {text, role, sources:new Set()});
          unique.get(key).sources.add(rep.name);
        }
      }
      return [...unique.values()].map(note => ({...note,sources:[...note.sources]}));
    }
    function commentsHtml(comments) {
      return comments.map(note => '<div class="rep-note"><small>' + escape([note.role,...note.sources].filter(Boolean).join(' · ')) + '</small><p>' + escape(note.text) + '</p></div>').join('');
    }
    function commentActions(entries) {
      return '<div class="rep-comment-actions">' + entries.map(({rep,move}) => '<button class="rep-button" data-rep-note-edit="' + escape(rep.id + ':' + (move.uci || 'current')) + '"><span class="fa" aria-hidden="true">&#xf040;</span> Edit comment' + (entries.length > 1 ? ' · ' + escape(rep.name) : '') + '</button>').join('') + '</div>';
    }
    function rows() {
      const grouped = new Map();
      for (const rep of visible()) for (const move of rep.moves || []) {
        if (!grouped.has(move.uci)) grouped.set(move.uci, []);
        grouped.get(move.uci).push({rep,move});
      }
      return [...grouped].map(([uci, entries]) => {
        const best = entries.find(e => e.move.theory && !e.move.alternative) || entries.find(e => e.move.theory) || entries[0];
        return {uci, entries, best, comments:commentList(entries)};
      }).sort((a,b) => Number(b.best.move.theory) - Number(a.best.move.theory) || Number(a.best.move.alternative) - Number(b.best.move.alternative));
    }
    function extensionTarget(candidates = visible().filter(rep => rep.can_add)) {
      if (candidates.length === 1) return candidates[0];
      // Closest covered prefix, not catalog order or the last arbitrary choice. A tie
      // is genuinely ambiguous (e.g. two Sicilian repertoires) and keeps the chooser.
      const nearest = Math.max(0,...candidates.map(rep => Number(rep.deviation) || 0));
      const best = candidates.filter(rep => (Number(rep.deviation) || 0) === nearest);
      return best.length === 1 ? best[0] : null;
    }
    function editComment(rep, position = rep) {
      api.settings(); adjusting = null;
      const text = (position.comments || []).join('\n\n');
      commentEditor = {id:rep.id,name:rep.name,fen:position.fen || api.context().fen,text,original:text,edited:!!position.comment_edited};
      api.render();
    }
    function commentForm() {
      const e = commentEditor;
      return '<div class="rep-comment-editor"><div class="rep-adjust-header"><h3>Comment<small>' + escape(e.name) + '</small></h3><div class="rep-comment-toolbar">' + button('cancel-comment','Cancel') + button('save-comment','Save','aria-label="Save comment"' + (editing ? ' disabled' : '')) + '</div></div>' +
        '<textarea data-rep-comment-input aria-label="Repertoire comment" placeholder="Write a comment…" maxlength="65536" spellcheck="true">' + escape(e.text) + '</textarea>' +
        (e.edited ? '<div class="rep-comment-actions">' + button('restore-comment','Restore source text',editing ? 'disabled' : '') + '</div>' : '') +
        '<p class="rep-hint">Saved on this phone. Follows this position through transpositions; original PGN text stays intact.</p></div>';
    }
    function notices(showUndo = true) {
      return (error ? '<p class="rep-error" role="alert">' + escape(error) + '</p>' : '') + (busy ? '<p class="rep-hint" role="status">Getting the PC copy… Saved moves stay available.</p>' : '') + (editing ? '<p class="rep-hint" role="status">Saving repertoire change…</p>' : addedMessage ? '<div class="rep-change-notice" role="status"><span>' + escape(addedMessage) + '</span>' + (addedTo ? button('added-comment','Comment','aria-label="Comment on added line"' + (results.some(rep => rep.id === addedTo) ? '' : ' disabled')) : '') + (showUndo && lastChange ? button('undo','Undo','aria-label="Undo last repertoire change" data-rep-undo-token="' + escape(lastChange.token) + '"') : '') + '</div>' : '');
    }
    function adjustment() {
      const {rep, move} = adjusting;
      return '<div class="rep-adjust-header"><h3>' + escape(move.san) + '<small>' + escape(rep.name) + '</small></h3>' + button('cancel','Done') + '</div><div class="rep-button-stack">' +
        button('move-comment','<span class="fa" aria-hidden="true">&#xf040;</span> &nbsp; Write / edit comment') +
        (move.theory && move.own ? button('alternative','☆ &nbsp; Make optional alternative') : '') + button('analysis','⊖ &nbsp; Exclude branch on this phone') +
        (move.edited ? button('reset','↶ &nbsp; Restore original / remove local addition') : '') + '</div><p class="rep-hint">' + escape(move.reason) + '</p>';
    }
    function settingsHtml() {
      let content = notices(false);
      if (commentEditor) return '<div class="repertoire-panel rep-editing-comment">' + content + commentForm() + '</div>';
      if (adjusting) return '<div class="repertoire-panel">' + content + adjustment() + '</div>';
      content += '<button class="touch-switch' + (markerEnabled() ? ' on' : '') + '" data-rep-action="marker" role="switch" aria-checked="' + markerEnabled() + '"><span>Book marker on board</span><i aria-hidden="true"></i></button>';
      if (lastChange) content += '<button class="rep-undo-setting" data-rep-action="undo" data-rep-undo-token="' + escape(lastChange.token) + '" aria-label="Undo last repertoire change"' + (editing ? ' disabled' : '') + '><span class="fa" aria-hidden="true">&#xf0e2;</span><span>Undo last repertoire change<small>' + escape(lastChange.label + ' · ' + lastChange.name) + '</small></span></button>';
      if (installed) {
        content += selection();
        const commentable = visible().filter(rep => rep.known || rep.theory);
        if (commentable.length) content += '<div class="rep-section-label">Position comments</div><div class="rep-button-stack">' + commentable.map(rep => button('position-comment','<span class="fa" aria-hidden="true">&#xf040;</span> &nbsp; ' + escape(rep.name),'data-rep-id="' + escape(rep.id) + '"')).join('') + '</div>';
        if (selected().length > 1) content += '<div class="rep-section-label">Move list</div><div class="rep-filters"><button data-rep-focus="" class="' + (!focus() ? 'selected' : '') + '">Combined</button>' + catalog.filter(rep => selected().includes(rep.id)).map(rep => '<button data-rep-focus="' + escape(rep.id) + '" class="' + (focus() === rep.id ? 'selected' : '') + '">' + escape(rep.name) + '</button>').join('') + '</div>';
        const extendable = results.filter(rep => rep.can_add);
        if (extendable.length) content += '<div class="rep-section-label">Extend a repertoire</div><div class="rep-button-stack">' + extendable.map(rep => button('add','+ Add current line · ' + escape(rep.name),'data-rep-id="' + escape(rep.id) + '"' + (editing ? ' disabled' : ''))).join('') + '</div>';
      }
      if(native()?.openRepertoireLibrary) content += '<div class="rep-footer">' + button('manage-library','<span class="fa" aria-hidden="true">&#xf02d;</span> &nbsp; Create / manage repertoires') + '</div>';
      content += '<div class="rep-footer">' + button('download',busy ? 'Downloading…' : installed ? '↻ Update from PC' : 'Download from PC',busy ? 'disabled' : '') + button('help',help ? 'Hide guide' : 'How to use') + '</div>';
      if (help) content += helpHtml();
      return '<div class="repertoire-panel rep-settings">' + content + '</div>';
    }
    function html() {
      if (!catalogReady && !error) return '<div class="repertoire-panel"></div>';
      let content = notices();
      if (!installed || !selected().length) return '<div class="repertoire-panel">' + content + '<p class="rep-hint">' + (installed ? 'No repertoire selected.' : 'Download your repertoires once to use them offline.') + '</p>' + button('choose',installed ? 'Choose repertoires' : 'Set up repertoires') + '</div>';
      const ended = visible().filter(rep => rep.end_of_line);
      if (ended.length) content += '<div class="rep-end" role="status"><span class="fa" aria-hidden="true">&#xf11e;</span><span>End of line' + (ended.length < visible().length ? '<small>' + escape(ended.map(rep => rep.name).join(' · ')) + '</small>' : '') + '</span></div>';
      const currentComments = commentList(visible().map(rep => ({rep,move:rep})));
      if (currentComments.length) content += '<button class="rep-current-note" data-rep-comment="current" aria-expanded="' + (expanded === 'current') + '" aria-label="' + (api.hasMoves() ? 'Comment on played move' : 'Comment on position') + '"><span class="fa" aria-hidden="true">&#xf075;</span><span>' + escape(currentComments[0].text) + '</span><span aria-hidden="true">' + (expanded === 'current' ? '−' : '+') + '</span></button>' + (expanded === 'current' ? '<div class="rep-comments">' + commentsHtml(currentComments) + commentActions(visible().filter(rep => rep.known || rep.theory).map(rep => ({rep,move:rep}))) + '</div>' : '');
      const moves = rows();
      for (const row of moves) {
        content += '<div class="rep-move"><button data-rep-play="' + escape(row.uci) + '"><b>' + escape(row.best.move.san) + '</b>' + badge(row.best.move) + '</button>' +
          (row.comments.length ? '<button class="rep-comment-toggle fa" data-rep-comment="' + escape(row.uci) + '" aria-label="Comments on ' + escape(row.best.move.san) + '" aria-expanded="' + (expanded === row.uci) + '">&#xf075;</button>' : '') +
          '<button class="rep-adjust fa" data-rep-edit="' + escape(row.uci) + '" aria-label="Adjust ' + escape(row.best.move.san) + '">&#xf1de;</button></div>';
        if (expanded === row.uci) content += '<div class="rep-comments">' + commentsHtml(row.comments) + commentActions(row.entries) + '</div>';
        if (expanded === 'edit:' + row.uci) content += '<div class="rep-edit-sources">' + row.entries.map(({rep,move}) => '<button class="rep-button" data-rep-source="' + escape(rep.id + ':' + move.uci) + '">' + escape(rep.name) + ' ' + badge(move) + '</button>').join('') + '</div>';
      }
      const extendable = visible().filter(rep => rep.can_add);
      const automatic = extensionTarget(extendable);
      if (extendable.length) content += '<div class="rep-extend">' + button('quick-add',(automatic ? automatic.add_count === 1 : extendable.every(rep => rep.add_count === 1)) ? '+ Add move to repertoire' : '+ Add line to repertoire',editing ? 'disabled' : '') + (automatic ? '<small class="rep-add-target">' + escape(automatic.name) + '</small>' : '') + (expanded === 'add' ? '<div class="rep-edit-sources">' + extendable.map(rep => button('add',escape(rep.name),'data-rep-id="' + escape(rep.id) + '"' + (editing ? ' disabled' : ''))).join('') + '</div>' : '') + '</div>';
      if (!moves.length && !extendable.length) content += '<p class="rep-hint">' + (ended.length ? 'Play on the board to extend this line.' : !results.length || visible().some(rep => rep.pending) ? 'Checking the position…' : 'No recorded continuation.') + '</p>';
      return '<div class="repertoire-panel rep-lines">' + content + '</div>';
    }
    function saveEdit(id, kind, history, extra = {}) {
      if (editing) return;
      const context = api.context(), name = catalog.find(rep => rep.id === id)?.name || 'repertoire';
      const isCurrent = () => cacheKey(context,[]) === cacheKey(api.context(),[]);
      editing = true; error = ''; addedMessage = ''; addedTo = ''; invalidate(); api.render();
      request({...context, id, history, action:'edit', kind, ...extra}, data => {
        editing = false; invalidate();
        if ('undo' in data) lastChange = data.undo;
        if (data.event === 'error') { error = data.message; api.render(); }
        else { error = ''; commentEditor = null; refresh(); if (kind === 'comment' || kind === 'reset_comment') api.closeSettings(); if (isCurrent()) { addedMessage = kind === 'add' ? 'Added to ' + name : (lastChange?.label || 'Updated repertoire') + ' · ' + name; addedTo = kind === 'add' ? id : ''; api.render(); } }
      });
    }
    function undoLastChange(token) {
      if (editing || !lastChange || token !== lastChange.token) return;
      const name = lastChange.name;
      editing = true; error = ''; addedMessage = ''; addedTo = ''; invalidate(); api.render();
      request({action:'undo',token}, data => {
        editing = false; invalidate();
        if ('undo' in data) lastChange = data.undo;
        if (data.event === 'error') { error = data.message; api.render(); }
        else { lastChange = null; error = ''; refresh(); addedMessage = 'Change undone · ' + name; api.render(); }
      });
    }
    function editMove(rep, move) { api.settings(); adjusting = {rep,move}; api.render(); }
    function bind(panel) {
      const input = panel.querySelector?.('[data-rep-comment-input]');
      if (input) input.oninput = () => { if(commentEditor) commentEditor.text=input.value; };
      panel.querySelectorAll('[data-rep-select]').forEach(el => el.onclick = () => {
        const ids = selected(), id = el.dataset.repSelect;
        if (!ids.includes(id) && ids.length >= 16) { error = 'Compare up to 16 repertoires at a time.'; api.render(); return; }
        settings[contextKey()] = ids.includes(id) ? ids.filter(x => x !== id) : [...ids,id];
        settings._selected = [...settings[contextKey()]]; settings._focus = ''; save(); refresh();
      });
      panel.querySelectorAll('[data-rep-focus]').forEach(el => el.onclick = () => { settings._focus = el.dataset.repFocus; save(); api.render(); });
      panel.querySelectorAll('[data-rep-play]').forEach(el => el.onclick = () => api.play(el.dataset.repPlay));
      panel.querySelectorAll('[data-rep-comment]').forEach(el => el.onclick = () => { expanded = expanded === el.dataset.repComment ? '' : el.dataset.repComment; api.render(); });
      panel.querySelectorAll('[data-rep-note-edit]').forEach(el => el.onclick = () => {
        const [id,uci] = el.dataset.repNoteEdit.split(':'), rep = results.find(rep => rep.id === id);
        if(!rep) return; const move = uci === 'current' ? rep : rep.moves.find(move => move.uci === uci);
        if(move) editComment(rep,move.position || move);
      });
      panel.querySelectorAll('[data-rep-edit]').forEach(el => el.onclick = () => {
        const row = rows().find(row => row.uci === el.dataset.repEdit); if (!row) return;
        if (row.entries.length === 1) editMove(row.entries[0].rep,row.entries[0].move);
        else { expanded = expanded === 'edit:' + row.uci ? '' : 'edit:' + row.uci; api.render(); }
      });
      panel.querySelectorAll('[data-rep-source]').forEach(el => el.onclick = () => { const [id,uci] = el.dataset.repSource.split(':'); const rep = results.find(r => r.id === id); if (rep) editMove(rep,rep.moves.find(move => move.uci === uci)); });
      panel.querySelectorAll('[data-rep-action]').forEach(el => el.onclick = () => {
        const action = el.dataset.repAction;
        if(action === 'manage-library') { native()?.openRepertoireLibrary?.(); return; }
        if (action === 'position-comment' || action === 'added-comment') {
          const rep = results.find(rep => rep.id === (action === 'added-comment' ? addedTo : el.dataset.repId));
          if(rep) editComment(rep); return;
        }
        if (action === 'move-comment') { editComment(adjusting.rep,adjusting.move.position || adjusting.move); return; }
        if (action === 'cancel-comment') { commentEditor = null; api.closeSettings(); return; }
        if (action === 'save-comment' || action === 'restore-comment') {
          const e = commentEditor; if(!e || editing) return;
          input?.blur();
          if(action === 'save-comment' && e.text === e.original) { commentEditor = null; api.closeSettings(); return; }
          saveEdit(e.id,action === 'save-comment' ? 'comment' : 'reset_comment',[],{fen:e.fen,comment:e.text,entries:[]}); return;
        }
        if (action === 'choose') { api.settings(); return; }
        if (action === 'undo') { undoLastChange(el.dataset.repUndoToken); return; }
        if (action === 'marker') { settings._bookMarker = !markerEnabled(); save(); updateMarker(); }
        else if (action === 'help') help = !help;
        else if (action === 'cancel') { adjusting = null; api.closeSettings(); return; }
        else if (action === 'download') { loadCatalog(true); return; }
        else if (action === 'quick-add') {
          const target = extensionTarget();
          if (target) saveEdit(target.id,'add',api.context().history);
          else { expanded = expanded === 'add' ? '' : 'add'; api.render(); }
          return;
        }
        else {
          const context = api.context(), id = action === 'add' ? el.dataset.repId : adjusting.rep.id;
          const history = action === 'add' ? context.history : [...context.history, adjusting.move.uci];
          saveEdit(id,action,history);
          return;
        }
        api.render();
      });
    }
    function summary() { if (!results.length) return ''; if (results.length === 1) return status(results[0]); const count = results.filter(r => r.theory).length; return count ? count + '/' + results.length + ' repertoires' : 'Outside repertoires'; }
    setTimeout(() => loadCatalog(), 0);
    return {html, settingsHtml, bind, refresh, summary, updateMarker,
      hasEditorFocus:() => !!commentEditor && !!document.activeElement?.matches?.('[data-rep-comment-input]'),
      beginSettings:() => { adjusting = null; commentEditor = null; help = false; },
      openLibrary:id => { api.tab(); if(id) loadCatalog(false,id); }, isLibrary:() => false, back:() => false};
  };
  window.repertoireBookIcon = icon;
}());
