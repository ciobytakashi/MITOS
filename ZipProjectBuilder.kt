package com.mitos.app

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class ZipProjectInspection(
    val kind: String,
    val entryCount: Int,
    val mainHtml: String?,
    val extractedDirectory: String,
    val outputZip: String?,
    val notes: List<String>
)

object ZipProjectBuilder {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
    private const val MAX_SINGLE_FILE_BYTES = 100L * 1024L * 1024L

    fun inspectAndPrepare(
        context: Context,
        source: Uri,
        appName: String,
        packageId: String
    ): ZipProjectInspection {
        require(appName.isNotBlank()) { "Enter an application name." }
        require(packageId.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            "Package ID must look like com.example.app and contain valid Android identifiers."
        }

        val base = File(context.filesDir, "zip-builder").apply { mkdirs() }
        val stamp = System.currentTimeMillis().toString()
        val extracted = File(base, "import-$stamp").apply { mkdirs() }
        val names = mutableListOf<String>()
        var totalBytes = 0L
        val input = context.contentResolver.openInputStream(source)
            ?: error("Android could not open the selected ZIP file.")

        input.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    require(names.size < MAX_ENTRIES) { "ZIP contains too many files (limit: $MAX_ENTRIES)." }
                    val normalized = entry.name.replace('\\', '/')
                    require(!normalized.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
                        "The ZIP contains an unsafe absolute path."
                    }
                    val segments = normalized.split('/')
                    require(segments.none { it == ".." }) { "The ZIP contains an unsafe parent-directory path." }
                    val target = File(extracted, normalized).canonicalFile
                    require(target.toPath().startsWith(extracted.canonicalFile.toPath())) {
                        "The ZIP contains a path outside its extraction folder."
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        var written = 0L
                        FileOutputStream(target).use { out ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                written += count
                                totalBytes += count
                                require(written <= MAX_SINGLE_FILE_BYTES) { "A ZIP entry exceeds the 100 MB safety limit." }
                                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "The extracted ZIP exceeds the 512 MB safety limit." }
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                    names += normalized
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        val files = extracted.walkTopDown().filter { it.isFile }.toList()
        val androidRoot = findAndroidRoot(extracted)
        val html = files.firstOrNull {
            it.name.equals("index.html", ignoreCase = true) &&
                !it.path.contains("/node_modules/") && !it.path.contains("/.git/")
        }
        return if (androidRoot != null) {
            val hasSettings = File(androidRoot, "settings.gradle").exists() ||
                File(androidRoot, "settings.gradle.kts").exists()
            val hasBuild = File(androidRoot, "build.gradle").exists() ||
                File(androidRoot, "build.gradle.kts").exists()
            val hasAppModule = File(androidRoot, "app/build.gradle").exists() ||
                File(androidRoot, "app/build.gradle.kts").exists()
            val notes = mutableListOf<String>()
            if (!hasSettings) notes += "Missing settings.gradle or settings.gradle.kts."
            if (!hasBuild) notes += "Missing root build.gradle or build.gradle.kts."
            if (!hasAppModule) notes += "No app module build.gradle(.kts) found; this may be a library or incomplete project."
            val preparedZip = File(base, "MITOS-Android-Project-$stamp.zip")
            zipDirectory(androidRoot, preparedZip)
            ZipProjectInspection(
                kind = "Android project",
                entryCount = names.size,
                mainHtml = null,
                extractedDirectory = androidRoot.absolutePath,
                outputZip = preparedZip.absolutePath,
                notes = notes.ifEmpty { listOf("Project structure detected. This is a structural check, not a Gradle build.") }
            )
        } else if (html != null) {
            val generated = File(base, "web-shell-$stamp")
            generateWebShell(html.parentFile ?: extracted, generated, appName.trim(), packageId.trim())
            val output = File(base, "MITOS-WebView-Project-$stamp.zip")
            zipDirectory(generated, output)
            ZipProjectInspection(
                kind = "Web project",
                entryCount = names.size,
                mainHtml = html.relativeTo(extracted).invariantSeparatorsPath,
                extractedDirectory = extracted.absolutePath,
                outputZip = output.absolutePath,
                notes = listOf(
                    "A native Android WebView shell project was generated.",
                    "Website files were copied from the folder containing ${html.name}; check the output if your site uses assets stored elsewhere.",
                    "The generated project still needs an Android SDK/Gradle build, such as the MITOS GitHub Actions workflow."
                )
            )
        } else {
            ZipProjectInspection(
                kind = "Unrecognized ZIP",
                entryCount = names.size,
                mainHtml = null,
                extractedDirectory = extracted.absolutePath,
                outputZip = null,
                notes = listOf("No Android Gradle project or index.html was found. The ZIP was safely extracted for inspection.")
            )
        }
    }

    private fun findAndroidRoot(extracted: File): File? {
        val dirs = listOf(extracted) + extracted.walkTopDown().filter { it.isDirectory && it != extracted }.toList()
        return dirs.firstOrNull { dir ->
            (File(dir, "settings.gradle").exists() || File(dir, "settings.gradle.kts").exists()) &&
                (File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists())
        }
    }

    private fun generateWebShell(siteRoot: File, output: File, appName: String, packageId: String) {
        val packagePath = packageId.replace('.', '/')
        val assets = File(output, "app/src/main/assets/site").apply { mkdirs() }
        siteRoot.copyRecursively(assets, overwrite = true)
        val javaDir = File(output, "app/src/main/java/$packagePath").apply { mkdirs() }
        File(javaDir, "MainActivity.kt").writeText(
            """package $packageId

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        setContentView(webView)
        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/site/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        (window.decorView.findViewById<android.view.View>(android.R.id.content) as? android.view.ViewGroup)
            ?.getChildAt(0)?.let { (it as? WebView)?.saveState(outState) }
        super.onSaveInstanceState(outState)
    }
}
"""
        )
        File(output, "settings.gradle.kts").writeText(
            """pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "WebViewApp"
include(":app")
"""
        )
        File(output, "build.gradle.kts").writeText(
            """plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
"""
        )
        File(output, "gradle.properties").writeText(
            "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nkotlin.code.style=official\n"
        )
        File(output, "app/build.gradle.kts").writeText(
            """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "$packageId"
    compileSdk = 35
    defaultConfig {
        applicationId = "$packageId"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.0")
}
"""
        )
        File(output, "app/src/main/AndroidManifest.xml").writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:label="$appName" android:theme="@android:style/Theme.Material.Light.NoActionBar"
        android:usesCleartextTraffic="false">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""
        )
        File(output, "README.md").writeText(
            "# $appName\n\nGenerated WebView wrapper project. Website files are in `app/src/main/assets/site`.\n\nBuild with JDK 17, Android SDK 35, and Gradle 8.9 using `gradle :app:assembleDebug`.\n"
        )
        File(output, ".github/workflows/android.yml").apply {
            parentFile?.mkdirs()
            writeText(
                """name: Android APK
on: [push, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'
      - run: gradle :app:assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with:
          name: $appName-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
"""
            )
        }
    }

    private fun zipDirectory(source: File, destination: File) {
        ZipOutputStream(FileOutputStream(destination)).use { zip ->
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = file.relativeTo(source).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
