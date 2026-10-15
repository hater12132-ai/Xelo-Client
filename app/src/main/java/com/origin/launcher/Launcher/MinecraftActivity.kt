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
import com.origin.launcher.LaunchLog
import dalvik.system.BaseDexClassLoader
import java.io.IOException

class MinecraftActivity : MainActivity() {

    /** Optional: preloader mod manager (android_main handoff is unused for 1.26 — symbols hidden). */
    external fun nativeOnLauncherLoaded(libPath: String)

    private lateinit var gameManager: GamePackageManager
    private var overlayManager: InbuiltOverlayManager? = null

    /**
     * Make System.loadLibrary("minecraftpe") succeed for GameActivity by putting
     * the MC package nativeLibraryDir on this ClassLoader's native path.
     * Required because android_main is NOT exported from libminecraftpe.so, so the
     * preloader dlsym handoff cannot work — GameActivity must load minecraftpe itself.
     */
    private fun injectNativeLibraryPath(dir: String) {
        try {
            val cl = classLoader as? BaseDexClassLoader ?: return
            val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(cl) ?: return

            val plClass = pathList.javaClass
            // nativeLibraryDirectories: List<File>
            try {
                val f = plClass.getDeclaredField("nativeLibraryDirectories")
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = f.get(pathList) as? MutableList<File>
                if (list != null) {
                    val d = File(dir)
                    if (!list.contains(d)) {
                        list.add(0, d)
                        LaunchLog.w("injectNativeLibraryPath: added to nativeLibraryDirectories: $dir")
                    }
                }
            } catch (t: Throwable) {
                LaunchLog.e("nativeLibraryDirectories inject failed", t)
            }

            // systemNativeLibraryDirectories on some versions
            try {
                val f = plClass.getDeclaredField("systemNativeLibraryDirectories")
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = f.get(pathList) as? MutableList<File>
                if (list != null) {
                    val d = File(dir)
                    if (!list.contains(d)) list.add(0, d)
                }
            } catch (_: Throwable) { }

            // Rebuild nativeLibraryPathElements if present (API 23+)
            try {
                val makePathElements = plClass.declaredMethods.firstOrNull {
                    it.name == "makePathElements" && it.parameterTypes.size in 1..3
                }
                val dirsField = plClass.getDeclaredField("nativeLibraryDirectories")
                dirsField.isAccessible = true
                val dirs = dirsField.get(pathList)
                if (makePathElements != null && dirs != null) {
                    makePathElements.isAccessible = true
                    val elements = when (makePathElements.parameterTypes.size) {
                        1 -> makePathElements.invoke(null, dirs)
                        2 -> makePathElements.invoke(null, dirs, ArrayList<IOException>())
                        else -> makePathElements.invoke(null, dirs, null, ArrayList<IOException>())
                    }
                    val elField = plClass.getDeclaredField("nativeLibraryPathElements")
                    elField.isAccessible = true
                    elField.set(pathList, elements)
                    LaunchLog.w("injectNativeLibraryPath: rebuilt nativeLibraryPathElements")
                }
            } catch (t: Throwable) {
                LaunchLog.e("nativeLibraryPathElements rebuild failed (may still work)", t)
            }
        } catch (t: Throwable) {
            LaunchLog.e("injectNativeLibraryPath failed", t)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LaunchLog.init(applicationContext)
        LaunchLog.w("MinecraftActivity.onCreate begin")
        var nativesOk = false
        try {
            val versionDir = intent.getStringExtra("MC_PATH")
            val versionCode = intent.getStringExtra("MINECRAFT_VERSION") ?: ""
            val versionDirName = intent.getStringExtra("MINECRAFT_VERSION_DIR") ?: ""
            val isInstalled = intent.getBooleanExtra("IS_INSTALLED", false)
            val isIsolated = FeatureSettings.getInstance().isVersionIsolationEnabled()
            LaunchLog.w("MC_PATH=$versionDir version=$versionCode dirName=$versionDirName installed=$isInstalled isolated=$isIsolated")

            val version = if (isIsolated && !versionDir.isNullOrEmpty()) {
                GameVersion(
                    versionDirName,
                    versionCode,
                    versionCode,
                    File(versionDir),
                    isInstalled,
                    MinecraftLauncher.MC_PACKAGE_NAME,
                    ""
                )
            } else if (versionCode.isNotEmpty()) {
                GameVersion(
                    versionDirName,
                    versionCode,
                    versionCode,
                    File(versionDir ?: ""),
                    true,
                    MinecraftLauncher.MC_PACKAGE_NAME,
                    ""
                )
            } else {
                null
            }

            gameManager = GamePackageManager.getInstance(applicationContext, version)

            // 1) MC C++ first (before any host .so that pulls NDK c++)
            LaunchLog.w("loading MC libc++_shared first")
            if (!gameManager.loadLibrary("c++_shared")) {
                throw RuntimeException("Failed to load MC libc++_shared.so")
            }
            gameManager.loadLibrary("HttpClient.Android")
            if (!gameManager.loadLibrary("fmod")) {
                throw RuntimeException("Failed to load libfmod.so")
            }
            gameManager.loadLibrary("MediaDecoders_Android")
            LaunchLog.w("loading minecraftpe")
            if (!gameManager.loadLibrary("minecraftpe")) {
                throw RuntimeException("Failed to load libminecraftpe.so")
            }
            LaunchLog.w("minecraftpe OK")

            // 2) Let GameActivity's System.loadLibrary("minecraftpe") find the file
            val peDir = File(gameManager.minecraftPeAbsolutePath).parentFile?.absolutePath
            if (peDir != null) {
                injectNativeLibraryPath(peDir)
            }

            // 3) Optional preloader (mods). Do NOT rely on it for android_main handoff.
            try {
                LaunchLog.w("loading preloader (optional, for mods)")
                System.loadLibrary("preloader")
                LaunchLog.w("preloader OK")
                try {
                    nativeOnLauncherLoaded(gameManager.minecraftPeAbsolutePath)
                    LaunchLog.w("nativeOnLauncherLoaded OK (mod init only)")
                } catch (t: Throwable) {
                    LaunchLog.e("nativeOnLauncherLoaded failed (non-fatal)", t)
                }
            } catch (e: Exception) {
                LaunchLog.e("preloader not loaded (non-fatal)", e)
            }

            nativesOk = true
        } catch (e: Exception) {
            LaunchLog.e("MinecraftActivity native load failed", e)
        }

        // ALWAYS call super.onCreate
        try {
            LaunchLog.w("calling super.onCreate (GameActivity → minecraftpe)")
            super.onCreate(savedInstanceState)
            LaunchLog.w("super.onCreate OK nativesOk=$nativesOk")
        } catch (e: Exception) {
            LaunchLog.e("super.onCreate failed", e)
            Toast.makeText(this, "Game init failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!nativesOk) {
            Toast.makeText(this, "Failed to load Minecraft native libraries", Toast.LENGTH_LONG).show()
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

        if (overlayManager == null) {
            startInbuiltModServices()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        overlayManager?.let { manager ->
            if (manager.handleKeyEvent(event.keyCode, event.action)) {
                return true
            }
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
        return try {
            gameManager.getAssets()
        } catch (e: Exception) {
            super.getAssets()
        }
    }

    override fun getFilesDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isVersionIsolationEnabled = FeatureSettings.getInstance().isVersionIsolationEnabled()

        return if (isVersionIsolationEnabled && !mcPath.isNullOrEmpty()) {
            val filesDir = File(mcPath, "files")
            if (!filesDir.exists()) {
                filesDir.mkdirs()
            }
            filesDir
        } else {
            super.getFilesDir()
        }
    }

    override fun tick() {
        super.tick()
        overlayManager?.tick()
    }

    override fun getDataDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isVersionIsolationEnabled = FeatureSettings.getInstance().isVersionIsolationEnabled()

        return if (isVersionIsolationEnabled && !mcPath.isNullOrEmpty()) {
            val dataDir = File(mcPath)
            if (!dataDir.exists()) {
                dataDir.mkdirs()
            }
            dataDir
        } else {
            super.getDataDir()
        }
    }

    override fun getExternalFilesDir(type: String?): File? {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isVersionIsolationEnabled = FeatureSettings.getInstance().isVersionIsolationEnabled()

        return if (isVersionIsolationEnabled && !mcPath.isNullOrEmpty()) {
            val externalDir = if (type != null) {
                File(mcPath, "games/com.mojang/$type")
            } else {
                File(mcPath, "games/com.mojang")
            }
            if (!externalDir.exists()) {
                externalDir.mkdirs()
            }
            externalDir
        } else {
            super.getExternalFilesDir(type)
        }
    }

    override fun getDatabasePath(name: String): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isVersionIsolationEnabled = FeatureSettings.getInstance().isVersionIsolationEnabled()

        return if (isVersionIsolationEnabled && !mcPath.isNullOrEmpty()) {
            val dbDir = File(mcPath, "databases")
            if (!dbDir.exists()) {
                dbDir.mkdirs()
            }
            File(dbDir, name)
        } else {
            super.getDatabasePath(name)
        }
    }

    override fun getCacheDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isVersionIsolationEnabled = FeatureSettings.getInstance().isVersionIsolationEnabled()

        return if (isVersionIsolationEnabled && !mcPath.isNullOrEmpty()) {
            val cacheDir = File(mcPath, "cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            cacheDir
        } else {
            super.getCacheDir()
        }
    }

    companion object {
        private const val TAG = "MinecraftActivity"
    }
}
