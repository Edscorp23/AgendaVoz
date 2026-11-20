package com.agendavoz.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.util.HashSet;
import java.util.Set;

/**
 * Escucha la palabra «Honor» todo el tiempo (también con la pantalla bloqueada), sin internet,
 * usando Vosk. Al oírla vibra y abre Agenda Voz lista para recibir una orden.
 *
 * Solo reconoce unas pocas palabras (gramática limitada): así gasta menos batería y se confunde menos.
 * Se pausa cuando otra parte de la app necesita el micrófono.
 */
public class EscuchaService extends Service {

    public static final String ACCION_INICIAR = "com.agendavoz.app.HONOR_INICIAR";
    public static final String ACCION_PAUSAR = "com.agendavoz.app.HONOR_PAUSAR";
    public static final String ACCION_REANUDAR = "com.agendavoz.app.HONOR_REANUDAR";
    public static final String EXTRA_MOTIVO = "motivo";
    public static final String EXTRA_DESPERTAR = "despertar_honor";

    public static final String MOTIVO_APP = "app";
    public static final String MOTIVO_RECORDATORIO = "recordatorio";
    private static final String MOTIVO_DESPERTAR = "despertar";
    private static final String MOTIVO_NOCHE = "noche";

    private static final String CANAL = "escucha_honor";
    private static final int ID_NOTIF = 4343;
    private static final int ID_NOTIF_ABRIR = 4344;

    /** «honor» más palabras parecidas, para que no se active con cualquier cosa. */
    private static final String GRAMATICA =
            "[\"honor\", \"hora\", \"ahora\", \"amor\", \"señor\", \"favor\", \"oro\", \"por\", \"no\", \"[unk]\"]";

    /** true mientras el servicio está activo. */
    public static volatile boolean activo = false;
    /** Texto corto con el estado, para mostrarlo en Ajustes. */
    public static volatile String estado = "Apagado";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Set<String> motivosPausa = new HashSet<>();
    private Model modelo;
    private SpeechService oido;
    private boolean cargando = false;
    private long ultimoDespertar = 0;

    // ------------------------------------------------------------------ ayudas para el resto de la app

    public static void iniciar(Context ctx) {
        if (!Prefs.activarConHonor(ctx)) return;
        try {
            Intent i = new Intent(ctx, EscuchaService.class);
            i.setAction(ACCION_INICIAR);
            ctx.startForegroundService(i);
        } catch (Exception ignored) {
        }
    }

    public static void detener(Context ctx) {
        ctx.stopService(new Intent(ctx, EscuchaService.class));
    }

    public static void pausar(Context ctx, String motivo) {
        enviar(ctx, ACCION_PAUSAR, motivo);
    }

    public static void reanudar(Context ctx, String motivo) {
        enviar(ctx, ACCION_REANUDAR, motivo);
    }

    private static void enviar(Context ctx, String accion, String motivo) {
        if (!activo) return;
        try {
            Intent i = new Intent(ctx, EscuchaService.class);
            i.setAction(accion);
            i.putExtra(EXTRA_MOTIVO, motivo);
            ctx.startService(i);
        } catch (Exception ignored) {
        }
    }

