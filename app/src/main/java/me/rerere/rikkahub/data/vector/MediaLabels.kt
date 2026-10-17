package me.rerere.rikkahub.data.vector

/**
 * The text a media row is stored under.
 *
 * It has two jobs and they pull in opposite directions: it is what a search result is *shown* as,
 * so it has to be readable, and it is the only handle a caller has when it wants to jump to the
 * moment a hit came from, so it has to carry the time. It is not a transcript and never pretends to
 * be - the vector is what makes the row findable, this line is what makes it clickable.
 *
 * Deliberately not translated: it is a machine-written label that travels with the index, and the
 * index stores the text it was built with. A locale switch would otherwise leave half the library
 * labelled in the previous language.
 */
object MediaLabels {

    /** `Audio 1:05–2:20 · interview.m4a` */
    fun audio(fileName: String, startMs: Long, endMs: Long): String =
        "Audio ${clock(startMs)}–${clock(endMs)} · $fileName"

    /** `Video 3:15 · holiday.mp4` */
    fun frame(fileName: String, atMs: Long): String =
        "Video ${clock(atMs)} · $fileName"

    /**
     * `m:ss`, or `h:mm:ss` past an hour.
     *
     * Milliseconds are dropped on purpose: a hit is answered by seeking a player, and no player
     * needs better than a second, while the label is stored once per row.
     */
    fun clock(millis: Long): String {
        val totalSeconds = (if (millis < 0) 0 else millis) / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        } else {
            "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }
}
