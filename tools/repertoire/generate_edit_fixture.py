"""Artificial chess only. Does not read any user's books, backups or app state."""
import argparse,hashlib,json,random,shutil,sqlite3
from pathlib import Path
import chess
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('destination',type=Path)
parser.add_argument('--copies',type=int,default=80)
args=parser.parse_args()
root=args.destination.resolve()
if root.exists():raise SystemExit('Refusing to overwrite an existing fixture folder')
root.mkdir(parents=True)
import generate_fixture
generate_fixture.generate(root/'synthetic',copies=args.copies,books=9)
source=root/'synthetic/annotations/repertoire.sqlite'
def pos(b):return ' '.join(b.fen(en_passant='legal').split()[:4])
def key(before,uci):return hashlib.sha256(f'repertoire-edge-v1\n{before}\n{uci}'.encode()).hexdigest()[:32]
def request(moves):
    b=chess.Board();entries=[]
    for uci in moves:
        m=chess.Move.from_uci(uci);san=b.san(m);b.push(m);entries.append(dict(uci=uci,san=san,fen=pos(b)))
    return dict(action='lookup',root=pos(chess.Board()),history=moves,entries=entries,fen=pos(b),selected=['synthetic_1'],intersections=True)
anchor='e2e4 e7e5 g1f3 b8c6 f1c4 g8f6'.split()
rng=random.Random(198301);edges={};paths={};ends=[]
while len(edges)<1000:
    b=chess.Board();line=anchor.copy()
    for uci in line:b.push_uci(uci)
    for _ in range(24):
        legal=sorted(b.legal_moves,key=lambda m:m.uci())
        if not legal:break
        m=rng.choice(legal);before=pos(b);san=b.san(m);b.push(m);line.append(m.uci())
        k=key(before,m.uci())
        if k not in edges:
            edges[k]=dict(added=True,scope='position',kind='repertoire',anchored=False,before=before,fen=pos(b),uci=m.uci(),san=san)
            paths[k]=line.copy()
        if len(edges)==1000:break
    ends.append(line.copy())
add100=dict(list(edges.items())[:100]);add1000=edges
at_added=request(ends[1][:16]);late=request(ends[-1][:16]);outside=ends[0].copy()
b=chess.Board();[b.push_uci(m) for m in outside]
while len(outside)<140 and not b.is_game_over():
    m=rng.choice(sorted(b.legal_moves,key=lambda m:m.uci()));b.push(m);outside.append(m.uci())
first=request(['e2e4']);reply=request(['e2e4','e7e5'])
main={key(first['fen'],'e7e5'):dict(scope='position',kind='main',before=first['fen'],fen=reply['fen'],uci='e7e5',san='e5')}
notes={hashlib.sha256(('repertoire-comment-v1\n'+e['fen']).encode()).hexdigest()[:32]:dict(scope='comment',fen=e['fen'],comment='Synthetic personal note.') for e in edges.values()}
configs={
 'source':{},'main-label':{'synthetic_1':main},'comments1000':{'synthetic_1':notes},
 'add100':{'synthetic_1':add100},'add1000':{'synthetic_1':add1000},
 'add1000-main':{'synthetic_1':dict(add1000,**main)},
 'all9-add100':{f'synthetic_{i}':add100 for i in range(1,10)}}
for name,edits in configs.items():(root/f'{name}.json').write_text(json.dumps(dict(v=1,edits=edits,settings={},corpus='')))

# Same legal local edges represented as indexed source nodes: an experimental
# representation control, NOT a production converter or migration.
shutil.copy2(source,root/'indexed1000.sqlite')
with sqlite3.connect(root/'indexed1000.sqlite') as db:
    db.row_factory=sqlite3.Row
    def pid(moves):return hashlib.sha256(('repertoire-path-v1\n'+pos(chess.Board())+'\n'+' '.join(moves)).encode()).hexdigest()[:32]
    template=dict(db.execute('SELECT * FROM nodes WHERE repertoire_id=? AND path_id=? LIMIT 1',('synthetic_1',pid(anchor))).fetchone())
    nextid=db.execute('SELECT MAX(id) FROM nodes').fetchone()[0]+1
    known={pid(anchor):template['id']}
    for k,e in edges.items():
        line=paths[k];row=dict(template);path=pid(line)
        row.update(id=nextid,parent_id=known[pid(line[:-1])],path_id=path,ply=len(line),uci=e['uci'],san=e['san'],fen_before=e['before'],fen=e['fen'],
                   mover=e['before'].split()[1],own_move=int(e['before'].split()[1]=='b'),kind='repertoire',theory=1,line_alternative=0,srs=int(e['before'].split()[1]=='b'),comment='',starting_comment='',reason='Synthetic indexed extension.')
        db.execute('INSERT INTO nodes('+','.join(row)+') VALUES('+','.join('?' for _ in row)+')',tuple(row.values()))
        known[path]=nextid;nextid+=1
(root/'workloads.json').write_text(json.dumps(dict(unchanged=request('d2d4 d7d5'.split()),relabelled=reply,anchor=request(anchor),
    added=at_added,added_later=late,new_game=request(outside),next_added=request(ends[1][:17]))))
print(json.dumps(dict(source_rows=sqlite3.connect(source).execute('SELECT COUNT(*) FROM nodes').fetchone()[0],books=9,local_edges=len(edges),distinct_local_start_positions=len({e['before'] for e in edges.values()}),new_game_plies=len(outside))))

