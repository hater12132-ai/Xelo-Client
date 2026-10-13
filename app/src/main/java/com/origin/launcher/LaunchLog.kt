package com.origin.launcher

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes launch diagnostics to a file Termux can read without READ_LOGS.
 * Path: /sdcard/Android/data/com.origin.launcher/files/xelo_launch.log
 * Fallback: /sdcard/Download/xelo_launch.log
 */
object LaunchLog {
    private const val TAG = "XeloLaunchLog"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var logFile: File? = null

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null)
                ?: File(Environment.getExternalStorageDirectory(), "Download")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "xelo_launch.log")
            // also mirror to Download for easy Termux access
            val dl = File(Environment.getExternalStorageDirectory(), "Download/xelo_launch.log")
            if (logFile?.absolutePath != dl.absolutePath) {
                // keep primary in app files; copy path noted in first line
            }
            w("==== Xelo launch log start ${Date()} ====")
            w("filesDir=${context.filesDir}")
            w("extFiles=${context.getExternalFilesDir(null)}")
            w("log=${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}")
        }
    }

    fun w(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        Log.i(TAG, msg)
        try {
            val f = logFile
            if (f != null) {
                FileWriter(f, true).use { it.append(line).append('\n') }
            }
            // always try Download mirror so Termux can cat without special paths
            val dl = File(Environment.getExternalStorageDirectory(), "Download/xelo_launch.log")
            FileWriter(dl, true).use { it.append(line).append('\n') }
        } catch (_: Exception) {
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg
        w("ERROR: $full")
        t?.stackTrace?.take(12)?.forEach { w("  at $it") }
    }
}
