// Packaged WebView assets at phone sizes, with a deterministic native bridge.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile, mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright';

const assets = new URL('../../app/src/main/assets/analysis/', import.meta.url);
const output = process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-repertoire-preview';
await mkdir(output,{recursive:true});
const server = createServer(async (req,res) => {
  try {
    const name = req.url === '/' ? 'index.html' : req.url.slice(1);
    if (name.includes('..')) throw Error('Invalid asset');
    const type = {js:'text/javascript',css:'text/css',svg:'image/svg+xml',html:'text/html',ttf:'font/ttf'}[name.split('.').at(-1)];
    const body = await readFile(new URL(name,assets));
    res.writeHead(200,{'Content-Type':type || 'application/octet-stream'}).end(body);
  } catch {res.writeHead(404).end();}
});
await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
const browser = await chromium.launch({headless:true,...(process.env.PHONE_PREVIEW_CHROMIUM ? {executablePath:process.env.PHONE_PREVIEW_CHROMIUM} : {})});
try {
  for (const [width,height] of [[360,640],[390,780],[412,844]]) {
    const page = await browser.newPage({viewport:{width,height},deviceScaleFactor:1,isMobile:true,hasTouch:true});
    const errors=[];page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(() => {
      let sequence=0, installed=true;
      const catalog=[{id:'white',name:'Tame the Sicilian',side:'white'},{id:'qga',name:'Queen’s Gambit Accepted',side:'black'},{id:'black',name:'Taimanov Sicilian',side:'black'}];
      const settings={};window.__test={requests:[],saved:'{}',edits:[],failDownload:false};
      window.InstinctaZeroNative={
        getUiSettings:()=>'{"leelaEnabled":false}',saveUiSettings:()=>{},getStudyState:()=>window.__test.saved,saveStudyState:raw=>{window.__test.saved=raw;},
        cancelAnalysis:()=>{},leaveAnalysis:()=>{window.__test.left=true;},
        getRepertoireSettings:()=>JSON.stringify(settings),saveRepertoireSettings:raw=>{Object.assign(settings,JSON.parse(raw));},
        requestRepertoire:raw=>{
          const request=JSON.parse(raw),id='rep-'+(++sequence);window.__test.requests.push(request);
          let result;
          if(request.action==='catalog')result={installed,repertoires:catalog};
          if(request.action==='edit'){window.__test.edits.push(request);result={saved:true};}
          if(request.action==='lookup')result={results:request.selected.map(rep=>({...catalog.find(r=>r.id===rep),theory:request.history.length<3,alternative:false,deviation:request.history.length>=3?3:0,candidates:0,moves:[
            {uci:request.history.length===0?'e2e4':'c7c5',san:request.history.length===0?'e4':'c5',theory:true,alternative:false,own:rep==='white',reason:'Source annotation',comment:'Review this move in its own repertoire.',kind:'repertoire'},
            {uci:request.history.length===0?'d2d4':'e7e5',san:request.history.length===0?'d4':'e5',theory:false,alternative:false,own:false,kind:'analysis',reason:'Informational only; does not reactivate theory',comment:''}
          ]}))};
          setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,result),12);return id;
        },
        downloadRepertoires:()=>{const id='download-'+(++sequence);setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,window.__test.failDownload?{event:'error',message:'Connection interrupted. Saved copy unchanged.'}:{installed:true,repertoires:catalog,downloaded:true}),40);return id;}
      };
    });
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
    await page.evaluate(()=>window.InstinctaZero.openRepertoires());
    await page.getByRole('checkbox').first().waitFor();
    await page.getByRole('checkbox',{name:/Tame the Sicilian/}).tap();
    await page.getByRole('checkbox',{name:/Taimanov/}).tap();
    await page.screenshot({path:`${output}/library-${width}.png`});
    assert.equal(await page.locator('#board').isVisible(),false);
    await page.getByRole('button',{name:'Compare selected'}).tap();
    await page.locator('.rep-result').nth(1).waitFor();
    assert.equal(await page.locator('#board').isVisible(),true);
    assert.equal(await page.locator('.rep-result').count(),2);
    await page.screenshot({path:`${output}/combined-${width}.png`});
    await page.locator('[data-rep-focus=white]').tap();
    assert.equal(await page.locator('.rep-result').count(),1);
    await page.getByRole('button',{name:/Adjust e4 in Tame/}).tap();
    await page.screenshot({path:`${output}/adjust-${width}.png`});
    await page.getByRole('button',{name:/Make optional alternative/}).tap();
    await page.waitForFunction(()=>window.__test.edits.length===1);
    const edit=await page.evaluate(()=>window.__test.edits[0]);assert.equal(edit.id,'white');assert.equal(edit.kind,'alternative');assert.deepEqual(edit.history,['e2e4']);
    await page.locator('[data-rep-play=e2e4]').tap();
    await page.waitForFunction(()=>JSON.parse(window.__test.saved).cursor?.length===1);
    await page.locator('[data-action=prev]').tap();
    await page.locator('[data-action=next]').tap();
    await page.waitForFunction(()=>window.__test.requests.at(-1).history?.at(-1)==='e2e4');
    // A different archived game starts with its own repertoire selection.
    await page.evaluate(()=>window.InstinctaZero.loadArchivedGame({id:'abcdEF12',moves:[{uci:'e2e4'},{uci:'e7e5'}],white:{name:'White'},black:{name:'Black'}}));
    await page.getByRole('button',{name:'Repertoires',exact:true}).tap();
    assert.equal(await page.locator('.rep-card.checked').count(),0);
    await page.getByRole('checkbox',{name:/Queen/}).tap();
    await page.getByRole('button',{name:'Compare selected'}).tap();
    await page.locator('.rep-result').waitFor();
    assert.match(await page.locator('.rep-result').innerText(),/Queen/i);
    await page.getByRole('button',{name:'How to use repertoires'}).tap();
    await page.screenshot({path:`${output}/guide-${width}.png`});
    assert.match(await page.locator('.rep-guide').innerText(),/full history/);
    await page.getByRole('button',{name:'Choose repertoires'}).tap();
    await page.evaluate(()=>{window.__test.failDownload=true;});
    await page.getByRole('button',{name:/Update from PC/}).tap();
    await page.getByRole('alert').waitFor();
    assert.equal(await page.locator('.rep-card.checked').count(),1);
    const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);assert.equal(overflow,false);
    assert.deepEqual(errors,[]);
    console.log(`${width}×${height}: library, combined/individual, local edits, game-specific selection, help and download failure passed`);
    await page.close();
  }
} finally {await browser.close();server.close();}
