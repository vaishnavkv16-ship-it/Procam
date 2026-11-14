package com.procam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object Imgs {
    fun exifDegrees(jpeg: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) { 0 }

    fun decode(jpeg: ByteArray, maxArea: Long = Long.MAX_VALUE, rotate: Boolean = true): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var s = 1
        while ((bounds.outWidth.toLong() / s) * (bounds.outHeight.toLong() / s) > maxArea) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o) ?: return null
        return if (rotate) rotate(bmp, exifDegrees(jpeg)) else bmp
    }

    fun rotate(b: Bitmap, deg: Int): Bitmap {
        if (deg == 0) return b
        val m = Matrix(); m.postRotate(deg.toFloat())
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return r
    }
}

/** Basic panorama stitcher: image-based shift estimation + centre-seam strips. */
object Stitcher {
    private fun gray(b: Bitmap, gw: Int): Pair<IntArray, Int> {
        val gh = max(1, b.height * gw / b.width)
        val s = Bitmap.createScaledBitmap(b, gw, gh, true)
        val px = IntArray(gw * gh)
        s.getPixels(px, 0, gw, 0, 0, gw, gh)
        if (s !== b) s.recycle()
        for (i in px.indices) {
            val p = px[i]
            px[i] = (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10
        }
        return Pair(px, gh)
    }

    // returns d where cur(x) ~ prev(x + d); d > 0 means the camera moved right
    private fun search(a: IntArray, b: IntArray, w: Int, h: Int, center: Int, radius: Int): Int {
        var best = center
        var bestCost = Long.MAX_VALUE
        val y0 = h / 6
        val y1 = h - h / 6
        for (d in (center - radius)..(center + radius)) {
            val x0 = max(0, -d)
            val x1 = min(w, w - d)
            if (x1 - x0 < w / 3) continue
            var cost = 0L
            var cnt = 0
            var y = y0
            while (y < y1) {
                val o = y * w
                var x = x0
                while (x < x1) {
                    cost += abs(b[o + x] - a[o + x + d])
                    cnt++
                    x += 2
                }
                y += 2
            }
            val c = cost * 1000 / max(cnt, 1)
            if (c < bestCost) { bestCost = c; best = d }
        }
        return best
    }

    private fun shift(prev: Bitmap, cur: Bitmap): Int {
        val w = prev.width
        val (a1, h1) = gray(prev, 120)
        val (b1, _) = gray(cur, 120)
        val d1 = search(a1, b1, 120, h1, 0, 54)
        val (a2, h2) = gray(prev, 480)
        val (b2, _) = gray(cur, 480)
        val d2 = search(a2, b2, 480, h2, d1 * 4, 8)
        return (d2 * w.toFloat() / 480f).roundToInt()
    }

    fun stitch(frames: List<ByteArray>): Bitmap? {
        if (frames.size < 2) return null
        val maxArea = 3_200_000L
        val idx = ArrayList<Int>()
        val xs = ArrayList<Int>()
        var prev = Imgs.decode(frames[0], maxArea) ?: return null
        val w = prev.width
        val h = prev.height
        idx += 0; xs += 0
        for (i in 1 until frames.size) {
            val cur = Imgs.decode(frames[i], maxArea) ?: continue
            if (cur.width != w || cur.height != h) { cur.recycle(); continue }
            val d = shift(prev, cur)
            xs += xs.last() + d
            idx += i
            prev.recycle()
            prev = cur
        }
        prev.recycle()

        val order = xs.indices.sortedBy { xs[it] }
        val minX = xs[order.first()]
        val maxX = xs[order.last()]
        val totalW = maxX - minX + w
        val out = Bitmap.createBitmap(totalW, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (k in order.indices) {
            val fi = order[k]
            val bmp = Imgs.decode(frames[idx[fi]], maxArea) ?: continue
            val left = xs[fi] - minX
            val dstL = if (k == 0) 0 else ((xs[order[k - 1]] + xs[fi]) / 2 - minX + w / 2)
            val dstR = if (k == order.size - 1) totalW else ((xs[fi] + xs[order[k + 1]]) / 2 - minX + w / 2)
            val sl = (dstL - left).coerceIn(0, w)
            val sr = (dstR - left).coerceIn(0, w)
            if (sr > sl) c.drawBitmap(bmp, Rect(sl, 0, sr, h), Rect(sl + left, 0, sr + left, h), paint)
            bmp.recycle()
        }
        return out
    }
}

/** Averages many long exposures and stretches the levels (tripod astrophotography). */
class AstroStacker {
    private var w = 0
    private var h = 0
    private var rot = 0
    private var rs: ShortArray? = null
    private var gs: ShortArray? = null
    private var bs: ShortArray? = null
    var count = 0
        private set

    fun add(jpeg: ByteArray): Boolean {
        val bmp = Imgs.decode(jpeg, Long.MAX_VALUE, false) ?: return false
        if (rs == null) {
            w = bmp.width; h = bmp.height; rot = Imgs.exifDegrees(jpeg)
            rs = ShortArray(w * h); gs = ShortArray(w * h); bs = ShortArray(w * h)
        }
        if (bmp.width != w || bmp.height != h) { bmp.recycle(); return false }
        val r = rs!!
        val g = gs!!
        val b = bs!!
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val o = y * w
            for (x in 0 until w) {
                val p = row[x]
                r[o + x] = (r[o + x] + ((p shr 16) and 255)).toShort()
                g[o + x] = (g[o + x] + ((p shr 8) and 255)).toShort()
                b[o + x] = (b[o + x] + (p and 255)).toShort()
            }
        }
        bmp.recycle()
        count++
        return true
    }

    private fun stretch(v: Float, low: Int, high: Int): Int {
        val t = ((v - low) / (high - low)).coerceIn(0f, 1f)
        return (t.pow(0.8f) * 255f).toInt()
    }

    fun result(): Bitmap? {
        val r = rs ?: return null
        val g = gs ?: return null
        val b = bs ?: return null
        if (count == 0) return null
        val n = count
        val total = w * h
        val hist = IntArray(256)
        for (i in 0 until total) {
            val l = (r[i] * 2126 + g[i] * 7152 + b[i] * 722) / (10000 * n)
            hist[min(255, l)]++
        }
        fun pct(p: Double): Int {
            var acc = 0L
            val t = (total * p).toLong()
            for (i in 0..255) { acc += hist[i]; if (acc >= t) return i }
            return 255
        }
        val low = pct(0.01)
        val high = max(low + 20, pct(0.9995))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) {
                val i = o + x
                val rr = stretch(r[i].toFloat() / n, low, high)
                val gg = stretch(g[i].toFloat() / n, low, high)
                val bb = stretch(b[i].toFloat() / n, low, high)
                row[x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return Imgs.rotate(out, rot)
    }
}

/** Portrait mode: ML Kit person segmentation + blurred background. */
object Portrait {
    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val step = if (horizontal) 1 else w
        val win = 2 * r + 1
        for (l in 0 until lines) {
            val base = if (horizontal) l * w else l
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[base + i.coerceIn(0, len - 1) * step]
                sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (i in 0 until len) {
                dst[base + i * step] = (0xFF shl 24) or ((sr / win) shl 16) or ((sg / win) shl 8) or (sb / win)
                val add = src[base + min(len - 1, i + r + 1) * step]
                val rem = src[base + max(0, i - r) * step]
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }

    fun apply(jpeg: ByteArray, blurLevel: Int): Bitmap? {
        val full = Imgs.decode(jpeg, 12_600_000L) ?: return null
        val bigW = full.width
        val bigH = full.height
        val sc = 512f / max(bigW, bigH)
        val small = Bitmap.createScaledBitmap(full, max(1, (bigW * sc).toInt()), max(1, (bigH * sc).toInt()), true)

        var maskArr: FloatArray? = null
        var mw = 0
        var mh = 0
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val seg = Segmentation.getClient(opts)
        val latch = CountDownLatch(1)
        seg.process(InputImage.fromBitmap(small, 0))
            .addOnSuccessListener { m ->
                mw = m.width
                mh = m.height
                val buf = m.buffer
                maskArr = FloatArray(mw * mh) { i -> buf.getFloat(i * 4) }
                latch.countDown()
            }
            .addOnFailureListener { latch.countDown() }
        latch.await(25, TimeUnit.SECONDS)
        seg.close()
        small.recycle()

        val mask = maskArr
        if (mask == null || mw < 2 || mh < 2) { full.recycle(); return null }
        var fg = 0
        for (v in mask) if (v > 0.5f) fg++
        if (fg < mask.size * 0.02) { full.recycle(); return null }

        // blurred background (computed at 1/6 size, then scaled up)
        val bw = max(8, bigW / 6)
        val bh = max(8, bigH / 6)
        val bgs = Bitmap.createScaledBitmap(full, bw, bh, true)
        val a = IntArray(bw * bh)
        bgs.getPixels(a, 0, bw, 0, 0, bw, bh)
        bgs.recycle()
        val b = IntArray(a.size)
        val radius = intArrayOf(4, 8, 12, 18)[blurLevel.coerceIn(0, 3)]
        repeat(2) {
            blurPass(a, b, bw, bh, radius, true)
            blurPass(b, a, bw, bh, radius, false)
        }
        val bgSmall = Bitmap.createBitmap(a, bw, bh, Bitmap.Config.ARGB_8888)
        val bg = Bitmap.createScaledBitmap(bgSmall, bigW, bigH, true)
        bgSmall.recycle()

        val out = Bitmap.createBitmap(bigW, bigH, Bitmap.Config.ARGB_8888)
        val rowF = IntArray(bigW)
        val rowB = IntArray(bigW)
        val xa = IntArray(bigW)
        val xb = IntArray(bigW)
        val wx = FloatArray(bigW)
        for (x in 0 until bigW) {
            val fx = x * (mw - 1).toFloat() / max(1, bigW - 1)
            val i = fx.toInt().coerceIn(0, mw - 1)
            xa[x] = i; xb[x] = min(mw - 1, i + 1); wx[x] = fx - i
        }
        for (y in 0 until bigH) {
            val fy = y * (mh - 1).toFloat() / max(1, bigH - 1)
            val j = fy.toInt().coerceIn(0, mh - 1)
            val j1 = min(mh - 1, j + 1)
            val wy = fy - j
            full.getPixels(rowF, 0, bigW, 0, y, bigW, 1)
            bg.getPixels(rowB, 0, bigW, 0, y, bigW, 1)
            for (x in 0 until bigW) {
                val top = mask[j * mw + xa[x]] * (1 - wx[x]) + mask[j * mw + xb[x]] * wx[x]
                val bot = mask[j1 * mw + xa[x]] * (1 - wx[x]) + mask[j1 * mw + xb[x]] * wx[x]
                val m = top * (1 - wy) + bot * wy
                var al = ((m - 0.4f) / 0.3f).coerceIn(0f, 1f)
                al = al * al * (3 - 2 * al)
                val f = rowF[x]
                val g = rowB[x]
                val rr = (((f shr 16) and 255) * al + ((g shr 16) and 255) * (1 - al)).toInt()
                val gg = (((f shr 8) and 255) * al + ((g shr 8) and 255) * (1 - al)).toInt()
                val bb = ((f and 255) * al + (g and 255) * (1 - al)).toInt()
                rowF[x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            out.setPixels(rowF, 0, bigW, 0, y, bigW, 1)
        }
        full.recycle()
        bg.recycle()
        return out
    }
}
