@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.dougasort

import android.app.Activity
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private val KeepColor = Color(0xFF2E7D32)
private val DeleteColor = Color(0xFFC62828)
private val NeutralColor = Color(0xFF4A4A4A)

/** Most videos handed to one system delete confirmation. */
private const val DELETE_CHUNK = 500

/** Viewer playlist window: how many videos before/after the opened one are loaded up front. */
private const val VIEWER_BEHIND = 10
private const val VIEWER_AHEAD = 60
private const val VIEWER_REFILL = 50

/** Smallest time a full-width drag covers (finger at the bottom of the video). */
internal const val MIN_SPAN_MS = 500L

/**
 * Suspends until [ms]'s seek is actually reflected by the player, instead of a fixed delay — this
 * paces scrubbing to what the decoder can really keep up with, so seeks never pile up faster than
 * they can be shown. Bounded by a timeout in case a seek resolves to the same frame and never fires
 * a discontinuity (e.g. two targets snapping to the same keyframe).
 */
internal suspend fun ExoPlayer.seekAndAwait(ms: Long) {
    // Many targets land in the same GOP and resolve to a keyframe the player is already at, so no
    // onPositionDiscontinuity fires at all — waiting out a timeout for every one of those made
    // scrubbing update at ~5fps. onEvents fires for every seek the player actually processes
    // (moved or not), so it's a much more reliable "this seek is done" signal than discontinuity.
    seekTo(ms)
    val t0 = android.os.SystemClock.uptimeMillis()
    val completed = withTimeoutOrNull(120) {
        suspendCancellableCoroutine<Unit> { cont ->
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                        events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                    ) {
                        removeListener(this)
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                }
            }
            addListener(listener)
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            cont.invokeOnCancellation { main.post { removeListener(listener) } }
        }
    }
    android.util.Log.d(
        "ScrubPerf",
        "seek to $ms took ${android.os.SystemClock.uptimeMillis() - t0}ms, " +
            if (completed != null) "resolved via onEvents" else "TIMED OUT",
    )
}

internal fun spanText(ms: Long): String = when {
    ms < 1_000 -> "%.1f秒".format(ms / 1000.0)
    ms < 60_000 -> "%.0f秒".format(ms / 1000.0)
    else -> "%d分%02d秒".format(ms / 60_000, ms / 1000 % 60)
}

/**
 * How much time a full-width drag covers, relative to where the finger went down: the middle of
 * [MIN_SPAN_MS]..whole video at the start, [dy] px up (negative) widens it to the whole video and
 * [dy] px down narrows it to [MIN_SPAN_MS]; [rangePx] is the travel for the full range.
 */
internal fun scrubSpanRelative(totalMs: Long, dy: Float, rangePx: Float): Double {
    val total = totalMs.coerceAtLeast(MIN_SPAN_MS).toDouble()
    val min = MIN_SPAN_MS.toDouble()
    val start = Math.sqrt(total * min)
    val span = start * Math.pow(Math.sqrt(total / min), (-dy / rangePx).toDouble())
    return span.coerceIn(min, total)
}

/** Finger travel (px) that sweeps the whole sensitivity range: 30% of the screen height. */
@Composable
internal fun rememberScrubRange(): Float {
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current
    return with(density) { cfg.screenHeightDp.dp.toPx() * 0.3f }
}

/** Floor between issued seeks while dragging, so the renderer has time to actually paint each one. */
internal const val MIN_FRAME_INTERVAL_MS = 32L

/**
 * Horizontal scrub gesture shared by the viewer and the grid cells. Sensitivity is relative to where
 * the finger went down (up = coarser, down = finer, see [scrubSpanRelative]). [seek] is paced by
 * [ExoPlayer.seekAndAwait] — only one seek in flight, always the latest target — so it never queues
 * more than the decoder can keep up with. On release, one frame-accurate seek settles on the exact
 * position.
 */
