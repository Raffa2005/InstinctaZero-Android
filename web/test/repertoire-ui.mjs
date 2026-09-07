// Packaged WebView assets at phone sizes, with a deterministic native bridge.
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile, mkdir} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';

const assets = process.env.PHONE_PREVIEW_ASSETS ? pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS) + '/') : new URL('../../app/src/main/assets/analysis/', import.meta.url);
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
      const settings=JSON.parse(localStorage.getItem('repertoires') || '{"analysis":["white","black"]}');window.__test={requests:[],saved:localStorage.getItem('study') || '{}',edits:[],failDownload:false,delay:12};
      window.InstinctaZeroNative={
        getUiSettings:()=>'{"leelaEnabled":false}',saveUiSettings:()=>{},getStudyState:()=>window.__test.saved,saveStudyState:raw=>{window.__test.saved=raw;localStorage.setItem('study',raw);},
        cancelAnalysis:()=>{},leaveAnalysis:()=>{window.__test.left=true;},
        getRepertoireSettings:()=>JSON.stringify(settings),saveRepertoireSettings:raw=>{Object.assign(settings,JSON.parse(raw));localStorage.setItem('repertoires',raw);},
        requestRepertoire:raw=>{
          const request=JSON.parse(raw),id='rep-'+(++sequence);window.__test.requests.push(request);
          let result;
          if(request.action==='catalog')result={installed,repertoires:catalog};
          if(request.action==='edit'){window.__test.edits.push(request);result={saved:true};}
          if(request.action==='lookup') {
            const length=request.history.length;
            const next=length===0?['e2e4','e4']:length===1?['c7c5','c5']:length===2?['g1f3','Nf3']:['b8c6','Nc6'];
            result={results:request.selected.map(rep=>({...catalog.find(r=>r.id===rep),theory:length<3,alternative:false,deviation:length>=3?3:0,candidates:length===3?1:0,position_match:length===3,
              comments:length?['Control the centre.\nKeep an eye on the d5 break.','<img src=x onerror=alert(1)> is plain source text.']:[],
              moves:[
                {uci:next[0],san:next[1],theory:true,alternative:length>0,own:length%2===0?rep==='white':rep!=='white',reason:'Source annotation',comments:['Develop naturally and prepare the centre.',rep==='white'?'A second file contributes another useful comment.':'Black’s perspective on this position.'],kind:length>0&&rep!=='white'?'alternative':'repertoire'},
                ...(length<2?[{uci:length===0?'d2d4':'e7e5',san:length===0?'d4':'e5',theory:false,alternative:false,own:false,kind:'analysis',reason:'Informational only; does not reactivate theory',comment:''}]:[])
              ]}))};
          }
          setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,result),window.__test.delay);return id;
        },
        downloadRepertoires:()=>{const id='download-'+(++sequence);setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,window.__test.failDownload?{event:'error',message:'Connection interrupted. Saved copy unchanged.'}:{installed:true,repertoires:catalog,downloaded:true}),40);return id;}
      };
    });
    await page.goto(`http://127.0.0.1:${server.address().port}`);
    await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
    await page.evaluate(()=>window.InstinctaZero.openRepertoires());
    await page.locator('[data-rep-play=e2e4]').waitFor();
    assert.equal(await page.locator('#board').isVisible(),true);
    assert.equal(await page.locator('[data-rep-play=e2e4]').count(),1,'combined moves deduplicated');
    assert.equal(await page.locator('.rep-toolbar,.rep-filters,.rep-card').count(),0,'settings hidden from move view');
    assert.equal(await page.locator('.repertoire-book-marker').isVisible(),false,'no root marker');
    await page.getByRole('button',{name:'Comments on e4',exact:true}).tap();
    assert.match(await page.locator('.rep-comments').innerText(),/second file/);
    assert.match(await page.locator('.rep-comments').innerText(),/Black’s perspective/);
    assert.equal(await page.locator('[data-action=prev]').isDisabled(),true,'reading does not play a move');
    await page.screenshot({path:`${output}/comments-${width}.png`});
    await page.locator('[data-rep-play=e2e4]').tap();
    await page.getByRole('img',{name:'Repertoire move',exact:true}).waitFor();
    await page.getByRole('button',{name:'Comment on played move'}).tap();
    assert.match(await page.locator('.rep-comments').innerText(),/Keep an eye/);
    assert.match(await page.locator('.rep-comments').innerText(),/<img src=x/);
    assert.equal(await page.locator('.rep-comments img').count(),0,'comments are escaped');
    assert.equal(await page.locator('.repertoire-book-marker').getAttribute('data-square'),'e4');
    await page.screenshot({path:`${output}/played-comment-${width}.png`});
    const markerPosition=()=>page.locator('.repertoire-book-marker').evaluate(el=>({left:el.style.left,top:el.style.top}));
    assert.deepEqual(await markerPosition(),{left:'62.5%',top:'50%'});
    await page.locator('[data-action=flip]').tap();
    assert.deepEqual(await markerPosition(),{left:'50%',top:'37.5%'});
    await page.locator('[data-action=flip]').tap();
    await page.locator('[data-action=prev]').tap();
    await page.locator('[data-rep-play=e2e4]').waitFor();
    assert.equal(await page.locator('.repertoire-book-marker').isVisible(),false);
    await page.locator('[data-action=next]').tap();
    await page.getByRole('img',{name:'Repertoire move',exact:true}).waitFor();
    await page.getByRole('button',{name:'Comment on played move'}).waitFor();
    await page.locator('[data-action=settings]').tap();
    await page.getByRole('switch',{name:'Book marker on board'}).tap();
    assert.equal(await page.locator('.repertoire-book-marker').isVisible(),false);
    assert.equal(await page.locator('.rep-card.checked').count(),2);
    await page.screenshot({path:`${output}/settings-${width}.png`});
    await page.locator('[data-rep-focus=white]').tap();
    await page.locator('[data-action=settings]').tap();
    await page.getByRole('button',{name:'Adjust c5',exact:true}).tap();
    await page.screenshot({path:`${output}/adjust-${width}.png`});
    assert.equal(await page.getByRole('button',{name:/Make optional alternative/}).count(),0,'opponent reply cannot be optional');
    await page.getByRole('button',{name:/Exclude branch/}).tap();
    await page.waitForFunction(()=>window.__test.edits.length===1);
    const edit=await page.evaluate(()=>window.__test.edits[0]);
    assert.equal(edit.id,'white');assert.equal(edit.kind,'analysis');assert.deepEqual(edit.history,['e2e4','c7c5']);
    await page.locator('[data-action=settings]').tap();
    // Wait for the debounced board save before recreating the whole WebView.
    await page.waitForFunction(()=>JSON.parse(window.__test.saved).cursor?.length===1);
    await page.reload();
    await page.locator('[data-rep-play=c7c5]').waitFor();
    assert.equal(await page.locator('.rep-card,.rep-filters').count(),0,'reopen directly into moves');
    assert.equal(await page.locator('.repertoire-book-marker').isVisible(),false,'marker preference survives restart');
    await page.locator('[data-action=settings]').tap();
    assert.equal(await page.locator('[data-rep-focus=white]').getAttribute('class'),'selected','focus survives restart');
    await page.getByRole('switch',{name:'Book marker on board'}).tap();
    await page.locator('[data-action=settings]').tap();
    await page.getByRole('img',{name:'Repertoire move',exact:true}).waitFor();
    // Out-of-order native lookups must never move a stale marker onto another position.
    await page.evaluate(()=>{window.__test.delay=160;});
    await page.locator('[data-action=prev]').tap();
    await page.evaluate(()=>{window.__test.delay=10;});
    await page.locator('[data-action=next]').tap();
    await page.getByRole('img',{name:'Repertoire move',exact:true}).waitFor();
    await page.waitForTimeout(200);
    assert.equal(await page.locator('.repertoire-book-marker').getAttribute('data-square'),'e4');
    assert.equal(await page.locator('.repertoire-book-marker').isVisible(),true);
    // A new archived game inherits the last selection, but an explicit empty override wins.
    await page.evaluate(()=>window.InstinctaZero.loadArchivedGame({id:'abcdEF12',moves:[{uci:'e2e4'},{uci:'c7c5'},{uci:'g1f3'}],white:{name:'White'},black:{name:'Black'}}));
    await page.getByRole('button',{name:'Repertoires',exact:true}).tap();
    await page.getByRole('img',{name:'Repertoire position by transposition',exact:true}).waitFor();
    assert.equal(await page.locator('.repertoire-book-marker').getAttribute('data-square'),'f3');
    assert.match(await page.locator('.game-title small').innerText(),/Outside repertoires/);
    await page.screenshot({path:`${output}/transposition-${width}.png`});
    await page.locator('[data-action=settings]').tap();
    assert.equal(await page.locator('.rep-card.checked').count(),2);
    await page.getByRole('checkbox',{name:/Tame/}).tap();
    await page.getByRole('checkbox',{name:/Taimanov/}).tap();
    await page.locator('[data-action=settings]').tap();
    await page.getByText('No repertoire selected.').waitFor();
    assert.equal(await page.locator('.repertoire-book-marker').isVisible(),false);
    await page.reload();
    await page.getByText('No repertoire selected.').waitFor();
    await page.getByRole('button',{name:'Choose repertoires',exact:true}).tap();
    await page.getByRole('checkbox',{name:/Queen/}).tap();
    await page.getByRole('button',{name:'How to use',exact:true}).tap();
    await page.screenshot({path:`${output}/guide-${width}.png`});
    assert.match(await page.locator('.rep-guide').innerText(),/full history/);
    await page.evaluate(()=>{window.__test.failDownload=true;});
    await page.waitForTimeout(50);
    const beforeFailure=await page.evaluate(()=>window.__test.requests.length);
    await page.getByRole('button',{name:/Update from PC/}).tap();
    await page.getByRole('alert').waitFor();
    assert.equal(await page.evaluate(()=>window.__test.requests.length),beforeFailure,'download error stays visible');
    assert.equal(await page.locator('.rep-card.checked').count(),1);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    assert.deepEqual(errors,[]);
    console.log(`${width}×${height}: compact moves, all comments, saved choices, independent edits, marker toggle/flip/navigation/transposition/stale replies and errors passed`);
    await page.close();
  }
} finally {await browser.close();server.close();}
