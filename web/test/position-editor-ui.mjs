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
 const cdp=await page.context().newCDPSession(page);
 const square=async key=>{const box=await page.locator('.pe-board').boundingBox(),black=await page.locator('.pe-board-wrap.orientation-black').count();return {x:box.x+(black?7-(key.charCodeAt(0)-97)+.5:key.charCodeAt(0)-97+.5)*box.width/8,y:box.y+(black?Number(key[1])-.5:8-Number(key[1])+.5)*box.height/8};};
 const tap=async key=>{const point=await square(key);await page.touchscreen.tap(point.x,point.y);};
 const drag=async(from,to,cancel=false,fast=false)=>{const a=await square(from),b=await square(to);await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[a]});for(let i=1;i<=5;i++)await cdp.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:a.x+(b.x-a.x)*i/5,y:a.y+(b.y-a.y)*i/5}]});if(!fast)await page.waitForTimeout(40);await cdp.send('Input.dispatchTouchEvent',{type:cancel?'touchCancel':'touchEnd',touchPoints:[]});await page.waitForTimeout(30);};
 const draft=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('draft')).fen);
 const pieces=()=>page.evaluate(()=>window.InstinctaZeroPosition.parse(JSON.parse(localStorage.getItem('draft')).fen).pieces);
 const choose=async piece=>{const button=page.getByRole('button',{name:piece,exact:true});if(await button.getAttribute('aria-pressed')!=='true')await button.tap();};
 await page.evaluate(()=>window.InstinctaZero.loadArchivedGame({id:'Editor01',moves:[{uci:'e2e4'},{uci:'e7e5'}],white:{name:'Original game'},black:{name:'Black'}}));
 await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));
 await page.getByRole('button',{name:'More',exact:true}).tap();await page.getByRole('button',{name:'Board editor',exact:true}).tap();
 const original=await page.evaluate(()=>localStorage.getItem('source'));
 assert.equal(await page.locator('#app').isVisible(),false);await page.getByRole('button',{name:'Clear',exact:true}).tap();
 assert.equal(await page.getByRole('button',{name:'Analyse position',exact:true}).isDisabled(),true);
 for(const [piece,key] of [['White king','e1'],['Black king','e8'],['White rook','a1']]){await choose(piece);await tap(key);}
 await drag('a1','b1'); // No Move mode: palette selection must not block a real drag.
 await page.waitForFunction(()=>JSON.parse(localStorage.getItem('draft')).fen.startsWith('4k3/8/8/8/8/8/8/1R2K3'));
 assert.equal(await page.getByRole('button',{name:'White rook',exact:true}).getAttribute('aria-pressed'),'true');
 await tap('b1');assert.equal((await pieces()).b1,undefined,'identical palette piece toggles off');
 await page.getByRole('button',{name:'Undo',exact:true}).tap();assert.equal((await pieces()).b1,'R');
 await page.getByRole('button',{name:'White rook',exact:true}).tap();
 assert.equal(await page.getByRole('button',{name:'Erase',exact:true}).getAttribute('aria-pressed'),'true','deselect enters Erase');
 await drag('b1','c1');assert.equal((await pieces()).c1,'R','drag always moves, even in Erase');assert.equal((await pieces()).b1,undefined);
 await tap('c1');assert.equal((await pieces()).c1,undefined);await page.getByRole('button',{name:'Undo',exact:true}).tap();
 await page.getByRole('button',{name:'Move',exact:true}).tap();await tap('c1');await drag('e8','d8');
 assert.equal((await pieces()).d8,'k');assert.equal((await pieces()).c1,'R','previous board selection must not hijack another piece drag');
 await page.getByRole('button',{name:'Undo',exact:true}).tap();
 const beforeCancel=await draft();await drag('c1','d2',true);assert.equal(await draft(),beforeCancel,'cancelled drag is not an edit');
 await tap('c1');await tap('c2');assert.equal((await pieces()).c2,'R','Move still supports tap/tap');await page.getByRole('button',{name:'Undo',exact:true}).tap();
 await choose('White king');await tap('e1');assert.equal((await pieces()).e1,undefined);assert.equal(await page.getByRole('button',{name:'Analyse position',exact:true}).isDisabled(),true);await page.getByRole('button',{name:'Undo',exact:true}).tap();
 const beforeReverse=await draft(),beforePieces=await pieces();await page.getByRole('button',{name:'Reverse coordinates',exact:true}).tap();
 assert.deepEqual(await pieces(),Object.fromEntries(Object.entries(beforePieces).map(([key,p])=>[String.fromCharCode(201-key.charCodeAt(0))+(9-Number(key[1])),p])));
 assert.equal(await page.locator('.pe-board-wrap.orientation-white').count(),1,'Reverse changes the position, not the view');
 assert.match(await page.locator('.pe-status').innerText(),/rotated 180/);
 await page.screenshot({path:`${output}/reverse-${width}.png`});await page.getByRole('button',{name:'Undo',exact:true}).tap();assert.equal(await draft(),beforeReverse);
 const files=page.locator('.pe-board li-coords.files li-coord'),ranks=page.locator('.pe-board li-coords.ranks li-coord');
 assert.equal(await files.count(),8);assert.equal(await ranks.count(),8);assert.equal(await files.first().isVisible(),true);assert.equal(await ranks.first().isVisible(),true);
 assert.ok((await files.first().boundingBox()).x<(await files.last().boundingBox()).x);
 assert.ok((await ranks.first().boundingBox()).y>(await ranks.last().boundingBox()).y);
 await page.getByRole('button',{name:'Flip editor board',exact:true}).tap();assert.equal(await draft(),beforeReverse,'Flip view must not edit FEN');
 assert.ok((await files.first().boundingBox()).x>(await files.last().boundingBox()).x);assert.ok((await ranks.first().boundingBox()).y<(await ranks.last().boundingBox()).y);
 await choose('Black knight');await drag('c1','c2');assert.equal((await pieces()).c2,'R','palette drag works from Black view');await tap('c2');assert.equal((await pieces()).c2,'n','a different colour/type replaces rather than erases');await tap('c2');assert.equal((await pieces()).c2,undefined);
 await page.screenshot({path:`${output}/black-${width}.png`});await page.getByRole('button',{name:'Flip editor board',exact:true}).tap();
 await page.getByRole('button',{name:'Reset',exact:true}).tap();
 await choose('White pawn');await drag('a1','h1',false,true);assert.equal((await pieces()).a1,undefined);assert.equal((await pieces()).h1,'R','quick occupied-square drag uses the piece, never the palette');await page.getByRole('button',{name:'Undo',exact:true}).tap();
 const beforeEmptyDrag=await draft();await drag('d4','e4');assert.equal(await draft(),beforeEmptyDrag,'dragging empty space must not paint');
 const origin=await square('a1'),other=await square('b1');
 await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...origin,id:1}]});await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...origin,id:1},{...other,id:2}]});await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
 assert.equal(await draft(),beforeEmptyDrag,'multitouch cancels instead of editing');
 const outsideBox=await page.getByRole('button',{name:'White knight',exact:true}).boundingBox(),outside={x:outsideBox.x+outsideBox.width/2,y:outsideBox.y+outsideBox.height/2,id:2};
 for(const releasedFirst of [outside,{...origin,id:1}]){
  await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...origin,id:1}]});await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{...origin,id:1},outside]});await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[releasedFirst]});await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
  assert.equal(await draft(),beforeEmptyDrag,'a second finger outside the board must also cancel, regardless of release order');
 }
 await choose('White rook');await tap('a1');assert.equal((await pieces()).a1,undefined);
 await page.getByRole('button',{name:'Undo',exact:true}).tap();
 assert.equal((await pieces()).a1,'R');
 await page.getByRole('button',{name:'Erase',exact:true}).tap();await tap('a1');
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
 console.log(`${width}×${height}: drag in every mode, toggle/deselect, cancellation, coordinates/reverse/flip, Undo, FEN, analysis and source preservation/restart passed`);await page.close();
}}finally{await browser.close();await new Promise(r=>server.close(r))}
