package com.example.dougasort

import android.graphics.Bitmap
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Minimal, dependency-free animated GIF89a writer: one shared global palette (built by [Quantizer]
 * from all frames) plus a standard GIF LZW compressor per frame. No external library needed.
 */
class GifEncoder(private val width: Int, private val height: Int) {
    private val frames = mutableListOf<Bitmap>()
    private val delaysCs = mutableListOf<Int>()

    /** [delayMs] is this frame's on-screen time; GIF delays are in 1/100s, minimum 1. */
    fun addFrame(bitmap: Bitmap, delayMs: Int) {
        frames += bitmap
        delaysCs += (delayMs / 10).coerceAtLeast(1)
    }

    /**
     * Builds the shared palette, then writes the whole file. [onProgress] reports 0f..1f.
     * [sampleTarget] is how many pixels per frame are sampled when building the palette — more
     * samples means colors are chosen more faithfully (fewer "wrong" matches), at the cost of time.
     */
    fun write(out: OutputStream, sampleTarget: Int = 20_000, onProgress: (Float) -> Unit = {}) {
        check(frames.isNotEmpty()) { "no frames added" }
        val palette = Quantizer.build(frames, sampleTarget)
        val bits = palette.bitsPerPixel()
        val tableSize = 1 shl bits

        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShortLE(out, width)
        writeShortLE(out, height)
        out.write(0x80 or (0x70) or (bits - 1)) // global color table, color res=8bit, table size
        out.write(0) // background color index
        out.write(0) // pixel aspect ratio
        for (i in 0 until tableSize) {
            val rgb = if (i < palette.colors.size) palette.colors[i] else 0
            out.write((rgb shr 16) and 0xFF)
            out.write((rgb shr 8) and 0xFF)
            out.write(rgb and 0xFF)
        }
        writeNetscapeLoop(out)

        val lzw = GifLzwWriter(bits.coerceAtLeast(2), out)
        for (i in frames.indices) {
            writeGraphicControlExtension(out, delaysCs[i])
            writeImageDescriptor(out)
            out.write(bits.coerceAtLeast(2))
            lzw.encode(palette.quantize(frames[i], width, height))
            onProgress((i + 1).toFloat() / frames.size)
        }
        out.write(0x3B) // trailer
    }

    private fun writeShortLE(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeNetscapeLoop(out: OutputStream) {
        out.write(0x21); out.write(0xFF); out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(0x03); out.write(0x01)
        writeShortLE(out, 0) // 0 = loop forever
        out.write(0x00)
    }

    private fun writeGraphicControlExtension(out: OutputStream, delayCs: Int) {
        out.write(0x21); out.write(0xF9); out.write(0x04)
        out.write(0x00) // disposal unspecified, no transparency
        writeShortLE(out, delayCs)
        out.write(0x00) // transparent color index (unused)
        out.write(0x00)
    }

    private fun writeImageDescriptor(out: OutputStream) {
        out.write(0x2C)
        writeShortLE(out, 0); writeShortLE(out, 0)
        writeShortLE(out, width); writeShortLE(out, height)
        out.write(0x00) // no local color table
    }
}

/**
 * Builds one global 256-color palette via [NeuQuant] (the same algorithm the earlier WebView-based
 * build of this tool used, through gif.js) and maps each pixel to it with Floyd-Steinberg dithering.
 */
private object Quantizer {
    class Palette(private val neu: NeuQuant, val colors: IntArray) {
        fun bitsPerPixel(): Int {
            var n = 2
            while ((1 shl n) < colors.size) n++
            return n
        }

