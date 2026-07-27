# AmpelPilot - Android 14+

A traffic light assistance app for visually impaired users (Ampelassistenz-App für Sehbehinderte).

This is a modernized port of the original [AmpelPilot Android app](https://github.com/strator1/Ampel-Pilot-Android) to Android 14+ (API level 34+), upgraded to use a neural-network detection model.

## Detection Engine

The app uses a **TensorFlow Lite object detection model** (SSD MobileNet, 300×300 input) trained
to detect red and green pedestrian traffic light phases. The model and label map originate from
the author's newer project,
[Ampel-Pilot-for-Android-new-](https://github.com/strator1/Ampel-Pilot-for-Android-new-),
trained on the [Ampel-Pilot dataset](https://github.com/strator1/Ampel-Pilot-Dataset)
(~3600 German pedestrian traffic light images).

This replaces the original OpenCV Haar/LBP cascade classifiers for significantly better accuracy
(mAP@IoU0.5 ≈ 85%).

## Architecture

- **CameraX** for the camera pipeline (`PreviewView` + `ImageAnalysis`) — handles rotation
  natively and works in both portrait and landscape.
- **TensorFlow Lite** runs inference on each analyzed frame (300×300 RGB input).
- A **stability buffer** requires the same phase across several consecutive frames before
  announcing, to avoid false positives.
- **DetectionOverlayView** draws bounding boxes over the preview.
- **Sensor-based tilt guidance** (accelerometer + magnetometer) vibrates and speaks hints to
  help visually impaired users aim the camera correctly.

## Functionality

- Real-time red/green pedestrian traffic light detection
- Text-to-speech announcements ("Es ist rot" / "Es ist grün")
- Distinct vibration patterns per phase (red = pulsing pattern, green = solid 1s)
- Tilt feedback when the phone is held at a poor angle
- Configurable stability window (Settings → "Dauer bis zur Erkennung")
- Works in portrait and landscape

## Changes from Original

- **Target/Min SDK**: raised from API 23/19 to API 34 (Android 14+)
- **Build system**: AGP 2.3.1 / legacy Gradle → AGP 8.5.0 / Gradle 8.7
- **AndroidX**: migrated from `android.support.*`
- **Camera**: OpenCV `JavaCameraView` (deprecated Camera API) → CameraX
- **Detection**: OpenCV Haar/LBP cascades → TensorFlow Lite SSD MobileNet
- **Permissions**: modern `ActivityResultContracts` runtime permission flow
- **Vibration**: `VibrationEffect` / `VibratorManager` APIs
- **Manifest**: `namespace` in build.gradle, `android:exported` on all activities
- Removed OpenCV dependency entirely (APK reduced from ~114 MB to ~46 MB)

## Building

Requires:
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17 or 21
- Android SDK 34

```bash
./build.sh          # checks/switches to Java 21 via SDKMAN, then builds
# or
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Original Projects

- **Original Android app**: https://github.com/strator1/Ampel-Pilot-Android (branch `ampelPilot_And_prototyp`)
- **TFLite model source**: https://github.com/strator1/Ampel-Pilot-for-Android-new-
- **Dataset**: https://github.com/strator1/Ampel-Pilot-Dataset
- **iOS version**: https://github.com/strator1/Ampel-Pilot-iOS
- **Author**: [strator1 (Torsten Straßer)](https://github.com/strator1) / HS Augsburg

## License

MIT License - see [LICENSE](LICENSE). Original work © strator1 / HS Augsburg.
