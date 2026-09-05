(function () {
  'use strict';
  window.InstinctaZeroLibrary = function (api) {
    const tools=window.InstinctaZeroStudyTools, Chess=window.InstinctaZeroChessRules.Chess;
    let overlay=null, side='w', index={w:{},b:{}}, message='', currentStudy=null;
    const graphs=new Map(); let revision=0, statusKey='', statusHtml='';
    const native=()=>window.InstinctaZeroNative, esc=api.safe;
    const list=()=>{ try { return JSON.parse(native().listBoards()); } catch (_) { return []; } };
    const id=()=> 'board-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,10);
    const read=key=>{ try { return JSON.parse(native().readBoard(key)); } catch (_) { return {}; } };
    function close() { if(overlay) overlay.remove(); overlay=null; }
    function rebuild(changed) {
      if(changed) graphs.set(changed.boardId,tools.repertoireIndex([changed],Chess));
      else {
        graphs.clear();
        for(const meta of list().filter(m=>m.repertoireColor==='w'||m.repertoireColor==='b')) graphs.set(meta.boardId,tools.repertoireIndex([read(meta.boardId)],Chess));
      }
      const result={w:{},b:{}};
      for (const graph of graphs.values()) {
        for (const color of ['w','b']) for (const [key,moves] of Object.entries(graph[color])) result[color][key]=[...new Set([...(result[color][key]||[]),...moves])];
      }
      index=result; revision++; api.render();
    }
    function open(studyId) {
      api.save(); close(); currentStudy=studyId || null;
      const all=list(), items=currentStudy?all.filter(m=>m.studyId===currentStudy):all.filter((m,i,a)=>a.findIndex(n=>n.studyId===m.studyId)===i);
      overlay=document.createElement('aside'); overlay.className='library-shade'; overlay.setAttribute('aria-label','Study library');
      overlay.innerHTML='<section class="chapter-sidebar"><div class="library-heading"><b>'+ (currentStudy?'Chapters':'Studies')+'</b><button data-close aria-label="Close library">×</button></div>'+
        '<div class="library-tools"><button data-new>'+ (currentStudy?'+ Chapter':'+ Study')+'</button><button data-import>Import PGN</button></div>'+
        (message?'<p class="library-message" role="status">'+esc(message)+'</p>':'')+
        '<div class="library-list">'+(items.length?items.map(m=>'<button class="library-item'+(m.boardId===api.state().boardId?' active':'')+'" data-open="'+esc(m.boardId)+'"><b>'+esc(currentStudy?m.chapterTitle:m.title)+'</b><small>'+esc(currentStudy?(m.repertoireColor?'Repertoire · '+(m.repertoireColor==='w'?'White':'Black'):'Autosaved on this phone'):all.filter(n=>n.studyId===m.studyId).length+' chapters')+'</small><span>›</span></button>').join(''):'<p class="empty">Create a board or open a PGN. Your existing analysis stays saved.</p>')+'</div>'+
        (currentStudy?'<button class="library-bottom" data-studies>All studies</button>':'')+'</section>';
      document.body.appendChild(overlay);
      overlay.onclick=e=>{if(e.target===overlay)close();};
      overlay.querySelector('[data-close]').onclick=close;
      overlay.querySelector('[data-new]').onclick=()=>newBoard(currentStudy);
      overlay.querySelector('[data-import]').onclick=()=>native().importPgn();
      const back=overlay.querySelector('[data-studies]'); if(back)back.onclick=()=>open();
      overlay.querySelectorAll('[data-open]').forEach(button=>button.onclick=()=>{
        const state=read(button.dataset.open); if(!state.tree) {message='Could not open this chapter.';return open(currentStudy);}
        if(!currentStudy) return open(state.studyId);
        if(api.load(state)){close();api.save();}
      });
    }
    function newBoard(studyId) {
      api.save(); const all=list(), siblings=all.filter(m=>m.studyId===studyId), boardId=id();
      const state={v:1,boardId,studyId:studyId||boardId,chapterTitle:'Chapter '+(siblings.length+1),title:siblings[0]?.title||'Study '+(new Set(all.map(m=>m.studyId)).size+1),initialFen:new Chess().fen(),cursor:[],tree:[],tab:'moves'};
      if(!native().saveBoard(JSON.stringify(state))){message='Could not save the new chapter.';return open(studyId);}
      api.load(state);api.save();close();
    }
    function importPgn(raw, filename) {
      try {
        const games=tools.parsePgn(raw,Chess); api.save();
        const studyId=id(), studyTitle=tools.pgnStudyName(filename,games[0].headers.Event);
        const states=games.map((g,i)=>Object.assign({v:1,boardId:i?id():studyId,studyId,title:studyTitle,chapterTitle:g.headers.White&&g.headers.Black?g.headers.White+' – '+g.headers.Black:'Chapter '+(i+1),cursor:[],tab:'moves'},g));
        const written=[];
        for(const state of states) {
          if(!native().saveBoard(JSON.stringify(state))) {written.forEach(key=>native().deleteBoard(key)); throw Error('Import could not be saved. Existing boards were not changed.');}
          written.push(state.boardId);
        }
        api.load(states[0]);api.save();message=games.length+' chapter'+(games.length===1?'':'s')+' imported';open(studyId);
      } catch(error){message=error.message;open(currentStudy);}
    }
    function markRepertoire(color) {
      api.setRepertoire(color);api.save();
      for(const meta of list().filter(m=>m.studyId===api.state().studyId&&m.boardId!==api.state().boardId)) {
        const state=read(meta.boardId);state.repertoireColor=color;native().saveBoard(JSON.stringify(state));
      }
      if(color)side=color;rebuild();
    }
    function status() {
      if(!Object.keys(index.w).length&&!Object.keys(index.b).length) return '';
      const state=api.position(), key=JSON.stringify([revision,side,state.initialFen,state.cursor]);
      if(key===statusKey)return statusHtml;
      const result=tools.deviation(index,side,state.cursor,state.initialFen,Chess);
      statusKey=key;
      statusHtml='<button class="repertoire-status" data-repertoire-side aria-label="Switch repertoire side">'+(side==='w'?'♔':'♚')+' '+(result.known?(result.transposed?'Back in repertoire · transposition':'In repertoire'):(result.first?esc(result.first.who)+' deviated · ply '+result.first.ply:'Outside repertoire'))+'</button>';
      return statusHtml;
    }
    function bindStatus() { const button=document.querySelector('[data-repertoire-side]'); if(button)button.onclick=()=>{side=side==='w'?'b':'w';api.render();}; }
    function exportCurrent() { try {native().exportPgn(tools.exportPgn(api.state(),Chess));} catch(e){message=e.message;open();} }
    function exportStudy() {try { const state=api.state();api.save(); const pgn=list().filter(m=>m.studyId===state.studyId).map(m=>tools.exportPgn(read(m.boardId),Chess)).join('\n');native().exportPgn(pgn); }catch(e){message=e.message;open();} }
    function deleteCurrent() {const state=api.state();if(state.gameId)return;if(native().deleteBoard(state.boardId)){api.load({v:1,gameId:null,initialFen:new Chess().fen(),tree:[],cursor:[],title:'Local analysis'});api.save();message='Chapter deleted. Export PGN before deleting if you need a backup.';rebuild();open();}}
    setTimeout(rebuild,0);
    return {open,close,isOpen:()=>!!overlay,newBoard,importPgn,markRepertoire,status,bindStatus,rebuild,exportCurrent,exportStudy,deleteCurrent};
  };
}());
