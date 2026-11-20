package com.agendavoz.app;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.KeyEvent;

import java.util.List;

/** Spotify y radios por voz. */
public final class Musica {

    public static final String SPOTIFY = "com.spotify.music";

    private Musica() {
    }

    public static boolean spotifyInstalado(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(SPOTIFY, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Pide a Spotify que reproduzca un artista o canción. Con la cuenta gratis, Spotify lo pone en aleatorio.
     * Devuelve false si no se pudo abrir Spotify.
     */
    public static boolean reproducirEnSpotify(Context ctx, String consulta) {
        RadioService.detener(ctx);
        String q = consulta == null ? "" : consulta.trim();
        try {
            Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
            i.setPackage(SPOTIFY);
            if (q.isEmpty()) {
                i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
                i.putExtra(SearchManager.QUERY, "");
            } else {
                i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE);
                i.putExtra(MediaStore.EXTRA_MEDIA_ARTIST, q);
                i.putExtra(SearchManager.QUERY, q);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                Intent v = new Intent(Intent.ACTION_VIEW, Uri.parse(q.isEmpty() ? "spotify:" : "spotify:search:" + q));
                v.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(v);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    /** Detiene la radio de la app y pausa la música que esté sonando (Spotify u otra). */
    public static void detenerTodo(Context ctx) {
        RadioService.detener(ctx);
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am != null && am.isMusicActive()) {
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
        }
    }

    /**
     * Busca entre las radios guardadas la que mejor coincide con lo dicho.
     * Si no se dijo nombre, devuelve la última que sonó (o la primera guardada).
     */
    public static Radio buscarRadio(Context ctx, String consulta) {
        List<Radio> radios = DbHelper.get(ctx).radios();
        if (radios.isEmpty()) return null;
        String q = VozParser.normalizar(consulta == null ? "" : consulta).trim()
                .replaceAll("^(la|el)\\s+", "").replaceAll("\\s+", " ");
        if (q.isEmpty()) {
            String ultima = Prefs.ultimaRadio(ctx);
            for (Radio r : radios) if (r.nombre.equals(ultima)) return r;
            return radios.get(0);
        }
        Radio mejor = null;
        int mejorPuntaje = 0;
        for (Radio r : radios) {
            String n = VozParser.normalizar(r.nombre).replaceAll("\\s+", " ").trim();
            int puntaje = 0;
            if (n.equals(q)) puntaje = 100;
            else if (n.contains(q)) puntaje = 50;
            for (String palabra : q.split(" ")) {
                if (palabra.length() >= 2 && (" " + n + " ").contains(" " + palabra + " ")) puntaje += 10;
            }
            if (puntaje > mejorPuntaje) {
                mejorPuntaje = puntaje;
                mejor = r;
            }
        }
        return mejor;
    }
}
