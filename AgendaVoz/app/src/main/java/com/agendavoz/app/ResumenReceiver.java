package com.agendavoz.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** A las 7:00 a.m. dice en voz alta los pendientes del día (si hay alguno) y programa el de mañana. */
public class ResumenReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Alarmas.avanzarRepetidos(ctx);
        String texto = Resumen.textoDeHoy(ctx);
        if (texto != null) {
            Intent s = new Intent(ctx, RecordatorioService.class);
            s.setAction(RecordatorioService.ACCION_INICIAR);
            s.putExtra(Alarmas.EXTRA_ID, Alarmas.ID_RESUMEN);
            s.putExtra(Alarmas.EXTRA_PREVIO, true);
            s.putExtra(RecordatorioService.EXTRA_TEXTO, texto);
            try {
                ctx.startForegroundService(s);
            } catch (Exception e) {
                Acciones.mostrarAviso(ctx, Alarmas.ID_RESUMEN, "Tus pendientes de hoy", Resumen.listaDeHoy(ctx), false);
            }
        }
        Resumen.programar(ctx);
    }
}
