package com.autolyrics.auto

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus

class LyricsScreen(carContext: CarContext) : Screen(carContext) {

    private val mediaTracker = MediaTracker.getInstance(carContext)
    private val handler = Handler(Looper.getMainLooper())
    private var lastRenderedIdx: Int = Int.MIN_VALUE
    private var lastRenderedTrackKey: String? = null
    private var lastRenderedStatus: LyricsStatus? = null
    private var lastInvalidateFailureLogged = false
    private val updater = object : Runnable {
        override fun run() {
            maybeInvalidate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                handler.removeCallbacks(updater)
                handler.post(updater)
            }

            override fun onPause(owner: LifecycleOwner) {
                handler.removeCallbacks(updater)
            }
        })
    }

    private fun maybeInvalidate() {
        val state = mediaTracker.state.value
        val idx = currentIndex(state)
        val trackKey = state.track?.let { "${it.title}|${it.artist}" }
        val changed = idx != lastRenderedIdx ||
                trackKey != lastRenderedTrackKey ||
                state.status != lastRenderedStatus
        if (!changed) return
        // Update bookkeeping BEFORE calling invalidate so we don't spam retries
        // if the host rejects the call.
        lastRenderedIdx = idx
        lastRenderedTrackKey = trackKey
        lastRenderedStatus = state.status
        if (lifecycle.currentState != Lifecycle.State.RESUMED) return
        try {
            invalidate()
            lastInvalidateFailureLogged = false
        } catch (t: Throwable) {
            if (!lastInvalidateFailureLogged) {
                Log.w(TAG, "invalidate failed (dedup): ${t.message} / cause=${t.cause?.message}")
                lastInvalidateFailureLogged = true
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = mediaTracker.state.value
        val idx = currentIndex(state)
        lastRenderedIdx = idx
        lastRenderedTrackKey = state.track?.let { "${it.title}|${it.artist}" }
        lastRenderedStatus = state.status
        lastInvalidateFailureLogged = false

        Log.d(TAG, "render status=${state.status} idx=$idx lines=${state.lines.size} " +
                "track=${state.track?.title ?: "none"} playing=${state.isPlaying}")

        val pane = buildPane(state)
        val title = buildTitle(state)
        return PaneTemplate.Builder(pane)
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun buildTitle(state: LyricsState): String {
        val track = state.track ?: return "Lyrics"
        return if (track.artist.isNotBlank()) "${track.title} — ${track.artist}" else track.title
    }

    private fun buildPane(state: LyricsState): Pane {
        val builder = Pane.Builder()
        when {
            state.status == LyricsStatus.NO_MEDIA || state.track == null -> {
                builder.addRow(textRow(" "))
                builder.addRow(textRow("Play a song to see lyrics", highlight = true))
                builder.addRow(textRow(" "))
            }
            state.status == LyricsStatus.LOADING -> {
                builder.addRow(textRow(" "))
                builder.addRow(textRow("Loading lyrics…", highlight = true))
                builder.addRow(textRow(" "))
            }
            state.status == LyricsStatus.NOT_FOUND -> {
                builder.addRow(textRow(" "))
                builder.addRow(textRow("No lyrics found", highlight = true))
                builder.addRow(textRow(" "))
            }
            state.status == LyricsStatus.ERROR -> {
                builder.addRow(textRow(" "))
                builder.addRow(textRow("Error loading lyrics", highlight = true))
                builder.addRow(textRow(" "))
            }
            state.lines.isEmpty() -> {
                builder.addRow(textRow(" "))
                builder.addRow(textRow("♪", highlight = true))
                builder.addRow(textRow(" "))
            }
            else -> {
                val idx = currentIndex(state).coerceAtLeast(0)
                val prev = lineTextOrFallback(state.lines, idx - 1)
                val cur = lineTextOrFallback(state.lines, idx)
                val next = lineTextOrFallback(state.lines, idx + 1)
                builder.addRow(textRow(prev))
                builder.addRow(textRow(cur, highlight = true))
                builder.addRow(textRow(next))
            }
        }
        return builder.build()
    }

    private fun lineTextOrFallback(lines: List<LyricLine>, idx: Int): String {
        val raw = lines.getOrNull(idx)?.text?.takeIf { it.isNotBlank() } ?: " "
        return raw
    }

    private fun textRow(text: String, highlight: Boolean = false): Row {
        // PaneTemplate rows forbid ForegroundCarColorSpan in titles, so we signal the
        // current line with an inline marker + bold-ish prefix instead of a color span.
        val displayed = if (highlight) "▶  $text" else "       $text"
        return Row.Builder().setTitle(displayed).build()
    }

    private fun currentIndex(state: LyricsState): Int {
        if (state.lines.isEmpty()) return -1
        if (state.status == LyricsStatus.FOUND && state.currentIndex >= 0) {
            return state.currentIndex
        }
        if (state.status == LyricsStatus.PLAIN_ONLY) {
            val duration = state.track?.durationMs ?: 0
            if (duration <= 0) return 0
            val pos = try { mediaTracker.getCurrentPositionMs() } catch (_: Exception) { 0L }
            return ((pos.toFloat() / duration) * state.lines.size).toInt()
                .coerceIn(0, state.lines.size - 1)
        }
        return 0
    }

    companion object {
        private const val TAG = "LyricsScreen"
        // Car App Library templates have a ~5 second refresh quota per screen.
        // We tick a bit above that so a line-change-triggered invalidate is never
        // rejected for rate-limit reasons.
        private const val TICK_MS = 5500L
    }
}
