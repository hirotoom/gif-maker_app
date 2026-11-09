package com.example.dougasort

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Native GIF export: trims/speeds/crops/resizes the chosen video into an animated GIF, entirely
 * on-device (ExoPlayer for the live preview, MediaMetadataRetriever for frame grabs, [GifEncoder]
 * to write the file). A 4-step flow (range / speed / settings / confirm) mirrors the layout of the
 * earlier WebView-hosted tool, rebuilt natively after a WebView `<video>` platform limitation on
 * this device kept that version from loading local video reliably.
 */
class GifActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java)
        if (uri == null) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_NAME) ?: "video"
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { GifScreen(uri, name, onClose = { finish() }) }
            }
        }
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"
        fun intent(ctx: Context, uri: Uri, name: String): Intent =
            Intent(ctx, GifActivity::class.java).putExtra(EXTRA_URI, uri).putExtra(EXTRA_NAME, name)
    }
}

private enum class GifStep(val label: String) { RANGE("範囲"), SPEED("速度"), SETTINGS("切り抜き"), CONFIRM("最終確認") }

private val SizePresets = listOf("小" to 320, "中" to 480, "大" to 720, "原寸" to Int.MAX_VALUE)
private val FpsPresets = listOf(30, 20, 15, 10) // matches the old web tool's "滑らかさ" presets/default
private val CropRatios = listOf("元" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f)
/** label to onProgress's sample-pixels-per-frame count: higher = truer colors, slower to build. */
private val QualityPresets = listOf("高" to 60_000, "標準" to 20_000, "軽量" to 6_000)
private const val STEP_FRAME_MS = 1000L / 30
private const val SPEED_MIN = 0.1f
private const val SPEED_MAX = 4f
private const val MIN_CROP = 16 // smallest crop edge, source pixels

private fun cropRectFor(srcW: Int, srcH: Int, ratio: Float?): Rect {
    if (ratio == null || srcW <= 0 || srcH <= 0) return Rect(0, 0, srcW, srcH)
    val srcRatio = srcW.toFloat() / srcH
    return if (srcRatio > ratio) {
        val w = (srcH * ratio).toInt().coerceAtLeast(2)
        val x = (srcW - w) / 2
        Rect(x, 0, x + w, srcH)
    } else {
        val h = (srcW / ratio).toInt().coerceAtLeast(2)
        val y = (srcH - h) / 2
        Rect(0, y, srcW, y + h)
    }
}

/** Where the video actually renders within a [boxW]x[boxH] container under PlayerView's FIT mode. */
private data class VideoRect(val w: Float, val h: Float, val x: Float, val y: Float)

private fun fitVideoRect(boxW: Float, boxH: Float, srcW: Int, srcH: Int): VideoRect {
    val srcAr = srcW.toFloat() / srcH
    val boxAr = boxW / boxH
    return if (srcAr > boxAr) {
        val h = boxW / srcAr
        VideoRect(boxW, h, 0f, (boxH - h) / 2)
    } else {
        val w = boxH * srcAr
        VideoRect(w, boxH, (boxW - w) / 2, 0f)
    }
}

private fun speedToSlider(s: Float): Float =
    ((ln(s / SPEED_MIN) / ln(SPEED_MAX / SPEED_MIN))).coerceIn(0f, 1f)

private fun sliderToSpeed(p: Float): Float {
    val s = SPEED_MIN * (SPEED_MAX / SPEED_MIN).pow(p.coerceIn(0f, 1f))
    return (s / 0.05f).roundToInt() * 0.05f
}

@Composable
private fun GifScreen(videoUri: Uri, videoName: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var srcW by remember { mutableIntStateOf(0) }
    var srcH by remember { mutableIntStateOf(0) }

    LaunchedEffect(videoUri) {
        runCatching {
            withContext(Dispatchers.IO) { probeVideo(ctx, videoUri) }
        }.onSuccess { (dur, w, h) ->
            if (dur <= 0) error = "動画の長さを取得できませんでした" else { durationMs = dur; srcW = w; srcH = h }
        }.onFailure {
            error = it.message ?: "動画を読み込めませんでした"
        }
    }

    when {
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("エラー: $error") }
        durationMs <= 0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("動画を読み込み中...", Modifier.padding(top = 12.dp))
            }
        }
        else -> GifEditor(videoUri, videoName, durationMs, srcW.coerceAtLeast(16), srcH.coerceAtLeast(16), onClose)
    }
}

private fun probeVideo(ctx: Context, uri: Uri): Triple<Long, Int, Int> = MediaMetadataRetriever().use { r ->
    r.setDataSource(ctx, uri)
    val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
    Triple(dur, w, h)
}

