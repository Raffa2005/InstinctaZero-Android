#!/usr/bin/env python3
"""Build and query a PGN sidecar index. Querying by UCI history uses only stdlib."""
import argparse
import csv
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import sqlite3
import sys

VERSION = 1
POLICY_VERSION = 4
NEGATIVE = {'refutation', 'analysis', 'model_game'}
KINDS = NEGATIVE | {'repertoire', 'alternative'}
START = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def path_id(root, moves):
    return digest(('repertoire-path-v1\n' + root + '\n' + ' '.join(moves)).encode())[:32]

def pos(board):
    return ' '.join(board.fen(en_passant='legal').split()[:4])

def read_games(path):
    import chess.pgn
    games = []
    with Path(path).open(encoding='utf-8-sig') as f:
        while (game := chess.pgn.read_game(f)) is not None:
            if game.errors:
                raise ValueError(f'{path}: game {len(games)+1}: {game.errors}')
            games.append(game)
    return games

def walk(game):
    stack = [(game, game.board(), (), None, True)]
    while stack:
        n, board, moves, parent_token, mainline = stack.pop()
        token = id(n)
        yield n, board, moves, parent_token, token, mainline
        for i in range(len(n.variations)-1, -1, -1):
            child = n.variations[i]
            if child.move and child.move not in board.legal_moves:
                raise ValueError(f'Illegal move {child.move} after {moves}')
            b = board.copy(stack=False)
            b.push(child.move)
            stack.append((child, b, moves+(child.move.uci(),), token, mainline and i == 0))

def load_package(folder, pgn_root=None):
    folder = Path(folder)
    manifest_path = folder/'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    if manifest['schema_version'] != VERSION:
        raise ValueError('Unsupported manifest schema')
    pgn_root = Path(pgn_root) if pgn_root else folder.parent
    fingerprints = {'manifest.json': digest(manifest_path.read_bytes()), 'resolver_policy_version': str(POLICY_VERSION)}
    configs = []
    for item in manifest['repertoires']:
        path = folder/item['sidecar']
        config = json.loads(path.read_text())
        if config['schema_version'] != VERSION or config['repertoire_id'] != item['id']:
            raise ValueError(f'Bad sidecar identity: {path}')
        pgn = pgn_root/config['pgn']['file']
        actual = digest(pgn.read_bytes())
        if actual != config['pgn']['sha256']:
            raise ValueError(f'Stale PGN: {pgn}; migrate annotations before rebuilding')
        fingerprints[item['sidecar']] = digest(path.read_bytes())
        fingerprints[config['pgn']['file']] = actual
        configs.append((config, pgn))
    return manifest, configs, fingerprints

SCHEMA = '''
CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE repertoires(id TEXT PRIMARY KEY,name TEXT,side TEXT,pgn TEXT,sha256 TEXT);
CREATE TABLE games(repertoire_id TEXT,game_number INTEGER,game_id TEXT,headers TEXT,root_fen TEXT,metadata TEXT,PRIMARY KEY(repertoire_id,game_number));
CREATE TABLE rules(repertoire_id TEXT,rule_id TEXT,body TEXT,PRIMARY KEY(repertoire_id,rule_id));
CREATE TABLE nodes(
 id INTEGER PRIMARY KEY,repertoire_id TEXT,game_number INTEGER,parent_id INTEGER,
 path_id TEXT,ply INTEGER,uci TEXT,san TEXT,fen_before TEXT,fen TEXT,mover TEXT,
 own_move INTEGER,source_known INTEGER,source_mainline INTEGER,
 kind TEXT,line_alternative INTEGER,theory INTEGER,srs INTEGER,confidence TEXT,
 reason TEXT,rule_ids TEXT,comment TEXT,starting_comment TEXT,nags TEXT,
 FOREIGN KEY(parent_id) REFERENCES nodes(id));
CREATE TABLE preferences(repertoire_id TEXT,fen TEXT,preferred_uci TEXT,status TEXT,votes TEXT,PRIMARY KEY(repertoire_id,fen));
CREATE INDEX nodes_path ON nodes(repertoire_id,path_id);
CREATE INDEX nodes_fen ON nodes(repertoire_id,fen);
CREATE INDEX nodes_before ON nodes(repertoire_id,fen_before);
CREATE INDEX nodes_parent ON nodes(parent_id);
CREATE INDEX nodes_training ON nodes(repertoire_id,srs);
'''

