package com.instinctazero.android

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.move.MoveList

/** Standard-chess one-ply geometry only: no evaluation, source data or recursive closure.
 * Shared across repertoires; bounded independently of the corpus and edit caches. */
internal object RepertoireLegalMoves {
    data class Candidate(val uci: String, val fen: String)
    private val cache = object : LinkedHashMap<String,List<Candidate>>(128,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,List<Candidate>>?) = size>256
    }
    private fun board(fen: String) = Board().apply { loadFromFen(RepertoireStore.position(fen)+" 0 1") }
    private fun canonical(board: Board): String {
        val parts=board.getFen(false).split(' ').toMutableList()
        // Capturability must be legal, not merely an adjacent pawn (pinned EP).
        if(parts[3]!="-" && board.legalMoves().none { move ->
            move.to==board.enPassant && board.getPiece(move.from).pieceType.name=="PAWN" &&
                board.getPiece(move.to)==Piece.NONE
        })parts[3]="-"
        return parts.joinToString(" ")
    }
    @Synchronized fun from(fen: String): List<Candidate> = cache.getOrPut(RepertoireStore.position(fen)) {
        val board=board(fen)
        // Old synthetic test/custom-position records can have no kings. Never derive
        // moves from an invalid board, but keep their recorded material readable.
        if(board.getPieceLocation(Piece.WHITE_KING).size!=1 || board.getPieceLocation(Piece.BLACK_KING).size!=1) emptyList()
        else board.legalMoves().map { move ->
            check(board.doMove(move));val result=Candidate(move.toString(),canonical(board));board.undoMove();result
        }
    }
    fun san(fen: String, uci: String): String {
        val board=board(fen)
        val move=board.legalMoves().first { it.toString()==uci }
        return MoveList(board.fen).apply { add(move) }.toSanArray().single()
    }
}
