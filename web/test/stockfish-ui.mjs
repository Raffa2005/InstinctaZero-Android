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
  for(const model of ['lite','full']) {
    const page=await browser.newPage({viewport:{width:390,height:780},isMobile:true,hasTouch:true});
    page.on('pageerror',err=>console.error(err.message));
    await page.addInitScript(model=>{window.InstinctaZeroNative={
      getUiSettings:()=>'{"leelaEnabled":false}',getRepertoireSettings:()=>'{}',getStudyState:()=>'{}',
      stockfishOptions:()=>JSON.stringify({local:model,lite:true,full:true}),saveStudyState:raw=>{window.saved=JSON.parse(raw);return true;},
      startAnalysis:()=>{throw Error('Unexpected PC analysis')},cancelAnalysis:()=>{}
    }},model);
    await page.goto('http://127.0.0.1:'+http.address().port+'/');
    await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));
    await page.locator('[data-tab="engine"]').tap();
    await page.waitForSelector('.pv[data-uci]',{timeout:45000});
    await page.locator('.pv[data-uci]').first().evaluate(el=>el.click());
    await page.waitForFunction(()=>window.saved?.cursor?.length===1 && document.querySelectorAll('.pv[data-uci]').length>=2).catch(async e=>{console.error((await page.locator('body').innerText()).slice(0,1800));throw e;});
    assert.ok(await page.locator('.analysis-arrow').count()>0);
    await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(false));
    await page.close();console.log(model+': real local MultiPV, arrows and navigation passed');
  }
} finally {await browser.close();http.close();}