def validate_rules(config, games):
    found = set()
    available = {}
    roots = {}
    for gi, game in enumerate(games, 1):
        roots[gi] = pos(game.board())
        available[gi] = {p for _,_,p,_,_,_ in walk(game)}
    for key in config.get('game_metadata',{}):
        if not key.isdigit() or int(key) not in available:
            raise ValueError(f'Unknown metadata game: {key}')
    identities = set()
    preferred_moves = config.get('preferred_moves', {})
    if not isinstance(preferred_moves, dict):
        raise ValueError('preferred_moves must map normalized positions to UCI moves')
    if preferred_moves:
        import chess
        for fen, uci in preferred_moves.items():
            try:
                board = chess.Board(fen)
                move = chess.Move.from_uci(uci)
            except (ValueError, TypeError) as exc:
                raise ValueError(f'Invalid author preference: {fen!r} -> {uci!r}') from exc
            if pos(board) != fen or board.turn != (config['side']=='white') or not move or move not in board.legal_moves:
                raise ValueError(f'Author preference must select a legal own-side move at a normalized position: {fen} -> {uci}')
    for r in config['rules']:
        if r['id'] in identities:
            raise ValueError(f'Duplicate rule ID {r["id"]}')
        identities.add(r['id'])
        if r['kind'] not in KINDS or not isinstance(r['descendants'], bool):
            raise ValueError(f'Invalid kind/scope: {r}')
        if r['kind'] != 'repertoire' and not r['descendants']:
            raise ValueError(f'Exclusions and optional alternatives must cover their subtree: {r["id"]}')
        if r.get('game') is not None and (type(r['game']) is not int or r['game'] not in available):
            raise ValueError(f'Unknown game: {r["id"]}')
        if not r['path_uci'] and r.get('game') is None:
            raise ValueError(f'An empty path requires a specific game: {r["id"]}')
        if r['confidence'] not in {'high','medium','low'}:
            raise ValueError(f'Bad confidence: {r["id"]}')
        matches = [i for i in available if (r.get('game') is None or i == r['game']) and roots[i] == r['root_fen'] and tuple(r['path_uci']) in available[i]]
        if not matches:
            raise ValueError(f'Unmatched rule selector: {r["id"]}')
        if r['kind'] == 'alternative':
            if not r['path_uci'] or r['path_uci'][-1] == '0000':
                raise ValueError(f'An alternative must select a real own-side move: {r["id"]}')
            board = games[matches[0]-1].board()
            for uci in r['path_uci'][:-1]:
                board.push_uci(uci)
            mover = 'white' if board.turn else 'black'
            if mover != config['side']:
                raise ValueError(f'Alternative rule targets the opponent instead of the repertoire side: {r["id"]}')
        found.add(r['id'])
    return len(found)

