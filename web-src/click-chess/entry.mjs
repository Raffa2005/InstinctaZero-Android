import {OriginRanker} from './ranker.mjs';
import modelBytes from './origin-ranker.bin';
export const ranker = OriginRanker.fromBinary(modelBytes);

// The app supplies legal moves and owns its study tree. Keep upstream Chessground
// untouched: intercept destination-only gestures before its piece/drag handlers.
export function bindDestinationInput({element, ground, enabled, canTap, tap, clear}) {
  let gesture = null;
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
    if (!event.target.closest('.cg-board') || !enabled()) { gesture = null; return; }
    if (event.touches.length !== 1) { gesture = null; clear(); return; }
    const touch = event.touches[0], square = squareAt(touch);
    if (!square || ground.state.selected || !canTap(square)) { gesture = null; clear(); return; }
    gesture = {square, id:touch.identifier, x:touch.clientX, y:touch.clientY};
    consume(event);
  }, {capture:true, passive:false});
  element.addEventListener('touchmove', event => {
    if (!gesture) return;
    consume(event);
    const touch = Array.from(event.touches).find(t => t.identifier === gesture.id);
    if (event.touches.length !== 1 || !touch || Math.hypot(touch.clientX-gesture.x,touch.clientY-gesture.y) > 10) gesture.canceled = true;
  }, {capture:true, passive:false});
  element.addEventListener('touchend', event => {
    if (!gesture) return;
    consume(event);
    const pending = gesture; gesture = null;
    const touch = Array.from(event.changedTouches).find(t => t.identifier === pending.id);
    if (!pending.canceled && !event.touches.length && touch && squareAt(touch) === pending.square && enabled()) tap(pending.square);
  }, {capture:true, passive:false});
  element.addEventListener('touchcancel', () => { gesture = null; clear(); }, true);
  document.addEventListener('pointerdown', event => { if (!element.contains(event.target)) { gesture = null; clear(); } }, true);
  // Keyboard/assistive-technology activations also end a correction session.
  document.addEventListener('click', event => { if (!element.contains(event.target)) { gesture = null; clear(); } }, true);
  return {cancel:()=>{gesture=null;}};
}
