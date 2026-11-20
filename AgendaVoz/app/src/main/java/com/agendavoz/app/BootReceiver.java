package com.agendavoz.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Al reiniciar el teléfono (o cambiar la hora) se vuelven a programar todas las alarmas. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult resultado = goAsync();
        new Thread(() -> {
            try {
                Alarmas.crearCanal(ctx);
                Alarmas.reprogramarTodo(ctx);
                Calendario.programarRevision(ctx);
                Resumen.programar(ctx);
                Calendario.sincronizar(ctx);
                if (Prefs.activarConHonor(ctx)) avisarActivarHonor(ctx);
            } finally {
                resultado.finish();
            }
        }).start();
    }

    /**
     * Android no deja usar el micrófono a un servicio que arranca solo al encender el teléfono.
     * Por eso se muestra un aviso: con un toque se abre la app y «Honor» vuelve a escuchar.
     */
    private static void avisarActivarHonor(Context ctx) {
        Alarmas.crearCanal(ctx);
        Intent abrir = new Intent(ctx, MainActivity.class);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 46, abrir,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification n = new android.app.Notification.Builder(ctx, Alarmas.CANAL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Activa «Honor»")
                .setContentText("Se reinició el teléfono: toca aquí para que vuelva a escuchar «Honor».")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        android.app.NotificationManager nm = ctx.getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.notify(4345, n);
    }
}
