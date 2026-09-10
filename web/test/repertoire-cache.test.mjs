import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import test from 'node:test';

const source = readFileSync(new URL('../../app/src/main/assets/analysis/repertoire.js',import.meta.url),'utf8');
function harness() {
  const requests=[], timers=[], markers=[],cancellations=[];
  const settings={analysis:['r']};
  const fen=(root,moves)=>root+'|'+moves.join(' ');
  let context={root:'start w - - 0 1',fen:fen('start w - - 0 1',[]),history:[],entries:[]}, renders=0,repertoireVisible=false;
  const window={InstinctaZeroNative:{getRepertoireSettings:()=>JSON.stringify(settings),cancelRepertoireLookup:()=>cancellations.push(true),
    saveRepertoireSettings:raw=>Object.assign(settings,JSON.parse(raw)),
    requestRepertoire:raw=>{const id=String(requests.length+1);requests.push({requestId:id,...JSON.parse(raw)});return id;},
    downloadRepertoires:()=>{const id=String(requests.length+1);requests.push({requestId:id,action:'download'});return id;}}};
  vm.runInNewContext(source,{window,setTimeout:fn=>timers.push(fn)});
  const panel=window.createRepertoirePanel({key:()=>null,root:()=>context.root,context:()=>context,hasMoves:()=>!!context.history.length,
    marker:(kind,end)=>markers.push({kind,end}),render:()=>renders++,settings:()=>{},closeSettings:()=>{},tab:()=>{},repertoireVisible:()=>repertoireVisible});
  const reply=(request,data)=>{
    if(data.results)data.results=data.results.map(rep=>({...rep,fen:request.fen,moves:(rep.moves || []).map(move=>({...move,position:{theory:move.theory,end_of_line:move.end_of_line,comments:move.comments,starting_comments:move.starting_comments,...move.position,fen:fen(request.root,[...request.history,move.uci])}}))}));
    window.InstinctaZero.onNativeRepertoire(request.requestId,JSON.stringify(data));
  };
  timers.shift()();reply(requests.at(-1),{installed:true,repertoires:[{id:'r',name:'Test',side:'white'}]});
  return {panel,requests,markers,settings,reply,cancellations,window,showRepertoire(){repertoireVisible=true;panel.refresh();},
    go(history,root=context.root,position=fen(root,history)){context={...context,history,root,fen:position};panel.refresh();},
    click(dataset){const element={dataset};panel.bind({querySelectorAll:selector=>selector==='[data-rep-action]'?[element]:[]});element.onclick();},
    select(id){const element={dataset:{repSelect:id}};panel.bind({querySelectorAll:selector=>selector==='[data-rep-select]'?[element]:[]});element.onclick();},
    get last(){return requests.at(-1);},get marker(){return markers.at(-1);},get renders(){return renders;}};
}
const result = (extra={})=>({id:'r',name:'Test',side:'white',theory:true,comments:[],moves:[],...extra});
const move = (uci,extra={})=>({uci,san:uci,theory:true,deviation:0,comments:['Already known comment'],end_of_line:true,...extra});

test('first-stage markers are immediate, position-shared and stale-safe while departure context is not shared',()=>{
  const h=harness();h.reply(h.last,{results:[result()]});h.go(['e2e4']);const obsolete=h.last;h.go(['d2d4']);const current=h.last;
  h.window.InstinctaZero.onNativeRepertoireMarker(obsolete.requestId,{results:[result({fen:obsolete.fen})]});assert.equal(h.marker.kind,'');
  h.window.InstinctaZero.onNativeRepertoireMarker(current.requestId,{results:[result({fen:current.fen,end_of_line:true})]});assert.deepEqual(h.marker,{kind:'theory',end:true});
  h.reply(current,{results:[result({theory:true,deviation:2})]});
  h.go(['g1f3','g8f6','d2d4'],current.root,current.fen);assert.equal(h.marker.kind,'theory','transposition marker needs no bridge result');
  assert.equal(h.last.history.length,3,'full history is still supplied for contextual deviations');
  h.reply(h.last,{results:[result({theory:false,deviation:3})]});assert.equal(h.marker.kind,'');
  h.window.InstinctaZero.onNativeRepertoireRestored();assert.equal(h.last.action,'catalog');
});

