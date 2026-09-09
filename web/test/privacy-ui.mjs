import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT||'/tmp/instinctazero-privacy-preview';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{const f=req.url==='/'?'index.html':req.url.slice(1);if(f.includes('..'))throw Error();const body=await readFile(new URL(f,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[f.split('.').at(-1)]||'application/octet-stream'}).end(body)}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,...(process.env.PHONE_PREVIEW_CHROMIUM?{executablePath:process.env.PHONE_PREVIEW_CHROMIUM}:{})});
try{for(const [width,height] of [[360,640],[390,780],[412,844]]){
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  let seq=0;window.__test={requests:[],mutations:[],record:true,fail:false,privacy:localStorage.getItem('privacy')!=='false',saved:localStorage.getItem('study')||'{}'};
  const secret=/SecretOwner|SecondAccount|RivalName|HiddenHandle/i;
  // Capture every inserted/changed text, not merely the final screenshot; scripts/data are not presentation.
  new MutationObserver(records=>{if(!window.__test.record)return;for(const r of records){
   const scan=node=>{if(node.nodeType===3){if(node.parentElement?.closest('script,style'))return;const s=node.textContent;if(secret.test(s))window.__test.mutations.push(s)}else if(node.nodeType===1){if(node.matches('script,style'))return;for(const n of node.childNodes)scan(n);for(const k of ['title','aria-label','value']){const s=node.getAttribute(k)||'';if(secret.test(s))window.__test.mutations.push(s)}}};
   if(r.type==='childList')for(const n of r.addedNodes)scan(n);else scan(r.target);
  }}).observe(document,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['title','aria-label','value']});
  const emit=data=>{const id='rep'+ ++seq;setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,JSON.stringify(data)),0);return id};
  const note='SecretOwner prepared this line. https://lichess.org/@/SecretOwner\n[White "HiddenHandle"]\n[Black "RivalName"]';
  window.InstinctaZeroNative={getDisplayPrivacy:()=>{if(window.__test.fail)throw Error('offline');return JSON.stringify({enabled:window.__test.privacy,names:['SecretOwner','SecondAccount']})},
   getUiSettings:()=>'{"leelaEnabled":true}',saveUiSettings:()=>{},cancelAnalysis:()=>{},cancelRepertoireLookup:()=>{},
   getStudyState:()=>window.__test.saved,saveStudyState:raw=>{window.__test.saved=raw;localStorage.setItem('study',raw)},
   getRepertoireSettings:()=>'{"analysis":["rep"],"_selected":["rep"]}',saveRepertoireSettings:()=>{},
   startAnalysis:raw=>{const id='engine'+ ++seq;window.__test.engine=id;window.__test.requests.push(JSON.parse(raw));return id},
   requestExplorer:()=>{const id='book'+ ++seq;setTimeout(()=>window.InstinctaZero.onNativeExplorer(id,{event:'error',message:'HiddenHandle https://lichess.org/SecretOwner'}),0);return id},
   requestRepertoire:raw=>{const r=JSON.parse(raw);window.__test.requests.push(r);
    if(r.action==='catalog')return emit({installed:true,repertoires:[{id:'rep',name:'SecretOwner English',side:'black'}]});
    if(r.action==='edit'){window.__test.edit=r;return emit({saved:true})}
    return emit({results:[{id:'rep',name:'SecretOwner English',side:'black',fen:r.fen,known:true,theory:true,comments:[note],moves:[]}]});
   }
  };
  window.__game=black=>({id:black?'Private2':'Private1',white:{name:black?'RivalName':'SecretOwner'},black:{name:black?'SecretOwner':'RivalName'},mobile_orientation:black?'black':'white',moves:[...Array.from({length:160},(_,i)=>({uci:['g1f3','g8f6','f3g1','f6g8'][i%4]}))]});
 });
 const noLeak=async()=>{await page.waitForTimeout(20);assert.deepEqual(await page.evaluate(()=>window.__test.mutations),[]);assert.doesNotMatch(await page.locator('body').innerText(),/SecretOwner|SecondAccount|RivalName|HiddenHandle/i)};
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>!!window.InstinctaZero?.loadArchivedGame);
 for(const black of [false,true]){
  await page.evaluate(async black=>{const pending=window.InstinctaZero.loadArchivedGame(window.__game(black));window.__test.loading=document.querySelector('[data-study-title]').textContent;await pending},black);
  assert.equal(await page.evaluate(()=>window.__test.loading),black?'Opponent – Player':'Player – Opponent');
  assert.equal(await page.locator('[data-study-title]').innerText(),black?'Opponent – Player':'Player – Opponent');
  assert.equal(await page.locator('#board-wrap.orientation-'+(black?'black':'white')).count(),1);await noLeak();
 }
 await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));await page.waitForFunction(()=>!!window.__test.engine);
 await page.getByRole('button',{name:'Engine',exact:true}).tap();
 await page.evaluate(()=>window.InstinctaZero.onNativeAnalysis(window.__test.engine,{event:'error',message:'SecretOwner HiddenHandle failed'}));
 assert.match(await page.locator('#panel').innerText(),/Leela unavailable/);await noLeak();
 await page.getByRole('button',{name:'Book',exact:true}).tap();await page.waitForFunction(()=>document.querySelector('#panel').textContent.includes('Opening book unavailable'));await noLeak();
 await page.getByRole('button',{name:'Repertoires',exact:true}).tap();await page.getByRole('button',{name:'Comment on played move',exact:true}).waitFor();await noLeak();
 await page.screenshot({path:`${output}/private-analysis-${width}.png`});
 const rawBefore=await page.evaluate(()=>window.__test.saved);assert.match(rawBefore,/SecretOwner/);
 await page.reload();await page.evaluate(()=>window.InstinctaZero.setAnalysisActive(true));await page.getByRole('button',{name:'Comment on played move',exact:true}).waitFor();await noLeak();
 await page.evaluate(()=>window.InstinctaZero.onNativeConnectionState());await noLeak();
 await page.getByRole('button',{name:'Comment on played move',exact:true}).tap();await page.getByRole('button',{name:'Edit comment',exact:true}).tap();
 assert.equal(await page.getByRole('textbox',{name:'Repertoire comment'}).count(),0);await noLeak();
 await page.screenshot({path:`${output}/private-reveal-${width}.png`});
 await page.evaluate(()=>window.__test.record=false);await page.getByRole('button',{name:'Show original text (reveals identity)',exact:true}).tap();
 const input=page.getByRole('textbox',{name:'Repertoire comment'});assert.match(await input.inputValue(),/SecretOwner/);
 await input.focus();await page.evaluate(()=>window.InstinctaZero.onNativeConnectionState());assert.equal(await input.evaluate(el=>el===document.activeElement),true);
 // Turning protection back on also covers an already-focused original-text editor.
 await page.evaluate(()=>{window.__test.privacy=false;window.InstinctaZero.refreshDisplayPrivacy()});await input.focus();
 await page.evaluate(()=>{window.__test.privacy=true;window.InstinctaZero.refreshDisplayPrivacy()});
 assert.equal(await input.count(),0);await page.getByRole('button',{name:'Show original text (reveals identity)',exact:true}).tap();
 const originalComment=(await input.inputValue())+'\nA deliberate extra note.';await input.fill(originalComment);await page.getByRole('button',{name:'Save comment',exact:true}).tap();await page.waitForFunction(()=>!!window.__test.edit);
 assert.equal(await page.evaluate(()=>window.__test.edit.comment),originalComment,'deliberately editing original must not save aliases');
 await page.getByRole('button',{name:'Comment on played move',exact:true}).waitFor();
 await page.evaluate(()=>{window.__test.record=true;window.__test.mutations=[]});await noLeak();
 // Reversible display toggling leaves the exact stored game/tree metadata unchanged.
 const beforeToggle=await page.evaluate(()=>window.__test.saved);
 await page.evaluate(()=>{window.__test.record=false;window.__test.privacy=false;window.InstinctaZero.refreshDisplayPrivacy()});
 assert.equal(await page.locator('[data-study-title]').innerText(),'RivalName – SecretOwner');
 assert.equal(await page.evaluate(()=>window.__test.saved),beforeToggle);
 await page.evaluate(()=>{window.__test.privacy=true;window.InstinctaZero.refreshDisplayPrivacy()});
 await page.waitForTimeout(20);await page.evaluate(()=>{window.__test.record=true;window.__test.mutations=[]});await noLeak();
 assert.equal(await page.evaluate(()=>window.__test.saved),beforeToggle);
 await page.evaluate(()=>{window.__test.fail=true;window.InstinctaZero.refreshDisplayPrivacy()});await noLeak();
 assert.equal(await page.locator('[data-study-title]').innerText(),'White player – Black player');
 await page.evaluate(()=>{window.__test.fail=false;window.InstinctaZero.refreshDisplayPrivacy()});await noLeak();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
 console.log(`${width}×${height}: no name flashes across loading/reopen/reconnect, both colours, errors, comments/reveal, reversible display and raw saves passed`);await page.close();
}}finally{await browser.close();await new Promise(r=>server.close(r))}
