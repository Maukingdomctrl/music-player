# Music Player

A native, offline Android music player using Kotlin, Jetpack Compose,
Media3, Room, DataStore, MediaStore and the Storage Access Framework.

Package: `com.maukingdom.musicplayer`

## Current integration status

The source files were generated in batches. Before treating the project as
build-ready, apply the separately supplied integration corrections to the
earlier files.

The project has not been compiled or physically tested merely because its
files have been generated. Build and test it in Android Studio.

## Requirements

- Windows 10 or 11.
- Android Studio with Android SDK 35 installed.
- JDK 17 configured as the Gradle JDK.
- Gradle 8.9, or a locally generated Gradle wrapper for Gradle 8.9.
- An Android emulator or device running Android 8.0 / API 26 or later.

The project targets API 35. Initial dependency downloads require an internet
connection; normal music playback is designed to work offline.

## Windows Android Studio setup

1. Install Android Studio from the official Android developer website.
2. Open Android Studio and complete its setup wizard.
3. Open **Tools > SDK Manager**:
   - Install Android SDK Platform 35.
   - Install the Android SDK Platform Tools.
   - Install an appropriate Android Emulator and system image.
4. Install a JDK 17 distribution if one is not already available.
5. In Android Studio, open **Settings > Build, Execution, Deployment >
   Build Tools > Gradle** and select a JDK 17 installation for **Gradle JDK**.
6. Assemble the project files under a directory named `music-player`, using
   the exact paths provided with the source files.
7. Apply the integration corrections mentioned above before building.

## Gradle setup

This manually assembled project does not include a Gradle wrapper JAR or
wrapper scripts.

One option is to install Gradle 8.9 locally and configure Android Studio to
use it. Another is to use that local installation to generate a wrapper:

```powershell
cd C:\path\to\music-player
gradle wrapper --gradle-version 8.9
```

The wrapper task generates:

- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar`

Only use a trusted Gradle installation and distribution.

## Open and build

1. In Android Studio, choose **Open** and select the `music-player` folder.
2. Allow Gradle sync to finish and install any requested SDK components.
3. Confirm that the Gradle JDK is JDK 17.
4. Choose **Build > Make Project**.
5. Select the `app` run configuration and run it on an emulator or device.

From PowerShell, after generating the wrapper:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
```

The connected test command requires a running emulator or connected device.

## Emulator setup and local music

1. In Android Studio, open **Tools > Device Manager**.
2. Create a phone emulator with an API 35 system image.
3. Start the emulator.
4. Drag actual MP3, M4A, or FLAC files from Windows into the emulator, or use
   Android Studio's Device Explorer to place files in shared storage.
5. Install and open the app.
6. Use **SELECT FILES** to choose audio through the system document picker.
7. Alternatively, use **SCAN DEVICE** and grant audio-library permission.

The emulator's media index may take time to notice newly copied files.
If scanning does not show them, try the document picker or restart the
emulator and scan again. File-format support depends on the device and
decoder implementation.

## Local playback checklist

- [ ] Import a real MP3 file.
- [ ] Import an M4A file supported by the device.
- [ ] Import a FLAC file supported by the device.
- [ ] Confirm title, singer, album/movie and duration where available.
- [ ] Check available embedded or provider artwork.
- [ ] Play, pause, seek, skip forward and skip backward.
- [ ] Test shuffle and all repeat modes.
- [ ] Edit metadata and confirm the library shows the correction.
- [ ] Verify the original audio file was not modified.
- [ ] Create, rename, reorder and delete playlists.
- [ ] Remove a song from the app library and confirm its file remains.
- [ ] Test selected-file access again after restarting the app.
- [ ] Test what happens when a file is moved, deleted or access is revoked.
- [ ] Check whether importing the same physical file through MediaStore and
      SAF creates duplicate entries.

## Recovery checklist

- [ ] Play a queue and seek to a recognizable position.
- [ ] Set shuffle and repeat.
- [ ] Pause and close the app, then reopen it.
- [ ] Confirm the queue, track, position, shuffle and repeat are restored.
- [ ] Confirm restoration does not start audible playback automatically.
- [ ] Repeat with one saved file unavailable and check the recovery notice.
- [ ] Test service/process restart separately from normal activity recreation.

Checkpointing is periodic. An abrupt process kill can lose changes made
since the last completed checkpoint.

## Background playback and Bluetooth checklist

- [ ] Start a real local track, then return to the launcher.
- [ ] Lock the screen and confirm playback continues.
- [ ] Test play/pause and skip from Android media controls.
- [ ] Verify notification and lock-screen metadata.
- [ ] Connect Bluetooth headphones or a speaker and check metadata and
      hardware media buttons.
- [ ] Disconnect the headphones or speaker and confirm playback does not
      unexpectedly continue through the phone speaker.
- [ ] Test interruptions from another audio app and a phone call.
- [ ] Test on both an emulator and a physical device where possible.

The app restores playback when the app or service starts. The current
generated service does not implement Media3's separate system playback-
resumption callback for restarting a stopped service from an old system
notification.

## Harman simulator and vehicle testing

The Tata Punch EV Harman diagnostic screen is a **simulator only**.
Its connection state, metadata and events are simulated. It does not
connect to or diagnose a vehicle and does not establish compatibility.

Actual vehicle and infotainment behavior must be tested on the physical
hardware. Configure music before driving and do not operate the phone
while driving.

## Privacy and offline behavior

The project does not include accounts, cloud storage, online streaming,
advertisements, subscriptions or analytics. It plays locally accessible
audio files.
