/* Display only: raw account/game data never passes through this policy on save or export. */
(function () {
  'use strict';
  let enabled=false,names=[],matcher=null,failed=false,signature='';
  const raw=value=>String(value==null?'':value);
  function refresh(){
    const before=signature;
    try {
      const bridge=window.InstinctaZeroNative;
      const config=bridge?.getDisplayPrivacy?JSON.parse(bridge.getDisplayPrivacy()):{enabled:false,names:[]};
      enabled=config.enabled!==false;failed=false;
      names=(Array.isArray(config.names)?config.names:[]).filter(n=>typeof n==='string'&&/^[a-zA-Z0-9_-]{2,40}$/.test(n)).slice(-128);
      matcher=names.length?new RegExp(names.slice().sort((a,b)=>b.length-a.length).map(n=>n.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('|'),'gi'):null;
    }catch(_){enabled=true;names=[];matcher=null;failed=true;}
    signature=JSON.stringify([enabled,names,failed]);return before!==signature;
  }
  function text(value){
    const input=raw(value);if(!enabled)return input;
    if(failed)return input?'[Private text unavailable]':'';
    const metadata=input.replace(/\[(\w+)\s+"(?:[^"\\]|\\.)*"\]/g,(tag,key)=>/^(FEN|SetUp|Result|Variant)$/i.test(key)?tag:'['+key+' "Hidden"]');
    const noLinks=metadata.replace(/https?:\/\/[^\s<>]+|(?:www\.)?lichess\.org\/[^\s<>]+/gi,'[link hidden]');
    return matcher?noLinks.replace(matcher,'Player'):noLinks;
  }
  function player(name,side){return names.some(n=>n.toLowerCase()===raw(name).toLowerCase())?'Player':names.length?'Opponent':side+' player';}
  function title(value,game){
    if(!enabled)return raw(value);
    const players=raw(value).split(' – ');
    if(game && players.length===2)return player(players[0],'White')+' – '+player(players[1],'Black');
    return ['Local analysis','Edited position','Repertoires'].includes(value)?value:game?'Game analysis':'Private analysis';
  }
  function subtitle(value){return !enabled||['Starting position','Custom setup','Completed game','1-0','0-1','1/2-1/2','*'].includes(value)?raw(value):'Position';}
  function error(value,fallback){return enabled?fallback:raw(value);}
  function html(value){
    if(!enabled)return value;
    // Scrub detached presentation nodes before insertion, never live DOM after paint.
    // Opaque IDs and data-* actions remain exact; editing originals is an explicit reveal.
    const template=document.createElement('template');template.innerHTML=value;
    const walk=document.createTreeWalker(template.content,NodeFilter.SHOW_TEXT);
    let node;while((node=walk.nextNode()))if(!node.parentElement?.closest('[data-privacy-revealed],textarea,input'))node.nodeValue=text(node.nodeValue);
    template.content.querySelectorAll('[title],[aria-label]').forEach(el=>{for(const key of ['title','aria-label'])if(el.hasAttribute(key))el.setAttribute(key,text(el.getAttribute(key)));});
    return template.innerHTML;
  }
  window.InstinctaZeroPrivacy={refresh,text,title,subtitle,error,html,get enabled(){return enabled;}};
  refresh(); // Before the saved study can be rendered, not after a connection callback.
}());
