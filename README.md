# Pepper Museum Guider

Pepper Museum Guider is an Android application built for the SoftBank Robotics Pepper robot to guide visitors through museum exhibits. The app is written in Kotlin, leverages the QiSDK and QiSDK Design libraries, and manages conversation, autonomous abilities, and multimedia playback on the robot.

## Project Overview

The application focuses on:

- Connecting to the Pepper robot and managing robot lifecycle callbacks.
- Running a `QiChatbot` conversation with visitors, with optional fallback to a text-driven broadcast mode.
- Periodically reading text-to-speech content and playing it through Pepper.
- Displaying image slideshows and a logo sourced from the robot's external storage.
- Managing runtime permissions for storage and audio recording.

The primary logic resides in `MainActivity.kt`, which coordinates robot focus events, chatbot state transitions, file initialization, and UI indicators for playback state.

## Repository Structure

```
app/
├─ src/main/
│  ├─ java/com/example/myapplication/MainActivity.kt   Pepper lifecycle and business logic
│  ├─ res/layout/activity_main.xml                    Main UI layout
│  ├─ res/drawable/indicator_*                        UI indicator assets
│  └─ res/raw/greetings.top                           QiChat topic definition (optional)
├─ build.gradle                                       Android module configuration (compileSdk 29)
gradle.properties / build.gradle                      Project-level Gradle configuration
```

## Environment Setup

### Required Tools

1. **Android Studio**: Use Arctic Fox (2020.3.1) or a newer release with Android SDK 29 and Build Tools 29.0.3 installed.
2. **Kotlin Support**: The project enables `kotlin-android` and related Gradle plugins out of the box.
3. **Pepper SDK**: Download the QiSDK packages (`com.aldebaran:qisdk:1.7.5` and `com.aldebaran:qisdk-design:1.7.5`) from the SoftBank Robotics developer portal and add them to your environment. Refer to the official setup instructions: https://qisdk.softbankrobotics.com/sdk/doc/pepper-sdk/index.html
4. **Pepper Robot or Emulator**: Testing requires access to a Pepper robot (or simulator). Enable Developer Mode on the device and ensure it shares a network with your development machine.

## Build and Run

### Command Line

```bash
./gradlew assembleDebug
```

The first run downloads Gradle wrapper dependencies. The generated APK can be found under `app/build/outputs/apk/debug/`.

### Android Studio

1. Open the repository root via **File > Open...**.
2. Allow Gradle sync to finish. Install any requested SDK updates or plugins.
3. Select the target device (Pepper or emulator) from the device chooser.
4. Click **Run** or press `Shift + F10` to deploy and debug.

## External Resources on Pepper

Pepper Museum Guider expects content on the robot's external storage (`/sdcard/pepper`):

- `speech.txt`: Text used for scheduled announcements. The app writes a default sample when the file is missing.
- `images/`: Directory containing slideshow images (`.jpg`, `.jpeg`, `.png`). Restart the app or trigger a reload to pick up changes.
- `logo.png`: Logo displayed in the UI.

Sample commands to push resources via ADB:

```bash
adb push speech.txt /sdcard/pepper/speech.txt
adb push logo.png /sdcard/pepper/logo.png
adb push images/ /sdcard/pepper/images/
```

## Debugging Tips

- Use Android Studio **Logcat** to inspect logs with `TAG = "MainActivity"` for lifecycle events, chatbot status, and file operations.
- When running in Debug builds, add temporary `Log.d` statements in `MainActivity` to trace chatbot transitions or file reloads.
- If the chatbot fails to start, verify that a valid `QiContext` is available and that the `TopicBuilder` successfully loads `res/raw/greetings.top`.
- During text-to-speech playback, the chatbot is paused. Confirm the `isHolding` and `isChatbotRunning` flags reset correctly when playback ends.
- Ensure storage and audio permissions are granted. If file I/O fails, inspect `/sdcard/pepper` via `adb shell` to confirm the files exist and are readable.
- Managing autonomous abilities uses `toggleAutonomousAbilities()` to hold and release capabilities. Extend this logic with additional `HolderBuilder` configurations if more control is required.

## Future Enhancements

- Promote configurable settings (such as slideshow intervals and resource paths) via `SharedPreferences` or a remote configuration service.
- Add unit tests for file management and state handling, and explore QiSDK-provided tools for instrumentation testing.
- Integrate CI/CD (e.g., GitHub Actions) to run `./gradlew lint` and `./gradlew test` on each change.

## Additional Resources

Consult the SoftBank Robotics documentation and QiSDK API reference for deeper guidance on Pepper development, including the official SDK documentation linked above.
