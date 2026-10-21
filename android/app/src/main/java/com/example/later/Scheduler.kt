package com.example.later

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

// WorkManager keeps these running across app restarts and phone reboots.
object Scheduler {

    private const val DIGEST_HOUR = 20   // 8 PM

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        // 15 minutes is the shortest interval Android allows for periodic background work
        workManager.enqueueUniquePeriodicWork(
            "scan",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        )
        workManager.enqueueUniquePeriodicWork(
            "digest",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DigestWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(millisUntil(DIGEST_HOUR), TimeUnit.MILLISECONDS)
                .build()
        )
    }

    private fun millisUntil(hour: Int): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }
}
