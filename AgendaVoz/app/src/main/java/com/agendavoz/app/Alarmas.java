package com.agendavoz.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import java.util.List;

/** Programa y cancela las alarmas locales. Nunca dependen de internet. */
public final class Alarmas {

    public static final String CANAL = "recordatorios_alarma";
    public static final String EXTRA_ID = "evento_id";
    public static final String EXTRA_PREVIO = "previo";
    public static final String EXTRA_TITULO = "titulo_prueba";
    public static final long ID_PRUEBA = -1;

    private Alarmas() {
    }

    public static void crearCanal(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Recordatorios", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Avisos de reuniones y tareas");
        Uri sonido = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sonido == null) sonido = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ch.setSound(sonido, aa);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    /** true si el sistema permite alarmas exactas (en Android 11 siempre es true). */
    public static boolean puedeExactas(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        return am != null && am.canScheduleExactAlarms();
    }

    public static void programar(Context ctx, Evento e) {
        cancelar(ctx, e.id);
        if (e.hecho) return;
        long ahora = System.currentTimeMillis();
        if (e.cuando > ahora) {
            poner(ctx, e.cuando, pendiente(ctx, e.id, false, null));
        }
        long previo = e.cuando - e.avisoMin * 60_000L;
        if (e.avisoMin > 0 && previo > ahora) {
            poner(ctx, previo, pendiente(ctx, e.id, true, null));
        }
    }

    public static void cancelar(Context ctx, long id) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.cancel(pendiente(ctx, id, false, null));
        am.cancel(pendiente(ctx, id, true, null));
    }

    public static void reprogramarTodo(Context ctx) {
        List<Evento> lista = DbHelper.get(ctx).pendientesDesde(System.currentTimeMillis());
        for (Evento e : lista) programar(ctx, e);
    }

    /** Alarma de prueba en 1 minuto para verificar que el teléfono no la bloquea. */
    public static void probarEnUnMinuto(Context ctx) {
        long t = System.currentTimeMillis() + 60_000L;
        poner(ctx, t, pendiente(ctx, ID_PRUEBA, false, "Alarma de prueba: si ves esto, los recordatorios funcionan."));
    }

    private static void poner(Context ctx, long cuando, PendingIntent pi) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        if (puedeExactas(ctx)) {
            Intent abrir = new Intent(ctx, MainActivity.class);
            PendingIntent mostrar = PendingIntent.getActivity(ctx, 0, abrir,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // setAlarmClock es el tipo de alarma más fiable: el sistema no la retrasa por ahorro de batería.
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(cuando, mostrar), pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
        }
    }

    private static PendingIntent pendiente(Context ctx, long id, boolean previo, String tituloPrueba) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("com.agendavoz.app.ALARMA." + id + (previo ? ".PREVIO" : ""));
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_PREVIO, previo);
        if (tituloPrueba != null) i.putExtra(EXTRA_TITULO, tituloPrueba);
        int codigo = (int) (id * 2 + (previo ? 1 : 0));
        return PendingIntent.getBroadcast(ctx, codigo, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
