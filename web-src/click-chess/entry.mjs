import {OriginRanker} from './ranker.mjs';
import modelBytes from './origin-ranker.bin';
export const ranker = OriginRanker.fromBinary(modelBytes);

// The app supplies legal moves and owns its study tree. Keep upstream Chessground
// untouched: intercept destination-only gestures before its piece/drag handlers.
export function bindDestinationInput({element, ground, enabled, canTap, tap, correction, undo, clear, keepOutside = () => false}) {
  let gesture = null;
  const cancel = () => {
    if (gesture && gesture.correction) ground.cancelMove();
    gesture = null;
  };
  const squareAt = touch => {
    const b = element.getBoundingClientRect();
    let file = Math.floor(8 * (touch.clientX - b.left) / b.width);
    let rank = Math.floor(8 * (touch.clientY - b.top) / b.height);
    if (file < 0 || file > 7 || rank < 0 || rank > 7) return null;
    if (ground.state.orientation === 'black') file = 7 - file; else rank = 7 - rank;
    return String.fromCharCode(97 + file) + (rank + 1);
  };
  const consume = event => { event.preventDefault(); event.stopImmediatePropagation(); };
  element.addEventListener('touchstart', event => {
    if (!event.target.closest('.cg-board') || !enabled()) { cancel(); return; }
    if (event.touches.length !== 1) { cancel(); clear(); return; }
    const touch = event.touches[0], square = squareAt(touch);
    const back = correction && correction();
    if (back && square === back.to && (!ground.state.selected || ground.state.selected === square)) {
      // Let Chessground render the actual piece under the finger. Only its drop
      // is intercepted: a reverse gesture is an undo, never a chess move.
      gesture = {square, id:touch.identifier, x:touch.clientX, y:touch.clientY, correction:back};
      return;
    }
    if (!square || ground.state.selected || !canTap(square)) { cancel(); clear(); return; }
    gesture = {square, id:touch.identifier, x:touch.clientX, y:touch.clientY};
    consume(event);
  }, {capture:true, passive:false});
  element.addEventListener('touchmove', event => {
    if (!gesture) return;
    const touch = Array.from(event.touches).find(t => t.identifier === gesture.id);
    if (event.touches.length !== 1 || !touch) { consume(event); cancel(); clear(); return; }
    if (Math.hypot(touch.clientX-gesture.x,touch.clientY-gesture.y) > 10) gesture.moved = true;
    if (!gesture.correction) consume(event);
  }, {capture:true, passive:false});
  element.addEventListener('touchend', event => {
    if (!gesture) return;
    consume(event);
    const pending = gesture; gesture = null;
    const touch = Array.from(event.changedTouches).find(t => t.identifier === pending.id);
    if (pending.correction) ground.cancelMove();
    if (event.touches.length || !touch || !enabled()) return;
    const square = squareAt(touch);
    if (pending.correction && pending.moved && square === pending.correction.from) undo(pending.square, square);
    else if (!pending.moved && square === pending.square && canTap(square)) tap(square);
  }, {capture:true, passive:false});
  element.addEventListener('touchcancel', () => { cancel(); clear(); }, true);
  document.addEventListener('pointerdown', event => { if (!element.contains(event.target) && !keepOutside(event.target)) { cancel(); clear(); } }, true);
  // Keyboard/assistive-technology activations also end a correction session.
  document.addEventListener('click', event => { if (!element.contains(event.target) && !keepOutside(event.target)) { cancel(); clear(); } }, true);
  return {cancel};
}
