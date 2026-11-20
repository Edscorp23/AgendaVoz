package com.agendavoz.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Ajustes guardados en el teléfono. */
public final class Prefs {
    private static final String NOMBRE = "agenda_voz";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NOMBRE, Context.MODE_PRIVATE);
    }

    public static String proxyUrl(Context c) {
        return sp(c).getString("proxy_url", "").trim();
    }

    public static String proxyToken(Context c) {
        return sp(c).getString("proxy_token", "").trim();
    }

    public static boolean iaActiva(Context c) {
        return sp(c).getBoolean("ia_activa", true) && !proxyUrl(c).isEmpty();
    }

    public static int avisoMin(Context c) {
        return sp(c).getInt("aviso_min", 10);
    }

    public static String idioma(Context c) {
        return sp(c).getString("idioma", "es-PE");
    }

    /** Cada cuántos segundos repite el recordatorio hablado. */
    public static int repetirSeg(Context c) {
        return Math.max(10, sp(c).getInt("repetir_seg", 30));
    }

    /** Durante cuántos minutos insiste hablando antes de posponer solo. */
    public static int insistirMin(Context c) {
        return Math.max(1, sp(c).getInt("insistir_min", 3));
    }

    /** Si no respondes, en cuántos minutos vuelve a avisar (0 = no vuelve a avisar). */
    public static int reavisarMin(Context c) {
        return Math.max(0, sp(c).getInt("reavisar_min", 10));
    }

    // ---- radio
    public static String ultimaRadio(Context c) {
        return sp(c).getString("ultima_radio", "");
    }

    public static void guardarUltimaRadio(Context c, String nombre) {
        sp(c).edit().putString("ultima_radio", nombre == null ? "" : nombre).apply();
    }

    // ---- resumen de la mañana
    public static boolean resumenActivo(Context c) {
        return sp(c).getBoolean("resumen_activo", true);
    }

    public static int resumenHora(Context c) {
        return sp(c).getInt("resumen_hora", 7);
    }

    public static int resumenMinuto(Context c) {
        return sp(c).getInt("resumen_minuto", 0);
    }

    public static void guardarResumenActivo(Context c, boolean si) {
        sp(c).edit().putBoolean("resumen_activo", si).apply();
    }

    // ---- «Honor» descansa de noche
    public static boolean honorDescansaDeNoche(Context c) {
        return sp(c).getBoolean("honor_noche", true);
    }

    public static int honorNocheDesde(Context c) {
        return sp(c).getInt("honor_noche_desde", 23);
    }

    public static int honorNocheHasta(Context c) {
        return sp(c).getInt("honor_noche_hasta", 6);
    }

    public static void guardarHonorDescansaDeNoche(Context c, boolean si) {
        sp(c).edit().putBoolean("honor_noche", si).apply();
    }

    /** Escuchar la palabra «Honor» todo el tiempo, también con la pantalla bloqueada. */
    public static boolean activarConHonor(Context c) {
        return sp(c).getBoolean("activar_honor", false);
    }

    public static void guardarActivarConHonor(Context c, boolean si) {
        sp(c).edit().putBoolean("activar_honor", si).apply();
    }

    /** Si la pantalla del recordatorio escucha sola órdenes como «finaliza» o «pospón». */
    public static boolean escucharRespuestas(Context c) {
        return sp(c).getBoolean("escuchar_respuestas", true);
    }

    public static void guardarEscucharRespuestas(Context c, boolean si) {
        sp(c).edit().putBoolean("escuchar_respuestas", si).apply();
    }

    /** Leer eventos del calendario del teléfono (Google, y lo que se agrega con Alexa). */
    public static boolean leerCalendario(Context c) {
        return sp(c).getBoolean("leer_calendario", false);
    }

    public static void guardarLeerCalendario(Context c, boolean si) {
        sp(c).edit().putBoolean("leer_calendario", si).apply();
    }

    public static java.util.Set<String> calendarioIgnorados(Context c) {
        return new java.util.HashSet<>(sp(c).getStringSet("calendario_ignorados", new java.util.HashSet<>()));
    }

    public static void ignorarDelCalendario(Context c, String clave) {
        java.util.Set<String> s = calendarioIgnorados(c);
        s.add(clave);
        sp(c).edit().putStringSet("calendario_ignorados", s).apply();
    }

    public static void guardarRecordatorio(Context c, int repetirSeg, int insistirMin, int reavisarMin) {
        sp(c).edit()
                .putInt("repetir_seg", Math.max(10, repetirSeg))
                .putInt("insistir_min", Math.max(1, insistirMin))
                .putInt("reavisar_min", Math.max(0, reavisarMin))
                .apply();
    }

    public static boolean configuracionInicialHecha(Context c) {
        return sp(c).getBoolean("config_inicial", false);
    }

    public static void marcarConfiguracionInicial(Context c) {
        sp(c).edit().putBoolean("config_inicial", true).apply();
    }

    public static void guardar(Context c, String url, String token, boolean ia, int aviso, String idioma) {
        sp(c).edit()
                .putString("proxy_url", url == null ? "" : url.trim())
                .putString("proxy_token", token == null ? "" : token.trim())
                .putBoolean("ia_activa", ia)
                .putInt("aviso_min", Math.max(0, aviso))
                .putString("idioma", idioma == null || idioma.trim().isEmpty() ? "es-PE" : idioma.trim())
                .apply();
    }
}
