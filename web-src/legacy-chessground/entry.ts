/*
 * GPL-3.0-or-later: adapter around the verbatim lichobile 063 Chessground
 * source. It deliberately lives outside src/ so the upstream source snapshot
 * stays mechanically comparable to its provenance revision.
 */
import Chessground from './src/Chessground'
import { InitConfig } from './src/interfaces'

export function LegacyChessground(element: HTMLElement, config: InitConfig = {} as InitConfig): Chessground {
  const board = new Chessground(config)
  board.attach(element)
  return board
}

declare global {
  interface Window {
    /** Factory API: LegacyChessground(element, config) -> attached controller. */
    LegacyChessground: typeof LegacyChessground
    /** Original constructor API for callers that attach manually. */
    LegacyChessground063: typeof Chessground
  }
}

window.LegacyChessground = LegacyChessground
window.LegacyChessground063 = Chessground
