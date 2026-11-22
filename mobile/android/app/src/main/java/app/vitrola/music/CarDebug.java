package app.vitrola.music;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnóstico do Android Auto (temporário): o que o carro pediu aparece numa notificação no
 * celular ("Vitrola no carro"), para descobrir o que falha sem precisar de cabo e adb.
 */
final class CarDebug {

    private static final String TAG = "VitrolaCar";
    private static final String CHANNEL_ID = "vitrola_car_debug";
    private static final int NOTIFICATION_ID = 4711;
    private static final int MAX_LINES = 12;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();

    private CarDebug() {}

    static synchronized void log(Context ctx, String message) {
        Log.i(TAG, message);
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        lines.addFirst(time + " " + message);
        while (lines.size() > MAX_LINES) lines.removeLast();
        try {
            Context app = ctx.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Diagnóstico do Android Auto", NotificationManager.IMPORTANCE_LOW));
            }
            String text = String.join("\n", lines);
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
