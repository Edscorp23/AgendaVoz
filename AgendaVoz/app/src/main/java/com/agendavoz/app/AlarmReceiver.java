package com.agendavoz.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.time.format.DateTimeFormatter;

/** Muestra la notificación con sonido cuando llega la hora de un recordatorio. */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Alarmas.crearCanal(ctx);
        long id = intent.getLongExtra(Alarmas.EXTRA_ID, Long.MIN_VALUE);
        boolean previo = intent.getBooleanExtra(Alarmas.EXTRA_PREVIO, false);

        String titulo;
        String texto;
        if (id == Alarmas.ID_PRUEBA) {
            titulo = "Agenda Voz";
            texto = intent.getStringExtra(Alarmas.EXTRA_TITULO);
            if (texto == null) texto = "Alarma de prueba";
        } else {
            Evento e = DbHelper.get(ctx).obtener(id);
            if (e == null || e.hecho) return;
            String hora = e.fechaHora().format(DateTimeFormatter.ofPattern("HH:mm"));
            String tipo = e.esReunion() ? "Reunión" : "Tarea";
            if (previo) {
                titulo = "En " + e.avisoMin + " min: " + e.titulo;
                texto = tipo + " a las " + hora;
            } else {
                titulo = e.titulo;
                texto = tipo + " ahora (" + hora + ")";
            }
            if (!e.participantes.isEmpty()) texto += " · con " + e.participantes;
        }

        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Intent abrir = new Intent(ctx, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) (id % 100000), abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(ctx, Alarmas.CANAL)
                .setSmallIcon(R.drawable.ic_notificacion)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setShowWhen(true)
                .build();

        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify((int) (id * 2 + (previo ? 1 : 0)), n);
    }
}
