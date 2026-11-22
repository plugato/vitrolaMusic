package app.vitrola.music;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnóstico do Android Auto (temporário): o que o carro pediu aparece num aviso rápido
 * (toast), numa notificação ("Vitrola no carro") e em Configurações → Diagnóstico do Android
 * Auto (arquivo `files/car-debug.log`), para descobrir o que falha sem cabo e adb.
 */
final class CarDebug {

    private static final String TAG = "VitrolaCar";
    private static final String CHANNEL_ID = "vitrola_car_debug";
    private static final int NOTIFICATION_ID = 4711;
    private static final int MAX_LINES = 12;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final long MAX_FILE_BYTES = 64 * 1024;

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "car-debug.log");
    }

    /** Histórico salvo (o mais recente no fim). */
    static synchronized String read(Context ctx) {
        try {
            File f = file(ctx);
            return f.isFile() ? new String(readAll(f), StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized void clear(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        file(ctx).delete();
        lines.clear();
    }

    /** Só grava no histórico (sem toast nem notificação). */
    static synchronized void note(Context ctx, String message) {
        Log.i(TAG, message);
        append(ctx, new java.text.SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(new Date()) + " " + message);
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void append(Context ctx, String line) {
        try {
            File f = file(ctx);
            if (f.length() > MAX_FILE_BYTES) {
                byte[] all = readAll(f);
                try (FileOutputStream out = new FileOutputStream(f, false)) {
                    out.write(all, all.length / 2, all.length - all.length / 2);
                }
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Histórico falhou: " + e.getMessage());
        }
    }

    private CarDebug() {}

    static synchronized void log(Context ctx, String message) {
        Log.i(TAG, message);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        lines.addFirst(time + " " + message);
        append(ctx, new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(new Date()) + " " + message);
        Context appCtx = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(appCtx, "Vitrola/carro: " + message, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
                // sem toast
            }
        });
        while (lines.size() > MAX_LINES) lines.removeLast();
        try {
            Context app = ctx.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Diagnóstico do Android Auto", NotificationManager.IMPORTANCE_LOW));
            }
            String text = android.text.TextUtils.join("\n", lines);
            NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Vitrola no carro (diagnóstico)")
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true);
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException e) {
            // Sem permissão de notificação: fica só no logcat.
        } catch (Exception e) {
            Log.w(TAG, "Diagnóstico falhou: " + e.getMessage());
        }
    }
}
