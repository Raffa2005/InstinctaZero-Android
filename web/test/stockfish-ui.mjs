// Opt-in integration test: supply the upstream, digest-verified files outside the APK.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {createReadStream} from 'node:fs';
import {stat} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';
const packs=process.env.STOCKFISH_DOWNLOADS;
if(!packs)throw Error('Set STOCKFISH_DOWNLOADS to the two installed Stockfish 19 packs');
const assets=process.env.PHONE_PREVIEW_ASSETS || fileURLToPath(new URL('../../app/src/main/assets/analysis/',import.meta.url));
const http=createServer(async(req,res)=>{
  try {
    const name=req.url.slice(1)||'index.html';if(name.includes('..'))throw Error();
    const worker=name.startsWith('fish/');
    const file=worker?join(packs,name.slice(5)):join(assets,name);
    const {size}=await stat(file);
    res.writeHead(200,{'Content-Type':{js:'application/javascript',wasm:'application/wasm',html:'text/html',css:'text/css',svg:'image/svg+xml'}[name.split('.').pop()]||'application/octet-stream',
      'Content-Length':size,...worker?{'Content-Security-Policy':"default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'"}:{}});
    createReadStream(file).pipe(res);
  } catch {res.writeHead(404).end();}
});
await new Promise(r=>http.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({executablePath:process.env.PHONE_PREVIEW_CHROMIUM,headless:true});
try {
  for(const model of process.env.STOCKFISH_MODEL?[process.env.STOCKFISH_MODEL]:['lite','full']) {
    const page=await browser.newPage({viewport:{width:390,height:780},isMobile:true,hasTouch:true});
    const errors=[];page.on('pageerror',err=>errors.push(err.message));
    await page.addInitScript(model=>{window.InstinctaZeroNative={
      getUiSettings:()=>'{"leelaEnabled":true}',getRepertoireSettings:()=>'{}',getStudyState:()=>'{}',
      stockfishOptions:()=>JSON.stringify({local:model,lite:true,full:true}),saveStudyState:raw=>{window.saved=JSON.parse(raw);return true;},
      startAnalysis:raw=>{window.lastSearch=JSON.parse(raw);return 'mock';},cancelAnalysis:()=>{}
    }},model);
    await page.goto('http://127.0.0.1:'+http.address().port+'/');
    await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));
    await page.locator('[data-tab="engine"]').tap();
    await page.waitForSelector('.pv[data-uci]',{timeout:45000});
    await page.locator('.pv[data-uci]').first().evaluate(el=>el.click());
    await page.waitForFunction(()=>window.saved?.cursor?.length===1 && document.querySelectorAll('.pv[data-uci]').length>=2).catch(async e=>{console.error((await page.locator('body').innerText()).slice(0,1800));throw e;});
    assert.ok(await page.locator('.analysis-arrow').count()>0);
    const phone=await page.locator('.engine-pane.phone header').boundingBox(),leela=await page.locator('.engine-pane.leela header').boundingBox();
    assert.ok(phone.y < leela.y && leela.y+leela.height<780);
    await page.locator('[data-action="settings"]').tap();
    await page.locator('[data-section="Stockfish"]').tap();await page.locator('[data-fish="server"]').tap();
    await page.locator('[data-sf-lines]').fill('3');await page.waitForFunction(()=>window.lastSearch?.sf_lines===3);
    await page.locator('[data-section="Leela"]').tap();await page.locator('[data-leela-lines]').fill('4');
    await page.waitForFunction(()=>window.lastSearch?.leela_lines===4 && window.lastSearch.sf_lines===3);
    await page.evaluate(()=>window.InstinctaZero.onNativeAnalysis('mock',{event:'stockfish',data:{lines:[{white_cp:37,multipv:1,depth:10,pv:['e7e5'],san:['e5']}]}}));
    for(const tab of ['moves','book','repertoire','info','chart','engine']){
      await page.locator('[data-tab="'+tab+'"]').tap();
      assert.ok(await page.locator('.tabs #sf-eval').isVisible());
      assert.match(await page.locator('#sf-eval').textContent(), /\+0\.37\s·\sSF\sPC/);
      assert.ok(await page.locator('.tabs #leela-eval').isVisible());
      assert.equal(await page.locator('#sf-eval').evaluate(el=>el.parentNode.id),'tab-title');
    }
    await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(false));
    assert.deepEqual(errors,[]);await page.close();console.log(model+': real local MultiPV, arrows and navigation passed');
  }
} finally {await browser.close();http.close();}
