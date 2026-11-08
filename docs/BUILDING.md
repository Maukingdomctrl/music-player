# Building MyCar Music

This page is for developers. If you just want to install the app, see the
[Download section of the README](../README.md#download).

## Toolchain

| Component | Version |
| --- | --- |
| Gradle (wrapper) | see `gradle/wrapper/gradle-wrapper.properties` |
| JDK running Gradle | 25 (Eclipse Adoptium, via `gradle/gradle-daemon-jvm.properties`) |
| Android Gradle Plugin | 9.5.1 (built-in Kotlin) |
| Kotlin / Compose compiler | 2.4.21 |
| KSP | 2.3.13 |
| Room | 3.0.3 (`androidx.room3`) |
| Java / Kotlin bytecode | 17 |
| compileSdk / targetSdk | 37 |
| minSdk | 26 (Android 8.0) |
| Build Tools | 36.0.0 |

All library and plugin versions live in `gradle/libs.versions.toml`.

## Requirements

- Windows, macOS or Linux.
- Android Studio (current stable) with Android SDK Platform 37 and
  Build Tools 36.0.0.
- JDK 25. Gradle picks it from `gradle/gradle-daemon-jvm.properties`
  and can download it automatically through the Foojay toolchain resolver.
- An emulator or device on Android 8.0 (API 26) or later.

The first build needs an internet connection to download Gradle and
dependencies. The app itself works fully offline.

## Build

```powershell
# Windows
.\gradlew.bat assembleDebug
```

```sh
# macOS / Linux
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Other useful tasks:

```sh
./gradlew testDebugUnitTest        # JVM unit tests (incl. Robolectric Room tests)
./gradlew lintDebug                # Android lint
./gradlew assembleRelease          # unsigned release APK
./gradlew installDebug             # install on the connected device/emulator
./gradlew connectedDebugAndroidTest  # instrumented tests (needs a device/emulator)
```

## Database and migrations

The library database uses Room 3 with exported schemas in `app/schemas/`.
Migrations are never destructive: `MusicDatabase.MIGRATION_1_2` upgrades
version 1 databases, and `RoomUpgradeDataPreservationTest` checks that
databases created by the earlier Room 2 build open with all data intact.

## Emulator setup and local music

1. In Android Studio, open **Tools > Device Manager** and create a phone
   emulator (API 35 or later).
2. Copy MP3, M4A or FLAC files onto it (drag them onto the emulator window,
   or use `adb push file.mp3 /sdcard/Music/`).
3. Install and open the app, then open the menu and use **Scan this phone**,
   **Select files** or **Import SD-card folder**.

The emulator's media index can take a moment to notice new files.

## Manual test checklists

### Local playback

- [ ] Import a real MP3, an M4A and a FLAC file.
- [ ] Confirm title, artist, album and duration; check embedded artwork.
- [ ] Play, pause, seek, skip forward and back; test shuffle and repeat.
- [ ] Edit metadata and confirm the library shows the change while the
      original audio file is unchanged.
- [ ] Create, rename, reorder and delete playlists.
- [ ] Remove a song from the library and confirm its file remains.
- [ ] Restart the app and confirm selected-file access still works.
- [ ] Move, delete or revoke access to a file and check how the app reacts.
- [ ] Check whether importing the same file through MediaStore and the
      document picker creates duplicates.

### Recovery

- [ ] Play a queue, seek, set shuffle and repeat, then close and reopen the
      app: queue, track, position, shuffle and repeat are restored.
- [ ] Restoration does not start audible playback automatically.
- [ ] Repeat with one saved file unavailable and check the recovery notice.
- [ ] Test a service/process restart separately from activity recreation.

Checkpointing is periodic, so an abrupt process kill can lose changes made
since the last checkpoint.

### Background playback and Bluetooth

- [ ] Playback continues from the launcher and with the screen locked.
- [ ] Notification and lock-screen controls and metadata work.
- [ ] Bluetooth headphones/speaker show metadata; hardware buttons work.
- [ ] Disconnecting Bluetooth does not continue playback on the speaker.
- [ ] Interruptions from another audio app and a phone call behave well.

The app restores playback when the app or service starts. It does not yet
implement Media3's separate playback-resumption callback for restarting a
stopped service from an old system notification.

### Harman simulator

The Tata Punch EV Harman diagnostic screen is a **simulator only** (see the
README). Real vehicle and infotainment behaviour must be tested on the
physical hardware, never while driving.