@Composable
private fun GifEditor(videoUri: Uri, videoName: String, durationMs: Long, srcW: Int, srcH: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scrubRange = rememberScrubRange()

    var step by remember { mutableStateOf(GifStep.RANGE) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(durationMs) }
    var pos by remember { mutableLongStateOf(0L) }
    var target by remember { mutableStateOf("play") } // "start" / "play" / "end"
    var scrubLabel by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var cropRect by remember { mutableStateOf(Rect(0, 0, srcW, srcH)) }
    var sizeIdx by remember { mutableIntStateOf(2) } // default "大"(720) — "中"(480) read as too rough
    var fpsIdx by remember { mutableIntStateOf(0) } // default 30fps, matching the old web tool's default
    var qualityIdx by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var wasPlayingBeforeDrag by remember { mutableStateOf(false) }
    // Explicit "is the preview in play mode" flag, instead of reading player.isPlaying: that flag
    // flickers transiently during normal playback (mid-seek/buffer), and using it to gate the
    // loop-back-to-start check meant the check sometimes fired while the user was still manually
    // positioning the current-position marker past endMs — snapping it back to startMs even though
    // nothing was actually playing. This tracks only what WE told the player to do.
    var isPreviewPlaying by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
            prepare()
        }
    }
    // Hidden capture surface used only during GIF generation (see generateGif): invisible (alpha 0)
    // but must stay attached/non-zero-size so its SurfaceTexture is actually usable.
    var captureTextureView by remember { mutableStateOf<TextureView?>(null) }
    var previewPlayerView by remember { mutableStateOf<PlayerView?>(null) }
    AndroidView(
        factory = { c -> TextureView(c).also { captureTextureView = it } },
        modifier = Modifier.size(2.dp).graphicsLayer { alpha = 0f },
    )
    LaunchedEffect(player, startMs, endMs) {
        while (true) {
            // Preview playback loops within the trimmed range — playing past it defeats the point
            // of trimming. Gated on isPreviewPlaying (our own flag, not player.isPlaying — see
            // above) so this only snaps back during actual playback, never while the user is
            // manually positioning the current-position marker past endMs. Also skipped entirely
            // while dragging: this loop writing `pos` from the player every 100ms raced with the
            // drag's own `pos` updates, which is what made the drag look jittery instead of smooth.
            if (!dragging) {
                if (isPreviewPlaying && player.currentPosition >= endMs) player.seekTo(startMs)
                pos = player.currentPosition
            }
            delay(100)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // So the preview actually demonstrates the chosen speed instead of always playing at 1x.
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    val rangeLen = (endMs - startMs).coerceAtLeast(1)
    val outLenMs = (rangeLen / speed).toLong().coerceAtLeast(1)
    val fps = FpsPresets[fpsIdx]
    val frameCount = (outLenMs / 1000.0 * fps).toInt().coerceAtLeast(1)
    val (outW, outH) = gifOutputSize(cropRect.width().coerceAtLeast(1), cropRect.height().coerceAtLeast(1), SizePresets[sizeIdx].second)
    val sampleTarget = QualityPresets[qualityIdx].second
    val estSeconds = (frameCount * (0.03 + sampleTarget / 4_000_000.0) + 2).toInt()
    val heavy = outW.toLong() * outH * frameCount > 60_000_000L

    // Dragging the timeline fired a raw player.seekTo() per pixel of movement, which queued up
    // faster than the decoder could keep up — the preview looked like it wasn't tracking the drag
    // at all. Route every seek through the same paced "only the latest target, one at a time"
    // approach used for scrubbing in the main app.
    val seekScope = rememberCoroutineScope()
    var seekJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pendingSeek by remember { mutableStateOf<Long?>(null) }
    var lastSeekIssuedAt by remember { mutableLongStateOf(0L) }
    fun pumpSeek() {
        if (seekJob?.isActive == true) return
        val ms = pendingSeek ?: return
        pendingSeek = null
        seekJob = seekScope.launch {
            // Same floor as the main app's scrubGesture: the player can report a seek "done" before
            // it has actually painted the frame, so issuing the next one immediately races ahead of
            // what's visible, which read as "not smooth" even though every seek was landing.
            val wait = lastSeekIssuedAt + MIN_FRAME_INTERVAL_MS - android.os.SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            lastSeekIssuedAt = android.os.SystemClock.uptimeMillis()
            // CLOSEST_SYNC snaps to the nearest keyframe, which for long-GOP footage can silently
            // sit on the same frame across many drag events — that reads as jerky/stuck, not smooth.
            // Exact decode cost is bounded by the keyframe interval, not by jump distance, so (as in
            // the main app's scrubGesture) there's no benefit to the coarse mode — always go exact.
            player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            player.seekAndAwait(ms)
            pumpSeek()
        }
    }
    fun requestSeek(ms: Long) {
        pendingSeek = ms
        pumpSeek()
    }
    fun settleSeek(ms: Long) {
        seekScope.launch {
            player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            player.seekAndAwait(ms)
        }
    }

    fun applyPos(role: String, ms: Long) {
        val clamped = ms.coerceIn(0L, durationMs)
        when (role) {
            "start" -> { startMs = clamped.coerceAtMost(endMs - 100); if (endMs <= startMs) endMs = (startMs + 100).coerceAtMost(durationMs) }
            "end" -> { endMs = clamped.coerceAtLeast(startMs + 100) }
            else -> pos = clamped
        }
        requestSeek(clamped)
    }

    fun markerFor(role: String) = when (role) { "start" -> startMs; "end" -> endMs; else -> pos }

    fun applyRange(s: Long, e: Long) {
        val startChanged = s != startMs
        startMs = s.coerceIn(0L, durationMs)
        endMs = e.coerceIn(startMs + 100, durationMs).coerceAtLeast(startMs + 100)
        val seekTo = if (startChanged) startMs else endMs
        pos = seekTo
        requestSeek(seekTo)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("GIF化: $videoName", maxLines = 1, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
            StepDots(step)
        }

        // Preview: always visible, same scrub behavior as DougaSort's own viewer. On the settings
        // step the full frame shows with a draggable crop box; everywhere else, the inner box is
        // sized to exactly match the crop's aspect ratio and centered, and the video is zoomed/
        // panned so the crop fills it precisely — the framing you'll actually get is what you see.
        val showCropped = step != GifStep.SETTINGS
        val previewAr = (if (showCropped) cropRect.width().toFloat() / cropRect.height() else srcW.toFloat() / srcH)
            .takeIf { it.isFinite() && it > 0f } ?: (srcW.toFloat() / srcH)
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).heightIn(max = 340.dp),
            contentAlignment = Alignment.Center,
        ) {
            val availW = constraints.maxWidth.toFloat()
            val availH = constraints.maxHeight.toFloat()
            val boxW: Float; val boxH: Float
            if (availH <= 0f || previewAr > availW / availH.coerceAtLeast(1f)) {
                boxW = availW; boxH = if (previewAr > 0f) availW / previewAr else availH
            } else {
                boxH = availH; boxW = availH * previewAr
            }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val zoomTransform: Modifier = if (showCropped && boxW > 0f && boxH > 0f) {
                val (videoW, _, videoX, videoY) = fitVideoRect(boxW, boxH, srcW, srcH)
                val scale = videoW / srcW
                val cropBoxLeft = videoX + cropRect.left * scale
                val cropBoxTop = videoY + cropRect.top * scale
                val cropBoxW = (cropRect.width() * scale).coerceAtLeast(1f)
                // Container is already shaped to the crop's aspect ratio, so one zoom factor fills it exactly.
                val zoom = boxW / cropBoxW
                Modifier.graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = -cropBoxLeft * zoom
                    translationY = -cropBoxTop * zoom
                }
            } else Modifier
            // clipToBounds is required: the zoomed-in AndroidView draws past this box's edges
            // otherwise (graphicsLayer's own `clip` clips in the *pre-scale* local space, which
            // isn't enough once the layer is scaled up) — without it, the discarded-outside-crop
            // part of the video stayed visibly bleeding around the edges.
            Box(
                Modifier
                    .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                    .clipToBounds(),
            ) {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player; useController = false }.also { previewPlayerView = it } },
                    modifier = Modifier.fillMaxSize().then(zoomTransform),
                )
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(videoUri, step) {
                            if (step != GifStep.SETTINGS) {
                                detectTapGestures(onTap = {
                                    if (isPreviewPlaying) { player.pause(); isPreviewPlaying = false }
                                    else { player.play(); isPreviewPlaying = true }
                                })
                            }
                        }
                        .pointerInput(videoUri, step) {
                            if (step != GifStep.SETTINGS) {
                                var resumeOnFinish = false
                                scrubGesture(
                                    totalMs = durationMs,
                                    rangePx = scrubRange,
                                    startPosition = {
                                        resumeOnFinish = isPreviewPlaying
                                        player.pause()
                                        isPreviewPlaying = false
                                        dragging = true
                                        player.currentPosition
                                    },
                                    seek = { ms, _ -> player.seekAndAwait(ms); pos = ms },
                                    label = { scrubLabel = it },
                                    onFinish = {
                                        dragging = false
                                        if (resumeOnFinish) { player.play(); isPreviewPlaying = true }
                                    },
                                )
                            }
                        },
                )
                if (step == GifStep.SETTINGS && boxW > 0f && boxH > 0f) {
                    CropOverlay(boxW, boxH, srcW, srcH, cropRect) { cropRect = it }
                }
                scrubLabel?.let {
                    Text(
                        it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                            .background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("現在 ${formatDuration(pos)}", fontSize = 12.sp)
            ActionButton(
                if (isPreviewPlaying) "■ 停止" else "▶ 再生",
                {
                    if (isPreviewPlaying) { player.pause(); isPreviewPlaying = false }
                    else { player.play(); isPreviewPlaying = true }
                },
            )
            Text("全体 ${formatDuration(durationMs)}", fontSize = 12.sp)
        }

        // Fixed height across every step (tall enough for the busiest one, settings) so switching
        // tabs doesn't jump the layout around, while still fitting every step without scrolling.
        Column(
            // verticalScroll is just a safety net: content is sized to fit without scrolling.
            Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (step) {
                GifStep.RANGE -> RangeStep(
                    durationMs, startMs, endMs, pos, target,
                    onTarget = { target = it },
                    onRange = ::applyRange,
                    onSetStart = { applyPos("start", pos) },
                    onSetEnd = { applyPos("end", pos) },
                    onCurrent = { ms -> applyPos("play", ms) },
                    onSettle = { settleSeek(it) },
                    onStepFrames = { d -> applyPos(target, markerFor(target) + d * STEP_FRAME_MS) },
                    onDragStart = {
                        wasPlayingBeforeDrag = isPreviewPlaying
                        player.pause()
                        isPreviewPlaying = false
                        dragging = true
                    },
                    onDragEnd = {
                        dragging = false
                        if (wasPlayingBeforeDrag) { player.play(); isPreviewPlaying = true }
                    },
                )
                GifStep.SPEED -> SpeedStep(speed, outLenMs) { speed = it }
                GifStep.SETTINGS -> SettingsStep(
                    cropRect, sizeIdx, fpsIdx, qualityIdx, outW, outH,
                    onRatio = { cropRect = cropRectFor(srcW, srcH, it) },
                    onSize = { sizeIdx = it }, onFps = { fpsIdx = it }, onQuality = { qualityIdx = it },
                )
                GifStep.CONFIRM -> ConfirmStep(
                    startMs, endMs, speed, outLenMs, cropRect, outW, outH, fps, frameCount,
                    QualityPresets[qualityIdx].first, estSeconds, heavy,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(if (step == GifStep.RANGE) "閉じる" else "戻る", {
                if (step == GifStep.RANGE) onClose() else step = GifStep.entries[step.ordinal - 1]
            }, Modifier.weight(1f))
            if (step != GifStep.CONFIRM) {
                val nextStep = GifStep.entries[step.ordinal + 1]
                ActionButton("次へ(${nextStep.label})", { step = nextStep }, Modifier.weight(1f), AccentColor)
            } else if (!busy) {
                ActionButton(
                    "GIFを生成",
                    { player.pause(); busy = true; progress = 0f; status = "準備中..." },
                    Modifier.weight(1f), AccentColor,
                )
            }
        }
        if (busy) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(status, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    if (busy) {
        LaunchedEffect(Unit) {
            val result = runCatching {
                val tv = captureTextureView ?: error("キャプチャ用の画面が準備できていません")
                generateGif(
                    ctx = ctx, player = player, textureView = tv, srcW = srcW, srcH = srcH,
                    startMs = startMs, speed = speed, fps = fps,
                    crop = cropRect, outW = outW, outH = outH, frameCount = frameCount, sampleTarget = sampleTarget,
                    onProgress = { p, msg -> progress = p; status = msg },
                )
            }
            busy = false
            // Generation hijacks the player's video output to the hidden capture TextureView (see
            // generateGif) — reattach it to the visible preview PlayerView either way, success or
            // failure, so the live preview keeps working if the user stays on this screen.
            previewPlayerView?.player = player
            result.onSuccess {
                Toast.makeText(ctx, "ギャラリーに保存しました（Pictures/DougaGIF）", Toast.LENGTH_LONG).show()
                // Stay on this video instead of closing: picking a different range/crop on the same
                // clip to make another GIF is a common next action, and closing threw that away.
                step = GifStep.RANGE
            }.onFailure {
                Toast.makeText(ctx, "失敗しました: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun StepDots(step: GifStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        GifStep.entries.forEachIndexed { i, _ ->
            Box(
                Modifier.size(width = 16.dp, height = 5.dp)
                    .background(
                        if (i <= step.ordinal) AccentColor else NeutralChipColor,
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}

@Composable
private fun RangeStep(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    curMs: Long,
    target: String,
    onTarget: (String) -> Unit,
    onRange: (start: Long, end: Long) -> Unit,
    onSetStart: () -> Unit,
    onSetEnd: () -> Unit,
    onCurrent: (Long) -> Unit,
    onSettle: (Long) -> Unit,
    onStepFrames: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
) {
    val curState = rememberUpdatedState(curMs)
    Text(
        "開始 ${formatDuration(startMs)}　現在 ${formatDuration(curMs)}　終了 ${formatDuration(endMs)}" +
            "　長さ ${formatDuration(endMs - startMs)}",
        fontSize = 12.sp,
    )
    // Material's own RangeSlider handles ONLY the start/end thumbs — nothing else is drawn on or
    // near it, so it can never be mistaken for (or touch-conflict with) the current-position
    // control below, which is a fully separate track. Extra side padding keeps the thumbs off the
    // screen edge (Android's back-swipe zone).
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        RangeSlider(
            value = startMs.toFloat()..endMs.toFloat(),
            onValueChange = { r -> onRange(r.start.toLong(), r.endInclusive.toLong()) },
            onValueChangeFinished = { onSettle(curMs) },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
        )
    }
    Text("現在位置（下のバーをドラッグ。開始・終了には影響しません）", fontSize = 11.sp, color = Color(0xFF9AA3B2))
    BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 14.dp)) {
        val trackW = constraints.maxWidth.toFloat()
        val frac = if (durationMs > 0) (curMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier.fillMaxSize().background(Color(0xFF2A303B), RoundedCornerShape(6.dp))
                .pointerInput(durationMs) {
                    var base = 0L
                    detectDragGestures(
                        onDragStart = { onDragStart(); base = curState.value },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val deltaMs = (dragAmount.x / trackW * durationMs).toLong()
                            base = (base + deltaMs).coerceIn(0L, durationMs)
                            onCurrent(base)
                        },
                        onDragEnd = { onSettle(curState.value); onDragEnd() },
                        onDragCancel = { onSettle(curState.value); onDragEnd() },
                    )
                },
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((frac * trackW).roundToInt() - 6, 0) }
                .size(width = 12.dp, height = 24.dp)
                .background(AccentColor, RoundedCornerShape(3.dp)),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("現在位置を開始に", onSetStart, Modifier.weight(1f))
        ActionButton("現在位置を終了に", onSetEnd, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RepeatButton("◀◀ -5", Modifier.weight(1f)) { onStepFrames(-5) }
        RepeatButton("◀ -1", Modifier.weight(1f)) { onStepFrames(-1) }
        RepeatButton("+1 ▶", Modifier.weight(1f)) { onStepFrames(1) }
        RepeatButton("+5 ▶▶", Modifier.weight(1f)) { onStepFrames(5) }
    }
    Text("コマ送りの対象", fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GifChip("開始", target == "start", Modifier.weight(1f)) { onTarget("start") }
        GifChip("現在", target == "play", Modifier.weight(1f)) { onTarget("play") }
        GifChip("終了", target == "end", Modifier.weight(1f)) { onTarget("end") }
    }
}

/** Button that fires once immediately, then repeats (accelerating) while held — for frame-stepping. */
@Composable
private fun RepeatButton(text: String, modifier: Modifier = Modifier, onTrigger: () -> Unit) {
    val scope = rememberCoroutineScope()
    val triggerState = rememberUpdatedState(onTrigger)
    Box(
        modifier
            .background(NeutralChipColor, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    triggerState.value()
                    val job = scope.launch {
                        delay(400)
                        var interval = 140L
                        var elapsed = 0L
                        while (true) {
                            delay(interval)
                            triggerState.value()
                            elapsed += interval
                            interval = if (elapsed > 2500) 35 else if (elapsed > 1200) 70 else 140
                        }
                    }
                    waitForUpOrCancellation()
                    job.cancel()
                }
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1) }
}

@Composable
private fun SpeedStep(speed: Float, outLenMs: Long, onSpeed: (Float) -> Unit) {
    Text("速度", fontSize = 13.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("遅い", fontSize = 12.sp)
        Slider(
            value = speedToSlider(speed),
            onValueChange = { onSpeed(sliderToSpeed(it)) },
            modifier = Modifier.weight(1f),
        )
        Text("速い", fontSize = 12.sp)
    }
    Text("×${"%.2f".format(speed)}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.25f, 0.5f, 1f, 2f, 4f).forEach { v -> GifChip("×$v", speed == v, Modifier.weight(1f)) { onSpeed(v) } }
    }
    Text("出力されるGIFの長さ: ${"%.1f".format(outLenMs / 1000.0)}秒", fontSize = 13.sp)
    if (outLenMs > 10_000) {
        Text(
            "10秒を超えています。生成に時間がかかるか、端末のメモリ不足で失敗する場合があります。",
            fontSize = 12.sp, color = Color(0xFFF0B24A),
        )
    }
}

@Composable
private fun SettingsStep(
    crop: Rect, sizeIdx: Int, fpsIdx: Int, qualityIdx: Int, outW: Int, outH: Int,
    onRatio: (Float?) -> Unit, onSize: (Int) -> Unit, onFps: (Int) -> Unit, onQuality: (Int) -> Unit,
) {
    Text("切り抜き（プレビュー上の枠をドラッグでも調整できます）", fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CropRatios.forEach { (label, ratio) -> GifChip(label, false, Modifier.weight(1f)) { onRatio(ratio) } }
    }
    Text("画像サイズ（長辺 px）", fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SizePresets.forEachIndexed { i, (label, _) -> GifChip(label, sizeIdx == i, Modifier.weight(1f)) { onSize(i) } }
    }
    Text("滑らかさ（コマ/秒）", fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FpsPresets.forEachIndexed { i, v -> GifChip("$v", fpsIdx == i, Modifier.weight(1f)) { onFps(i) } }
    }
    Text("画質", fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QualityPresets.forEachIndexed { i, (label, _) -> GifChip(label, qualityIdx == i, Modifier.weight(1f)) { onQuality(i) } }
    }
    Text("切り抜き ${crop.width()}×${crop.height()} → 出力 ${outW}×${outH}px", fontSize = 12.sp)
}

@Composable
private fun ConfirmStep(
    startMs: Long, endMs: Long, speed: Float, outLenMs: Long, crop: Rect, outW: Int, outH: Int,
    fps: Int, frameCount: Int, qualityLabel: String, estSeconds: Int, heavy: Boolean,
) {
    Text("最終確認", fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val lines = listOf(
        "範囲 ${formatDuration(startMs)} 〜 ${formatDuration(endMs)}（${formatDuration(endMs - startMs)}）",
        "速度 ×${"%.2f".format(speed)} → 出力の長さ ${"%.1f".format(outLenMs / 1000.0)}秒",
        "切り抜き ${crop.width()}×${crop.height()} → 出力 ${outW}×${outH}px",
        "滑らかさ ${fps}コマ/秒（${frameCount}コマ）・ 画質 $qualityLabel",
        "生成にかかる時間の目安: 約${estSeconds}秒",
    )
    lines.forEach { Text(it, fontSize = 13.sp) }
    if (outLenMs > 10_000) {
        Text("10秒を超えています。生成に時間がかかる場合があります。", fontSize = 12.sp, color = Color(0xFFF0B24A))
    }
    if (heavy) {
        Text("コマ数×画素数が多く、端末のメモリ不足で失敗する可能性があります。", fontSize = 12.sp, color = Color(0xFFF0B24A))
    }
}

/** Non-interactive outline showing where [crop] sits on the full, unzoomed video — used outside
 *  the settings step so the framing stays visible without the fragile zoomed-preview math. */
@Composable
private fun CropOutline(boxW: Float, boxH: Float, srcW: Int, srcH: Int, crop: Rect) {
    val (videoW, videoH, videoX, videoY) = fitVideoRect(boxW, boxH, srcW, srcH)
    val fullFrame = crop.left == 0 && crop.top == 0 && crop.width() == srcW && crop.height() == srcH
    if (fullFrame) return
    val scale = videoW / srcW
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val l = videoX + crop.left * scale
        val t = videoY + crop.top * scale
        val r = videoX + crop.right * scale
        val bb = videoY + crop.bottom * scale
        val dim = Color(0x99000000)
        drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(videoX, videoY), size = androidx.compose.ui.geometry.Size(videoW, t - videoY))
        drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(videoX, bb), size = androidx.compose.ui.geometry.Size(videoW, videoY + videoH - bb))
        drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(videoX, t), size = androidx.compose.ui.geometry.Size(l - videoX, bb - t))
        drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(r, t), size = androidx.compose.ui.geometry.Size(videoX + videoW - r, bb - t))
        drawRect(
            Color.White, topLeft = androidx.compose.ui.geometry.Offset(l, t),
            size = androidx.compose.ui.geometry.Size(r - l, bb - t), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
        )
    }
}

/**
 * Freeform crop box drawn over the video at its true displayed rect (PlayerView letterboxes to
 * fit, so the video doesn't necessarily fill [boxW]x[boxH]). Corner handles resize; dragging inside
 * the box moves it; everything stays within the video's actual bounds.
 */
@Composable
private fun CropOverlay(boxW: Float, boxH: Float, srcW: Int, srcH: Int, crop: Rect, onCrop: (Rect) -> Unit) {
    val (videoW, videoH, videoX, videoY) = fitVideoRect(boxW, boxH, srcW, srcH)
    val scale = videoW / srcW // == videoH / srcH

    fun toBoxX(srcX: Int) = videoX + srcX * scale
    fun toBoxY(srcY: Int) = videoY + srcY * scale
    fun toSrcDx(boxDx: Float) = boxDx / scale

    val cropState = rememberUpdatedState(crop)

    fun dragFor(corner: String) = Modifier.pointerInput(srcW, srcH) {
        var base = Rect()
        detectDragGestures(
            onDragStart = { base = Rect(cropState.value) },
            onDrag = { change, dragAmount ->
                change.consume()
                val dx = toSrcDx(dragAmount.x).toInt()
                val dy = toSrcDx(dragAmount.y).toInt()
                var r = Rect(base)
                when (corner) {
                    "nw" -> { r.left = (r.left + dx).coerceIn(0, r.right - MIN_CROP); r.top = (r.top + dy).coerceIn(0, r.bottom - MIN_CROP) }
                    "ne" -> { r.right = (r.right + dx).coerceIn(r.left + MIN_CROP, srcW); r.top = (r.top + dy).coerceIn(0, r.bottom - MIN_CROP) }
                    "sw" -> { r.left = (r.left + dx).coerceIn(0, r.right - MIN_CROP); r.bottom = (r.bottom + dy).coerceIn(r.top + MIN_CROP, srcH) }
                    "se" -> { r.right = (r.right + dx).coerceIn(r.left + MIN_CROP, srcW); r.bottom = (r.bottom + dy).coerceIn(r.top + MIN_CROP, srcH) }
                    "n" -> r.top = (r.top + dy).coerceIn(0, r.bottom - MIN_CROP)
                    "s" -> r.bottom = (r.bottom + dy).coerceIn(r.top + MIN_CROP, srcH)
                    "w" -> r.left = (r.left + dx).coerceIn(0, r.right - MIN_CROP)
                    "e" -> r.right = (r.right + dx).coerceIn(r.left + MIN_CROP, srcW)
                    "move" -> {
                        val w = r.width(); val h = r.height()
                        val nl = (r.left + dx).coerceIn(0, srcW - w)
                        val nt = (r.top + dy).coerceIn(0, srcH - h)
                        r = Rect(nl, nt, nl + w, nt + h)
                    }
                }
                base = r
                onCrop(r)
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        // Dim outside the crop, leave the crop area clear, via four strips (top/bottom/left/right).
        val dim = Color(0xAA000000)
        Box(Modifier.offset { IntOffset(0, 0) }.size(0.dp)) // keep BoxScope happy with no-op anchor
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val l = toBoxX(crop.left); val t = toBoxY(crop.top); val r = toBoxX(crop.right); val b = toBoxY(crop.bottom)
            drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(videoX, videoY), size = androidx.compose.ui.geometry.Size(videoW, t - videoY))
            drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(videoX, b), size = androidx.compose.ui.geometry.Size(videoW, videoY + videoH - b))
            drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(videoX, t), size = androidx.compose.ui.geometry.Size(l - videoX, b - t))
            drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(r, t), size = androidx.compose.ui.geometry.Size(videoX + videoW - r, b - t))
            drawRect(
                Color.White, topLeft = androidx.compose.ui.geometry.Offset(l, t),
                size = androidx.compose.ui.geometry.Size(r - l, b - t), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
            )
        }
        val cropWidthDp = with(androidx.compose.ui.platform.LocalDensity.current) { (toBoxX(crop.right) - toBoxX(crop.left)).toDp() }
        val cropHeightDp = with(androidx.compose.ui.platform.LocalDensity.current) { (toBoxY(crop.bottom) - toBoxY(crop.top)).toDp() }
        val leftDp = with(androidx.compose.ui.platform.LocalDensity.current) { toBoxX(crop.left).toDp() }
        val topDp = with(androidx.compose.ui.platform.LocalDensity.current) { toBoxY(crop.top).toDp() }
        Box(
            Modifier.offset(x = leftDp, y = topDp).size(cropWidthDp, cropHeightDp)
                .then(dragFor("move")),
        )
        // Edge strips let the whole side be dragged (not just the corners) to resize one axis at a
        // time, inset from the corners so the (larger) corner touch targets still take priority there.
        val edgeThickness = 32.dp
        val handleTouch = 40.dp
        Box(
            Modifier.offset(x = leftDp + handleTouch / 2, y = topDp - edgeThickness / 2)
                .size((cropWidthDp - handleTouch).coerceAtLeast(0.dp), edgeThickness)
                .then(dragFor("n")),
        )
        Box(
            Modifier.offset(x = leftDp + handleTouch / 2, y = topDp + cropHeightDp - edgeThickness / 2)
                .size((cropWidthDp - handleTouch).coerceAtLeast(0.dp), edgeThickness)
                .then(dragFor("s")),
        )
        Box(
            Modifier.offset(x = leftDp - edgeThickness / 2, y = topDp + handleTouch / 2)
                .size(edgeThickness, (cropHeightDp - handleTouch).coerceAtLeast(0.dp))
                .then(dragFor("w")),
        )
        Box(
            Modifier.offset(x = leftDp + cropWidthDp - edgeThickness / 2, y = topDp + handleTouch / 2)
                .size(edgeThickness, (cropHeightDp - handleTouch).coerceAtLeast(0.dp))
                .then(dragFor("e")),
        )
        // Corner touch target (40dp) is deliberately much bigger than the visible white square
        // (20dp) — the small visual size alone was too small to reliably grab with a fingertip.
        CropHandle(Modifier.offset(x = leftDp - handleTouch / 2, y = topDp - handleTouch / 2).then(dragFor("nw")))
        CropHandle(Modifier.offset(x = leftDp + cropWidthDp - handleTouch / 2, y = topDp - handleTouch / 2).then(dragFor("ne")))
        CropHandle(Modifier.offset(x = leftDp - handleTouch / 2, y = topDp + cropHeightDp - handleTouch / 2).then(dragFor("sw")))
        CropHandle(Modifier.offset(x = leftDp + cropWidthDp - handleTouch / 2, y = topDp + cropHeightDp - handleTouch / 2).then(dragFor("se")))
    }
}

