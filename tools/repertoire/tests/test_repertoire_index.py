"""Portable policy/index regression tests.

Run from the annotation package with:
    python -m unittest discover -s tests -v

Requires python-chess, as listed in ../requirements.txt. Fixtures and generated
indexes live only in temporary directories; supplied repertoire PGNs are untouched.
"""
import importlib.util
import json
import sqlite3
import unittest
import hashlib
import re
import subprocess
import sys
import tempfile
from pathlib import Path
import chess,chess.pgn
PACKAGE=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('reviewed_resolver',PACKAGE/'repertoire_index.py');mod=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(mod)

def make_game(sans,title):
 g=chess.pgn.Game();g.headers['White']=title;n=g;b=g.board()
 for san in sans.split():m=b.parse_san(san);n=n.add_variation(m);b.push(m)
 return g

def ucis(sans):
 b=chess.Board();out=[]
 for san in sans.split():m=b.parse_san(san);out.append(m.uci());b.push(m)
 return out

def rule(id,sans,kind,game=None,descendants=True):
 return dict(id=id,game=game,root_fen=mod.START,path_uci=ucis(sans),kind=kind,reason='Synthetic scope test',evidence='test fixture',confidence='high',descendants=descendants)

class ResolverTests(unittest.TestCase):
 def setUp(self):
  temporary=tempfile.TemporaryDirectory(prefix='repertoire-index-tests-')
  self.fixture_root=Path(temporary.name)
  self.addCleanup(temporary.cleanup)
 def reopen(self,folder):
  book=mod.Book(folder,folder)
  self.addCleanup(book.close)
  return book
 def setup(self,name,lines=None,rules=None,side='white',known_games=None):
  folder=self.fixture_root/name;folder.mkdir(parents=True,exist_ok=True)
  games=[make_game(s,'G'+str(i))for i,s in enumerate(lines or ['e4 e5 Nf3 Nc6 Bb5 a6 Ba4','e4 e5 Nf3 Nc6 Bc4 Bc5'],1)]
  pgn='\n\n'.join(g.accept(chess.pgn.StringExporter(headers=True,variations=True,comments=True))for g in games)+'\n'
  (folder/'fixture.pgn').write_text(pgn)
  baseline={}
  for gi,g in enumerate(games,1):
   pids=[mod.path_id(mod.START,p)for _,_,p,_,_,_ in mod.walk(g)]
   baseline[str(gi)]={'known_path_ids':pids if known_games is None or gi in known_games else [],'mainline_path_ids':pids if known_games is None or gi in known_games else []}
  config=dict(schema_version=1,repertoire_id='test',name='Synthetic repertoire',side=side,pgn={'file':'fixture.pgn','sha256':mod.digest(pgn.encode())},baseline={'games':baseline,'additional_known_path_ids':[]},rules=rules or [])
  self.write(folder,config);return folder,config,games
 def write(self,folder,cfg):
  (folder/'test.json').write_text(json.dumps(cfg));(folder/'manifest.json').write_text(json.dumps({'schema_version':1,'repertoires':[{'id':'test','sidecar':'test.json'}]}))
 def open(self,folder):mod.build(folder,folder);return self.reopen(folder)
 def test_negative_ancestor_cannot_be_reactivated(self):
  f,_,_=self.setup('negative',rules=[rule('exclude','e4 e5 Nf3 Nc6','refutation'),rule('positive','e4 e5 Nf3 Nc6 Bb5','repertoire',game=1)])
  b=self.open(f)
  for s in ['e4 e5 Nf3 Nc6','e4 e5 Nf3 Nc6 Bb5','e4 e5 Nf3 Nc6 Bb5 a6 Ba4']:
   r=b.lookup('test',ucis(s));self.assertFalse(r['still_theory']);self.assertTrue(all(not o['srs'] for o in r['occurrences']))
  self.assertTrue(b.lookup('test',ucis('e4 e5 Nf3'))['still_theory']);b.close()
 def test_game_scoped_model_preserves_other_record(self):
  f,_,_=self.setup('model',lines=['e4 e5 Nf3 Nc6 Bb5','e4 e5 Nf3 Nc6 Bb5'],rules=[rule('model','','model_game',game=1)])
  b=self.open(f);r=b.lookup('test',ucis('e4 e5 Nf3 Nc6 Bb5'));self.assertTrue(r['still_theory']);states={o['game_number']:o for o in r['occurrences']};self.assertEqual(states[1]['kind'],'model_game');self.assertEqual(states[2]['kind'],'repertoire');self.assertFalse(states[1]['srs']);self.assertTrue(states[2]['srs']);self.assertTrue(all(1 not in p['game_records']for p in b.prompts('test')));b.close()
 def test_alternatives_remain_theory_optional_training(self):
  f,_,_=self.setup('alternatives',rules=[rule('alt','e4 e5 Nf3 Nc6 Bc4','alternative',game=2)])
  b=self.open(f);r=b.lookup('test',ucis('e4 e5 Nf3 Nc6 Bc4'));self.assertTrue(r['still_theory']);self.assertFalse(r['occurrences'][0]['srs']);self.assertEqual(r['occurrences'][0]['kind'],'alternative');self.assertFalse(any(p['uci']=='f1c4'for p in b.prompts('test')));self.assertTrue(any(p['uci']=='f1c4'for p in b.prompts('test',True)));b.close()
 def test_training_is_only_own_side(self):
  for side in ['white','black']:
   f,_,_=self.setup('own_'+side,side=side);b=self.open(f)
   for item in b.prompts('test',True):self.assertEqual(chess.Board(item['fen']).turn,side=='white')
   b.close()
 def test_opponent_replies_are_regular_inside_optional_branches(self):
  for side,start,reply in [('white','e4 e5 Nf3','e4 e5 Nf3 Nc6'),('black','e4 e5','e4 e5 Nf3')]:
   f,_,_=self.setup('optional_reply_'+side,side=side,rules=[rule('alt',start,'alternative')])
   b=self.open(f);result=b.lookup('test',ucis(reply))
   self.assertTrue(result['still_theory'])
   self.assertTrue(all(r['kind']=='repertoire' and r['line_alternative']==1 and not r['srs'] for r in result['occurrences']))
   self.assertEqual(b.db.execute("SELECT COUNT(*) FROM nodes WHERE kind='alternative' AND own_move=0").fetchone()[0],0)
 def test_opponent_alternative_rules_are_rejected(self):
  for side,start in [('white','e4 e5'),('black','e4')]:
   f,_,_=self.setup('wrong_colour_'+side,side=side,rules=[rule('wrong',start,'alternative')])
   with self.assertRaisesRegex(ValueError,'opponent'):
    mod.build(f,f)
 def test_multiple_opponent_choices_are_regular(self):
  for side,lines in [('white',['e4 e5 Nf3','e4 c5 Nf3']),('black',['e4 e5 Nf3','d4 d5 c4'])]:
   f,_,_=self.setup('opponent_choices_'+side,side=side,lines=lines)
   b=self.open(f)
   self.assertEqual(b.db.execute("SELECT COUNT(*) FROM nodes WHERE own_move=0 AND kind='alternative'").fetchone()[0],0)
   self.assertEqual(b.db.execute("SELECT COUNT(*) FROM nodes WHERE line_alternative=1").fetchone()[0],0)
 def test_stale_pgn_and_missing_pgn(self):
  f,_,_=self.setup('stale_pgn');b=self.open(f);b.close();original=(f/'fixture.pgn').read_bytes();(f/'fixture.pgn').write_bytes(original+b'\n')
  with self.assertRaises(ValueError):mod.Book(f,f)
  with self.assertRaises(ValueError):mod.build(f,f)
  (f/'fixture.pgn').unlink()
  with self.assertRaises(OSError):mod.Book(f,f)
 def test_stale_rules_and_missing_hash(self):
  f,c,_=self.setup('stale_rule');b=self.open(f);b.close();c['rules']=[rule('new','e4','alternative')];self.write(f,c)
  with self.assertRaises(ValueError):mod.Book(f,f)
  del c['pgn']['sha256'];self.write(f,c)
  with self.assertRaises(KeyError):mod.build(f,f)
 def test_unknown_path_and_unknown_rep(self):
  f,_,_=self.setup('unknown');b=self.open(f);r=b.lookup('test',ucis('d4 d5'));self.assertFalse(r['known']);self.assertFalse(r['still_theory']);self.assertEqual(r['continuations'],[])
  with self.assertRaises(ValueError):b.lookup('missing',[])
  b.close()
 def test_preference_ties_are_not_arbitrarily_selected(self):
  f,_,_=self.setup('ties');b=self.open(f);r=b.lookup('test',ucis('e4 e5 Nf3 Nc6'));self.assertEqual({c['kind']for c in r['continuations']},{'alternative'});self.assertTrue(all(not c['srs']for c in r['continuations']));board=chess.Board();[board.push_uci(u)for u in ucis('e4 e5 Nf3 Nc6')];pref=b.db.execute('SELECT * FROM preferences WHERE fen=?',(mod.pos(board),)).fetchone();self.assertEqual(pref['status'],'needs_choice');self.assertIsNone(pref['preferred_uci']);b.close()
 def test_source_baseline_is_per_game(self):
  f,_,_=self.setup('source_scope',lines=['e4 e5 Nf3','e4 e5 Nf3'],known_games={1});b=self.open(f);r=b.lookup('test',ucis('e4 e5 Nf3'));self.assertEqual({o['game_number']:o['theory']for o in r['occurrences']},{1:1,2:0});b.close()
 def test_position_fallback_preserves_transposition_candidates(self):
  f,_,_=self.setup('transposition',lines=['Nf3 d5 d4 Nf6','d4 Nf6 Nf3 d5']);b=self.open(f);board=chess.Board();[board.push_uci(u)for u in ucis('Nf3 d5 d4 Nf6')];r=b.position_candidates('test',board.fen());self.assertTrue(r['history_required']);self.assertEqual(len({x['path_id']for x in r['candidates']}),2);self.assertNotIn('still_theory',r);b.close()
 def test_bad_selectors_and_negative_direct_rules_rejected(self):
  for name,r in [('game',rule('x','e4','analysis',game=99)),('path',rule('x','d4','repertoire')),('negative_scope',rule('x','e4','analysis',descendants=False))]:
   f,_,_=self.setup('invalid_'+name,rules=[r])
   with self.assertRaises(ValueError):mod.build(f,f)
 def test_explicit_positive_cannot_bypass_unreviewed_ancestor(self):
  f,c,_=self.setup('unreviewed_bridge',lines=['e4 e5 Nf3 Nc6'],known_games=set(),rules=[rule('positive','e4 e5 Nf3 Nc6','repertoire',game=1,descendants=False)])
  # Only the initial position is verified; the intervening moves are unreviewed.
  c['baseline']['games']['1']['known_path_ids']=[mod.path_id(mod.START,[])];self.write(f,c)
  b=self.open(f);r=b.lookup('test',ucis('e4 e5 Nf3 Nc6'));self.assertFalse(r['still_theory'],'Unknown intermediate history must not silently become theory at a reviewed endpoint.');b.close()

 def test_mark_cli_valid_rebuild_and_invalid_no_mutation(self):
  f,_,_=self.setup('mark_cli');b=self.open(f);b.close()
  executable=[sys.executable,str(PACKAGE/'repertoire_index.py'),'--folder',str(f),'--pgn-root',str(f),'mark','test','refutation']
  pgn_before=(f/'fixture.pgn').read_bytes()
  valid=subprocess.run(executable+ucis('e4 e5 Nf3 Nc6 Bc4')+['--game','2','--reason','Fixture manual exclusion'],capture_output=True,text=True)
  self.assertEqual(valid.returncode,0,valid.stderr)
  b=self.reopen(f);self.assertFalse(b.lookup('test',ucis('e4 e5 Nf3 Nc6 Bc4'))['still_theory']);b.close()
  before={p.name:p.read_bytes()for p in f.iterdir()if p.is_file()}
  bad=subprocess.run(executable+ucis('d4 d5')+['--game','2','--reason','Invalid path should not edit'],capture_output=True,text=True)
  self.assertNotEqual(bad.returncode,0)
  after={p.name:p.read_bytes()for p in f.iterdir()if p.is_file()};self.assertEqual(before,after)
  self.assertEqual(pgn_before,(f/'fixture.pgn').read_bytes())
 def test_failed_mark_rebuild_retains_previous_valid_index(self):
  f,_,_=self.setup('mark_failed_build');b=self.open(f);b.close()
  sidecar=(f/'test.json').read_bytes();index=(f/'repertoire.sqlite').read_bytes()
  (f/'Summary.json').unlink();(f/'Summary.json').mkdir()
  try:
   command=[sys.executable,str(PACKAGE/'repertoire_index.py'),'--folder',str(f),'--pgn-root',str(f),'mark','test','refutation',*ucis('e4 e5 Nf3 Nc6 Bc4'),'--game','2','--reason','Simulated summary write failure']
   run=subprocess.run(command,capture_output=True,text=True);self.assertNotEqual(run.returncode,0)
   self.assertEqual(sidecar,(f/'test.json').read_bytes())
   self.assertEqual(index,(f/'repertoire.sqlite').read_bytes(),'Failed mark must preserve the previous index together with the reverted sidecar')
  finally:(f/'Summary.json').rmdir()

if __name__=='__main__':
 unittest.main(verbosity=2)
