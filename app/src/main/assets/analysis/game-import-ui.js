(function(scope) {
  'use strict';
  scope.createGameImporter=function({Chess,privacy,native,open,close}) {
    const escape=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    const view=document.createElement('section');view.id='game-import';view.hidden=true;document.body.appendChild(view);
    let original='',filename='',games=[],page=0,language='auto',generation=0,pending=null,status='';
    function render() {
      const start=page*20;
      view.innerHTML='<header><button data-close aria-label="Back to Games">‹</button><strong>Import · experimental</strong></header><div class="import-content">'+
        '<div class="import-actions"><button data-file>Open PGN file</button><button data-paste>Paste PGN / FEN</button></div>'+
        '<details><summary>Paste or type manually</summary><p>Original text can contain real player names, even with Privacy on.</p><textarea aria-label="PGN or FEN" spellcheck="false" placeholder="Paste PGN or a FEN position here"></textarea><button data-read>Read text</button></details>'+
        '<label class="import-language">Notation <select aria-label="Notation language">'+[['auto','Auto · legal moves only'],['en','English'],['de','German'],['fr','French'],['es','Spanish'],['it','Italian'],['ru','Russian']].map(([key,label])=>'<option value="'+key+'"'+(key===language?' selected':'')+'>'+label+'</option>').join('')+'</select></label>'+
        '<p class="import-status" role="status">'+escape(status || 'Files open as temporary games. Analysis stays saved. Export before closing if you want to keep edits.')+'</p>'+
        (pending?'<div class="import-report">'+(pending.fen?'<p>FEN detected. Open a separate temporary position; saved Analysis is unchanged.</p><button data-open>Open temporary position</button>':'<p>'+pending.parsed.repairs.length+' notation repairs · '+pending.parsed.issues.length+' unresolved segments.</p>'+pending.parsed.issues.slice(0,30).map(i=>'<p>'+escape(privacy.text(i.token))+': '+escape(i.message)+'</p>').join('')+(pending.parsed.issues.length>30?'<p>More unresolved segments remain in the original file.</p>':'')+'<button data-open>'+(pending.parsed.issues.length?'Open parsed portion':'Open game')+'</button>')+'</div>':'')+
        (games.length?'<h2>'+escape(privacy.text(filename))+' · '+games.length+' games</h2><div class="import-games">'+games.slice(start,start+20).map((g,i)=>'<button data-game="'+(start+i)+'"><b>'+escape(privacy.title((g.headers.White || 'White')+' – '+(g.headers.Black || 'Black'),true))+'</b><small>'+escape(privacy.text([g.headers.Event,g.headers.Date,g.headers.Result].filter(Boolean).join(' · ')))+'</small></button>').join('')+'</div><div class="import-actions"><button data-page="-1"'+(!page?' disabled':'')+'>Previous</button><span>'+(page+1)+' / '+Math.ceil(games.length/20)+'</span><button data-page="1"'+(start+20>=games.length?' disabled':'')+'>Next</button></div>':'')+'</div>';
      view.querySelector('[data-close]').onclick=()=>{hide();close();};
      view.querySelector('[data-file]').onclick=()=>native()?.openPgnFile?.();
      view.querySelector('[data-paste]').onclick=()=>{
        try{const result=JSON.parse(native()?.pasteGameText?.() || '{}');if(result.text)load(result.text,'Clipboard');else{status=result.error || 'Clipboard unavailable. Use Paste or type manually below.';render();view.querySelector('details').open=true;view.querySelector('textarea').focus();}}catch(_){status='Clipboard unavailable. Use the text field below.';render();view.querySelector('details').open=true;}
      };
      view.querySelector('[data-read]').onclick=()=>load(view.querySelector('textarea').value,'Pasted text');
      view.querySelector('select').onchange=event=>{language=event.target.value;pending=null;generation++;status='Notation changed. Select the game again.';render();};
      view.querySelectorAll('[data-game]').forEach(b=>b.onclick=()=>select(Number(b.dataset.game)));
      view.querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>{page+=Number(b.dataset.page);render();view.querySelector('.import-content').scrollTop=0;});
      const confirm=view.querySelector('[data-open]');if(confirm)confirm.onclick=()=>{
        const result=pending;const parsed=result.fen?{root:{id:0,fen:result.fen,children:[],parent:null},headers:{SetUp:'1',FEN:result.fen},original}:result.parsed;
        const source={kind:'import',document:{original,filename,games,page,language}};
        if(open(parsed,source,filename)){hide();pending=null;}else{status='Could not open another game. Close a temporary tab or retry saving Analysis first.';render();}
      };
    }
    async function select(index) {
      const token=++generation,g=games[index];pending=null;status='Reading game…';render();
      try{const parsed=await scope.InstinctaPgn.parse(original.slice(g.start,g.end),Chess,language,()=>token!==generation);if(token!==generation)return;pending={parsed};status=parsed.issues.length?'Some moves need review. Choose a notation language or open only the legal portion.':parsed.repairs.length?'Notation normalized using legal moves. The original text is retained.':'Ready to open.';render();if(!parsed.issues.length && !parsed.repairs.length)view.querySelector('[data-open]').click();}catch(error){if(token===generation){status=error.message;render();}}
    }
    async function load(text,name='PGN file') {
      show();const token=++generation;status='Reading file…';pending=null;games=[];render();
      try {
        if(new TextEncoder().encode(text).length>scope.InstinctaPgn.LIMIT)throw Error('Choose a file smaller than 8 MiB. Nothing was imported.');
        original=text;filename=name;page=0;
        const trimmed=text.trim();
        if(/^[prnbqkPRNBQK1-8/]+\s+[wb]\s/.test(trimmed)) {const probe=new Chess(trimmed);pending={fen:probe.fen({forceEnpassantSquare:true})};status='Choose where to open this position.';render();return;}
        const next=await scope.InstinctaPgn.index(text);if(token!==generation)return;games=next;status=games.length?'Select a game. Temporary tabs are not saved on restart.':'No games found.';render();
      }catch(error){if(token===generation){status=error.message;render();}}
    }
    function show(){view.hidden=false;render();}
    function hide(){generation++;view.hidden=true;}
    function restore(doc){if(!doc)return;generation++;({original,filename,games,page,language}=doc);pending=null;status='Select a game. Temporary tabs are not saved on restart.';show();if(!games.length)load(original,filename);}
    return {show,hide,load,restore,error:message=>{status=message;render();},isOpen:()=>!view.hidden,refresh:()=>{if(!view.hidden)render();}};
  };
})(window);
