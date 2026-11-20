package com.agendavoz.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

/** Programa y cancela las alarmas locales. Nunca dependen de internet. */
public final class Alarmas {

    /** Canal silencioso: el aviso lo da la voz, no un sonido. */
    public static final String CANAL = "recordatorios_voz";
    private static final String CANAL_VIEJO = "recordatorios_alarma";

    public static final String EXTRA_ID = "evento_id";
    public static final String EXTRA_PREVIO = "previo";
    public static final String EXTRA_REAVISOS = "reavisos";
    public static final long ID_PRUEBA = -1;

    private Alarmas() {
    }

    public static void crearCanal(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CANAL_VIEJO) != null) nm.deleteNotificationChannel(CANAL_VIEJO);
        if (nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Recordatorios hablados", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Reuniones y tareas: la app las dice en voz alta");
        ch.setSound(null, null);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 400, 200, 400});
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    /** true si el sistema permite alarmas exactas (en Android 11 siempre es true). */
    public static boolean puedeExactas(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        return am != null && am.canScheduleExactAlarms();
    }

    public static void programar(Context ctx, Evento e) {
        programar(ctx, e, 0);
    }

    /** Programa el aviso principal (o el pospuesto) y el aviso previo. */
    public static void programar(Context ctx, Evento e, int reavisos) {
        cancelar(ctx, e.id);
        if (e.hecho) return;
        long ahora = System.currentTimeMillis();
        if (e.pospuesto > ahora) {
            poner(ctx, e.pospuesto, pendiente(ctx, e.id, false, reavisos));
            return; // ya pospuesto: no hay aviso previo
        }
        if (e.cuando > ahora) {
            poner(ctx, e.cuando, pendiente(ctx, e.id, false, 0));
        }
        long previo = e.cuando - e.avisoMin * 60_000L;
        if (e.avisoMin > 0 && previo > ahora) {
            poner(ctx, previo, pendiente(ctx, e.id, true, 0));
        }
    }

    public static void cancelar(Context ctx, long id) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.cancel(pendiente(ctx, id, false, 0));
        am.cancel(pendiente(ctx, id, true, 0));
    }

    public static void reprogramarTodo(Context ctx) {
        List<Evento> lista = DbHelper.get(ctx).pendientesConAlarmaFutura(System.currentTimeMillis());
        for (Evento e : lista) programar(ctx, e);
    }

    /** Recordatorio de prueba en 1 minuto para verificar que el teléfono no lo bloquea. */
    public static void probarEnUnMinuto(Context ctx) {
        poner(ctx, System.currentTimeMillis() + 60_000L, pendiente(ctx, ID_PRUEBA, false, 0));
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

    private static PendingIntent pendiente(Context ctx, long id, boolean previo, int reavisos) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("com.agendavoz.app.ALARMA." + id + (previo ? ".PREVIO" : ""));
        i.putExtra(EXTRA_ID, id);
        i.putExtra(EXTRA_PREVIO, previo);
        i.putExtra(EXTRA_REAVISOS, reavisos);
        int codigo = (int) (id * 2 + (previo ? 1 : 0));
        return PendingIntent.getBroadcast(ctx, codigo, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
