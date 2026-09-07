import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import test from 'node:test';

const source = readFileSync(new URL('../../app/src/main/assets/analysis/repertoire.js',import.meta.url),'utf8');
function harness() {
  const requests=[], timers=[], markers=[];
  const settings={analysis:['r']};
  let context={root:'start w - - 0 1',history:[],entries:[]}, renders=0;
  const window={InstinctaZeroNative:{getRepertoireSettings:()=>JSON.stringify(settings),
    saveRepertoireSettings:raw=>Object.assign(settings,JSON.parse(raw)),
    requestRepertoire:raw=>{const id=String(requests.length+1);requests.push({requestId:id,...JSON.parse(raw)});return id;},
    downloadRepertoires:()=>{const id=String(requests.length+1);requests.push({requestId:id,action:'download'});return id;}}};
  vm.runInNewContext(source,{window,setTimeout:fn=>timers.push(fn)});
  const panel=window.createRepertoirePanel({key:()=>null,root:()=>context.root,context:()=>context,hasMoves:()=>!!context.history.length,
    marker:(kind,end)=>markers.push({kind,end}),render:()=>renders++,settings:()=>{},closeSettings:()=>{}});
  const reply=(request,data)=>window.InstinctaZero.onNativeRepertoire(request.requestId,JSON.stringify(data));
  timers.shift()();reply(requests.at(-1),{installed:true,repertoires:[{id:'r',name:'Test',side:'white'}]});
  return {panel,requests,markers,settings,reply,go(history,root=context.root){context={...context,history,root};panel.refresh();},
    click(dataset){const element={dataset};panel.bind({querySelectorAll:selector=>selector==='[data-rep-action]'?[element]:[]});element.onclick();},
    select(id){const element={dataset:{repSelect:id}};panel.bind({querySelectorAll:selector=>selector==='[data-rep-select]'?[element]:[]});element.onclick();},
    get last(){return requests.at(-1);},get marker(){return markers.at(-1);},get renders(){return renders;}};
}
const result = (extra={})=>({id:'r',name:'Test',side:'white',theory:true,comments:[],moves:[],...extra});
const move = (uci,extra={})=>({uci,san:uci,theory:true,deviation:0,comments:['Already known comment'],end_of_line:true,...extra});

test('child marker and terminal status are synchronous for any navigation path, with immediate comments',()=>{
  const h=harness();h.reply(h.last,{results:[result({moves:[move('e2e4')]})]});
  h.go(['e2e4']); // Intentionally do not deliver the native lookup yet.
  assert.deepEqual(h.marker,{kind:'theory',end:true});
  assert.match(h.panel.html(),/End of line/);assert.match(h.panel.html(),/Already known comment/);
  assert.doesNotMatch(h.panel.html(),/data-rep-play="e2e4"/,'parent move must not remain playable');
  h.reply(h.last,{results:[result({end_of_line:true})]});
  const count=h.requests.length;
  h.go([]);assert.equal(h.marker.kind,'');
  h.go(['e2e4']);assert.equal(h.marker.kind,'theory');assert.equal(h.requests.length,count,'visited positions need no bridge round trip');
});

test('cache keys retain full history/root/selection and stale replies never change current marker',()=>{
  const h=harness();h.reply(h.last,{results:[result({moves:[move('e2e4')]})]});
  h.go(['e2e4']);const slow=h.last;
  h.go(['d2d4']);const current=h.last;
  assert.equal(h.marker.kind,'','unrecorded move cannot inherit the other branch');
  h.reply(slow,{results:[result({end_of_line:true})]});assert.equal(h.marker.kind,'');
  h.reply(current,{results:[result({theory:false,position_match:true,deviation:1})]});assert.equal(h.marker.kind,'transposition');
  h.go(['d2d4'],'different w - - 0 1');assert.equal(h.marker.kind,'');
  h.select('r');h.go(['e2e4'],'start w - - 0 1');assert.equal(h.marker.kind,'');
});

test('information and transposition projections never become theory or an end-of-line marker',()=>{
  const h=harness();h.reply(h.last,{results:[result({moves:[move('e2e4',{theory:false,position_match:true,deviation:1,end_of_line:false})]})]});
  h.go(['e2e4']);assert.deepEqual(h.marker,{kind:'transposition',end:false});assert.doesNotMatch(h.panel.html(),/class="rep-end"/);
  h.click({repAction:'marker'});assert.equal(h.marker.kind,'');
});

test('edits and downloads invalidate cached terminal markers; duplicate add taps send one write',()=>{
  const h=harness();h.reply(h.last,{results:[result({moves:[move('e2e4')]})]});
  h.go(['e2e4']);h.reply(h.last,{results:[result({end_of_line:true})]});
  h.go(['e2e4','e7e5']);h.reply(h.last,{results:[result({theory:false,can_add:true,add_count:1})]});
  h.click({repAction:'quick-add'});const write=h.last;
  h.click({repAction:'quick-add'});assert.equal(h.last,write);assert.equal(write.action,'edit');assert.equal(write.kind,'add');
  h.reply(write,{saved:true});const fresh=h.last;assert.equal(fresh.action,'lookup');
  h.reply(fresh,{results:[result({end_of_line:true})]});assert.equal(h.marker.kind,'theory');
  h.go(['e2e4']);assert.equal(h.last.action,'lookup');assert.equal(h.marker.kind,'','old terminal state was invalidated');
  h.reply(h.last,{results:[result({moves:[move('e7e5')]})]});
  h.click({repAction:'download'});h.reply(h.last,{installed:true,repertoires:[{id:'r',name:'Test',side:'white'}]});
  assert.equal(h.last.action,'lookup','updated corpus must be queried again');
});

test('cache has a fixed bound instead of retaining every visited game position',()=>{
  const h=harness();h.reply(h.last,{results:[result()]});
  for(let i=0;i<120;i++){h.go([String(i)]);h.reply(h.last,{results:[result()]});}
  const count=h.requests.length;h.go(['0']);assert.equal(h.requests.length,count+1);
  h.go(['119']);assert.equal(h.requests.length,count+1,'recent entries remain cached');
});
