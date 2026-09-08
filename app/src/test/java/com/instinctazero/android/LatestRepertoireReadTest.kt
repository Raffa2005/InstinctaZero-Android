package com.instinctazero.android

import android.database.sqlite.SQLiteDatabase
import android.os.OperationCanceledException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LatestRepertoireReadTest {
    @Test fun newestGameInvalidatesRunningAndQueuedReadsAndLateCallbacks() {
        val gate=LatestRepertoireRead();val first=gate.begin();val second=gate.begin()
        assertTrue(first.isCanceled);assertFalse(gate.isCurrent(first));assertTrue(gate.isCurrent(second))
        val third=gate.begin();assertTrue(second.isCanceled);assertTrue(gate.isCurrent(third))
        gate.cancel();assertTrue(third.isCanceled);assertFalse(gate.isCurrent(third))
        assertTrue(gate.isCurrent(gate.begin()))
    }
    @Test fun cancellationInterruptsSqlAndReleasesTheSameWorkerForNewestLookup() {
        val worker=Executors.newSingleThreadExecutor();val gate=LatestRepertoireRead()
        val started=CountDownLatch(1);val stopped=CountDownLatch(1);val newestDone=CountDownLatch(1)
        val canceled=AtomicBoolean(false);val old=gate.begin()
        SQLiteDatabase.create(null).use { db ->
            try {
                worker.execute {
                    try {
                        started.countDown()
                        db.rawQuery("WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n<100000000) SELECT sum(n) FROM numbers",null,old).use { it.moveToFirst() }
                    } catch (_: OperationCanceledException) { canceled.set(true) }
                    finally { stopped.countDown() }
                }
                assertTrue(started.await(5,TimeUnit.SECONDS))
                // Let native SQLite enter the deliberately expensive read; no phone/server is used.
                Thread.sleep(25)
                val next=gate.begin()
                worker.execute {
                    if(gate.isCurrent(next))db.rawQuery("SELECT 1",null,next).use { assertTrue(it.moveToFirst());newestDone.countDown() }
                }
                assertTrue(stopped.await(5,TimeUnit.SECONDS));assertTrue(canceled.get())
                assertTrue(newestDone.await(5,TimeUnit.SECONDS))
            } finally { gate.cancel();worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS) }
        }
    }
}
