package app.aemu.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

import android.view.MotionEvent
import android.view.View
import app.aemu.core.InputService
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Слой поверх экрана гостя: принимает касания и переводит их в координаты гостя.
 * В режиме GPU-моста (KK; arm64 с мостом GLES, пока кадры идут с GPU) прозрачен — кадр рисует мост в SurfaceView под ним.
 * В программном режиме (GB или без GPU) сам показывает fb0: отображает файл в память
 * и копирует в RGB565-битмап только когда кадр изменился.
 */
class GuestScreen(ctx: Context, val w: Int, val h: Int) : View(ctx) {
    var input: InputService? = null
    var fb: File? = null
    @Volatile var passthrough = false
        set(v) { field = v; postInvalidate() }
    @Volatile var fps = 0f
        private set
    /** frames this screen has shown since the view started, by either path (the testers compare it with the guest's count) */
    @Volatile var shown = 0L
        private set
    @Volatile var rings = 0L
    /** the count of frames the GLES bridge put on the screen itself (GlHost.presented); while it moves, this view is clear */
    var direct: (() -> Long)? = null
    private var directSeen = 0L
    private var directAt = 0L
    private var directFrames = 0L
    /** 2 = the guest flips between two pages by panning (recovery's minui); show the one it wrote last */
    var pages = 1
    /** Broadcom V3D firmware: SurfaceFlinger pans between several pages of fb0 (the file holds 4 here); count them from the file size and follow the page written last */
    var autoPages = false
    /** pixel format of fb0: 0 = RGB565, 1 = RGBA/RGBX_8888, 2 = BGRA_8888 (TWRP); a change remaps the file */
    @Volatile var format = 0
        set(v) {
            if (field == v) return
            field = v
            mappedIno = -1
            poke()
        }
    private val pageHash = LongArray(8)
    private var panMode = false
    private var lastChanged = 0
    private var postSeen = 0
    /** the menu entry "Screen mode" picks how a V3D firmware's frames are shown: 0 = the page that changed last, at once; 1 = the page the
     *  last job drew in, once the job count has been still for a moment; 2 = like 1 and never while a job runs. Default 1. */
    @Volatile var fbMode = 1
    private var traceBusy = 0
    private var traceSeq = 0
    private var traceLines = 0
    private var flashes = 0
    private var prevAvg = -1
    private val traceBuf = StringBuilder()
    private var postOff = 0
    private var lastMove = 0L
    private var unsettledSince = 0L
    private val postBuf = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    private var argb: IntArray? = null
    private var page = 0
    /** the page of a two-page fb0 that was written last (0 for single-page buffers) */
    val shownPage: Int get() = page

    private var bmp: Bitmap? = null
    private var buf: MappedByteBuffer? = null
    private var raf: RandomAccessFile? = null
    private var mappedIno = 0L
    private var lastHash = 0L
    private var ringSeen = 0L
    private var countSeen = 0
    private var changed = 0L
    private var changedAt = 0L
    private var quiet = 0
    @Volatile private var live = false
    private val gate = Object()
    private companion object { const val POST_MAGIC = 0x31544f50; const val SETTLE_MS = 10L; const val SETTLE_MAX_MS = 50L; const val BUSY_MAX_MS = 400L; const val BLACK_HOLD_MS = 150L }
    private val paint = Paint().apply { isFilterBitmap = true }
    private val dst = Rect()

    fun start() {
        if (live) return
        live = true
        Thread({
            while (live) {
                runCatching { tick() }
                synchronized(gate) { runCatching { gate.wait(if (quiet > 30 || (passthrough && direct != null)) 16L else 5L) } }
            }
        }, "guest-fb").apply { isDaemon = true; start() }
    }

    fun stop() { live = false }

    /** Copy of the last frame shown from fb0 (software path); null in GPU passthrough or before the first frame. */
    fun snapshot(): Bitmap? = if (passthrough) null else bmp?.copy(Bitmap.Config.ARGB_8888, false)

    fun poke() { quiet = 0; synchronized(gate) { gate.notifyAll() } }

