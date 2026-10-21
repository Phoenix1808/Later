package com.example.later

// What the backend's AI says about one screenshot.
// Gson maps snake_case JSON to these camelCase names (see ApiClient).
data class Analysis(
    val kind: String,          // event | bill | todo | place | link | note | skip | private
    val title: String,
    val summary: String,
    val date: String?,         // YYYY-MM-DD
    val time: String?,         // HH:MM
    val location: String?,
    val amount: String?,
    val url: String?
)

enum class ShotStatus { PENDING, READ, FAILED }

// One screenshot as Later tracks it on the phone.
data class Shot(
    val mediaId: Long,          // MediaStore id - stable, used to avoid reading a screenshot twice
    val uri: String,
    val takenAtSec: Long,       // MediaStore DATE_ADDED (seconds)
    val status: ShotStatus = ShotStatus.PENDING,
    val analysis: Analysis? = null,
    val handled: Boolean = false,
    val attempts: Int = 0
) {
    val kind: String? get() = analysis?.kind

    // Memes and private screenshots never need doing
    val isActionable: Boolean
        get() = status == ShotStatus.READ && kind !in setOf(Kind.SKIP, Kind.PRIVATE)
}

object Kind {
    const val EVENT = "event"
    const val BILL = "bill"
    const val TODO = "todo"
    const val PLACE = "place"
    const val LINK = "link"
    const val NOTE = "note"
    const val SKIP = "skip"
    const val PRIVATE = "private"
}
