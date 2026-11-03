package com.freetv.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import coil.load
import com.freetv.app.databinding.ActivityPlayerBinding
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Full-screen live player.
 *  Remote:  UP / CH+ = next channel,  DOWN / CH- = previous channel,  OK = show / hide info,
 *           LEFT / RIGHT = move between the on-screen buttons,  BACK = back to the list.
 *  Touch:   tap the screen to show / hide the buttons;  swipe up = next channel,  swipe down = previous channel (Phase 35).
 *  Overlay (Phase 33): top bar (back, name, LIVE) + bottom bar (position, round icon buttons), faded in / out in 150 ms.
 *  Channel panel (Phase 34): right side list of the playlist, opened by the channels button (or RIGHT on that button); BACK closes it first.
 *  Tuning screen (Phase 37): logo / initials + name + thin cyan bar on @color/bg while a channel loads; faded out at STATE_READY.
 *  Error card (Phase 37): "This channel isn't available right now" + Retry / Next channel; with Prefs.skipDeadOnZap on, a 5 s countdown
 *  jumps to the next channel by itself (any key or tap cancels it).
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var index = 0
    private var retried = false
    private var healthMarked = false    // true once HealthStore.mark(url, true) was sent for the current prepare()
    private var recentAdded = false     // Phase 25: true once Prefs.addRecent was sent for the current channel
    private var overlayShown = false    // Phase 33: what the overlay is heading to (true while fading in / shown, false while fading out / hidden)
    private var panelOpen = false       // Phase 34: the channel panel is open
    private var zapAdapter: ZapAdapter? = null
    private var swipeDone = false       // Phase 35: the current touch already zapped once (one zap per swipe)
    private var tuningShown = false     // Phase 37: what the tuning screen is heading to (true while shown, false while fading out / hidden)
    private var tuneAnim: ObjectAnimator? = null   // Phase 37: sweep of the thin bar, runs only while the tuning screen is up
    private var autoSkipActive = false  // Phase 37: the 5 s countdown on the error card is running
    private var autoSkipLeft = 0        // Phase 37: seconds left on that countdown
    private var autoSkipStreak = 0      // Phase 37: automatic jumps in a row without a channel reaching STATE_READY or the user pressing anything
    private val backCallback = object : OnBackPressedCallback(false) {   // enabled only while the panel is open
        override fun handleOnBackPressed() {
            closePanel()
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayTask = Runnable { hideOverlay() }
    private val retryTask = Runnable {
        healthMarked = false
        player?.prepare()
    }
    private val countdownTask = Runnable { tickCountdown() }   // Phase 37: one tick per second

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ChannelStore.playlist.isEmpty()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        hideSystemBars()

        index = ChannelStore.index.coerceIn(0, ChannelStore.playlist.lastIndex)

        // Phase 35: touches on touchLayer go through a GestureDetector (tap = toggle overlay, vertical swipe = zap).
        // The click listener stays for accessibility services; real touches never reach it (the touch listener consumes them).
        b.touchLayer.setOnClickListener { if (panelOpen) closePanel() else toggleOverlay() }
        val swipePx = SWIPE_DP * resources.displayMetrics.density
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                swipeDone = false
                return true   // without this the detector gets no MOVE / UP events
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (panelOpen) closePanel() else toggleOverlay()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (swipeDone || e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) > swipePx && abs(dy) > abs(dx)) {     // far enough and mostly vertical
                    swipeDone = true
                    zap(if (dy < 0) 1 else -1)                    // finger up = next channel, finger down = previous
                }
                return true
            }
        })
        b.touchLayer.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
        b.btnBack.setOnClickListener { finish() }
        b.btnPrev.setOnClickListener { zap(-1) }
        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnNext.setOnClickListener { zap(1) }
        b.btnFav.setOnClickListener { toggleFavorite() }
        b.btnChannels.setOnClickListener { openPanel() }
        b.errRetry.setOnClickListener { playCurrent() }   // Phase 37: same path as opening the channel (tuning screen again, fresh retry budget)
        b.errNext.setOnClickListener { zap(1) }           // Phase 37: next channel (skips DEAD ones while Prefs.skipDeadOnZap is on)

        // Phase 34: channel panel (list of the playlist, current channel highlighted)
        onBackPressedDispatcher.addCallback(this, backCallback)
        val zap = ZapAdapter { pos -> pickChannel(pos) }
        zapAdapter = zap
        b.channelList.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            setHasFixedSize(true)
            itemAnimator = null
            adapter = zap
        }
        zap.submitList(ChannelStore.playlist)   // diffed on a background thread
        zap.setCurrent(index)
    }

    override fun onStart() {
        super.onStart()
        if (!::b.isInitialized) return
        // Fast start: small start buffers, decoder fallback, audio focus, pause when headphones are unplugged
        val p = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15000, 40000, 1500, 3000)
                    .build()
            )
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
        p.addListener(listener)
        b.playerView.player = p
        player = p
        playCurrent()
    }

    override fun onStop() {
        super.onStop()
        if (!::b.isInitialized) return
        handler.removeCallbacksAndMessages(null)
        b.overlay.animate().cancel()            // Phase 33: settle a fade that is still running
        b.overlay.alpha = 1f
        b.overlay.isVisible = overlayShown
        // Phase 37: settle the tuning fade, stop the bar, forget the countdown (handler callbacks were removed just above)
        b.tuning.animate().cancel()
        b.tuning.alpha = 1f
        b.tuning.isVisible = tuningShown
        stopTuneBar()
        autoSkipActive = false
        autoSkipStreak = 0
        b.playerView.player = null
        player?.release()
        player = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------- playback ----------

    private fun playCurrent() {
        val list = ChannelStore.playlist
        val ch = list.getOrNull(index)
        if (ch == null) {
            finish()
            return
        }
        ChannelStore.index = index
        zapAdapter?.setCurrent(index)   // Phase 34: the panel highlights the channel that plays now
        retried = false
        healthMarked = false
        recentAdded = false
        handler.removeCallbacks(retryTask)
        hideError()          // Phase 37: a new start clears the error card and its countdown
        showTuning(ch)       // Phase 37: "Tuning in" screen until STATE_READY
        showInfo(ch)

        // Shared OkHttp client: redirects (also http <-> https) and timeouts come from Net.client
        val http = OkHttpDataSource.Factory(Net.client)
            .setUserAgent(ch.userAgent ?: DEFAULT_UA)
        ch.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val item = MediaItem.Builder().setUri(ch.url)
        val lower = ch.url.lowercase()
        if (lower.contains(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (lower.contains(".mpd")) {
            item.setMimeType(MimeTypes.APPLICATION_MPD)
        }

        val source = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(http)
            .createMediaSource(item.build())

        player?.let {
            applyQualityCap(it)   // Phase 32: read Prefs.qualityCap for every channel
            it.setMediaSource(source)
            it.prepare()
            it.playWhenReady = true
        }
        updatePlayButton()
    }

    private fun zap(delta: Int) {
        val list = ChannelStore.playlist
        val n = list.size
        if (n == 0) return
        // Plain neighbour = the behaviour before Phase 12. It stays the target when every channel is DEAD.
        var target = ((index + delta) % n + n) % n
        if (Prefs.skipDeadOnZap) {
            // Walk in the zapping direction, at most n steps, and take the first channel that is not DEAD.
            var candidate = target
            var steps = 0
            while (steps < n) {
                if (HealthStore.state(list[candidate].url) != HealthStore.State.DEAD) {
                    target = candidate
                    break
                }
                candidate = ((candidate + delta) % n + n) % n
                steps++
            }
        }
        index = target
        playCurrent()
    }

    // Phase 32: Prefs.qualityCap -> 0 = no limit, 1 = SD max (setMaxVideoSizeSd), 2 = 720p max (1280 x 720). Applied to the player's track
    // selection before the next channel is prepared. Only set when something changed, so a zap does not re-select the tracks for nothing.
    private fun applyQualityCap(p: ExoPlayer) {
        val current = p.trackSelectionParameters
        val builder = current.buildUpon()
        when (Prefs.qualityCap) {
            1 -> builder.setMaxVideoSizeSd()
            2 -> builder.setMaxVideoSize(1280, 720)
            else -> builder.clearVideoSizeConstraints()
        }
        val next = builder.build()
        if (next != current) p.setTrackSelectionParameters(next)
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePlayButton()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            b.buffering.isVisible = playbackState == Player.STATE_BUFFERING && !tuningShown   // Phase 37: the tuning screen has its own bar
            if (playbackState == Player.STATE_READY) {
                hideTuning(true)         // Phase 37: fade the tuning screen out
                autoSkipStreak = 0       // Phase 37: something plays again, so automatic jumping may start over
                hideError()
                if (!healthMarked) {     // once per prepare(), not on every rebuffer
                    healthMarked = true
                    ChannelStore.playlist.getOrNull(index)?.let { HealthStore.mark(it.url, true) }
                }
                if (!recentAdded) {      // Phase 25: once per channel, the first time it plays (not when it is merely opened)
                    recentAdded = true
                    // Prefs parses / writes JSON, so it runs on a background thread
                    ChannelStore.playlist.getOrNull(index)?.let { ch -> RECENTS_IO.execute { Prefs.addRecent(ch) } }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                healthMarked = false
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (!retried) {          // one quiet retry before giving up
                retried = true
                handler.postDelayed(retryTask, 1500)
                return
            }
            ChannelStore.playlist.getOrNull(index)?.let { HealthStore.mark(it.url, false) }   // final error
            b.buffering.isVisible = false
            hideTuning(false)            // Phase 37: no logo behind the error card
            showError()                  // Phase 37: card with Retry / Next channel (+ countdown)
            showOverlay()
        }
    }

    // ---------- overlay / favorites ----------

    private fun showInfo(ch: Channel) {
        b.chName.text = ch.name
        val pos = "${index + 1} / ${ChannelStore.playlist.size}"
        b.chInfo.text = ch.group?.let { "$pos  •  $it" } ?: pos
        b.error.isVisible = false
        updateFavButton()
        updatePlayButton()
        showOverlay()
    }

    // Phase 33: the overlay fades (alpha, 150 ms) instead of popping. Every call restarts the 5 s auto-hide timer.
    private fun showOverlay() {
        handler.removeCallbacks(hideOverlayTask)
        handler.postDelayed(hideOverlayTask, AUTO_HIDE_MS)
        if (overlayShown) return
        overlayShown = true
        b.overlay.animate().cancel()
        if (!b.overlay.isVisible) {
            b.overlay.alpha = 0f
            b.overlay.isVisible = true
        }
        b.overlay.animate().alpha(1f).setDuration(FADE_MS).withLayer().start()
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hideOverlayTask)
        if (!overlayShown) return
        overlayShown = false
        b.overlay.animate().cancel()
        b.overlay.animate().alpha(0f).setDuration(FADE_MS).withLayer()
            .withEndAction { if (!overlayShown) b.overlay.isVisible = false }
            .start()
    }

    private fun toggleOverlay() {
        if (overlayShown) hideOverlay() else showOverlay()
    }

    private fun togglePlay() {
        player?.let { it.playWhenReady = !it.playWhenReady }
        updatePlayButton()
        showOverlay()
    }

    // Pause icon while playing (playWhenReady), play icon while paused
    private fun updatePlayButton() {
        val playing = player?.playWhenReady ?: true
        b.btnPlay.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        b.btnPlay.contentDescription = getString(if (playing) R.string.player_pause else R.string.player_play)
    }

    // ---------- tuning screen + error card (Phase 37) ----------

    // "Tuning in": logo (or initials) + name on @color/bg with a thin cyan bar. Initials show at once, the logo replaces them when it has loaded
    // (a logo that fails to load simply leaves the initials). Fades out in hideTuning() when the channel is ready.
    private fun showTuning(ch: Channel) {
        tuningShown = true
        val url = ch.url
        b.tuneName.text = ch.name
        b.tuneMono.text = monogram(ch.name)
        b.tuneMono.isVisible = true
        b.tuneLogo.isVisible = false
        val logo = ch.logo
        if (!logo.isNullOrBlank()) {
            b.tuneLogo.load(logo) {
                listener(onSuccess = { _, _ ->
                    // ignore a late answer for a channel that is no longer the one being tuned
                    if (tuningShown && ChannelStore.playlist.getOrNull(index)?.url == url) {
                        b.tuneLogo.isVisible = true
                        b.tuneMono.isVisible = false
                    }
                })
            }
        }
        b.tuning.animate().cancel()
        b.tuning.alpha = 1f
        b.tuning.isVisible = true
        b.buffering.isVisible = false
        startTuneBar()
    }

    // animated = true: fade out in TUNE_FADE_MS (STATE_READY); false: gone at once (final error)
    private fun hideTuning(animated: Boolean) {
        if (!tuningShown) return
        tuningShown = false
        b.tuning.animate().cancel()
        if (!animated) {
            b.tuning.alpha = 1f
            b.tuning.isVisible = false
            stopTuneBar()
            return
        }
        b.tuning.animate().alpha(0f).setDuration(TUNE_FADE_MS).withLayer()
            .withEndAction {
                if (!tuningShown) {       // a new channel may have started while it faded
                    b.tuning.isVisible = false
                    stopTuneBar()
                }
            }
            .start()
    }

    // The cyan sliver (tuneBar) sweeps back and forth along the track (tuneTrack). It rests at the right end when animations are switched off.
    private fun startTuneBar() {
        if (tuneAnim?.isRunning == true) return
        val travel = (TUNE_TRACK_DP - TUNE_BAR_DP) * resources.displayMetrics.density
        b.tuneBar.translationX = 0f
        val a = ObjectAnimator.ofFloat(b.tuneBar, View.TRANSLATION_X, 0f, travel)
        a.setDuration(TUNE_SWEEP_MS)
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.interpolator = AccelerateDecelerateInterpolator()
        a.start()
        tuneAnim = a
    }

    private fun stopTuneBar() {
        tuneAnim?.cancel()
        tuneAnim = null
    }

    // First 1-2 letters / digits of the name, upper case ("TV" if there are none): same rule as the channel cards
    private fun monogram(name: String): String {
        val sb = StringBuilder(2)
        for (c in name) {
            if (c.isLetterOrDigit()) {
                sb.append(c)
                if (sb.length == 2) break
            }
        }
        return if (sb.isEmpty()) "TV" else sb.toString().uppercase()
    }

    // Final playback error: show the card. With Prefs.skipDeadOnZap on (and somewhere to go) a 5 s countdown jumps to the next channel by itself.
    private fun showError() {
        b.error.isVisible = true
        if (Prefs.skipDeadOnZap && ChannelStore.playlist.size > 1 && autoSkipStreak < MAX_AUTO_SKIPS) {
            autoSkipLeft = AUTO_SKIP_SECONDS
            autoSkipActive = true
            updateCountdownText()
            b.errorCountdown.isVisible = true
            handler.removeCallbacks(countdownTask)
            handler.postDelayed(countdownTask, 1000L)
        } else {
            cancelCountdown()
        }
        if (!panelOpen) b.errRetry.requestFocus()   // remote: OK retries at once (no effect in touch mode)
    }

    private fun hideError() {
        cancelCountdown()
        val hadFocus = b.error.hasFocus()
        b.error.isVisible = false
        // The remote's focus was on Retry / Next. Without this the system hands it to the first visible button (the back arrow), and OK would leave the player.
        if (hadFocus) b.btnPlay.requestFocus()
    }

    private fun tickCountdown() {
        if (!autoSkipActive) return
        autoSkipLeft--
        if (autoSkipLeft > 0) {
            updateCountdownText()
            handler.postDelayed(countdownTask, 1000L)
            return
        }
        autoSkipActive = false
        autoSkipStreak++
        zap(1)   // playCurrent() hides the card
    }

    private fun updateCountdownText() {
        b.errorCountdown.text = getString(R.string.player_err_countdown, autoSkipLeft)
    }

    private fun cancelCountdown() {
        autoSkipActive = false
        handler.removeCallbacks(countdownTask)
        b.errorCountdown.isVisible = false
    }

    // Any key press or touch = the user is there: stop the countdown (the card stays) and let automatic jumping start over
    private fun onUserInput() {
        if (!::b.isInitialized) return
        autoSkipStreak = 0
        if (autoSkipActive) cancelCountdown()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) onUserInput()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onUserInput()
        return super.dispatchTouchEvent(ev)
    }

    // ---------- channel panel (Phase 34) ----------

    private fun openPanel() {
        if (panelOpen) return
        panelOpen = true
        backCallback.isEnabled = true
        zapAdapter?.setCurrent(index)
        hideOverlay()
        b.overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS   // the remote must not leave the panel
        b.channelPanel.isVisible = true
        b.channelList.doOnLayout { scrollPanelToCurrent() }
    }

    private fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        backCallback.isEnabled = false
        b.channelPanel.isVisible = false
        b.overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        showOverlay()
    }

    // Current row in the middle of the panel, then focus on it (remote)
    private fun scrollPanelToCurrent() {
        val lm = b.channelList.layoutManager as? LinearLayoutManager ?: return
        val rowPx = (ZAP_ROW_DP * resources.displayMetrics.density).toInt()
        lm.scrollToPositionWithOffset(index, ((b.channelList.height - rowPx) / 2).coerceAtLeast(0))
        b.channelList.doOnLayout { focusCurrentRow() }
    }

    private fun focusCurrentRow() {
        if (!panelOpen) return
        val holder = b.channelList.findViewHolderForAdapterPosition(index)
        if (holder != null) holder.itemView.requestFocus() else b.channelList.requestFocus()
    }

    // A row was clicked: play that channel through the normal playCurrent() path, then close the panel
    private fun pickChannel(pos: Int) {
        if (pos < 0 || pos > ChannelStore.playlist.lastIndex) return
        if (pos != index) {
            index = pos
            playCurrent()
        }
        closePanel()
    }

    private fun toggleFavorite() {
        val ch = ChannelStore.playlist.getOrNull(index) ?: return
        Prefs.toggleFavorite(ch)
        updateFavButton()
        showOverlay()
    }

    private fun updateFavButton() {
        val ch = ChannelStore.playlist.getOrNull(index) ?: return
        val fav = Prefs.isFavorite(ch.url)
        b.btnFav.setImageResource(if (fav) R.drawable.ic_player_heart_on else R.drawable.ic_player_heart)
        b.btnFav.contentDescription = getString(if (fav) R.string.player_fav_remove else R.string.player_fav_add)
    }

    // ---------- remote control ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val firstPress = (event?.repeatCount ?: 0) == 0
        if (panelOpen) {
            when (keyCode) {
                // UP / DOWN / OK belong to the list (normal focus movement and clicks), not to zapping
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> return super.onKeyDown(keyCode, event)

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (firstPress) closePanel()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (firstPress) zap(1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (firstPress) zap(-1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_INFO -> {
                if (firstPress) toggleOverlay()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                togglePlay()
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Phase 34: RIGHT on the last button (channels) opens the channel panel
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && overlayShown && currentFocus === b.btnChannels) {
                    openPanel()
                    return true
                }
                showOverlay()
                // Nothing focused yet: the first press only highlights the play button, then LEFT / RIGHT move between the buttons
                if (currentFocus == null && b.btnPlay.requestFocus()) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private companion object {
        val RECENTS_IO = Executors.newSingleThreadExecutor()   // Phase 25: one background thread, keeps addRecent calls in order
        const val AUTO_HIDE_MS = 5000L   // overlay auto-hide
        const val FADE_MS = 150L         // overlay fade in / out (Phase 33)
        const val SWIPE_DP = 100f        // Phase 35: minimum vertical swipe distance to zap
        const val ZAP_ROW_DP = 60f       // one panel row: 56dp + 2dp margin above and below (item_zap.xml)
        const val TUNE_FADE_MS = 250L    // Phase 37: tuning screen fade-out
        const val TUNE_SWEEP_MS = 900L   // Phase 37: one sweep of the cyan sliver (one way)
        const val TUNE_TRACK_DP = 240f   // Phase 37: width of @id/tuneTrack in activity_player.xml
        const val TUNE_BAR_DP = 96f      // Phase 37: width of @id/tuneBar in activity_player.xml
        const val AUTO_SKIP_SECONDS = 5  // Phase 37: countdown on the error card
        const val MAX_AUTO_SKIPS = 5     // Phase 37: automatic jumps in a row before the countdown stops (no endless loop through dead channels / offline)
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
