import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const page = read('../../app/src/main/assets/analysis/index.html');
const script = read('../../app/src/main/assets/analysis/analysis.js');
const themeCss = read('../../app/src/main/assets/analysis/theme.css');
const nativeTheme = read('../../app/src/main/java/com/instinctazero/android/UiTheme.kt');

const themeKeys = [...script.matchAll(/(\w+):'(Ember|Ocean|Pine|Slate)'/g)].map(match => match[1]);

test('analysis page loads the theme palette with a stable default theme', () => {
  assert.match(page, /<html lang="en" class="board-assets-loading" data-theme="brown">/);
  assert.ok(page.indexOf('theme.css') > page.indexOf('analysis.css'), 'theme.css wins over analysis.css');
});

test('web and native themes expose the same four palette keys', () => {
  assert.deepEqual(themeKeys, ['brown', 'blue', 'green', 'grey']);
  for (const key of themeKeys) {
    assert.match(nativeTheme, new RegExp(`"${key}"`), `UiTheme declares "${key}"`);
    assert.match(themeCss, new RegExp(`html\\[data-theme="${key}"\\]`), `theme.css styles "${key}"`);
  }
});

test('theme choice renders a picker, applies immediately, and persists through ui settings', () => {
  assert.match(script, /data-theme-choice/);
  assert.match(script, /function applyTheme\(\)/);
  assert.match(script, /appearance: settings\.appearance/);
  assert.match(script, /THEMES\[saved\.appearance\]/);
  // The native shell can change the theme while the page stays warm; analysis
  // re-reads the persisted appearance every time the board is opened.
  assert.match(script, /window\.InstinctaZero\.syncTheme = function/);
});

test('every palette overrides the shared accent hooks', () => {
  for (const key of themeKeys) {
    const block = themeCss.split(`html[data-theme="${key}"]`)[1];
    for (const variable of ['--accent:', '--accent-hi:', '--accent-dim:', '--accent-soft:', '--panel:']) {
      assert.ok(block.includes(variable), `theme "${key}" defines ${variable}`);
    }
  }
  // Accent-driven overrides exist for both the analysis shell and repertoire UI.
  for (const selector of ['.tabs button.selected', '.move.current', '.touch-switch.on', '.rep-card.checked', '.theme-row button']) {
    assert.ok(themeCss.includes(selector), `theme.css overrides ${selector}`);
  }
});
