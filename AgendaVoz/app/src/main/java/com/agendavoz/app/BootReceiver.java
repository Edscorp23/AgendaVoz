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
            } finally {
                resultado.finish();
            }
        }).start();
    }
}