internal suspend fun PointerInputScope.scrubGesture(
    totalMs: Long,
    rangePx: Float,
    startPosition: () -> Long,
    seek: suspend (ms: Long, exact: Boolean) -> Unit,
    label: (String?) -> Unit,
    onFinish: () -> Unit,
) = coroutineScope {
    val total = totalMs.coerceAtLeast(MIN_SPAN_MS)
    var target = 0L
    var startY = 0f
    var pending: Long? = null
    var seekJob: Job? = null
    var lastIssuedAt = 0L

    fun pump() {
        if (seekJob?.isActive == true) return
        val ms = pending ?: return
        pending = null
        seekJob = launch {
            // The player can report a seek "done" before it has actually painted the frame; issuing
            // the next one immediately then races ahead of what's visible. A small floor gives the
            // renderer room to catch up, so the preview visibly tracks the finger.
            val wait = lastIssuedAt + MIN_FRAME_INTERVAL_MS - android.os.SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            lastIssuedAt = android.os.SystemClock.uptimeMillis()
            // Exact decode cost is bounded by the keyframe interval, not by how far the jump is, so
            // there is no "coarse" mode to fall back to — keyframe-snapping just silently sits on the
            // same frame for long-GOP footage, which looked like scrubbing wasn't registering at all.
            seek(ms, true)
            pump()
        }
    }

    fun finish() {
        seekJob?.cancel()
        pending = null
        launch { seek(target, true) } // settle on the exact frame
        label(null)
        onFinish()
    }

    detectHorizontalDragGestures(
        onDragStart = { down ->
            startY = down.y
            target = startPosition()
            pending = null
        },
        onDragEnd = { finish() },
        onDragCancel = { finish() },
    ) { change, dx ->
        change.consume()
        val span = scrubSpanRelative(totalMs, change.position.y - startY, rangePx)
        target = (target + dx / size.width * span).toLong().coerceIn(0L, totalMs)
        label(scrubText(span.toLong(), total, target))
        pending = target
        pump()
    }
}

/** HUD text while scrubbing: how coarse the drag is, plus the exact position. */
internal fun scrubText(spanMs: Long, totalMs: Long, posMs: Long): String {
    val mode = when {
        spanMs >= totalMs * 9 / 10 -> "全体"
        spanMs < 3_000 -> "微調整"
        else -> "中間"
    }
    val s = posMs / 1000
    val pos = "%d:%02d.%d".format(s / 60, s % 60, posMs % 1000 / 100)
    return "$mode（全幅=${spanText(spanMs)}）  $pos"
}

/** Button with explicit white bold text so it stays readable on every background (incl. disabled). */
@Composable
internal fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = NeutralColor,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF2A2A2A),
            disabledContentColor = Color(0xFF9A9A9A),
        ),
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1) }
}

