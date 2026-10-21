package com.example.system

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class DndManager(private val context: Context) {

    companion object {
        private const val TAG = "DndManager"
        private const val PREFS_NAME = "dnd_state_prefs"
        private const val KEY_PREV_FILTER = "prev_interruption_filter"
        private const val KEY_DND_APPLIED = "dnd_currently_applied"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val hasPolicyAccess: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.isNotificationPolicyAccessGranted
        } else {
            true
        }

    fun requestPolicyAccessIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent()
        }
    }

    fun applyDndIfEnabled(enabled: Boolean): Boolean {
        if (!enabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && hasPolicyAccess) {
            try {
                val currentFilter = notificationManager.currentInterruptionFilter
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                // Only store if not already active from a previous session
                if (!prefs.getBoolean(KEY_DND_APPLIED, false)) {
                    prefs.edit()
                        .putInt(KEY_PREV_FILTER, currentFilter)
                        .putBoolean(KEY_DND_APPLIED, true)
                        .apply()
                }

                // Switch to Priority / Alarms filter to suppress visual banners
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                Log.d(TAG, "Applied Do Not Disturb (saved previous filter: $currentFilter)")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply DND: ${e.message}")
            }
        }
        return false
    }

    fun restoreDnd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && hasPolicyAccess) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val wasApplied = prefs.getBoolean(KEY_DND_APPLIED, false)
                if (wasApplied) {
                    val prevFilter = prefs.getInt(
                        KEY_PREV_FILTER,
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    )
                    notificationManager.setInterruptionFilter(prevFilter)
                    prefs.edit()
                        .putBoolean(KEY_DND_APPLIED, false)
                        .apply()
                    Log.d(TAG, "Restored previous DND filter: $prevFilter")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore DND: ${e.message}")
            }
        }
    }
}
