package de.hsaugsburg.ampelpilot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private TextView text_Frames;
    private TextView text_Scale;
    private TextView text_MinN;
    private SeekBar seekBar_Frames;
    private SeekBar seekBar_MinN;
    private SeekBar seekBar_Scale;

    private int startValue_Frames = 3;
    private int startValue_Scale = 1;
    private int startValue_MinN = 3;

    private String helpText = "Bitte halten Sie das Handy im Landschaftsmodus und halten Sie die Kamera Richtung Fußgängerampel.\n" +
            "\n" +
            "Um die Fußgängerampel wird ein roter oder grüner Kasten gezeichnet und " +
            "eine Stimme teilt Ihnen mit, ob die Fußgängerampel rot oder grün ist.\n" +
            "\n" +
            "Falls Sie das Handy falsch halten, wird es vibrieren und eine Sprachnachricht abspielen.\n" +
            "\n" +
            "In den Settings können Sie die Werte zur Erkennung umstellen. ";

    private String helpTextScale = "Standard Wert: 2\n" + "\nJe kleiner der Wert ist, umso genauer wird nach einer Fußgängerampel gesucht.\n"
            + "Allerdings wird die App dadurch langsamer.";

    private String helpTextMinN = "Standard Wert: 5\n" + "\nJe größer der Wert ist, umso genauer muss die App eine Fußgängerampel erkennen.\n"
            + "";

    private String helpTextFrames = "Standard Wert: 5\n" + "\nWert gibt an, wie oft eine Fußgängerampel erkannt werden muss," +
            " bevor ein akustisches Signal ausgegeben wird.\n";
    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = this.getSharedPreferences(
                "de.hsaugsburg.ampelpilot", Context.MODE_PRIVATE);
        editor = prefs.edit();

        seekBar_Frames = (SeekBar) findViewById(R.id.seekBar_Frames);
        seekBar_Scale = (SeekBar) findViewById(R.id.seekBar_Scale);
        seekBar_MinN = (SeekBar) findViewById(R.id.seekBar_MinN);

        seekBar_Frames.setProgress(prefs.getInt("Frames", 7) - startValue_Frames);
        seekBar_Scale.setProgress((int) ((prefs.getFloat("Scale", 2) - startValue_Scale) * 10));
        seekBar_MinN.setProgress(prefs.getInt("MinN", 5) - startValue_MinN);

        text_Frames = (TextView) findViewById(R.id.text_Frames);
        text_Scale = (TextView) findViewById(R.id.text_Scale);
        text_MinN = (TextView) findViewById(R.id.text_MinN);

        text_Frames.setText(getString(R.string.Text_SeekBar) + String.valueOf(seekBar_Frames.getProgress() + startValue_Frames));
        text_Scale.setText(getString(R.string.Text_SeekBar) +
                (roundFloat(getConvertedValue(seekBar_Scale.getProgress()), 2) + startValue_Scale));
        text_MinN.setText(getString(R.string.Text_SeekBar) + String.valueOf(seekBar_MinN.getProgress() + startValue_MinN));

        seekBar_Scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (progress == 0) {
                    progress = 1;
                }
                text_Scale.setText(getString(R.string.Text_SeekBar) + roundFloat(getConvertedValue(progress) + startValue_Scale, 2));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekBar_Frames.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                text_Frames.setText(getString(R.string.Text_SeekBar) + String.valueOf(progress + startValue_Frames));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekBar_MinN.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                text_MinN.setText(getString(R.string.Text_SeekBar) + String.valueOf(progress + startValue_MinN));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        Button btnGo = (Button) findViewById(R.id.btnGo);
        btnGo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                Intent nextScreen = new Intent(getApplicationContext(), LdActivity.class);
                editor.putFloat("Scale", roundFloat(getConvertedValue(seekBar_Scale.getProgress()) + startValue_Scale, 2));
                editor.putInt("Frames", seekBar_Frames.getProgress() + startValue_Frames);
                editor.putInt("MinN", seekBar_MinN.getProgress() + startValue_MinN);
                editor.commit();
                startActivity(nextScreen);
                finish();
            }
        });

        Button btnHelp = (Button) findViewById(R.id.btnHelp);
        btnHelp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                AlertDialog.Builder dlgAlert = new AlertDialog.Builder(SettingsActivity.this);
                dlgAlert.setMessage(helpText);
                dlgAlert.setTitle("Hilfe zu Ampel-Pilot");
                dlgAlert.setPositiveButton("OK", null);
                dlgAlert.setCancelable(true);
                dlgAlert.create().show();
            }
        });

        Button btnHelpFrames = (Button) findViewById(R.id.helpButtonFrames);
        btnHelpFrames.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                AlertDialog.Builder dlgAlert = new AlertDialog.Builder(SettingsActivity.this);
                dlgAlert.setMessage(helpTextFrames);
                dlgAlert.setTitle("Frames");
                dlgAlert.setPositiveButton("OK", null);
                dlgAlert.setCancelable(true);
                dlgAlert.create().show();
            }
        });

        Button btnHelpMinN = (Button) findViewById(R.id.helpButtonMinN);
        btnHelpMinN.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                AlertDialog.Builder dlgAlert = new AlertDialog.Builder(SettingsActivity.this);
                dlgAlert.setMessage(helpTextMinN);
                dlgAlert.setTitle("MinNeighbours");
                dlgAlert.setPositiveButton("OK", null);
                dlgAlert.setCancelable(true);
                dlgAlert.create().show();
            }
        });

        Button btnHelpScale = (Button) findViewById(R.id.helpButtonScale);
        btnHelpScale.setOnClickListener(new View.OnClickListener() {
            public void onClick(View arg0) {
                AlertDialog.Builder dlgAlert = new AlertDialog.Builder(SettingsActivity.this);
                dlgAlert.setMessage(helpTextScale);
                dlgAlert.setTitle("ScaleFactor");
                dlgAlert.setPositiveButton("OK", null);
                dlgAlert.setCancelable(true);
                dlgAlert.create().show();
            }
        });
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }

    private float roundFloat(final float number, final int decimalPlaces) {
        float precision = 1.0F;
        for (int i = 0; i < decimalPlaces; i++, precision *= 10) ;
        return ((int) (number * precision + 0.5) / precision);
    }

    public float getConvertedValue(int intVal) {
        float floatVal = 0.0f;
        floatVal = 0.1f * intVal;
        return floatVal;
    }
}
