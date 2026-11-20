package com.agendavoz.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/** «Semáforo» de todo lo que la app necesita para que los recordatorios se vean y se oigan. */
public final class Permisos {

    private Permisos() {
    }

    public static boolean notificaciones(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    /** Los avisos del recordatorio deben ser «emergentes» (importancia alta) para verse con botones. */
    public static boolean avisosEmergentes(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return false;
        Alarmas.crearCanal(c);
        NotificationChannel ch = nm.getNotificationChannel(Alarmas.CANAL);
        return ch != null && ch.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    }

    public static boolean mostrarEnPantalla(Context c) {
        return Settings.canDrawOverlays(c);
    }

    public static boolean sinOptimizacionBateria(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    public static boolean microfono(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Nombres cortos de lo que falta para ver y oír bien los recordatorios (vacía si está todo bien). */
    public static List<String> faltantes(Context c) {
        List<String> l = new ArrayList<>();
        if (!notificaciones(c)) l.add("notificaciones");
        else if (!avisosEmergentes(c)) l.add("avisos emergentes");
        if (!mostrarEnPantalla(c)) l.add("mostrar en pantalla");
        if (!Alarmas.puedeExactas(c)) l.add("alarmas exactas");
        if (!sinOptimizacionBateria(c)) l.add("batería sin restricciones");
        if ((Prefs.activarConHonor(c) || Prefs.escucharRespuestas(c)) && !microfono(c)) l.add("micrófono");
        return l;
    }

    /** Abre los ajustes del aviso del recordatorio (para activar «emergente» y «pantalla de bloqueo»). */
    public static void abrirAjustesDeAvisos(Context c) {
        try {
            Intent i;
            if (notificaciones(c)) {
                i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_CHANNEL_ID, Alarmas.CANAL);
            } else {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            }
            i.putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            SettingsActivity.abrirNotificaciones(c);
        }
    }
}
