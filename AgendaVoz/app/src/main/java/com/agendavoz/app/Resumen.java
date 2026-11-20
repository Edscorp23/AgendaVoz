package com.agendavoz.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** Resumen hablado de la mañana: «Buenos días. Hoy tienes 3 pendientes: …». */
public final class Resumen {

    private Resumen() {
    }

    /** Programa el próximo resumen (por defecto a las 7:00 a.m.). */
    public static void programar(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = pendiente(ctx);
        am.cancel(pi);
        if (!Prefs.resumenActivo(ctx)) return;

        LocalDateTime ahora = LocalDateTime.now();
        LocalDateTime cuando = LocalDate.now().atTime(Prefs.resumenHora(ctx), Prefs.resumenMinuto(ctx));
        if (!cuando.isAfter(ahora)) cuando = cuando.plusDays(1);
        long ms = cuando.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (Alarmas.puedeExactas(ctx)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi);
        }
    }

    private static PendingIntent pendiente(Context ctx) {
        Intent i = new Intent(ctx, ResumenReceiver.class);
        i.setAction("com.agendavoz.app.RESUMEN_DEL_DIA");
        return PendingIntent.getBroadcast(ctx, 888, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Texto del resumen de hoy, o null si no hay nada pendiente. */
    public static String textoDeHoy(Context ctx) {
        LocalDate hoy = LocalDate.now();
        long ini = hoy.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long fin = hoy.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<Evento> l = DbHelper.get(ctx).pendientesEntre(ini, fin);
        if (l.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("Buenos días. Hoy, ").append(Formato.fechaLarga(hoy))
                .append(", tienes ").append(l.size()).append(l.size() == 1 ? " pendiente: " : " pendientes: ");
        for (int i = 0; i < l.size(); i++) {
            Evento e = l.get(i);
            if (i > 0) sb.append(i == l.size() - 1 ? "; y " : "; ");
            sb.append("a ").append(Formato.horaHablada(e.fechaHora().toLocalTime())).append(", ");
            sb.append(e.esReunion() && !Formato.empiezaConReunion(e.titulo) ? "reunión: " : "").append(e.titulo);
        }
        sb.append(". Que tengas un buen día.");
        return sb.toString();
    }

    /** Lista corta para la notificación del resumen. */
    public static String listaDeHoy(Context ctx) {
        LocalDate hoy = LocalDate.now();
        long ini = hoy.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long fin = hoy.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        StringBuilder sb = new StringBuilder();
        for (Evento e : DbHelper.get(ctx).pendientesEntre(ini, fin)) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(Formato.hora(e.fechaHora().toLocalTime())).append("  ").append(e.titulo);
        }
        return sb.toString();
    }
}
