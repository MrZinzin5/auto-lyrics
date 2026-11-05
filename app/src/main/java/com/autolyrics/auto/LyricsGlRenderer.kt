package com.autolyrics.auto

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class LyricsGlRenderer(
    private val carContext: CarContext,
    private val lifecycleOwner: LifecycleOwner
) : SurfaceCallback, DefaultLifecycleObserver {

    private val mediaTracker = MediaTracker.getInstance(carContext)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var textureId = 0
    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null

    private var width = 0
    private var height = 0
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var visibleArea: Rect? = null
    private var ready = false

    private val gradientPaint = Paint()
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val coverShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 17f
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = 14f
    }
    private val waveFgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val waveBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
    }
    private val waveBoundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private var waveTrackKey: String? = null
    private var waveHeights: FloatArray = FloatArray(0)

    private val prevPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 26f
    }
    private val curPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(8f, 0f, 2f, Color.argb(200, 0, 0, 0))
    }
    private val nextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 26f
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }

    private var lastBgDominant = 0
    private var lastGradient: LinearGradient? = null
    private var cachedBlurredArt: Bitmap? = null
    private var cachedBlurredArtKey: Int = 0
    private var cachedCoverShader: BitmapShader? = null
    private var cachedCoverShaderKey: Int = 0
    private var cachedVignette: LinearGradient? = null
    private var cachedVignetteKey: Long = 0L
    private val overlayPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    private val bitmapBgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val reusableMatrix = Matrix()
    private val reusableMatrix2 = Matrix()
    private val reusableRect = RectF()
    private val reusableShadowRect = RectF()

    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
            Log.d(TAG, "setSurfaceCallback ok")
        } catch (t: Throwable) {
            Log.w(TAG, "setSurfaceCallback failed: ${t.message}")
        }
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        width = container.width
        height = container.height
        Log.d(TAG, "onSurfaceAvailable w=$width h=$height")
        releaseEgl()
        val surface = container.surface
        if (surface == null) { Log.w(TAG, "null surface"); return }
        if (!setupEgl(surface)) { Log.w(TAG, "setupEgl failed"); return }
        if (!setupGl()) { Log.w(TAG, "setupGl failed"); return }
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap!!)
        ready = true
        requestRedraw()
    }

    override fun onVisibleAreaChanged(area: Rect) {
        visibleArea = area
        requestRedraw()
    }

    override fun onStableAreaChanged(area: Rect) {
        visibleArea = area
        requestRedraw()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        Log.d(TAG, "onSurfaceDestroyed")
        ready = false
        releaseEgl()
        bitmap?.recycle()
        bitmap = null
        canvas = null
    }

    private fun setupEgl(surface: android.view.Surface): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) return false
        val config = configs[0] ?: return false

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun setupGl(): Boolean {
        val vsSrc = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTex;
            void main() {
                gl_Position = aPosition;
                vTex = aTexCoord;
            }
        """.trimIndent()
        val fsSrc = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
        """.trimIndent()
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        if (vs == 0 || fs == 0) return false

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "link: ${GLES20.glGetProgramInfoLog(program)}")
            return false
        }
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTex")

        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val texs = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
        texCoordBuffer = ByteBuffer.allocateDirect(texs.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texs); position(0) }

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glViewport(0, 0, width, height)
        return true
    }

    private fun compile(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "shader: ${GLES20.glGetShaderInfoLog(id)}")
            GLES20.glDeleteShader(id)
            return 0
        }
        return id
    }

    fun requestRedraw() {
        if (!ready) return
        val bmp = bitmap ?: return
        val cv = canvas ?: return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return

        drawLyrics(cv, bmp.width, bmp.height)

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawLyrics(c: Canvas, w: Int, h: Int) {
        val state = mediaTracker.state.value
        drawBackground(c, w, h, state)

        val area = visibleArea
        val sl = area?.left?.toFloat() ?: 0f
        val st = area?.top?.toFloat() ?: 0f
        val sr = area?.right?.toFloat() ?: w.toFloat()
        val sb = area?.bottom?.toFloat() ?: h.toFloat()

        // Header uses the full top of the surface. The host's ActionStrip pill
        // briefly overlays the top-right corner then fades out — fine to overlap.
        val headerBottom = h.toFloat() * 0.33f
        drawHeader(c, 0f, 0f, w.toFloat(), headerBottom, state)

        // Lyrics area: split into three equal vertical slots, one per line.
        val bodyTop = headerBottom + 12f
        val bodyBottom = sb - 12f
        val cx = (sl + sr) / 2f
        val bodyH = bodyBottom - bodyTop
        val slotH = bodyH / 3f
        val prevY = bodyTop + slotH * 0.5f
        val curY = bodyTop + slotH * 1.5f
        val nextY = bodyTop + slotH * 2.5f

        val maxTextWidth = (sr - sl) * 0.90f

        if (state.track == null || state.lines.isEmpty()) {
            val msg = when (state.status) {
                LyricsStatus.NO_MEDIA -> "Play a song to see lyrics"
                LyricsStatus.LOADING -> "Loading lyrics..."
                LyricsStatus.NOT_FOUND -> "No lyrics found"
                LyricsStatus.ERROR -> "Error loading lyrics"
                else -> "♪"
            }
            drawWrapped(c, msg, cx, curY, statusPaint, maxTextWidth)
            return
        }

        // Continuous "virtual index" for a smooth rolling scroll effect.
        val idxFloat = currentIndexFloat(state)
        val idxInt = idxFloat.toInt().coerceIn(0, state.lines.size - 1)
        val offset = idxFloat - idxInt

        // Clip to the body area so scrolling lines never leak into the header.
        c.save()
        c.clipRect(sl, bodyTop, sr, bodyBottom)
        for (d in -2..2) {
            val lineIdx = idxInt + d
            val text = lineOrBlank(state.lines, lineIdx)
            if (text.isBlank()) continue
            val relative = d - offset
            val y = curY + relative * slotH
            val isCurrent = (d == 0 && offset < 0.5f) || (d == 1 && offset >= 0.5f)
            val paint = if (isCurrent) curPaint else prevPaint
            val distance = Math.abs(relative)
            val alpha = ((1f - distance * 0.45f).coerceIn(0.15f, 1f) * 255f).toInt()
            val prevAlpha = paint.alpha
            paint.alpha = alpha
            drawWrapped(c, text, cx, y, paint, maxTextWidth)
            paint.alpha = prevAlpha
        }
        c.restore()
    }

    private fun currentIndexFloat(state: LyricsState): Float {
        if (state.lines.isEmpty()) return 0f
        val pos = try { mediaTracker.getCurrentPositionMs() } catch (_: Exception) { 0L }
        if (state.status == LyricsStatus.FOUND) {
            val adjusted = pos + state.offsetMs
            var idx = -1
            for (i in state.lines.indices) {
                if (state.lines[i].timeMs <= adjusted) idx = i else break
            }
            if (idx < 0) return 0f
            val curTime = state.lines[idx].timeMs
            val nextTime = if (idx + 1 < state.lines.size) state.lines[idx + 1].timeMs else curTime + 3000L
            val span = (nextTime - curTime).coerceAtLeast(1L)
            val within = ((adjusted - curTime).toFloat() / span).coerceIn(0f, 1f)
            // Keep the center stable for most of each line, roll smoothly in the
            // last 30% so the eye only follows a brief slide, not a constant drift.
            val roll = ((within - 0.70f) / 0.30f).coerceIn(0f, 1f)
            return idx + roll
        }
        if (state.status == LyricsStatus.PLAIN_ONLY) {
            val duration = state.track?.durationMs ?: 0
            if (duration <= 0) return 0f
            return (pos.toFloat() / duration * state.lines.size).coerceIn(0f, (state.lines.size - 1).toFloat())
        }
        return 0f
    }

    private fun drawHeader(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, state: LyricsState) {
        val track = state.track ?: return
        val height = bottom - top
        val coverSize = height * 0.80f
        val margin = height * 0.10f
        val cLeft = left + margin
        val cTop = top + margin
        reusableRect.set(cLeft, cTop, cLeft + coverSize, cTop + coverSize)
        val cRect = reusableRect

        // Cover (if available) — cache the BitmapShader per album art so we
        // don't rebuild it at 60 fps.
        val art = state.albumArt
        if (art != null) {
            reusableShadowRect.set(cRect.left + 3, cRect.top + 4, cRect.right + 3, cRect.bottom + 4)
            c.drawRoundRect(reusableShadowRect, 10f, 10f, coverShadowPaint)
            val artKey = System.identityHashCode(art)
            if (cachedCoverShader == null || cachedCoverShaderKey != artKey) {
                cachedCoverShader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                cachedCoverShaderKey = artKey
            }
            val scale = coverSize / minOf(art.width, art.height)
            val dx = (art.width * scale - coverSize) / 2f
            val dy = (art.height * scale - coverSize) / 2f
            reusableMatrix.setScale(scale, scale)
            reusableMatrix.postTranslate(cLeft - dx, cTop - dy)
            cachedCoverShader!!.setLocalMatrix(reusableMatrix)
            coverPaint.shader = cachedCoverShader
            c.drawRoundRect(cRect, 10f, 10f, coverPaint)
            coverPaint.shader = null
        }

        // Title + artist right of the cover
        val textLeft = cRect.right + margin
        val textRight = right - margin
        val maxW = textRight - textLeft
        if (maxW > 50f) {
            titlePaint.textAlign = Paint.Align.LEFT
            artistPaint.textAlign = Paint.Align.LEFT
            val title = ellipsize(track.title, titlePaint, maxW)
            val artist = ellipsize(track.artist, artistPaint, maxW)
            c.drawText(title, textLeft, cTop + titlePaint.textSize, titlePaint)
            c.drawText(artist, textLeft, cTop + titlePaint.textSize + artistPaint.textSize + 4f, artistPaint)

            // Waveform (SoundCloud-style) below artist
            val duration = track.durationMs
            if (duration > 0) {
                val pos = try { mediaTracker.getCurrentPositionMs() } catch (_: Exception) { 0L }
                val frac = (pos.toFloat() / duration).coerceIn(0f, 1f)
                val waveTop = cTop + titlePaint.textSize + artistPaint.textSize + 10f
                val waveBottom = cRect.bottom
                val label = "${formatMs(pos)} / ${formatMs(duration)}"
                // Reserve exactly the measured label width + small gap, so the
                // waveform runs flush right up to the time text.
                timePaint.textAlign = Paint.Align.RIGHT
                val labelWidth = timePaint.measureText(label)
                val waveRight = textRight - labelWidth - 10f
                drawWaveform(c, track.title + "|" + track.artist, textLeft, waveTop, waveRight, waveBottom, frac)
                val labelY = (waveTop + waveBottom) / 2f + timePaint.textSize / 3f
                c.drawText(label, textRight, labelY, timePaint)
            }
        }
    }

    private fun drawWaveform(c: Canvas, trackKey: String, left: Float, top: Float, right: Float, bottom: Float, frac: Float) {
        val width = right - left
        if (width <= 0f) return
        val height = (bottom - top).coerceAtLeast(10f)
        val barWidth = 3f
        val gap = 3f
        val count = ((width + gap) / (barWidth + gap)).toInt().coerceAtLeast(10)

        if (trackKey != waveTrackKey || waveHeights.size != count) {
            waveHeights = generateWaveHeights(trackKey, count)
            waveTrackKey = trackKey
        }

        val midY = (top + bottom) / 2f
        val minH = height * 0.10f
        val maxH = height * 0.80f
        val exactPlayed = count * frac
        val fullyPlayed = exactPlayed.toInt()
        val fade = exactPlayed - fullyPlayed // 0..1 for the boundary bar

        for (i in 0 until count) {
            val x = left + i * (barWidth + gap)
            val h = minH + (maxH - minH) * waveHeights[i]
            val paint = when {
                i < fullyPlayed -> waveFgPaint
                i == fullyPlayed -> {
                    // smooth transition: lerp alpha so the bar brightens as the
                    // position moves across it, instead of snapping bar-to-bar.
                    val alpha = (90 + (255 - 90) * fade).toInt().coerceIn(0, 255)
                    waveBoundaryPaint.color = Color.argb(alpha, 255, 255, 255)
                    waveBoundaryPaint
                }
                else -> waveBgPaint
            }
            c.drawRoundRect(x, midY - h / 2f, x + barWidth, midY + h / 2f, 1f, 1f, paint)
        }
    }

    private fun generateWaveHeights(seedKey: String, count: Int): FloatArray {
        val seed = seedKey.hashCode().toLong() * 2862933555777941757L + 3037000493L
        val out = FloatArray(count)
        var s = if (seed == 0L) 1L else seed
        // Uniform distribution across the full track, SoundCloud-style. Smooth
        // adjacent bars with a 3-tap moving average so the pattern doesn't look
        // like pure visual noise.
        val raw = FloatArray(count)
        for (i in 0 until count) {
            s = s * 6364136223846793005L + 1442695040888963407L
            val n = ((s ushr 33).toInt() and 0xFFFF) / 65535f
            raw[i] = 0.35f + n * 0.65f
        }
        for (i in 0 until count) {
            val l = raw[(i - 1).coerceAtLeast(0)]
            val m = raw[i]
            val r = raw[(i + 1).coerceAtMost(count - 1)]
            out[i] = (l + m + r) / 3f
        }
        return out
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && paint.measureText("$s…") > maxWidth) s = s.dropLast(1)
        return "$s…"
    }

    private fun formatMs(ms: Long): String {
        val total = (ms / 1000).toInt()
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    private fun drawBackground(c: Canvas, w: Int, h: Int, state: LyricsState) {
        val art = state.albumArt
        val wF = w.toFloat()
        val hF = h.toFloat()

        if (art != null) {
            // Heavy blur via iterated downscale/upscale chain. The final
            // bitmap is cached at a high resolution so drawing it full-screen
            // is a clean bilinear upscale without visible pixel steps.
            val key = System.identityHashCode(art)
            if (cachedBlurredArt == null || key != cachedBlurredArtKey) {
                cachedBlurredArt?.recycle()
                cachedBlurredArt = buildBlurredBitmap(art)
                cachedBlurredArtKey = key
            }
            val bg = cachedBlurredArt!!
            val scale = maxOf(wF / bg.width, hF / bg.height) * 1.1f
            val dw = bg.width * scale
            val dh = bg.height * scale
            val dx = (wF - dw) / 2f
            val dy = (hF - dh) / 2f
            reusableMatrix2.setScale(scale, scale)
            reusableMatrix2.postTranslate(dx, dy)
            c.drawBitmap(bg, reusableMatrix2, bitmapBgPaint)

            // Dark overlay for text legibility
            c.drawRect(0f, 0f, wF, hF, overlayPaint)

            // Vignette gradient — rebuilt only when dominant color or height
            // actually changes.
            val dominant = state.albumColors?.dominantDark ?: Color.BLACK
            val vignetteKey = (dominant.toLong() shl 32) or (h.toLong() and 0xFFFFFFFFL)
            if (cachedVignette == null || vignetteKey != cachedVignetteKey) {
                val topColor = Color.argb(140, Color.red(dominant), Color.green(dominant), Color.blue(dominant))
                cachedVignette = LinearGradient(
                    0f, 0f, 0f, hF,
                    intArrayOf(topColor, 0x00000000, 0xC8000000.toInt()),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                cachedVignetteKey = vignetteKey
            }
            gradientPaint.shader = cachedVignette
            c.drawRect(0f, 0f, wF, hF, gradientPaint)
            return
        }

        // Fallback: subtle gradient from the dominant color to near-black.
        val dominant = state.albumColors?.dominantDark ?: 0xFF101014.toInt()
        if (dominant != lastBgDominant || lastGradient == null) {
            val topColor = darken(dominant, 0.55f)
            val bottomColor = darken(dominant, 0.08f)
            lastGradient = LinearGradient(
                0f, 0f, 0f, hF,
                topColor, bottomColor, Shader.TileMode.CLAMP
            )
            lastBgDominant = dominant
        }
        gradientPaint.shader = lastGradient
        c.drawRect(0f, 0f, wF, hF, gradientPaint)
    }

    private fun buildBlurredBitmap(src: Bitmap): Bitmap {
        val ratio = src.height.toFloat() / src.width
        // Chain of ever-smaller scaled bitmaps = iterated box blur approximation.
        val chain = intArrayOf(256, 128, 64, 32, 20, 32, 64, 128, 256)
        var current = Bitmap.createScaledBitmap(src, chain[0], (chain[0] * ratio).toInt().coerceAtLeast(16), true)
        for (i in 1 until chain.size) {
            val next = Bitmap.createScaledBitmap(current, chain[i], (chain[i] * ratio).toInt().coerceAtLeast(16), true)
            if (current !== src) current.recycle()
            current = next
        }
        return current
    }

    private fun darken(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        val r = (Color.red(color) * f).toInt()
        val g = (Color.green(color) * f).toInt()
        val b = (Color.blue(color) * f).toInt()
        return Color.rgb(r, g, b)
    }

    private fun drawWrapped(c: Canvas, text: String, cx: Float, cy: Float, paint: Paint, maxWidth: Float) {
        if (text.isBlank()) return
        val lines = wrap(text, paint, maxWidth)
        val fm = paint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.05f
        val blockTop = cy - lines.size * lineHeight / 2f
        for (i in lines.indices) {
            val baseline = blockTop + i * lineHeight - fm.ascent
            c.drawText(lines[i], cx, baseline, paint)
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val words = text.split(' ')
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (w in words) {
            val tentative = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(tentative) <= maxWidth) {
                cur = StringBuilder(tentative)
            } else {
                if (cur.isNotEmpty()) out.add(cur.toString())
                cur = StringBuilder(w)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun lineOrBlank(lines: List<LyricLine>, idx: Int): String =
        lines.getOrNull(idx)?.text?.takeIf { it.isNotBlank() } ?: " "

    private fun currentIndex(state: LyricsState): Int {
        if (state.lines.isEmpty()) return -1
        val pos = try { mediaTracker.getCurrentPositionMs() } catch (_: Exception) { 0L }
        if (state.status == LyricsStatus.FOUND) {
            val target = pos + state.offsetMs
            var idx = -1
            for (i in state.lines.indices) {
                if (state.lines[i].timeMs <= target) idx = i else break
            }
            return idx.coerceAtLeast(0)
        }
        if (state.status == LyricsStatus.PLAIN_ONLY) {
            val duration = state.track?.durationMs ?: 0
            if (duration <= 0) return 0
            return ((pos.toFloat() / duration) * state.lines.size).toInt()
                .coerceIn(0, state.lines.size - 1)
        }
        return 0
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val TAG = "LyricsGlRenderer"
    }
}
