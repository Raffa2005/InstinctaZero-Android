#!/usr/bin/env python3
"""Generate artificial, deterministic source packages without reading any real book.

The default package is small. Increase copies/books to exercise repeated source
occurrences and multi-selection lookup. Output must be a new directory.
"""
import argparse
import json
from pathlib import Path

import chess
import chess.pgn
import repertoire_index as index


LINES = (
    "d4 d5 Nf3",
    "Nf3 Nf6 d4 d5 c4 e6 Nc3",
    "e4 e5 Nf3 Nc6 Bc4 Nf6",
    "e4 e5 Nf3 Nc6 Bb5 a6",
)


def game_for(line, record):
    game = chess.pgn.Game()
    game.headers["Event"] = "Artificial repertoire test"
    game.headers["White"] = "Synthetic white"
    game.headers["Black"] = "Synthetic black"
    game.headers["Round"] = str(record)
    node = game
    for san in line.split():
        node = node.add_variation(node.board().parse_san(san))
        node.comment = "Generated test explanation. [%cal Gd2d4]"
    if line == LINES[2]:
        parent = game.variations[0].variations[0]
        alternative = parent.add_variation(parent.board().parse_san("Bc4"))
        alternative.starting_comment = "Generated variation introduction."
        alternative.comment = "Generated alternative explanation."
    return game


def generate(destination, copies=8, books=2):
    if not 1 <= copies <= 2000 or not 1 <= books <= 16:
        raise ValueError("Use 1–2000 copies and 1–16 books")
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=False)
    annotations = destination / "annotations"
    annotations.mkdir()
    manifest = {"schema_version": 1, "repertoires": []}
    for book in range(1, books + 1):
        rep = f"synthetic_{book}"
        games = [game_for(line, number + 1)
                 for number, line in enumerate(LINES * copies)]
        pgn = "\n\n".join(game.accept(chess.pgn.StringExporter(
            headers=True, variations=True, comments=True)) for game in games) + "\n"
        (destination / f"{rep}.pgn").write_text(pgn, encoding="utf-8")
        baseline, rules = {}, []
        for number, game in enumerate(games, 1):
            rows = list(index.walk(game))
            baseline[str(number)] = {
                "known_path_ids": [index.path_id(index.START, row[2]) for row in rows],
                "mainline_path_ids": [index.path_id(index.START, row[2])
                                      for row in rows if row[5]],
            }
            if number % 4 == 0:
                rules.append({
                    "id": f"model-tail-{number}", "game": number,
                    "root_fen": index.START,
                    "path_uci": ["e2e4", "e7e5", "g1f3", "b8c6", "f1b5"],
                    "kind": "model_game", "descendants": True,
                    "confidence": "high", "reason": "Artificial model-game cutoff.",
                    "evidence": "Generated fixture, not chess instruction.",
                })
        config = {
            "schema_version": 1, "repertoire_id": rep,
            "name": f"Synthetic book {book}",
            "side": "black" if book % 2 else "white",
            "pgn": {"file": f"{rep}.pgn", "sha256": index.digest(pgn.encode())},
            "baseline": {"games": baseline, "additional_known_path_ids": []},
            "rules": rules,
        }
        sidecar = f"{rep}.annotations.json"
        (annotations / sidecar).write_text(json.dumps(config, indent=2) + "\n")
        manifest["repertoires"].append({"id": rep, "sidecar": sidecar})
    (annotations / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    summary = index.build(annotations, destination)
    (destination / "SYNTHETIC_ONLY.txt").write_text(
        "Generated from public test constants. Contains no real book or user data.\n")
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--copies", type=int, default=8)
    parser.add_argument("--books", type=int, default=2)
    args = parser.parse_args()
    print(json.dumps(generate(args.destination, args.copies, args.books), indent=2))