test('opening repertoire requests intersections even for a position cached outside the tab',()=>{
  const h=harness();h.reply(h.last,{results:[result()]});const count=h.requests.length;
  h.showRepertoire();assert.equal(h.requests.length,count+1);assert.equal(h.last.intersections,true);
  h.reply(h.last,{results:[result({intersection:2})]});assert.equal(h.panel.intersection(),2);
  h.go(['e2e4']);assert.equal(h.panel.intersection(),-1,'old branch target cannot follow a new cursor');
});

test('just-played response is a distinct single write at a known informational parent',()=>{
  const h=harness();h.go(['e2e4']);h.reply(h.last,{results:[result({theory:false,can_add:false,can_add_move:true,add_move_independent:true})]});
  assert.match(h.panel.html(),/Add just-played/);assert.doesNotMatch(h.panel.html(),/data-rep-action="quick-add"/);
  h.click({repAction:'add-played'});assert.equal(h.last.kind,'add_move');assert.equal(h.last.id,'r');
  const count=h.requests.length;h.click({repAction:'add-played'});assert.equal(h.requests.length,count);
});

test('extension chooses the nearest covered repertoire, never arbitrary catalog order',()=>{
  const h=harness();
  h.reply(h.last,{results:[result({id:'unrelated',name:'Unrelated',theory:false,can_add:true,add_count:17,deviation:1}),
    result({id:'english',name:'English',theory:false,can_add:true,add_count:1,deviation:17})]});
  h.click({repAction:'quick-add'});
  assert.equal(h.last.action,'edit');assert.equal(h.last.id,'english');assert.equal(h.last.kind,'add');
});

test('loading another game clears old display and cancels even when the next position is cached',()=>{
  const h=harness();h.reply(h.last,{results:[result({moves:[move('e2e4')]})]});
  h.go(['e2e4']);const old=h.last;const cancellations=h.cancellations.length;
  h.panel.beginGame();assert.equal(h.marker.kind,'');assert.ok(h.cancellations.length>cancellations);
  const count=h.requests.length;h.go(['d2d4']);assert.equal(h.requests.length,count,'hidden/loading board must not enqueue work');
  h.reply(old,{results:[result({comments:['Old game comment']})]});assert.doesNotMatch(h.panel.html(),/Old game comment/);
  h.panel.setActive(true);assert.equal(h.requests.length,count+1);h.reply(h.last,{results:[result({comments:['New game comment']})]});
  const ready=h.requests.length;h.go([]);assert.equal(h.requests.length,ready,'position cache reuse still cancels obsolete native reads');
  assert.doesNotMatch(h.panel.html(),/New game comment/);
});

test('equally plausible repertoires keep the chooser and a single candidate needs no extra choice',()=>{
  const h=harness();
  h.reply(h.last,{results:[result({id:'one',name:'One',can_add:true,deviation:4}),result({id:'two',name:'Two',can_add:true,deviation:4})]});
  const count=h.requests.length;h.click({repAction:'quick-add'});assert.equal(h.requests.length,count);
  assert.match(h.panel.html(),/data-rep-id="one"/);assert.match(h.panel.html(),/data-rep-id="two"/);
  h.go(['e2e4']);h.reply(h.last,{results:[result({id:'one',can_add:true,deviation:4}),result({id:'two',can_add:false})]});
  h.click({repAction:'quick-add'});assert.equal(h.last.action,'edit');assert.equal(h.last.id,'one');
});

test('opening a named library book refreshes the catalogue and selects that book',()=>{
  const h=harness();h.panel.openLibrary('new');
  h.reply(h.last,{installed:true,repertoires:[{id:'r',name:'Old',side:'white'},{id:'new',name:'New',side:'black',local:true}]});
  assert.deepEqual(h.settings.analysis,['new']);assert.deepEqual(h.last.selected,['new']);
});

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
  h.reply(current,{results:[result({theory:true,deviation:0})]});assert.equal(h.marker.kind,'theory');
  h.go(['d2d4'],'different w - - 0 1');assert.equal(h.marker.kind,'');
  h.select('r');h.go(['e2e4'],'start w - - 0 1');assert.equal(h.marker.kind,'');
});

