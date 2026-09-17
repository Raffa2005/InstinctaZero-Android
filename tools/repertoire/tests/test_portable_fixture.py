from pathlib import Path
import sys
import tempfile
import unittest

PACKAGE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PACKAGE))
import generate_fixture
import repertoire_index


class PortableFixtureTests(unittest.TestCase):
    def test_generated_package_retains_duplicates_and_has_missing_reentry(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "fixture"
            summary = generate_fixture.generate(root, copies=3, books=2)
            self.assertEqual(len(summary), 2)
            book = repertoire_index.Book(root / "annotations", root)
            try:
                rows = book.db.execute(
                    "SELECT COUNT(*), COUNT(DISTINCT uci) FROM nodes "
                    "WHERE repertoire_id=? AND fen_before=?",
                    ("synthetic_1", repertoire_index.START)).fetchone()
                self.assertEqual(tuple(rows), (12, 3))
                board = generate_fixture.chess.Board()
                for san in "d4 d5 Nf3".split():
                    board.push_san(san)
                before = repertoire_index.pos(board)
                board.push_san("Nf6")
                target = repertoire_index.pos(board)
                self.assertEqual(book.db.execute(
                    "SELECT COUNT(*) FROM nodes WHERE repertoire_id=? AND fen_before=? AND uci=?",
                    ("synthetic_1", before, "g8f6")).fetchone()[0], 0)
                self.assertGreater(book.db.execute(
                    "SELECT COUNT(*) FROM nodes WHERE repertoire_id=? AND fen=? AND theory=1",
                    ("synthetic_1", target)).fetchone()[0], 0)
            finally:
                book.close()
            with self.assertRaises(FileExistsError):
                generate_fixture.generate(root)
