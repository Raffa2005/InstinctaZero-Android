import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync,existsSync} from 'node:fs';

const source=readFileSync(new URL('../../app/src/main/assets/analysis/analysis.js',import.meta.url),'utf8');
const helpers=source.slice(source.indexOf('  function moveArrowShape('),source.indexOf('  function arrowLine('));
const maneuver=new Function(helpers+';return maneuverArrowShapes;')();
const moves=pv=>maneuver(pv,'paleBlue').map(s=>s.orig+s.dest);
const knight=['g1f3','g8f6','f3h4','f6g4','h4f5','g4h6','f5d6'];

test('web maneuver follows only the best piece for up to three own turns',()=>{
  assert.deepEqual(moves(knight),['g1f3','f3h4','h4f5']);
  assert.deepEqual(moves(knight.slice(1)),['g8f6','f6g4','g4h6']);
  assert.deepEqual(moves(['g1f3','g8f6','b1c3','b8c6','c3d5']),['g1f3']);
  assert.deepEqual(moves(['e2e4']),['e2e4']);
  assert.deepEqual(moves([]),[]);
  assert.deepEqual(moves(['a7a8q','h8g7','a8c8','g7h7','c8c7']),['a7a8','a8c8','c8c7']);
});

test('web overlap rules stop reversals, shared destinations and sliding-path interference',()=>{
  assert.deepEqual(moves(['g1f3','g8f6','f3g1']),['g1f3']);
  assert.deepEqual(moves(['a1a4','h8h7','a4a2']),['a1a4']);
  assert.deepEqual(moves(['a1c3','h8h7','c3a3','h7h8','a3c1']),['a1c3','c3a3']);
  assert.deepEqual(moves(['e1g1','h8h7','g1f1']),['e1g1']);
  assert.deepEqual(moves(['a1a1']),[]);
  assert.deepEqual(moves(['bad']),[]);
  assert.deepEqual(moves(['g1f3','g8f6','f3h6']),['g1f3']);
  assert.deepEqual(moves(['g1f3','g8f6','f3h4garbage']),['g1f3']);
});

const webPath=process.env.INSTINCTAZERO_WEB_APP || '/home/rafael/projects/InstinctaZero/instinctazero/web/static/app.js';
test('maneuver helpers are identical to the current live-web sources', {skip:!existsSync(webPath)},()=>{
  const web=readFileSync(webPath,'utf8');
  for(const name of ['moveArrowShape','maneuverArrowShapes','arrowPathInterferes','squareCoordinates']){
    const reference=web.slice(web.indexOf(`function ${name}(`)).split('\n}\n')[0]+'\n}';
    const mobile=helpers.slice(helpers.indexOf(`  function ${name}(`)).split('\n  }\n')[0]+'\n  }';
    assert.equal(mobile.replace(/^  /gm,''),reference);
  }
});

test('maneuvers share the total arrow cap and do not change alternative weighting',()=>{
  const math=source.slice(source.indexOf('  function lineWinningChance('),source.indexOf('  function moveArrowShape('));
  const draw=source.slice(source.indexOf('  function renderArrows('),source.indexOf('  function openPanelView('));
  const engine={stats:{move_stats:[{uci:'g1f3',visits:1000,q:.2},{uci:'b1c3',visits:300,q:.18},{uci:'e2e4',visits:80,q:-.4},{uci:'d2d4',visits:15,q:.16}]}};
  const lines=[{pv:knight},{pv:['b1c3']},{pv:['e2e4']},{pv:['d2d4']},{pv:['g1f3']}];
  const settings={showArrows:true,arrowMode:'maneuver',arrowCount:8},arrows={querySelector:()=>({outerHTML:''}),innerHTML:''};
  let shapes=[];
  const drawArrows=new Function('settings','engine','arrows','finiteMetric','chess','arrowLine',math+helpers+draw+';return renderArrows;')(
    settings,engine,arrows,v=>v==null?null:Number(v),{turn:()=> 'w'},(move,kind,width,shortening)=>{shapes.push({move,kind,width,shortening});return '';});
  const render=()=>{shapes=[];drawArrows(lines);return shapes};
  assert.deepEqual(render().map(s=>s.move),['g1f3','f3h4','h4f5','b1c3','d2d4']);
  const alternative=shapes.find(s=>s.move==='b1c3');
  settings.arrowMode='best';assert.deepEqual(render().map(s=>s.move),['g1f3','b1c3','d2d4']);
  assert.deepEqual(shapes.find(s=>s.move==='b1c3'),alternative);
  settings.arrowMode='maneuver';settings.arrowCount=2;assert.deepEqual(render().map(s=>s.move),['g1f3','f3h4']);
  settings.arrowCount=1;assert.deepEqual(render().map(s=>s.move),['g1f3']);
  settings.showArrows=false;assert.deepEqual(render(),[]);
});
