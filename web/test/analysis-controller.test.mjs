import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';

const controllerUrl = new URL('../../app/src/main/assets/analysis/analysis.js', import.meta.url);
const styleUrl = new URL('../../app/src/main/assets/analysis/analysis.css', import.meta.url);
const pageUrl = new URL('../../app/src/main/assets/analysis/index.html', import.meta.url);

function functionSource(source, name, nextName) {
  const start = source.indexOf(`function ${name}`);
  const end = source.indexOf(`  function ${nextName}`, start);
  assert.ok(start >= 0 && end > start, `could not extract ${name}`);
  return source.slice(start, end);
}

test('notation is an inline mainline and introduces structure only for real siblings', async () => {
  const [controller, style] = await Promise.all([
    readFile(controllerUrl, 'utf8'),
    readFile(styleUrl, 'utf8')
  ]);

  assert.doesNotMatch(controller, /style\s*=/i);
  const source = functionSource(controller, 'moveMarkup', 'movesPanel');
  const root = { children: [] };
  const e4 = { id:1, color:'w', number:1, san:'e4', children:[] };
  const e5 = { id:2, color:'b', number:1, san:'e5', children:[] };
  const nf3 = { id:3, color:'w', number:2, san:'Nf3', children:[] };
  root.children = [e4]; e4.children = [e5]; e5.children = [nf3];
  const render = new Function('safe', 'cursor', `${source}; return renderMoves;`)(String, nf3);
  const mainline = render(root);
  assert.doesNotMatch(mainline, /class="variations"/);
  assert.match(mainline, />1\.<\/span>e4/);
  assert.match(mainline, />e5<\/button>/);
  assert.doesNotMatch(mainline, /1\.\.\./);
  assert.match(mainline, />2\.<\/span>Nf3/);

  const c5 = { id:4, color:'b', number:1, san:'c5', children:[] };
  e4.children = [e5, c5];
  const branches = render(root);
  assert.equal((branches.match(/class="variation"/g) || []).length, 2);
  assert.equal((branches.match(/1\.\.\./g) || []).length, 2);
  assert.match(style, /\.variations\{display:block;/);
  assert.match(style, /\.variation\{display:block\}/);
});

test('controller uses the narrow native study bridge without credentials or direct network', async () => {
  const controller = await readFile(controllerUrl, 'utf8');

  assert.match(controller, /window\.InstinctaZeroNative/);
  assert.match(controller, /startAnalysis\(JSON\.stringify\(studyRequest\(\)\)\)/);
  assert.match(controller, /requestExplorer\(JSON\.stringify/);
  assert.match(controller, /onNativeAnalysis/);
  assert.match(controller, /onNativeExplorer/);
  assert.match(controller, /visibilitychange/);
  assert.match(controller, /pagehide/);
  assert.doesNotMatch(controller, /\bfetch\s*\(/);
  assert.doesNotMatch(controller, /Authorization\s*:/);
  assert.doesNotMatch(controller, /localStorage/);
});

test('controller retains study branches and presents all underpromotions', async () => {
  const controller = await readFile(controllerUrl, 'utf8');

  assert.match(controller, /cursor\.children\.find/);
  const remember = functionSource(controller, 'remember', 'cancelBookRequest');
  assert.doesNotMatch(remember, /children\.splice|children\s*=/);
  assert.match(controller, /\['q','r','b','n'\]/);
  assert.match(controller, /data-cancel/);
  assert.match(controller, /selectedChild/);
});

test('creating and restoring a new variation does not replace the main navigation continuation', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const rememberSource = controller.slice(controller.indexOf('function mainlineChild'), controller.indexOf('  function cancelBookRequest'));
  const makeRemember = (parent, fen) => new Function(
    'initialCursor', 'chess', 'saveStudyNow',
    `let cursor = initialCursor, nodeId = 10; ${rememberSource}; return remember;`
  )(parent, { fen: () => fen }, () => {});

  const main = { id:1, san:'e5', fen:'main', move:{from:'e7',to:'e5'}, children:[], selectedChild:null };
  const parent = { id:0, children:[main], selectedChild:main };
  const variation = makeRemember(parent, 'variation')({ san:'c5', from:'c7', to:'c5' }, 1, 'b');
  assert.equal(parent.children.length, 2);
  assert.equal(parent.children[1], variation);
  assert.equal(parent.selectedChild, main);

  const emptyParent = { id:2, children:[], selectedChild:null };
  const first = makeRemember(emptyParent, 'first')({ san:'Nf3', from:'g1', to:'f3' }, 1, 'w');
  assert.equal(emptyParent.selectedChild, first);

  const cursorSource = functionSource(controller, 'cursorForHistory', 'applyStudyState');
  const restored = new Function('root', 'moveUci', `${cursorSource}; return cursorForHistory;`)(
    parent,
    node => node.move.from + node.move.to
  )(['c7c5']);
  assert.equal(restored, variation);
  assert.equal(parent.selectedChild, main);
});

test('forward navigation is canonical and Return to mainline finds the nearest branch intersection', async () => {
  const [controller, page, style] = await Promise.all([
    readFile(controllerUrl, 'utf8'),
    readFile(pageUrl, 'utf8'),
    readFile(styleUrl, 'utf8'),
  ]);
  const mainlineSource = functionSource(controller, 'mainlineChild', 'remember');
  const mainlineChild = new Function(`${mainlineSource}; return mainlineChild;`)();
  const root = { children:[] };
  const beforeBranch = { parent:root, children:[] };
  const branchPoint = { parent:beforeBranch, children:[] };
  root.children = [beforeBranch];
  beforeBranch.children = [branchPoint];
  const canonical = { parent:branchPoint, children:[] };
  const variation = { parent:branchPoint, children:[] };
  const continuation = { parent:variation, children:[] };
  branchPoint.children = [canonical, variation];
  variation.children = [continuation];
  branchPoint.selectedChild = variation; // Simulate stale v0.4.5 visit state.
  assert.equal(mainlineChild(branchPoint), canonical);

  const actionSource = functionSource(controller, 'mainlineIntersection', 'restore');
  let restored = null;
  const actions = new Function(
    'mainlineChild', 'initialCursor', 'restore',
    `let cursor = initialCursor; ${actionSource}; return { mainlineIntersection, returnToMainline };`,
  )(mainlineChild, continuation, node => { restored = node; });
  assert.equal(actions.mainlineIntersection(continuation), branchPoint);
  actions.returnToMainline();
  assert.equal(restored, branchPoint);

  const restoreSource = functionSource(controller, 'restore', 'playUci');
  assert.doesNotMatch(restoreSource, /selectedChild/);
  assert.match(controller, /next\.disabled = !mainlineChild\(cursor\)/);
  assert.match(controller, /action === 'prev' \? cursor\.parent : mainlineChild\(cursor\)/);
  assert.doesNotMatch(controller, /cursor\.selectedChild \|\| cursor\.children\[0\]/);
  assert.match(page, /data-action="mainline"[^>]*aria-label="Return to mainline intersection"/);
  assert.doesNotMatch(page, /data-action="size"|aria-label="Expand board"/);
  assert.match(style, /\.actions button\.return-mainline\{[^}]*#c4a86f[^}]*border-top:2px solid #c4a86f/);
});

test('real LC0 callback fixture uses White-POV score, SAN, search stats, and elapsed schema', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const fixture = {
    event: 'lc0',
    data: {
      elapsed_ms: 1432,
      nps: 28,
      progress: { visits: 44, target: 1000, nps: 28 },
      lines: [{ multipv: 1, score: '+0.31', white_score: '+0.31', white_cp: 31, white_mate: null, pv: ['e2e4', 'e7e5'], san: ['e4', 'e5'], nodes: 44, nps: 28 }]
    }
  };

  assert.equal(fixture.data.lines[0].white_score, '+0.31');
  assert.deepEqual(fixture.data.lines[0].san, ['e4', 'e5']);
  assert.match(controller, /line\.white_score/);
  assert.match(controller, /line && line\.white_cp/);
  assert.doesNotMatch(controller, /line\.score/);
  assert.match(controller, /line\.san/);
  assert.match(controller, /stats\.elapsed_ms/);
  assert.match(controller, /stats\.total_nodes != null \? stats\.total_nodes : stats\.nodes/);
  assert.match(controller, /rootStat\.visits/);
  assert.match(controller, /rootStat\.prior/);
  assert.match(controller, /class="leela-stat-bar"/);
  assert.match(controller, /safe\(visits\) \+ '\/' \+ safe\(progress\.target \|\| settings\.nodes\)/);
  assert.doesNotMatch(controller, /score_cp|score_mate|pv_san|time_ms/);
});

test('visible study controls have menu and native-home behavior without a header appearance popup', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const page = await readFile(pageUrl, 'utf8');

  assert.match(controller, /function showMenu/);
  assert.match(controller, /function deleteCurrentBranch/);
  assert.match(controller, /Delete the current branch\?/);
  assert.match(controller, /function setTab/);
  assert.match(controller, /leaveAnalysis/);
  assert.doesNotMatch(controller, /exitStudy/);
  assert.match(controller, /settings\.arrowCount/);
  assert.doesNotMatch(controller, /function showAppearance/);
  assert.doesNotMatch(page, /board-icon/);
  assert.doesNotMatch(controller, /settings\.multipv|data-multipv/);
  assert.doesNotMatch(controller, /engine\.lines\.slice/);
  const requestSource = functionSource(controller, 'studyRequest', 'renderActivePanel');
  const requests = new Function('history', 'settings', 'studyContext', `${requestSource}; return [studyRequest(), explorerRequest()];`)(() => ['e2e4'], { nodes:4321, backend:'cpu', bookSource:'lichess', bookSpeeds:[], bookRatings:[] }, {gameId:null});
  assert.deepEqual(requests, [
    { history:['e2e4'], nodes:4321, backend:'cpu' },
    { history:['e2e4'], source:'lichess' }
  ]);
  assert.match(controller, /saveUiSettings/);
  assert.match(controller, /settings\.nodes = next/);
});

test('explorer errors render as errors and PV buttons retain legacy row styling', async () => {
  const [controller, style] = await Promise.all([
    readFile(controllerUrl, 'utf8'),
    readFile(styleUrl, 'utf8')
  ]);

  assert.match(controller, /payload\.event === 'error'/);
  assert.match(controller, /book\.error = payload\.message/);
  assert.match(controller, /book\.data = null/);
  assert.match(style, /\.pv\{[^}]*width:100%[^}]*border:0[^}]*background:transparent[^}]*text-align:left[^}]*appearance:none/);
});

