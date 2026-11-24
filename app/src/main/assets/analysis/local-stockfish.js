/* UCI is serial. Wait for the stopped search's bestmove before sending a new position. */
class LocalStockfish {
  constructor(update) { this.update = update; this.worker = null; this.pending = null; this.current = null; }
  close() {
    clearTimeout(this.timer);
    clearTimeout(this.stopDeadline);
    this.worker?.terminate();
    this.worker = this.pending = this.current = null;
  }
  cancel() {
    clearTimeout(this.timer);
    this.pending = null;
    if (this.current && !this.current.stopping) {
      this.current.stopping = true;
      this.worker.postMessage('stop');
      // Some single-thread WebViews cannot dispatch stop while WASM is busy.
      // Never leave a new board waiting for the obsolete depth limit.
      this.stopDeadline = setTimeout(() => {
        const next = this.pending;
        this.close();
        if (next) this.search(this.model,next.fen,next.initialFen,next.history);
      },350);
    }
  }
  search(model, fen, initialFen, history) {
    if (this.model !== model) this.close();
    this.cancel();
    this.model = model;
    this.pending = {fen, initialFen, history, rows:new Map()};
    this.timer = setTimeout(() => {
      try {
        if (!this.worker) {
          this.ready = false;
          const file = model === 'lite' ? 'stockfish-19-lite-single' : 'stockfish-19-single';
          const worker = new Worker('fish/' + file + '.js');
          this.worker = worker;
          worker.onmessage = event => { if (worker === this.worker) this.message(String(event.data)); };
          worker.onerror = () => { if (worker === this.worker) this.fail(); };
          worker.postMessage('uci');
        } else this.start();
      } catch (_) { this.fail(); }
    }, 100);
  }
  fail() { this.close(); this.update(null, [], 'Phone engine could not start. Check Android System WebView and retry.'); }
  start() {
    if (!this.ready || this.current || !this.pending) return;
    this.current = this.pending;
    this.pending = null;
    const {initialFen, history} = this.current;
    this.worker.postMessage('position fen ' + initialFen + (history.length ? ' moves ' + history.join(' ') : ''));
    this.worker.postMessage('go depth 16');
  }
  message(text) {
    if (text === 'uciok') {
      this.worker.postMessage('setoption name Hash value 16');
      this.worker.postMessage('setoption name MultiPV value 5');
      this.worker.postMessage('isready');
    } else if (text === 'readyok') { this.ready = true; this.start(); }
    else if (text.startsWith('bestmove')) { clearTimeout(this.stopDeadline); this.current = null; this.start(); }
    else if (this.current && !this.current.stopping && text.startsWith('info depth')) {
      const match = /^info depth (\d+).*? multipv (\d+) score (cp|mate) (-?\d+).*? pv (.+)$/.exec(text);
      if (!match) return;
      const {fen,rows} = this.current, rank = Number(match[2]);
      const pv = match[5].trim().split(/\s+/);
      const value = Number(match[4]) * (fen.split(' ')[1] === 'w' ? 1 : -1);
      rows.set(rank,{multipv:rank,depth:Number(match[1]),pv,white_cp:match[3]==='cp'?value:null,white_mate:match[3]==='mate'?value:null});
      this.update(fen,[...rows.values()].sort((a,b)=>a.multipv-b.multipv),'');
    }
  }
}
