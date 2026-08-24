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

test('controller keeps branches CSP-safe and stylesheet-indented', async () => {
  const [controller, style] = await Promise.all([
    readFile(controllerUrl, 'utf8'),
    readFile(styleUrl, 'utf8')
  ]);

  assert.doesNotMatch(controller, /style\s*=/i);
  assert.match(controller, /<div class="branch">/);
  assert.match(controller, /function renderMoves\(node\)/);
  assert.match(controller, /renderMoves\(child\)/);
  assert.match(style, /\.branch\{display:block;white-space:nowrap\}/);
  assert.match(style, /\.branch \.branch\{margin-left:10px\}/);
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
  assert.doesNotMatch(controller, /children\.splice/);
  assert.match(controller, /\['q','r','b','n'\]/);
  assert.match(controller, /data-cancel/);
  assert.match(controller, /selectedChild/);
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

test('visible study controls have real appearance, menu, and exit behavior', async () => {
  const controller = await readFile(controllerUrl, 'utf8');

  assert.match(controller, /function showAppearance/);
  assert.match(controller, /function showMenu/);
  assert.match(controller, /function deleteCurrentBranch/);
  assert.match(controller, /Delete this branch\?/);
  assert.match(controller, /function setTab/);
  assert.match(controller, /exitStudy/);
  assert.match(controller, /settings\.arrowCount/);
  assert.match(controller, /multipv: Math\.max\(settings\.multipv, settings\.arrowCount\)/);
  assert.match(controller, /engine\.lines = data\.lines\.slice\(0, 8\)/);
  assert.match(controller, /engine\.lines\.slice\(0, settings\.multipv\)/);
  assert.match(controller, /saveUiSettings/);
  assert.match(controller, /settings\.nodes = clampInteger\(/);
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

test('reset clears the tree, Leela off clears engine state, and tabs preserve analysis', async () => {
  const controller = await readFile(controllerUrl, 'utf8');

  assert.match(controller, /function resetStudy\(\) \{ root\.children = \[\]; root\.selectedChild = null; nodeId = 0; restore\(root\); \}/);
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
  assert.match(controller, /if \(panelView\) \{ closePanelView\(\); return; \}/);
  assert.doesNotMatch(controller, /study-overlay|overlay-card|document\.body\.appendChild\(overlay\)/);
  assert.match(style, /\.panel-view\{width:100%/);
  assert.match(style, /\.panel-buttons button\{min-height:44px/);
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
  const controller = await readFile(controllerUrl, 'utf8');
  const finite = functionSource(controller, 'finiteMetric', 'compactCount');
  const percentages = functionSource(controller, 'resultPercentages', 'resultBar');
  const normalize = new Function(`${finite}${percentages}; return resultPercentages;`)();

  assert.deepEqual(normalize({ games:100, white:32, draws:44, black:24 }), [32, 44, 24]);
  assert.deepEqual(normalize({ games:100, white_pct:20, draw_pct:30, black_pct:30 }), [25, 37.5, 37.5]);
  assert.deepEqual(normalize({ games:0 }), [0, 0, 0]);
  assert.match(controller, /No explorer moves for this position/);
  assert.match(controller, /moves \+ summary/);
});

test('arrow SVG uses desktop blue-grey brushes, dynamic widths, and shortened endpoints', async () => {
  const [controller, style, page] = await Promise.all([
    readFile(controllerUrl, 'utf8'),
    readFile(styleUrl, 'utf8'),
    readFile(pageUrl, 'utf8')
  ]);

  assert.match(style, /\.analysis-arrow\.blue\{stroke:#003088;opacity:\.4\}/);
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
  const hidden = new Function('panelView', 'renderPanel', `${source}; return renderActivePanel;`)('settings', () => { renders += 1; });
  hidden();
  assert.equal(renders, 0);
  const visible = new Function('panelView', 'renderPanel', `${source}; return renderActivePanel;`)(null, () => { renders += 1; });
  visible();
  assert.equal(renders, 1);
  assert.match(controller, /onNativeAnalysis[^\n]*renderActivePanel/);
  assert.match(controller, /settings\.showArrows = [^;]+; renderArrows\(engine\.lines\)/);
});
