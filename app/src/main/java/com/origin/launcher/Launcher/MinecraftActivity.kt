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
import java.lang.reflect.Array as ReflectArray
import com.origin.launcher.LaunchLog
import dalvik.system.BaseDexClassLoader

class MinecraftActivity : MainActivity() {

    private lateinit var gameManager: GamePackageManager
    private var overlayManager: InbuiltOverlayManager? = null

    /**
     * Android 10–16: System.loadLibrary uses DexPathList.nativeLibraryPathElements
     * which is NativeLibraryElement[], NOT Element[]. Build that array by hand so
     * findLibrary("minecraftpe") resolves to the MC package lib dir.
     */
    private fun injectNativeLibraryPath(dir: String) {
        try {
            val cl = classLoader as? BaseDexClassLoader ?: run {
                LaunchLog.w("inject: classLoader is not BaseDexClassLoader")
                return
            }
            val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(cl) ?: return
            val plClass = pathList.javaClass
            val d = File(dir)

            // 1) nativeLibraryDirectories: List<File>
            try {
                val f = plClass.getDeclaredField("nativeLibraryDirectories")
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val list = f.get(pathList) as? MutableList<File>
                if (list != null && !list.contains(d)) {
                    list.add(0, d)
                    LaunchLog.w("inject: nativeLibraryDirectories += $dir")
                }
            } catch (t: Throwable) {
                LaunchLog.e("inject dirs failed", t)
            }

            // 2) nativeLibraryPathElements: NativeLibraryElement[]
            try {
                val nleClass = Class.forName("dalvik.system.DexPathList\$NativeLibraryElement")
                val elField = plClass.getDeclaredField("nativeLibraryPathElements")
                elField.isAccessible = true
                val existing = elField.get(pathList)

                // Collect dirs to put in elements
                val dirList = mutableListOf<File>()
                try {
                    val f = plClass.getDeclaredField("nativeLibraryDirectories")
                    f.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val list = f.get(pathList) as? List<File>
                    if (list != null) dirList.addAll(list)
                } catch (_: Throwable) { }
                if (!dirList.contains(d)) dirList.add(0, d)

                // Prefer makePathElements(List) that returns NativeLibraryElement[]
                var built: Any? = null
                for (m in plClass.declaredMethods.filter { it.name == "makePathElements" }) {
                    try {
                        m.isAccessible = true
                        val ret = m.returnType
                        if (ret.isArray && ret.componentType == nleClass) {
                            built = when (m.parameterTypes.size) {
                                1 -> m.invoke(null, dirList)
                                else -> continue
                            }
                            LaunchLog.w("inject: makePathElements(NativeLibraryElement) OK")
                            break
                        }
                    } catch (_: Throwable) { }
                }

                // Fallback: construct NativeLibraryElement(File) per dir
                if (built == null) {
                    val ctor = nleClass.declaredConstructors.firstOrNull {
                        it.parameterTypes.size == 1 && it.parameterTypes[0] == File::class.java
                    } ?: nleClass.declaredConstructors.firstOrNull {
                        it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == File::class.java
                    }
                    if (ctor == null) {
                        LaunchLog.w("inject: no NativeLibraryElement constructor")
                    } else {
                        ctor.isAccessible = true
                        val arr = ReflectArray.newInstance(nleClass, dirList.size)
                        for (i in dirList.indices) {
                            val inst = try {
                                ctor.newInstance(dirList[i])
                            } catch (_: Throwable) {
                                // some versions: (File, boolean)
                                try {
                                    val c2 = nleClass.getDeclaredConstructor(File::class.java, Boolean::class.javaPrimitiveType)
                                    c2.isAccessible = true
                                    c2.newInstance(dirList[i], true)
                                } catch (t2: Throwable) {
                                    LaunchLog.e("NLE ctor failed for ${dirList[i]}", t2)
                                    null
                                }
                            }
                            if (inst != null) ReflectArray.set(arr, i, inst)
                        }
                        built = arr
                        LaunchLog.w("inject: built NativeLibraryElement[${dirList.size}] manually")
                    }
                }

                if (built != null) {
                    elField.set(pathList, built)
                    LaunchLog.w("inject: nativeLibraryPathElements set OK")
                }

                // Verify findLibrary
                try {
                    val findLib = BaseDexClassLoader::class.java.getDeclaredMethod("findLibrary", String::class.java)
                    findLib.isAccessible = true
                    val found = findLib.invoke(cl, "minecraftpe") as? String
                    LaunchLog.w("inject: findLibrary(minecraftpe)=$found")
                } catch (t: Throwable) {
                    LaunchLog.e("findLibrary check failed", t)
                }
            } catch (t: Throwable) {
                LaunchLog.e("inject NativeLibraryElement failed", t)
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

            LaunchLog.w("loading MC natives (absolute path)")
            if (!gameManager.loadLibrary("c++_shared")) throw RuntimeException("c++_shared")
            gameManager.loadLibrary("HttpClient.Android")
            if (!gameManager.loadLibrary("fmod")) throw RuntimeException("fmod")
            gameManager.loadLibrary("MediaDecoders_Android")
            if (!gameManager.loadLibrary("minecraftpe")) throw RuntimeException("minecraftpe")
            LaunchLog.w("minecraftpe OK via System.load")

            val peDir = File(gameManager.minecraftPeAbsolutePath).parentFile?.absolutePath
            if (peDir != null) injectNativeLibraryPath(peDir)

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

        var superOk = false
        try {
            LaunchLog.w("calling super.onCreate (lib_name=minecraftpe)")
            super.onCreate(savedInstanceState)
            superOk = true
            LaunchLog.w("super.onCreate OK nativesOk=$nativesOk")
        } catch (t: Throwable) {
            LaunchLog.e("super.onCreate failed", t)
            loadError = t
        }

        if (!superOk) {
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