        fun quantize(bitmap: Bitmap, outW: Int, outH: Int): ByteArray {
            val scaled = if (bitmap.width != outW || bitmap.height != outH) {
                Bitmap.createScaledBitmap(bitmap, outW, outH, true)
            } else bitmap
            val px = IntArray(outW * outH)
            scaled.getPixels(px, 0, outW, 0, 0, outW, outH)
            val out = ByteArray(px.size)
            // Floyd-Steinberg error diffusion. A flat nearest-palette-color mapping (no dithering)
            // shows hard color banding on gradients and antialiased UI text at only 256 colors —
            // that banding is what reads as "rough/low quality" even with a well-chosen palette.
            val errR = FloatArray(px.size)
            val errG = FloatArray(px.size)
            val errB = FloatArray(px.size)
            for (y in 0 until outH) {
                // Serpentine (boustrophedon) scan: alternate direction every row. Scanning the same
                // direction every row biases error diffusion into a visible directional "worm grain"
                // texture; alternating cancels that bias out and generally reads as cleaner/less noisy
                // for the same palette.
                val leftToRight = y % 2 == 0
                val xs = if (leftToRight) 0 until outW else (outW - 1) downTo 0
                val fwd = if (leftToRight) 1 else -1
                for (x in xs) {
                    val i = y * outW + x
                    val p = px[i]
                    val r = ((p shr 16) and 0xFF) + errR[i]
                    val g = ((p shr 8) and 0xFF) + errG[i]
                    val b = (p and 0xFF) + errB[i]
                    val rc = r.toInt().coerceIn(0, 255)
                    val gc = g.toInt().coerceIn(0, 255)
                    val bc = b.toInt().coerceIn(0, 255)
                    val idx = neu.map(rc, gc, bc)
                    out[i] = idx.toByte()
                    val chosen = colors[idx]
                    val dr = r - ((chosen shr 16) and 0xFF)
                    val dg = g - ((chosen shr 8) and 0xFF)
                    val db = b - (chosen and 0xFF)
                    val xAhead = x + fwd
                    val xBehind = x - fwd
                    if (xAhead in 0 until outW) {
                        val j = i + fwd
                        errR[j] += dr * 7f / 16f; errG[j] += dg * 7f / 16f; errB[j] += db * 7f / 16f
                    }
                    if (y + 1 < outH) {
                        if (xBehind in 0 until outW) {
                            val j = i + outW - fwd
                            errR[j] += dr * 3f / 16f; errG[j] += dg * 3f / 16f; errB[j] += db * 3f / 16f
                        }
                        val j = i + outW
                        errR[j] += dr * 5f / 16f; errG[j] += dg * 5f / 16f; errB[j] += db * 5f / 16f
                        if (xAhead in 0 until outW) {
                            val k = i + outW + fwd
                            errR[k] += dr * 1f / 16f; errG[k] += dg * 1f / 16f; errB[k] += db * 1f / 16f
                        }
                    }
                }
            }
            if (scaled !== bitmap) scaled.recycle()
            return out
        }
    }

