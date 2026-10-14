package com.origin.launcher.Launcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import com.origin.launcher.versions.GameVersion
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import com.origin.launcher.LaunchLog

class GamePackageManager private constructor(private val context: Context, private val version: GameVersion?) {

    private val packageContext: Context
    private val assetManager: AssetManager
    private val nativeLibDir: String
    private val applicationInfo: ApplicationInfo
    // Note: packageContext/applicationInfo assigned in init for isolated vs installed paths

    private val knownPackages = arrayOf(
        "com.mojang.minecraftpe",
        "com.mojang.minecraftpe.beta",
        "com.mojang.minecraftpe.preview"
    )

    private val requiredLibs = arrayOf(
        "libc++_shared.so",
        "libfmod.so",
        "libMediaDecoders_Android.so",
        "libmaesdk.so",
        "libminecraftpe.so"
    )

    private val systemLoadedLibs = arrayOf(
        "libpairipcore.so",
        "libxelo.so",
        "libmtbinloader2.so"
    )

    init {
        // Isolated imported APK (Levi-style): do NOT require Play Store Minecraft installed
        if (version != null && !version.isInstalled) {
            applicationInfo = MinecraftLauncher(context).createFakeApplicationInfo(version, MinecraftLauncher.MC_PACKAGE_NAME)
            nativeLibDir = applicationInfo.nativeLibraryDir
            // Use host app context as package context; assets/libs come from the imported APK
            packageContext = context
            Log.i(TAG, "Isolated version mode: ${version.versionCode} dir=${version.versionDir}")
        } else {
            val packageName = detectGamePackage()
                ?: throw IllegalStateException("Minecraft not found — install MCPE or import an APK version")
            packageContext = context.createPackageContext(
                packageName,
                Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
            )
            applicationInfo = packageContext.applicationInfo
            nativeLibDir = resolveNativeLibDir()
        }

        extractLibraries()
        assetManager = createAssetManager()
        setupSecurityProvider()
    }