def build(folder, pgn_root=None):
    folder = Path(folder)
    manifest, configs, fingerprints = load_package(folder, pgn_root)
    temporary = folder/'repertoire.sqlite.new'
    if temporary.exists():
        temporary.unlink()
    db = sqlite3.connect(temporary)
    db.executescript(SCHEMA)
    next_id = 1
    all_rows = []
    csv_rows = []
    summaries = []
    try:
        for config, pgn in configs:
            rep = config['repertoire_id']; side = config['side']
            games = read_games(pgn)
            checked = validate_rules(config, games)
            db.execute('INSERT INTO repertoires VALUES(?,?,?,?,?)', (rep,config['name'],side,pgn.name,config['pgn']['sha256']))
            rules_at = defaultdict(list)
            for rule in config['rules']:
                key = (rule.get('game'),rule['root_fen'],tuple(rule['path_uci']))
                rules_at[key].append(rule)
                db.execute('INSERT INTO rules VALUES(?,?,?)',(rep,rule['id'],json.dumps(rule,ensure_ascii=False)))
                import chess
                san_path = '-- (biography placeholder)' if rule['path_uci']==['0000'] else chess.Board(rule['root_fen']).variation_san([chess.Move.from_uci(u) for u in rule['path_uci']])
                csv_rows.append([rep,rule['id'],rule.get('game') or 'all matching records',rule['kind'],rule['confidence'],san_path,rule['reason'],rule.get('evidence','')])
            for gi, item in config.get('game_metadata',{}).items():
                metadata_kind = 'model_game_record' if 'model_game' in item.get('tags',[]) else 'source_record'
                csv_rows.append([rep,'record-metadata',gi,metadata_kind,item.get('confidence',''),'',item.get('reason',''),item.get('evidence','')])
            global_known = set(config['baseline'].get('additional_known_path_ids', []))
            rep_rows = []
            for gi, game in enumerate(games, 1):
                root = pos(game.board())
                headers = json.dumps(dict(game.headers),sort_keys=True,ensure_ascii=False)
                game_id = digest((str(gi)+'\n'+headers+'\n'+root).encode())[:24]
                db.execute('INSERT INTO games VALUES(?,?,?,?,?,?)',(rep,gi,game_id,headers,root,json.dumps(config.get('game_metadata',{}).get(str(gi),{}),ensure_ascii=False)))
                source = config['baseline']['games'].get(str(gi),{})
                known = set(source.get('known_path_ids',[])) | global_known
                main = set(source.get('mainline_path_ids',[]))
                state = {}; node_map = {}
                for node, board, moves, parent_token, token, _ in walk(game):
                    pid = path_id(root,moves)
                    parent = state.get(parent_token, {'carry':[], 'first_inactive':None})
                    carried = parent['carry']
                    direct = rules_at.get((None,root,moves),[]) + rules_at.get((gi,root,moves),[])
                    applicable = carried + direct
                    inherited_next = carried + [r for r in direct if r['descendants']]
                    negatives = [r for r in applicable if r['kind'] in NEGATIVE]
                    positives = [r for r in applicable if r['kind'] not in NEGATIVE]
                    selected = None
                    if negatives:
                        # Explicit exclusions stay in scope regardless of deeper positive rules.
                        selected = max(negatives,key=lambda r:({'refutation':3,'model_game':2,'analysis':1}[r['kind']],len(r['path_uci']),r.get('game') is not None,r['id']))
                        kind = selected['kind']
                    elif positives:
                        selected = max(positives,key=lambda r:(len(r['path_uci']),r.get('game') is not None,r['kind']=='alternative',r['id']))
                        kind = selected['kind']
                    else:
                        kind = 'repertoire' if pid in known else 'analysis'
                    if '0000' in moves:
                        kind = 'analysis'
                        reason = 'Original non-chess null-move placeholder; excluded from training and theory.'
                        confidence = 'high'
                    elif selected:
                        reason = selected['reason']; confidence = selected['confidence']
                    elif kind == 'analysis':
                        reason = 'Comment-derived continuation without an explicit positive review; inactive pending review.'
                        confidence = 'low'
                    else:
                        reason = 'Present in this original PGN record or in the saved ChessTempo move tree.'
                        confidence = 'medium'
                    # A deeper positive review cannot repair an unreviewed earlier bridge.
                    # Reactivation requires reviewing the first inactive move itself.
                    if kind not in NEGATIVE and parent['first_inactive']:
                        kind = 'analysis'
                        reason = f'An earlier move in this exact history is inactive ({parent["first_inactive"]}); review that bridge before activating later replies.'
                        confidence = 'low'
                    line_alt = any(r['kind']=='alternative' for r in applicable)
                    mover = ('black' if board.turn else 'white') if moves else None
                    before = pos(node.parent.board()) if moves else None
                    own = mover == side if moves else False
                    rid = next_id; next_id += 1
                    row = dict(id=rid,repertoire_id=rep,game_number=gi,parent_id=node_map.get(parent_token),path_id=pid,ply=len(moves),uci=node.move.uci() if moves else None,san=node.parent.board().san(node.move) if moves else None,fen_before=before,fen=pos(board),mover=mover,own_move=int(own),source_known=int(pid in known),source_mainline=int(pid in main),kind=kind,line_alternative=int(line_alt or kind=='alternative'),theory=int(kind not in NEGATIVE),srs=0,confidence=confidence,reason=reason,rule_ids=json.dumps([r['id'] for r in applicable]),comment=node.comment,starting_comment=node.starting_comment,nags=json.dumps(sorted(node.nags)))
                    rep_rows.append(row)
                    state[token] = {'carry':inherited_next,'first_inactive':parent['first_inactive'] or (pid if kind in NEGATIVE else None)}
                    node_map[token] = rid
            # Choose among eligible own-side moves, never among explanatory/model-game branches.
            candidates = defaultdict(lambda:defaultdict(set))
            for row in rep_rows:
                if row['own_move'] and row['theory']:
                    candidates[row['fen_before']][row['uci']]
                    if row['source_mainline'] and not row['line_alternative']:
                        candidates[row['fen_before']][row['uci']].add(row['game_number'])
            preferred = {}
            authored = config.get('preferred_moves', {})
            for fen, uci in authored.items():
                if fen not in candidates or uci not in candidates[fen]:
                    raise ValueError(f'Author preference has no eligible source occurrence: {fen} -> {uci}')
            for fen, choices in candidates.items():
                votes = {u:len(records) for u,records in choices.items()}
                maximum = max(votes.values())
                leaders = [u for u,v in votes.items() if v == maximum]
                if fen in authored:
                    choice = authored[fen]; status = 'author_recommendation'
                elif len(choices) == 1:
                    choice = next(iter(choices)); status = 'sole_eligible_move'
                elif maximum > 0 and len(leaders) == 1:
                    choice = leaders[0]; status = 'inferred_from_original_mainlines'
                else:
                    choice = None; status = 'needs_choice'
                preferred[fen] = choice
                db.execute('INSERT INTO preferences VALUES(?,?,?,?,?)',(rep,fen,choice,status,json.dumps(votes,sort_keys=True)))
            inherited_alt = {}
            for row in rep_rows:
                alt = bool(row['line_alternative'] or inherited_alt.get(row['parent_id'],False))
                if row['theory'] and row['own_move'] and row['uci'] != preferred.get(row['fen_before']):
                    alt = True
                inherited_alt[row['id']] = alt
                row['line_alternative'] = int(alt)
                if row['theory']:
                    # Opponent replies remain regular moves, even inside an optional line.
                    # line_alternative separately preserves the branch's training policy.
                    row['kind'] = 'alternative' if alt and row['own_move'] else 'repertoire'
                row['srs'] = int(bool(row['own_move'] and row['theory'] and not alt))
            all_rows.extend(rep_rows)
            kind_counts = Counter(row['kind'] for row in rep_rows if row['ply'])
            unique_by_kind = {kind:len({r['path_id'] for r in rep_rows if r['ply'] and r['kind']==kind}) for kind in KINDS}
            active_positions = {r['fen'] for r in rep_rows if r['theory']}
            summaries.append(dict(repertoire_id=rep,games=len(games),rules=checked,node_occurrences=len(rep_rows),move_occurrences_by_kind=dict(kind_counts),unique_move_paths_by_kind=unique_by_kind,unique_active_theory_positions=len(active_positions),unique_normal_srs_prompts=len({r['fen_before'] for r in rep_rows if r['srs']}),unique_optional_alternative_prompts=len({r['fen_before'] for r in rep_rows if r['own_move'] and r['theory'] and r['line_alternative']})))
            normal_answers = {(r['fen_before'],r['uci']) for r in rep_rows if r['srs']}
            all_answers = {(r['fen_before'],r['uci']) for r in rep_rows if r['own_move'] and r['theory']}
            # Preserve the legacy optional field (positions), and expose answer-edge counts explicitly.
            summaries[-1].update(unique_optional_alternative_positions=summaries[-1]['unique_optional_alternative_prompts'], unique_srs_answer_edges_including_alternatives=len(all_answers), unique_additional_optional_srs_answer_edges=len(all_answers-normal_answers))
        keys = list(all_rows[0])
        db.executemany('INSERT INTO nodes('+','.join(keys)+') VALUES('+','.join('?' for _ in keys)+')',([r[k] for k in keys] for r in all_rows))
        db.execute('INSERT INTO meta VALUES(?,?)',('schema_version',str(VERSION)))
        db.execute('INSERT INTO meta VALUES(?,?)',('fingerprints',json.dumps(fingerprints,sort_keys=True)))
        db.execute('INSERT INTO meta VALUES(?,?)',('summary',json.dumps(summaries)))
        db.commit()
        if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise ValueError('SQLite integrity failure')
        db.close()
        (folder/'Summary.json.new').write_text(json.dumps(summaries,indent=2)+'\n')
        with (folder/'Annotations.csv.new').open('w',encoding='utf-8-sig',newline='') as f:
            writer=csv.writer(f)
            writer.writerow(['Repertoire','Rule ID','PGN game record','Kind','Confidence','Moves through annotation root','Reason','Source evidence'])
            writer.writerows(csv_rows)
        (folder/'Summary.json.new').replace(folder/'Summary.json')
        (folder/'Annotations.csv.new').replace(folder/'Annotations.csv')
        # Commit the authoritative cache last: failed report writes cannot destroy it.
        temporary.replace(folder/'repertoire.sqlite')
        return summaries
    except Exception:
        db.close()
        temporary.unlink(missing_ok=True)
        (folder/'Summary.json.new').unlink(missing_ok=True)
        (folder/'Annotations.csv.new').unlink(missing_ok=True)
        raise

