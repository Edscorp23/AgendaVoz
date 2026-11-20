package com.agendavoz.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Reproduce una radio por internet, también con la pantalla bloqueada.
 * Cuando la app dice un recordatorio, la radio baja el volumen y luego lo recupera.
 */
public class RadioService extends Service {

    public static final String ACCION_TOCAR = "com.agendavoz.app.RADIO_TOCAR";
    public static final String ACCION_DETENER = "com.agendavoz.app.RADIO_DETENER";
    public static final String EXTRA_NOMBRE = "nombre";
    public static final String EXTRA_URL = "url";

    private static final String CANAL = "radio";
    private static final int ID_NOTIF = 4350;

    /** Nombre de la radio que suena ahora, o null. */
    public static volatile String sonando = null;

    private MediaPlayer reproductor;
    private AudioManager audio;
    private AudioFocusRequest foco;
    private String nombre = "";

    public static void tocar(Context ctx, Radio r) {
        Intent i = new Intent(ctx, RadioService.class);
        i.setAction(ACCION_TOCAR);
        i.putExtra(EXTRA_NOMBRE, r.nombre);
        i.putExtra(EXTRA_URL, r.url);
        ctx.startForegroundService(i);
        Prefs.guardarUltimaRadio(ctx, r.nombre);
    }

    public static void detener(Context ctx) {
        ctx.stopService(new Intent(ctx, RadioService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CANAL) == null) {
            NotificationChannel ch = new NotificationChannel(CANAL, "Radio", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent != null ? intent.getAction() : null;
        if (ACCION_DETENER.equals(accion) || intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        nombre = intent.getStringExtra(EXTRA_NOMBRE);
        String url = intent.getStringExtra(EXTRA_URL);
        if (nombre == null) nombre = "Radio";
        ponerEnPrimerPlano("Conectando…");
        if (url == null || url.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        empezar(url);
        return START_NOT_STICKY;
    }

    private void empezar(String url) {
        liberar();
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        foco = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(aa)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(this::cambioDeFoco)
                .build();
        if (audio != null) audio.requestAudioFocus(foco);

        reproductor = new MediaPlayer();
        reproductor.setAudioAttributes(aa);
        reproductor.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        reproductor.setOnPreparedListener(mp -> {
            mp.start();
            sonando = nombre;
            actualizarNotificacion("Sonando");
        });
        reproductor.setOnErrorListener((mp, what, extra) -> {
            actualizarNotificacion("No se pudo conectar con la radio");
            sonando = null;
            stopSelf();
            return true;
        });
        try {
            reproductor.setDataSource(url);
            reproductor.prepareAsync();
        } catch (Exception e) {
            actualizarNotificacion("Enlace de radio no válido");
            stopSelf();
        }
    }

    /** Baja el volumen mientras habla un recordatorio; se detiene si otra app pone música. */
    private void cambioDeFoco(int cambio) {
        if (reproductor == null) return;
        try {
            switch (cambio) {
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    reproductor.setVolume(0.15f, 0.15f);
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    reproductor.setVolume(1f, 1f);
                    if (!reproductor.isPlaying()) reproductor.start();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    stopSelf();
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private Notification notificacion(String texto) {
        Intent abrir = new Intent(this, RadiosActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 50, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent det = new Intent(this, RadioService.class);
        det.setAction(ACCION_DETENER);
        PendingIntent piDet = PendingIntent.getService(this, 51, det,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_notificacion)
                .setContentTitle(nombre)
                .setContentText(texto)
                .setOngoing(true)
                .setContentIntent(pi)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notificacion), "Detener", piDet).build())
                .build();
    }

    private void ponerEnPrimerPlano(String texto) {
        Notification n = notificacion(texto);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID_NOTIF, n);
        }
    }

    private void actualizarNotificacion(String texto) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_NOTIF, notificacion(texto));
    }

    private void liberar() {
        if (reproductor != null) {
            try {
                reproductor.stop();
            } catch (Exception ignored) {
            }
            reproductor.release();
            reproductor = null;
        }
        if (audio != null && foco != null) audio.abandonAudioFocusRequest(foco);
    }

    @Override
    public void onDestroy() {
        liberar();
        sonando = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
