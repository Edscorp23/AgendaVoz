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
