// Isolated phone-size UI checks with a fake native bridge. No pairing or live services are used.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const assetRoot = new URL('../../app/src/main/assets/analysis/', import.meta.url);
const output = new URL('../../app/build/reports/phone-preview/', import.meta.url);
await mkdir(output, { recursive:true });
const mime = { '.html':'text/html', '.css':'text/css', '.js':'text/javascript', '.svg':'image/svg+xml', '.ttf':'font/ttf' };
const server = createServer(async (req,res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  if (path.includes('..')) { res.writeHead(404).end(); return; }
  try {
    const file = path === '/' ? 'index.html' : path.slice(1);
    const data = await readFile(new URL(file, assetRoot));
    res.writeHead(200, { 'Content-Type':mime[file.slice(file.lastIndexOf('.'))] || 'application/octet-stream' }).end(data);
  } catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ headless:true, ...(process.env.PHONE_PREVIEW_CHROMIUM ? { executablePath:process.env.PHONE_PREVIEW_CHROMIUM } : {}) });
try {
  for (const [width,height] of [[360,640],[390,780],[412,844]]) {
    const context = await browser.newContext({ viewport:{width,height}, deviceScaleFactor:1, isMobile:true, hasTouch:true });
    await context.addInitScript(() => {
      let sequence = 0;
      const canceled = new Set();
      window.__preview = { saved:'{}', settings:{nodes:4000,leelaEnabled:true}, errors:[] };
      const preferred = ['e2e4','e7e5','g1f3','b8c6','f1c4','g8f6','d2d3','f8c5'];
      function position(raw) {
        const chess = new window.InstinctaZeroChessRules.Chess();
        JSON.parse(raw).history.forEach(uci => chess.move({from:uci.slice(0,2),to:uci.slice(2,4),promotion:uci[4]}));
        return chess;
      }
      window.InstinctaZeroNative = {
        getUiSettings:() => JSON.stringify(window.__preview.settings),
        saveUiSettings:raw => { window.__preview.settings=JSON.parse(raw); return raw; },
        getStudyState:() => window.__preview.saved,
        saveStudyState:raw => { window.__preview.saved=raw; return true; },
        cancelAnalysis:id => canceled.add(id),
        leaveAnalysis:() => { window.__preview.left=true; },
        startAnalysis:raw => {
          const id = 'analysis-'+ ++sequence, chess = position(raw);
          const rank = move => preferred.indexOf(move.from+move.to) < 0 ? 99 : preferred.indexOf(move.from+move.to);
          const legal = chess.moves({verbose:true}).sort((a,b) => rank(a)-rank(b));
          const lines = legal.map((move,index) => {
            const copy = new window.InstinctaZeroChessRules.Chess(chess.fen());
            const pv=[],san=[];
            let next = move;
            for(let step=0; step<7 && next; step++) {
              const played = copy.move(next); pv.push(played.from+played.to+(played.promotion||'')); san.push(played.san);
              const options = copy.moves({verbose:true}); next=options.find(m=>preferred.includes(m.from+m.to)) || options[0];
            }
            return {multipv:index+1,pv,san,white_cp:31-index*4,white_score:((31-index*4)/100).toFixed(2)};
          });
          const data={lines,search_phase:'ready',total_nodes:3600,nps:2400,elapsed_ms:1500,progress:{visits:3600,target:4000},move_stats:lines.map((line,i)=>({uci:line.pv[0],visits:Math.max(2,1400-i*170),prior:Math.max(.005,.35-i*.045),q:.15-i*.015}))};
          setTimeout(()=>{ if(!canceled.has(id))window.InstinctaZero.onNativeAnalysis(id,{event:'lc0',data}); },40);
          return id;
        },
        requestExplorer:raw => {
          const id='book-'+ ++sequence;
          const moves=position(raw).moves({verbose:true}).slice(0,12).map((move,i)=>({uci:move.from+move.to,san:move.san,games:1200000-i*80000,white:550000-i*20000,draws:180000,black:470000-i*60000}));
          setTimeout(()=>{ if(!canceled.has(id))window.InstinctaZero.onNativeExplorer(id,{moves,totals:{games:6000000,white:2700000,draws:1500000,black:1800000}}); },40);
          return id;
        }
      };
    });
    const page=await context.newPage(), errors=[];
    page.on('pageerror', error=>errors.push(error.message));
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
    await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));
    await page.locator('.pv').first().waitFor();
    const shot = name=>page.screenshot({path:fileURLToPath(new URL(`${name}-${width}.png`,output))});
    await shot('engine');
    const metrics=await page.evaluate(()=>({
      overflow:document.documentElement.scrollWidth>innerWidth,
      board:document.querySelector('#board').getBoundingClientRect().toJSON(),
      controls:[...document.querySelectorAll('.tabs button:not([hidden]),.actions button')].map(b=>({name:b.getAttribute('aria-label'),width:b.offsetWidth,height:b.offsetHeight}))
    }));
    assert.equal(metrics.overflow,false);
    assert.equal(metrics.board.width,metrics.board.height);
    metrics.controls.forEach(b=>assert.ok(b.width>=44 && b.height>=44,`${width}: ${b.name} is ${b.width}×${b.height}`));
    async function tapMove(uci) {
      const board=await page.locator('#board').boundingBox();
      const tap=s=>page.touchscreen.tap(board.x+(s.charCodeAt(0)-97+.5)*board.width/8,board.y+(8-Number(s[1])+.5)*board.height/8);
      await tap(uci.slice(0,2)); await tap(uci.slice(2,4));
      await page.waitForFunction(move=>JSON.parse(window.__preview.saved).cursor?.at(-1)===move,uci);
    }
    const history=()=>page.evaluate(()=>JSON.parse(window.__preview.saved).cursor);
    const nav=direction=>page.locator(`[data-action=${direction}]`).tap();
    await page.locator('.pv').first().tap();
    assert.deepEqual(await history(),['e2e4']);
    await nav('prev');
    await tapMove('e2e4'); await tapMove('e7e5'); await tapMove('g1f3');
    await nav('prev'); await nav('prev');
    await tapMove('c7c5'); await tapMove('g1f3');
    await page.getByRole('button',{name:'Moves',exact:true}).tap();
    await shot('moves');
    await page.locator('.move').filter({hasText:'c5'}).dispatchEvent('pointerdown',{pointerType:'touch',button:0});
    await page.getByRole('button',{name:'Promote to main line'}).waitFor();
    assert.equal(await page.evaluate(()=>getSelection().toString()),'');
    await page.getByRole('button',{name:'Cancel',exact:true}).tap();
    await nav('mainline');
    assert.deepEqual(await history(),['e2e4']);
    await nav('next');
    assert.deepEqual(await history(),['e2e4','e7e5']);
    // Visit the variation explicitly, then go back past it and use ordinary Forward.
    await page.locator('.move').filter({hasText:'c5'}).tap();
    await nav('prev'); await nav('prev'); await nav('next'); await nav('next');
    assert.deepEqual(await history(),['e2e4','e7e5']);
    await page.getByRole('button',{name:'Engine',exact:true}).tap();
    await page.locator('.pv').first().waitFor();
    const count=await page.locator('.pv').count(); assert.ok(count>8);
    await page.locator('#panel').evaluate(el=>el.scrollTop=150);
    assert.ok(await page.locator('#panel').evaluate(el=>el.scrollTop)>0);
    await page.getByRole('button',{name:'Settings',exact:true}).tap();
    await shot('settings');
    assert.ok(await page.locator('.compact-settings').evaluate(el=>el.scrollHeight<=el.clientHeight),`${width}: engine settings clipped`);
    assert.equal(await page.locator('.tabs button.selected').count(),1);
    await page.getByRole('switch',{name:'Leela'}).tap();
    assert.equal(await page.locator('#tab-title').textContent(),'Leela off');
    await page.getByRole('switch',{name:'Leela'}).tap();
    await page.getByRole('button',{name:'Settings',exact:true}).tap();
    assert.equal(await page.locator('.compact-settings').count(),0);
    await page.getByRole('button',{name:'Book',exact:true}).tap();
    await page.locator('.book-move[data-uci]').first().waitFor();
    await shot('book');
    assert.ok(await page.locator('.result-bar').first().evaluate(el=>el.getBoundingClientRect().width)>160);
    const bookMove=await page.locator('.book-move[data-uci]').first().getAttribute('data-uci');
    await page.locator('.book-move[data-uci]').first().tap();
    assert.equal((await history()).at(-1),bookMove);
    await nav('prev');
    await page.getByRole('button',{name:'Settings',exact:true}).tap();
    await shot('book-settings');
    assert.ok(await page.locator('.book-settings').evaluate(el=>el.scrollHeight<=el.clientHeight),`${width}: book settings clipped`);
    await page.getByRole('button',{name:'Moves',exact:true}).tap();
    assert.equal(await page.locator('.book-settings').count(),0);
    // The typed saved state survives a fresh document, including branches and current position.
    const saved=await page.evaluate(()=>window.__preview.saved);
    await page.addInitScript(value=>{ window.__preview.saved=value; },saved);
    await page.reload();
    assert.deepEqual(await history(),['e2e4','e7e5']);
    await nav('next'); assert.deepEqual(await history(),['e2e4','e7e5','g1f3']);
    assert.deepEqual(errors,[]);
    console.log(`${width}×${height}: layout, touch controls, panels, mainline, return and saved-state checks passed`);
    await context.close();
  }
} finally { await browser.close(); server.close(); }
