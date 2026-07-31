package de.hsaugsburg.ampelpilot;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Settings screen. Lets the user adjust the detection stability window - how many
 * consecutive frames must agree on a light phase before it is announced. A higher
 * value is more reliable but slower to react; a lower value reacts faster but may
 * produce more false positives.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final int START_VALUE_FRAMES = 1;

    private TextView textFrames;
    private SeekBar seekBarFrames;
    private SwitchCompat switchTiltPause;
    private SwitchCompat switchTorch;
    private SwitchCompat switchLogging;

    private final String helpText = "AmpelPilot erkennt rote und gr\u00fcne Fu\u00dfg\u00e4ngerampeln \u00fcber die Kamera " +
            "und teilt Ihnen per Sprachausgabe und Vibration mit, welche Phase aktiv ist.\n" +
            "\n" +
            "Halten Sie das Handy hoch oder quer und richten Sie die Kamera auf die Ampel.\n" +
            "\n" +
            "Benutzen Sie diese App nur als zus\u00e4tzliche Hilfe! Verlassen Sie sich stets auf Ihre eigene Wahrnehmung!";

    private final String helpTextFrames = "Legt fest, wie viele aufeinanderfolgende Kamerabilder die gleiche " +
            "Ampelphase zeigen m\u00fcssen, bevor sie angesagt wird.\n" +
            "\nEin h\u00f6herer Wert ist zuverl\u00e4ssiger, reagiert aber langsamer.\n" +
            "Ein niedrigerer Wert reagiert schneller, kann aber mehr Fehlerkennungen ausl\u00f6sen.\n" +
            "\nStandard: 4";

    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("de.hsaugsburg.ampelpilot", Context.MODE_PRIVATE);
        editor = prefs.edit();

        seekBarFrames = findViewById(R.id.seekBar_Frames);
        textFrames = findViewById(R.id.text_Frames);
        switchTiltPause = findViewById(R.id.switch_TiltPause);
        switchTorch = findViewById(R.id.switch_Torch);
        switchLogging = findViewById(R.id.switch_Logging);

        seekBarFrames.setProgress(prefs.getInt("Frames", 4) - START_VALUE_FRAMES);
        updateFramesText(seekBarFrames.getProgress());

        switchTiltPause.setChecked(prefs.getBoolean("tilt_pause_inference", false));
        switchTorch.setChecked(prefs.getBoolean("use_torch", true));
        switchLogging.setChecked(prefs.getBoolean("debug_logging", false));

        seekBarFrames.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateFramesText(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        Button btnGo = findViewById(R.id.btnGo);
        btnGo.setOnClickListener(v -> {
            editor.putInt("Frames", seekBarFrames.getProgress() + START_VALUE_FRAMES);
            editor.putBoolean("tilt_pause_inference", switchTiltPause.isChecked());
            editor.putBoolean("use_torch", switchTorch.isChecked());
            editor.putBoolean("debug_logging", switchLogging.isChecked());
            editor.apply();
            // Apply logging state immediately
            DebugLogger.get(this).setEnabled(switchLogging.isChecked());
            startActivity(new Intent(getApplicationContext(), LdActivity.class));
            finish();
        });

        Button btnHelp = findViewById(R.id.btnHelp);
        btnHelp.setOnClickListener(v -> showDialog("Hilfe zu Ampel-Pilot", helpText));

        Button btnHelpFrames = findViewById(R.id.helpButtonFrames);
        btnHelpFrames.setOnClickListener(v -> showDialog("Dauer bis zur Erkennung", helpTextFrames));

        Button btnShareLog = findViewById(R.id.btnShareLog);
        btnShareLog.setOnClickListener(v -> shareLog());
    }

    private void shareLog() {
        File logFile = DebugLogger.get(this).getLogFile();
        if (logFile == null || !logFile.exists() || logFile.length() == 0) {
            Toast.makeText(this,
                    "Kein Protokoll vorhanden. Aktivieren Sie zuerst die Protokollierung.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", logFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "AmpelPilot Protokoll");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.share_log)));
        } catch (Exception e) {
            Toast.makeText(this, "Protokoll konnte nicht geteilt werden.", Toast.LENGTH_LONG).show();
        }
    }

    private void updateFramesText(int progress) {
        int value = progress + START_VALUE_FRAMES;
        textFrames.setText(getString(R.string.Text_SeekBar) + value);
    }

    private void showDialog(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setCancelable(true)
                .show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }
}
