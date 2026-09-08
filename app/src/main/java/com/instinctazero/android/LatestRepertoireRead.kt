package com.instinctazero.android

import android.os.CancellationSignal
import java.util.concurrent.atomic.AtomicReference

/** Replaceable reads only. Edits, Undo and installation keep their ordered worker. */
internal class LatestRepertoireRead {
    private val current = AtomicReference<CancellationSignal?>()
    fun begin(): CancellationSignal = CancellationSignal().also { next -> current.getAndSet(next)?.cancel() }
    fun cancel() { current.getAndSet(null)?.cancel() }
    fun isCurrent(signal: CancellationSignal) = current.get() === signal && !signal.isCanceled
}
