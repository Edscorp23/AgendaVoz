package com.agendavoz.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Recibe los toques en los botones de la notificación (Hecho, Posponer, Silenciar). */
public class AccionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        long id = intent.getLongExtra(Alarmas.EXTRA_ID, Long.MIN_VALUE);
        if (id == Long.MIN_VALUE) return;
        String accion = intent.getStringExtra("accion");
        int minutos = intent.getIntExtra(Acciones.EXTRA_MINUTOS, 10);
        if (Acciones.HECHO.equals(accion)) {
            Acciones.hecho(ctx, id);
        } else if (Acciones.POSPONER.equals(accion)) {
            Acciones.posponer(ctx, id, minutos > 0 ? minutos : 10, 0, false);
        } else if (Acciones.SILENCIAR.equals(accion)) {
            Acciones.silenciar(ctx, id);
        }
    }
}
