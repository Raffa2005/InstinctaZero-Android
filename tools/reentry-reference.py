"""Read-only, independent python-chess oracle; outputs private fixtures outside repo.

Usage: repertoire/.venv/bin/python tools/reentry-reference.py INDEX OLD_CASES OUTPUT
The old cases retain exact source comments/labels; only legal missing edges are added.
"""
import json
import sqlite3
import sys
from pathlib import Path

import chess

index, previous, output = map(Path, sys.argv[1:])
db = sqlite3.connect(f"{index.as_uri()}?mode=ro", uri=True)
db.row_factory = sqlite3.Row
cases = json.loads(previous.read_text())
count = 0
legal_cases = []
for case in cases:
    rep, fen = case["rep"], case["fen"]
    board = chess.Board(fen)
    explicit = {row[0] for row in db.execute(
        "SELECT DISTINCT uci FROM nodes WHERE repertoire_id=? AND fen_before=?", (rep, fen))}
    geometry = []
    for move in board.legal_moves:
        san = board.san(move)
        board.push(move)
        target = " ".join(board.fen(en_passant="legal").split()[:4])
        board.pop()
        geometry.append(dict(uci=move.uci(), fen=target, san=san))
        if move.uci() in explicit:
            continue
        rows = list(db.execute("SELECT * FROM nodes WHERE repertoire_id=? AND fen=?", (rep, target)))
        active = [r for r in rows if r["theory"]]
        if not active:
            continue
        count += 1
        case["moves"].append(move.uci())
        if "move_details" in case:
            case["move_details"].append(dict(
                uci=move.uci(), theory=True, alternative=all(r["line_alternative"] for r in active),
                own=fen.split()[1] == ("w" if case["side"] == "white" else "b"),
                comments=sorted({r["comment"] for r in rows if r["comment"]}), starting_comments=[]))
    case["moves"].sort()
    legal_cases.append(dict(fen=fen, moves=geometry))
output.write_text(json.dumps(cases))
output.with_suffix(".legal.json").write_text(json.dumps(legal_cases))
print(f"{len(cases)} corpus cases across {len({c['rep'] for c in cases})} repertoires; {count} legal re-entry associations")
