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
- **Optional tilt-pause** (gravity sensor): if enabled in settings, detection pauses while
  the phone is held flat and prompts the user to raise the camera. Off by default.

## Functionality

- Real-time red/green pedestrian traffic light detection
- Text-to-speech announcements ("Es ist rot" / "Es ist grün")
- Distinct vibration patterns per phase (red = pulsing pattern, green = solid 1s)
- Configurable stability window (Settings → "Dauer bis zur Erkennung")
- Optional flashlight/torch to keep LED colours from washing out (Settings, on by default)
- Optional tilt-pause when the phone is held flat (Settings, off by default)
- Opt-in debug logging with a "share log" option (Settings)
- Works in portrait and landscape

## Known Limitations

- **German pedestrian traffic lights only.** The model is trained on the German Ampel-Pilot
  dataset (red standing figure / green walking figure). It detects those pedestrian phases
  and is not intended for vehicle traffic lights.
- **Tram / light-rail signals (Straßenbahn) are not detected.** German tram signals use
  BOStrab "Fahrsignale" (F0–F5) — white bar/line symbols on a dark matrix, not red/green
  discs. They share none of the pedestrian-signal visual features (colour, shape, symbol),
  so the model correctly returns no detection for them. Supporting tram signals would require
  a different or retrained model that includes tram-signal classes.
- **Not a substitute for your own judgement.** Use only as an additional aid; night, glare,
  distance, and multi-lane crossings reduce accuracy.

## Internationalization / Porting to Other Countries

The app currently works reliably only for **German pedestrian traffic lights**. The detection
model was trained on the German Ampel-Pilot dataset and recognizes the German Ampelmännchen
(red standing figure / green walking figure) via its learned appearance — it does not do
generic colour/shape detection.

How other countries compare:

| Country | Pedestrian signal | Works today? |
|---------|-------------------|--------------|
| 🇩🇪 Germany | Red standing / green walking figure | Yes (trained for this) |
| 🇬🇧 UK | Red standing man / green walking man (steady) | Maybe partial, unvalidated (different figure design/hue) |
| 🇪🇺 Continental EU | Red/green human figures (varied designs) | Possibly partial, unvalidated |
| 🇺🇸 US / 🇨🇦 Canada | Orange raised hand + white walking person, often countdown | No (wrong colours and symbol) |

The **app architecture is country-agnostic** — the CameraX pipeline, TFLite inference,
stability buffer, torch control, orientation handling, and TTS are all reusable. Only three
pieces are locale-specific:

1. **The model + `app/src/main/assets/labelmap.txt`** — the main effort. Fine-tune or retrain
   on datasets that include the target country's pedestrian signals, and update the labels
   (e.g. add classes such as `walk_hand` / `countdown`). Keep the SSD MobileNet 300×300
   float input/output contract, or update `TFLiteDetector` accordingly. The `/home/ali/ampel/`
   YOLOv8 training pipeline is a starting point (note: its bundled LISA dataset is US *vehicle*
   lights, not pedestrian).
2. **Spoken strings** — the announcements ("Es ist rot", "Es ist grün", "Warte!",
   "Halten Sie die Kamera bitte hoch!") are currently hard-coded German in `LdActivity`.
   Move them to `strings.xml` and provide per-locale `values-xx/strings.xml`.
3. **Optional region/model selector** — bundle multiple models or let the user choose a
   country, loading the matching `.tflite` + labels + announcement locale.

In short, internationalization is primarily a **model/dataset task**, not an app rewrite.

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