test('reset clears the tree and game context, Leela off clears engine state, and tabs preserve analysis', async () => {
  const controller = await readFile(controllerUrl, 'utf8');

  assert.match(controller, /function resetStudy\(\)[^\n]*resetRoot\(START_FEN,[^\n]*gameId:null/);
  assert.match(controller, /function clearEngine\(nextStatus\)/);
  assert.match(controller, /engine\.lastGood = null; engine\.lines = \[\]; engine\.stats = null; engine\.progress = null/);
  assert.match(controller, /clearEngine\('off'\)/);
  assert.match(controller, /function setTab\(next\) \{[^}]*if \(tab === 'book'\) requestBook\(\); \}/);
  const setTabBody = controller.match(/function setTab\(next\) \{([^}]*)\}/)[1];
  assert.doesNotMatch(setTabBody, /scheduleAnalysis|cancelAnalysis/);
});

test('board resize refreshes Chessground cached bounds after layout', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const start = controller.indexOf('function refreshBoardBounds');
  const end = controller.indexOf('  function loadUiSettings', start);
  assert.ok(start >= 0 && end > start);
  const source = controller.slice(start, end);
  let frame = null;
  const calls = [];
  const bounds = { left: 2, top: 4, width: 320, height: 320 };
  const fakeGround = {
    setBounds(value) { calls.push(['bounds', value]); },
    redrawSync() { calls.push(['redraw']); }
  };
  const fakeBoard = { getBoundingClientRect() { return bounds; } };
  const makeRefresh = new Function('requestAnimationFrame', 'ground', 'boardEl', 'renderArrows', 'engine', source + '; return refreshBoardBounds;');
  const refresh = makeRefresh(callback => { frame = callback; }, null, null, () => {}, { lines: [] });

  refresh(fakeGround, fakeBoard, () => calls.push(['arrows']));
  assert.deepEqual(calls, []);
  assert.equal(typeof frame, 'function');
  frame();
  assert.deepEqual(calls, [['bounds', bounds], ['redraw'], ['arrows']]);
  assert.ok((controller.match(/refreshBoardBounds\(\)/g) || []).length >= 3);
});