    private fun tick() {
        val d = direct
        if (d != null) {
            val n = d()
            val now = System.currentTimeMillis()
            if (n < directSeen) { directSeen = n; passthrough = false; lastHash = 0 }      // (the guest started again)
            else if (n != directSeen) {
                directFrames += n - directSeen
                shown += n - directSeen
                directSeen = n
                if (!passthrough) { passthrough = true; countSeen = fbCount(); directFrames = 0; directAt = now }
                if (now - directAt > 1000) { fps = directFrames * 1000f / (now - directAt); directFrames = 0; directAt = now; android.util.Log.d("aemu-screen", "gpu %.1f fps".format(fps)) }
            } else if (passthrough) {
                if (now - directAt > 1000) { fps = 0f; directAt = now }
                // the composer went back to fb0 (a frame the GPU does not have): this view shows it again
                if (fbCount() != countSeen) { passthrough = false; lastHash = 0 }
            }
        }
        if (passthrough) return
        val f = fb ?: return
        val pixels = w.toLong() * h * (if (format == 0) 2 else 4)
        if (buf != null) {
            val ino = runCatching { android.system.Os.stat(f.absolutePath).st_ino }.getOrDefault(0L)
            if (ino != mappedIno || mappedIno == -1L) { runCatching { raf?.close() }; raf = null; buf = null; bmp = null; lastHash = 0 }
        }
        if (buf == null) {
            if (!f.isFile || f.length() < pixels) return
            raf = RandomAccessFile(f, "r")
            if (autoPages && pages == 1 && f.length() >= pixels * 2) pages = (f.length() / pixels).toInt().coerceIn(2, 8)
            // (the engine's hwcomposer counts its frames in the first word after the page)
            val span = if (pages >= 2 && f.length() >= pixels * pages) pixels * pages else if (pages >= 2 && f.length() >= pixels * 2) pixels * 2 else if (f.length() >= pixels + 8) pixels + 8 else pixels
            buf = raf!!.channel.map(FileChannel.MapMode.READ_ONLY, 0, span)
            mappedIno = runCatching { android.system.Os.stat(f.absolutePath).st_ino }.getOrDefault(0L)
            bmp = Bitmap.createBitmap(w, h, if (format == 0) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
        }
        val b = buf ?: return
        val bitmap = bmp ?: return
        val two = pages >= 2 && b.capacity() >= pixels * 2
        if (two) {
            // several pages: follow the one whose content changed most recently
            val n = (b.capacity() / pixels).toInt().coerceIn(2, 8)
            for (pg in 0 until n) {
                var hh = 0L
                var j = (pg * pixels).toInt()
                val end = j + pixels.toInt() - 4 - (if (pg == n - 1) 16 else 0)
                while (j <= end) { hh = 31 * hh + b.getInt(j); j += 2048 }
                if (hh != pageHash[pg]) { pageHash[pg] = hh; lastChanged = pg; if (!panMode || fbMode == 0) page = pg }
            }
            if (autoPages) {
                // V3D firmware: the shim leaves a record at the end of fb0 after every job {render target offset, job count}. A frame is
                // several jobs, so the page is shown once the count has stopped rising (SETTLE_MS) or, for a stream that never stops,
                // after SETTLE_MAX_MS. Without a record (older shim) the page that changed last is shown at once, as before.
                val r = if (fbMode == 0) null else postRecord(f)
                if (r != null && fbMode == 3) {
                    // The first job of a frame clears the whole page to black and the compose job fills it in tens of milliseconds later
                    // (translation is slow): those black frames are what flashed. Show the page the last job drew in as soon as it has
                    // anything in it; an all-black page is kept off the screen unless no job has run for BLACK_HOLD_MS (a really black screen).
                    panMode = true
                    val t = System.currentTimeMillis()
                    traceBusy = r.third; traceSeq = r.second
                    if (r.second != postSeen || r.third != 0) { postSeen = r.second; lastMove = t }
                    page = (r.first.toLong() / pixels).toInt().coerceIn(0, n - 1)
                    if (pageHash[page] == 0L && (t - lastMove < BLACK_HOLD_MS || r.third != 0)) { quiet++; return }
                } else if (r != null) {
                    panMode = true
                    val t = System.currentTimeMillis()
                    val busy = r.third != 0 && fbMode == 2
                    traceBusy = r.third; traceSeq = r.second
                    if (r.second != postSeen || busy) { postSeen = r.second; postOff = r.first; lastMove = t; if (unsettledSince == 0L) unsettledSince = t }
                    if (unsettledSince == 0L) { quiet++; return }
                    // while a job runs the page may hold the cleared or half composed frame: wait for it (long jobs are normal under
                    // translation); the longer cap only guards against a record left busy by a process that died mid-job
                    if (t - lastMove < SETTLE_MS && t - unsettledSince < (if (busy) BUSY_MAX_MS else SETTLE_MAX_MS)) return
                    if (busy && t - unsettledSince < BUSY_MAX_MS) return
                    unsettledSince = 0L
                    page = (postOff.toLong() / pixels).toInt().coerceIn(0, n - 1)
                    // a record that names a page with nothing in it: show the page that changed last
                    if (pageHash[page] == 0L && pageHash[lastChanged] != 0L) page = lastChanged
                }
            }
        }
        val base = if (two) (page * pixels).toInt() else 0
        var hash = page.toLong()
        val last = base + pixels.toInt() - 4
        var i = base
        while (i <= last) { hash = 31 * hash + b.getInt(i); i += 2048 }
        val count = if (!two && b.capacity() >= pixels + 8) b.getInt(pixels.toInt()) else 0
        val rung = rings != ringSeen || count != countSeen
        countSeen = count
        ringSeen = rings
        quiet++
        val force = quiet > 30 && quiet % 64 == 0      // idle: a full look about once a second, whatever the checks say
        if (rung || hash != lastHash || force) {
            if (format != 2) {
                // RGB565 and RGBA/RGBX: the bitmap's own memory layout, one copy. RGBX's alpha byte is padding, so the
                // bitmap is marked opaque instead of rewriting every pixel (a 1080×1920 frame cost the loop most of a core)
                b.limit(base + pixels.toInt()); b.position(base)
                bitmap.copyPixelsFromBuffer(b)
                b.clear()
                if (format == 1 && bitmap.hasAlpha()) bitmap.setHasAlpha(false)
            } else {
                // BGRA (TWRP): read as ARGB ints; the alpha byte is padding (TWRP leaves 0), so force it opaque
                val n = w * h
                val px = argb?.takeIf { it.size == n } ?: IntArray(n).also { argb = it }
                val ib = b.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).position(base).let { (it as java.nio.ByteBuffer).asIntBuffer() }
                ib.get(px, 0, n)
                for (k in 0 until n) px[k] = px[k] or -0x1000000
                bitmap.setPixels(px, 0, w, 0, 0, w, h)
            }
            if (hash != lastHash || rung) {
                quiet = 0
                lastHash = hash
                changed++
                trace(bitmap)
                shown++
                val now = System.currentTimeMillis()
                if (changedAt == 0L) changedAt = now
                if (now - changedAt > 1000) { fps = changed * 1000f / (now - changedAt); changed = 0; changedAt = now; android.util.Log.d("aemu-screen", "fb0 %.1f fps".format(fps)) }
            }
            postInvalidate()
        }
    }

