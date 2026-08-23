import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';

const controllerUrl = new URL('../../app/src/main/assets/analysis/analysis.js', import.meta.url);
const styleUrl = new URL('../../app/src/main/assets/analysis/analysis.css', import.meta.url);

test('variation markup stays CSP-safe and stylesheet-indented', async () => {
  const [controller, style] = await Promise.all([
    readFile(controllerUrl, 'utf8'),
    readFile(styleUrl, 'utf8')
  ]);

  assert.doesNotMatch(controller, /style\s*=/i);
  assert.match(controller, /<div class="branch">/);
  assert.match(controller, /render\(child\)/);
  assert.match(style, /\.branch\{display:block;white-space:nowrap\}/);
  assert.match(style, /\.branch \.branch\{margin-left:10px\}/);
});
