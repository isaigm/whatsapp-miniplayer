package com.example.kat.utils

object AppConstants {
    const val KEY_CURRENT_FILE_PATH = "current_file_path"
    const val KEY_CURRENT_PROGRESS = "current_progress"
    const val KEY_IS_PLAYING = "is_playing"
    const val KEY_MINI_PLAYER_VISIBILITY = "mini_player_visibility"
    const val KEY_CURRENTLY_PLAYING_POSITION = "currently_playing_position"
    const val KEY_CURRENT_ORDER = "asc_order" // Consider renaming to KEY_SORT_ORDER

    const val NO_POSITION = -1

    const val REQUEST_CODE_POST_NOTIFICATIONS = 101 // Still used if permissions are requested separately
    const val REQUEST_CODE_READ_STORAGE = 1
    const val REQUEST_CODE_MANAGE_STORAGE = 2 // For ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
}

enum class SortOrder(val value: Int) {
    ASC(1), // Oldest first
    DESC(2); // Newest first

    companion object {
        fun fromValue(value: Int) = entries.find { it.value == value } ?: DESC // Default to DESC
    }
}