    private fun runDir(): File? = fb?.parentFile?.parentFile?.parentFile?.parentFile?.let { File(it, "run") }

    /** What was shown, for the diag: a line per shown frame (the first 600) and a PNG of every frame whose brightness jumps (the first 8). */
    private fun trace(bm: Bitmap) {
        if (!autoPages || traceLines >= 600 && flashes >= 8) return
        var sum = 0L; var n = 0
        val stepX = maxOf(1, bm.width / 16); val stepY = maxOf(1, bm.height / 24)
        var y = stepY / 2
        while (y < bm.height) { var x = stepX / 2; while (x < bm.width) { val c = bm.getPixel(x, y); sum += ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3; n++; x += stepX }; y += stepY }
        val avg = if (n > 0) (sum / n).toInt() else 0
        val jump = prevAvg >= 0 && Math.abs(avg - prevAvg) > 40
        if (traceLines < 600) {
            traceBuf.append(System.currentTimeMillis() % 100000).append(" mode=").append(fbMode).append(" page=").append(page)
                .append(" seq=").append(traceSeq).append(" busy=").append(traceBusy).append(" avg=").append(avg).append(if (jump) " JUMP" else "").append('\n')
            traceLines++
            if (traceLines % 25 == 0 || traceLines == 600) runCatching { File(runDir(), "screen-trace.log").appendText(traceBuf.toString()); traceBuf.setLength(0) }
        }
        if (jump && flashes < 8) {
            flashes++
            runCatching { java.io.FileOutputStream(File(runDir(), "flash-$flashes-page$page-avg$avg.png")).use { bm.compress(Bitmap.CompressFormat.PNG, 90, it) } }
        }
        prevAvg = avg
    }