@Composable
fun AppScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val items by vm.visible.collectAsState()
    val meta by vm.meta.collectAsState()
    val tags by vm.allTags.collectAsState()
    val viewMode by vm.viewMode.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val tagFilter by vm.tagFilter.collectAsState()
    val deleteItems by vm.deleteItems.collectAsState()
    val albums by vm.albums.collectAsState()
    val albumFilter by vm.albumFilter.collectAsState()

    // The viewer works on a snapshot so marking an item (which can drop it from the filtered grid)
    // does not shift the playlist under the user.
    var viewer by remember { mutableStateOf<Pair<List<VideoItem>, Int>?>(null) }
    var tagTarget by remember { mutableStateOf<VideoItem?>(null) }
    var moveItems by remember { mutableStateOf<List<VideoItem>?>(null) }
    var renameAlbum by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showBulkTag by remember { mutableStateOf(false) }
    var columns by remember { mutableIntStateOf(2) }
    // Selection is limited to what is currently listed, so a filter change can't act on hidden items.
    val selectedItems = items.filter { it.id in selectedIds }

    // Big deletions go through the system in chunks (one confirmation each): a single request with
    // thousands of URIs can exceed the IPC size limit.
    var deleteQueue by remember { mutableStateOf<List<List<VideoItem>>>(emptyList()) }
    val launchDeleteChunk = remember { arrayOfNulls<(List<VideoItem>) -> Unit>(1) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val next = deleteQueue.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && next != null) {
            deleteQueue = deleteQueue.drop(1)
            launchDeleteChunk[0]?.invoke(next)
        } else {
            deleteQueue = emptyList()
            vm.refresh()
        }
    }
    launchDeleteChunk[0] = { chunk ->
        // Moves to the gallery's trash (restorable for ~30 days) instead of deleting for good.
        val sender = MediaStore.createTrashRequest(ctx.contentResolver, chunk.map { it.uri }, true).intentSender
        deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    // Kept here (not inside the grid) so the scroll position survives opening the viewer.
    val gridState = rememberLazyGridState()

    // "All files access" lets us move without a system prompt.
    val startMove: (List<VideoItem>, String) -> Unit = { moving, path ->
        if (moving.isNotEmpty()) {
            selectedIds = emptySet()
            vm.moveTo(moving.map { it.uri }, path)
        }
    }

    // System confirmation dialog, then the files go to the trash.
    val requestDelete: (List<VideoItem>) -> Unit = { doomed ->
        if (doomed.isNotEmpty()) {
            selectedIds = emptySet()
            val chunks = doomed.chunked(DELETE_CHUNK)
            deleteQueue = chunks.drop(1)
            launchDeleteChunk[0]?.invoke(chunks.first())
        }
    }

    // Look-ahead: build previews for the next couple of screens while the user looks at this one.
    LaunchedEffect(gridState, items, columns, viewer == null) {
        if (viewer != null) return@LaunchedEffect
        snapshotFlow { gridState.firstVisibleItemIndex + gridState.layoutInfo.visibleItemsInfo.size }
            .collectLatest { end ->
                delay(300) // skip while flinging; collectLatest cancels this on the next scroll step
                val to = minOf(items.size, end + columns * 8)
                for (i in minOf(end, items.size) until to) vm.frames.get(items[i], prefetch = true)
            }
    }

    val current = viewer
    if (current != null) {
        Viewer(
            items = current.first,
            startIndex = current.second,
            vm = vm,
            onTag = { tagTarget = it },
            onClose = { viewer = null },
        )
    } else Scaffold(
        topBar = {
            FilterBar(
                viewMode = viewMode,
                tagFilter = tagFilter,
                tags = tags,
                albums = albums,
                albumFilter = albumFilter,
                columns = columns,
                onColumns = { columns = if (columns == 1) 2 else 1 },
                sortMode = sortMode,
                onSort = { vm.setSortMode(it) },
                onViewMode = { vm.viewMode.value = it },
                onTag = { vm.tagFilter.value = if (tagFilter == it) null else it },
                onAlbum = { vm.albumFilter.value = it },
                onRenameAlbum = { renameAlbum = it },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .navigationBarsPadding()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (selectedItems.isNotEmpty()) {
                    val ids = selectedItems.map { it.id }
                    val allDone = selectedItems.all { meta[it.id]?.status == Status.KEEP }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${selectedItems.size}件選択", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        ActionButton("全選択", { selectedIds = items.map { it.id }.toSet() })
                        ActionButton("解除", { selectedIds = emptySet() })
                        ActionButton("ゴミ箱へ", { requestDelete(selectedItems) }, color = DeleteColor)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActionButton(
                            if (allDone) "✓済を戻す" else "✓済",
                            { vm.toggleDone(selectedItems); selectedIds = emptySet() },
                            Modifier.weight(1f),
                            KeepColor,
                        )
                        ActionButton(
                            "削除印",
                            { vm.setStatusAll(ids, Status.DELETE); selectedIds = emptySet() },
                            Modifier.weight(1f),
                            DeleteColor,
                        )
                        ActionButton("#タグ", { showBulkTag = true }, Modifier.weight(1f))
                        ActionButton("移動", { moveItems = selectedItems }, Modifier.weight(1f))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(
                            "ゴミ箱へ ${deleteItems.size}件 (${formatSize(deleteItems.sumOf { it.size })})",
                            { requestDelete(deleteItems) },
                            Modifier.weight(1f),
                            DeleteColor,
                            enabled = deleteItems.isNotEmpty(),
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("該当する動画がありません")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    VideoCell(
                        item = item,
                        meta = meta[item.id] ?: Meta(),
                        vm = vm,
                        aspect = if (columns == 1) 1f else 0.8f,
                        selected = item.id in selectedIds,
                        onSelect = {
                            selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                        },
                        onOpen = { viewer = items to items.indexOf(item) },
                        onTag = { tagTarget = item },
                    )
                }
            }
        }
    }

    tagTarget?.let { target ->
        TagDialog(
            item = target,
            current = meta[target.id]?.tags ?: emptySet(),
            all = tags,
            onToggle = { vm.toggleTag(target.id, it) },
            onAdd = { vm.addTag(target.id, it) },
            onDismiss = { tagTarget = null },
        )
    }

    if (showBulkTag) {
        var text by remember { mutableStateOf("") }
        val ids = selectedItems.map { it.id }
        AlertDialog(
            onDismissRequest = { showBulkTag = false },
            title = { Text("${selectedItems.size}件にタグを付ける") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("全件に付いているタグが選択状態です。押すと全件に付与／解除します。", fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { t ->
                            val onAll = selectedItems.isNotEmpty() && selectedItems.all { t in (meta[it.id]?.tags ?: emptySet()) }
                            FilterChip(selected = onAll, onClick = { vm.setTagAll(ids, t, !onAll) }, label = { Text(t) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            text, { text = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("新しいタグ") },
                        )
                        TextButton(onClick = {
                            val t = text.trim()
                            if (t.isNotEmpty()) vm.setTagAll(ids, t, true)
                            text = ""
                        }) { Text("追加") }
                    }
                }
            },
            confirmButton = { TextButton({ showBulkTag = false }) { Text("閉じる") } },
        )
    }

    moveItems?.let { moving ->
        var dest by remember { mutableStateOf<String?>(null) }
        var newName by remember { mutableStateOf("") }
        val target = if (newName.isNotBlank()) "Movies/${newName.trim()}" else dest
        AlertDialog(
            onDismissRequest = { moveItems = null },
            title = { Text("アルバムへ移動 (${moving.size}件)") },
            text = {
                Column {
                    Text("既存のアルバム", fontSize = 12.sp)
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(albums) { (path, count) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { dest = path; newName = "" },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = newName.isBlank() && dest == path, onClick = { dest = path; newName = "" })
                                Text("${path.trimEnd('/')} ($count)", fontSize = 14.sp)
                            }
                        }
                    }
                    OutlinedTextField(
                        newName, { newName = it },
                        singleLine = true,
                        label = { Text("または新規アルバム名（Movies/ 内）") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = target != null,
                    onClick = {
                        moveItems = null
                        target?.let { startMove(moving, it) }
                    },
                ) { Text("移動") }
            },
            dismissButton = { TextButton({ moveItems = null }) { Text("キャンセル") } },
        )
    }

    renameAlbum?.let { path ->
        var name by remember(path) { mutableStateOf(path.trimEnd('/').substringAfterLast('/')) }
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        AlertDialog(
            onDismissRequest = { renameAlbum = null },
            title = { Text("アルバム名を変更") },
            text = {
                Column {
                    Text("場所: $parent/", fontSize = 12.sp)
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("新しい名前") })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        renameAlbum = null
                        startMove(vm.videos.value.filter { it.folder == path }, "$parent/${name.trim()}")
                    },
                ) { Text("変更") }
            },
            dismissButton = { TextButton({ renameAlbum = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun FilterBar(
    viewMode: ViewMode,
    tagFilter: String?,
    tags: List<String>,
    albums: List<Pair<String, Int>>,
    albumFilter: String?,
    columns: Int,
    onColumns: () -> Unit,
    sortMode: SortMode,
    onSort: (SortMode) -> Unit,
    onViewMode: (ViewMode) -> Unit,
    onTag: (String) -> Unit,
    onAlbum: (String?) -> Unit,
    onRenameAlbum: (String) -> Unit,
) {
    Column(Modifier.statusBarsPadding()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                ActionButton(
                    if (columns == 1) "2列にする" else "1列にする",
                    onColumns,
                    Modifier.height(36.dp),
                )
            }
            item {
                var open by remember { mutableStateOf(false) }
                Box {
                    ActionButton("並び: ${sortMode.label}", { open = true }, Modifier.height(36.dp))
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text((if (m == sortMode) "✓ " else "") + m.label) },
                                onClick = { open = false; onSort(m) },
                            )
                        }
                    }
                }
            }
            items(ViewMode.entries) { mode ->
                FilterChip(selected = viewMode == mode, onClick = { onViewMode(mode) }, label = { Text(mode.label) })
            }
            items(tags) { t ->
                FilterChip(selected = tagFilter == t, onClick = { onTag(t) }, label = { Text("#$t") })
            }
        }
        // Albums = the gallery's folders (MediaStore RELATIVE_PATH).
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                FilterChip(selected = albumFilter == null, onClick = { onAlbum(null) }, label = { Text("全アルバム") })
            }
            items(albums) { (path, count) ->
                FilterChip(
                    selected = albumFilter == path,
                    onClick = { onAlbum(if (albumFilter == path) null else path) },
                    label = { Text("${path.trimEnd('/')} ($count)") },
                )
            }
            if (albumFilter != null && albumFilter.trimEnd('/').contains('/')) {
                item { TextButton(onClick = { onRenameAlbum(albumFilter) }) { Text("名前変更") } }
            }
        }
    }
}

