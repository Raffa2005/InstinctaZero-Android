"""Deterministic work-count and transaction regressions; no timing thresholds."""
from pathlib import Path
import json
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

PACKAGE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PACKAGE))
import generate_fixture
import repertoire_index as index


class IndexEfficiencyTests(unittest.TestCase):
    def test_duplicate_history_lookup_uses_two_queries_and_preserves_every_occurrence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'fixture'
            generate_fixture.generate(root, copies=12, books=2)
            book = index.Book(root / 'annotations', root)
            try:
                # Independent reference: the old per-parent query semantics.
                pid = index.path_id(index.START, [])
                parents = book.db.execute(
                    'SELECT id FROM nodes WHERE repertoire_id=? AND path_id=? ORDER BY id',
                    ('synthetic_1', pid)).fetchall()
                expected = []
                for parent in parents:
                    expected.extend(dict(row) for row in book.db.execute(
                        'SELECT game_number,uci,san,kind,theory,srs,line_alternative,reason,confidence,path_id '
                        'FROM nodes WHERE parent_id=? ORDER BY id', (parent['id'],)))
                queries = []
                book.db.set_trace_callback(queries.append)
                result = book.lookup('synthetic_1', [])
                book.db.set_trace_callback(None)
                self.assertEqual(result['continuations'], expected)
                self.assertEqual(len(result['occurrences']), len(parents))
                self.assertGreater(len(parents), 40)
                self.assertEqual(len(queries), 2)
                self.assertTrue(all(query.startswith('SELECT') for query in queries))
                self.assertEqual(book.lookup('synthetic_1', ['a2a4'])['continuations'], [])
            finally:
                book.close()

    def test_build_flushes_each_repertoire_before_reading_the_next(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'fixture'
            expected = generate_fixture.generate(root, copies=2, books=3)
            folder = root / 'annotations'
            with sqlite3.connect(folder / 'repertoire.sqlite') as db:
                expected_rows = db.execute('SELECT * FROM nodes ORDER BY id').fetchall()
            batches = []
            real_connect = sqlite3.connect
            real_read = index.read_games

            class CountingConnection(sqlite3.Connection):
                def executemany(self, sql, parameters):
                    if sql.startswith('INSERT INTO nodes('):
                        batches.append(True)
                    return super().executemany(sql, parameters)

            reads = 0
            def read_next(path):
                nonlocal reads
                self.assertEqual(len(batches), reads, 'Completed books must not accumulate in memory')
                reads += 1
                return real_read(path)

            with patch.object(index.sqlite3, 'connect', side_effect=lambda *a, **kw: real_connect(*a, factory=CountingConnection, **kw)), \
                    patch.object(index, 'read_games', side_effect=read_next):
                self.assertEqual(index.build(folder, root), expected)
            self.assertEqual(len(batches), 3)
            with sqlite3.connect(folder / 'repertoire.sqlite') as db:
                self.assertEqual(db.execute('SELECT * FROM nodes ORDER BY id').fetchall(), expected_rows)
                self.assertEqual(db.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_later_repertoire_failure_does_not_publish_an_earlier_batch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'fixture'
            generate_fixture.generate(root, copies=2, books=2)
            folder = root / 'annotations'
            original = {name: (folder / name).read_bytes() for name in
                        ('repertoire.sqlite', 'Summary.json', 'Annotations.csv')}
            manifest = json.loads((folder / 'manifest.json').read_text())
            sidecar = folder / manifest['repertoires'][1]['sidecar']
            config = json.loads(sidecar.read_text())
            config['game_metadata'] = {'999': {}}
            sidecar.write_text(json.dumps(config))
            with self.assertRaisesRegex(ValueError, 'Unknown metadata game'):
                index.build(folder, root)
            for name, data in original.items():
                self.assertEqual((folder / name).read_bytes(), data)
            self.assertEqual(list(folder.glob('*.new')), [])
