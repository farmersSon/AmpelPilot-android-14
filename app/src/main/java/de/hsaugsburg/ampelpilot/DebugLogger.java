package de.hsaugsburg.ampelpilot;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Simple opt-in debug logger. Writes timestamped lines to a log file in the
 * app-scoped external files directory (no runtime storage permission needed on
 * Android 14). The file can be shared from the settings screen via a FileProvider.
 *
 * Logging is a no-op unless explicitly enabled in the settings.
 */
public final class DebugLogger {

    private static final String LOG_FILE_NAME = "ampelpilot.log";
    private static DebugLogger instance;

    private final File logFile;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private volatile boolean enabled = false;

    private DebugLogger(Context appContext) {
        File dir = appContext.getExternalFilesDir(null);
        if (dir == null) {
            dir = appContext.getFilesDir();
        }
        logFile = new File(dir, LOG_FILE_NAME);
    }

    public static synchronized DebugLogger get(Context context) {
        if (instance == null) {
            instance = new DebugLogger(context.getApplicationContext());
        }
        return instance;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public File getLogFile() {
        return logFile;
    }

    /** Append a timestamped, tagged line. No-op if logging is disabled. */
    public synchronized void log(String tag, String message) {
        if (!enabled) return;
        String line = dateFormat.format(new Date()) + " [" + tag + "] " + message;
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
            writer.println(line);
        } catch (Exception ignored) {
            // Silently ignore write failures
        }
    }

    /** Delete the log file. */
    public synchronized void clear() {
        try {
            if (logFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
            }
        } catch (Exception ignored) {
        }
    }
}
