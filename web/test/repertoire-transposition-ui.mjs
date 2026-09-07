// Render actual native lookup results against Rafael's move-order reproduction.
// The optional data file is private/local and is never packaged in the APK.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';

const dataPath=process.env.REPERTOIRE_PREVIEW_OUTPUT;
if(!dataPath)throw Error('Set REPERTOIRE_PREVIEW_OUTPUT to the local native-test output.');
const nativeResults=JSON.parse(await readFile(dataPath,'utf8'));
const fixture=JSON.parse(await readFile(new URL('../../app/src/test/resources/qga-transposition.json',import.meta.url),'utf8'));
const assets=process.env.PHONE_PREVIEW_ASSETS ? pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/') : new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-transposition-preview';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{
  try{const path=req.url==='/'?'index.html':req.url.slice(1);if(path.includes('..'))throw Error('Invalid asset');
    const type={js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[path.split('.').at(-1)];
    const body=await readFile(new URL(path,assets));
    res.writeHead(200,{'Content-Type':type || 'application/octet-stream'}).end(body);
  }catch{res.writeHead(404).end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({headless:true,...(process.env.PHONE_PREVIEW_CHROMIUM?{executablePath:process.env.PHONE_PREVIEW_CHROMIUM}:{})});
try{
  for(const [width,height] of [[360,640],[390,780],[412,844]]){
    const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];
    page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(({nativeResults})=>{
      let sequence=0;window.__test={saved:localStorage.getItem('study') || '{}',requests:[],delay:10};
      const settings=JSON.parse(localStorage.getItem('repSettings') || '{"analysis":["qga"]}');
      window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":false}',saveUiSettings:()=>{},
        getStudyState:()=>window.__test.saved,saveStudyState:raw=>{window.__test.saved=raw;localStorage.setItem('study',raw);},
        cancelAnalysis:()=>{},getRepertoireSettings:()=>JSON.stringify(settings),saveRepertoireSettings:raw=>localStorage.setItem('repSettings',raw),
        requestRepertoire:raw=>{const request=JSON.parse(raw),id=String(++sequence);window.__test.requests.push(request);
          const result=request.action==='catalog'?{installed:true,repertoires:[{id:'qga',name:'Queen’s Gambit Accepted',side:'black'}]}:
            {results:[nativeResults[request.fen] || {id:'qga',name:'Queen’s Gambit Accepted',side:'black',theory:false,comments:[],moves:[]}]};
          setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,result),window.__test.delay);return id;}
      };
    },{nativeResults});
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
    const load=async(name)=>{
      await page.evaluate(({line})=>window.InstinctaZero.loadArchivedGame({id:'QgaTest1',initial_fen:line.root,moves:line.history.slice(0,16).map(uci=>({uci})),white:{name:'White'},black:{name:'Black'}}),{line:fixture[name]});
      await page.getByRole('button',{name:'Repertoires',exact:true}).tap();
      await page.locator('[data-rep-play=f1d1]').waitFor();await page.locator('[data-rep-play=e3e4]').waitFor();
    };
    await load('reported');
    assert.equal(await page.locator('.rep-end').count(),0);
    assert.equal(await page.getByRole('img',{name:'Repertoire move',exact:true}).isVisible(),true);
    assert.doesNotMatch(await page.locator('.repertoire-panel').innerText(),/different move order|No continuation for this history/);
    await page.waitForTimeout(300); // Let the existing board/tab animations finish for visual inspection.
    await page.screenshot({path:`${output}/qga-moves-${width}.png`});
    await page.getByRole('button',{name:'Comment on played move'}).tap();
    assert.match(await page.locator('.rep-comments').innerText(),/knight belongs on d7/);
    assert.equal((await page.locator('.rep-comments').innerText()).match(/knight belongs on d7/g).length,1);
    await page.screenshot({path:`${output}/qga-transposition-${width}.png`});
    await page.locator('[data-rep-play=f1d1]').tap();await page.locator('[data-rep-play=b7b5]').waitFor();
    await page.getByRole('button',{name:'Comment on played move'}).tap();
    assert.match(await page.locator('.rep-comments').innerText(),/does not win a tempo/);
    await page.locator('[data-action=prev]').tap();await page.locator('[data-rep-play=e3e4]').waitFor();
    await page.evaluate(()=>{window.__test.delay=1500;});
    await page.locator('[data-rep-play=e3e4]').tap();
    assert.equal(await page.getByRole('img',{name:'Repertoire move',exact:true}).isVisible(),true,'native projected position has an immediate normal marker');
    assert.match(await page.locator('.rep-current-note').innerText(),/winning some space/);
    await page.locator('[data-rep-play=b7b5]').waitFor();
    await page.evaluate(()=>{window.__test.delay=10;});
    await page.waitForFunction(()=>JSON.parse(window.__test.saved).cursor?.length===17);
    const history=await page.evaluate(()=>JSON.parse(window.__test.saved).cursor);
    assert.deepEqual(history,fixture.reported.history.concat('e3e4'),'book lookup must not replace actual game history with canonical source moves');
    await page.locator('[data-action=prev]').tap();await page.locator('[data-action=next]').tap();
    // Ordinary forward still chooses the first variation (Rd1), not the most recently visited e4.
    await page.locator('[data-rep-play=b7b5]').waitFor();
    await page.waitForFunction(()=>JSON.parse(window.__test.saved).cursor?.at(-1)==='f1d1');
    await page.reload();await page.locator('[data-rep-play=b7b5]').waitFor();
    assert.equal(await page.getByRole('img',{name:'Repertoire move',exact:true}).isVisible(),true);
    await load('canonical');
    await page.waitForFunction(()=>JSON.parse(window.__test.saved).cursor?.length===16);
    assert.deepEqual(await page.evaluate(()=>JSON.parse(window.__test.saved).cursor),fixture.canonical.history);
    assert.deepEqual(errors,[]);assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    console.log(`${width}×${height}: actual QGA native moves/comments, instant rejoin, both move orders, unchanged history and canonical forward navigation passed`);
    await page.close();
  }
}finally{await browser.close();server.close();}
