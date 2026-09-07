import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile,mkdir} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {chromium} from 'playwright';
const assets=process.env.PHONE_PREVIEW_ASSETS?pathToFileURL(resolve(process.env.PHONE_PREVIEW_ASSETS)+'/'):new URL('../../app/src/main/assets/analysis/',import.meta.url);
const output=process.env.PHONE_PREVIEW_OUTPUT || '/tmp/instinctazero-authoring-preview';await mkdir(output,{recursive:true});
const server=createServer(async(req,res)=>{try{
  const name=req.url==='/'?'index.html':req.url.slice(1);if(name.includes('..'))throw Error('Invalid asset');
  const body=await readFile(new URL(name,assets));res.writeHead(200,{'Content-Type':{js:'text/javascript',css:'text/css',html:'text/html',svg:'image/svg+xml',ttf:'font/ttf'}[name.split('.').at(-1)] || 'application/octet-stream'}).end(body);
}catch{res.writeHead(404).end()}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,...(process.env.PHONE_PREVIEW_CHROMIUM?{executablePath:process.env.PHONE_PREVIEW_CHROMIUM}:{})});
try{for(const [width,height] of [[360,640],[390,780],[412,844]]){
 const page=await browser.newPage({viewport:{width,height},isMobile:true,hasTouch:true});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  let sequence=0,undo=null;const notes=JSON.parse(localStorage.getItem('notes') || '{}');
  const settings=JSON.parse(localStorage.getItem('reps') || '{"analysis":["qga","english"]}');
  const catalog=[{id:'qga',name:'Queen’s Gambit Accepted',side:'black'},{id:'english',name:'English',side:'black'},{id:'local_new',name:'My new repertoire',side:'white',local:true}];
  window.__test={saved:localStorage.getItem('study') || '{}',requests:[],added:false};
  const emit=result=>{const id=String(++sequence);setTimeout(()=>window.InstinctaZero.onNativeRepertoire(id,JSON.stringify(result)),10);return id;};
  const original='Keep the centre flexible.\nA full source comment.';
  window.InstinctaZeroNative={getUiSettings:()=>'{"leelaEnabled":false}',saveUiSettings:()=>{},cancelAnalysis:()=>{},openRepertoireLibrary:()=>{window.__test.libraryOpened=true},
   getStudyState:()=>window.__test.saved,saveStudyState:raw=>{window.__test.saved=raw;localStorage.setItem('study',raw)},
   getRepertoireSettings:()=>JSON.stringify(settings),saveRepertoireSettings:raw=>{Object.assign(settings,JSON.parse(raw));localStorage.setItem('reps',raw)},
   requestRepertoire:raw=>{
    const r=JSON.parse(raw);window.__test.requests.push(r);
    if(r.action==='catalog')return emit({installed:true,repertoires:catalog,undo});
    if(r.action==='edit'){
      const key=r.id+'|'+r.fen;undo={token:String(sequence),name:r.id,label:r.kind==='add'?'Added move':'Edited comment',key,previous:notes[key]};
      if(r.kind==='comment')notes[key]=r.comment;
      if(r.kind==='reset_comment')delete notes[key];
      if(r.kind==='add')window.__test.added=true;
      localStorage.setItem('notes',JSON.stringify(notes));return emit({saved:true,undo});
    }
    if(r.action==='undo'){if(undo.previous===undefined)delete notes[undo.key];else notes[undo.key]=undo.previous;undo=null;localStorage.setItem('notes',JSON.stringify(notes));return emit({saved:true,undo});}
    return emit({results:r.selected.map(id=>{
      const key=id+'|'+r.fen,own=Object.hasOwn(notes,key),source=own?notes[key]:original;
      const chess=new window.InstinctaZeroChessRules.Chess(r.fen+' 0 1');const legal=chess.moves({verbose:true})[0];
      const covered=r.history.length===0 || window.__test.added || id==='local_new';
      return {...catalog.find(rep=>rep.id===id),fen:r.fen,known:covered,theory:covered,can_add:!covered,add_count:id==='english'?1:3,deviation:id==='english'?3:1,
        comments:source?[source]:[],comment_edited:own,starting_comments:[],moves:covered&&legal?[{uci:legal.from+legal.to,san:legal.san,theory:true,own:true,comments:['Continuation note'],position:{fen:legal.after.split(' ').slice(0,4).join(' '),known:true,theory:true,comments:['Continuation note']}}]:[]};
    })});
   }
  };
 });
 await page.goto(`http://127.0.0.1:${server.address().port}`);await page.waitForFunction(()=>!document.documentElement.classList.contains('board-assets-loading'));
 await page.evaluate(()=>window.InstinctaZero.openRepertoires('local_new'));
 await page.getByRole('button',{name:'Comment on position',exact:true}).waitFor();await page.getByRole('button',{name:'Comment on position',exact:true}).tap();
 await page.getByRole('button',{name:'Edit comment',exact:true}).tap();
 const input=page.getByRole('textbox',{name:'Repertoire comment'});await input.fill('My plan ♞\n\n<img src=x> stays plain text.');
 await page.evaluate(()=>{window.InstinctaZero.setAnalysisActive(false);window.InstinctaZero.setAnalysisActive(true)});
 assert.equal(await input.inputValue(),'My plan ♞\n\n<img src=x> stays plain text.');
 assert.equal(await input.evaluate(el=>el===document.activeElement),true,'updates preserve keyboard focus');
 await page.screenshot({path:`${output}/comment-${width}.png`});
 await page.setViewportSize({width,height:380});
 const saveBounds=await page.getByRole('button',{name:'Save comment',exact:true}).boundingBox();
 assert.ok(saveBounds.y+saveBounds.height<=340,'Save remains above the footer with a keyboard-sized viewport');
 await page.screenshot({path:`${output}/keyboard-${width}.png`});await page.setViewportSize({width,height});
 await page.getByRole('button',{name:'Save comment',exact:true}).tap();await page.getByRole('button',{name:'Comment on position',exact:true}).waitFor();
 await page.getByRole('button',{name:'Comment on position',exact:true}).tap();assert.match(await page.locator('.rep-comments').innerText(),/My plan ♞/);assert.equal(await page.locator('.rep-comments img').count(),0);
 await page.reload();await page.getByRole('button',{name:'Comment on position',exact:true}).waitFor();await page.getByRole('button',{name:'Comment on position',exact:true}).tap();
 await page.getByRole('button',{name:'Edit comment',exact:true}).tap();assert.match(await input.inputValue(),/My plan/);
 await page.getByRole('button',{name:'Restore source text',exact:true}).tap();await page.getByRole('button',{name:'Comment on position',exact:true}).waitFor();
 assert.match(await page.getByRole('button',{name:'Comment on position',exact:true}).innerText(),/Keep the centre/);
 await page.getByRole('button',{name:'Undo last repertoire change',exact:true}).tap();await page.waitForTimeout(60);
 assert.match(await page.getByRole('button',{name:'Comment on position',exact:true}).innerText(),/My plan/);
 // Two selections, but only English has the closest covered prefix: one tap writes it.
 await page.evaluate(()=>{window.InstinctaZero.openRepertoires('qga')});await page.waitForTimeout(50);
 await page.locator('[data-action=settings]').tap();await page.locator('[data-rep-select=english]').tap();await page.locator('[data-rep-focus=""]').tap();await page.locator('[data-action=settings]').tap();
 await page.evaluate(()=>window.InstinctaZero.loadArchivedGame({id:'Author01',moves:[{uci:'c2c4'},{uci:'c7c5'},{uci:'g1f3'}],white:{name:'White'},black:{name:'Black'}}));
 await page.getByRole('button',{name:'Repertoires',exact:true}).tap();await page.locator('[data-rep-action=quick-add]').waitFor();
 assert.equal(await page.locator('.rep-add-target').innerText(),'English');
 await page.locator('[data-rep-action=quick-add]').tap();await page.getByRole('button',{name:'Comment on added line'}).waitFor();
 assert.equal(await page.evaluate(()=>window.__test.requests.filter(r=>r.action==='edit').at(-1).id),'english');
 await page.getByRole('button',{name:'Comment on added line'}).tap();await input.waitFor();
 await page.getByRole('button',{name:'Cancel',exact:true}).tap();await page.locator('[data-action=settings]').tap();
 await page.locator('[data-rep-action=manage-library]').tap();assert.equal(await page.evaluate(()=>window.__test.libraryOpened),true);
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);
 console.log(`${width}×${height}: local library selection, typed/escaped/restored comments, focus, restart, Undo and automatic extension passed`);await page.close();
}}finally{await browser.close();await new Promise(r=>server.close(r));}