/**
 * Idle: flip-book of cached frames. While dragging horizontally, a temporary muted player is laid
 * over the cell and scrubbed like in the viewer (finger height = coarse at top, fine at bottom).
 */
@Composable
private fun VideoCell(
    item: VideoItem,
    meta: Meta,
    vm: MainViewModel,
    aspect: Float,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onTag: () -> Unit,
) {
    val ctx = LocalContext.current
    val scrubRange = rememberScrubRange()
    val frames by produceState<List<Bitmap>>(emptyList(), item.id) { value = vm.frames.get(item) }
    // The system thumbnail is near-instant; it fills the cell until our own frames are ready.
    val thumb by produceState<Bitmap?>(null, item.id) { value = vm.frames.thumb(item) }
    var index by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var scrubLabel by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableLongStateOf(0L) }

    // Releases a scrub player when it is replaced/cleared or the cell leaves composition.
    DisposableEffect(scrubPlayer) {
        val p = scrubPlayer
        onDispose { p?.release() }
    }
    fun endScrub() {
        scrubbing = false
        scrubLabel = null
        scrubPlayer = null
    }

    LaunchedEffect(frames, scrubbing) {
        if (frames.size > 1 && !scrubbing) {
            while (true) {
                delay(450)
                index = (index + 1) % frames.size
            }
        }
    }

    val borderColor = when (meta.status) {
        Status.KEEP -> KeepColor
        Status.DELETE -> DeleteColor
        Status.NONE -> Color.Transparent
    }

    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .border(BorderStroke(3.dp, borderColor), MaterialTheme.shapes.medium)
            .background(Color(0xFF1B1B1B)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .background(Color.Black)
                .clickable(onClick = onOpen)
                .pointerInput(item.id) {
                    scrubGesture(
                        totalMs = item.durationMs,
                        rangePx = scrubRange,
                        startPosition = {
                            scrubbing = true
                            // Continue from the frame currently shown in the flip-book.
                            val start = if (frames.isEmpty()) 0L
                            else item.durationMs * (2L * index + 1) / (2L * frames.size)
                            target = start
                            scrubPlayer = ExoPlayer.Builder(ctx).build().apply {
                                volume = 0f
                                setMediaItem(MediaItem.fromUri(item.uri))
                                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                                playWhenReady = false
                                prepare()
                                seekTo(start)
                            }
                            start
                        },
                        seek = { ms, exact ->
                            target = ms
                            scrubPlayer?.let { p ->
                                p.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
                                p.seekAndAwait(ms)
                            }
                        },
                        label = { scrubLabel = it },
                        onFinish = { endScrub() },
                    )
                },
        ) {
            (frames.getOrNull(index) ?: thumb)?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            scrubPlayer?.let { p ->
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            useController = false
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            player = p
                        }
                    },
                    update = { if (it.player !== p) it.player = p },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            scrubLabel?.let {
                Text(
                    it,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .background(Color(0xCC000000), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                "${formatDuration(item.durationMs)}  ${formatSize(item.size)}",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(horizontal = 4.dp),
            )
            if (frames.size > 1 || scrubPlayer != null) {
                LinearProgressIndicator(
                    progress = {
                        if (scrubPlayer != null) (target.toFloat() / item.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
                        else (index + 1f) / frames.size
                    },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp),
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = { onSelect() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .background(Color(0xAA000000), MaterialTheme.shapes.small),
                colors = CheckboxDefaults.colors(uncheckedColor = Color.White, checkmarkColor = Color.White),
            )
        }
        if (meta.tags.isNotEmpty()) {
            Text(
                meta.tags.sorted().joinToString(" ") { "#$it" },
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        TriageButtons(
            status = meta.status,
            onDelete = { vm.setStatus(item.id, Status.DELETE) },
            onTag = onTag,
            onDone = { vm.toggleDone(listOf(item)) },
        )
    }
}

@Composable
private fun TriageButtons(
    status: Status,
    onDelete: () -> Unit,
    onTag: () -> Unit,
    onDone: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionButton("削除", onDelete, Modifier.weight(1f), if (status == Status.DELETE) DeleteColor else NeutralColor)
        ActionButton("#タグ", onTag, Modifier.weight(1f))
        ActionButton("✓済", onDone, Modifier.weight(1f), if (status == Status.KEEP) KeepColor else NeutralColor)
    }
}

/**
 * Full-screen player over the whole (snapshotted) list as one ExoPlayer playlist, so the next item
 * is already buffered when you move on. Marking keep/delete advances automatically.
 */
@Composable
private fun Viewer(
    items: List<VideoItem>,
    startIndex: Int,
    vm: MainViewModel,
    onTag: (VideoItem) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scrubRange = rememberScrubRange()
    val meta by vm.meta.collectAsState()
    var index by remember { mutableIntStateOf(startIndex) }

    // Only a window of the list lives in the player; more is appended as playback nears its end.
    // player index + base = index in [items].
    val base = remember { (startIndex - VIEWER_BEHIND).coerceAtLeast(0) }
    val loadedEnd = remember { intArrayOf(minOf(items.size, startIndex + VIEWER_AHEAD)) }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItems(items.subList(base, loadedEnd[0]).map { MediaItem.fromUri(it.uri) })
            seekTo(startIndex - base, 0)
            repeatMode = Player.REPEAT_MODE_ONE
            setSeekParameters(SeekParameters.CLOSEST_SYNC) // snap to keyframes: fast, smooth scrubbing
            playWhenReady = true
            prepare()
        }
    }
    var scrubLabel by remember { mutableStateOf<String?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            pos = player.currentPosition
            delay(100)
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = player.currentMediaItemIndex
                index = base + i
                if (i >= player.mediaItemCount - 10 && loadedEnd[0] < items.size) {
                    val end = minOf(items.size, loadedEnd[0] + VIEWER_REFILL)
                    player.addMediaItems(items.subList(loadedEnd[0], end).map { MediaItem.fromUri(it.uri) })
                    loadedEnd[0] = end
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    BackHandler(onBack = onClose)
    // Pause when another screen (e.g. the GIF tool) comes to the foreground, so it doesn't keep
    // playing in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }

    val item = items[index]
    val status = meta[item.id]?.status ?: Status.NONE
    val frames by produceState<List<Bitmap>>(emptyList(), item.id) { value = vm.frames.get(item) }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNext()
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        // Drag anywhere on the video to scrub (full width = whole video); tap to play/pause.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player; useController = false } },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(item.id) {
                        detectTapGestures(onTap = { if (player.isPlaying) player.pause() else player.play() })
                    }
                    .pointerInput(item.id) {
                        var resumeOnFinish = false
                        scrubGesture(
                            totalMs = item.durationMs,
                            rangePx = scrubRange,
                            startPosition = {
                                // Playback fighting our seeks is what made dragging look un-smooth.
                                resumeOnFinish = player.isPlaying
                                player.pause()
                                player.currentPosition
                            },
                            seek = { ms, exact ->
                                // Exact only for the settle-seek on release; keyframe-snapping (fast) while dragging.
                                player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
                                player.seekAndAwait(ms)
                                // The progress bar otherwise only polls every 100ms (see below), which
                                // lagged visibly behind the much faster scrub updates.
                                pos = ms
                            },
                            label = { scrubLabel = it },
                            onFinish = { if (resumeOnFinish) player.play() },
                        )
                    },
            )
            scrubLabel?.let {
                Text(
                    it,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(Color(0xCC000000), MaterialTheme.shapes.medium)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        LinearProgressIndicator(
            progress = { if (item.durationMs > 0) (pos.toFloat() / item.durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
        )
        Text(
            "${index + 1}/${items.size}  ${item.name}  ${formatDuration(item.durationMs)}  ${formatSize(item.size)}",
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        // Touch or drag along the frame strip to jump to that position.
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .pointerInput(item.id) {
                    coroutineScope {
                        fun timeAt(x: Float): Long {
                            val f = (x / size.width).coerceIn(0f, 1f)
                            return (f * item.durationMs).toLong()
                        }

                        var pending: Long? = null
                        var seekJob: Job? = null
                        var lastIssuedAt = 0L
                        fun pump() {
                            if (seekJob?.isActive == true) return
                            val ms = pending ?: return
                            pending = null
                            seekJob = launch {
                                val wait = lastIssuedAt + MIN_FRAME_INTERVAL_MS - android.os.SystemClock.uptimeMillis()
                                if (wait > 0) delay(wait)
                                lastIssuedAt = android.os.SystemClock.uptimeMillis()
                                player.seekAndAwait(ms)
                                pos = ms
                                pump()
                            }
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown()
                            // Same fix as the video drag: playback fighting our seeks made this look un-smooth.
                            val resumeOnRelease = player.isPlaying
                            player.pause()
                            player.setSeekParameters(SeekParameters.EXACT)
                            var last = timeAt(down.position.x)
                            pending = last
                            pump()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull()?.let {
                                    last = timeAt(it.position.x)
                                    pending = last
                                    pump()
                                    it.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            seekJob?.cancel()
                            val settleTarget = last
                            launch {
                                player.seekAndAwait(settleTarget) // settle on the final position
                                pos = settleTarget
                                if (resumeOnRelease) player.play()
                            }
                        }
                    }
                },
        ) {
            frames.forEach { bmp ->
                Image(bmp.asImageBitmap(), null, Modifier.weight(1f).fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton("戻る", onClose, Modifier.weight(1f))
                ActionButton(
                    "GIF化",
                    { ctx.startActivity(GifActivity.intent(ctx, item.uri, item.name)) },
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(
                    "削除",
                    { vm.setStatus(item.id, Status.DELETE); next() },
                    Modifier.weight(1f),
                    if (status == Status.DELETE) DeleteColor else NeutralColor,
                )
                ActionButton("#タグ", { onTag(item) }, Modifier.weight(1f))
                ActionButton(
                    "✓済",
                    {
                        // Un-stamping stays on this video; stamping moves on to the next one.
                        val wasDone = status == Status.KEEP
                        vm.toggleDone(listOf(item))
                        if (!wasDone) next()
                    },
                    Modifier.weight(1f),
                    if (status == Status.KEEP) KeepColor else NeutralColor,
                )
            }
        }
    }
}

@Composable
private fun TagDialog(
    item: VideoItem,
    current: Set<String>,
    all: List<String>,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    all.forEach { t ->
                        FilterChip(selected = t in current, onClick = { onToggle(t) }, label = { Text(t) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        text, { text = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("新しいタグ") },
                    )
                    TextButton(onClick = {
                        val t = text.trim()
                        if (t.isNotEmpty()) onAdd(t)
                        text = ""
                    }) { Text("追加") }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("閉じる") } },
    )
}