test('stream rerenders retain engine scroll/focus and PV taps play the first move', async () => {
  const controller = await readFile(controllerUrl, 'utf8');

  assert.match(controller, /scrollTop: panel\.scrollTop/);
  assert.match(controller, /document\.activeElement\.dataset\.pv/);
  assert.match(controller, /focused\.focus\(\{ preventScroll:true \}\)/);
  assert.match(controller, /panel\.scrollTop = preserveEngine\.scrollTop/);
  assert.match(controller, /data-pv-uci=/);
  assert.match(controller, /playUci\(button\.dataset\.pvUci\)/);
  assert.doesNotMatch(controller, /selectedPv|engineArrowLines/);
  assert.match(controller, /function flipBoard\(\) \{[^}]*renderArrows\(engine\.lines\)/);
});

test('leaving the book tab cancels its in-flight native request', async () => {
  const controller = await readFile(controllerUrl, 'utf8');

  assert.match(controller, /function cancelBookRequest\(\)/);
  assert.match(controller, /native\(\)\.cancelAnalysis\(book\.requestId\)/);
  assert.match(controller, /if \(tab === 'book' && next !== 'book'\) cancelBookRequest\(\)/);
  assert.match(controller, /book\.requestId = null; book\.loading = false/);
});

test('menus are touch-first full-panel views and Back dismisses them', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const style = await readFile(styleUrl, 'utf8');

  assert.match(controller, /function panelViewHtml\(\)/);
  assert.match(controller, /panel\.innerHTML = panelView \? panelViewHtml\(\)/);
  assert.match(controller, /handleAndroidBack = function \(\) \{ if \(promotionPicker\)[^\n]*if \(panelView\) \{ closePanelView\(\); return true; \}/);
  assert.doesNotMatch(controller, /study-overlay|overlay-card|document\.body\.appendChild\(overlay\)/);
  assert.match(style, /\.panel-view\{width:100%/);
  assert.match(style, /\.panel-buttons button\{min-height:40px/);
  assert.doesNotMatch(style, /\.study-overlay|\.overlay-card/);
});

test('desktop Leela arrow weighting fixtures execute with exact widths and suppression', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const source = functionSource(controller, 'leelaArrowWidthFromMetrics', 'rankedLeelaAlternativeLines');
  const width = new Function(`${source}; return leelaArrowWidthFromMetrics;`)();

  assert.equal(width({ bestVisits:7176, alternativeVisits:2152, referenceQ:-0.0779, alternativeQ:-0.0779 }), 7);
  assert.equal(width({ bestVisits:7176, alternativeVisits:321, referenceQ:-0.0779, alternativeQ:-0.11384 }), 4);
  assert.equal(width({ bestVisits:1000, alternativeVisits:800, referenceQ:0.4, alternativeQ:0 }), null);
});

test('engine eval text always uses White perspective and never side-to-move score', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const start = controller.indexOf('function finiteMetric');
  const end = controller.indexOf('  function engineMoveStats', start);
  const source = controller.slice(start, end);
  const whiteEval = new Function(`${source}; return whiteEvalText;`)();

  assert.equal(whiteEval({ score:'+0.80', white_score:'-0.80', white_cp:-80 }), '-0.80');
  assert.equal(whiteEval({ score:'+0.80', white_cp:-80 }), '-0.80');
  assert.equal(whiteEval({ score:'+0.80' }), '—');
});

