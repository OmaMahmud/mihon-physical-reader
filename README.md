# Real Reader — independent Android alpha

Original Kotlin / Jetpack Compose app (`com.realreader.app`), separate from Mihon. No Mihon application classes or dependencies are included. The approved v4.0 prototype's mathematical crease behavior is implemented natively in OpenGL ES 2; no WebView or bundled comic demo.

Build with JDK 17, Android SDK 35, Gradle 8.11.1: `gradle testDebugUnitTest lintDebug assembleDebug`. The CI workflow additionally runs Android 35 emulator tests. Minimum Android 8 (API 26).

Open Settings to enable PDF explicitly, choose one or more SAF folders, or use + to import files. CBZ/ZIP, PDF, JPEG, PNG and WebP are supported. CBR is outstanding. Original files remain in place; a seekable temporary copy (up to 1 GB) is made while reading archives/PDF. Library metadata and progress live in private SQLite storage.

This is a debug alpha, not a Play release. See REPORT.md for known limitations and validation evidence.
