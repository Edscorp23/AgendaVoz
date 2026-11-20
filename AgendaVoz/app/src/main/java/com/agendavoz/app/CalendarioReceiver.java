package com.agendavoz.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Cada media hora importa los eventos nuevos del calendario del teléfono. */
public class CalendarioReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult resultado = goAsync();
        new Thread(() -> {
            try {
                Calendario.sincronizar(ctx);
            } finally {
                resultado.finish();
            }
        }).start();
    }
}
