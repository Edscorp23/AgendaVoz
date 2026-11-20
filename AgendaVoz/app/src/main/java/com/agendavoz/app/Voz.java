package com.agendavoz.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.speech.tts.TextToSpeech;

import java.util.List;
import java.util.Locale;

/**
 * Elige el motor de voz y el idioma español.
 * En muchos Honor/Huawei el motor por defecto no trae español; si está instalado,
 * se usa el de Google ("Servicios de voz de Google"), que sí lo tiene.
 */
public final class Voz {

    public static final String MOTOR_GOOGLE = "com.google.android.tts";

    private Voz() {
    }

    /** Paquete del motor a usar: Google si está instalado; si no, null (el del teléfono). */
    public static String motorPreferido(Context ctx) {
        try {
            List<ResolveInfo> l = ctx.getPackageManager().queryIntentServices(
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
            for (ResolveInfo r : l) {
                if (r.serviceInfo != null && MOTOR_GOOGLE.equals(r.serviceInfo.packageName)) return MOTOR_GOOGLE;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static boolean googleInstalado(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(MOTOR_GOOGLE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static TextToSpeech crear(Context ctx, TextToSpeech.OnInitListener listener) {
        String motor = motorPreferido(ctx);
        return motor != null ? new TextToSpeech(ctx, listener, motor) : new TextToSpeech(ctx, listener);
    }

    /** Pone el idioma español (probando variantes). Devuelve true si hay voz en español. */
    public static boolean ponerEspanol(Context ctx, TextToSpeech tts) {
        String[] intentos = {Prefs.idioma(ctx), "es-US", "es-MX", "es-ES", "es"};
        for (String tag : intentos) {
            try {
                int r = tts.setLanguage(Locale.forLanguageTag(tag));
                if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** Abre la pantalla para descargar la voz en español del motor indicado. */
    public static void instalarVozEspanol(Context ctx) {
        String motor = motorPreferido(ctx);
        try {
            Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
            if (motor != null) i.setPackage(motor);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        abrirAjustesDeVoz(ctx);
    }

    public static void abrirAjustesDeVoz(Context ctx) {
        try {
            Intent i = new Intent("com.android.settings.TTS_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            SettingsActivity.abrirInfoApp(ctx);
        }
    }

    /** Abre la Play Store en "Servicios de voz de Google". */
    public static void instalarMotorGoogle(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + MOTOR_GOOGLE));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + MOTOR_GOOGLE));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception ignored) {
            }
        }
    }
}
