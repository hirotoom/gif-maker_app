package com.example.dougasort

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = VideoRepository(app)
    private val store = MetaStore(app)
    val frames = FrameCache(app)

    private val _videos = MutableStateFlow<List<VideoItem>>(emptyList())
    private val _meta = MutableStateFlow(store.load())
    val meta: StateFlow<Map<Long, Meta>> = _meta

    val viewMode = MutableStateFlow(ViewMode.UNREVIEWED)
    val tagFilter = MutableStateFlow<String?>(null)

    /** Album = MediaStore RELATIVE_PATH (e.g. "Movies/ScreenRecord/"); null = all albums. */
    val albumFilter = MutableStateFlow<String?>(null)

    val videos: StateFlow<List<VideoItem>> = _videos

    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Remembered across launches. */
    val sortMode = MutableStateFlow(
        runCatching { SortMode.valueOf(prefs.getString("sort", null) ?: "") }.getOrDefault(SortMode.NEWEST),
    )

    fun setSortMode(mode: SortMode) {
        sortMode.value = mode
        prefs.edit().putString("sort", mode.name).apply()
    }

    private val filtered =
        combine(_videos, _meta, viewMode, tagFilter, albumFilter) { videos, meta, mode, tag, album ->
            videos.filter { v ->
                val m = meta[v.id] ?: Meta()
                mode.accepts(m.status) &&
                    (tag == null || tag in m.tags) &&
                    (album == null || v.folder == album)
            }
        }

    val visible: StateFlow<List<VideoItem>> =
        combine(filtered, sortMode) { list, sort -> sort.sort(list) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** (album path, video count), sorted by path. */
    val albums: StateFlow<List<Pair<String, Int>>> =
        _videos.map { list -> list.groupingBy { it.folder }.eachCount().toList().sortedBy { it.first } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val deleteItems: StateFlow<List<VideoItem>> = itemsWithStatus(Status.DELETE)
    val keepItems: StateFlow<List<VideoItem>> = itemsWithStatus(Status.KEEP)

    val allTags: StateFlow<List<String>> =
        _meta.map { m -> m.values.flatMap { it.tags }.distinct().sorted() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun itemsWithStatus(s: Status) =
        combine(_videos, _meta) { videos, meta -> videos.filter { meta[it.id]?.status == s } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repo.load() }
            _videos.value = list
            if (albumFilter.value != null && list.none { it.folder == albumFilter.value }) albumFilter.value = null
            val ids = list.mapTo(HashSet()) { it.id }
            _meta.update { m -> m.filterKeys { it in ids } }
            persist()
            withContext(Dispatchers.IO) { frames.prune(ids) }
        }
    }

    /** Pressing the same status again clears it. */
    fun setStatus(id: Long, status: Status) = update(id) {
        it.copy(status = if (it.status == status) Status.NONE else status)
    }

    fun toggleTag(id: Long, tag: String) = update(id) {
        it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag)
    }

    fun addTag(id: Long, tag: String) = update(id) { it.copy(tags = it.tags + tag) }

    /** Bulk: sets (not toggles) the status of every id. */
    fun setStatusAll(ids: Collection<Long>, status: Status) = updateAll(ids) { it.copy(status = status) }

    /** Bulk: adds [tag] to all ids, or removes it from all when [add] is false. */
    fun setTagAll(ids: Collection<Long>, tag: String, add: Boolean) = updateAll(ids) {
        it.copy(tags = if (add) it.tags + tag else it.tags - tag)
    }

    private fun updateAll(ids: Collection<Long>, f: (Meta) -> Meta) {
        _meta.update { m -> m + ids.associateWith { id -> f(m[id] ?: Meta()) } }
        persist()
    }

    /**
     * Moves one video into the album at [path] (e.g. "Movies/確認済"); the gallery shows it as an album.
     * Needs "all files access" to work without a system prompt. Returns false on failure.
     */
    private fun moveOne(uri: Uri, path: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.RELATIVE_PATH, path.trim('/') + "/")
        }
        return runCatching {
            getApplication<Application>().contentResolver.update(uri, values, null, null) > 0
        }.getOrDefault(false)
    }

    private fun toast(msg: String) = Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()

    fun moveTo(uris: List<Uri>, path: String) {
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) { uris.count { !moveOne(it, path) } }
            if (failed > 0) toast("${failed}件を移動できませんでした")
            refresh()
        }
    }

    /**
     * "済" stamp. If every item is already stamped, un-stamps them (moves each back to where it came
     * from); otherwise stamps the ones that are not yet (moves them into [DONE_ALBUM]).
     * The mark changes immediately and is reverted for any item whose move fails.
     */
    fun toggleDone(items: List<VideoItem>) {
        if (items.isEmpty()) return
        val meta = _meta.value
        val unstamp = items.all { meta[it.id]?.status == Status.KEEP }
        // Where each item is right now (a viewer's list can be a stale snapshot).
        val current = _videos.value.associateBy { it.id }

        class Job(val item: VideoItem, val before: Meta, val dest: String?)
        val jobs = items.mapNotNull { item ->
            val before = meta[item.id] ?: Meta()
            when {
                unstamp -> Job(item, before, before.origin)
                before.status != Status.KEEP -> Job(item, before, DONE_ALBUM)
                else -> null
            }
        }
        // One state update + one disk write for the whole batch (selections can be thousands of items).
        _meta.update { m ->
            m + jobs.associate { j ->
                val folder = (current[j.item.id] ?: j.item).folder
                j.item.id to if (unstamp) j.before.copy(status = Status.NONE, origin = null)
                else j.before.copy(status = Status.KEEP, origin = folder)
            }
        }
        persist()
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) {
                jobs.filter { j -> j.dest != null && !moveOne(j.item.uri, j.dest) }
            }
            if (failed.isNotEmpty()) {
                _meta.update { m -> m + failed.associate { it.item.id to it.before } }
                persist()
            }
            if (failed.isNotEmpty()) toast("${failed.size}件を移動できませんでした（全ファイルへのアクセスを確認）")
            refresh()
        }
    }

    private fun update(id: Long, f: (Meta) -> Meta) {
        _meta.update { m -> m + (id to f(m[id] ?: Meta())) }
        persist()
    }

    private fun persist() {
        val snapshot = _meta.value
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
    }
}
