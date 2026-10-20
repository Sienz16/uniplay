package com.andrerinas.openheadunit.utils

import android.util.Log

object AppLog {
    private const val TAG = "UniplayAAP"
    const val LOG_VERBOSE = false

    @JvmStatic
    fun d(msg: String, vararg args: Any?) {
        try {
            val formatted = if (args.isNotEmpty()) String.format(msg, *args) else msg
            Log.d(TAG, formatted)
        } catch (_: Exception) {
            Log.d(TAG, msg)
        }
    }

    @JvmStatic
    fun i(msg: String, vararg args: Any?) {
        try {
            val formatted = if (args.isNotEmpty()) String.format(msg, *args) else msg
            Log.i(TAG, formatted)
        } catch (_: Exception) {
            Log.i(TAG, msg)
        }
    }

    @JvmStatic
    fun w(msg: String, vararg args: Any?) {
        try {
            val formatted = if (args.isNotEmpty()) String.format(msg, *args) else msg
            Log.w(TAG, formatted)
        } catch (_: Exception) {
            Log.w(TAG, msg)
        }
    }

    @JvmStatic
    fun e(msg: String) {
        Log.e(TAG, msg)
    }

    @JvmStatic
    fun e(msg: String, tr: Throwable?) {
        Log.e(TAG, msg, tr)
    }

    @JvmStatic
    fun e(tr: Throwable) {
        Log.e(TAG, "AAP Error", tr)
    }
}
