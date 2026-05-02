package com.example.mediacontrol

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

@SuppressLint("AccessibilityPolicy")
class VolumeKeyService : AccessibilityService() {

    private companion object {
        const val DOUBLE_PRESS_WINDOW = 350L
        const val LONG_PRESS_DURATION = 650L
        const val LONG_PRESS = -1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    private inner class KeyState {
        var pendingCount = 0
        var pendingRunnable: Runnable? = null
        var longPressRunnable: Runnable? = null
        var isLongPressing = false
        var hadRepeat = false  // true if key-repeat events fired during this hold
    }

    private val keyStates = mapOf(
        KeyEvent.KEYCODE_VOLUME_UP to KeyState(),
        KeyEvent.KEYCODE_VOLUME_DOWN to KeyState()
    )

    override fun onServiceConnected() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!GestureConfig.isActive(this)) return false
        val state = keyStates[event.keyCode] ?: return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown(event.keyCode, event.repeatCount, state)
            KeyEvent.ACTION_UP -> handleUp(event.keyCode, state)
            else -> false
        }
    }

    private fun longGesture(keyCode: Int) =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) GestureType.VOL_UP_LONG else GestureType.VOL_DOWN_LONG

    private fun handleDown(keyCode: Int, repeatCount: Int, state: KeyState): Boolean {
        if (repeatCount != 0) {
            state.hadRepeat = true
            // If long-hold is SYSTEM_DEFAULT, let repeats through so the OS handles
            // continuous volume change. Otherwise suppress them — long-press runnable will fire.
            return GestureConfig.getAction(this, longGesture(keyCode)) != MediaAction.SYSTEM_DEFAULT
        }

        state.longPressRunnable?.let { handler.removeCallbacks(it) }

        // Only need a long-press timer when the long-hold gesture does something custom
        if (GestureConfig.getAction(this, longGesture(keyCode)) != MediaAction.SYSTEM_DEFAULT) {
            val lp = Runnable {
                state.isLongPressing = true
                state.pendingRunnable?.let { handler.removeCallbacks(it) }
                state.pendingCount = 0
                executeGesture(keyCode, LONG_PRESS)
            }
            state.longPressRunnable = lp
            handler.postDelayed(lp, LONG_PRESS_DURATION)
        }
        return true
    }

    private fun handleUp(keyCode: Int, state: KeyState): Boolean {
        state.longPressRunnable?.let { handler.removeCallbacks(it) }
        state.longPressRunnable = null

        // Don't fire a press action if the key was held (either custom long-press fired,
        // or OS key-repeats handled volume continuously)
        if (state.isLongPressing || state.hadRepeat) {
            state.isLongPressing = false
            state.hadRepeat = false
            return true
        }

        state.pendingCount++
        state.pendingRunnable?.let { handler.removeCallbacks(it) }

        val pr = Runnable {
            val count = state.pendingCount
            state.pendingCount = 0
            state.pendingRunnable = null
            executeGesture(keyCode, count)
        }
        state.pendingRunnable = pr
        handler.postDelayed(pr, DOUBLE_PRESS_WINDOW)
        return true
    }

    private fun executeGesture(keyCode: Int, count: Int) {
        if (count == LONG_PRESS) {
            dispatchAction(keyCode, GestureConfig.getAction(this, longGesture(keyCode)), 1)
            return
        }

        val gesture = when {
            keyCode == KeyEvent.KEYCODE_VOLUME_UP -> if (count >= 2) GestureType.VOL_UP_DOUBLE else GestureType.VOL_UP_SINGLE
            else                                  -> if (count >= 2) GestureType.VOL_DOWN_DOUBLE else GestureType.VOL_DOWN_SINGLE
        }
        val action = GestureConfig.getAction(this, gesture)
        // For SYSTEM_DEFAULT, honour the actual press count (double-tap → 2 steps, triple → 3, …)
        val times = if (action == MediaAction.SYSTEM_DEFAULT) count else 1
        dispatchAction(keyCode, action, times)
    }

    private fun dispatchAction(keyCode: Int, action: MediaAction, times: Int) {
        repeat(times) {
            when (action) {
                MediaAction.SYSTEM_DEFAULT -> adjustVolume(keyCode)
                MediaAction.NONE          -> Unit
                MediaAction.PLAY_PAUSE    -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                MediaAction.NEXT_TRACK    -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                MediaAction.PREV_TRACK    -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                MediaAction.STOP          -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
                MediaAction.FAST_FORWARD  -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                MediaAction.REWIND        -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_REWIND)
            }
        }
    }

    private fun sendMediaKey(mediaKeyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, mediaKeyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, mediaKeyCode))
    }

    private fun adjustVolume(keyCode: Int) {
        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