    fun build(frames: List<Bitmap>, sampleTarget: Int): Palette {
        // Gather a bounded sample of pixels (R,G,B bytes) across all frames — sampleTarget pixels
        // per frame, same budget the old histogram-based quantizer used — and hand them to NeuQuant
        // for training (samplefac=1: we've already done the downsampling ourselves).
        var total = 0
        for (bmp in frames) total += minOf(bmp.width * bmp.height, sampleTarget)
        val samples = ByteArray(total * 3)
        var w = 0
        for (bmp in frames) {
            val bw = bmp.width
            val bh = bmp.height
            val totalPx = bw * bh
            val step = (totalPx / sampleTarget).coerceAtLeast(1)
            val px = IntArray(totalPx)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
            var i = 0
            while (i < totalPx && w + 3 <= samples.size) {
                val p = px[i]
                samples[w] = ((p shr 16) and 0xFF).toByte()
                samples[w + 1] = ((p shr 8) and 0xFF).toByte()
                samples[w + 2] = (p and 0xFF).toByte()
                w += 3
                i += step
            }
        }
        val neu = NeuQuant(samples, w, 1)
        neu.process()
        val map = neu.colorMap()
        val colors = IntArray(256) { i ->
            val r = map[i * 3].toInt() and 0xFF
            val g = map[i * 3 + 1].toInt() and 0xFF
            val b = map[i * 3 + 2].toInt() and 0xFF
            (r shl 16) or (g shl 8) or b
        }
        return Palette(neu, colors)
    }
}

/**
 * NeuQuant (Anthony Dekker's neural-net color quantizer), ported from the classic public-domain
 * reference implementation — this is the same algorithm the earlier WebView-based build of this
 * tool used via gif.js's bundled quantizer. It self-organizes 256 "neurons" (colors) toward the
 * actual pixel distribution during training, which represents minority-but-visually-important
 * colors (fine gradients, accents) much better than a frequency- or box-split-based quantizer:
 * those approaches compete purely on pixel count or range and can starve smooth gradients of
 * palette slots in favor of large flat regions.
 *
 * [pixels] is sample pixels as consecutive R,G,B bytes; [sampleFac] >1 further subsamples them
 * during training (1 = use every byte given). After [process], [colorMap] returns the 256-entry
 * palette (R,G,B per entry) and [map] finds the nearest palette index for an arbitrary color.
 */
private class NeuQuant(private val pixels: ByteArray, private val lengthCount: Int, sampleFac: Int) {
    companion object {
        private const val NETSIZE = 256
        private const val PRIME1 = 499
        private const val PRIME2 = 491
        private const val PRIME3 = 487
        private const val PRIME4 = 503
        private const val MINPICTUREBYTES = 3 * PRIME4
        private const val MAXNETPOS = NETSIZE - 1
        private const val NETBIASSHIFT = 4
        private const val NCYCLES = 100
        private const val INTBIASSHIFT = 16
        private const val INTBIAS = 1 shl INTBIASSHIFT
        private const val GAMMASHIFT = 10
        private const val BETASHIFT = 10
        private const val BETA = INTBIAS shr BETASHIFT
        private const val BETAGAMMA = INTBIAS shl (GAMMASHIFT - BETASHIFT)
        private const val INITRAD = NETSIZE shr 3
        private const val RADIUSBIASSHIFT = 6
        private const val RADIUSBIAS = 1 shl RADIUSBIASSHIFT
        private const val INITRADIUS = INITRAD * RADIUSBIAS
        private const val RADIUSDEC = 30
        private const val ALPHABIASSHIFT = 10
        private const val INITALPHA = 1 shl ALPHABIASSHIFT
        private const val RADBIASSHIFT = 8
        private const val RADBIAS = 1 shl RADBIASSHIFT
        private const val ALPHARADBSHIFT = ALPHABIASSHIFT + RADBIASSHIFT
        private const val ALPHARADBIAS = 1 shl ALPHARADBSHIFT
    }

    private var sampleFac = sampleFac
    // Each neuron is (channel0, channel1, channel2, originalIndex). Channel order matches [pixels]
    // (we feed R,G,B) and is otherwise just slot 0/1/2 — the algorithm itself is channel-symmetric.
    private val network = Array(NETSIZE) { IntArray(4) }
    private val netindex = IntArray(256)
    private val bias = IntArray(NETSIZE)
    private val freq = IntArray(NETSIZE)
    private var radpower = IntArray(INITRAD)
    private var alphadec = 0

    init {
        for (i in 0 until NETSIZE) {
            val v = (i shl (NETBIASSHIFT + 8)) / NETSIZE
            network[i][0] = v; network[i][1] = v; network[i][2] = v
            freq[i] = INTBIAS / NETSIZE
            bias[i] = 0
        }
    }

    fun colorMap(): ByteArray {
        val map = ByteArray(3 * NETSIZE)
        val index = IntArray(NETSIZE)
        for (i in 0 until NETSIZE) index[network[i][3]] = i
        var k = 0
        for (i in 0 until NETSIZE) {
            val j = index[i]
            map[k++] = network[j][0].toByte()
            map[k++] = network[j][1].toByte()
            map[k++] = network[j][2].toByte()
        }
        return map
    }

    fun process() {
        learn()
        unbiasnet()
        inxbuild()
    }

    private fun unbiasnet() {
        for (i in 0 until NETSIZE) {
            network[i][0] = network[i][0] shr NETBIASSHIFT
            network[i][1] = network[i][1] shr NETBIASSHIFT
            network[i][2] = network[i][2] shr NETBIASSHIFT
            network[i][3] = i
        }
    }

    private fun inxbuild() {
        var previouscol = 0
        var startpos = 0
        for (i in 0 until NETSIZE) {
            val p = network[i]
            var smallpos = i
            var smallval = p[1]
            for (j in i + 1 until NETSIZE) {
                val q = network[j]
                if (q[1] < smallval) { smallpos = j; smallval = q[1] }
            }
            val q = network[smallpos]
            if (i != smallpos) {
                for (c in 0..3) { val t = q[c]; q[c] = p[c]; p[c] = t }
            }
            if (smallval != previouscol) {
                netindex[previouscol] = (startpos + i) shr 1
                for (j in previouscol + 1 until smallval) netindex[j] = i
                previouscol = smallval
                startpos = i
            }
        }
        netindex[previouscol] = (startpos + MAXNETPOS) shr 1
        for (j in previouscol + 1 until 256) netindex[j] = MAXNETPOS
    }

