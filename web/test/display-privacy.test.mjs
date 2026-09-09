import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {runInNewContext} from 'node:vm';
const source=await readFile(new URL('../../app/src/main/assets/analysis/display-privacy.js',import.meta.url),'utf8');
function policy(config={enabled:true,names:['SecretOwner','SecondAccount']}){
 const window={InstinctaZeroNative:{getDisplayPrivacy:()=>JSON.stringify(config)}};
 runInNewContext(source,{window});return {p:window.InstinctaZeroPrivacy,window,config};
}
test('privacy is synchronous, reversible and does not change its raw inputs',()=>{
 const {p,config}=policy(),game={title:'SecretOwner – RivalName',pgn:'[White "SecretOwner"]\n[Black "RivalName"]\n1. e4 *'},before=JSON.stringify(game);
 assert.equal(p.title(game.title,true),'Player – Opponent');
 assert.equal(p.title('RivalName – SecretOwner',true),'Opponent – Player');
 assert.equal(p.title('OldUnknownName – RivalName',true),'Opponent – Opponent');
 assert.equal(p.title('SecretOwner custom saved title',false),'Private analysis');
 assert.equal(p.subtitle('SecretOwner · 2100 · https://lichess.org/@/SecretOwner'),'Position');
 assert.equal(p.text(game.pgn),'[White "Hidden"]\n[Black "Hidden"]\n1. e4 *');
 assert.equal(p.text('secretowner_black.pgn https://lichess.org/12345678'),'Player_black.pgn [link hidden]');
 assert.equal(p.text('SecondAccount'),'Player');
 assert.equal(p.error('Request for SecretOwner failed','Connection unavailable'),'Connection unavailable');
 assert.equal(p.refresh(),false,'unchanged reconnect policy must not rebuild editors');
 config.enabled=false;assert.equal(p.refresh(),true);assert.equal(p.title(game.title,true),game.title);assert.equal(p.text(game.pgn),game.pgn);assert.equal(JSON.stringify(game),before);
});
test('missing old bridge remains compatible but a failing privacy bridge fails closed',()=>{
 const {p,window}=policy();window.InstinctaZeroNative.getDisplayPrivacy=()=>{throw Error('offline')};
 assert.equal(p.refresh(),true);assert.equal(p.enabled,true);assert.equal(p.text('SecretOwner'),'[Private text unavailable]');assert.equal(p.title('SecretOwner – RivalName',true),'White player – Black player');
 delete window.InstinctaZeroNative.getDisplayPrivacy;p.refresh();assert.equal(p.enabled,false);
});
test('privacy source is loaded before any saved study or repertoire can render',async()=>{
 const html=await readFile(new URL('../../app/src/main/assets/analysis/index.html',import.meta.url),'utf8');
 assert.ok(html.indexOf('src="display-privacy.js"')<html.indexOf('src="repertoire.js"'));
 assert.ok(html.indexOf('src="display-privacy.js"')<html.indexOf('src="analysis.js"'));
});
