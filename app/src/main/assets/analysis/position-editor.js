/* A separate setup board: no legal-move history or source game is changed here. */
(function () {
  'use strict';
  const START = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';
  const roles = {k:'king',q:'queen',r:'rook',b:'bishop',n:'knight',p:'pawn'};
  const homes = {K:['e1','K','h1','R'],Q:['e1','K','a1','R'],k:['e8','k','h8','r'],q:['e8','k','a8','r']};
  function parse(raw) {
    if(typeof raw !== 'string' || raw.length > 200)throw Error('Paste a FEN of at most 200 characters.');
    const fields=raw.trim().split(/\s+/);if(fields.length===4)fields.push('0','1');
    if(fields.length!==6)throw Error('FEN needs a board, turn, castling rights, en-passant square and two move counters.');
    const [board,turn,rights,ep,half,full]=fields, ranks=board.split('/'), pieces={};
    if(ranks.length!==8)throw Error('The board must contain eight ranks.');
    ranks.forEach((rank,row)=>{let file=0,lastDigit=false;
      for(const symbol of rank){if(/[1-8]/.test(symbol)){if(lastDigit)throw Error('Use one number for consecutive empty squares.');file+=Number(symbol);lastDigit=true;}
        else {if(!/[kqrbnpKQRBNP]/.test(symbol)||file>=8)throw Error('The board contains an invalid piece or rank.');pieces[String.fromCharCode(97+file++)+(8-row)]=symbol;lastDigit=false;}}
      if(file!==8)throw Error('Each rank must contain exactly eight squares.');
    });
    if(!/^[wb]$/.test(turn))throw Error('Choose White or Black to move.');
    if(!/^(?:-|K?Q?k?q?)$/.test(rights)||!rights)throw Error('Castling rights must be KQkq (or a subset), or a dash.');
    if(!/^(?:-|[a-h][36])$/.test(ep))throw Error('En-passant must be a square on rank 3 or 6, or a dash.');
    if(!/^\d{1,4}$/.test(half)||!/^\d{1,4}$/.test(full)||Number(full)<1)throw Error('Use a halfmove count from 0–9999 and a move number from 1–9999.');
    return {pieces,turn,rights:rights==='-'?'':rights,ep,half:Number(half),full:Number(full)};
  }
  function placement(s) {
    const rows=[];for(let rank=8;rank>=1;rank--){let row='',empty=0;for(let file=0;file<8;file++){const p=s.pieces[String.fromCharCode(97+file)+rank];if(p){if(empty)row+=empty;empty=0;row+=p;}else empty++;}if(empty)row+=empty;rows.push(row);}return rows.join('/');
  }
  function fen(s) {return [placement(s),s.turn,s.rights||'-',s.ep,s.half,s.full].join(' ');}
  function canCastle(s,key) {const [king,k,rook,r]=homes[key];return s.pieces[king]===k && s.pieces[rook]===r;}
  function epOptions(s) {const out=[];for(const file of 'abcdefgh'){const target=file+(s.turn==='w'?'6':'3'),pawn=file+(s.turn==='w'?'5':'4'),origin=file+(s.turn==='w'?'7':'2');if(!s.pieces[target]&&!s.pieces[origin]&&s.pieces[pawn]===(s.turn==='w'?'p':'P'))out.push(target);}return out;}
  function changed(s) {s.rights=[...s.rights].filter(k=>canCastle(s,k)).join('');s.ep='-';s.half=0;return s;}
  function tapPiece(s,tool,square) {
    if(tool==='erase' || s.pieces[square]===tool) {
      if(!s.pieces[square])return false;
      delete s.pieces[square];
    } else {
      if(!roles[tool.toLowerCase()])return false;
      if(tool==='K'||tool==='k')for(const key of Object.keys(s.pieces))if(s.pieces[key]===tool)delete s.pieces[key];
      s.pieces[square]=tool;
    }
    changed(s);return true;
  }
  function reverseCoordinates(s) {
    const reverse=sq=>String.fromCharCode(201-sq.charCodeAt(0))+(9-Number(sq[1]));
    s.pieces=Object.fromEntries(Object.entries(s.pieces).map(([sq,p])=>[reverse(sq),p]));
    s.rights=[...s.rights].filter(k=>canCastle(s,k)).join('');
    if(s.ep!=='-')s.ep=reverse(s.ep);
    if(s.ep!=='-' && (s.half!==0||!epOptions(s).includes(s.ep)))s.ep='-';
    return s;
  }
  function validate(s) {
    const values=Object.values(s.pieces);
    for(const [color,k,p] of [['White','K','P'],['Black','k','p']]) {
      if(values.filter(x=>x===k).length!==1)return {error:'Place exactly one '+color+' king.'};
      if(values.filter(x=>x===p).length>8)return {error:color+' cannot have more than eight pawns.'};
      if(values.filter(x=>color==='White'?x===x.toUpperCase():x===x.toLowerCase()).length>16)return {error:color+' cannot have more than sixteen pieces.'};
    }
    if(Object.entries(s.pieces).some(([sq,p])=>p.toLowerCase()==='p'&&/[18]$/.test(sq)))return {error:'Pawns cannot be on the first or eighth rank.'};
    if([...s.rights].some(k=>!canCastle(s,k)))return {error:'Castling needs the king and rook on their starting squares. Turn off the unavailable right in State.'};
    if(s.ep!=='-' && (!epOptions(s).includes(s.ep)||s.half!==0))return {error:'En-passant needs the opposing pawn just after its two-square move, an empty starting square and a zero halfmove count.'};
    try {
      const text=fen(s),chess=new window.InstinctaZeroChessRules.Chess(text),opponent=s.turn==='w'?'b':'w';
      const king=Object.keys(s.pieces).find(sq=>s.pieces[sq]===(opponent==='w'?'K':'k'));
      if(chess.isAttacked(king,s.turn))return {error:'The side that just moved cannot be in check. Move a piece or change whose turn it is.'};
      const own=Object.keys(s.pieces).find(sq=>s.pieces[sq]===(s.turn==='w'?'K':'k'));
      if(chess.attackers(own,opponent).length>2)return {error:'The king cannot be checked by more than two pieces at once.'};
      return {fen:text};
    }catch(error){return {error:error.message.replace(/^Invalid FEN: /,'')};}
  }
  window.InstinctaZeroPosition = {START,parse,fen,placement,canCastle,epOptions,changed,tapPiece,reverseCoordinates,validate};

  window.createPositionEditor = function (api) {
    let state=parse(START),tool='move',page='pieces',black=false,opened=false,ground=null,undo=[],notice='',fenError=false,gesture=null;
    const icon=(code)=>'<span class="fa" aria-hidden="true">&#x'+code+';</span>';
    const el=document.createElement('section');el.id='position-editor';el.hidden=true;el.setAttribute('aria-label','Board editor');
    el.innerHTML='<header class="pe-head"><button data-pe-close aria-label="Cancel position editing">‹</button><strong>Board editor</strong><button data-pe-flip aria-label="Flip editor board" title="Flip view only">'+icon('f021')+'</button></header><div class="pe-board-wrap cburnett orientation-white"><div class="board pe-board"></div><div class="pe-squares"></div></div><nav class="pe-tabs" aria-label="Editor tabs"><button data-pe-tab="pieces">Pieces</button><button data-pe-tab="state">State</button><button data-pe-tab="fen">FEN</button></nav><div class="pe-panel"></div><div class="pe-status" role="status"></div><footer class="pe-footer"><button data-pe-close>Cancel</button><button data-pe-analyse>Analyse position</button></footer>';
    document.body.appendChild(el);
    const board=el.querySelector('.pe-board'),wrap=el.querySelector('.pe-board-wrap'),panel=el.querySelector('.pe-panel'),squares=el.querySelector('.pe-squares');
    const save=()=>api.saveDraft?.(JSON.stringify({fen:fen(state),black}));
    function stash(){undo.push(fen(state));if(undo.length>30)undo.shift();notice='';fenError=false;}
    function acceptGroundChanges(){
      if(!opened || ground.getFen()===placement(state))return;
      stash();state.pieces=parse(ground.getFen()+' '+state.turn+' - - 0 1').pieces;changed(state);sync();
    }
    function tapSquare(square,previous=null){
      ground.cancelMove();
      if(tool==='move') {
        if(previous && previous!==square){ground.apiMove(previous,square);acceptGroundChanges();}
        else if(!previous && state.pieces[square])ground.selectSquare(square);
      } else {
        const next=parse(fen(state));
        if(tapPiece(next,tool,square)){stash();state=next;sync();}
      }
    }
    function squareAt(x,y){
      const rect=board.getBoundingClientRect(),col=Math.floor((x-rect.left)*8/rect.width),row=Math.floor((y-rect.top)*8/rect.height);
      if(col<0||col>7||row<0||row>7)return null;
      return String.fromCharCode(97+(black?7-col:col))+(black?row+1:8-row);
    }
    // Let the existing Chessground own every drag. Intercept only completed taps;
    // a palette choice (or a previously tapped board piece) must not move on touchstart.
    board.addEventListener('touchstart',e=>{
      if(e.touches.length!==1){gesture=null;ground.cancelMove();e.stopPropagation();e.preventDefault();return;}
      const touch=e.touches[0];acceptGroundChanges();
      gesture={square:squareAt(touch.clientX,touch.clientY),x:touch.clientX,y:touch.clientY,previous:ground.state.selected,moved:false};
      ground.cancelMove();
    },{capture:true,passive:false});
    board.addEventListener('touchmove',e=>{
      if(gesture && e.touches.length===1)gesture.moved ||= Math.hypot(e.touches[0].clientX-gesture.x,e.touches[0].clientY-gesture.y)>=8;
    },{capture:true,passive:true});
    board.addEventListener('touchend',e=>{
      const last=gesture;gesture=null;
      if(!last)return;
      const touch=e.changedTouches[0];
      if(touch)last.moved ||= Math.hypot(touch.clientX-last.x,touch.clientY-last.y)>=8;
      // A quick release can precede Chessground's next animation frame. Use the
      // actual release square rather than its last rendered hover square.
      if(last.moved && touch && ground.state.draggable.current){ground.state.draggable.current.started=true;ground.state.draggable.current.over=squareAt(touch.clientX,touch.clientY);}
      if(!last.moved){e.stopPropagation();e.preventDefault();if(last.square)tapSquare(last.square,last.previous);}
    },{capture:true,passive:false});
    board.addEventListener('touchend',acceptGroundChanges);
    board.addEventListener('touchcancel',()=>{gesture=null;ground.cancelMove();});
    board.addEventListener('contextmenu',e=>e.preventDefault());
    function resize(){if(ground)requestAnimationFrame(()=>{ground.setBounds(board.getBoundingClientRect());ground.redrawSync();});}
    function status(){const result=validate(state);el.querySelector('.pe-status').textContent=notice||result.error||'Ready · opens separately from your saved board';el.querySelector('.pe-status').classList.toggle('invalid',!!result.error);el.querySelector('[data-pe-analyse]').disabled=!!result.error||fenError;}
    function sync(){if(ground){ground.set({fen:placement(state),orientation:black?'black':'white',lastMove:null});ground.selectSquare(null);}wrap.classList.toggle('orientation-black',black);wrap.classList.toggle('orientation-white',!black);save();render();resize();}
    function render(){
      el.classList.toggle('pe-fen-view',page==='fen');
      el.querySelectorAll('[data-pe-tab]').forEach(b=>b.classList.toggle('selected',b.dataset.peTab===page));
      squares.innerHTML='';
      for(let i=0;i<64;i++){const file=black?7-i%8:i%8,rank=black?1+Math.floor(i/8):8-Math.floor(i/8),sq=String.fromCharCode(97+file)+rank,b=document.createElement('button');b.setAttribute('aria-label',sq);b.onclick=()=>tapSquare(sq,ground.state.selected);squares.appendChild(b);}
      if(page==='pieces') {
        panel.innerHTML='<div class="pe-palette">'+[...'KQRBNPkqrbnp'].map(p=>'<button data-pe-piece="'+p+'" aria-label="'+(p===p.toUpperCase()?'White ':'Black ')+roles[p.toLowerCase()]+'" aria-pressed="'+(tool===p)+'" class="'+(tool===p?'selected':'')+'"><img alt="" src="pieces/'+(p===p.toUpperCase()?'w':'b')+p.toUpperCase()+'.svg"></button>').join('')+'</div><div class="pe-tools">'+[['data-pe-tool="move"','f047','Move'],['data-pe-tool="erase"','f12d','Erase'],['data-pe-undo','f0e2','Undo'],['data-pe-clear','f1f8','Clear'],['data-pe-reset','f01e','Reset']].map(([attr,code,label])=>'<button '+attr+' aria-label="'+label+'">'+icon(code)+'<small>'+label+'</small></button>').join('')+'<button data-pe-reverse aria-label="Reverse coordinates" title="Rotate the pieces 180°, not the view"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 7a8 8 0 0 1 14-1m0-4v4h-4M19 17a8 8 0 0 1-14 1m0 4v-4h4"/><path d="M9 9h6v6H9z"/></svg><small>Reverse</small></button></div>';
        panel.querySelectorAll('[data-pe-piece]').forEach(b=>b.onclick=()=>{tool=tool===b.dataset.pePiece?'erase':b.dataset.pePiece;ground.cancelMove();render();});
        panel.querySelectorAll('[data-pe-tool]').forEach(b=>{b.classList.toggle('selected',b.dataset.peTool===tool);b.setAttribute('aria-pressed',String(b.dataset.peTool===tool));b.onclick=()=>{tool=b.dataset.peTool;ground.cancelMove();render();};});
        panel.querySelector('[data-pe-undo]').disabled=!undo.length;panel.querySelector('[data-pe-undo]').onclick=()=>{state=parse(undo.pop());notice='';sync();};
        panel.querySelector('[data-pe-clear]').onclick=()=>{stash();state=parse('8/8/8/8/8/8/8/8 w - - 0 1');tool='K';sync();};
        panel.querySelector('[data-pe-reset]').onclick=()=>{stash();state=parse(START);tool='move';sync();};
        panel.querySelector('[data-pe-reverse]').onclick=()=>{ground.cancelMove();stash();reverseCoordinates(state);notice='Pieces rotated 180°. Check castling and en-passant in State.';sync();};
      } else if(page==='state') {
        panel.innerHTML='<div class="pe-turn"><span>To move</span><button data-pe-turn="w">White</button><button data-pe-turn="b">Black</button></div><div class="pe-rights"><span>Castling</span>'+[... 'KQkq'].map(k=>'<button data-pe-right="'+k+'" aria-label="'+(k===k.toUpperCase()?'White ':'Black ')+(k.toLowerCase()==='k'?'kingside':'queenside')+' castling">'+(k===k.toUpperCase()?'♔ ':'♚ ')+(k.toLowerCase()==='k'?'O-O':'O-O-O')+'</button>').join('')+'</div><label class="pe-ep">En-passant <select aria-label="En-passant square"><option value="-">None</option>'+[...new Set([...epOptions(state),...(state.ep==='-'?[]:[state.ep])])].map(sq=>'<option value="'+sq+'">'+sq+'</option>').join('')+'</select></label><div class="pe-counters">'+['half','full'].map(k=>'<span>'+(k==='half'?'Halfmove clock':'Move number')+'</span><button data-pe-count="'+k+'" data-delta="-1" aria-label="Decrease '+k+' counter">−</button><output>'+state[k]+'</output><button data-pe-count="'+k+'" data-delta="1" aria-label="Increase '+k+' counter">+</button>').join('')+'</div>';
        panel.querySelectorAll('[data-pe-turn]').forEach(b=>{b.classList.toggle('selected',b.dataset.peTurn===state.turn);b.onclick=()=>{if(state.turn===b.dataset.peTurn)return;stash();state.turn=b.dataset.peTurn;state.ep='-';sync();};});
        panel.querySelectorAll('[data-pe-right]').forEach(b=>{const k=b.dataset.peRight;b.classList.toggle('selected',state.rights.includes(k));b.setAttribute('aria-pressed',String(state.rights.includes(k)));b.disabled=!state.rights.includes(k)&&!canCastle(state,k);b.onclick=()=>{stash();state.rights=[...'KQkq'].filter(x=>x===k?!state.rights.includes(x):state.rights.includes(x)).join('');sync();};});
        const select=panel.querySelector('select');select.value=state.ep;select.onchange=()=>{stash();state.ep=select.value;if(state.ep!=='-')state.half=0;sync();};
        panel.querySelectorAll('[data-pe-count]').forEach(b=>b.onclick=()=>{stash();const key=b.dataset.peCount;state[key]=Math.max(key==='half'?0:1,Math.min(9999,state[key]+Number(b.dataset.delta)));sync();});
      } else {
        panel.innerHTML='<label class="pe-fen-label">Position FEN<textarea aria-label="Position FEN" spellcheck="false" autocapitalize="off" autocomplete="off" maxlength="200"></textarea></label><div class="pe-fen-actions"><button data-pe-paste>Paste</button><button data-pe-import>Use FEN</button><button data-pe-copy>Copy FEN</button></div>';
        const input=panel.querySelector('textarea');input.value=fen(state);
        panel.querySelector('[data-pe-paste]').onclick=()=>{const value=api.paste?.();if(value){input.value=value;notice='Tap Use FEN to load it.';}else notice='No FEN text on the clipboard.';status();};
        panel.querySelector('[data-pe-import]').onclick=()=>{try{const next=parse(input.value);stash();state=next;tool='move';sync();}catch(e){fenError=true;notice=e.message;status();}};
        panel.querySelector('[data-pe-copy]').onclick=()=>{if(api.copy?.(fen(state)))notice='FEN copied.';else{input.value=fen(state);input.focus();input.select();notice='Select and copy this FEN.';}status();};
      }
      status();
    }
    function close(accepted){gesture=null;ground.cancelMove();opened=false;el.hidden=true;api.close(accepted||null,black);}
    el.querySelectorAll('[data-pe-close]').forEach(b=>b.onclick=()=>close(null));
    el.querySelector('[data-pe-flip]').onclick=()=>{black=!black;sync();};
    el.querySelectorAll('[data-pe-tab]').forEach(b=>b.onclick=()=>{page=b.dataset.peTab;notice='';fenError=false;render();resize();});
    el.querySelector('[data-pe-analyse]').onclick=()=>{const result=validate(state);if(result.fen)close(result.fen);else status();};
    new ResizeObserver(resize).observe(board);
    return {isOpen:()=>opened,close:()=>close(null),open:(currentFen,currentBlack,useCurrent=false)=>{
      let draft=null;try{draft=JSON.parse(api.getDraft?.()||'null');}catch(_){}
      try{state=parse(!useCurrent&&draft?.fen?draft.fen:currentFen);}catch(_){state=parse(START);}
      black=!useCurrent&&draft?.fen?!!draft.black:!!currentBlack;tool='move';page='pieces';undo=[];notice='';fenError=false;opened=true;el.hidden=false;
      if(!ground)ground=window.LegacyChessground(board,{fen:placement(state),coordinates:true,autoCastle:false,animation:{enabled:false},movable:{color:'both',free:true,showDests:false},premovable:{enabled:false},draggable:{enabled:true,distance:8,magnified:false,deleteOnDropOff:true},events:{change:acceptGroundChanges}});
      sync();
    }};
  };
}());