test('book percentages normalize counts and inconsistent reported percentages', async () => {
  const [controller, style] = await Promise.all([readFile(controllerUrl, 'utf8'), readFile(styleUrl, 'utf8')]);
  const finite = functionSource(controller, 'finiteMetric', 'compactCount');
  const percentages = functionSource(controller, 'resultPercentages', 'resultBar');
  const normalize = new Function(`${finite}${percentages}; return resultPercentages;`)();

  assert.deepEqual(normalize({ games:100, white:32, draws:44, black:24 }), [32, 44, 24]);
  assert.deepEqual(normalize({ games:100, white_pct:20, draw_pct:30, black_pct:30 }), [25, 37.5, 37.5]);
  assert.deepEqual(normalize({ games:0 }), [0, 0, 0]);
  assert.match(controller, /No explorer moves for this position/);
  assert.match(controller, /moves \+ summary/);
  const resultSource = controller.slice(controller.indexOf('function resultPercentages'), controller.indexOf('  function bookPanel'));
  const resultBar = new Function('finiteMetric', 'safe', `${resultSource}; return resultBar;`)(value => Number.isFinite(Number(value)) ? Number(value) : null, String);
  const markup = resultBar({ games:100, white:32, draws:44, black:24 }, 'fixture');
  assert.doesNotMatch(markup, /viewBox=/);
  assert.match(markup, /class="result-white" width="32%"/);
  assert.match(markup, /class="result-draw" x="32%" width="44%"/);
  assert.match(markup, /class="result-black" x="76%" width="24%"/);
  assert.match(markup, /class="result-outline"/);
  assert.match(style, /grid-template-columns:38px 38px 48px minmax\(0,1fr\)/);
  assert.match(style, /\.result-bar\{display:block;width:100%;min-width:0;/);
});

test('played PV is projected into a nodes-and-backend-keyed child cache until a coherent snapshot arrives', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const projectSource = functionSource(controller, 'projectAnalysisToChild', 'joinedPositiveLines');
  const finiteMetric = value => value === null || value === undefined || value === '' || !Number.isFinite(Number(value)) ? null : Number(value);
  const project = new Function('finiteMetric', `${projectSource}; return projectAnalysisToChild;`)(finiteMetric);
  const parent = {
    lines:[{ multipv:2, pv:['e2e4','c7c5','g1f3'], san:['e4','c5','Nf3'], white_score:'+0.18', white_cp:18, white_mate:null }],
    move_stats:[{ uci:'e2e4', visits:321, prior:.27 }],
    mobile_backend:'cpu',
    target_nodes:1000,
    total_nodes:900
  };
  const inherited = project(parent, 'e2e4', 4000);
  assert.equal(inherited.inherited, true);
  assert.equal(inherited.target_nodes, 4000);
  assert.equal(inherited.total_nodes, 321);
  assert.deepEqual(inherited.move_stats, []);
  assert.deepEqual(inherited.lines[0].pv, ['c7c5','g1f3']);
  assert.deepEqual(inherited.lines[0].san, ['c5','Nf3']);
  assert.deepEqual(inherited.inherited_eval, {white_score:'+0.18', white_cp:18, white_mate:null});
  const leaf = project({ ...parent, lines:[{pv:['e2e4'],san:['e4'],white_score:'+0.18',white_cp:18}] }, 'e2e4', 4000);
  assert.deepEqual(leaf.lines, []);
  assert.equal(leaf.inherited_eval.white_cp, 18);
  assert.match(controller, /engine\.stats && engine\.stats\.inherited_eval/);
  assert.equal(project(parent, 'd2d4', 4000), null);
  const inherit = new Function(
    'finiteMetric',
    'moveUci',
    'settings',
    'engine',
    `${projectSource}; return inheritAnalysisToChild;`,
  )(finiteMetric, node => node.uci, {nodes:4000}, {stats:null});
  const parentNode = {analysisCache:parent};
  const childNode = {parent:parentNode, uci:'e2e4'};
  inherit(parentNode, childNode);
  assert.deepEqual(childNode.analysisCache.lines[0].pv, ['c7c5','g1f3']);
  assert.equal(childNode.analysisCache.inherited_eval.white_cp, 18);
  assert.match(controller, /if \(next\.parent === previous\) inheritAnalysisToChild\(previous, next\)/);

  const snapshotSource = controller.slice(controller.indexOf('function joinedPositiveLines'), controller.indexOf('  function applyAnalysisSnapshot'));
  const coherent = new Function('finiteMetric', 'settings', `${snapshotSource}; return coherentAnalysisSnapshot;`)(finiteMetric, {backend:'cpu'});
  const packet = {
    search_phase:'ready',
    lines:[{pv:['c7c5']},{pv:['e7e5']},{pv:['e7e6']}],
    move_stats:[{uci:'c7c5',visits:20},{uci:'e7e5',visits:0},{uci:'g8f6',visits:8}]
  };
  assert.equal(coherent({ ...packet, search_phase:'provisional' }, 4000), null);
  assert.deepEqual(coherent(packet, 4000).lines.map(line => line.pv[0]), ['c7c5']);
  assert.deepEqual(coherent(packet, 4000).move_stats.map(stat => stat.uci), ['c7c5','g8f6']);
  assert.deepEqual(coherent({ search_phase:'final', lines:[], move_stats:[] }, 4000).lines, []);
  assert.equal(coherent({ search_phase:'provisional', lines:[], move_stats:[] }, 4000), null);

  const cacheSource = controller.slice(controller.indexOf('function applyAnalysisSnapshot'), controller.indexOf('  function onPositionChanged'));
  const engine = { lines:[], stats:null };
  const cursor = { analysisCache:inherited };
  let arrowLines = null;
  const restore = new Function('engine', 'cursor', 'settings', 'finiteMetric', 'renderArrows', `${cacheSource}; return restoreCachedAnalysis;`)(engine, cursor, {nodes:4000,backend:'cpu'}, finiteMetric, lines => { arrowLines = lines; });
  restore();
  assert.deepEqual(engine.lines[0].pv, ['c7c5','g1f3']);
  assert.equal(engine.status, 'inherited');
  assert.deepEqual(arrowLines[0].pv, ['c7c5','g1f3']);
  cursor.analysisCache.target_nodes = 1000;
  restore();
  assert.deepEqual(engine.lines, []);
  assert.equal(engine.status, 'idle');
});

