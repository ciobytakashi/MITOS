# MITOS — Personal Android Utility

MITOS is a Kotlin + Jetpack Compose Android application foundation. It currently includes a monochrome app shell, persisted light/dark appearance, Storage Access Framework folder access, basic folder browsing and folder creation, plus an initial ZIP-to-APK preparation tool.

## Build requirements
- JDK 17
- Android SDK Platform 35
- Android Gradle Plugin 8.7.3
- Gradle 8.9

The root Gradle plugin declarations are in `build.gradle.kts`; plugin repositories are configured in `settings.gradle.kts`. These are intentionally kept aligned with AGP 8.7.3 and Kotlin 2.0.21.

## Build a debug APK
From the project root in an Android/Gradle environment:

```bash
gradle :app:assembleDebug --no-daemon
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions runs the same build on pushes and pull requests to `main`, and on manual dispatch. It renames the verified build output to `MITOS-debug.apk` and uploads it as the `MITOS-debug-apk` artifact. A workflow file existing in the repository is not itself proof of a successful build; check the Actions run result.

## Storage access
Open MITOS and choose **Choose folder**. Select a folder in Android's system picker and grant access. The app can browse and create folders within the granted tree, subject to Android restrictions. It does not claim access to protected app-private or system directories.

## ZIP-to-APK Builder foundation
Open **ZIP-to-APK Builder**, enter an app name and package ID, then select a ZIP:
- **Web project:** searches for `index.html`, safely extracts the archive, generates a native Android WebView project, and lets you save the generated project ZIP.
- **Android project:** detects a Gradle Android project, safely extracts it, performs basic structural checks, and packages the detected project root for saving without rewriting its source.
- **Other ZIP:** safely extracts it and reports that it could not identify a supported project.

ZIP extraction rejects absolute paths and `..` traversal, and limits extraction to 10,000 entries, 100 MB per file, and 512 MB total uncompressed data.

**Important limitations:** The app does not compile APKs on the phone. The generated WebView project or imported Android project still needs a real Android SDK + Gradle build environment. GitHub Actions can compile it after the project is uploaded to a repository, but this foundation does not yet upload projects to GitHub or trigger remote builds automatically. Android-project inspection is structural only, not a guarantee that the project will compile. The WebView shell currently loads local website assets; online site features may need additional permissions or configuration.

## Implemented in this foundation
- MITOS app identity and package `com.mitos.app`
- Compose app shell and bottom navigation
- Monochrome light/dark appearance toggle
- Persisted Storage Access Framework folder selection
- Basic folder listing, navigation, refresh and folder creation
- ZIP import, safe extraction, basic project detection
- WebView shell project generation and ZIP export
- Android Gradle project structural inspection and ZIP export
- GitHub Actions debug APK workflow

## Not implemented yet
Advanced file operations (rename/copy/move/delete/multi-select/search/favorites/trash/archive operations), full browser, PWA management, offline pages, utilities, diagnostics, build history, configurable signing, and automatic GitHub cloud-build submission remain future phases.

## Build verification status
This source archive has been inspected and updated. A successful APK build has **not** been claimed. Verify the first build using the included GitHub Actions workflow.
