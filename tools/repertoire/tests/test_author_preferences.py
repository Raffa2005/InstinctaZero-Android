"""Author policy must outrank statistical votes without bypassing exclusions."""
import csv
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import chess
import chess.pgn
from test_repertoire_index import mod, make_game, ucis, rule

class AuthorPreferenceTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory(prefix='author-policy-')
        self.addCleanup(temporary.cleanup)
        self.folder=Path(temporary.name)
        games=[make_game(s,str(i))for i,s in enumerate(['c4 c5 Nc3 Nc6','c4 e5 Nc3 Nf6'],1)]
        pgn='\n\n'.join(g.accept(chess.pgn.StringExporter())for g in games)+'\n'
        (self.folder/'fixture.pgn').write_text(pgn)
        baseline={}
        for gi,g in enumerate(games,1):
            ids=[mod.path_id(mod.START,p)for _,_,p,_,_,_ in mod.walk(g)]
            baseline[str(gi)]={'known_path_ids':ids,'mainline_path_ids':[]}
        self.position=mod.pos(chess.Board())
        board=chess.Board();board.push_san('c4');self.position=mod.pos(board)
        self.config=dict(schema_version=1,repertoire_id='test',name='Author policy',side='black',pgn={'file':'fixture.pgn','sha256':mod.digest(pgn.encode())},baseline={'games':baseline,'additional_known_path_ids':[]},rules=[],preferred_moves={self.position:'c7c5'})
        (self.folder/'manifest.json').write_text(json.dumps({'schema_version':1,'repertoires':[{'id':'test','sidecar':'test.json'}]}))

    def write(self):
        (self.folder/'test.json').write_text(json.dumps(self.config))

    def test_author_choice_without_mainline_votes_and_context_metadata(self):
        self.config['rules']=[rule('optional','c4 e5','alternative',game=2)]
        self.config['game_metadata']={'1':{'author_id':'primary-lesson','repertoire_context':'primary'},'2':{'author_id':'optional-lesson','repertoire_context':'optional_system','tags':['optional_repertoire']}}
        self.write();mod.build(self.folder,self.folder)
        book=mod.Book(self.folder,self.folder)
        try:
            preference=book.db.execute('SELECT preferred_uci,status FROM preferences WHERE fen=?',(self.position,)).fetchone()
            self.assertEqual(tuple(preference),('c7c5','author_recommendation'))
            primary=book.lookup('test',ucis('c4 c5'))['occurrences'][0]
            self.assertEqual((primary['kind'],primary['srs']),('repertoire',1))
            opponent=book.lookup('test',ucis('c4 e5 Nc3'))['occurrences'][0]
            self.assertEqual((opponent['kind'],opponent['line_alternative'],opponent['srs']),('repertoire',1,0))
            optional=book.lookup('test',ucis('c4 e5 Nc3 Nf6'))['occurrences'][0]
            self.assertEqual((optional['kind'],optional['srs']),('alternative',0))
            metadata=json.loads(book.db.execute('SELECT metadata FROM games WHERE game_number=2').fetchone()[0])
            self.assertEqual(metadata['repertoire_context'],'optional_system')
        finally:book.close()
        with (self.folder/'Annotations.csv').open(encoding='utf-8-sig')as f:
            rows=list(csv.DictReader(f))
        self.assertTrue(any(r['Kind']=='source_record'for r in rows))
        self.assertFalse(any(r['Kind']=='model_game_record'for r in rows))

    def test_changed_author_choice_invalidates_cache(self):
        self.write();mod.build(self.folder,self.folder)
        self.config['preferred_moves'][self.position]='e7e5';self.write()
        with self.assertRaisesRegex(ValueError,'Stale annotation'):
            mod.Book(self.folder,self.folder)

    def test_invalid_or_excluded_preferences_fail_without_replacing_cache(self):
        self.write();mod.build(self.folder,self.folder)
        original=(self.folder/'repertoire.sqlite').read_bytes()
        for preferences,rules in [({mod.START:'d2d4'},[]),({self.position:'a7a6'},[]),({self.position:'c7c5'},[rule('reject','c4 c5','refutation',game=1)]),({self.position+' 0 1':'c7c5'},[])]:
            with self.subTest(preferences=preferences,rules=rules):
                self.config['preferred_moves']=preferences;self.config['rules']=rules;self.write()
                with self.assertRaises(ValueError):mod.build(self.folder,self.folder)
                self.assertEqual((self.folder/'repertoire.sqlite').read_bytes(),original)

if __name__=='__main__':unittest.main()
