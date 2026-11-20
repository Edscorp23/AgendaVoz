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
