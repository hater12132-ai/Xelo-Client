package com.origin.launcher

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LaunchLog {
    private const val TAG = "XeloLaunchLog"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var logFile: File? = null
    @Volatile private var downloadFile: File? = null

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null)
                ?: File(Environment.getExternalStorageDirectory(), "Download")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "xelo_launch.log")
            downloadFile = File(Environment.getExternalStorageDirectory(), "Download/xelo_launch.log")
            logFile?.writeText("")
            downloadFile?.writeText("")
            w("==== Xelo launch log start ${Date()} ====")
            w("filesDir=${context.filesDir}")
            w("extFiles=${context.getExternalFilesDir(null)}")
            w("log=${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}")
        }
    }

    fun w(msg: String) {
        val line = "${fmt.format(Date())} $msg\n"
        Log.i(TAG, msg)
        try {
            logFile?.let { f ->
                FileOutputStream(f, true).use { o ->
                    o.write(line.toByteArray())
                    o.flush()
                    o.fd.sync()
                }
            }
            downloadFile?.let { f ->
                FileOutputStream(f, true).use { o ->
                    o.write(line.toByteArray())
                    o.flush()
                    o.fd.sync()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg
        w("ERROR: $full")
        t?.stackTrace?.take(12)?.forEach { w("  at $it") }
    }
}
