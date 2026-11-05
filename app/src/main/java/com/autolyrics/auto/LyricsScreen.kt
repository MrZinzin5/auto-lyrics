package com.autolyrics.auto

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus

class LyricsScreen(carContext: CarContext) : Screen(carContext) {

    private val mediaTracker = MediaTracker.getInstance(carContext)
    private val handler = Handler(Looper.getMainLooper())
    private val renderer = LyricsGlRenderer(carContext, this)
    private var lastInvalidateTrackKey: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            renderer.requestRedraw()
            // Refresh the host-side ActionStrip (the title pill) when the track
            // changes — the ActionStrip is rebuilt in onGetTemplate.
            val trackKey = mediaTracker.state.value.track?.let { "${it.title}|${it.artist}" }
            if (trackKey != lastInvalidateTrackKey) {
                lastInvalidateTrackKey = trackKey
                try { invalidate() } catch (_: Throwable) { }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                handler.removeCallbacks(ticker)
                handler.post(ticker)
                startNavigationSession()
            }

            override fun onPause(owner: LifecycleOwner) {
                handler.removeCallbacks(ticker)
                stopNavigationSession()
            }
        })
    }

    private fun startNavigationSession() {
        try {
            val nav = carContext.getCarService(NavigationManager::class.java)
            nav.setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() { }
                override fun onAutoDriveEnabled() { }
            })
            nav.navigationStarted()
            nav.updateTrip(Trip.Builder().setLoading(true).build())
            Log.d(TAG, "navigationStarted()")
        } catch (t: Throwable) {
            Log.w(TAG, "navigationStarted failed: ${t.message}")
        }
    }

    private fun stopNavigationSession() {
        try {
            carContext.getCarService(NavigationManager::class.java).navigationEnded()
        } catch (_: Throwable) { }
    }

    override fun onGetTemplate(): Template {
        val state = mediaTracker.state.value
        Log.d(TAG, "onGetTemplate status=${state.status} track=${state.track?.title ?: "none"}")
        return NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.DEFAULT)
            .setActionStrip(buildActionStrip(state))
            .build()
    }

    private fun buildActionStrip(state: LyricsState): ActionStrip {
        val title = state.track?.let {
            if (it.artist.isNotBlank()) "${it.title} — ${it.artist}" else it.title
        } ?: "Lyrics"
        return ActionStrip.Builder()
            .addAction(Action.Builder().setTitle(trimStrip(title)).build())
            .build()
    }

    private fun trimStrip(s: String): String =
        if (s.length > 40) s.substring(0, 37) + "…" else s

    companion object {
        private const val TAG = "LyricsScreen"
        // NavigationTemplate surfaces are quota-exempt — we draw via GLES so we can
        // tick at display rate. 10 ms ≈ 100 fps, which also hits every vsync on
        // 60/90/120 Hz head units without missing frames.
        private const val TICK_MS = 10L
    }
}
