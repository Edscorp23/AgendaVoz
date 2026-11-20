package com.agendavoz.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lee los eventos del calendario del teléfono (por ejemplo Google Calendar) y los convierte en
 * recordatorios hablados. Si Alexa está vinculada a ese mismo Google Calendar, lo que le digas
 * a Alexa («Alexa, agrega al calendario…») también llega aquí.
 */
public final class Calendario {

    private static final long DIAS_ADELANTE = 14;

    private static final String[] COLUMNAS = {
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.EVENT_LOCATION,
    };

    private Calendario() {
    }

    public static boolean tienePermiso(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Importa los eventos de los próximos días. Devuelve cuántos eventos nuevos importó,
     * o -1 si está desactivado o falta el permiso.
     */
    public static synchronized int sincronizar(Context ctx) {
        if (!Prefs.leerCalendario(ctx) || !tienePermiso(ctx)) return -1;
        long ahora = System.currentTimeMillis();
        long fin = ahora + DIAS_ADELANTE * 24 * 60 * 60_000L;
        DbHelper db = DbHelper.get(ctx);
        Set<String> ignorados = Prefs.calendarioIgnorados(ctx);
        Set<String> vistos = new HashSet<>();
        int nuevos = 0;

        ContentResolver cr = ctx.getContentResolver();
        Cursor c = null;
        try {
            c = CalendarContract.Instances.query(cr, COLUMNAS, ahora, fin);
            if (c == null) return 0;
            while (c.moveToNext()) {
                long eventoId = c.getLong(0);
                String titulo = c.getString(1);
                long inicio = c.getLong(2);
                boolean todoElDia = c.getInt(3) == 1;
                int estado = c.isNull(4) ? -1 : c.getInt(4);
                int miRespuesta = c.isNull(5) ? -1 : c.getInt(5);
                String lugar = c.getString(6);

                if (todoElDia || inicio < ahora) continue;
                if (estado == CalendarContract.Events.STATUS_CANCELED) continue;
                if (miRespuesta == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue;

                String clave = eventoId + "@" + inicio;
                vistos.add(clave);
                if (ignorados.contains(clave)) continue;

                if (titulo == null || titulo.trim().isEmpty()) titulo = "Evento del calendario";
                titulo = titulo.trim();

                Evento e = db.porCalendarioId(clave);
                if (e == null) {
                    e = new Evento();
                    e.tipo = Evento.REUNION;
                    e.titulo = titulo;
                    e.participantes = lugar == null || lugar.trim().isEmpty() ? "" : "en " + lugar.trim();
                    e.cuando = inicio;
                    e.avisoMin = Prefs.avisoMin(ctx);
                    e.calendarioId = clave;
                    db.insertar(e);
                    Alarmas.programar(ctx, e);
                    nuevos++;
                } else if (!e.hecho && !titulo.equals(e.titulo)) {
                    e.titulo = titulo;
                    db.actualizar(e);
                }
            }
        } catch (SecurityException ex) {
            return -1;
        } catch (Exception ex) {
            return nuevos;
        } finally {
            if (c != null) c.close();
        }

        // Los que se borraron o movieron en el calendario se quitan aquí también.
        List<Evento> importados = db.importadosFuturos(ahora);
        for (Evento e : importados) {
            if (!vistos.contains(e.calendarioId) && e.pospuesto <= ahora) {
                Alarmas.cancelar(ctx, e.id);
                db.eliminar(e.id);
            }
        }
        return nuevos;
    }

    /** Revisa el calendario cada media hora, aunque la app esté cerrada. */
    public static void programarRevision(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = pendiente(ctx);
        am.cancel(pi);
        if (!Prefs.leerCalendario(ctx)) return;
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000L,
                AlarmManager.INTERVAL_HALF_HOUR, pi);
    }

    private static PendingIntent pendiente(Context ctx) {
        Intent i = new Intent(ctx, CalendarioReceiver.class);
        i.setAction("com.agendavoz.app.REVISAR_CALENDARIO");
        return PendingIntent.getBroadcast(ctx, 777, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
