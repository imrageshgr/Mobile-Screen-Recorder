package com.example.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

class CallStateDetector(
    private val context: Context,
    private val onCallStarted: () -> Unit,
    private val onCallEnded: () -> Unit
) {
    companion object {
        private const val TAG = "CallStateDetector"
    }

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var isListening = false
    private var isInCall = false

    fun startListening() {
        if (isListening) return
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "READ_PHONE_STATE permission not granted; call monitoring disabled")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state)
                    }
                }
                telephonyManager.registerTelephonyCallback(
                    context.mainExecutor,
                    callback
                )
                telephonyCallback = callback
                isListening = true
                Log.d(TAG, "TelephonyCallback registered (API 31+)")
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
                isListening = true
                Log.d(TAG, "PhoneStateListener registered (API < 31)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening for call state: ${e.message}")
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!isInCall) {
                    isInCall = true
                    Log.d(TAG, "Call active or ringing -> triggering onCallStarted")
                    onCallStarted()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (isInCall) {
                    isInCall = false
                    Log.d(TAG, "Call ended or idle -> triggering onCallEnded")
                    onCallEnded()
                }
            }
        }
    }

    fun stopListening() {
        if (!isListening) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
                telephonyCallback = null
            } else {
                phoneStateListener?.let {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
                phoneStateListener = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering call listener: ${e.message}")
        } finally {
            isListening = false
            isInCall = false
        }
    }
}
