package com.example.kat.utils


import java.util.Locale

object TimeUtils {
    fun formatTime(millis: Int): String {
        if (millis < 0) return "0:00" // Handle invalid duration
        val totalSeconds = millis / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    fun formatTime(millis: Long): String {
        return formatTime(millis.toInt())
    }
}