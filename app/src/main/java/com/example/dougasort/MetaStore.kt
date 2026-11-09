package com.example.dougasort

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists keep/delete marks and tags per MediaStore id in a small JSON file. */
class MetaStore(context: Context) {
    private val file = File(context.filesDir, "meta.json")

    fun load(): Map<Long, Meta> {
        if (!file.exists()) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            buildMap {
                root.keys().forEach { key ->
                    val o = root.getJSONObject(key)
                    val tags = o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                        ?: emptySet()
                    val status = runCatching { Status.valueOf(o.optString("status", "NONE")) }.getOrDefault(Status.NONE)
                    val origin = if (o.has("origin")) o.getString("origin") else null
                    put(key.toLong(), Meta(status, tags, origin))
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun save(map: Map<Long, Meta>) {
        val root = JSONObject()
        map.forEach { (id, m) ->
            if (m.status == Status.NONE && m.tags.isEmpty()) return@forEach
            val o = JSONObject().put("status", m.status.name).put("tags", JSONArray(m.tags.toList()))
            m.origin?.let { o.put("origin", it) }
            root.put(id.toString(), o)
        }
        file.writeText(root.toString())
    }
}
