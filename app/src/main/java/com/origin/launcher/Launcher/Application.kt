package com.origin.launcher.Launcher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import androidx.preference.PreferenceManager
import com.origin.launcher.BuildConfig
import com.origin.launcher.utils.FeatureSettings
import xcrash.ICrashCallback
import xcrash.XCrash
import java.io.File
import android.content.Intent
import com.origin.launcher.activity.CrashActivity
import com.origin.launcher.manager.LogcatOverlayManager

class LauncherApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        FeatureSettings.init(applicationContext)
        LogcatOverlayManager.init(this)
        preferences = PreferenceManager.getDefaultSharedPreferences(this)

        val callback: ICrashCallback = ICrashCallback { logPath, emergency ->
            try {
                val i = Intent(applicationContext, CrashActivity::class.java).apply {
                    putExtra("LOG_PATH", logPath)
                    putExtra("EMERGENCY", emergency)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                applicationContext.startActivity(i)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        XCrash.init(this, XCrash.InitParameters().apply {
            setAppVersion(BuildConfig.VERSION_NAME)
            setLogDir(File(Environment.getExternalStorageDirectory(), "games/xelo_client/crash_logs").absolutePath)
            setNativeCallback(callback)
            setJavaCallback(callback)
            setAnrCallback(callback)
            setJavaRethrow(false)
            setNativeRethrow(false)
            setAnrRethrow(false)
        })

        // Do NOT load xelo_init here — it pulls NDK libc++_shared and breaks
        // libminecraftpe.so on 1.26.x (__cxa_init_primary_exception).
        // nativeSetupRuntime is called from MinecraftLauncher AFTER MC libs load.
    }

    external fun nativeSetupRuntime(modsPath: String)

    companion object {
        @JvmStatic
        lateinit var context: Context
            private set

        @JvmStatic
        lateinit var preferences: SharedPreferences
            private set

        @Volatile
        private var nativeReady = false

        /**
         * Load xelo_init + setup runtime AFTER Minecraft's libc++_shared / libminecraftpe.
         */
        @JvmStatic
        fun ensureNativeRuntime(appContext: Context) {
            if (nativeReady) return
            synchronized(this) {
                if (nativeReady) return
                try {
                    System.loadLibrary("xelo_init")
                    val modsDir = File(appContext.cacheDir, "mods")
                    if (!modsDir.exists()) modsDir.mkdirs()
                    (appContext.applicationContext as LauncherApplication)
                        .nativeSetupRuntime(modsDir.absolutePath)
                    nativeReady = true
                    Log.d("LauncherApplication", "xelo_init ready, mods=${modsDir.absolutePath}")
                } catch (e: Exception) {
                    Log.e("LauncherApplication", "ensureNativeRuntime failed: ${e.message}", e)
                }
            }
        }
    }
}
