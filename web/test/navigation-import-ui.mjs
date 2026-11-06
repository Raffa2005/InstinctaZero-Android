import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-navigation-preview';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const file=req.url==='/'?'index.html':req.url.slice(1);if(file.includes('..'))throw Error();const data=await readFile(new URL(file,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[file.split('.').at(-1)]||'application/octet-stream'}).end(data)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,...(process.env.PHONE_PREVIEW_CHROMIUM?{executablePath:process.env.PHONE_PREVIEW_CHROMIUM}:{})});
const result=[];
try{for(const [width,height] of [[360,640],[390,780],[412,844]]) {
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const cdp=await page.context().newCDPSession(page);await cdp.send('Emulation.setCPUThrottlingRate',{rate:4});
 await page.addInitScript(()=>{
   let seq=0;window.__b={saves:[],analysis:[],left:[],exports:[],rankCalls:0,privacy:false};
   window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":true}',getRepertoireSettings:()=>'{}',
     getStudyState:()=>localStorage.getItem('study') || '{}',saveStudyState:raw=>{localStorage.setItem('study',raw);window.__b.saves.push(raw);return true},
     startAnalysis:raw=>{const id='engine-'+ ++seq;window.__b.analysis.push({id,...JSON.parse(raw)});return id},cancelAnalysis:()=>{},
     getDisplayPrivacy:()=>JSON.stringify({enabled:window.__b.privacy,names:['PrivateAccount']}),
     leaveGame:source=>{window.__b.left.push(source);window.InstinctaZero.setAnalysisActive(false)},leaveAnalysis:()=>window.__b.left.push('home'),
     requestExplorer:raw=>{const id='book-'+ ++seq;setTimeout(()=>window.InstinctaZero.onNativeExplorer(id,{totals:{games:3,white:2,draws:1,black:0},moves:[{uci:'e2e4',san:'e4',games:3,white:2,draws:1,black:0}],top_games:[{id:'abcdefgh',white:{name:'PrivateAccount',rating:2400},black:{name:'Other',rating:2300},winner:'white',year:2024}]}),15);return id;},
     requestExplorerGame:raw=>{const id='game-'+ ++seq;setTimeout(()=>window.InstinctaZero.onNativeExplorerGame(id,{pgn:'[White "PrivateAccount"]\n[Black "Other"]\n[Result "1-0"]\n\n1.e4 {Explorer comment} e5 1-0'}),20);return id;},
     pasteGameText:()=>JSON.stringify({text:window.__b.clipboard || ''}),exportPgn:(text,name)=>window.__b.exports.push({text,name})};
 });
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>!!window.InstinctaZero && !document.documentElement.classList.contains('board-assets-loading'));
 await page.evaluate(()=>{window.InstinctaZero.setAnalysisActive(true);const rank=window.InstinctaClickChess.ranker.rank.bind(window.InstinctaClickChess.ranker);window.InstinctaClickChess.ranker.rank=(...args)=>{window.__b.rankCalls++;return rank(...args)}});
 const stored=()=>page.evaluate(()=>JSON.parse(localStorage.getItem('study') || '{}'));
 const tap=async square=>{const p=await page.evaluate(square=>{const b=document.querySelector('#board').getBoundingClientRect(),black=document.querySelector('.orientation-black');const f=square.charCodeAt(0)-97,r=Number(square[1])-1;return {x:b.x+(black?7-f:f)*b.width/8+b.width/16,y:b.y+(black?r:7-r)*b.height/8+b.height/16}},square);await page.touchscreen.tap(p.x,p.y)};
 await page.waitForFunction(()=>window.__b.analysis.length>0);
 await page.evaluate(()=>window.InstinctaZero.onNativeAnalysis(window.__b.analysis.at(-1).id,{event:'lc0',data:{search_phase:'ready',move_stats:[{uci:'c2c3',visits:80,prior:.8},{uci:'b1c3',visits:2,prior:.01}],lines:[{pv:['c2c3','d7d5'],san:['c3','d5']}]}}));
 await tap('c3');assert.deepEqual((await stored()).cursor,['c2c3']);assert.equal(await page.evaluate(()=>window.__b.rankCalls),0);
 await tap('c3');assert.deepEqual((await stored()).cursor,['c2c3'],'tap alone does not cycle');
 await page.getByRole('button',{name:'Previous move',exact:true}).tap();assert.deepEqual((await stored()).cursor,[]);assert.equal((await stored()).tree.length,0);
 await tap('c3');assert.deepEqual((await stored()).cursor,['b1c3']);assert.equal((await stored()).tree.length,1);
 await page.getByRole('button',{name:'Previous move',exact:true}).tap();
 await page.getByRole('button',{name:'Flip board',exact:true}).tap(); // outside control clears cycle, cached engine remains
 await tap('c3');assert.deepEqual((await stored()).cursor,['c2c3']);assert.equal(await page.evaluate(()=>window.__b.rankCalls),0,'position-matching cache used');
 await page.getByRole('button',{name:'Previous move',exact:true}).tap();
 await page.getByRole('button',{name:'Flip board',exact:true}).tap();
 await tap('e4');assert.deepEqual((await stored()).cursor,['e2e4']);assert.equal(await page.evaluate(()=>window.__b.rankCalls),1,'uncovered destination falls back locally');
 const analysis=await stored();
 await page.evaluate(()=>window.InstinctaZero.loadArchivedGame({id:'Archived',moves:[{uci:'d2d4'},{uci:'d7d5'}],white:{name:'PrivateAccount'},black:{name:'Other'},mobile_orientation:'black'}));
 assert.equal(await page.locator('[data-workspace]').count(),2);assert.equal(await page.locator('.orientation-black').count(),1);
 await tap('c4');assert.deepEqual(await stored(),analysis,'temporary edits never touch saved Analysis');
 await page.evaluate(()=>window.InstinctaZero.persistStudy());assert.deepEqual(await stored(),analysis);
 await page.locator('.back').tap();assert.equal(await page.evaluate(()=>window.__b.left.at(-1)),'games','screen Back returns to Games');
 await page.evaluate(()=>{window.InstinctaZero.showPersistentAnalysis();window.InstinctaZero.setAnalysisActive(true)});
 assert.deepEqual((await stored()).tree,analysis.tree);assert.deepEqual((await stored()).cursor,analysis.cursor);
 await page.getByRole('button',{name:'Previous move',exact:true}).tap(); // ordinary navigation after workspace switch preserves e4
 assert.equal((await stored()).tree.length,1);assert.deepEqual((await stored()).cursor,[]);
 await page.getByRole('button',{name:'Book',exact:true}).tap();await page.locator('[data-explorer-game]').waitFor();
 await page.locator('[data-explorer-game]').tap();await page.waitForFunction(()=>document.querySelectorAll('[data-workspace]').length===3);
 assert.match(await page.locator('#panel').innerText(),/Explorer comment/);await page.screenshot({path:`${output}/game-tabs-${width}.png`});
 await page.locator('.back').tap();assert.equal(await page.locator('[data-tab="book"].selected').count(),1);assert.equal(await page.locator('[data-explorer-game]').count(),1);
 const beforeImport=await stored();
 const document='[Event "German"]\n[White "PrivateAccount"]\n[Black "Other"]\n\n1.e4 e5 2.Sf3 {A useful comment} Sc6 (2...Sf6) *\n\n[Event "Second"]\n\n1.d4 d5 *';
 await page.evaluate(text=>window.InstinctaZero.onNativeGameText({name:'games.pgn',text}),document);
 await page.locator('[data-game="0"]').waitFor();assert.equal(await page.locator('[data-game]').count(),2);
 await page.locator('[data-game="0"]').tap();await page.locator('[data-open]').waitFor();assert.match(await page.locator('.import-report').innerText(),/notation repairs/);
 await page.locator('[data-open]').tap();assert.match(await page.locator('#panel').innerText(),/A useful comment/);assert.equal(await page.locator('.variation').count(),2);
 await page.getByRole('button',{name:'More',exact:true}).tap();await page.locator('[data-export-original]').tap();assert.equal(await page.evaluate(()=>window.__b.exports.at(-1).text),document.slice(0,document.indexOf('[Event "Second"]')));
 await page.getByRole('button',{name:'More',exact:true}).tap();await page.locator('[data-export]').tap();assert.match(await page.evaluate(()=>window.__b.exports.at(-1).text),/A useful comment/);
 assert.deepEqual(await stored(),beforeImport);
 await page.locator('.back').tap();await page.locator('[data-game="1"]').waitFor();await page.screenshot({path:`${output}/import-${width}.png`});
 await page.evaluate(()=>{window.__b.clipboard='7k/8/8/8/8/8/8/K7 w - - 0 1';});await page.locator('[data-paste]').tap();await page.getByRole('button',{name:'Open temporary position',exact:true}).tap();
 await tap('b2');assert.deepEqual(await stored(),beforeImport);assert.equal(await page.locator('[data-workspace]').count(),5);
 // Privacy applies to every temporary title, imported comment and explorer game row.
 await page.evaluate(()=>{window.__b.privacy=true;window.InstinctaZero.refreshDisplayPrivacy()});
 assert.doesNotMatch(await page.locator('body').innerText(),/PrivateAccount/);
 await page.reload();await page.waitForFunction(()=>!!window.InstinctaZero && !document.documentElement.classList.contains('board-assets-loading'));
 assert.equal(await page.locator('[data-workspace]').count(),1,'session game tabs disappear on restart');assert.deepEqual((await stored()).tree,beforeImport.tree);assert.deepEqual((await stored()).cursor,beforeImport.cursor);
 // The same Previous correction must preserve a pre-existing nonleaf.
 await page.evaluate(()=>{window.InstinctaZeroNative.saveStudyState=()=>true;localStorage.setItem('study',JSON.stringify({v:1,initialFen:new window.InstinctaZeroChessRules.Chess().fen(),title:'Local analysis',tab:'moves',tree:[{u:'b1c3',c:[{u:'e7e5',c:[]}]}],cursor:[]}))});
 await page.reload();await page.waitForFunction(()=>!!window.InstinctaZero && !document.documentElement.classList.contains('board-assets-loading'));
 await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));await page.waitForFunction(()=>window.__b.analysis.length>0);
 await page.evaluate(()=>window.InstinctaZero.onNativeAnalysis(window.__b.analysis.at(-1).id,{data:{search_phase:'ready',move_stats:[{uci:'b1c3',visits:80},{uci:'c2c3',visits:2}],lines:[{pv:['b1c3','e7e5'],san:['Nc3','e5']}]}}));
 await tap('c3');await page.getByRole('button',{name:'Previous move',exact:true}).tap();
 assert.equal((await stored()).tree[0].c[0].u,'e7e5');await tap('c3');assert.deepEqual((await stored()).cursor,['c2c3']);
 await page.getByRole('button',{name:'Previous move',exact:true}).tap();assert.equal((await stored()).tree.length,1);assert.equal((await stored()).tree[0].c[0].u,'e7e5');
 await page.getByRole('button',{name:'Next move',exact:true}).tap();assert.deepEqual((await stored()).cursor,['b1c3'],'ordinary forward still follows canonical mainline');
 assert.deepEqual(errors,[]);result.push({width,height,checks:'engine/back correction; isolated archive/explorer/import/FEN; source Back; comments/variations/export; privacy/restart'});await page.close();
 }}finally{await browser.close();server.close();}
await writeFile(`${output}/results.json`,JSON.stringify(result,null,2));console.log(JSON.stringify(result));
