package com.example.mediacontrol

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

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
    }

    private val keyStates = mapOf(
        KeyEvent.KEYCODE_VOLUME_UP to KeyState(),
        KeyEvent.KEYCODE_VOLUME_DOWN to KeyState()
    )

    override fun onServiceConnected() {
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val state = keyStates[event.keyCode] ?: return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown(event.keyCode, event.repeatCount, state)
            KeyEvent.ACTION_UP -> handleUp(event.keyCode, state)
            else -> false
        }
    }

    private fun handleDown(keyCode: Int, repeatCount: Int, state: KeyState): Boolean {
        if (repeatCount != 0) return true  // consume OS key-repeat events silently

        state.longPressRunnable?.let { handler.removeCallbacks(it) }

        val lp = Runnable {
            state.isLongPressing = true
            state.pendingRunnable?.let { handler.removeCallbacks(it) }
            state.pendingCount = 0
            executeGesture(keyCode, LONG_PRESS)
        }
        state.longPressRunnable = lp
        handler.postDelayed(lp, LONG_PRESS_DURATION)
        return true
    }

    private fun handleUp(keyCode: Int, state: KeyState): Boolean {
        state.longPressRunnable?.let { handler.removeCallbacks(it) }
        state.longPressRunnable = null

        if (state.isLongPressing) {
            state.isLongPressing = false
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
        val gesture = when {
            keyCode == KeyEvent.KEYCODE_VOLUME_UP && count == 1      -> GestureType.VOL_UP_SINGLE
            keyCode == KeyEvent.KEYCODE_VOLUME_UP && count == 2      -> GestureType.VOL_UP_DOUBLE
            keyCode == KeyEvent.KEYCODE_VOLUME_UP && count == LONG_PRESS -> GestureType.VOL_UP_LONG
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && count == 1    -> GestureType.VOL_DOWN_SINGLE
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && count == 2    -> GestureType.VOL_DOWN_DOUBLE
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && count == LONG_PRESS -> GestureType.VOL_DOWN_LONG
            else -> return
        }
        dispatchAction(keyCode, GestureConfig.getAction(this, gesture))
    }

    private fun dispatchAction(keyCode: Int, action: MediaAction) {
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
