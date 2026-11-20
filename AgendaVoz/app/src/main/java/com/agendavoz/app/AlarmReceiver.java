package com.agendavoz.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Llega la hora de un recordatorio: arranca el servicio que lo dice en voz alta. */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Alarmas.crearCanal(ctx);
        long id = intent.getLongExtra(Alarmas.EXTRA_ID, Long.MIN_VALUE);
        boolean previo = intent.getBooleanExtra(Alarmas.EXTRA_PREVIO, false);
        int reavisos = intent.getIntExtra(Alarmas.EXTRA_REAVISOS, 0);
        if (id == Long.MIN_VALUE) return;

        if (id != Alarmas.ID_PRUEBA) {
            Evento e = DbHelper.get(ctx).obtener(id);
            if (e == null || e.hecho) return;
        }

        Intent s = new Intent(ctx, RecordatorioService.class);
        s.setAction(RecordatorioService.ACCION_INICIAR);
        s.putExtra(Alarmas.EXTRA_ID, id);
        s.putExtra(Alarmas.EXTRA_PREVIO, previo);
        s.putExtra(Alarmas.EXTRA_REAVISOS, reavisos);
        try {
            ctx.startForegroundService(s);
        } catch (Exception ex) {
            // Si el sistema no deja arrancar el servicio, al menos se muestra una notificación.
            notificacionDeRespaldo(ctx, id);
        }
    }

    private static void notificacionDeRespaldo(Context ctx, long id) {
        Evento e = id == Alarmas.ID_PRUEBA ? null : DbHelper.get(ctx).obtener(id);
        String titulo = e != null ? e.titulo : "Recordatorio de prueba";
        Intent abrir = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, Alarmas.CANAL)
                .setSmallIcon(R.drawable.ic_notificacion)
                .setContentTitle(titulo)
                .setContentText("Es la hora de tu recordatorio")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(RecordatorioService.idNotificacion(id), n);
    }
}
