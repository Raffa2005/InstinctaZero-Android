"""Verify that the occurrence index retains PGN comments before variations.

Run from the staged package: python -m unittest discover -s tests -v
All fixtures are temporary; no supplied repertoire files are modified.
"""
import collections
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

import chess
import chess.pgn

# The same file is kept beside the staged builder and inside package/tests.
_THIS = Path(__file__).resolve()
_PACKAGE = _THIS.parent.parent if _THIS.parent.name == "tests" else _THIS.parent / "package"
_SPEC = importlib.util.spec_from_file_location("starting_comment_resolver", _PACKAGE / "repertoire_index.py")
resolver = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(resolver)


class StartingCommentTests(unittest.TestCase):
    def test_starting_and_trailing_comments_survive_distinct_occurrences(self):
        with tempfile.TemporaryDirectory(prefix="repertoire-starting-comments-") as temporary:
            folder = Path(temporary)
            game = chess.pgn.Game()
            game.headers["White"] = "Starting-comment fixture"
            node = game
            for san in ("e4", "e5", "Nf3", "Nc6"):
                node = node.add_variation(node.board().parse_san(san))
            parent = game.variations[0].variations[0]
            for before, after in (
                ("First variation introduction.", "First trailing explanation. [%cal Gc4f7]"),
                ("Second occurrence introduction.", "Second trailing explanation."),
            ):
                child = parent.add_variation(parent.board().parse_san("Bc4"))
                child.starting_comment = before
                child.comment = after
            pgn = folder / "fixture.pgn"
            pgn.write_text(game.accept(chess.pgn.StringExporter(headers=True, variations=True, comments=True)) + "\n", encoding="utf-8")
            parsed = resolver.read_games(pgn)[0]
            paths = [resolver.path_id(resolver.START, moves) for _, _, moves, _, _, _ in resolver.walk(parsed)]
            sidecar = {
                "schema_version": 1,
                "repertoire_id": "fixture",
                "name": "Starting comments",
                "side": "white",
                "pgn": {"file": pgn.name, "sha256": resolver.digest(pgn.read_bytes())},
                "baseline": {"games": {"1": {"known_path_ids": paths, "mainline_path_ids": []}}, "additional_known_path_ids": []},
                "rules": [],
            }
            (folder / "fixture.json").write_text(json.dumps(sidecar), encoding="utf-8")
            (folder / "manifest.json").write_text(json.dumps({"schema_version": 1, "repertoires": [{"id": "fixture", "sidecar": "fixture.json"}]}), encoding="utf-8")
            resolver.build(folder, folder)
            book = resolver.Book(folder, folder)
            try:
                observed = collections.Counter(
                    (row["path_id"], row["comment"], row["starting_comment"])
                    for row in book.db.execute("SELECT path_id,comment,starting_comment FROM nodes")
                )
                expected = collections.Counter(
                    (resolver.path_id(resolver.START, moves), node.comment, node.starting_comment)
                    for node, _, moves, _, _, _ in resolver.walk(parsed)
                )
                self.assertEqual(observed, expected)
                self.assertEqual(sum(count for (_, _, start), count in observed.items() if start), 2)
                # Identical chess histories retain their separate occurrence prose.
                alternatives = list(book.db.execute("SELECT path_id,starting_comment FROM nodes WHERE uci='f1c4'"))
                self.assertEqual(len(alternatives), 2)
                self.assertEqual(len({row["path_id"] for row in alternatives}), 1)
                self.assertEqual(len({row["starting_comment"] for row in alternatives}), 2)
                self.assertEqual(collections.Counter(row[0] for row in book.db.execute("SELECT path_id FROM nodes")), collections.Counter(paths))
            finally:
                book.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