    /** the shim's record of the last V3D job (see bcmgpu.h): {render target offset, job count}, or null when the shim leaves none */
    private fun postRecord(f: File): Triple<Int, Int, Int>? {
        val ch = raf?.channel ?: return null
        val len = f.length()
        if (len < 16) return null
        postBuf.clear()
        if (ch.read(postBuf, len - 16) != 16) return null
        postBuf.flip()
        if (postBuf.getInt(0) != POST_MAGIC) return null
        return Triple(postBuf.getInt(4), postBuf.getInt(8), postBuf.getInt(12))
    }

    /** the hwcomposer's count of frames written to fb0 (0 when the file has none) */
    private fun fbCount(): Int {
        val b = buf ?: return 0
        val pixels = w * h * (if (format == 0) 2 else 4)
        return if (pages < 2 && b.capacity() >= pixels + 8) b.getInt(pixels) else 0
    }

    override fun onDraw(c: Canvas) {
        dst.set(0, 0, width, height)
        if (passthrough) return
        val b = bmp
        if (b != null) c.drawBitmap(b, null, dst, paint) else c.drawColor(0xff000000.toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val inp = input ?: return false
        poke()
        if (width <= 0 || height <= 0) return false
        val act = e.actionMasked
        if (inp.mouseKeyboardEnabled) {
            // mouse and keyboard: the guest has no touch screen, this view is its touchpad
            val liftedIndex = if (act == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
            val over = act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL
            val contacts = ArrayList<app.aemu.core.TouchpadProtocol.Contact>(e.pointerCount)
            for (i in 0 until e.pointerCount) {
                if (over || i == liftedIndex) continue
                contacts.add(app.aemu.core.TouchpadProtocol.Contact(e.getPointerId(i),
                    app.aemu.core.TouchpadProtocol.pad(gx(e.getX(i)), w), app.aemu.core.TouchpadProtocol.pad(gy(e.getY(i)), h)))
            }
            inp.padTouch(contacts)
            return true
        }
        val up = act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL
        val gone = if (act == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        // промежуточные точки движения — чтобы жесты и прокрутка были плавными
        val gap = if (inp.rateHz > 0) 1000L / inp.rateHz else 0L
        var prevAt = 0L
        for (hi in 0 until e.historySize) {
            val ht = e.getHistoricalEventTime(hi)
            if (gap > 0 && prevAt != 0L && ht - prevAt < gap) continue
            prevAt = ht
            val pts = ArrayList<InputService.P>(e.pointerCount)
            for (i in 0 until e.pointerCount) {
                if (up || i == gone) continue
                pts.add(InputService.P(e.getPointerId(i), gx(e.getHistoricalX(i, hi)), gy(e.getHistoricalY(i, hi))))
            }
            if (pts.isNotEmpty()) inp.touch(pts, true)
        }
        val pts = ArrayList<InputService.P>(e.pointerCount)
        for (i in 0 until e.pointerCount) {
            if (up || i == gone) continue
            pts.add(InputService.P(e.getPointerId(i), gx(e.getX(i)), gy(e.getY(i))))
        }
        inp.touch(pts, act != MotionEvent.ACTION_MOVE)
        return true
    }

    private fun gx(x: Float) = (x * w / width).toInt().coerceIn(0, w - 1)
    private fun gy(y: Float) = (y * h / height).toInt().coerceIn(0, h - 1)
}
