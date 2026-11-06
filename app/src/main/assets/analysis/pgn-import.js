/* Bounded, dependency-free PGN browsing. The original document is never rewritten. */
(function(scope) {
  'use strict';
  const LIMIT=8*1024*1024, NODE_LIMIT=20000;
  const languages={en:{K:'K',Q:'Q',R:'R',B:'B',N:'N'},de:{K:'K',D:'Q',T:'R',L:'B',S:'N'},fr:{R:'K',D:'Q',T:'R',F:'B',C:'N'},es:{R:'K',D:'Q',T:'R',A:'B',C:'N'},it:{R:'K',D:'Q',T:'R',A:'B',C:'N'},ru:{'Кр':'K','Ф':'Q','Л':'R','С':'B','К':'N'}};
  const figures={'♔':'K','♚':'K','♕':'Q','♛':'Q','♖':'R','♜':'R','♗':'B','♝':'B','♘':'N','♞':'N','♙':'','♟':''};
  const canonical=s=>s.replace(/[!?+#]+/g,'').replace(/0/g,'O').replace(/[–—−]/g,'-').replace(/\u00a0/g,'').replace(/e\.?p\.?$/i,'').replace(/×|:/g,'x');
  const uci=m=>m.from+m.to+(m.promotion || '');
  const pause=()=>new Promise(resolve=>setTimeout(resolve,0));
  function* tokens(text) {
    let i=0;
    while(i<text.length) {
      if(/\s|\ufeff/.test(text[i])){i++;continue;}
      const start=i,ch=text[i++];
      if(ch==='{' || ch===';') {
        const end=text.indexOf(ch==='{'?'}':'\n',i),closed=end>=0;
        i=closed?end+1:text.length;
        yield {kind:'comment',value:text.slice(start+1,closed?end:i),start,end:i,broken:ch==='{'&&!closed};continue;
      }
      if(ch==='[') {
        let quote=false,escaped=false;
        for(;i<text.length;i++){const c=text[i];if(!escaped && c==='"')quote=!quote;if(!quote && c===']'){i++;break;}escaped=!escaped && c==='\\';}
        const raw=text.slice(start,i),match=raw.match(/^\[\s*([A-Za-z][\w]*)\s+"((?:[^"\\]|\\.)*)"\s*\]$/);
        yield {kind:'tag',value:raw,name:match?.[1],text:match?.[2].replace(/\\(["\\])/g,'$1'),start,end:i};continue;
      }
      if(ch==='(' || ch===')'){yield {kind:ch,start,end:i};continue;}
      for(;i<text.length && !/[\s{};[\]()]/.test(text[i]);i++);
      yield {kind:'word',value:text.slice(start,i),start,end:i};
    }
  }
  async function index(text) {
    if(typeof text!=='string' || new TextEncoder().encode(text).length>LIMIT)throw Error('Choose a PGN file smaller than 8 MiB. Nothing was imported.');
    const games=[];let start=0,headers={},hasMoves=false,depth=0,ended=false,slice=performance.now();
    const finish=end=>{if(text.slice(start,end).trim())games.push({start,end,headers});start=end;headers={};hasMoves=false;ended=false;};
    for(const token of tokens(text)) {
      if(token.kind==='tag' && depth===0) {
        if(hasMoves || (token.name==='Event' && headers.Event!==undefined))finish(token.start);
        if(token.name)headers[token.name]=token.text;
      } else {
        if(ended && depth===0 && token.kind==='word')finish(token.start);
        if(token.kind==='(')depth++;
        if(token.kind===')')depth=Math.max(0,depth-1);
        if(token.kind==='word'){hasMoves=true;if(depth===0 && /^(1-0|0-1|1\/2-1\/2|½-½|\*)$/.test(token.value))ended=true;}
      }
      if(performance.now()-slice>6){await pause();slice=performance.now();}
    }
    finish(text.length);return games;
  }
  function resolveMove(chess,raw,language='auto') {
    let token=canonical(raw).replace(/[♔♚♕♛♖♜♗♝♘♞♙♟]/g,c=>figures[c]);
    const legal=chess.moves({verbose:true}),matches=new Map();
    if(/^[a-h][1-8][-x]?[a-h][1-8][qrbnQRBN]?$/.test(token)) {
      const lan=token.replace(/[-x]/g,'').toLowerCase();for(const m of legal)if(uci(m)===lan)matches.set(uci(m),m);
    } else {
      const maps=language==='auto'?Object.values(languages):[languages[language] || languages.en];
      const candidates=new Set();
      for(const map of maps) {
        const head=token.startsWith('Кр')?'Кр':token[0],piece=map[head];
        const candidate=piece?piece+token.slice(head.length):(!/^[A-ZА-Я]/.test(token) || /^O-O/.test(token))?token:null;
        if(candidate)candidates.add(candidate.replace(/([a-h][18])=?([A-ZА-Я])$/,(_,square,p)=>square+'='+(map[p] || p)));
      }
      for(const candidate of candidates)for(const m of legal) {
        const san=canonical(m.san);
        if(candidate===san || candidate.replace('=','')===san.replace('=',''))matches.set(uci(m),m);
      }
    }
    if(matches.size!==1)return {error:matches.size?'Ambiguous move':'Illegal or unrecognized move',token:raw};
    const move=[...matches.values()][0];return {move,repaired:raw!==move.san && raw.replace(/[!?]+$/,'')!==move.san};
  }
  async function parse(text,Chess,language='auto',canceled=()=>false) {
    const headers={},issues=[],repairs=[],stack=[];let chess=null,root=null,cursor=null,count=0,blocked=false,slice=performance.now();
    const initialize=()=>{if(chess)return;if(headers.Variant && !['Standard','From Position'].includes(headers.Variant))throw Error('This preview supports standard chess only. Original file unchanged.');chess=new Chess(headers.FEN || undefined);root={id:0,fen:chess.fen(),children:[],parent:null,comment:'',san:null,move:null};cursor=root;};
    const issue=(token,message)=>issues.push({token:token.value || token.kind,offset:token.start,message});
    for(const token of tokens(text)) {
      if(canceled())throw Error('Import cancelled.');
      if(token.kind==='tag') {if(token.name && !chess)headers[token.name]=token.text;else if(!token.name)issue(token,'Malformed PGN header retained in original');continue;}
      initialize();
      if(token.kind==='comment'){if(!blocked)cursor.comment=(cursor.comment?cursor.comment+'\n':'')+token.value.trim();if(token.broken)issue(token,'Unclosed comment');continue;}
      if(token.kind==='('){stack.push({cursor,blocked});if(!cursor.parent){issue(token,'Variation without a preceding move');blocked=true;}else{cursor=cursor.parent;chess.load(cursor.fen);}continue;}
      if(token.kind===')'){const saved=stack.pop();if(!saved){issue(token,'Unmatched closing variation');continue;}cursor=saved.cursor;blocked=saved.blocked;chess.load(cursor.fen);continue;}
      let word=token.value.replace(/^\d+\.(?:\.\.)?/,'').replace(/^\.{2,3}/,'');
      const attachedNag=word.match(/\$\d+$/)?.[0];if(attachedNag && word!==attachedNag)word=word.slice(0,-attachedNag.length);
      if(/^[!?]{1,2}$/.test(word)){if(!blocked)(cursor.nags || (cursor.nags=[])).push(word);continue;}
      if(!word || /^(\d+\.{1,3}|\$\d+|e\.?p\.?|1-0|0-1|1\/2-1\/2|½-½|\*)$/i.test(word)) {
        if(/^\$\d+$/.test(word) && !blocked)(cursor.nags || (cursor.nags=[])).push(word);
        continue;
      }
      if(blocked)continue;
      if(count>=NODE_LIMIT){issue(token,'20,000-move preview limit; original retained');blocked=true;continue;}
      const result=resolveMove(chess,word,language);
      if(result.error){issue(token,result.error+'; this branch stops here');blocked=true;continue;}
      const number=chess.moveNumber(),move=chess.move(result.move);
      const child={id:++count,fen:chess.fen(),san:move.san,move:{from:move.from,to:move.to,promotion:move.promotion || null},color:move.color,number,parent:cursor,children:[],comment:'',nags:[...(word.match(/[!?]+$/)||[]),...(attachedNag?[attachedNag]:[])]};
      cursor.children.push(child);cursor.selectedChild=cursor.children[0];cursor=child;
      if(result.repaired)repairs.push({from:word,to:move.san});
      if(performance.now()-slice>6){await pause();slice=performance.now();}
    }
    initialize();if(stack.length)issues.push({token:'(',message:'Unclosed variation'});
    return {root,headers,issues,repairs,count,original:text};
  }
  function exportPgn(root,headers={}) {
    const escape=value=>String(value).replace(/\\/g,'\\\\').replace(/"/g,'\\"').replace(/[\r\n]/g,' ');
    const h={...headers};if(root.fen!==new scope.InstinctaZeroChessRules.Chess().fen()){h.SetUp='1';h.FEN=root.fen;}
    const out=Object.entries(h).map(([k,v])=>'['+k+' "'+escape(v)+'"]').join('\n');
    const comment=n=>n.comment?' {'+String(n.comment).replace(/}/g,']')+'}':'';
    const parts=[comment(root)],stack=[];
    const schedule=node=>{if(!node.children.length)return;stack.push({node:node.children[0],siblings:node.children.slice(1)});};
    schedule(root);
    while(stack.length){const item=stack.pop();if(typeof item==='string'){parts.push(item);continue;}const n=item.node;parts.push(n.number+(n.color==='w'?'.':'...')+n.san+(n.nags?.length?' '+n.nags.join(' '):'')+comment(n));schedule(n);for(let i=item.siblings.length-1;i>=0;i--)stack.push(')',{node:item.siblings[i],siblings:[]},'(');}
    return out+'\n\n'+parts.join(' ')+' '+(h.Result || '*')+'\n';
  }
  scope.InstinctaPgn={index,parse,resolveMove,exportPgn,LIMIT};
})(typeof window==='object'?window:globalThis);
