package com.example.later

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

// The one notification a day: "here's what your screenshots turned into".
object Digest {

    private const val CHANNEL_ID = "digest"
    private const val NOTIFICATION_ID = 1

    data class Summary(val screenshots: Int, val toHandle: Int, val text: String)

    // Built on the phone from what's already been read - no extra AI call
    fun summarize(context: Context): Summary {
        val dayAgo = System.currentTimeMillis() / 1000 - 24 * 60 * 60
        val today = ShotStore(context).all().filter { it.takenAtSec >= dayAgo }
        val open = today.filter { it.isActionable && !it.handled }

        val parts = listOf(
            Kind.EVENT to "event", Kind.BILL to "bill", Kind.TODO to "to-do",
            Kind.PLACE to "place", Kind.LINK to "link", Kind.NOTE to "note"
        ).mapNotNull { (kind, word) ->
            val n = open.count { it.kind == kind }
            if (n == 0) null else "$n $word${if (n > 1) "s" else ""}"
        }
        val text = if (parts.isEmpty()) context.getString(R.string.digest_none)
        else parts.joinToString(", ") + ". " + context.getString(R.string.digest_tap)
        return Summary(today.size, open.size, text)
    }

    // Returns false if notifications aren't allowed
    fun show(context: Context, summary: Summary): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.digest_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kind_todo)
            .setContentTitle(context.getString(R.string.digest_title, summary.screenshots))
            .setContentText(summary.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }
}

// Runs once a day around 8 PM (see Scheduler)
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Catch screenshots from the last few minutes first, if the server answers
        if (Screenshots.hasPermission(applicationContext)) Scanner.scan(applicationContext)
        val summary = Digest.summarize(applicationContext)
        if (summary.screenshots > 0) Digest.show(applicationContext, summary)
        return Result.success()
    }
}