    private fun learn() {
        if (lengthCount < MINPICTUREBYTES) sampleFac = 1
        alphadec = 30 + (sampleFac - 1) / 3
        var pix = 0
        val lim = lengthCount
        val samplepixels = lengthCount / (3 * sampleFac)
        var delta = samplepixels / NCYCLES
        var alpha = INITALPHA
        var radius = INITRADIUS

        var rad = radius shr RADIUSBIASSHIFT
        if (rad <= 1) rad = 0
        for (i in 0 until rad) radpower[i] = alpha * (((rad * rad - i * i) * RADBIAS) / (rad * rad))

        val step = when {
            lengthCount < MINPICTUREBYTES -> 3
            lengthCount % PRIME1 != 0 -> 3 * PRIME1
            lengthCount % PRIME2 != 0 -> 3 * PRIME2
            lengthCount % PRIME3 != 0 -> 3 * PRIME3
            else -> 3 * PRIME4
        }

        var i = 0
        if (delta == 0) delta = 1
        while (i < samplepixels) {
            val ch0 = (pixels[pix].toInt() and 0xFF) shl NETBIASSHIFT
            val ch1 = (pixels[pix + 1].toInt() and 0xFF) shl NETBIASSHIFT
            val ch2 = (pixels[pix + 2].toInt() and 0xFF) shl NETBIASSHIFT
            val j = contest(ch0, ch1, ch2)

            altersingle(alpha, j, ch0, ch1, ch2)
            if (rad != 0) alterneigh(rad, j, ch0, ch1, ch2)

            pix += step
            if (pix >= lim) pix -= lengthCount

            i++
            if (i % delta == 0) {
                alpha -= alpha / alphadec
                radius -= radius / RADIUSDEC
                rad = radius shr RADIUSBIASSHIFT
                if (rad <= 1) rad = 0
                for (j2 in 0 until rad) radpower[j2] = alpha * (((rad * rad - j2 * j2) * RADBIAS) / (rad * rad))
            }
        }
    }

    private fun contest(ch0: Int, ch1: Int, ch2: Int): Int {
        var bestd = Int.MAX_VALUE
        var bestbiasd = bestd
        var bestpos = -1
        var bestbiaspos = bestpos
        for (i in 0 until NETSIZE) {
            val n = network[i]
            var dist = kotlin.math.abs(n[0] - ch0)
            dist += kotlin.math.abs(n[1] - ch1)
            dist += kotlin.math.abs(n[2] - ch2)
            if (dist < bestd) { bestd = dist; bestpos = i }
            val biasdist = dist - (bias[i] shr (INTBIASSHIFT - NETBIASSHIFT))
            if (biasdist < bestbiasd) { bestbiasd = biasdist; bestbiaspos = i }
            val betafreq = freq[i] shr BETASHIFT
            freq[i] -= betafreq
            bias[i] += betafreq shl GAMMASHIFT
        }
        freq[bestpos] += BETA
        bias[bestpos] -= BETAGAMMA
        return bestbiaspos
    }

    private fun altersingle(alpha: Int, i: Int, ch0: Int, ch1: Int, ch2: Int) {
        val n = network[i]
        n[0] -= (alpha * (n[0] - ch0)) / INITALPHA
        n[1] -= (alpha * (n[1] - ch1)) / INITALPHA
        n[2] -= (alpha * (n[2] - ch2)) / INITALPHA
    }

