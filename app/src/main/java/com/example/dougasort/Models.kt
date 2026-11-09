package com.example.dougasort

import android.net.Uri

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long,
    val folder: String,
    /** MediaStore DATE_ADDED, in seconds. */
    val dateAdded: Long,
)

enum class SortMode(val label: String) {
    NEWEST("新しい順"),
    OLDEST("古い順"),
    LARGEST("サイズ大きい順"),
    SMALLEST("サイズ小さい順");

    fun sort(list: List<VideoItem>): List<VideoItem> = when (this) {
        NEWEST -> list.sortedByDescending { it.dateAdded }
        OLDEST -> list.sortedBy { it.dateAdded }
        LARGEST -> list.sortedByDescending { it.size }
        SMALLEST -> list.sortedBy { it.size }
    }
}

/** KEEP is the "済" (reviewed) stamp: the file lives in [DONE_ALBUM] until the stamp is removed. */
enum class Status { NONE, KEEP, DELETE }

/** Album (MediaStore RELATIVE_PATH) that stamped videos are moved into. */
const val DONE_ALBUM = "Movies/確認済"

data class Meta(
    val status: Status = Status.NONE,
    val tags: Set<String> = emptySet(),
    /** Album the video was in before it was stamped, so un-stamping can put it back. */
    val origin: String? = null,
)

enum class ViewMode(val label: String) {
    UNREVIEWED("未確認"),
    HIDE_DONE("済を除く"),
    ALL("全部(済を含む)"),
    DONE("済のみ"),
    DELETE("削除候補");

    fun accepts(s: Status) = when (this) {
        UNREVIEWED -> s == Status.NONE
        HIDE_DONE -> s != Status.KEEP
        ALL -> true
        DONE -> s == Status.KEEP
        DELETE -> s == Status.DELETE
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1fGB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.0fMB".format(bytes / 1048576.0)
    else -> "${bytes / 1024}KB"
}
