package com.example.later

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.annotation.StringRes
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// The one-tap follow-up for each kind of screenshot. These hand off to apps the user
// already has (calendar, maps, browser), so Later needs no calendar permission.
object Actions {

    class Action(@StringRes val label: Int, val run: (Context) -> Unit)

    fun primaryFor(analysis: Analysis): Action? = when (analysis.kind) {
        Kind.EVENT -> when {
            analysis.date != null -> Action(R.string.action_calendar) { calendar(it, analysis) }
            analysis.url != null -> Action(R.string.action_link) { open(it, Uri.parse(analysis.url)) }
            else -> null
        }
        Kind.BILL, Kind.TODO ->
            if (analysis.date != null) Action(R.string.action_reminder) { calendar(it, analysis) } else null
        Kind.PLACE ->
            analysis.location?.let { place -> Action(R.string.action_maps) { open(it, Uri.parse("geo:0,0?q=" + Uri.encode(place))) } }
        Kind.LINK ->
            if (analysis.url != null) Action(R.string.action_link) { open(it, Uri.parse(analysis.url)) }
            else Action(R.string.action_search) { open(it, Uri.parse("https://www.google.com/search?q=" + Uri.encode(analysis.title))) }
        Kind.NOTE -> Action(R.string.action_copy) { copy(it, analysis) }
        else -> null
    }

    // Pre-filled "new event" screen in whatever calendar app the phone uses
    private fun calendar(context: Context, a: Analysis) {
        val allDay = a.time == null
        val format = SimpleDateFormat(if (allDay) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm", Locale.US)
        // Android's calendar contract: all-day events start at midnight UTC, timed ones in local time.
        // Using local midnight here would land the event on the previous day in India (UTC+5:30).
        if (allDay) format.timeZone = TimeZone.getTimeZone("UTC")
        val begin = format.parse(if (allDay) a.date!! else "${a.date} ${a.time}")?.time ?: return
        val title = when (a.kind) {
            Kind.BILL -> "Pay: ${a.title}" + (a.amount?.let { " ($it)" } ?: "")
            Kind.TODO -> "To do: ${a.title}"
            else -> a.title
        }
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, listOfNotNull(a.summary, a.url).joinToString("\n"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        if (!allDay) intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60 * 1000)
        a.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        context.startActivity(intent)
    }

    private fun open(context: Context, uri: Uri) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun copy(context: Context, a: Analysis) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(a.title, "${a.title}\n${a.summary}"))
    }
}
