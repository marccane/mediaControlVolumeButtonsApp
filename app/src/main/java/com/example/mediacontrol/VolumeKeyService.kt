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
        const val DOUBLE_PRESS_WINDOW = 300L
        const val LONG_PRESS_DURATION = 600L
        const val VOLUME_REPEAT_START_DELAY = 150L
        const val VOLUME_REPEAT_INTERVAL = 80L
        const val LONG_PRESS = -1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    private inner class KeyState {
        var pendingCount = 0
        var pendingRunnable: Runnable? = null
        var longPressRunnable: Runnable? = null
        var volumeRepeatRunnable: Runnable? = null
        var isLongPressing = false
        var hadRepeat = false
        // True when we fired an immediate volume step on ACTION_DOWN so the
        // delayed single-press action doesn't double-adjust.
        var immediateStepFired = false
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
            KeyEvent.ACTION_UP   -> handleUp(event.keyCode, state)
            else -> false
        }
    }

    private fun singleGesture(keyCode: Int) =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) GestureType.VOL_UP_SINGLE else GestureType.VOL_DOWN_SINGLE

    private fun longGesture(keyCode: Int) =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) GestureType.VOL_UP_LONG else GestureType.VOL_DOWN_LONG

    private fun startVolumeRepeat(keyCode: Int, state: KeyState, initialDelay: Long = VOLUME_REPEAT_START_DELAY) {
        val repeater = object : Runnable {
            override fun run() {
                state.hadRepeat = true
                state.pendingRunnable?.let { handler.removeCallbacks(it) }
                state.pendingCount = 0
                adjustVolume(keyCode)
                handler.postDelayed(this, VOLUME_REPEAT_INTERVAL)
            }
        }
        state.volumeRepeatRunnable = repeater
        handler.postDelayed(repeater, initialDelay)
    }

    private fun stopVolumeRepeat(state: KeyState) {
        state.volumeRepeatRunnable?.let { handler.removeCallbacks(it) }
        state.volumeRepeatRunnable = null
    }

    private fun handleDown(keyCode: Int, repeatCount: Int, state: KeyState): Boolean {
        if (repeatCount != 0) return true

        state.longPressRunnable?.let { handler.removeCallbacks(it) }

        val singleAction = GestureConfig.getAction(this, singleGesture(keyCode))
        val longAction   = GestureConfig.getAction(this, longGesture(keyCode))

        when {
            // Both single and long are default and this is the first press in a sequence:
            // fire one volume step immediately so there is zero perceived delay, then
            // keep repeating. If the user double-presses, the blip is the accepted trade-off.
            singleAction == MediaAction.SYSTEM_DEFAULT
                    && longAction == MediaAction.SYSTEM_DEFAULT
                    && state.pendingCount == 0 -> {
                adjustVolume(keyCode)
                state.immediateStepFired = true
                startVolumeRepeat(keyCode, state, initialDelay = VOLUME_REPEAT_INTERVAL)
            }

            // Long-hold is default but single is something custom (e.g. Play/Pause):
            // can't fire immediately — use the normal delayed repeater.
            longAction == MediaAction.SYSTEM_DEFAULT && state.pendingCount == 0 -> {
                startVolumeRepeat(keyCode, state)
            }

            // Custom long-hold action: schedule the long-press runnable.
            longAction != MediaAction.SYSTEM_DEFAULT -> {
                val lp = Runnable {
                    state.isLongPressing = true
                    stopVolumeRepeat(state)
                    state.pendingRunnable?.let { handler.removeCallbacks(it) }
                    state.pendingCount = 0
                    executeGesture(keyCode, LONG_PRESS)
                }
                state.longPressRunnable = lp
                handler.postDelayed(lp, LONG_PRESS_DURATION)
            }

            // longAction == SYSTEM_DEFAULT but pendingCount > 0 (mid double-press):
            // don't start a repeater — the user is tapping, not holding.
        }
        return true
    }

    private fun handleUp(keyCode: Int, state: KeyState): Boolean {
        state.longPressRunnable?.let { handler.removeCallbacks(it) }
        state.longPressRunnable = null
        stopVolumeRepeat(state)

        if (state.isLongPressing || state.hadRepeat) {
            state.isLongPressing = false
            state.hadRepeat = false
            state.immediateStepFired = false
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
        val state = keyStates[keyCode] ?: return

        if (count == LONG_PRESS) {
            state.immediateStepFired = false
            dispatchAction(keyCode, GestureConfig.getAction(this, longGesture(keyCode)), 1)
            return
        }

        val gesture = when {
            keyCode == KeyEvent.KEYCODE_VOLUME_UP -> if (count >= 2) GestureType.VOL_UP_DOUBLE else GestureType.VOL_UP_SINGLE
            else                                  -> if (count >= 2) GestureType.VOL_DOWN_DOUBLE else GestureType.VOL_DOWN_SINGLE
        }
        val action = GestureConfig.getAction(this, gesture)

        val times = if (action == MediaAction.SYSTEM_DEFAULT) {
            // Subtract the step already fired immediately on ACTION_DOWN so we don't double-adjust.
            val alreadyFired = if (state.immediateStepFired) 1 else 0
            maxOf(0, count - alreadyFired)
        } else {
            // Undo the blip that fired immediately on ACTION_DOWN before executing the real action.
            if (state.immediateStepFired) undoVolume(keyCode)
            1
        }
        state.immediateStepFired = false
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

    @Suppress("DEPRECATION")
    private fun sendMediaKey(mediaKeyCode: Int) {
        // AudioManager.dispatchMediaKeyEvent is deprecated since API 31 but remains the only
        // option for third-party apps without NotificationListenerService access.
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, mediaKeyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, mediaKeyCode))
    }

    private fun adjustVolume(keyCode: Int) {
        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        // adjustVolume targets whichever stream is currently active, which is more
        // correct on Android 14 where media may not always be on STREAM_MUSIC.
        audioManager.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun undoVolume(keyCode: Int) {
        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
        audioManager.adjustVolume(direction, AudioManager.FLAG_SHOW_UI)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
