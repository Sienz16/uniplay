package com.uniplay.core

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log

/**
 * Strict memory guardian for ultra-low RAM automotive head units (1GB RAM devices).
 * Enforces lean buffer allocations and triggers proactive compaction before the
 * Linux Low Memory Killer (LMK) kills the app.
 */
class MemoryGuard(private val context: Context) {

    companion object {
        private const val TAG = "UniplayMemoryGuard"
        private const val LOW_MEMORY_WARNING_THRESHOLD_MB = 45L
        private const val CRITICAL_MEMORY_THRESHOLD_MB = 25L
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()

    fun getAvailableRamMb(): Long {
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024)
    }

    fun isLowMemory(): Boolean {
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory || (memoryInfo.availMem / (1024 * 1024)) < LOW_MEMORY_WARNING_THRESHOLD_MB
    }

    fun checkAndCompactMemory(): Boolean {
        val availMb = getAvailableRamMb()
        if (availMb < LOW_MEMORY_WARNING_THRESHOLD_MB) {
            Log.w(TAG, "Low RAM warning: only ${availMb}MB free! Triggering gc and buffer trimming...")
            System.gc()
            System.runFinalization()
            return true
        }
        return false
    }

    fun dumpMemoryStats(): String {
        activityManager.getMemoryInfo(memoryInfo)
        val heapAllocated = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val heapFree = Debug.getNativeHeapFreeSize() / (1024 * 1024)
        val totalAvail = memoryInfo.availMem / (1024 * 1024)
        return "RAM Free: ${totalAvail}MB | Native Allocated: ${heapAllocated}MB | Native Free: ${heapFree}MB | LowMemory Flag: ${memoryInfo.lowMemory}"
    }
}
