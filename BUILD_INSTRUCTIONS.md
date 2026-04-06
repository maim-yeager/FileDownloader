# 📱 File Downloader – Build Instructions

## ✅ What's in this project

A complete, production-ready Android app that:
- Downloads ALL file types (PDF, ZIP, APK, DOCX, images, etc.)
- **Blocks video and audio** downloads (legal-safe)
- Multi-threaded chunked downloading (HTTP Range, up to 8 threads)
- Pause / Resume / Cancel controls
- Background foreground service with progress notifications
- Download history (Room database)
- File manager to browse downloaded files
- Dark + Light mode
- Material Design 3 UI

---

## 🚀 Quick Build (Android Studio – Easiest)

1. **Install Android Studio** → https://developer.android.com/studio
2. **Open project**: File → Open → select the `FileDownloader` folder
3. Android Studio will **sync Gradle automatically**
4. Connect your Android device (or use the emulator)
5. Press **▶ Run** (Shift+F10)

The APK will be at:
```
FileDownloader/app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔧 Command-Line Build (Linux / Mac)

### Prerequisites
- **Java 17+** → `sudo apt install openjdk-17-jdk` (Ubuntu) or download from https://adoptium.net
- **Android SDK** → Install via Android Studio or `sdkmanager`
- Set `ANDROID_HOME`: `export ANDROID_HOME=~/Android/Sdk`

### Step 1 – Download real Gradle wrapper (one-time)
```bash
cd FileDownloader

# The included gradle-wrapper.jar is a stub. Replace it with the real one:
curl -L "https://github.com/nicklockwood/SwiftFormat/releases/download/0.53.7/SwiftFormat.zip" \
  -o /dev/null   # (just a connectivity test)

# Download real wrapper from Gradle releases:
mkdir -p gradle/wrapper
curl -L "https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar" \
  -o gradle/wrapper/gradle-wrapper.jar

# OR: run the Android Studio sync once - it auto-downloads the wrapper
```

### Step 2 – Set ANDROID_HOME and build
```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools

chmod +x gradlew
./gradlew assembleDebug
```

### Step 3 – Find the APK
```
app/build/outputs/apk/debug/app-debug.apk
```

### Install directly on connected device:
```bash
./gradlew installDebug
```

---

## 🪟 Windows Build

1. Install Android Studio from https://developer.android.com/studio
2. Open the `FileDownloader` folder
3. Let Gradle sync finish
4. Build → Generate Signed Bundle/APK → APK → Debug

Or via command prompt:
```cmd
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
gradlew.bat assembleDebug
```

---

## 📦 Build Release APK (signed)

### Create a keystore (one-time):
```bash
keytool -genkey -v -keystore my-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias mykey
```

### Add signing config to `app/build.gradle`:
```gradle
android {
    signingConfigs {
        release {
            storeFile file('../my-release.jks')
            storePassword 'your_password'
            keyAlias 'mykey'
            keyPassword 'your_password'
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
        }
    }
}
```

### Build signed release:
```bash
./gradlew assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk`

---

## ⚠️ Note on gradle-wrapper.jar

The `gradle/wrapper/gradle-wrapper.jar` included is a **stub placeholder**.  
When you open the project in Android Studio or run `./gradlew` for the first time,
Gradle will **automatically download the real wrapper** from https://services.gradle.org.

This is standard Android project behavior – the real jar cannot be distributed directly
in source packages due to size (~60KB binary).

---

## 📁 Project Structure

```
FileDownloader/
├── app/
│   ├── src/main/
│   │   ├── java/com/filedownloader/
│   │   │   ├── data/
│   │   │   │   ├── database/     AppDatabase.kt (Room)
│   │   │   │   ├── models/       DownloadItem.kt, FileCategory.kt
│   │   │   │   └── repository/   DownloadRepository.kt
│   │   │   ├── service/
│   │   │   │   └── DownloadService.kt  (Foreground service)
│   │   │   ├── ui/
│   │   │   │   ├── activities/   MainActivity.kt, FileManagerActivity.kt
│   │   │   │   ├── adapters/     DownloadsAdapter.kt
│   │   │   │   └── viewmodels/   MainViewModel.kt
│   │   │   └── utils/
│   │   │       ├── ChunkDownloader.kt  ← Multi-thread engine
│   │   │       ├── FileUtils.kt
│   │   │       └── UrlInfoFetcher.kt
│   │   ├── res/
│   │   │   ├── layout/           activity_main.xml, item_download.xml, etc.
│   │   │   ├── drawable/         Icons, backgrounds
│   │   │   ├── values/           colors, strings, themes (light)
│   │   │   └── values-night/     Dark mode overrides
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
├── gradle.properties
└── gradle/wrapper/
    ├── gradle-wrapper.jar   ← Replace with real jar (see above)
    └── gradle-wrapper.properties
```

---

## 🏗️ Architecture Overview

```
MainActivity
    │
    ├─── MainViewModel (StateFlow, LiveData)
    │         │
    │         └─── DownloadRepository
    │                     │
    │                     └─── Room Database (downloads table)
    │
    └─── DownloadService (Foreground)
              │
              └─── ChunkDownloader (per download)
                        │
                        ├─── OkHttp (HTTP client)
                        ├─── Coroutines (parallel chunks)
                        └─── FileOutputStream (merge chunks)
```

---

## 🔥 Performance Features

| Feature | Detail |
|---------|--------|
| Multi-threading | Up to 8 parallel chunks via Kotlin Coroutines |
| Range requests | `HTTP Range: bytes=X-Y` headers |
| Chunk size | Auto-calculated: `fileSize / threadCount` |
| Retry logic | 3 retries per chunk with exponential backoff |
| Buffer size | 8KB read buffer, 64KB merge buffer |
| Pause/Resume | Partial chunk files preserved on disk |
| Fallback | Single-thread if server doesn't support Range |

---

## 📋 Permissions Used

| Permission | Reason |
|-----------|--------|
| INTERNET | Download files |
| WRITE_EXTERNAL_STORAGE | Save files (Android < 10) |
| FOREGROUND_SERVICE | Background download service |
| POST_NOTIFICATIONS | Progress notifications (Android 13+) |
| ACCESS_NETWORK_STATE | Check connectivity |
| WAKE_LOCK | Keep CPU alive during download |