test('all positive-visit engine lines remain visible and the prior/visit bar is 8px', async () => {
  const [controller, style] = await Promise.all([readFile(controllerUrl, 'utf8'), readFile(styleUrl, 'utf8')]);
  const finiteMetric = value => value === null || value === undefined || value === '' || !Number.isFinite(Number(value)) ? null : Number(value);
  const snapshotSource = controller.slice(controller.indexOf('function joinedPositiveLines'), controller.indexOf('  function applyAnalysisSnapshot'));
  const coherent = new Function('finiteMetric', 'settings', `${snapshotSource}; return coherentAnalysisSnapshot;`)(finiteMetric, {backend:'cpu'});
  const lines = Array.from({length:11}, (_, index) => ({ pv:[`move${index}`] }));
  const move_stats = lines.map((line, index) => ({ uci:line.pv[0], visits:index === 10 ? 0 : index + 1 }));
  const joined = coherent({ search_phase:'ready', lines, move_stats }, 1000);
  assert.equal(joined.lines.length, 10);
  assert.equal(joined.lines.some(line => line.pv[0] === 'move10'), false);
  assert.equal(joined.mobile_backend, 'cpu');
  assert.match(controller, /cached\.mobile_backend === settings\.backend/);
  assert.doesNotMatch(controller, /engine\.lines\.slice/);
  assert.match(controller, /engine\.lines\.map\(/);
  assert.match(controller, /finiteMetric\(stat && stat\.visits\) > 0/);
  assert.match(controller, /viewBox="0 0 100 8"/);
  assert.match(controller, /class="stat-prior"[^>]*height="4"/);
  assert.match(controller, /class="stat-visits" y="4"[^>]*height="4"/);
  assert.match(style, /\.leela-stat-bar\{display:block;width:46px;height:8px\}/);
});

test('navigation buttons blur, stop globally, and repeat quickly until the tree boundary', async () => {
  const [controller, style] = await Promise.all([readFile(controllerUrl, 'utf8'), readFile(styleUrl, 'utf8')]);
  const navigationStart = controller.indexOf('function stopActiveNavigation');
  const source = controller.slice(navigationStart, controller.indexOf("  document.querySelectorAll('.tabs [data-tab]')", navigationStart));
  let activeNavigationStop = null, delayed = null, repeating = null, clearedIntervals = 0, restored = [];
  const button = { disabled:false, blurCount:0, blur() { this.blurCount += 1; }, setPointerCapture() {} };
  const targets = [{id:1},{id:2},null];
  const bind = new Function('restore', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'state', `let activeNavigationStop = state.value; ${source}; state.stop = stopActiveNavigation; return bindRepeatingNavigation;`)(
    next => restored.push(next.id),
    (callback, ms) => { delayed = {callback,ms}; return 1; },
    () => {},
    (callback, ms) => { repeating = {callback,ms}; return 2; },
    () => { clearedIntervals += 1; },
    {}
  );
  bind(button, () => targets.shift());
  let prevented = false;
  button.onpointerdown({ pointerId:7, preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.deepEqual(restored, [1]);
  assert.equal(delayed.ms, 150);
  delayed.callback();
  assert.equal(repeating.ms, 60);
  repeating.callback();
  repeating.callback();
  assert.deepEqual(restored, [1,2]);
  assert.ok(clearedIntervals >= 1);
  assert.ok(button.blurCount >= 3);
  assert.equal(button.onpointerleave, undefined);
  assert.equal(typeof button.onlostpointercapture, 'function');
  assert.match(controller, /window\.addEventListener\('blur', stopActiveNavigation\)/);
  assert.match(controller, /document\.hidden\) \{ stopActiveNavigation\(\); saveStudyNow\(\); resetTransport\(\); \}/);
  assert.match(style, /touch-action:manipulation/);
  assert.match(style, /-webkit-touch-callout:none/);
  assert.match(style, /user-select:none/);
});

test('arrow SVG uses desktop blue-grey brushes, dynamic widths, and shortened endpoints', async () => {
  const [controller, style, page] = await Promise.all([
    readFile(controllerUrl, 'utf8'),
    readFile(styleUrl, 'utf8'),
    readFile(pageUrl, 'utf8')
  ]);

  assert.match(style, /\.analysis-arrow\.blue\{stroke:#003088;opacity:\.28\}/);
  assert.match(style, /\.analysis-arrow\.grey\{stroke:#4a4a4a;opacity:\.35\}/);
  assert.doesNotMatch(style, /\.analysis-arrow\{[^}]*stroke-width/);
  assert.match(controller, /stroke-width="' \+ width/);
  assert.match(controller, /destinationCounts\.get\(shape\.move\.slice\(2,4\)\) > 1 \? 20 : 10/);
  assert.match(page, /id="arrow-blue" markerWidth="4" markerHeight="4" refX="2\.05" refY="2"/);
  assert.match(page, /id="arrow-grey" markerWidth="4" markerHeight="4" refX="2\.05" refY="2"/);
});

test('active full-panel forms are not rebuilt by streamed engine updates', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const source = functionSource(controller, 'renderActivePanel', 'scheduleAnalysis');
  let renders = 0;
  let headings = 0;
  const hidden = new Function('panelView', 'renderPanel', 'heading', `${source}; return renderActivePanel;`)('settings', () => { renders += 1; }, () => { headings += 1; });
  hidden();
  assert.equal(renders, 0);
  assert.equal(headings, 1);
  const visible = new Function('panelView', 'renderPanel', 'heading', `${source}; return renderActivePanel;`)(null, () => { renders += 1; }, () => { headings += 1; });
  visible();
  assert.equal(renders, 1);
  assert.match(controller, /onNativeAnalysis[^\n]*renderActivePanel/);
  assert.match(controller, /settings\.showArrows = !settings\.showArrows/);
  assert.match(controller, /renderArrows\(engine\.lines\)/);
});

test('engine controls are touch-only discrete sliders and contain no text or number field', async () => {
  const [controller, page, style] = await Promise.all([readFile(controllerUrl, 'utf8'), readFile(pageUrl, 'utf8'), readFile(styleUrl, 'utf8')]);
  assert.match(controller, /const NODE_OPTIONS = \[100, 200, 400, 700, 1000, 2000, 4000, 7000, 10000, 20000, 40000, 70000, 100000\]/);
  assert.match(controller, /data-nodes type="range"/);
  assert.match(controller, /data-arrow-count type="range" min="1" max="8" step="1"/);
  assert.match(controller, /data-backend="cpu"/);
  assert.match(controller, /data-backend="sycl"/);
  assert.match(controller, /settings\.backend = next; clearAnalysisCaches\(root\)/);
  assert.match(controller, /nodes\.oninput[^\n]*data-node-readout/);
  assert.match(controller, /nodes\.onchange[^\n]*clearAnalysisCaches\(root\)[^\n]*scheduleAnalysis\(\)/);
  assert.match(controller, /role="switch"/);
  assert.doesNotMatch(controller + page, /<input[^>]+type=["'](?:text|number)["']/i);
  assert.doesNotMatch(controller, /data-code|data-pair|Pair code/);
  assert.match(style, /\.panel-view\{[^}]*height:100%;overflow:hidden/);
  assert.match(style, /\.range-row\{height:46px/);
  assert.match(style, /\.switch-row\{height:42px/);
});

test('book settings produce exact source/filter payloads and refetch only on panel return', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const source = functionSource(controller, 'studyRequest', 'renderActivePanel');
  const make = settings => new Function('history', 'settings', 'studyContext', `${source}; return explorerRequest();`)(() => ['e2e4'], settings, {gameId:null});
  assert.deepEqual(make({bookSource:'masters',bookSpeeds:['blitz'],bookRatings:[1800]}), {history:['e2e4'],source:'masters'});
  assert.deepEqual(make({bookSource:'lichess',bookSpeeds:[],bookRatings:[]}), {history:['e2e4'],source:'lichess'});
  assert.deepEqual(make({bookSource:'lichess',bookSpeeds:['blitz','rapid'],bookRatings:[1800,2200]}), {history:['e2e4'],source:'lichess',speeds:['blitz','rapid'],ratings:[1800,2200]});
  assert.match(controller, /const BOOK_SPEEDS = \['bullet','blitz','rapid','classical','correspondence'\]/);
  assert.match(controller, /const BOOK_RATINGS = \[1600,1800,2000,2200,2500\]/);
  assert.match(controller, /BOOK_RATINGS\.map\(value => chip\(value, value,/);
  assert.doesNotMatch(controller, /BOOK_RATINGS\.map\(value => chip\(value, value \+ '\+'/);
  const persist = functionSource(controller, 'persistBookSettings', 'bindPanelView');
  assert.doesNotMatch(persist, /requestBook/);
  assert.match(controller, /panelView === 'bookSettings' && bookSettingsDirty/);
  assert.match(controller, /panelView === target\) closePanelView\(\)/);
});

test('analysis activation and Android Back preserve the retained offline board lifecycle', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  assert.match(controller, /analysisActive = false/);
  assert.match(controller, /setAnalysisActive = function \(active\)/);
  assert.match(controller, /if \(!analysisActive\) \{ resetTransport\(\);/);
  assert.match(controller, /else if \(analysisActive && settings\.enabled\) scheduleAnalysis\(\)/);
  assert.match(controller, /handleAndroidBack = function/);
  assert.match(controller, /if \(panelView\) \{ closePanelView\(\); return true; \}/);
  assert.doesNotMatch(controller.slice(controller.lastIndexOf('loadUiSettings()')), /scheduleAnalysis\(\)/);
});

test('legacy drag has no magnified finger offset and readable book geometry is phone sized', async () => {
  const [controller, style] = await Promise.all([readFile(controllerUrl, 'utf8'), readFile(styleUrl, 'utf8')]);
  assert.match(controller, /draggable:\{ enabled:true, magnified:false \}/);
  assert.doesNotMatch(controller, /draggable:\{ enabled:true, magnified:true \}/);
  assert.match(style, /\.book-move\{[^}]*height:44px/);
  assert.match(style, /\.book-move b\{font-size:14px/);
  assert.match(style, /\.book-share,\.book-games\{[^}]*font-size:12px/);
  assert.match(style, /\.result-bar\{[^}]*height:20px/);
  assert.match(style, /\.result-bar text\{[^}]*font:9px/);
});

test('study tree, cursor, game context and board layout persist through the typed native bridge', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  assert.match(controller, /function studyState\(\)/);
  assert.match(controller, /gameId:studyContext\.gameId/);
  assert.match(controller, /cursor:history\(\)/);
  assert.match(controller, /tree:root\.children\.map\(wireNode\)/);
  assert.match(controller, /black:wrap\.classList\.contains\('orientation-black'\)/);
  assert.match(controller, /expanded:false/);
  assert.match(controller, /wrap\.classList\.remove\('expanded'\)/);
  assert.match(controller, /native\(\)\.saveStudyState/);
  assert.match(controller, /native\(\)\.getStudyState/);
  assert.match(controller, /window\.InstinctaZero\.persistStudy = saveStudyNow/);
  assert.match(controller, /cursor\.selectedChild = mainlineChild\(cursor\)/);
  assert.match(controller, /cursor = child; saveStudyNow\(\)/);
  assert.match(controller, /function rebuildTree/);
  assert.match(controller, /loadStudyState\(\)/);
  assert.doesNotMatch(controller, /localStorage/);
});

test('variation long press opens touch actions without text selection', async () => {
  const [controller, style] = await Promise.all([readFile(controllerUrl, 'utf8'), readFile(styleUrl, 'utf8')]);
  assert.match(controller, /setTimeout\(\(\) => \{ held = true; variationTarget = target; openPanelView\('variationActions'\); \}, 480\)/);
  assert.match(controller, /Promote to main line/);
  assert.match(controller, /Delete variation/);
  assert.match(controller, /function promoteVariation/);
  assert.match(controller, /function deleteVariation/);
  assert.match(controller, /button\.oncontextmenu = event => event\.preventDefault\(\)/);
  assert.match(style, /\.moves\{[^}]*user-select:none[^}]*-webkit-touch-callout:none/);
});

test('settings are toggleable panel tabs and White POV eval remains in the tab title', async () => {
  const [controller, page] = await Promise.all([readFile(controllerUrl, 'utf8'), readFile(pageUrl, 'utf8')]);
  assert.match(page, /data-panel-tab="settings"/);
  assert.match(controller, /document\.querySelector\('\[data-panel-tab\]'\)\.onclick = closePanelView/);
  assert.match(controller, /if \(panelView === target\) closePanelView\(\)/);
  assert.match(controller, /const evalText = settings\.enabled && best \? whiteEvalText\(best\)/);
  assert.match(controller, /evalText \+ ' · Leela'/);
  assert.doesNotMatch(controller, /panel-back|‹ Back/);
});

test('stored completed games extend study requests only by trusted game id', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  const source = functionSource(controller, 'studyRequest', 'renderActivePanel');
  const make = new Function('history', 'settings', 'studyContext', `${source}; return [studyRequest(), explorerRequest()];`);
  assert.deepEqual(make(() => ['e2e4'], {nodes:700,backend:'sycl',bookSource:'masters',bookSpeeds:[],bookRatings:[]}, {gameId:'abcdEF12'}), [
    {history:['e2e4'],nodes:700,backend:'sycl',game_id:'abcdEF12'},
    {history:['e2e4'],source:'masters',game_id:'abcdEF12'},
  ]);
  assert.match(controller, /window\.InstinctaZero\.loadArchivedGame/);
  assert.equal('fen' in make(() => [], {nodes:1000,backend:'cpu',bookSource:'masters',bookSpeeds:[],bookRatings:[]}, {gameId:'abcdEF12'})[0], false);
});

test('account changes and missing stored games safely detach archived context', async () => {
  const controller = await readFile(controllerUrl, 'utf8');
  assert.match(controller, /onAccountChanged = function \(\) \{ if \(studyContext\.gameId\) resetStudy\(\); \}/);
  assert.match(controller, /studyContext\.gameId && Number\(payload\.code \|\| data\.code\) === 404/);
});