@Composable
private fun CropHandle(modifier: Modifier) {
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).background(Color.White, RoundedCornerShape(4.dp)))
    }
}

private val AccentColor = Color(0xFF4C8DFF)
private val NeutralChipColor = Color(0xFF3A3A3A)

@Composable
private fun GifChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionButton(text, onClick, modifier, if (selected) AccentColor else NeutralChipColor)
}

/** Suspends until this TextureView has a usable SurfaceTexture (no-op if it already does). */
private suspend fun TextureView.awaitSurfaceAvailable() {
    if (isAvailable) return
    suspendCancellableCoroutine<Unit> { cont ->
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }
}

/**
 * Grabs [frameCount] frames by seeking [player] (EXACT, paced one-at-a-time via [ExoPlayer.seekAndAwait])
 * and capturing each rendered frame off [textureView], crops/scales/encodes them, saves to the gallery.
 *
 * This replaced a MediaMetadataRetriever.getFrameAtTime-based version: direct measurement (logged
 * checksums of sampled pixels per extracted frame) showed that on this device, getFrameAtTime —
 * even with OPTION_CLOSEST — silently returns the SAME decoded frame for many consecutive distinct
 * timestamps within a keyframe interval (and is extremely slow at the start of each interval), so
 * most of the requested frames were exact duplicates: the GIF looked correct by file size/frame
 * count but was visibly choppy. ExoPlayer's EXACT seeks don't have this problem (same decode path
 * already proven frame-accurate by the app's scrub gesture), so reusing it for extraction sidesteps
 * the platform limitation entirely instead of working around it.
 */
