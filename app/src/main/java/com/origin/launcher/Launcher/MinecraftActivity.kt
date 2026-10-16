package com.origin.launcher.Launcher

import android.content.Intent
import android.content.res.AssetManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import com.mojang.minecraftpe.MainActivity
import com.origin.launcher.Launcher.inbuilt.overlay.InbuiltOverlayManager
import com.origin.launcher.versions.GameVersion
import com.origin.launcher.utils.FeatureSettings
import java.io.File
import com.origin.launcher.LaunchLog
import dalvik.system.BaseDexClassLoader
import java.lang.reflect.Array as ReflectArray

class MinecraftActivity : MainActivity() {

    external fun nativeOnLauncherLoaded(libPath: String): Boolean

    private lateinit var gameManager: GamePackageManager
    private var overlayManager: InbuiltOverlayManager? = null

    private fun injectNativeLibraryPath(dir: String) {
        try {
            val cl = classLoader as? BaseDexClassLoader ?: return
            val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(cl) ?: return
            val plClass = pathList.javaClass
            val d = File(dir)
            try {
                val f = plClass.getDeclaredField("nativeLibraryDirectories")
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = f.get(pathList) as? MutableList<File>
                if (list != null && !list.contains(d)) list.add(0, d)
            } catch (_: Throwable) {}
            try {
                val nleClass = Class.forName("dalvik.system.DexPathList\$NativeLibraryElement")
                val elField = plClass.getDeclaredField("nativeLibraryPathElements")
                elField.isAccessible = true
                val dirList = mutableListOf<File>()
                try {
                    val f = plClass.getDeclaredField("nativeLibraryDirectories")
                    f.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    (f.get(pathList) as? List<File>)?.let { dirList.addAll(it) }
                } catch (_: Throwable) {}
                if (!dirList.contains(d)) dirList.add(0, d)
                var built: Any? = null
                for (m in plClass.declaredMethods.filter { it.name == "makePathElements" }) {
                    try {
                        m.isAccessible = true
                        if (m.returnType.isArray && m.returnType.componentType == nleClass && m.parameterTypes.size == 1) {
                            built = m.invoke(null, dirList); break
                        }
                    } catch (_: Throwable) {}
                }
                if (built == null) {
                    val ctor = nleClass.declaredConstructors.firstOrNull {
                        it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == File::class.java
                    }
                    if (ctor != null) {
                        ctor.isAccessible = true
                        val arr = ReflectArray.newInstance(nleClass, dirList.size)
                        for (i in dirList.indices) {
                            try { ReflectArray.set(arr, i, ctor.newInstance(dirList[i])) } catch (_: Throwable) {}
                        }
                        built = arr
                    }
                }
                if (built != null) elField.set(pathList, built)
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}
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
            LaunchLog.w("MC_PATH=$versionDir version=$versionCode installed=$isInstalled isolated=$isIsolated")

            val version = if (isIsolated && !versionDir.isNullOrEmpty()) {
                GameVersion(versionDirName, versionCode, versionCode, File(versionDir), isInstalled, MinecraftLauncher.MC_PACKAGE_NAME, "")
            } else if (versionCode.isNotEmpty()) {
                GameVersion(versionDirName, versionCode, versionCode, File(versionDir ?: ""), true, MinecraftLauncher.MC_PACKAGE_NAME, "")
            } else null

            gameManager = GamePackageManager.getInstance(applicationContext, version)

            LaunchLog.w("loading MC natives")
            if (!gameManager.loadLibrary("c++_shared")) throw RuntimeException("c++_shared")
            gameManager.loadLibrary("HttpClient.Android")
            if (!gameManager.loadLibrary("fmod")) throw RuntimeException("fmod")
            gameManager.loadLibrary("MediaDecoders_Android")
            if (!gameManager.loadLibrary("minecraftpe")) throw RuntimeException("minecraftpe")
            LaunchLog.w("minecraftpe OK")

            File(gameManager.minecraftPeAbsolutePath).parentFile?.absolutePath?.let { injectNativeLibraryPath(it) }

            System.loadLibrary("preloader")
            LaunchLog.w("preloader OK")

            val pe = gameManager.minecraftPeAbsolutePath
            val bound = nativeOnLauncherLoaded(pe)
            LaunchLog.w("nativeOnLauncherLoaded bound=$bound")
            if (!bound) throw RuntimeException("android_main not bound")

            nativesOk = true
        } catch (t: Throwable) {
            loadError = t
            LaunchLog.e("native load failed", t)
        }

        // Keep screen on while debugging black screen
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Throwable) {}