    private fun detectGamePackage(): String? {
        return knownPackages.firstOrNull { isPackageInstalled(it) }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun resolveNativeLibDir(): String {
        val appInfo = packageContext.applicationInfo
        // Prefer the system-extracted package lib dir — same place the Play Store app loads from.
        // Cache copies of libc++_shared often miss symbols MC 1.26 needs (__cxa_init_primary_exception).
        val systemDir = File(appInfo.nativeLibraryDir ?: "")
        val pe = File(systemDir, "libminecraftpe.so")
        val cxx = File(systemDir, "libc++_shared.so")
        if (pe.exists() && pe.length() > 1_000_000L) {
            LaunchLog.w("Using package nativeLibraryDir=${systemDir.absolutePath} pe=${pe.length()} cxx=${cxx.length()}")
            return systemDir.absolutePath
        }
        val cacheLibDir = File(context.cacheDir, "lib/${getDeviceAbi()}")
        cacheLibDir.mkdirs()
        LaunchLog.w("Package nativeLibraryDir missing minecraftpe — using cache ${cacheLibDir.absolutePath}")
        return cacheLibDir.absolutePath
    }

    private fun getDeviceAbi(): String {
        // arm64-only client (1.26.52.3 target)
        return "arm64-v8a"
    }

    private fun extractLibraries() {
        val outputDir = File(nativeLibDir)
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        if (version != null && !version.isInstalled) {
            val apkPaths = mutableListOf<String>()
            val baseApk = File(applicationInfo.sourceDir)
            if (baseApk.exists()) {
                apkPaths.add(applicationInfo.sourceDir)
            } else {
                Log.w(TAG, "Base APK not found: ${applicationInfo.sourceDir}")
            }
            applicationInfo.splitSourceDirs?.forEach {
                if (File(it).exists()) {
                    apkPaths.add(it)
                } else {
                    Log.w(TAG, "Split APK not found: $it")
                }
            }
            apkPaths.forEach { extractFromApk(it, outputDir, getDeviceAbi()) }
            if (requiredLibs.any { !File(outputDir, it).exists() }) {
                Log.w(TAG, "Primary ABI ${getDeviceAbi()} libraries missing, trying fallback ABIs")
                val fallbackAbis = listOf("arm64-v8a") // arm64-only client
                fallbackAbis.filter { it != getDeviceAbi() }.forEach { abi ->
                    apkPaths.forEach { extractFromApk(it, outputDir, abi) }
                }
            }
        } else {
            val appInfo = packageContext.applicationInfo
            val systemDir = File(appInfo.nativeLibraryDir ?: "")
            // If we're already pointing at the real package lib dir, do not re-copy/extract
            if (outputDir.absolutePath == systemDir.absolutePath &&
                File(outputDir, "libminecraftpe.so").exists()) {
                LaunchLog.w("extractLibraries: already using package dir, skip copy")
                verifyLibraries(outputDir)
                return
            }
            // Fresh cache extract — delete stale c++_shared that may lack 1.26 symbols
            if (outputDir.exists() && outputDir.absolutePath.contains("/cache/lib/")) {
                outputDir.listFiles()?.forEach { it.delete() }
                LaunchLog.w("Cleared stale cache libs in ${outputDir.absolutePath}")
            }
            if (systemDir.exists()) {
                copyFromNativeDir(systemDir.absolutePath, outputDir)
            }
            val apkPaths = mutableListOf<String>()
            appInfo.sourceDir?.let { apkPaths.add(it) }
            appInfo.splitPublicSourceDirs?.let { apkPaths.addAll(it) }
            appInfo.splitSourceDirs?.forEach { apkPaths.add(it) }
            apkPaths.distinct().forEach { extractFromApk(it, outputDir, getDeviceAbi()) }
        }
        verifyLibraries(outputDir)
        LaunchLog.w("extract done: cxx=${File(outputDir,"libc++_shared.so").length()} pe=${File(outputDir,"libminecraftpe.so").length()}")
    }

    private fun copyFromNativeDir(sourceDir: String, destDir: File) {
        val source = File(sourceDir)
        if (!source.exists()) {
            Log.w(TAG, "Source native library directory does not exist: $sourceDir")
            return
        }

        requiredLibs.forEach { lib ->
            val srcFile = File(source, lib)
            val dstFile = File(destDir, lib)
            if (srcFile.exists() && srcFile.length() > 0) {
                try {
                    srcFile.copyTo(dstFile, overwrite = true)
                    dstFile.setReadable(true)
                    dstFile.setExecutable(true)
                    logFileOperation("Copied", lib)
                } catch (e: Exception) {
                    logFileOperation("Failed to copy", lib, e = e)
                }
            } else {
                Log.w(TAG, "Library $lib not found in $sourceDir")
            }
        }
    }

    private fun extractFromApk(apkPath: String, outputDir: File, abi: String) {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            Log.w(TAG, "APK file does not exist: $apkPath")
            return
        }
        if (!apkPath.contains("arm") && !apkPath.contains("x86") && !apkPath.contains("base.apk")) {
            return
        }

        try {
            ZipFile(apkPath).use { zip ->
                val abiPath = "lib/$abi"
                requiredLibs.forEach { lib ->
                    val entry = zip.getEntry("$abiPath/$lib")
                    if (entry == null) {
                        return@forEach
                    }
                    val output = File(outputDir, lib)
                    if (output.exists() && output.length() > 0) {
                        return@forEach
                    }
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { out ->
                            input.copyTo(out)
                        }
                    }
                    output.setReadable(true)
                    output.setExecutable(true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract libraries from $apkPath: ${e.message}")
        }
    }

    private fun verifyLibraries(dir: File) {
        val missing = requiredLibs.filterNot {
            File(dir, it).let { f -> f.exists() && f.length() > 0 }
        }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Missing libraries in $dir: ${missing.joinToString()}")
        } else {
            Log.i(TAG, "All libraries verified in $dir")
        }
    }

    private fun logFileOperation(action: String, lib: String, extra: String? = null, e: Exception? = null) {
        val message = buildString {
            append("$action $lib")
            if (extra != null) append(" $extra")
            if (e != null) append(": ${e.message}")
        }
        if (e != null) Log.w(TAG, message) else Log.d(TAG, message)
    }

    private fun createAssetManager(): AssetManager {
        val assets = AssetManager::class.java.newInstance()
        val addAssetPathMethod = AssetManager::class.java.getMethod("addAssetPath", String::class.java)

        val paths = mutableListOf<String>()

        if (version != null && !version.isInstalled) {
            val baseApk = File(applicationInfo.sourceDir)
            if (baseApk.exists()) {
                paths.add(applicationInfo.sourceDir)
            } else {
                Log.w(TAG, "Base APK for assets not found: ${applicationInfo.sourceDir}")
            }
            applicationInfo.splitSourceDirs?.forEach {
                if (File(it).exists()) {
                    paths.add(it)
                    Log.d(TAG, "Adding split APK for assets: $it")
                } else {
                    Log.w(TAG, "Split APK for assets not found: $it")
                }
            }
        } else {
            paths.add(packageContext.packageResourcePath)
            val splitPath = packageContext.packageResourcePath.replace("base.apk", "split_install_pack.apk")
            if (File(splitPath).exists()) paths.add(splitPath)
        }

        paths.add(context.packageResourcePath)

        paths.forEach { path ->
            try {
                addAssetPathMethod.invoke(assets, path)
                Log.d(TAG, "Added asset path: $path")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add asset path $path: ${e.message}")
            }
        }
        return assets
    }

    private fun setupSecurityProvider() {
        Log.d(TAG, "Setting up security provider...")
        try {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        } catch (e: Exception) {
            Log.w(TAG, "Conscrypt init failed: ${e.message}")
        }
    }

    // Libs shipped inside the Xelo APK (jniLibs), not the Minecraft package
    private val hostShippedLibs = setOf("xelo", "mtbinloader2", "xelo_init", "preloader", "pairipcore")

    /** Absolute path to libminecraftpe.so (for preloader nativeOnLauncherLoaded). */
    val minecraftPeAbsolutePath: String
        get() = File(nativeLibDir, "libminecraftpe.so").absolutePath

    fun loadLibrary(name: String): Boolean {

        val plain = name.removePrefix("lib").removeSuffix(".so")
        val mcFile = File(nativeLibDir, "lib$plain.so")
        val hostLibDir = context.applicationInfo.nativeLibraryDir ?: ""
        val hostFile = File(hostLibDir, "lib$plain.so")

        if (plain in hostShippedLibs) {
            // Log what the host app actually has extracted
            try {
                File(hostLibDir).listFiles()?.sortedBy { it.name }?.forEach {
                    LaunchLog.w("  host-lib-dir: ${it.name} (${it.length()})")
                }
            } catch (_: Exception) {}

            LaunchLog.w("ABOUT TO LOAD host $plain hostPath=${hostFile.absolutePath} exists=${hostFile.exists()} size=${if (hostFile.exists()) hostFile.length() else 0}")
            // 1) absolute path if extracted (legacy packaging)
            if (hostFile.exists() && hostFile.length() > 0) {
                return try {
                    System.load(hostFile.absolutePath)
                    LaunchLog.w("Loaded host $plain via absolute path OK")
                    true
                } catch (e: Throwable) {
                    LaunchLog.e("Failed host $plain absolute", e)
                    false
                }
            }
            // 2) System.loadLibrary — works when lib is in APK even if not extracted
            return try {
                System.loadLibrary(plain)
                LaunchLog.w("Loaded host $plain via loadLibrary OK")
                true
            } catch (e: Throwable) {
                LaunchLog.e("Failed host $plain loadLibrary", e)
                false
            }
        }

        LaunchLog.w("ABOUT TO LOAD $plain path=${mcFile.absolutePath} exists=${mcFile.exists()} size=${if (mcFile.exists()) mcFile.length() else 0}")
        return try {
            if (mcFile.exists() && mcFile.length() > 0) {
                System.load(mcFile.absolutePath)
                LaunchLog.w("Loaded $plain OK (${mcFile.length()} bytes)")
                true
            } else {
                System.loadLibrary(plain)
                LaunchLog.w("Loaded $plain via loadLibrary OK")
                true
            }
        } catch (e: Throwable) {
            LaunchLog.e("Failed $plain", e)
            false
        }
    }

    fun loadAllLibraries(excludeLibs: Set<String> = emptySet()) {
        // Do not preload maesdk/pairipcore first — they can SIGSEGV on 1.26.x
        val coreOrder = listOf(
            "c++_shared",
            "fmod",
            "MediaDecoders_Android",
            "minecraftpe",
            "xelo"
        )
        val optional = setOf("maesdk", "pairipcore", "HttpClient.Android", "PlayFabMultiplayer")
        LaunchLog.w("loadAllLibraries nativeLibDir=$nativeLibDir excludes=$excludeLibs")
        try {
            File(nativeLibDir).listFiles()?.sortedBy { it.name }?.forEach {
                LaunchLog.w("  extracted: ${it.name} (${it.length()} bytes)")
            }
        } catch (_: Exception) {}

        coreOrder.forEach { libName ->
            if (excludeLibs.contains(libName) || excludeLibs.contains("lib$libName.so")) {
                LaunchLog.w("exclude $libName")
                return@forEach
            }
            if (!loadLibrary(libName)) {
                LaunchLog.w("core lib $libName failed (continuing if not minecraftpe)")
            }
        }
        optional.forEach { libName ->
            if (excludeLibs.contains(libName)) return@forEach
            val f = File(nativeLibDir, "lib$libName.so")
            if (f.exists() && f.length() > 0) {
                LaunchLog.w("optional try $libName")
                loadLibrary(libName)
            }
        }
    }

    fun getAssets(): AssetManager = assetManager

    fun getPackageContext(): Context = packageContext

    fun getApplicationInfo(): ApplicationInfo = applicationInfo

    fun getVersionName(): String? {
        return try {
            context.packageManager.getPackageInfo(packageContext.packageName, 0).versionName
        } catch (e: Exception) {
            version?.versionCode
        }
    }

    companion object {
        private const val TAG = "GamePackageManager"

        @Volatile
        private var instance: GamePackageManager? = null

        @JvmStatic
        fun getInstance(context: Context, version: GameVersion? = null): GamePackageManager {
            return synchronized(this) {
                instance = null // Reset instance to ensure fresh initialization
                instance ?: GamePackageManager(context.applicationContext, version).also { instance = it }
            }
        }

        fun isInitialized() = instance != null
    }
}