private suspend fun generateGif(
    ctx: Context,
    player: ExoPlayer,
    textureView: android.view.TextureView,
    srcW: Int,
    srcH: Int,
    startMs: Long,
    speed: Float,
    fps: Int,
    crop: Rect,
    outW: Int,
    outH: Int,
    frameCount: Int,
    sampleTarget: Int,
    onProgress: (Float, String) -> Unit,
) {
    val encoder = GifEncoder(outW, outH)
    val delayMs = (1000 / fps).coerceAtLeast(10)

    withContext(Dispatchers.Main) {
        textureView.awaitSurfaceAvailable()
        player.setVideoTextureView(textureView)
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        for (i in 0 until frameCount) {
            val tMs = startMs + (i * speed * 1000.0 / fps).toLong()
            player.seekAndAwait(tMs)
            val frame = textureView.getBitmap(srcW, srcH) ?: continue
            val cropped = if (crop.left == 0 && crop.top == 0 && crop.width() == frame.width && crop.height() == frame.height) {
                frame
            } else {
                Bitmap.createBitmap(
                    frame,
                    crop.left.coerceIn(0, frame.width - 1),
                    crop.top.coerceIn(0, frame.height - 1),
                    crop.width().coerceIn(1, frame.width - crop.left.coerceAtLeast(0)),
                    crop.height().coerceIn(1, frame.height - crop.top.coerceAtLeast(0)),
                )
            }
            val scaled = if (cropped.width != outW || cropped.height != outH) {
                Bitmap.createScaledBitmap(cropped, outW, outH, true)
            } else cropped
            // The encoder keeps `scaled` by reference (read later in write()), not a copy —
            // recycling it here (as a previous version did) freed it before encoding ever
            // touched it, so every generation failed. Only recycle the OTHER intermediates.
            encoder.addFrame(scaled, delayMs)
            listOf(frame, cropped).distinct().forEach { if (it !== scaled) it.recycle() }
            onProgress((i + 1).toFloat() / frameCount * 0.5f, "フレーム取得中 ${i + 1} / $frameCount")
        }
    }

    val dir = File(ctx.cacheDir, "gif_out").apply { mkdirs() }
    val tmp = File(dir, "out_${System.currentTimeMillis()}.gif")
    withContext(Dispatchers.Default) {
        tmp.outputStream().use { out ->
            encoder.write(out, sampleTarget) { p -> onProgress(0.5f + p * 0.5f, "GIFに書き出し中...") }
        }
    }

    val name = "GIF_${System.currentTimeMillis()}.gif"
    withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DougaGIF/")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("保存先を作成できませんでした")
        ctx.contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            ?: error("書き込みに失敗しました")
    }
    tmp.delete()
}
