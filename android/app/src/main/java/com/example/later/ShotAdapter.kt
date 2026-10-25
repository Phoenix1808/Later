package com.example.later

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Locale

class ShotAdapter(
    private val onDone: (Shot) -> Unit,
    private val onRetry: (Shot) -> Unit
) : RecyclerView.Adapter<ShotAdapter.Holder>() {

    private var items: List<Shot> = emptyList()

    fun submit(newItems: List<Shot>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_shot, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val kindIcon: ImageView = view.findViewById(R.id.kindIcon)
        private val kindText: TextView = view.findViewById(R.id.kindText)
        private val ageText: TextView = view.findViewById(R.id.ageText)
        private val titleText: TextView = view.findViewById(R.id.titleText)
        private val summaryText: TextView = view.findViewById(R.id.summaryText)
        private val metaText: TextView = view.findViewById(R.id.metaText)
        private val actionRow: LinearLayout = view.findViewById(R.id.actionRow)
        private val primaryButton: MaterialButton = view.findViewById(R.id.primaryButton)
        private val doneButton: MaterialButton = view.findViewById(R.id.doneButton)

        fun bind(shot: Shot) {
            val context = itemView.context
            val uri = Uri.parse(shot.uri)

            // Never show a preview of a private screenshot (OTP, bank screen) - not even small
            if (shot.kind == Kind.PRIVATE) {
                thumb.scaleType = ImageView.ScaleType.CENTER
                thumb.setImageResource(R.drawable.ic_kind_private)
                thumb.setColorFilter(ContextCompat.getColor(context, R.color.kind_muted))
            } else {
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                thumb.clearColorFilter()
                thumb.setImageBitmap(Screenshots.thumbnail(context, uri, 160))
            }
            thumb.setOnClickListener {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.no_app, Toast.LENGTH_SHORT).show()
                }
            }
            ageText.text = "· " + DateUtils.getRelativeTimeSpanString(
                shot.takenAtSec * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )

            val style = KindStyle.of(if (shot.status == ShotStatus.READ) shot.kind else null)
            val color = ContextCompat.getColor(context, style.color)
            kindIcon.setImageResource(style.icon)
            kindIcon.setColorFilter(color)
            kindText.setText(style.label)
            kindText.setTextColor(color)

            val analysis = shot.analysis
            when {
                shot.status == ShotStatus.PENDING -> {
                    titleText.text = context.getString(R.string.kind_pending)
                    summaryText.text = context.getString(R.string.pending_summary)
                    metaText.visibility = View.GONE
                    actionRow.visibility = View.GONE
                }
                shot.status == ShotStatus.FAILED || analysis == null -> {
                    titleText.text = context.getString(R.string.failed_summary)
                    summaryText.text = ""
                    metaText.visibility = View.GONE
                    actionRow.visibility = View.VISIBLE
                    primaryButton.visibility = View.VISIBLE
                    primaryButton.setText(R.string.retry)
                    primaryButton.setOnClickListener { onRetry(shot) }
                    doneButton.visibility = View.GONE
                }
                else -> {
                    titleText.text = analysis.title
                    summaryText.text = analysis.summary
                    val meta = metaLines(analysis)
                    metaText.text = meta
                    metaText.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

                    val action = Actions.primaryFor(analysis)
                    primaryButton.visibility = if (action != null && !shot.handled) View.VISIBLE else View.GONE
                    action?.let { a ->
                        primaryButton.setText(a.label)
                        primaryButton.setOnClickListener {
                            try {
                                a.run(context)
                                if (a.label == R.string.action_copy) {
                                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, R.string.no_app, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    doneButton.visibility = if (shot.isActionable) View.VISIBLE else View.GONE
                    doneButton.setText(if (shot.handled) R.string.undo else R.string.done)
                    doneButton.setOnClickListener { onDone(shot) }
                    actionRow.visibility =
                        if (primaryButton.visibility == View.VISIBLE || doneButton.visibility == View.VISIBLE) View.VISIBLE
                        else View.GONE
                }
            }
        }

        private fun metaLines(a: Analysis): String {
            val lines = mutableListOf<String>()
            val whenText = listOfNotNull(a.date?.let(::prettyDate), a.time).joinToString(" · ")
            if (whenText.isNotEmpty()) lines += (if (a.kind == Kind.BILL || a.kind == Kind.TODO) "⏰ Due $whenText" else "📅 $whenText")
            a.amount?.let { lines += "💸 $it" }
            a.location?.let { lines += "📍 $it" }
            a.url?.let { lines += "🔗 " + (Uri.parse(it).host ?: it) }
            return lines.joinToString("\n")
        }

        private fun prettyDate(iso: String): String = try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
            SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            iso
        }
    }
}

// Icon, colour and label for each kind of screenshot
class KindStyle(val icon: Int, val color: Int, val label: Int) {
    companion object {
        fun of(kind: String?) = when (kind) {
            Kind.EVENT -> KindStyle(R.drawable.ic_kind_event, R.color.kind_event, R.string.kind_event)
            Kind.BILL -> KindStyle(R.drawable.ic_kind_bill, R.color.kind_bill, R.string.kind_bill)
            Kind.TODO -> KindStyle(R.drawable.ic_kind_todo, R.color.kind_todo, R.string.kind_todo)
            Kind.PLACE -> KindStyle(R.drawable.ic_kind_place, R.color.kind_place, R.string.kind_place)
            Kind.LINK -> KindStyle(R.drawable.ic_kind_link, R.color.kind_link, R.string.kind_link)
            Kind.NOTE -> KindStyle(R.drawable.ic_kind_note, R.color.kind_note, R.string.kind_note)
            Kind.PRIVATE -> KindStyle(R.drawable.ic_kind_private, R.color.kind_muted, R.string.kind_private)
            Kind.SKIP -> KindStyle(R.drawable.ic_kind_skip, R.color.kind_muted, R.string.kind_skip)
            else -> KindStyle(R.drawable.ic_kind_pending, R.color.kind_muted, R.string.kind_pending)
        }
    }
}
