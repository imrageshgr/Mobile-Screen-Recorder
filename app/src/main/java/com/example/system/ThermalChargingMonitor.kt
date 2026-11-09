package com.example.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ThermalStatusInfo(
    val temperatureCelsius: Float = 0f,
    val isCharging: Boolean = false,
    val chargeSource: String = "Not Charging",
    val batteryLevel: Int = 100,
    val thermalLevel: Int = 0, // PowerManager.THERMAL_STATUS_...
    val thermalDescription: String = "Normal",
    val isOverheating: Boolean = false,
    val isCriticalHeat: Boolean = false,
    val warningMessage: String? = null
)

/**
 * ThermalChargingMonitor
 * Uses REAL Android OS hardware telemetry:
 * 1. BroadcastReceiver for ACTION_BATTERY_CHANGED reading actual battery temperature and charge status.
 * 2. PowerManager.addThermalStatusListener (API 29+) reading real SoC/hardware thermal throttling states.
 * No synthetic or simulated values.
 */
class ThermalChargingMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ThermalChargingMonitor"
        const val TEMP_THRESHOLD_WARMING = 39.0f // 39°C: Device warming up while gaming
        const val TEMP_THRESHOLD_SEVERE = 43.0f  // 43°C: Critical overheating threshold
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val _statusFlow = MutableStateFlow(ThermalStatusInfo())
    val statusFlow: StateFlow<ThermalStatusInfo> = _statusFlow.asStateFlow()

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var isRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                processBatteryIntent(intent)
            }
        }
    }

    fun startMonitoring() {
        if (isRegistered) return

        try {
            // 1. Register for real battery telemetry
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val stickyIntent = context.registerReceiver(batteryReceiver, filter)
            if (stickyIntent != null) {
                processBatteryIntent(stickyIntent)
            }

            // 2. Register for real OS thermal status listener (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    processThermalStatus(status)
                }
                thermalListener = listener
                powerManager.addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
                processThermalStatus(powerManager.currentThermalStatus)
            }

            isRegistered = true
            Log.d(TAG, "Hardware thermal & charging telemetry monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start thermal monitoring: ${e.message}", e)
        }
    }

    fun stopMonitoring() {
        if (!isRegistered) return

        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
            try {
                powerManager.removeThermalStatusListener(thermalListener!!)
            } catch (_: Exception) {}
            thermalListener = null
        }

        isRegistered = false
        Log.d(TAG, "Hardware thermal & charging telemetry monitoring stopped")
    }

    private fun processBatteryIntent(intent: Intent) {
        val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempCelsius = if (rawTemp > 0) rawTemp / 10.0f else 0f

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = plugged != 0
        val chargeSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC Fast Charger"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB Port"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Charger"
            else -> "Battery Power"
        }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100

        val current = _statusFlow.value
        val isHot = tempCelsius >= TEMP_THRESHOLD_WARMING
        val isCritical = tempCelsius >= TEMP_THRESHOLD_SEVERE || current.thermalLevel >= 4 // PowerManager.THERMAL_STATUS_CRITICAL

        val warningMsg = when {
            isCritical -> "Critical hardware temperature (${String.format("%.1f", tempCelsius)}°C). Recording should pause or stop to protect battery."
            isHot && isCharging -> "High gaming temperature (${String.format("%.1f", tempCelsius)}°C) while charging via $chargeSource. Frame rate may be adapted."
            isHot -> "Device temperature rising (${String.format("%.1f", tempCelsius)}°C)."
            else -> null
        }

        _statusFlow.value = current.copy(
            temperatureCelsius = tempCelsius,
            isCharging = isCharging,
            chargeSource = chargeSource,
            batteryLevel = batteryPct,
            isOverheating = isHot,
            isCriticalHeat = isCritical,
            warningMessage = warningMsg
        )
    }

    private fun processThermalStatus(status: Int) {
        val (desc, isCritical) = when (status) {
            0 -> "Normal" to false // THERMAL_STATUS_NONE
            1 -> "Light Warmth" to false // THERMAL_STATUS_LIGHT
            2 -> "Moderate" to false // THERMAL_STATUS_MODERATE
            3 -> "Severe Throttling" to true // THERMAL_STATUS_SEVERE
            4 -> "Critical Heat" to true // THERMAL_STATUS_CRITICAL
            5 -> "Emergency" to true // THERMAL_STATUS_EMERGENCY
            6 -> "Shutdown Imminent" to true // THERMAL_STATUS_SHUTDOWN
            else -> "Normal" to false
        }

        val current = _statusFlow.value
        val isOverheat = isCritical || current.temperatureCelsius >= TEMP_THRESHOLD_WARMING
        val isCrit = isCritical || current.temperatureCelsius >= TEMP_THRESHOLD_SEVERE

        _statusFlow.value = current.copy(
            thermalLevel = status,
            thermalDescription = desc,
            isOverheating = isOverheat,
            isCriticalHeat = isCrit
        )
        Log.d(TAG, "Hardware thermal status changed: $desc ($status)")
    }
}