        var superOk = false
        try {
            LaunchLog.w("calling super.onCreate")
            super.onCreate(savedInstanceState)
            superOk = true
            LaunchLog.w("super.onCreate OK")
        } catch (t: Throwable) {
            LaunchLog.e("super.onCreate failed", t)
            loadError = t
        }

        if (!superOk) {
            try { Toast.makeText(this, "Game init failed: ${loadError?.message}", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
            try { finish() } catch (_: Throwable) {}
            return
        }
        if (!nativesOk) {
            Toast.makeText(this, "Failed natives: ${loadError?.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        MinecraftActivityState.onCreated(this)
        LaunchLog.w("MinecraftActivity fully created — check /sdcard/Download/xelo_android_main.txt")
    }

    override fun onStart() {
        LaunchLog.w("onStart")
        startMainThreadWatchdog()
        super.onStart()
        LaunchLog.w("onStart returned")
    }

    // If the UI thread blocks (e.g. GameActivity waiting for android_main), dump where it is stuck.
    private fun startMainThreadWatchdog() {
        val mainThread = android.os.Looper.getMainLooper().thread
        Thread {
            try {
                for (sec in intArrayOf(3, 6)) {
                    Thread.sleep(3000)
                    val st = mainThread.stackTrace.joinToString("\n    ") { it.toString() }
                    LaunchLog.w("WATCHDOG t+${sec}s main thread state=${mainThread.state}\n    $st")
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; name = "xelo-watchdog" }.start()
    }

    override fun onResume() {
        LaunchLog.w("onResume")
        super.onResume()
        MinecraftActivityState.onResumed()
        if (overlayManager == null) {
            try {
                overlayManager = InbuiltOverlayManager(this)
                overlayManager?.showEnabledOverlays()
            } catch (t: Throwable) {
                LaunchLog.e("overlay failed", t)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        LaunchLog.w("onWindowFocusChanged focus=$hasFocus")
        super.onWindowFocusChanged(hasFocus)
    }

    private fun stopInbuiltModServices() {
        overlayManager?.hideAllOverlays()
        overlayManager = null
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent) }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        overlayManager?.let { if (it.handleKeyEvent(event.keyCode, event.action)) return true }
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean = super.dispatchGenericMotionEvent(ev)
    override fun onStop() { stopInbuiltModServices(); super.onStop() }
    override fun onDestroy() {
        LaunchLog.w("onDestroy")
        stopInbuiltModServices()
        MinecraftActivityState.onDestroyed()
        super.onDestroy()
    }
    override fun getAssets(): AssetManager = try { gameManager.getAssets() } catch (e: Exception) { super.getAssets() }
    override fun getFilesDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isol = FeatureSettings.getInstance().isVersionIsolationEnabled()
        return if (isol && !mcPath.isNullOrEmpty()) File(mcPath, "files").also { if (!it.exists()) it.mkdirs() } else super.getFilesDir()
    }
    override fun tick() { super.tick(); overlayManager?.tick() }
    override fun getDataDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isol = FeatureSettings.getInstance().isVersionIsolationEnabled()
        return if (isol && !mcPath.isNullOrEmpty()) File(mcPath).also { if (!it.exists()) it.mkdirs() } else super.getDataDir()
    }
    override fun getExternalFilesDir(type: String?): File? {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isol = FeatureSettings.getInstance().isVersionIsolationEnabled()
        return if (isol && !mcPath.isNullOrEmpty()) {
            val d = if (type != null) File(mcPath, "games/com.mojang/$type") else File(mcPath, "games/com.mojang")
            if (!d.exists()) d.mkdirs(); d
        } else super.getExternalFilesDir(type)
    }
    override fun getDatabasePath(name: String): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isol = FeatureSettings.getInstance().isVersionIsolationEnabled()
        return if (isol && !mcPath.isNullOrEmpty()) {
            File(File(mcPath, "databases").also { if (!it.exists()) it.mkdirs() }, name)
        } else super.getDatabasePath(name)
    }
    override fun getCacheDir(): File {
        val mcPath = intent.getStringExtra("MC_PATH")
        val isol = FeatureSettings.getInstance().isVersionIsolationEnabled()
        return if (isol && !mcPath.isNullOrEmpty()) File(mcPath, "cache").also { if (!it.exists()) it.mkdirs() } else super.getCacheDir()
    }
}