class Book:
    """Freshness is checked at construction. Reopen after any sidecar or PGN edit."""
    def __init__(self, folder=None, pgn_root=None):
        self.folder = Path(folder or Path(__file__).parent)
        _, self.configs, fingerprints = load_package(self.folder,pgn_root)
        self.db = sqlite3.connect(f'file:{(self.folder/"repertoire.sqlite").resolve()}?mode=ro',uri=True)
        self.db.row_factory = sqlite3.Row
        stored = json.loads(self.db.execute("SELECT value FROM meta WHERE key='fingerprints'").fetchone()[0])
        if fingerprints != stored:
            self.db.close()
            raise ValueError('Stale annotation index: rebuild after editing sidecars or manifest')
        self.ids = {c['repertoire_id'] for c,_ in self.configs}

    def close(self):
        self.db.close()

    def _check_rep(self, repertoire):
        if repertoire not in self.ids:
            raise ValueError(f'Unknown repertoire: {repertoire}')

    def lookup(self, repertoire, moves, root_fen=START):
        """Use exact UCI history; moves may be a list or a whitespace-separated string."""
        self._check_rep(repertoire)
        if isinstance(moves,str): moves = moves.split()
        pid = path_id(root_fen,moves)
        rows = [dict(r) for r in self.db.execute('SELECT id,game_number,kind,theory,srs,line_alternative,reason,confidence,rule_ids,fen FROM nodes WHERE repertoire_id=? AND path_id=?',(repertoire,pid))]
        children = []
        for r in rows:
            children.extend(dict(c) for c in self.db.execute('SELECT game_number,uci,san,kind,theory,srs,line_alternative,reason,confidence,path_id FROM nodes WHERE parent_id=?',(r['id'],)))
        return dict(repertoire_id=repertoire,path_id=pid,known=bool(rows),still_theory=any(r['theory'] for r in rows),has_theory_continuation=any(c['theory'] for c in children),occurrences=rows,continuations=children)

    def position_candidates(self,repertoire,fen):
        """Position lookup is informational: callers must retain the actual history."""
        self._check_rep(repertoire)
        import chess
        key = pos(chess.Board(fen))
        rows = [dict(r) for r in self.db.execute('SELECT game_number,path_id,kind,theory,srs,line_alternative,reason FROM nodes WHERE repertoire_id=? AND fen=?',(repertoire,key))]
        return dict(repertoire_id=repertoire,position=key,history_required=True,candidates=rows)

    def prompts(self,repertoire,include_alternatives=False):
        """Return eligible own-side answer edges, deduplicated by before-position and move.
        Keep path IDs to avoid training through a refutation or model-game history.
        """
        self._check_rep(repertoire)
        where = 'own_move=1 AND theory=1' if include_alternatives else 'srs=1'
        rows = self.db.execute(f'SELECT fen_before,uci,san,path_id,game_number,kind FROM nodes WHERE repertoire_id=? AND {where}',(repertoire,))
        out = {}
        for r in rows:
            key=(r['fen_before'],r['uci'])
            item=out.setdefault(key,dict(fen=r['fen_before'],uci=r['uci'],san=r['san'],paths=set(),game_records=set(),kinds=set()))
            item['paths'].add(r['path_id']);item['game_records'].add(r['game_number']);item['kinds'].add(r['kind'])
        return [{**r,'paths':sorted(r['paths']),'game_records':sorted(r['game_records']),'kinds':sorted(r['kinds'])} for r in out.values()]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--folder',type=Path,default=Path(__file__).parent)
    parser.add_argument('--pgn-root',type=Path)
    sub=parser.add_subparsers(dest='command',required=True)
    sub.add_parser('build')
    sub.add_parser('check')
    q=sub.add_parser('query');q.add_argument('repertoire');q.add_argument('moves',nargs='*');q.add_argument('--root-fen',default=START)
    p=sub.add_parser('prompts');p.add_argument('repertoire');p.add_argument('--include-alternatives',action='store_true')
    m=sub.add_parser('mark');m.add_argument('repertoire');m.add_argument('kind',choices=sorted(KINDS));m.add_argument('moves',nargs='*');m.add_argument('--game',type=int);m.add_argument('--reason',required=True);m.add_argument('--evidence',default='Manual annotation');m.add_argument('--root-fen',default=START)
    args=parser.parse_args()
    try:
        if args.command=='build':result=build(args.folder,args.pgn_root)
        elif args.command=='mark':
            # Check the current package before editing; a mark cannot silently target a stale file.
            book=Book(args.folder,args.pgn_root)
            try:
                found=book.lookup(args.repertoire,args.moves,args.root_fen)
                if not found['known'] or (args.game is not None and not any(x['game_number']==args.game for x in found['occurrences'])):
                    raise ValueError('The requested path/game does not exist in this repertoire')
                if not args.moves and args.game is None:
                    raise ValueError('An empty path requires --game')
            finally:book.close()
            manifest,configs,_=load_package(args.folder,args.pgn_root)
            config=next(c for c,_ in configs if c['repertoire_id']==args.repertoire)
            file=args.folder/next(x['sidecar'] for x in manifest['repertoires'] if x['id']==args.repertoire)
            previous=file.read_bytes()
            rule=dict(game=args.game,root_fen=args.root_fen,path_uci=args.moves,kind=args.kind,reason=args.reason,evidence=args.evidence,confidence='high',descendants=True)
            rule['id']='manual-'+digest(json.dumps(rule,sort_keys=True).encode())[:12]
            if not any(r['id']==rule['id'] for r in config['rules']):config['rules'].append(rule)
            file.write_text(json.dumps(config,indent=2,ensure_ascii=False)+'\n')
            try:build(args.folder,args.pgn_root)
            except Exception:
                file.write_bytes(previous)
                raise
            result={'status':'ok','rule':rule,'note':'Existing ancestor exclusions still apply. To reactivate a branch, review and remove or narrow its excluding rules first.'}
        else:
            book=Book(args.folder,args.pgn_root)
            try:
                if args.command=='check':result={'status':'ok','summary':json.loads(book.db.execute("SELECT value FROM meta WHERE key='summary'").fetchone()[0])}
                elif args.command=='query':result=book.lookup(args.repertoire,args.moves,args.root_fen)
                elif args.command=='prompts':result=book.prompts(args.repertoire,args.include_alternatives)
            finally:book.close()
        print(json.dumps(result,indent=2,ensure_ascii=False))
    except (ValueError,OSError,sqlite3.Error,KeyError) as e:
        print(json.dumps({'status':'unavailable','error':str(e)},ensure_ascii=False),file=sys.stderr)
        return 2
    return 0

if __name__=='__main__':
    raise SystemExit(main())
