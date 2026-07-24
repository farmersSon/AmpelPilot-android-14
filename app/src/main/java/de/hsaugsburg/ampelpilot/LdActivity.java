package de.hsaugsburg.ampelpilot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class LdActivity extends Activity implements CvCameraViewListener2, SensorEventListener {

    private static final String TAG = "OCVSample::Activity";
    private static final Scalar GREEN_RECT_COLOR = new Scalar(0, 255, 0, 255);
    private static final Scalar RED_RECT_COLOR = new Scalar(255, 0, 0, 255);
    public static final int JAVA_DETECTOR = 0;
    private SensorManager mSensorManager;

    private LightPeriod lightgreen = new LightPeriod();
    private LightPeriod lightred = new LightPeriod();
    long SytsemTime = System.currentTimeMillis();
    private TextToSpeech tts;


    private Mat mRgba;
    private File mCascadeFileGreen;
    private File mCascadeFileRed;
    private CascadeClassifier mJavaDetectorGreen;
    private CascadeClassifier mJavaDetectorRed;

    float[] mGravity;
    float[] mGeomagnetic;
    Sensor accelerometer;
    Sensor magnetometer;
    Vibrator v;

    static {
        OpenCVLoader.initDebug();
    }

    private String helpText = "Halten Sie das Handy im Landschaftsmodus. Falls Sie das Handy falsch halten wird es vibrieren und eine Sprachnachricht wird abgespielt.\n" +
            "\n" +
            "In den Settings können Sie die Werte zur Erkennung umstellen.\n" +
            "\n" +
            "Der Anbieter dieser App \u00fcbernimmt keine Haftung f\u00fcr Sach- und Personensch\u00e4den, welche durch die Nutzung von \u201eAmpel-Pilot\u201c entstehen.";


    private double scaleFactor;
    private int minNeighbours;


    private int mDetectorType = JAVA_DETECTOR;
    private String[] mDetectorName;

    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;


    private CameraBridgeViewBase mOpenCvCameraView;

    long millis = System.currentTimeMillis();

    private static boolean openCvInit;

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {

            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    Log.i(TAG, "OpenCV loaded successfully");
                    try {
                        // load cascade file from application resources
                        InputStream is = getResources().openRawResource(R.raw.green);
                        File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
                        mCascadeFileGreen = new File(cascadeDir, "cascade_green.xml");
                        FileOutputStream os = new FileOutputStream(mCascadeFileGreen);

                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                        is.close();
                        os.close();

                        // load cascade file from application resources
                        InputStream ise = getResources().openRawResource(R.raw.red);
                        File cascadeDirRed = getDir("cascade", Context.MODE_PRIVATE);
                        mCascadeFileRed = new File(cascadeDirRed, "cascade_red.xml");
                        FileOutputStream ose = new FileOutputStream(mCascadeFileRed);

                        while ((bytesRead = ise.read(buffer)) != -1) {
                            ose.write(buffer, 0, bytesRead);
                        }
                        ise.close();
                        ose.close();

                        mJavaDetectorGreen = new CascadeClassifier(mCascadeFileGreen.getAbsolutePath());
                        if (mJavaDetectorGreen.empty()) {
                            Log.e(TAG, "Failed to load cascade classifier");
                            mJavaDetectorGreen = null;
                        } else
                            Log.i(TAG, "Loaded cascade classifier from " + mCascadeFileGreen.getAbsolutePath());

                        mJavaDetectorRed = new CascadeClassifier(mCascadeFileRed.getAbsolutePath());
                        if (mJavaDetectorRed.empty()) {
                            Log.e(TAG, "Failed to load cascade classifier for red");
                            mJavaDetectorRed = null;
                        } else
                            Log.i(TAG, "Loaded cascade classifier from " + mCascadeFileRed.getAbsolutePath());

                        cascadeDir.delete();
                        cascadeDirRed.delete();

                    } catch (IOException e) {
                        e.printStackTrace();
                        Log.e(TAG, "Failed to load cascade. Exception thrown: " + e);
                    }
                    mOpenCvCameraView.enableFpsMeter();
                    mOpenCvCameraView.setCameraIndex(0);
                    mOpenCvCameraView.setClickable(false);

                    mOpenCvCameraView.enableView();
                }
                break;
                default: {
                    super.onManagerConnected(status);
                }
                break;
            }
        }
    };

    public LdActivity() {
        mDetectorName = new String[2];
        mDetectorName[JAVA_DETECTOR] = "Java";

        Log.i(TAG, "Instantiated new " + this.getClass());
    }


    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "called onCreate");
        super.onCreate(savedInstanceState);

        prefs = this.getSharedPreferences(
                "de.hsaugsburg.ampelpilot", Context.MODE_PRIVATE);
        editor = prefs.edit();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (prefs.getBoolean("firstStart", true)) {
            speak("TEST TEST TEST");
            editor.putBoolean("firstStart", false);
            editor.commit();

            AlertDialog.Builder dlgAlert = new AlertDialog.Builder(this);
            dlgAlert.setMessage(helpText);
            dlgAlert.setTitle("AmpelPilot");
            dlgAlert.setPositiveButton("OK", null);
            dlgAlert.setCancelable(true);
            dlgAlert.create().show();
        }

        setContentView(R.layout.trafficlights_detect_surface_view);
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        mOpenCvCameraView = (CameraBridgeViewBase) findViewById(R.id.tl_activity_surface_view);
        mOpenCvCameraView.setCvCameraViewListener(this);
        Button btnSettings = (Button) findViewById(R.id.settingsbtn);


        minNeighbours = prefs.getInt("MinN", 5);
        scaleFactor = prefs.getFloat("Scale", 2);
        lightgreen.setAmountint(prefs.getInt("Frames", 7));

        // Get vibrator - use VibratorManager on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v = vibratorManager.getDefaultVibrator();
        } else {
            v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                Intent nextScreen = new Intent(getApplicationContext(), SettingsActivity.class);
                startActivity(nextScreen);
                finish();
            }
        });

        mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
    }

    @Override
    protected void onStart() {
        super.onStart();
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int result = tts.setLanguage(Locale.GERMANY);
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e("TTS", "This Language is not supported");
                    }
                } else {
                    Log.e("TTS", "Initialization Failed!");
                }
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
        v.cancel();
    }


    @Override
    public void onResume() {
        super.onResume();
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (tts != null) {
            tts.shutdown();
        }
    }

    public void onDestroy() {
        super.onDestroy();
        mSensorManager.unregisterListener(this);
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
    }

    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
    }

    public void onCameraViewStopped() {
        mRgba.release();
    }

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        mRgba = inputFrame.rgba();

        float zoom = (float) 0.6;
        Size orig = mRgba.size();
        int offx = (int) (0.5 * (1.0 - zoom) * orig.width);
        int offy = (int) (0.5 * (1.0 - zoom) * orig.height);

        // crop the part, you want to zoom into:
        Mat cropped = mRgba.submat(offy, (int) orig.height - offy, offx, (int) orig.width - offx);

        // resize to original:
        Imgproc.resize(cropped, cropped, orig);

        MatOfRect green = new MatOfRect();
        MatOfRect red = new MatOfRect();

        if (mDetectorType == JAVA_DETECTOR) {
            if (mJavaDetectorGreen != null) {
                mJavaDetectorGreen.detectMultiScale(cropped, green, scaleFactor, minNeighbours, 0,
                        new Size(20, 40), new Size(200, 400));
            }
            if (mJavaDetectorRed != null) {
                mJavaDetectorRed.detectMultiScale(cropped, red, scaleFactor, minNeighbours, 0,
                        new Size(20, 40), new Size(200, 400));
            }
        } else {
            Log.e(TAG, "Detection method is not selected!");
        }

        Rect[] redArray = red.toArray();
        lightred.addpoint(redArray);
        if (lightred.checklight()) {
            if ((System.currentTimeMillis() - SytsemTime) > 2000) {
                speak("Warte!");
                SytsemTime = System.currentTimeMillis();
            }
            Log.w("step", "onCameraFrame:  #################### Red wurde erkannt");
        }
        for (int i = 0; i < redArray.length; i++) {
            Imgproc.rectangle(cropped, redArray[i].tl(), redArray[i].br(),
                    RED_RECT_COLOR, 3);
        }


        Rect[] greenArray = green.toArray();
        lightgreen.addpoint(greenArray);
        if (lightgreen.checklight()) {
            Log.w("step", "onCameraFrame: " + System.currentTimeMillis() + "   " + SytsemTime);
            if ((System.currentTimeMillis() - SytsemTime) > 2000) {
                speak("Es ist Grün");
                SytsemTime = System.currentTimeMillis();
            }
            Log.w("step", "onCameraFrame:  #################### Grün wurde erkannt");
        }
        for (int i = 0; i < greenArray.length; i++) {
            Imgproc.rectangle(cropped, greenArray[i].tl(), greenArray[i].br(),
                    GREEN_RECT_COLOR, 3);
        }
        return cropped;
    }


    private void speak(String text) {
        if (tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

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
                float azimut = orientation[0];
                float pitch = orientation[1];
                float roll = orientation[2];


                double diffRoll = 0.6;
                double diffPitch = 0.2;


                double valueRoll = 1.25;
                if ((abs(roll) <= valueRoll) && (newMillis > millis + 1500)) {
                    float t = (150 / (abs(roll)) - 20);
                    vibrate(t);
                    millis = newMillis;
                    if ((System.currentTimeMillis() - SytsemTime) > 3000) {
                        speak("Winkel zu Niedrig");
                        SytsemTime = System.currentTimeMillis();
                    }
                }

                if ((abs(roll) >= valueRoll + diffRoll) && (newMillis > millis + 1500)) {
                    float t = ((abs(roll) * 100) - 50);
                    vibrate(t);
                    millis = newMillis;
                    if ((System.currentTimeMillis() - SytsemTime) > 3000) {
                        speak("Winkel zu Hoch");
                        SytsemTime = System.currentTimeMillis();
                    }
                }

                double valuePitch = 0;
                if (((pitch <= valuePitch - diffPitch)) && (newMillis > millis + 1500)) {
                    float t = ((abs(pitch) * 1000) - 50);
                    vibrate(t);
                    millis = newMillis;
                    if ((System.currentTimeMillis() - SytsemTime) > 3000) {
                        speak("Zu weit nach rechts geneigt");
                        SytsemTime = System.currentTimeMillis();
                    }
                }
                if (((pitch >= valuePitch + diffPitch)) && (newMillis > millis + 1500)) {
                    float t = ((abs(pitch) * 1000) - 50);
                    vibrate(t);
                    millis = newMillis;
                    if ((System.currentTimeMillis() - SytsemTime) > 3000) {
                        speak("Zu weit nach links geneigt");
                        SytsemTime = System.currentTimeMillis();
                    }
                }
            }
        }
    }

    private void vibrate(float t) {
        // limit to max 1000 milliseconds
        t = (t <= 1000) ? t : 1000;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot((long) t, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate((long) t);
        }
    }

    public static float abs(float a) {
        return (a <= 0.0F) ? 0.0F - a : a;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
