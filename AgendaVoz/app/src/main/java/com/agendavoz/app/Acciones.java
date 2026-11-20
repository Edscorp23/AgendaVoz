package com.agendavoz.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Lo que se puede hacer con un recordatorio que está sonando: Hecho, Posponer o Silenciar. */
public final class Acciones {

    public static final String HECHO = "com.agendavoz.app.HECHO";
    public static final String POSPONER = "com.agendavoz.app.POSPONER";
    public static final String SILENCIAR = "com.agendavoz.app.SILENCIAR";
    public static final String EXTRA_MINUTOS = "minutos";

    private Acciones() {
    }

    /** Marca la tarea como realizada: deja de hablar y no vuelve a avisar. */
    public static void hecho(Context ctx, long id) {
        if (id != Alarmas.ID_PRUEBA) {
            DbHelper db = DbHelper.get(ctx);
            Evento e = db.obtener(id);
            if (e != null) {
                e.hecho = true;
                e.pospuesto = 0;
                db.actualizar(e);
            }
            Alarmas.cancelar(ctx, id);
        }
        detenerVoz(ctx, id);
        quitarAviso(ctx, id);
    }

    /** Pospone: deja de hablar y vuelve a decirlo dentro de N minutos. */
    public static void posponer(Context ctx, long id, int minutos, int reavisos, boolean automatico) {
        long cuando = System.currentTimeMillis() + minutos * 60_000L;
        if (id != Alarmas.ID_PRUEBA) {
            DbHelper db = DbHelper.get(ctx);
            Evento e = db.obtener(id);
            if (e != null && !e.hecho) {
                e.pospuesto = cuando;
                db.actualizar(e);
                Alarmas.programar(ctx, e, reavisos);
            }
        }
        detenerVoz(ctx, id);
        String hora = Formato.hora(LocalDateTime.ofInstant(Instant.ofEpochMilli(cuando), ZoneId.systemDefault()).toLocalTime());
        String titulo = automatico ? "No respondiste: te lo vuelvo a decir a las " + hora
                : "Pospuesto: te lo vuelvo a decir a las " + hora;
        mostrarAviso(ctx, id, titulo, nombre(ctx, id), true);
    }

    /** Deja de hablar, pero el recordatorio queda en la barra de notificaciones con sus botones. */
    public static void silenciar(Context ctx, long id) {
        detenerVoz(ctx, id);
        mostrarAviso(ctx, id, "Pendiente: " + nombre(ctx, id), "Toca Hecho cuando lo termines.", true);
    }

    public static void detenerVoz(Context ctx, long id) {
        Intent s = new Intent(ctx, RecordatorioService.class);
        ctx.stopService(s);
    }

    static String nombre(Context ctx, long id) {
        if (id == Alarmas.ID_PRUEBA) return "Recordatorio de prueba";
        Evento e = DbHelper.get(ctx).obtener(id);
        return e == null ? "Recordatorio" : e.titulo;
    }

    /** Botones Hecho / Posponer / Silenciar para la notificación. */
    static Notification.Action accion(Context ctx, long id, String accion, String texto, int minutos) {
        Intent i = new Intent(ctx, AccionReceiver.class);
        i.setAction(accion + "." + id + "." + minutos);
        i.putExtra(Alarmas.EXTRA_ID, id);
        i.putExtra(EXTRA_MINUTOS, minutos);
        i.putExtra("accion", accion);
        int codigo = (int) ((id % 100_000) * 10 + (accion.equals(HECHO) ? 1 : accion.equals(POSPONER) ? 2 : 3));
        PendingIntent pi = PendingIntent.getBroadcast(ctx, codigo, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_notificacion), texto, pi).build();
    }

    static void mostrarAviso(Context ctx, long id, String titulo, String texto, boolean conBotones) {
        Intent abrir = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 2, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(ctx, Alarmas.CANAL)
                .setSmallIcon(R.drawable.ic_notificacion)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true);
        if (conBotones) {
            b.addAction(accion(ctx, id, HECHO, "Hecho", 0));
            b.addAction(accion(ctx, id, POSPONER, "Posponer 10 min", 10));
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(RecordatorioService.idNotificacion(id), b.build());
    }

    static void quitarAviso(Context ctx, long id) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(RecordatorioService.idNotificacion(id));
    }
}
