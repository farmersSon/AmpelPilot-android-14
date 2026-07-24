# AmpelPilot - Android 14+

A traffic light assistance app for visually impaired users (Ampelassistenz-App für Sehbehinderte).

This is a port of the original [AmpelPilot Android app](https://github.com/strator1/Ampel-Pilot-Android) to Android 14+ (API level 34+).

## Changes from Original

- **Target SDK**: Updated from API 23 to API 34 (Android 14)
- **Min SDK**: API 34 (Android 14+ only)
- **Build system**: Migrated from AGP 2.3.1 / Gradle legacy to AGP 8.5.0 / Gradle 8.7
- **AndroidX**: Migrated from `android.support.*` to `androidx.*`
- **Permissions**: Updated to modern `ActivityResultContracts` permission model
- **Vibration**: Updated to use `VibrationEffect` API (Android 8+) and `VibratorManager` (Android 12+)
- **OpenCV**: Retained OpenCV 3.2.0 Java bindings, removed unsupported ABIs (mips, mips64, armeabi)
- **Manifest**: Added `android:exported` attributes, removed `package` attribute (uses `namespace` in build.gradle)

## Functionality

**No functionality has been changed.** The app detects pedestrian traffic lights via the camera using Haar cascade classifiers (OpenCV) and provides:

- Real-time green/red traffic light detection
- Text-to-speech announcements ("Es ist Grün" / "Warte!")
- Vibration feedback when the phone is held at an incorrect angle
- Configurable detection parameters (ScaleFactor, MinNeighbours, Frames)

## Building

Requires:
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

```bash
./gradlew assembleDebug
```

## Original Project

- **Repository**: https://github.com/strator1/Ampel-Pilot-Android
- **Branch**: `ampelPilot_And_prototyp`
- **Author**: [strator1](https://github.com/strator1)
- **Original target**: Android API 23 (Android 6.0)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

Original work © strator1 / HS Augsburg.
