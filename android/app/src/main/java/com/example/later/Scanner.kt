package com.example.later

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Finds new screenshots and asks the AI about them. Used by the background worker
// and by the "Scan now" button.
object Scanner {

    // The free model tier handles ~3-4 images a minute, so read a few per run;
    // the rest wait for the next run (every 15 minutes) or the next "Scan now".
    private const val MAX_READS_PER_RUN = 4
    private const val MAX_ATTEMPTS = 3
    // First time ever: only look back a couple of days, not years of screenshots
    private const val FIRST_LOOKBACK_SEC = 2 * 24 * 60 * 60L

    private val mutex = Mutex()   // the worker and the button must not scan at the same time

    data class Result(val newFound: Int, val read: Int, val stillWaiting: Int, val error: ApiException?)

    suspend fun scan(context: Context, onReading: (Int) -> Unit = {}): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            val store = ShotStore(context)

            // 1) Discover screenshots taken since the last look
            val nowSec = System.currentTimeMillis() / 1000
            val since = store.lastSeenSec.takeIf { it > 0 } ?: (nowSec - FIRST_LOOKBACK_SEC)
            val found = Screenshots.findSince(context, since, limit = 50)
            val newFound = store.addNew(found)
            found.maxOfOrNull { it.takenAtSec }?.let { if (it > store.lastSeenSec) store.lastSeenSec = it }
            if (store.lastSeenSec == 0L) store.lastSeenSec = since

            // 2) Read the newest waiting screenshots
            val waiting = store.all()
                .filter { it.status == ShotStatus.PENDING && it.attempts < MAX_ATTEMPTS }
                .sortedByDescending { it.takenAtSec }
            val batch = waiting.take(MAX_READS_PER_RUN)
            if (batch.isNotEmpty()) withContext(Dispatchers.Main) { onReading(batch.size) }

            val (today, weekday) = todayAndWeekday()
            var read = 0
            var error: ApiException? = null
            for (shot in batch) {
                try {
                    val jpeg = Screenshots.loadForUpload(context, Uri.parse(shot.uri))
                    val analysis = ApiClient.analyze(jpeg, today, weekday)
                    store.update(shot.mediaId) { it.copy(status = ShotStatus.READ, analysis = analysis) }
                    read++
                } catch (e: FileNotFoundException) {
                    store.remove(shot.mediaId)            // deleted before we got to it
                } catch (e: SecurityException) {
                    store.remove(shot.mediaId)            // access was revoked
                } catch (e: ApiException) {
                    error = e
                    // Busy or offline: stop here and leave the rest for the next run
                    if (e.code == 429 || e.code == 0) break
                    store.update(shot.mediaId) {
                        val attempts = it.attempts + 1
                        it.copy(attempts = attempts, status = if (attempts >= MAX_ATTEMPTS) ShotStatus.FAILED else ShotStatus.PENDING)
                    }
                }
            }
            Result(newFound, read, (waiting.size - read).coerceAtLeast(0), error)
        }
    }

    fun retry(context: Context, mediaId: Long) {
        ShotStore(context).update(mediaId) { it.copy(status = ShotStatus.PENDING, attempts = 0) }
    }

    // The AI needs today's date to turn "this Friday" into a real date
    private fun todayAndWeekday(): Pair<String, String> {
        val now = Date()
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) to
            SimpleDateFormat("EEEE", Locale.US).format(now)
    }
}

// Runs every ~15 minutes in the background (see Scheduler), even when the app is closed.
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Screenshots.hasPermission(applicationContext)) return Result.success()
        Scanner.scan(applicationContext)
        return Result.success()
    }
}
