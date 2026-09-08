import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT||'/tmp/instinctazero-position-editor-preview';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const f=req.url==='/'?'index.html':req.url.slice(1);if(f.includes('..'))throw Error();const data=await readFile(new URL(f,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[f.split('.').at(-1)]||'application/octet-stream'}).end(data);}catch{res.writeHead(404).end();}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,...(process.env.PHONE_PREVIEW_CHROMIUM?{executablePath:process.env.PHONE_PREVIEW_CHROMIUM}:{})});
try{for(const [width,height] of [[360,640],[390,780],[412,844]]){
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  let seq=0;window.__test={requests:[],clipboard:'4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 17'};
  window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":true}',saveUiSettings:()=>{},cancelAnalysis:()=>{},cancelRepertoireLookup:()=>{},
   getStudyState:()=>localStorage.getItem(localStorage.getItem('positionActive')==='true'?'position':'source')||'{}',
   getSourceStudyState:()=>localStorage.getItem('source')||'{}',
   saveStudyState:raw=>{const position=!!JSON.parse(raw).editedPosition;localStorage.setItem(position?'position':'source',raw);localStorage.setItem('positionActive',String(position));return true},
   getEditorDraft:()=>localStorage.getItem('draft')||'{}',saveEditorDraft:raw=>{localStorage.setItem('draft',raw);return true},
   pastePositionFen:()=>window.__test.clipboard,copyPositionFen:fen=>{window.__test.clipboard=fen;return true},
   getRepertoireSettings:()=>'{"analysis":["r"],"_selected":["r"]}',saveRepertoireSettings:()=>{},
   requestRepertoire:raw=>{const r=JSON.parse(raw),id='rep'+ ++seq;window.__test.requests.push(r);setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,r.action==='catalog'?{installed:true,repertoires:[{id:'r',name:'My repertoire',side:'white'}]}:{results:[{id:'r',name:'My repertoire',side:'white',fen:r.fen,theory:false,comments:[],moves:[]}]}),0);return id},
   startAnalysis:raw=>{window.__test.requests.push({action:'engine',...JSON.parse(raw)});return 'engine'+ ++seq},
   requestExplorer:raw=>{window.__test.requests.push({action:'book',...JSON.parse(raw)});return 'book'+ ++seq}
  };
 });
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>!!window.InstinctaZero?.openBoardEditor);
 await page.evaluate(()=>window.InstinctaZero.loadArchivedGame({id:'Editor01',moves:[{uci:'e2e4'},{uci:'e7e5'}],white:{name:'Original game'},black:{name:'Black'}}));
 await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));
 await page.getByRole('button',{name:'More',exact:true}).tap();await page.getByRole('button',{name:'Board editor',exact:true}).tap();
 const original=await page.evaluate(()=>localStorage.getItem('source'));
 assert.equal(await page.locator('#app').isVisible(),false);await page.getByRole('button',{name:'Clear',exact:true}).tap();
 assert.equal(await page.getByRole('button',{name:'Analyse position',exact:true}).isDisabled(),true);
 for(const [piece,square] of [['White king','e1'],['Black king','e8'],['White rook','a1']]){await page.getByRole('button',{name:piece,exact:true}).tap();await page.locator('.pe-squares').getByRole('button',{name:square,exact:true}).tap();}
 await page.getByRole('button',{name:'Move',exact:true}).tap();
 const editorBoard=await page.locator('.pe-board').boundingBox();
 const cdp=await page.context().newCDPSession(page),y=editorBoard.y+editorBoard.height*15/16;
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:editorBoard.x+editorBoard.width/16,y}]});
 for(let i=1;i<=5;i++)await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:editorBoard.x+editorBoard.width*(1+2*i/5)/16,y}]});
 await page.waitForTimeout(40);await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
 await page.waitForFunction(()=>JSON.parse(localStorage.getItem('draft')).fen.startsWith('4k3/8/8/8/8/8/8/1R2K3'));
 await page.getByRole('button',{name:'Undo',exact:true}).tap();
 assert.match(await page.evaluate(()=>JSON.parse(localStorage.getItem('draft')).fen),/R3K3/);
 await page.getByRole('button',{name:'Erase',exact:true}).tap();await page.locator('.pe-squares').getByRole('button',{name:'a1',exact:true}).tap();
 await page.getByRole('button',{name:'Reset',exact:true}).tap();
 await page.screenshot({path:`${output}/pieces-${width}.png`});
 await page.getByRole('button',{name:'State',exact:true}).tap();await page.getByRole('button',{name:'Black',exact:true}).tap();
 assert.equal(await page.getByRole('button',{name:'White kingside castling',exact:true}).getAttribute('aria-pressed'),'true');
 await page.getByRole('button',{name:'White kingside castling',exact:true}).tap();
 await page.screenshot({path:`${output}/state-${width}.png`});
 await page.getByRole('button',{name:'FEN',exact:true}).tap();const input=page.getByRole('textbox',{name:'Position FEN',exact:true});
 await input.fill('invalid fen');await page.getByRole('button',{name:'Use FEN',exact:true}).tap();assert.match(await page.locator('.pe-status').innerText(),/FEN needs/);
 await page.getByRole('button',{name:'Paste',exact:true}).tap();await page.getByRole('button',{name:'Use FEN',exact:true}).tap();
 await page.getByRole('button',{name:'Copy FEN',exact:true}).tap();assert.equal(await page.evaluate(()=>window.__test.clipboard),'4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 17');
 await page.screenshot({path:`${output}/fen-${width}.png`});
 await page.setViewportSize({width,height:Math.max(360,height-300)});await input.focus();await page.screenshot({path:`${output}/keyboard-${width}.png`});await page.setViewportSize({width,height});
 await page.getByRole('button',{name:'State',exact:true}).tap();assert.equal(await page.getByRole('combobox',{name:'En-passant square'}).inputValue(),'d6');
 await page.getByRole('button',{name:'White',exact:true}).tap();assert.equal(await page.getByRole('combobox',{name:'En-passant square'}).inputValue(),'d6','reselecting the same turn must not clear EP');
 await page.getByRole('button',{name:'Flip editor board',exact:true}).tap();await page.getByRole('button',{name:'Analyse position',exact:true}).tap();
 await page.waitForFunction(()=>window.__test.requests.some(r=>r.action==='engine'&&r.initial_fen==='4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 17'&&!r.game_id));
 await page.waitForFunction(()=>window.__test.requests.some(r=>r.action==='lookup'&&r.root==='4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 17'));
 assert.equal(await page.locator('#board-wrap.orientation-black').count(),1);assert.equal(await page.evaluate(()=>localStorage.getItem('source')),original);
 const board=await page.locator('#board').boundingBox();const sq=key=>({x:board.x+(7-(key.charCodeAt(0)-97)+.5)*board.width/8,y:board.y+(Number(key[1])-.5)*board.height/8});
 for(const key of ['e5','d6']){const p=sq(key);await page.touchscreen.tap(p.x,p.y);}
 await page.waitForFunction(()=>JSON.parse(localStorage.getItem('position')).cursor.join(',')==='e5d6');
 await page.getByRole('button',{name:'Book',exact:true}).tap();await page.waitForFunction(()=>window.__test.requests.some(r=>r.action==='book'&&r.initial_fen&&r.history[0]==='e5d6'&&!r.game_id));
 assert.equal(await page.evaluate(()=>localStorage.getItem('source')),original);
 await page.reload();await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));
 assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('position')).initialFen),'4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 17');
 await page.getByRole('button',{name:'More',exact:true}).tap();await page.getByRole('button',{name:'New / reset',exact:true}).tap();
 assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('position')).editedPosition),true);
 assert.equal(await page.evaluate(()=>localStorage.getItem('source')),original,'Reset in position workspace must not reset the original game');
 await page.getByRole('button',{name:'More',exact:true}).tap();await page.getByRole('button',{name:'Return to saved board',exact:true}).tap();
 assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('source')).gameId),'Editor01');assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('source')).cursor.join(',')),'e2e4,e7e5');
 await page.evaluate(()=>window.InstinctaZero.openBoardEditor());await page.getByRole('button',{name:'FEN',exact:true}).tap();assert.equal(await input.inputValue(),'4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 17');
 await page.evaluate(()=>window.InstinctaZero.handleAndroidBack());assert.equal(await page.locator('#position-editor').isVisible(),false);assert.equal(await page.locator('#app').isVisible(),true);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
 console.log(`${width}×${height}: placement/drag/erase, Undo/reset, castling/EP/counters, clipboard, custom analysis/book, source preservation and restart passed`);await page.close();
}}finally{await browser.close();await new Promise(r=>server.close(r))}