test('purely informational positions remain unmarked',()=>{
  const h=harness();h.reply(h.last,{results:[result({moves:[move('e2e4',{theory:false,position:{theory:false,deviation:1,end_of_line:false}})]})]});
  h.go(['e2e4']);assert.deepEqual(h.marker,{kind:'',end:false});assert.doesNotMatch(h.panel.html(),/class="rep-end"/);
  h.click({repAction:'marker'});assert.equal(h.marker.kind,'');
});

test('covered child positions rejoin the book immediately with merged position comments regardless of the incoming edge',()=>{
  const h=harness();h.reply(h.last,{results:[result({moves:[move('e2e4',{theory:false,comments:[],position:{theory:true,deviation:0,end_of_line:false,comments:['Comment from another move order']}})]})]});
  h.go(['e2e4']);assert.deepEqual(h.marker,{kind:'theory',end:false});
  assert.match(h.panel.html(),/Comment from another move order/);
  assert.doesNotMatch(h.panel.html(),/different move order|No continuation for this history/);
  assert.equal(h.panel.summary(),'In repertoire');
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

test('undo invalidates cached coverage, rejects duplicate taps and leaves board context alone',()=>{
  const h=harness();h.reply(h.last,{results:[result({moves:[move('e2e4')]})]});
  h.go(['e2e4','e7e5']);h.reply(h.last,{results:[result({theory:false,can_add:true,add_count:1})]});
  h.click({repAction:'quick-add'});
  h.reply(h.last,{saved:true,undo:{token:'step-1',name:'Test',label:'Added move',repertoire:'r'}});
  h.reply(h.last,{results:[result({end_of_line:true})]});
  assert.match(h.panel.html(),/Undo last repertoire change/);
  assert.match(h.panel.settingsHtml(),/Added move · Test/);
  h.click({repAction:'undo',repUndoToken:'old-step'});assert.equal(h.last.action,'lookup');
  h.click({repAction:'undo',repUndoToken:'step-1'});const undo=h.last;
  assert.equal(undo.action,'undo');assert.equal(undo.token,'step-1');
  h.click({repAction:'undo',repUndoToken:'step-1'});assert.equal(h.last,undo);
  h.reply(undo,{saved:true,undo:null});
  assert.deepEqual(h.last.history,['e2e4','e7e5'],'undo refreshes the same board position');
  assert.equal(h.marker.kind,'','cache invalidated before fresh post-undo coverage');
  h.reply(h.last,{results:[result({theory:false,can_add:true,add_count:1})]});
  assert.equal(h.marker.kind,'');assert.doesNotMatch(h.panel.settingsHtml(),/Undo last repertoire change/);
});

test('failed undo stays retryable and a newer native journal replaces an obsolete button',()=>{
  const h=harness();h.reply(h.last,{results:[result({theory:false,can_add:true,add_count:1})]});
  h.click({repAction:'quick-add'});h.reply(h.last,{saved:true,undo:{token:'one',name:'Test',label:'Added move',repertoire:'r'}});
  h.click({repAction:'undo',repUndoToken:'one'});
  h.reply(h.last,{event:'error',message:'Could not save',undo:{token:'two',name:'Other',label:'Excluded branch',repertoire:'s'}});
  assert.match(h.panel.settingsHtml(),/Excluded branch · Other/);assert.match(h.panel.html(),/Could not save/);
  const count=h.requests.length;h.click({repAction:'undo',repUndoToken:'one'});assert.equal(h.requests.length,count);
  h.click({repAction:'undo',repUndoToken:'two'});assert.equal(h.last.token,'two');assert.equal(h.last.action,'undo');
});

test('variation introductions follow immediate child projections without becoming executable markup',()=>{
  const h=harness();h.reply(h.last,{results:[result({moves:[move('e2e4',{starting_comments:['Introduction <script>not code</script>']})]})]});
  h.go(['e2e4']);
  assert.match(h.panel.html(),/Introduction &lt;script&gt;not code&lt;\/script&gt;/);
  assert.doesNotMatch(h.panel.html(),/<script>/);
  assert.deepEqual(h.marker,{kind:'theory',end:true});
});
