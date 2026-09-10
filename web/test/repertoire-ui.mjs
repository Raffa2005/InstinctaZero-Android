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
      const catalog=[{id:'white',name:'Tame the Sicilian',side:'white'},{id:'qga',name:'Queen’s Gambit Accepted',side:'black'},{id:'black',name:'Taimanov Sicilian',side:'black'},{id:'ruy_lopez',name:'Ruy Lopez',side:'white'},{id:'jobava_london',name:'Jobava London',side:'white'}];
      const settings=JSON.parse(localStorage.getItem('repertoires') || '{"analysis":["white","black"]}');window.__test={requests:[],saved:localStorage.getItem('study') || '{}',edits:[],failDownload:false,delay:12};
      const additions=JSON.parse(localStorage.getItem('additions') || '{}');
      let undo=JSON.parse(localStorage.getItem('repertoireUndo') || 'null');
      const undoInfo=()=>undo?{token:undo.token,name:undo.name,label:undo.label,repertoire:undo.repertoire}:null;
      const saveUndo=()=>localStorage.setItem('repertoireUndo',JSON.stringify(undo));
      window.__test.additions=additions;window.__test.extensionMode=localStorage.getItem('extensionMode')==='true';
      window.InstinctaZeroNative={
        getUiSettings:()=>'{"leelaEnabled":false}',saveUiSettings:()=>{},getStudyState:()=>window.__test.saved,saveStudyState:raw=>{window.__test.saved=raw;localStorage.setItem('study',raw);},
        cancelAnalysis:()=>{},leaveAnalysis:()=>{window.__test.left=true;},
        getRepertoireSettings:()=>JSON.stringify(settings),saveRepertoireSettings:raw=>{Object.assign(settings,JSON.parse(raw));localStorage.setItem('repertoires',raw);},
        requestRepertoire:raw=>{
          const request=JSON.parse(raw),id='rep-'+(++sequence);window.__test.requests.push(request);
          let result;
          if(request.action==='catalog')result={installed,repertoires:catalog,undo:undoInfo()};
          if(request.action==='edit'){
            const before=JSON.parse(JSON.stringify(additions));
            window.__test.edits.push(request);result={saved:true};
            if(request.kind==='add'){
              additions[request.id]=additions[request.id] || [];
              for(let ply=4;ply<=request.history.length;ply++) {
                const path=request.history.slice(0,ply).join(' ');
                if(!additions[request.id].includes(path))additions[request.id].push(path);
              }
              localStorage.setItem('additions',JSON.stringify(additions));
            }
            const count=Number(localStorage.getItem('undoSequence') || '0')+1;localStorage.setItem('undoSequence',String(count));
            undo={token:'undo-'+count,repertoire:request.id,name:catalog.find(rep=>rep.id===request.id).name,label:request.kind==='add'?'Added line':'Changed label',before};
            saveUndo();result.undo=undoInfo();
          }
          if(request.action==='undo'){
            if(!undo || request.token!==undo.token)result={event:'error',message:'Undo no longer available',undo:undoInfo()};
            else {
              Object.keys(additions).forEach(key=>delete additions[key]);Object.assign(additions,undo.before);
              localStorage.setItem('additions',JSON.stringify(additions));undo=null;saveUndo();
              result={saved:true,undo:null};
            }
          }
          if(request.action==='lookup') {
            const length=request.history.length;
            const next=length===0?['e2e4','e4']:length===1?['c7c5','c5']:length===2?['g1f3','Nf3']:['b8c6','Nc6'];
            result={results:request.selected.map(rep=>({...catalog.find(r=>r.id===rep),theory:length<=3,alternative:false,deviation:length>3?4:0,
              comments:length?['Control the centre.\nKeep an eye on the d5 break.','<img src=x onerror=alert(1)> is plain source text.']:[],
              moves:[
                {uci:next[0],san:next[1],theory:true,alternative:length>0,own:length%2===0?rep==='white':rep!=='white',reason:'Source annotation',starting_comments:['Introduction to this variation <not markup>'],comments:['Develop naturally and prepare the centre.',rep==='white'?'A second file contributes another useful comment.':'Black’s perspective on this position.'],kind:length>0&&rep!=='white'?'alternative':'repertoire'},
                ...(length<2?[{uci:length===0?'d2d4':'e7e5',san:length===0?'d4':'e5',theory:false,alternative:false,own:false,kind:'analysis',reason:'Informational only; does not reactivate theory',comment:''}]:[])
              ]}))};
            if(window.__test.extensionMode) result={results:request.selected.map(rep=>{
              const path=request.history.join(' '), stored=additions[rep] || [], theory=length<=3 || stored.includes(path);
              const continuations=stored.filter(p=>p.startsWith(path+' ') && p.split(' ').length===length+1).map(p=>p.split(' ').at(-1));
              const missing=request.history.slice(3).filter((_,i)=>!stored.includes(request.history.slice(0,i+4).join(' '))).length;
              return {...catalog.find(r=>r.id===rep),theory,end_of_line:theory&&length>=3&&!continuations.length,
                can_add:!theory,add_count:missing,deviation:theory?0:4,comments:['Keep an eye on the d5 break.'],
                moves:(length<3?[next[0]]:continuations).map(uci=>({uci,san:uci==='b8c6'?'Nc6':uci,theory:true,deviation:0,comments:['Keep an eye on the d5 break.'],end_of_line:!stored.some(p=>p.startsWith(path+' '+uci+' '))}))};
            })};
            // The native position-book ABI includes normalized FENs on current and
            // projected child facts. Markers now intentionally use those position keys.
            result.results=result.results.map(rep=>({...rep,fen:request.fen,moves:rep.moves.map(move=>{
              const chess=new window.InstinctaZeroChessRules.Chess(request.fen+' 0 1');let played=null;
              try{played=chess.move({from:move.uci.slice(0,2),to:move.uci.slice(2,4),promotion:move.uci[4]})}catch{}
              return {...move,...(played?{position:{fen:played.after.split(' ').slice(0,4).join(' '),theory:move.theory,end_of_line:!!move.end_of_line,comments:move.comments,starting_comments:move.starting_comments}}:{})};
            })}));
          }
          setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,result),window.__test.delay);return id;
        },
        downloadRepertoires:()=>{const id='download-'+(++sequence);setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,window.__test.failDownload?{event:'error',message:'Connection interrupted. Saved copy unchanged.'}:{installed:true,repertoires:catalog,downloaded:true,undo:undoInfo()}),40);return id;}
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
    assert.match(await page.locator('.rep-comments').innerText(),/Before move/);
    assert.match(await page.locator('.rep-comments').innerText(),/Introduction to this variation <not markup>/);
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
    assert.equal(await page.locator('.rep-card').count(),5);
    await page.getByRole('checkbox',{name:/Ruy Lopez/}).tap();
    await page.getByRole('checkbox',{name:/Jobava London/}).tap();
    assert.equal(await page.locator('.rep-card.checked').count(),4);
    await page.screenshot({path:`${output}/five-repertoires-${width}.png`});
    const selection=await page.evaluate(()=>JSON.parse(localStorage.getItem('repertoires')).analysis);
    assert.deepEqual(selection,['white','black','ruy_lopez','jobava_london']);
    await page.locator('[data-action=settings]').tap();
    await page.waitForFunction(()=>window.__test.requests.some(r=>r.action==='lookup'&&r.selected.includes('ruy_lopez')&&r.selected.includes('jobava_london')));
    await page.reload(); await page.evaluate(() => window.InstinctaZero.setAnalysisActive(true));
    await page.locator('[data-rep-play=c7c5]').waitFor();
    await page.locator('[data-action=settings]').tap();
    assert.equal(await page.getByRole('checkbox',{name:/Ruy Lopez/}).getAttribute('aria-checked'),'true');
    assert.equal(await page.getByRole('checkbox',{name:/Jobava London/}).getAttribute('aria-checked'),'true');
    await page.getByRole('checkbox',{name:/Ruy Lopez/}).tap();
    await page.getByRole('checkbox',{name:/Jobava London/}).tap();
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
    await page.reload(); await page.evaluate(() => window.InstinctaZero.setAnalysisActive(true));
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
    await page.getByRole('img',{name:'Repertoire move',exact:true}).waitFor();
    assert.equal(await page.locator('.repertoire-book-marker').getAttribute('data-square'),'f3');
    assert.match(await page.locator('.game-title small').innerText(),/2\/2 repertoires/);
    await page.screenshot({path:`${output}/transposition-${width}.png`});
    await page.locator('[data-action=settings]').tap();
    assert.equal(await page.locator('.rep-card.checked').count(),2);
    await page.getByRole('checkbox',{name:/Tame/}).tap();
    await page.getByRole('checkbox',{name:/Taimanov/}).tap();
    await page.locator('[data-action=settings]').tap();
    await page.getByText('No repertoire selected.').waitFor();
    assert.equal(await page.locator('.repertoire-book-marker').isVisible(),false);
    await page.reload(); await page.evaluate(() => window.InstinctaZero.setAnalysisActive(true));
    await page.getByText('No repertoire selected.').waitFor();
    await page.getByRole('button',{name:'Choose repertoires',exact:true}).tap();
    await page.getByRole('checkbox',{name:/Queen/}).tap();
    await page.getByRole('button',{name:'How to use',exact:true}).tap();
    await page.screenshot({path:`${output}/guide-${width}.png`});
    assert.match(await page.locator('.rep-guide').innerText(),/book follows the board position, not your move order/);
    assert.doesNotMatch(await page.locator('.rep-guide').innerText(),/outlined book|history has already left/);
    await page.evaluate(()=>{window.__test.failDownload=true;});
    await page.waitForTimeout(50);
    const beforeFailure=await page.evaluate(()=>window.__test.requests.length);
    await page.getByRole('button',{name:/Update from PC/}).tap();
    await page.getByRole('alert').waitFor();
    assert.equal(await page.evaluate(()=>window.__test.requests.length),beforeFailure,'download error stays visible');
    assert.equal(await page.locator('.rep-card.checked').count(),1);
    // Terminal coverage -> a real board move -> add to one repertoire, then a whole line.
    await page.evaluate(()=>{window.__test.extensionMode=true;window.__test.failDownload=false;localStorage.setItem('extensionMode','true');});
    await page.getByRole('checkbox',{name:/Queen/}).tap();
    await page.getByRole('checkbox',{name:/Tame/}).tap();
    await page.getByRole('checkbox',{name:/Taimanov/}).tap();
    await page.getByRole('button',{name:/Update from PC/}).tap();
    await page.waitForFunction(()=>!document.querySelector('[data-rep-action=download]').disabled);
    await page.locator('[data-action=settings]').tap();
    await page.getByRole('img',{name:'Repertoire move · end of line',exact:true}).waitFor();
    assert.match(await page.locator('.rep-end').innerText(),/End of line/);
    await page.waitForTimeout(150);
    await page.screenshot({path:`${output}/end-of-line-${width}.png`});
    const playBoardMove=async(from,to)=>{
      const box=await page.locator('#board').boundingBox();
      for(const square of [from,to])await page.touchscreen.tap(box.x+(square.charCodeAt(0)-97+.5)*box.width/8,box.y+(8-Number(square[1])+.5)*box.height/8);
    };
    await playBoardMove('b8','c6');
    await page.getByRole('button',{name:'+ Add move to repertoire',exact:true}).tap();
    await page.screenshot({path:`${output}/choose-add-${width}.png`});
    await page.getByRole('button',{name:'Tame the Sicilian',exact:true}).tap();
    await page.waitForFunction(()=>window.__test.additions.white?.includes('e2e4 c7c5 g1f3 b8c6'));
    assert.equal(await page.evaluate(()=>window.__test.additions.black?.length || 0),0);
    await page.getByRole('img',{name:'Repertoire move · end of line',exact:true}).waitFor();
    await page.screenshot({path:`${output}/added-${width}.png`});
    await page.waitForFunction(()=>JSON.parse(window.__test.saved).cursor?.length===4);
    const boardBeforeUndo=await page.evaluate(()=>{const saved=JSON.parse(window.__test.saved);return {tree:saved.tree,cursor:saved.cursor,gameId:saved.gameId};});
    await page.getByRole('button',{name:'Undo last repertoire change',exact:true}).tap();
    await page.waitForFunction(()=>!window.__test.additions.white?.length);
    await page.getByText('Change undone · Tame the Sicilian',{exact:true}).waitFor();
    assert.equal(await page.locator('.repertoire-book-marker').isVisible(),false);
    assert.deepEqual(await page.evaluate(()=>{const saved=JSON.parse(window.__test.saved);return {tree:saved.tree,cursor:saved.cursor,gameId:saved.gameId};}),boardBeforeUndo);
    assert.equal(await page.locator('[data-rep-action=undo]').count(),0,'single undo is consumed');
    await page.getByRole('button',{name:'+ Add move to repertoire',exact:true}).tap();
    await page.getByRole('button',{name:'Tame the Sicilian',exact:true}).tap();
    await page.getByRole('img',{name:'Repertoire move · end of line',exact:true}).waitFor();
    const savedEdits=await page.evaluate(()=>window.__test.edits.length);
    await page.getByRole('button',{name:'+ Add move to repertoire',exact:true}).tap();
    await page.waitForFunction(count=>window.__test.edits.length===count+1,savedEdits);
    assert.equal(await page.evaluate(()=>window.__test.edits.at(-1).id),'black','one remaining candidate extends directly');
    await page.getByRole('button',{name:'Undo last repertoire change',exact:true}).tap();
    await page.waitForFunction(()=>!window.__test.additions.black?.length);
    await playBoardMove('f1','b5');await playBoardMove('a7','a6');
    await page.getByRole('button',{name:'+ Add line to repertoire',exact:true}).tap();
    await page.getByRole('button',{name:'Tame the Sicilian',exact:true}).tap();
    await page.waitForFunction(()=>window.__test.additions.white?.length===3);
    await page.getByRole('img',{name:'Repertoire move · end of line',exact:true}).waitFor();
    await page.waitForFunction(()=>JSON.parse(window.__test.saved).cursor?.length===6);
    await page.reload(); await page.evaluate(() => window.InstinctaZero.setAnalysisActive(true));
    await page.getByRole('img',{name:'Repertoire move · end of line',exact:true}).waitFor();
    await page.locator('[data-action=prev]').tap();
    await page.locator('[data-rep-play=a7a6]').waitFor();
    await page.evaluate(()=>{window.__test.delay=2000;});
    const start=Date.now();await page.locator('[data-action=next]').tap();
    assert.equal(await page.getByRole('img',{name:'Repertoire move · end of line',exact:true}).isVisible(),true);
    assert.ok(Date.now()-start<1000,'known badge must precede the delayed native reply');
    await page.evaluate(()=>{window.__test.delay=12;});
    await page.locator('[data-action=settings]').tap();
    await page.getByRole('button',{name:'Undo last repertoire change',exact:true}).waitFor();
    assert.match(await page.locator('.rep-undo-setting').innerText(),/Added line · Tame the Sicilian/);
    await page.screenshot({path:`${output}/undo-settings-${width}.png`});
    await page.getByRole('checkbox',{name:/Tame/}).tap();
    const selectionBeforeUndo=await page.evaluate(()=>localStorage.getItem('repertoires'));
    await page.getByRole('button',{name:'Undo last repertoire change',exact:true}).tap();
    await page.waitForFunction(()=>window.__test.additions.white?.length===1);
    await page.getByText('Change undone · Tame the Sicilian',{exact:true}).waitFor();
    assert.equal(await page.evaluate(()=>localStorage.getItem('repertoires')),selectionBeforeUndo,'undo never rolls back selected repertoires');
    assert.equal(await page.evaluate(()=>JSON.parse(window.__test.saved).cursor.length),6,'undo leaves the analyzed line in place');
    assert.equal(await page.locator('[data-rep-action=undo]').count(),0);
    await page.getByRole('checkbox',{name:/Tame/}).tap();
    await page.locator('[data-action=settings]').tap();
    await page.locator('[data-action=prev]').tap();await page.locator('[data-action=prev]').tap();
    await page.getByRole('img',{name:'Repertoire move · end of line',exact:true}).waitFor();
    assert.equal(await page.locator('.repertoire-book-marker').getAttribute('data-square'),'c6','pre-existing repertoire prefix survives undo');
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    assert.deepEqual(errors,[]);
    console.log(`${width}×${height}: comments, markers, additions, immediate/restarted undo, unchanged board/selection and restored terminal coverage passed`);
    await page.close();
  }
} finally {await browser.close();server.close();}