    /** El modelo de voz se agrega al compilar en GitHub; en otra compilación podría faltar. */
    public static boolean modeloIncluido(Context ctx) {
        try {
            String[] l = ctx.getAssets().list("model-es");
            return l != null && l.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void onCreate() {
        super.onCreate();
        activo = true;
        crearCanal();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ponerEnPrimerPlano();
        String accion = intent != null ? intent.getAction() : ACCION_INICIAR;
        String motivo = intent != null ? intent.getStringExtra(EXTRA_MOTIVO) : null;

        if (!Prefs.activarConHonor(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACCION_PAUSAR.equals(accion)) {
            if (motivo != null) motivosPausa.add(motivo);
            pararOido();
            estado = "En pausa";
            actualizarNotificacion("En pausa mientras usas la app");
        } else if (ACCION_REANUDAR.equals(accion)) {
            if (motivo != null) motivosPausa.remove(motivo);
            if (MOTIVO_APP.equals(motivo)) motivosPausa.remove(MOTIVO_DESPERTAR);
            empezarSiSePuede();
        } else {
            if (modelo == null) cargarModelo();
            else empezarSiSePuede();
        }
        return START_STICKY;
    }

    private void cargarModelo() {
        if (cargando) return;
        if (!modeloIncluido(this)) {
            estado = "Falta el modelo de voz en esta APK";
            actualizarNotificacion(estado);
            return;
        }
        cargando = true;
        estado = "Preparando…";
        StorageService.unpack(this, "model-es", "model",
                m -> {
                    cargando = false;
                    modelo = m;
                    empezarSiSePuede();
                },
                e -> {
                    cargando = false;
                    estado = "No se pudo cargar el modelo de voz";
                    actualizarNotificacion(estado);
                });
    }

    private void empezarSiSePuede() {
        if (modelo == null) {
            cargarModelo();
            return;
        }
        if (revisarNoche()) return;
        if (!motivosPausa.isEmpty() || oido != null) return;
        try {
            Recognizer r = new Recognizer(modelo, 16000.0f, GRAMATICA);
            oido = new SpeechService(r, 16000.0f);
            oido.startListening(oyente);
            estado = "Escuchando «Honor»";
            actualizarNotificacion("Di «Honor» para hablarme");
            programarInicioDeNoche();
        } catch (Exception e) {
            oido = null;
            estado = "No se pudo usar el micrófono";
            actualizarNotificacion(estado);
            h.postDelayed(this::empezarSiSePuede, 10_000);
        }
    }

    // ------------------------------------------------------------------ descanso nocturno (11 p.m. a 6 a.m.)

    private boolean esDeNoche() {
        if (!Prefs.honorDescansaDeNoche(this)) return false;
        int desde = Prefs.honorNocheDesde(this);
        int hasta = Prefs.honorNocheHasta(this);
        int h = java.time.LocalTime.now().getHour();
        return desde > hasta ? (h >= desde || h < hasta) : (h >= desde && h < hasta);
    }

    /** Si es de noche, deja de escuchar y programa el regreso a la hora indicada. Devuelve true si es de noche. */
    private boolean revisarNoche() {
        if (!esDeNoche()) {
            motivosPausa.remove(MOTIVO_NOCHE);
            return false;
        }
        motivosPausa.add(MOTIVO_NOCHE);
        pararOido();
        int hasta = Prefs.honorNocheHasta(this);
        estado = "Descansando hasta las " + hasta + ":00";
        actualizarNotificacion("«Honor» descansa de noche; vuelve a las " + hasta + ":00");
        // Una alarma (no un temporizador) despierta al servicio aunque el teléfono esté dormido.
        java.time.LocalDateTime fin = java.time.LocalDate.now().atTime(hasta, 0);
        if (!fin.isAfter(java.time.LocalDateTime.now())) fin = fin.plusDays(1);
        Intent i = new Intent(this, EscuchaService.class);
        i.setAction(ACCION_REANUDAR);
        i.putExtra(EXTRA_MOTIVO, MOTIVO_NOCHE);
        PendingIntent pi = PendingIntent.getForegroundService(this, 47, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.AlarmManager am = getSystemService(android.app.AlarmManager.class);
        if (am != null) {
            long ms = fin.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (Alarmas.puedeExactas(this)) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, ms, pi);
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, ms, pi);
        }
        return true;
    }

    /** Mientras escucha, el procesador está despierto, así que basta un temporizador hasta las 11 p.m. */
    private void programarInicioDeNoche() {
        h.removeCallbacks(empiezaLaNoche);
        if (!Prefs.honorDescansaDeNoche(this)) return;
        java.time.LocalDateTime inicio = java.time.LocalDate.now().atTime(Prefs.honorNocheDesde(this), 0);
        if (!inicio.isAfter(java.time.LocalDateTime.now())) inicio = inicio.plusDays(1);
        long ms = java.time.Duration.between(java.time.LocalDateTime.now(), inicio).toMillis();
        h.postDelayed(empiezaLaNoche, Math.max(1000, ms));
    }

    private final Runnable empiezaLaNoche = this::revisarNoche;

    private void pararOido() {
        if (oido != null) {
            try {
                oido.stop();
                oido.shutdown();
            } catch (Exception ignored) {
            }
            oido = null;
        }
    }

    private final RecognitionListener oyente = new RecognitionListener() {
        @Override
        public void onPartialResult(String hypothesis) {
        }

        @Override
        public void onResult(String hypothesis) {
            if (dijoHonor(hypothesis)) despertar();
        }

        @Override
        public void onFinalResult(String hypothesis) {
            if (dijoHonor(hypothesis)) despertar();
        }

        @Override
        public void onError(Exception exception) {
            pararOido();
            h.postDelayed(EscuchaService.this::empezarSiSePuede, 3000);
        }

        @Override
        public void onTimeout() {
            pararOido();
            empezarSiSePuede();
        }
    };

    private static boolean dijoHonor(String json) {
        try {
            String t = new JSONObject(json).optString("text", "");
            for (String palabra : t.split("\\s+")) {
                if ("honor".equals(palabra)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Se oyó «Honor»: vibra y abre la app para escuchar la orden. */
    private void despertar() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoDespertar < 4000) return;
        ultimoDespertar = ahora;
        motivosPausa.add(MOTIVO_DESPERTAR);
        pararOido();
        // Si en un minuto nadie usa la app, vuelve a escuchar «Honor».
        h.postDelayed(() -> {
            motivosPausa.remove(MOTIVO_DESPERTAR);
            empezarSiSePuede();
        }, 60_000);

        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }

        Intent abrir = new Intent(this, MainActivity.class);
        abrir.putExtra(EXTRA_DESPERTAR, true);
        abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        boolean abierta = false;
        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                startActivity(abrir);
                abierta = true;
            } catch (Exception ignored) {
            }
        }
        if (!abierta) {
            // Sin el permiso de "mostrar sobre otras apps": se abre con una notificación de pantalla completa.
            PendingIntent pi = PendingIntent.getActivity(this, 44, abrir,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Alarmas.crearCanal(this);
            Notification n = new Notification.Builder(this, Alarmas.CANAL)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle("Te escucho")
                    .setContentText("Toca para hablar con Agenda Voz")
                    .setCategory(Notification.CATEGORY_CALL)
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setTimeoutAfter(30_000)
                    .build();
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(ID_NOTIF_ABRIR, n);
        }
    }

    // ------------------------------------------------------------------ notificación fija

    private void crearCanal() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "Escucha de «Honor»", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Aviso permanente mientras la app espera la palabra «Honor»");
        ch.setSound(null, null);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notificacion(String texto) {
        Intent ajustes = new Intent(this, SettingsActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 45, ajustes,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Agenda Voz")
                .setContentText(texto)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void ponerEnPrimerPlano() {
        Notification n = notificacion(estado);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(ID_NOTIF, n);
        }
    }

    private void actualizarNotificacion(String texto) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_NOTIF, notificacion(texto));
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        pararOido();
        if (modelo != null) {
            try {
                modelo.close();
            } catch (Exception ignored) {
            }
            modelo = null;
        }
        activo = false;
        estado = "Apagado";
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
