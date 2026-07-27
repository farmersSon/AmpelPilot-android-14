package de.hsaugsburg.ampelpilot;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main detection activity. Uses CameraX for the camera pipeline and a TensorFlow Lite
 * object detection model (SSD MobileNet) to detect pedestrian traffic light phases.
 * Provides audio (TTS) and vibration feedback, plus sensor-based tilt guidance to help
 * visually impaired users aim the camera.
 */
public class LdActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "AmpelPilot::LdActivity";

    // TFLite model configuration
    private static final int TF_INPUT_SIZE = 300;
    private static final boolean TF_IS_QUANTIZED = false;
    private static final String TF_MODEL_FILE = "detect.tflite";
    private static final String TF_LABELS_FILE = "labelmap.txt";
    private static final float MIN_CONFIDENCE = 0.6f;

    private Classifier detector;

    // Sensor / feedback
    private SensorManager mSensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private Vibrator v;
    private TextToSpeech tts;

    private float[] mGravity;
    private float[] mGeomagnetic;

    // Stability detection - rolling buffer of recent detections
    private final LinkedList<String> recentResults = new LinkedList<>();
    private int stabilityWindow = 4;
    private long lastAnnounceTime = 0;
    private long tiltFeedbackTime = 0;
    private long tiltMillis = System.currentTimeMillis();

    // Vibration patterns
    private final long[] redPattern = {0, 200, 300, 200, 300, 200};
    private final int greenDuration = 1000;

    private SharedPreferences prefs;

    private PreviewView previewView;
    private DetectionOverlayView overlayView;
    private ExecutorService analysisExecutor;

    private volatile boolean detecting = false;

    private final String helpText = "Halten Sie das Handy hoch oder quer und richten Sie die Kamera auf die Ampel. " +
            "Falls Sie das Handy falsch halten wird es vibrieren und eine Sprachnachricht wird abgespielt.\n" +
            "\n" +
            "Benutzen Sie diese App nur als zus\u00e4tzliche Hilfe! Verlassen Sie sich stets auf Ihre eigene Wahrnehmung!\n" +
            "\n" +
            "Der Anbieter dieser App \u00fcbernimmt keine Haftung f\u00fcr Sach- und Personensch\u00e4den, " +
            "welche durch die Nutzung von \u201eAmpel-Pilot\u201c entstehen.";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("de.hsaugsburg.ampelpilot", Context.MODE_PRIVATE);

        if (prefs.getBoolean("firstStart", true)) {
            prefs.edit().putBoolean("firstStart", false).apply();
            new AlertDialog.Builder(this)
                    .setMessage(helpText)
                    .setTitle("AmpelPilot")
                    .setPositiveButton("OK", null)
                    .setCancelable(true)
                    .show();
        }

        setContentView(R.layout.trafficlights_detect_surface_view);

        previewView = findViewById(R.id.camera_preview);
        overlayView = findViewById(R.id.detection_overlay);

        // Keep screen on during detection
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Sensors for tilt guidance
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        // Stability window read from prefs ("Frames" from the settings screen)
        stabilityWindow = Math.max(1, prefs.getInt("Frames", 4));

        // Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v = vm.getDefaultVibrator();
        } else {
            v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        // Settings button
        findViewById(R.id.settingsbtn).setOnClickListener(view -> {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
        });

        // Load the TFLite detector
        try {
            detector = TFLiteDetector.create(
                    getAssets(), TF_MODEL_FILE, TF_LABELS_FILE, TF_INPUT_SIZE, TF_IS_QUANTIZED);
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize TFLite detector", e);
            Toast.makeText(this, "Der Classifier konnte nicht initialisiert werden!", Toast.LENGTH_LONG).show();
        }

        analysisExecutor = Executors.newSingleThreadExecutor();

        startCamera();
    }

    @Override
    protected void onStart() {
        super.onStart();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.GERMANY);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "German TTS not supported");
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
        if (v != null) v.cancel();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (analysisExecutor != null) analysisExecutor.shutdown();
        if (detector != null) detector.close();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                bindCameraUseCases(future.get());
            } catch (Exception e) {
                Log.e(TAG, "Camera initialization failed", e);
                Toast.makeText(this, "Kamera konnte nicht gestartet werden", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();

        imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
    }

    /**
     * Analyze a single camera frame: run TFLite detection and provide feedback.
     * Runs on the analysis executor thread.
     */
    private void analyzeFrame(@NonNull ImageProxy image) {
        if (detector == null || detecting) {
            image.close();
            return;
        }
        detecting = true;
        try {
            int width = image.getWidth();
            int height = image.getHeight();

            // CameraX RGBA_8888: single plane. Build a Bitmap from it.
            Bitmap fullBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            fullBitmap.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer());

            // Scale to the model input size (300x300)
            Bitmap modelInput = Bitmap.createScaledBitmap(fullBitmap, TF_INPUT_SIZE, TF_INPUT_SIZE, true);

            List<Classifier.Recognition> results = detector.recognizeImage(modelInput);

            // Filter by confidence, keep detections in model-input coordinates
            List<Classifier.Recognition> valid = new ArrayList<>();
            for (Classifier.Recognition r : results) {
                if (r.getLocation() != null && r.getConfidence() != null
                        && r.getConfidence() >= MIN_CONFIDENCE) {
                    valid.add(r);
                }
            }

            // Determine the biggest (closest) detection
            Classifier.Recognition biggest = biggestRecognition(valid);
            String currentLight = "none";
            if (biggest != null) {
                currentLight = biggest.getTitle();
            }

            // Rolling stability buffer
            recentResults.add(currentLight);
            while (recentResults.size() > stabilityWindow) {
                recentResults.removeFirst();
            }

            if (biggest != null && isStable()) {
                if (System.currentTimeMillis() - lastAnnounceTime >= 1500) {
                    lastAnnounceTime = System.currentTimeMillis();
                    provideFeedback(currentLight);
                }
            }

            // Build overlay rectangles (in model-input coordinate space, 300x300)
            List<RectF> greenRects = new ArrayList<>();
            List<RectF> redRects = new ArrayList<>();
            for (Classifier.Recognition r : valid) {
                if ("green".equals(r.getTitle())) {
                    greenRects.add(r.getLocation());
                } else if ("red".equals(r.getTitle())) {
                    redRects.add(r.getLocation());
                }
            }
            overlayView.setDetections(greenRects, redRects, TF_INPUT_SIZE, TF_INPUT_SIZE);

            fullBitmap.recycle();
            if (modelInput != fullBitmap) modelInput.recycle();

        } catch (Exception e) {
            Log.e(TAG, "Error analyzing frame", e);
        } finally {
            detecting = false;
            image.close();
        }
    }

    /** All entries in the rolling buffer are the same non-"none" phase. */
    private boolean isStable() {
        if (recentResults.size() < stabilityWindow) return false;
        String first = recentResults.getFirst();
        if ("none".equals(first)) return false;
        for (String s : recentResults) {
            if (!s.equals(first)) return false;
        }
        return true;
    }

    private Classifier.Recognition biggestRecognition(List<Classifier.Recognition> recognitions) {
        Classifier.Recognition biggest = null;
        double biggestArea = 0.0;
        for (Classifier.Recognition r : recognitions) {
            RectF loc = r.getLocation();
            double area = loc.width() * loc.height();
            if (area > biggestArea) {
                biggestArea = area;
                biggest = r;
            }
        }
        return biggest;
    }

    private void provideFeedback(String lightPhase) {
        if ("red".equals(lightPhase)) {
            vibratePattern(redPattern);
            speak("Es ist rot");
        } else if ("green".equals(lightPhase)) {
            vibrateOnce(greenDuration);
            speak("Es ist gr\u00fcn");
        }
    }

    private void speak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    // ---- Sensor tilt guidance (accessibility aid for aiming the camera) ----

    @Override
    public void onSensorChanged(SensorEvent event) {
        long newMillis = System.currentTimeMillis();

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
            mGravity = event.values;
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
            mGeomagnetic = event.values;

        if (mGravity != null && mGeomagnetic != null) {
            float[] R = new float[9];
            float[] I = new float[9];
            if (SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic)) {
                float[] orientation = new float[3];
                SensorManager.getOrientation(R, orientation);
                float pitch = orientation[1];
                float roll = orientation[2];

                double diffRoll = 0.6;
                double diffPitch = 0.2;
                double valueRoll = 1.25;

                if ((abs(roll) <= valueRoll) && (newMillis > tiltMillis + 1500)) {
                    vibrateOnce((long) (150 / (abs(roll)) - 20));
                    tiltMillis = newMillis;
                    if (System.currentTimeMillis() - tiltFeedbackTime > 3000) {
                        speak("Winkel zu Niedrig");
                        tiltFeedbackTime = System.currentTimeMillis();
                    }
                }

                if ((abs(roll) >= valueRoll + diffRoll) && (newMillis > tiltMillis + 1500)) {
                    vibrateOnce((long) ((abs(roll) * 100) - 50));
                    tiltMillis = newMillis;
                    if (System.currentTimeMillis() - tiltFeedbackTime > 3000) {
                        speak("Winkel zu Hoch");
                        tiltFeedbackTime = System.currentTimeMillis();
                    }
                }

                double valuePitch = 0;
                if ((pitch <= valuePitch - diffPitch) && (newMillis > tiltMillis + 1500)) {
                    vibrateOnce((long) ((abs(pitch) * 1000) - 50));
                    tiltMillis = newMillis;
                    if (System.currentTimeMillis() - tiltFeedbackTime > 3000) {
                        speak("Zu weit nach rechts geneigt");
                        tiltFeedbackTime = System.currentTimeMillis();
                    }
                }
                if ((pitch >= valuePitch + diffPitch) && (newMillis > tiltMillis + 1500)) {
                    vibrateOnce((long) ((abs(pitch) * 1000) - 50));
                    tiltMillis = newMillis;
                    if (System.currentTimeMillis() - tiltFeedbackTime > 3000) {
                        speak("Zu weit nach links geneigt");
                        tiltFeedbackTime = System.currentTimeMillis();
                    }
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void vibrateOnce(long duration) {
        if (duration <= 0) return;
        duration = Math.min(duration, 1000);
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(duration);
        }
    }

    private void vibratePattern(long[] pattern) {
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(pattern, -1);
        }
    }

    private static float abs(float a) {
        return (a <= 0.0F) ? 0.0F - a : a;
    }
}
