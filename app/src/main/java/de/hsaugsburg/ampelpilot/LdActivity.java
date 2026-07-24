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
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LdActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "AmpelPilot::LdActivity";

    private SensorManager mSensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private Vibrator v;

    private LightPeriod lightgreen = new LightPeriod();
    private LightPeriod lightred = new LightPeriod();
    private long systemTime = System.currentTimeMillis();
    private TextToSpeech tts;

    private CascadeClassifier mJavaDetectorGreen;
    private CascadeClassifier mJavaDetectorRed;

    private float[] mGravity;
    private float[] mGeomagnetic;

    private double scaleFactor;
    private int minNeighbours;

    private SharedPreferences prefs;

    private PreviewView previewView;
    private DetectionOverlayView overlayView;
    private ExecutorService analysisExecutor;

    private long millis = System.currentTimeMillis();

    private String helpText = "Halten Sie das Handy hoch oder quer und richten Sie die Kamera auf die Ampel. " +
            "Falls Sie das Handy falsch halten wird es vibrieren und eine Sprachnachricht wird abgespielt.\n" +
            "\n" +
            "In den Settings k\u00f6nnen Sie die Werte zur Erkennung umstellen.\n" +
            "\n" +
            "Der Anbieter dieser App \u00fcbernimmt keine Haftung f\u00fcr Sach- und Personensch\u00e4den, " +
            "welche durch die Nutzung von \u201eAmpel-Pilot\u201c entstehen.";

    static {
        OpenCVLoader.initDebug();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("de.hsaugsburg.ampelpilot", Context.MODE_PRIVATE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

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

        // Sensor setup
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        // Detection parameters
        minNeighbours = prefs.getInt("MinN", 5);
        scaleFactor = prefs.getFloat("Scale", 2);
        lightgreen.setAmountint(prefs.getInt("Frames", 7));

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

        // Load cascade classifiers
        loadCascadeClassifiers();

        // Analysis executor (single thread for frame processing)
        analysisExecutor = Executors.newSingleThreadExecutor();

        // Start camera
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
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
    }

    private void loadCascadeClassifiers() {
        try {
            File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);

            // Green classifier
            InputStream is = getResources().openRawResource(R.raw.green);
            File cascadeFileGreen = new File(cascadeDir, "cascade_green.xml");
            copyStreamToFile(is, cascadeFileGreen);
            is.close();

            mJavaDetectorGreen = new CascadeClassifier(cascadeFileGreen.getAbsolutePath());
            if (mJavaDetectorGreen.empty()) {
                Log.e(TAG, "Failed to load green cascade classifier");
                mJavaDetectorGreen = null;
            }

            // Red classifier
            InputStream ise = getResources().openRawResource(R.raw.red);
            File cascadeFileRed = new File(cascadeDir, "cascade_red.xml");
            copyStreamToFile(ise, cascadeFileRed);
            ise.close();

            mJavaDetectorRed = new CascadeClassifier(cascadeFileRed.getAbsolutePath());
            if (mJavaDetectorRed.empty()) {
                Log.e(TAG, "Failed to load red cascade classifier");
                mJavaDetectorRed = null;
            }

        } catch (IOException e) {
            Log.e(TAG, "Failed to load cascade classifiers", e);
        }
    }

    private void copyStreamToFile(InputStream is, File outFile) throws IOException {
        FileOutputStream os = new FileOutputStream(outFile);
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            os.write(buffer, 0, bytesRead);
        }
        os.close();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases(cameraProvider);
            } catch (Exception e) {
                Log.e(TAG, "Camera initialization failed", e);
                Toast.makeText(this, "Kamera konnte nicht gestartet werden", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        // Preview
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Image Analysis
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();

        imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeFrame);

        // Select back camera
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        // Unbind all and rebind
        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
    }

    /**
     * Process each camera frame for traffic light detection.
     * Runs on the analysis executor thread.
     */
    private void analyzeFrame(@NonNull ImageProxy image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();

            // Get RGBA data from the image
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            int rowStride = image.getPlanes()[0].getRowStride();
            int pixelStride = image.getPlanes()[0].getPixelStride();

            // Create OpenCV Mat from RGBA buffer
            Mat rgba = new Mat(height, width, CvType.CV_8UC4);
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);

            // Handle row stride padding if needed
            if (rowStride == width * pixelStride) {
                rgba.put(0, 0, data);
            } else {
                // Copy row by row to handle stride
                for (int row = 0; row < height; row++) {
                    int offset = row * rowStride;
                    byte[] rowData = new byte[width * pixelStride];
                    System.arraycopy(data, offset, rowData, 0, width * pixelStride);
                    rgba.row(row).put(0, 0, rowData);
                }
            }

            // Apply zoom crop (same as original)
            float zoom = 0.6f;
            int offx = (int) (0.5 * (1.0 - zoom) * width);
            int offy = (int) (0.5 * (1.0 - zoom) * height);
            Mat cropped = rgba.submat(offy, height - offy, offx, width - offx);

            Size croppedSize = cropped.size();
            Mat resized = new Mat();
            Imgproc.resize(cropped, resized, new Size(width, height));

            // Run detection
            MatOfRect greenDetections = new MatOfRect();
            MatOfRect redDetections = new MatOfRect();

            if (mJavaDetectorGreen != null) {
                mJavaDetectorGreen.detectMultiScale(resized, greenDetections, scaleFactor, minNeighbours, 0,
                        new Size(20, 40), new Size(200, 400));
            }
            if (mJavaDetectorRed != null) {
                mJavaDetectorRed.detectMultiScale(resized, redDetections, scaleFactor, minNeighbours, 0,
                        new Size(20, 40), new Size(200, 400));
            }

            // Process red detections
            Rect[] redArray = redDetections.toArray();
            lightred.addpoint(redArray);
            if (lightred.checklight()) {
                if ((System.currentTimeMillis() - systemTime) > 2000) {
                    speak("Warte!");
                    systemTime = System.currentTimeMillis();
                }
            }

            // Process green detections
            Rect[] greenArray = greenDetections.toArray();
            lightgreen.addpoint(greenArray);
            if (lightgreen.checklight()) {
                if ((System.currentTimeMillis() - systemTime) > 2000) {
                    speak("Es ist Gr\u00fcn");
                    systemTime = System.currentTimeMillis();
                }
            }

            // Convert detection rects to overlay coordinates
            List<RectF> greenRects = new ArrayList<>();
            for (Rect r : greenArray) {
                greenRects.add(new RectF((float) r.x, (float) r.y,
                        (float) (r.x + r.width), (float) (r.y + r.height)));
            }
            List<RectF> redRects = new ArrayList<>();
            for (Rect r : redArray) {
                redRects.add(new RectF((float) r.x, (float) r.y,
                        (float) (r.x + r.width), (float) (r.y + r.height)));
            }

            // Update overlay on UI thread
            overlayView.setDetections(greenRects, redRects, width, height);

            // Release mats
            rgba.release();
            resized.release();

        } finally {
            image.close();
        }
    }

    private void speak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    // ---- Sensor tilt detection (same logic as original) ----

    @Override
    public void onSensorChanged(SensorEvent event) {
        long newMillis = System.currentTimeMillis();

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
            mGravity = event.values;
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
            mGeomagnetic = event.values;

        if (mGravity != null && mGeomagnetic != null) {
            float R[] = new float[9];
            float I[] = new float[9];
            boolean success = SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic);
            if (success) {
                float orientation[] = new float[3];
                SensorManager.getOrientation(R, orientation);
                float pitch = orientation[1];
                float roll = orientation[2];

                double diffRoll = 0.6;
                double diffPitch = 0.2;
                double valueRoll = 1.25;

                // Roll too low (pointing at ground)
                if ((abs(roll) <= valueRoll) && (newMillis > millis + 1500)) {
                    float t = (150 / (abs(roll)) - 20);
                    vibrate(t);
                    millis = newMillis;
                    if ((System.currentTimeMillis() - systemTime) > 3000) {
                        speak("Winkel zu Niedrig");
                        systemTime = System.currentTimeMillis();
                    }
                }

                // Roll too high (pointing at sky)
                if ((abs(roll) >= valueRoll + diffRoll) && (newMillis > millis + 1500)) {
                    float t = ((abs(roll) * 100) - 50);
                    vibrate(t);
                    millis = newMillis;
                    if ((System.currentTimeMillis() - systemTime) > 3000) {
                        speak("Winkel zu Hoch");
                        systemTime = System.currentTimeMillis();
                    }
                }

                // Pitch tilted right
                double valuePitch = 0;
                if ((pitch <= valuePitch - diffPitch) && (newMillis > millis + 1500)) {
                    float t = ((abs(pitch) * 1000) - 50);
                    vibrate(t);
                    millis = newMillis;
                    if ((System.currentTimeMillis() - systemTime) > 3000) {
                        speak("Zu weit nach rechts geneigt");
                        systemTime = System.currentTimeMillis();
                    }
                }

                // Pitch tilted left
                if ((pitch >= valuePitch + diffPitch) && (newMillis > millis + 1500)) {
                    float t = ((abs(pitch) * 1000) - 50);
                    vibrate(t);
                    millis = newMillis;
                    if ((System.currentTimeMillis() - systemTime) > 3000) {
                        speak("Zu weit nach links geneigt");
                        systemTime = System.currentTimeMillis();
                    }
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void vibrate(float t) {
        t = Math.min(t, 1000f);
        if (t <= 0) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot((long) t, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate((long) t);
        }
    }

    private static float abs(float a) {
        return (a <= 0.0F) ? 0.0F - a : a;
    }
}
