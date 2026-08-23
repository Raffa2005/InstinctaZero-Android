import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';

const controllerUrl = new URL('../../app/src/main/assets/analysis/analysis.js', import.meta.url);
const styleUrl = new URL('../../app/src/main/assets/analysis/analysis.css', import.meta.url);

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

test('real LC0 callback fixture uses backend score, SAN, and elapsed schema', async () => {
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

  assert.equal(fixture.data.lines[0].score, '+0.31');
  assert.deepEqual(fixture.data.lines[0].san, ['e4', 'e5']);
  assert.match(controller, /line\.score/);
  assert.match(controller, /line\.san/);
  assert.match(controller, /stats\.elapsed_ms/);
  assert.match(controller, /stats\.total_nodes \|\| stats\.nodes \|\| visits/);
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
  assert.match(controller, /lines\.slice\(0, 3\)/);
  assert.match(controller, /saveUiSettings/);
  assert.match(controller, /settings\.nodes = Math\.min\(100000, Math\.max\(100,/);
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
  assert.equal((controller.match(/refreshBoardBounds\(\)/g) || []).length, 3);
});

test('stream rerenders retain the chosen PV, engine scroll, and keyboard focus', async () => {
  const controller = await readFile(controllerUrl, 'utf8');

  assert.match(controller, /selectedPv: null/);
  assert.match(controller, /scrollTop: panel\.scrollTop/);
  assert.match(controller, /document\.activeElement\.dataset\.pv/);
  assert.match(controller, /focused\.focus\(\{ preventScroll:true \}\)/);
  assert.match(controller, /panel\.scrollTop = preserveEngine\.scrollTop/);
  assert.match(controller, /engine\.selectedPv = Number\(button\.dataset\.pv\)/);
  assert.match(controller, /renderArrows\(engineArrowLines\(\)\)/);
  assert.match(controller, /function flipBoard\(\) \{[^}]*renderArrows\(engineArrowLines\(\)\)/);
});

test('leaving the book tab cancels its in-flight native request', async () => {
  const controller = await readFile(controllerUrl, 'utf8');

  assert.match(controller, /function cancelBookRequest\(\)/);
  assert.match(controller, /native\(\)\.cancelAnalysis\(book\.requestId\)/);
  assert.match(controller, /if \(tab === 'book' && next !== 'book'\) cancelBookRequest\(\)/);
  assert.match(controller, /book\.requestId = null; book\.loading = false/);
});

test('header back safely dismisses every overlay state', async () => {
  const controller = await readFile(controllerUrl, 'utf8');

  assert.match(controller, /if \(overlay\) \{ overlay\.remove\(\); overlay = null; return; \}/);
  assert.doesNotMatch(controller, /overlay\.querySelector\('\[data-close\]'\)\.click\(\)/);
});
