/* A small view over the native, indexed repertoire store. No corpus or credentials in JS. */
(function () {
  'use strict';
  const escape = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const labels = { alternative:'Alternative', analysis:'Informational', refutation:'Refutation', model_game:'Model game' };
  const icon = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v15M3 4c4-1 6 0 9 2 3-2 5-3 9-2v14c-4-1-6 0-9 2-3-2-5-3-9-2Z"/></svg>';
  window.createRepertoirePanel = function (api) {
    const pending = new Map();
    let catalog = [], catalogReady = false, installed = false, settings = {}, results = [], error = '', busy = false;
    let help = false, adjusting = null, expanded = '', generation = 0, lookupId = null;
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
      const kind = results.some(rep => rep.theory) ? 'theory' : results.some(rep => rep.position_match || rep.candidates > 0) ? 'transposition' : '';
      api.marker?.(markerEnabled() && api.hasMoves() ? kind : '');
    }
    function request(payload, done, download) {
      if (!native()?.requestRepertoire) { error = 'Repertoire storage is unavailable in this preview.'; busy = false; api.render(); return; }
      try { const id = download ? native().downloadRepertoires() : native().requestRepertoire(JSON.stringify(payload)); pending.set(id, done); return id; }
      catch (_) { error = 'Repertoire request could not start.'; busy = false; api.render(); }
    }
    window.InstinctaZero = window.InstinctaZero || {};
    window.InstinctaZero.onNativeRepertoire = (id, raw) => {
      const done = pending.get(id); if (!done) return; pending.delete(id);
      try { done(typeof raw === 'string' ? JSON.parse(raw) : raw); }
      catch (_) { error = 'The saved repertoire could not be read.'; busy = false; api.render(); }
    };
    function loadCatalog(download = false) {
      busy = download; error = ''; api.render();
      request({action:'catalog'}, data => {
        busy = false;
        if (data.event === 'error') { error = data.message; api.render(); return; }
        catalog = data.repertoires || []; catalogReady = true; installed = data.installed;
        refresh();
      }, download);
    }
    function refresh() {
      const current = ++generation;
      if (lookupId) pending.delete(lookupId);
      results = []; adjusting = null; expanded = ''; updateMarker();
      api.render(); // Never leave old-position play/edit targets or a stale book marker active.
      if (!selected().length) return;
      lookupId = request({...api.context(), selected:selected(), action:'lookup'}, data => {
        if (current !== generation) return;
        if (data.event === 'error') error = data.message;
        else { error = ''; results = data.results || []; }
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
      return '<div class="rep-guide"><h3>Comments as you go</h3><p>The played move’s note appears above the continuations. Tap it to read the full comment, or tap a comment bubble beside a move to read ahead without playing it. Distinct source comments are kept, even when a move appears in several files.</p><h3>Theory and alternatives</h3><p>Main repertoire moves have no extra label. Alternatives are valid choices by your repertoire colour; opponent replies retain their optional-line context. Informational analysis, refutations and model-game tails remain readable but do not count as theory.</p><h3>Book markers</h3><p>A filled book marks a move in a selected repertoire. An outlined book means the position matches by transposition, but the full history has already left recorded theory. This never silently reactivates an excluded line. Turn markers off here if you prefer a plain board.</p><h3>Separate or combined</h3><p>Choose one or several repertoires. Combined shows each move once; comments and adjustments retain their source. The focused view only filters the move list; board markers check all selected repertoires.</p><h3>Adjust safely</h3><p>Use the sliders beside a move to make an active own-side choice optional, exclude a branch, or restore its original label. Add current line extends the repertoire you choose. Edits save on this phone, not to the PC. Source PGNs and archived Lichess games are never rewritten. Informational source lines must be reviewed in the PC annotations before becoming active.</p></div>';
    }
    function commentList(entries) {
      const unique = new Map();
      for (const {rep, move} of entries) {
        const comments = Array.isArray(move.comments) ? move.comments : [move.comment];
        for (const text of comments) if (typeof text === 'string' && text.trim()) {
          if (!unique.has(text)) unique.set(text, new Set());
          unique.get(text).add(rep.name);
        }
      }
      return [...unique].map(([text, sources]) => ({text, sources:[...sources]}));
    }
    function commentsHtml(comments) {
      return comments.map(note => '<div class="rep-note"><small>' + escape(note.sources.join(' · ')) + '</small><p>' + escape(note.text) + '</p></div>').join('');
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
    function notices() {
      return (error ? '<p class="rep-error" role="alert">' + escape(error) + '</p>' : '') + (busy ? '<p class="rep-hint" role="status">Getting the PC copy… Saved moves stay available.</p>' : '');
    }
    function adjustment() {
      const {rep, move} = adjusting;
      return '<div class="rep-adjust-header"><h3>' + escape(move.san) + '<small>' + escape(rep.name) + '</small></h3>' + button('cancel','Done') + '</div><div class="rep-button-stack">' +
        (move.theory && move.own ? button('alternative','☆ &nbsp; Make optional alternative') : '') + button('analysis','⊖ &nbsp; Exclude branch on this phone') +
        (move.edited ? button('reset','↶ &nbsp; Restore original / remove local addition') : '') + '</div><p class="rep-hint">' + escape(move.reason) + '</p>';
    }
    function settingsHtml() {
      let content = notices();
      if (adjusting) return '<div class="repertoire-panel">' + content + adjustment() + '</div>';
      content += '<button class="touch-switch' + (markerEnabled() ? ' on' : '') + '" data-rep-action="marker" role="switch" aria-checked="' + markerEnabled() + '"><span>Book marker on board</span><i aria-hidden="true"></i></button>';
      if (installed) {
        content += selection();
        if (selected().length > 1) content += '<div class="rep-section-label">Move list</div><div class="rep-filters"><button data-rep-focus="" class="' + (!focus() ? 'selected' : '') + '">Combined</button>' + catalog.filter(rep => selected().includes(rep.id)).map(rep => '<button data-rep-focus="' + escape(rep.id) + '" class="' + (focus() === rep.id ? 'selected' : '') + '">' + escape(rep.name) + '</button>').join('') + '</div>';
        if (api.hasMoves()) content += '<div class="rep-section-label">Extend a repertoire</div><div class="rep-button-stack">' + catalog.filter(rep => selected().includes(rep.id)).map(rep => button('add','+ Add current line · ' + escape(rep.name),'data-rep-id="' + escape(rep.id) + '"')).join('') + '</div>';
      }
      content += '<div class="rep-footer">' + button('download',busy ? 'Downloading…' : installed ? '↻ Update from PC' : 'Download from PC',busy ? 'disabled' : '') + button('help',help ? 'Hide guide' : 'How to use') + '</div>';
      if (help) content += helpHtml();
      return '<div class="repertoire-panel rep-settings">' + content + '</div>';
    }
    function html() {
      if (!catalogReady && !error) return '<div class="repertoire-panel"></div>';
      let content = notices();
      if (!installed || !selected().length) return '<div class="repertoire-panel">' + content + '<p class="rep-hint">' + (installed ? 'No repertoire selected.' : 'Download your repertoires once to use them offline.') + '</p>' + button('choose',installed ? 'Choose repertoires' : 'Set up repertoires') + '</div>';
      const currentComments = commentList(visible().map(rep => ({rep,move:rep})));
      if (currentComments.length) content += '<button class="rep-current-note" data-rep-comment="current" aria-expanded="' + (expanded === 'current') + '" aria-label="' + (api.hasMoves() ? 'Comment on played move' : 'Comment on position') + '"><span class="fa" aria-hidden="true">&#xf075;</span><span>' + escape(currentComments[0].text) + '</span><span aria-hidden="true">' + (expanded === 'current' ? '−' : '+') + '</span></button>' + (expanded === 'current' ? '<div class="rep-comments">' + commentsHtml(currentComments) + '</div>' : '');
      const moves = rows();
      for (const row of moves) {
        content += '<div class="rep-move"><button data-rep-play="' + escape(row.uci) + '"><b>' + escape(row.best.move.san) + '</b>' + badge(row.best.move) + '</button>' +
          (row.comments.length ? '<button class="rep-comment-toggle fa" data-rep-comment="' + escape(row.uci) + '" aria-label="Comments on ' + escape(row.best.move.san) + '" aria-expanded="' + (expanded === row.uci) + '">&#xf075;</button>' : '') +
          '<button class="rep-adjust fa" data-rep-edit="' + escape(row.uci) + '" aria-label="Adjust ' + escape(row.best.move.san) + '">&#xf1de;</button></div>';
        if (expanded === row.uci) content += '<div class="rep-comments">' + commentsHtml(row.comments) + '</div>';
        if (expanded === 'edit:' + row.uci) content += '<div class="rep-edit-sources">' + row.entries.map(({rep,move}) => '<button class="rep-button" data-rep-source="' + escape(rep.id + ':' + move.uci) + '">' + escape(rep.name) + ' ' + badge(move) + '</button>').join('') + '</div>';
      }
      if (!moves.length) content += '<p class="rep-hint">' + (!results.length ? 'Checking the line…' : visible().some(rep => rep.candidates) ? 'Known position · different move order. No continuation for this history.' : 'No recorded continuation.') + '</p>';
      return '<div class="repertoire-panel rep-lines">' + content + '</div>';
    }
    function editMove(rep, move) { api.settings(); adjusting = {rep,move}; api.render(); }
    function bind(panel) {
      panel.querySelectorAll('[data-rep-select]').forEach(el => el.onclick = () => {
        const ids = selected(), id = el.dataset.repSelect;
        settings[contextKey()] = ids.includes(id) ? ids.filter(x => x !== id) : [...ids,id];
        settings._selected = [...settings[contextKey()]]; settings._focus = ''; save(); refresh();
      });
      panel.querySelectorAll('[data-rep-focus]').forEach(el => el.onclick = () => { settings._focus = el.dataset.repFocus; save(); api.render(); });
      panel.querySelectorAll('[data-rep-play]').forEach(el => el.onclick = () => api.play(el.dataset.repPlay));
      panel.querySelectorAll('[data-rep-comment]').forEach(el => el.onclick = () => { expanded = expanded === el.dataset.repComment ? '' : el.dataset.repComment; api.render(); });
      panel.querySelectorAll('[data-rep-edit]').forEach(el => el.onclick = () => {
        const row = rows().find(row => row.uci === el.dataset.repEdit); if (!row) return;
        if (row.entries.length === 1) editMove(row.entries[0].rep,row.entries[0].move);
        else { expanded = expanded === 'edit:' + row.uci ? '' : 'edit:' + row.uci; api.render(); }
      });
      panel.querySelectorAll('[data-rep-source]').forEach(el => el.onclick = () => { const [id,uci] = el.dataset.repSource.split(':'); const rep = results.find(r => r.id === id); if (rep) editMove(rep,rep.moves.find(move => move.uci === uci)); });
      panel.querySelectorAll('[data-rep-action]').forEach(el => el.onclick = () => {
        const action = el.dataset.repAction;
        if (action === 'choose') { api.settings(); return; }
        if (action === 'marker') { settings._bookMarker = !markerEnabled(); save(); updateMarker(); }
        else if (action === 'help') help = !help;
        else if (action === 'cancel') { adjusting = null; api.closeSettings(); return; }
        else if (action === 'download') { loadCatalog(true); return; }
        else {
          const context = api.context(), id = action === 'add' ? el.dataset.repId : adjusting.rep.id;
          const history = action === 'add' ? context.history : [...context.history, adjusting.move.uci];
          request({...context, id, history, action:'edit', kind:action}, data => { if (data.event === 'error') { error = data.message; api.render(); } else { error = ''; refresh(); } });
          return;
        }
        api.render();
      });
    }
    function summary() { if (!results.length) return ''; if (results.length === 1) return status(results[0]); const count = results.filter(r => r.theory).length; return count ? count + '/' + results.length + ' repertoires' : 'Outside repertoires'; }
    setTimeout(() => loadCatalog(), 0);
    return {html, settingsHtml, bind, refresh, summary, updateMarker,
      beginSettings:() => { adjusting = null; help = false; },
      openLibrary:() => { api.tab(); }, isLibrary:() => false, back:() => false};
  };
  window.repertoireBookIcon = icon;
}());
