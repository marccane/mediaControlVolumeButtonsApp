package com.example.mediacontrol

import android.content.Context

enum class MediaAction(val displayName: String) {
    SYSTEM_DEFAULT("System default"),
    NONE("Do nothing"),
    PLAY_PAUSE("Play / Pause"),
    NEXT_TRACK("Next track"),
    PREV_TRACK("Previous track"),
    STOP("Stop"),
    FAST_FORWARD("Fast forward"),
    REWIND("Rewind")
}

enum class GestureType {
    VOL_UP_SINGLE,
    VOL_UP_DOUBLE,
    VOL_UP_LONG,
    VOL_DOWN_SINGLE,
    VOL_DOWN_DOUBLE,
    VOL_DOWN_LONG
}

object GestureConfig {
    private const val PREFS_NAME = "gesture_config"

    private val defaults = mapOf(
        GestureType.VOL_UP_SINGLE to MediaAction.SYSTEM_DEFAULT,
        GestureType.VOL_UP_DOUBLE to MediaAction.NEXT_TRACK,
        GestureType.VOL_UP_LONG to MediaAction.FAST_FORWARD,
        GestureType.VOL_DOWN_SINGLE to MediaAction.SYSTEM_DEFAULT,
        GestureType.VOL_DOWN_DOUBLE to MediaAction.PREV_TRACK,
        GestureType.VOL_DOWN_LONG to MediaAction.REWIND
    )

    fun getAction(context: Context, gesture: GestureType): MediaAction {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(gesture.name, defaults[gesture]!!.name)!!
        return MediaAction.valueOf(name)
    }

    fun setAction(context: Context, gesture: GestureType, action: MediaAction) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(gesture.name, action.name)
            .apply()
    }
}