    private fun alterneigh(rad: Int, i: Int, ch0: Int, ch1: Int, ch2: Int) {
        var lo = i - rad; if (lo < -1) lo = -1
        var hi = i + rad; if (hi > NETSIZE) hi = NETSIZE
        var j = i + 1
        var k = i - 1
        var m = 1
        while (j < hi || k > lo) {
            val a = radpower[m++]
            if (j < hi) {
                val p = network[j++]
                p[0] -= (a * (p[0] - ch0)) / ALPHARADBIAS
                p[1] -= (a * (p[1] - ch1)) / ALPHARADBIAS
                p[2] -= (a * (p[2] - ch2)) / ALPHARADBIAS
            }
            if (k > lo) {
                val p = network[k--]
                p[0] -= (a * (p[0] - ch0)) / ALPHARADBIAS
                p[1] -= (a * (p[1] - ch1)) / ALPHARADBIAS
                p[2] -= (a * (p[2] - ch2)) / ALPHARADBIAS
            }
        }
    }

    /** Nearest palette index for an arbitrary (ch0, ch1, ch2) color, post-[process]. */
    fun map(ch0: Int, ch1: Int, ch2: Int): Int {
        var bestd = 1000
        var best = -1
        var i = netindex[ch1]
        var j = i - 1
        while (i < NETSIZE || j >= 0) {
            if (i < NETSIZE) {
                val p = network[i]
                var dist = p[1] - ch1
                if (dist >= bestd) {
                    i = NETSIZE
                } else {
                    i++
                    if (dist < 0) dist = -dist
                    var a = p[0] - ch0
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - ch2
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) { bestd = dist; best = p[3] }
                    }
                }
            }
            if (j >= 0) {
                val p = network[j]
                var dist = ch1 - p[1]
                if (dist >= bestd) {
                    j = -1
                } else {
                    j--
                    if (dist < 0) dist = -dist
                    var a = p[0] - ch0
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - ch2
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) { bestd = dist; best = p[3] }
                    }
                }
            }
        }
        return best
    }
}

/** Standard GIF LZW compressor: LSB-first bit packing into 255-byte sub-blocks, codes up to 12 bits. */
private class GifLzwWriter(private val minCodeSize: Int, private val out: OutputStream) {
    private val clearCode = 1 shl minCodeSize
    private val endCode = clearCode + 1
    private var nextCode = 0
    private var codeSize = 0
    private val table = HashMap<Long, Int>()

    private var bitBuf = 0
    private var bitCount = 0
    private val block = ByteArray(255)
    private var blockLen = 0

    fun encode(indices: ByteArray) {
        table.clear()
        nextCode = endCode + 1
        codeSize = minCodeSize + 1
        emitCode(clearCode)
        if (indices.isEmpty()) {
            emitCode(endCode)
        } else {
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val key = (prefix.toLong() shl 16) or k.toLong()
                val existing = table[key]
                if (existing != null) {
                    prefix = existing
                } else {
                    emitCode(prefix)
                    if (nextCode < 4096) {
                        table[key] = nextCode
                        nextCode++
                        if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                    } else {
                        emitCode(clearCode)
                        table.clear()
                        nextCode = endCode + 1
                        codeSize = minCodeSize + 1
                    }
                    prefix = k
                }
            }
            emitCode(prefix)
            emitCode(endCode)
        }
        if (bitCount > 0) {
            emitByteToBlock(bitBuf and 0xFF)
            bitBuf = 0
            bitCount = 0
        }
        flushBlock()
        out.write(0x00) // block terminator
    }

    private fun emitCode(code: Int) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += codeSize
        while (bitCount >= 8) {
            emitByteToBlock(bitBuf and 0xFF)
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    private fun emitByteToBlock(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen == 255) flushBlock()
    }

    private fun flushBlock() {
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }
    }
}

/** Smallest output long-edge, scaled to fit [maxLong], keeping the source aspect ratio, even dims. */
fun gifOutputSize(srcW: Int, srcH: Int, maxLong: Int): Pair<Int, Int> {
    val long = maxOf(srcW, srcH)
    if (long <= maxLong) return (srcW / 2 * 2).coerceAtLeast(2) to (srcH / 2 * 2).coerceAtLeast(2)
    val scale = maxLong.toDouble() / long
    val w = (srcW * scale).toInt().coerceAtLeast(2) / 2 * 2
    val h = (srcH * scale).toInt().coerceAtLeast(2) / 2 * 2
    return w to h
}

/** log2, rounded up, for display purposes (not used by the encoder itself). */
internal fun bitsFor(n: Int): Int = ceil(ln(n.coerceAtLeast(1).toDouble()) / ln(2.0)).toInt().coerceAtLeast(1)
