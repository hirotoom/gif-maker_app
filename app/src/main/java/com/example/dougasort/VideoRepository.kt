package com.example.dougasort

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

class VideoRepository(private val context: Context) {

    fun load(): List<VideoItem> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val result = ArrayList<VideoItem>()
        context.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += VideoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "?",
                    durationMs = c.getLong(durCol),
                    size = c.getLong(sizeCol),
                    folder = c.getString(pathCol) ?: "",
                    dateAdded = c.getLong(addedCol),
                )
            }
        }
        return result
    }
}
