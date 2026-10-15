package com.origin.launcher.Launcher

import android.content.Intent
import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import com.mojang.minecraftpe.MainActivity
import com.origin.launcher.Launcher.inbuilt.overlay.InbuiltOverlayManager
import com.origin.launcher.versions.GameVersion
import com.origin.launcher.utils.FeatureSettings
import java.io.File
import java.io.IOException
import com.origin.launcher.LaunchLog
import dalvik.system.BaseDexClassLoader

class MinecraftActivity : MainActivity() {

    private lateinit var gameManager: GamePackageManager
    private var overlayManager: InbuiltOverlayManager? = null

    /**
     * Put MC package nativeLibraryDir on ClassLoader search path so any
     * remaining System.loadLibrary("minecraftpe") can resolve the file.
     */
    private fun injectNativeLibraryPath(dir: String) {
        try {
            val cl = classLoader as? BaseDexClassLoader ?: return
            val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(cl) ?: return
            val plClass = pathList.javaClass

            try {
                val f = plClass.getDeclaredField("nativeLibraryDirectories")
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = f.get(pathList) as? MutableList<File>
                if (list != null) {
                    val d = File(dir)
                    if (!list.contains(d)) {
                        list.add(0, d)
                        LaunchLog.w("inject: nativeLibraryDirectories += $dir")
                    }
                }
            } catch (t: Throwable) {
                LaunchLog.e("inject nativeLibraryDirectories failed", t)
            }

            try {
                val dirsField = plClass.getDeclaredField("nativeLibraryDirectories")
                dirsField.isAccessible = true
                val dirs = dirsField.get(pathList)
                val makePathElements = plClass.declaredMethods.firstOrNull {
                    it.name == "makePathElements"
                }
                if (makePathElements != null && dirs != null) {
                    makePathElements.isAccessible = true
                    val elements = try {
                        when (makePathElements.parameterTypes.size) {
                            1 -> makePathElements.invoke(null, dirs)
                            2 -> makePathElements.invoke(null, dirs, ArrayList<IOException>())
                            else -> makePathElements.invoke(null, dirs, null, ArrayList<IOException>())
                        }
                    } catch (_: Throwable) {
                        null
                    }
                    if (elements != null) {
                        val elField = plClass.getDeclaredField("nativeLibraryPathElements")
                        elField.isAccessible = true
                        elField.set(pathList, elements)
                        LaunchLog.w("inject: rebuilt nativeLibraryPathElements")
                    }
                }
            } catch (t: Throwable) {
                LaunchLog.e("inject pathElements failed", t)
            }
        } catch (t: Throwable) {
            LaunchLog.e("injectNativeLibraryPath failed", t)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LaunchLog.init(applicationContext)
        LaunchLog.w("MinecraftActivity.onCreate begin")

        var nativesOk = false
        var loadError: Throwable? = null

        try {
            val versionDir = intent.getStringExtra("MC_PATH")
            val versionCode = intent.getStringExtra("MINECRAFT_VERSION") ?: ""
            val versionDirName = intent.getStringExtra("MINECRAFT_VERSION_DIR") ?: ""
            val isInstalled = intent.getBooleanExtra("IS_INSTALLED", false)
            val isIsolated = FeatureSettings.getInstance().isVersionIsolationEnabled()
            LaunchLog.w("MC_PATH=$versionDir version=$versionCode dirName=$versionDirName installed=$isInstalled isolated=$isIsolated")

            val version = if (isIsolated && !versionDir.isNullOrEmpty()) {
                GameVersion(
                    versionDirName, versionCode, versionCode,
                    File(versionDir), isInstalled,
                    MinecraftLauncher.MC_PACKAGE_NAME, ""
                )
            } else if (versionCode.isNotEmpty()) {
                GameVersion(
                    versionDirName, versionCode, versionCode,
                    File(versionDir ?: ""), true,
                    MinecraftLauncher.MC_PACKAGE_NAME, ""
                )
            } else null

            gameManager = GamePackageManager.getInstance(applicationContext, version)

            // Absolute System.load — do NOT rely on System.loadLibrary for MC
            LaunchLog.w("loading MC natives (absolute path)")
            if (!gameManager.loadLibrary("c++_shared")) throw RuntimeException("c++_shared")
            gameManager.loadLibrary("HttpClient.Android")
            if (!gameManager.loadLibrary("fmod")) throw RuntimeException("fmod")
            gameManager.loadLibrary("MediaDecoders_Android")
            if (!gameManager.loadLibrary("minecraftpe")) throw RuntimeException("minecraftpe")
            LaunchLog.w("minecraftpe OK via System.load")

            val peDir = File(gameManager.minecraftPeAbsolutePath).parentFile?.absolutePath
            if (peDir != null) injectNativeLibraryPath(peDir)

            // Optional preloader for mods only
            try {
                System.loadLibrary("preloader")
                LaunchLog.w("preloader OK")
            } catch (t: Throwable) {
                LaunchLog.e("preloader skipped", t)
            }

            nativesOk = true
        } catch (t: Throwable) {
            loadError = t
            LaunchLog.e("MinecraftActivity native load failed", t)
        }

        // CRITICAL: must call super.onCreate or Android throws SuperNotCalledException.
        // Catch Throwable (UnsatisfiedLinkError is an Error, not Exception).
        var superOk = false
        try {
            LaunchLog.w("calling super.onCreate")
            super.onCreate(savedInstanceState)
            superOk = true
            LaunchLog.w("super.onCreate OK nativesOk=$nativesOk")
        } catch (t: Throwable) {
            LaunchLog.e("super.onCreate failed", t)
            loadError = t
        }

        if (!superOk) {
            // Last-ditch: Android still requires Activity.onCreate to have been called.
            // If GameActivity threw before reaching Activity.super, we cannot recover —
            // finish and return; process may still report SuperNotCalledException.
            try {
                Toast.makeText(this, "Game init failed: ${loadError?.message}", Toast.LENGTH_LONG).show()
            } catch (_: Throwable) { }
            try { finish() } catch (_: Throwable) { }
            return
        }

        if (!nativesOk) {
            Toast.makeText(this, "Failed to load Minecraft libraries: ${loadError?.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        MinecraftActivityState.onCreated(this)
        LaunchLog.w("MinecraftActivity fully created")
    }

    private fun startInbuiltModServices() {
        overlayManager = InbuiltOverlayManager(this)
        overlayManager?.showEnabledOverlays()
    }

    private fun stopInbuiltModServices() {
        overlayManager?.hideAllOverlays()
        overlayManager = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        MinecraftActivityState.onResumed()
        if (overlayManager == null) startInbuiltModServices()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        overlayManager?.let { manager ->
            if (manager.handleKeyEvent(event.keyCode, event.action)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
    }

    override fun onStop() {
        stopInbuiltModServices()
        super.onStop()
    }

    override fun onDestroy() {
        stopInbuiltModServices()
        MinecraftActivityState.onDestroyed()
        super.onDestroy()
    }

    override fun getAssets(): AssetManager {
        return try { gameManager.getAssets() } catch (e: Exception) { super.getAssets() }
    }

    override fun getFilesDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isol = FeatureSettings.getInstance().isVersionIsolationEnabled()
        return if (isol && !mcPath.isNullOrEmpty()) {
            File(mcPath, "files").also { if (!it.exists()) it.mkdirs() }
        } else super.getFilesDir()
    }

    override fun tick() {
        super.tick()
        overlayManager?.tick()
    }

    override fun getDataDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isol = FeatureSettings.getInstance().isVersionIsolationEnabled()
        return if (isol && !mcPath.isNullOrEmpty()) {
            File(mcPath).also { if (!it.exists()) it.mkdirs() }
        } else super.getDataDir()
    }

    override fun getExternalFilesDir(type: String?): File? {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isol = FeatureSettings.getInstance().isVersionIsolationEnabled()
        return if (isol && !mcPath.isNullOrEmpty()) {
            val externalDir = if (type != null) File(mcPath, "games/com.mojang/$type")
            else File(mcPath, "games/com.mojang")
            if (!externalDir.exists()) externalDir.mkdirs()
            externalDir
        } else super.getExternalFilesDir(type)
    }

    override fun getDatabasePath(name: String): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isol = FeatureSettings.getInstance().isVersionIsolationEnabled()
        return if (isol && !mcPath.isNullOrEmpty()) {
            val dbDir = File(mcPath, "databases").also { if (!it.exists()) it.mkdirs() }
            File(dbDir, name)
        } else super.getDatabasePath(name)
    }

    override fun getCacheDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isol = FeatureSettings.getInstance().isVersionIsolationEnabled()
        return if (isol && !mcPath.isNullOrEmpty()) {
            File(mcPath, "cache").also { if (!it.exists()) it.mkdirs() }
        } else super.getCacheDir()
    }

    companion object {
        private const val TAG = "MinecraftActivity"
    }
}
