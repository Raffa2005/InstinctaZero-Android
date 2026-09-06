/* A small view over the native, indexed repertoire store. No corpus or credentials in JS. */
(function () {
  'use strict';
  const escape = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const labels = { repertoire:'Main repertoire', alternative:'Alternative', analysis:'Informational', refutation:'Refutation', model_game:'Model game' };
  window.createRepertoirePanel = function (api) {
    const pending = new Map();
    let catalog = [], installed = false, settings = {}, results = [], error = '', busy = false, choosing = false, help = false, focus = '', adjusting = null, library = false, generation = 0, lookupId = null;
    const native = () => window.InstinctaZeroNative;
    try { settings = JSON.parse(native()?.getRepertoireSettings?.() || '{}'); } catch (_) {}
    const contextKey = () => api.key() || 'analysis';
    const selected = () => Array.isArray(settings[contextKey()]) ? settings[contextKey()].filter(id => catalog.some(rep => rep.id === id)) : [];
    function save() { native()?.saveRepertoireSettings?.(JSON.stringify(settings)); }
    function request(payload, done, download) {
      if (!native()?.requestRepertoire) { error = 'Repertoire storage is unavailable in this preview.'; api.render(); return; }
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
      busy = true; error = ''; api.render();
      request({action:'catalog'}, data => {
        busy = false;
        if (data.event === 'error') { error = data.message; api.render(); return; }
        catalog = data.repertoires || []; installed = data.installed;
        api.render();
        refresh();
      }, download);
    }
    function refresh() {
      const current = ++generation;
      if (lookupId) pending.delete(lookupId);
      results = []; adjusting = null;
      if (!selected().length) { api.render(); return; }
      api.render(); // Remove old-position move/edit targets before the asynchronous local lookup.
      lookupId = request({...api.context(), selected:selected(), action:'lookup'}, data => {
        if (current !== generation) return;
        if (data.event === 'error') error = data.message;
        else { error = ''; results = data.results || []; }
        if (choosing || help || adjusting) api.status(); else api.render();
      });
    }
    function badge(move) { return '<span class="rep-badge ' + (move.theory ? move.alternative ? 'optional' : 'active' : 'info') + '">' + escape(move.theory ? move.kind === 'alternative' ? 'Alternative' : move.alternative ? 'Reply · optional line' : 'Repertoire' : labels[move.kind] || 'Informational') + '</span>'; }
    const button = (action, text, extra = '') => '<button class="rep-button" data-rep-action="' + action + '" ' + extra + '>' + text + '</button>';
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
      return '<div class="rep-section-label">COMPARE THIS ' + (api.key() ? 'GAME' : 'BOARD') + '</div>' + catalog.map(rep => {
        const on = selected().includes(rep.id);
        return '<button class="rep-card ' + (on ? 'checked' : '') + '" data-rep-select="' + escape(rep.id) + '" role="checkbox" aria-checked="' + on + '"><span class="rep-check" aria-hidden="true">' + (on ? '✓' : '+') + '</span><span><b>' + escape(rep.name) + '</b><small>' + escape(rep.side === 'white' ? 'White repertoire' : 'Black repertoire') + ' · independent edits</small></span></button>';
      }).join('') + '<p class="rep-hint">Choose one or several. Selections are saved separately for this game or analysis board.</p>';
    }
    function helpHtml() { return '<div class="rep-guide"><h3>One library, distinct repertoires</h3><p>Use <b>Combined</b> to see all selected repertoires, or tap a name to work with just that repertoire. Colours, annotations and edits stay separate.</p><h3>Theory is not every PGN move</h3><p><b>Main repertoire</b> is active theory. <b>Alternatives</b> are valid choices by your repertoire colour, not mistakes. Opponent replies stay regular replies; their optional-line context is retained.</p><p><b>Informational analysis, refutations and model-game tails</b> remain viewable but do not count as theory. These labels come from your reviewed annotation files—not an engine verdict.</p><h3>Move orders matter</h3><p>Checks use the full history. Recorded transpositions work normally. A known position reached by an unrecorded or excluded path is shown as a position match, never silently reactivated as theory.</p><h3>Adjust without changing the originals</h3><p>Use the sliders icon beside a move to make an active own-side choice optional, exclude a branch, or restore its original label. <b>Add current line</b> adds your board history only to the repertoire you choose. Local additions and exclusions cover the branch and save automatically on this phone.</p><p>Source PGNs, annotations and archived Lichess games are never rewritten. Informational source lines must be reviewed in the PC annotations before becoming active. Updating the PC copy keeps your local overrides. No spaced-repetition trainer is included.</p></div>'; }
    function adjustment() {
      const {rep, move} = adjusting;
      return '<div class="rep-adjust-header"><h3>' + escape(move.san) + ' <small>' + escape(rep.name) + '</small></h3>' + button('cancel','Done') + '</div><div class="rep-button-stack">' +
        (move.theory && move.own ? button('alternative','☆ &nbsp; Make optional alternative') : '') +
        button('analysis','⊖ &nbsp; Exclude branch on this phone') +
        (move.edited ? button('reset','↶ &nbsp; Restore original / remove local addition') : '') + '</div><details class="rep-explanation"><summary>Why this label? ' + badge(move) + '</summary><p class="rep-hint">' + escape(move.reason) + '</p>' + (move.comment ? '<p class="rep-comment">' + escape(move.comment) + '</p>' : '') + '</details>';
    }
    function html() {
      let content = '<div class="rep-toolbar"><b>Repertoires</b><div>' + button('choose','<span class="fa">&#xf0c9;</span>','aria-label="Choose repertoires"') + button('help','?','aria-label="How to use repertoires"') + '</div></div>';
      if (error) content += '<p class="rep-error" role="alert">' + escape(error) + '</p>';
      if (busy) content += '<p class="rep-hint" role="status">Downloading from your PC… Your saved copy stays available.</p>';
      if (help) return '<div class="repertoire-panel">' + content + helpHtml() + '</div>';
      if (!installed) return '<div class="repertoire-panel">' + content + '<div class="rep-welcome"><span class="rep-emblem fa">&#xf02d;</span><h3>Your openings, on hand</h3><p>Get your annotated repertoires from the paired PC. After the first download, comparisons work offline.</p>' + button('download', busy ? 'Downloading…' : 'Download from PC', busy ? 'disabled' : '') + '</div></div>';
      if (adjusting) return '<div class="repertoire-panel">' + (error ? '<p class="rep-error" role="alert">' + escape(error) + '</p>' : '') + adjustment() + '</div>';
      if (choosing || !selected().length) return '<div class="repertoire-panel">' + content + selection() + '<div class="rep-footer">' + button('download', busy ? 'Updating…' : '↻ Update from PC', busy ? 'disabled' : '') + (selected().length ? button('compare','Compare selected') : '') + '</div></div>';
      const visible = results.filter(rep => !focus || rep.id === focus);
      content += '<div class="rep-filters"><button data-rep-focus="" class="' + (!focus ? 'selected' : '') + '">Combined</button>' + catalog.filter(rep => selected().includes(rep.id)).map(rep => '<button data-rep-focus="' + escape(rep.id) + '" class="' + (focus === rep.id ? 'selected' : '') + '">' + escape(rep.name) + '</button>').join('') + '</div>';
      if (!results.length) content += '<p class="rep-hint">Checking the current line…</p>';
      for (const rep of visible) {
        content += '<section class="rep-result"><div class="rep-section-label">' + escape(rep.name) + '</div><div class="rep-status ' + (rep.theory ? 'active' : 'outside') + '">' + escape(status(rep)) + '</div>';
        if (rep.candidates) content += '<p class="rep-hint">Known position, different history. This does not reactivate theory.</p>';
        const moves = [...rep.moves].sort((a,b) => Number(b.theory) - Number(a.theory) || Number(a.alternative) - Number(b.alternative));
        if (!moves.length) content += '<p class="rep-hint">' + (rep.theory ? 'End of recorded coverage. Extend this line on the board to add it.' : 'No continuation for this exact history.') + '</p>';
        for (const move of moves) content += '<div class="rep-move"><button data-rep-play="' + escape(move.uci) + '"><b>' + escape(move.san) + '</b>' + badge(move) + '</button><button class="rep-adjust fa" data-rep-edit="' + escape(rep.id + ':' + move.uci) + '" aria-label="Adjust ' + escape(move.san + ' in ' + rep.name) + '">&#xf1de;</button></div>';
        if (api.hasMoves()) content += button('add','+ Add current line here','data-rep-id="' + escape(rep.id) + '"');
        content += '</section>';
      }
      return '<div class="repertoire-panel">' + content + '</div>';
    }
    function bind(panel) {
      panel.querySelectorAll('[data-rep-select]').forEach(el => el.onclick = () => { const ids = selected(), id = el.dataset.repSelect; settings[contextKey()] = ids.includes(id) ? ids.filter(x => x !== id) : [...ids,id]; choosing = true; focus = ''; save(); refresh(); api.render(); });
      panel.querySelectorAll('[data-rep-focus]').forEach(el => el.onclick = () => { focus = el.dataset.repFocus; api.render(); });
      panel.querySelectorAll('[data-rep-play]').forEach(el => el.onclick = () => { if (library) closeLibrary(); api.play(el.dataset.repPlay); });
      panel.querySelectorAll('[data-rep-edit]').forEach(el => el.onclick = () => { const [id,uci] = el.dataset.repEdit.split(':'); const rep = results.find(r => r.id === id); adjusting = {rep, move:rep.moves.find(m => m.uci === uci)}; api.render(); });
      panel.querySelectorAll('[data-rep-action]').forEach(el => el.onclick = () => {
        const action = el.dataset.repAction;
        if (action === 'choose') { choosing = !choosing; help = false; adjusting = null; }
        else if (action === 'help') { help = !help; adjusting = null; }
        else if (action === 'cancel') adjusting = null;
        else if (action === 'compare') { choosing = false; if (library) closeLibrary(); }
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
    function closeLibrary() { library = false; document.getElementById('app').classList.remove('repertoire-library'); api.resume(); }
    function openLibrary() { library = true; choosing = true; help = false; document.getElementById('app').classList.add('repertoire-library'); api.pause(); api.tab(); loadCatalog(); }
    function back() { if (adjusting || help || choosing && !library) { adjusting = null; help = false; choosing = false; api.render(); return true; } if (library) { closeLibrary(); api.render(); return false; } return false; }
    function summary() { if (!results.length) return ''; if (results.length === 1) return status(results[0]); const count = results.filter(r => r.theory).length; return count ? count + '/' + results.length + ' repertoires' : 'Outside repertoires'; }
    setTimeout(() => loadCatalog(), 0);
    return {html, bind, refresh, openLibrary, back, summary, isLibrary:() => library};
  };
}());
