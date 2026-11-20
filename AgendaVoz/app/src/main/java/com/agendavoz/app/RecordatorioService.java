package com.agendavoz.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.time.LocalDate;

/**
 * Dice el recordatorio en voz alta y lo repite cada cierto tiempo
 * hasta que la persona toca Hecho o Posponer (o hasta que se agota el tiempo y se pospone solo).
 */
public class RecordatorioService extends Service {

    public static final String ACCION_INICIAR = "com.agendavoz.app.VOZ_INICIAR";
    public static final String ACCION_PAUSAR = "com.agendavoz.app.VOZ_PAUSAR";
    public static final String ACCION_CERRAR_PANTALLA = "com.agendavoz.app.CERRAR_ALARMA";
    /** Texto fijo a decir (lo usa el resumen de la mañana). */
    public static final String EXTRA_TEXTO = "texto";
    /** Id del recordatorio que está insistiendo ahora (para mostrar sus botones en la app), o MIN_VALUE. */
    public static volatile long idInsistiendo = Long.MIN_VALUE;
    /** Avisos a la pantalla del recordatorio para que escuche solo cuando la app no está hablando. */
    public static final String ACCION_HABLANDO = "com.agendavoz.app.VOZ_HABLANDO";
    public static final String ACCION_CALLADO = "com.agendavoz.app.VOZ_CALLADO";

    /** true mientras la app está diciendo el recordatorio en voz alta. */
    public static volatile boolean hablando = false;

    private static final int ID_PRIMER_PLANO = 4242;
    private static final long NINGUNO = Long.MIN_VALUE;
    private static final int MAX_REAVISOS = 12;

    private final Handler h = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsListo = false;
    private boolean ttsFallo = false;
    private Ringtone timbre;
    private AudioManager audio;
    private AudioFocusRequest foco;
    private PowerManager.WakeLock wakeLock;

    private long idActual = NINGUNO;
    private boolean esPrevio;
    private int reavisos;
    private long inicio;
    private int vecesDicho;
    private boolean arrancarAlEstarListo = false;
    private String textoFijo = null;

    private final AudioAttributes atributos = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    /** Id de notificación por evento (distinto del de primer plano). */
    public static int idNotificacion(long id) {
        return (int) (Math.abs(id) % 1_000_000) + 10;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Alarmas.crearCanal(this);
        // El micrófono lo necesita la pantalla del recordatorio: «Honor» espera.
        EscuchaService.pausar(this, EscuchaService.MOTIVO_RECORDATORIO);
        audio = getSystemService(AudioManager.class);
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AgendaVoz:recordatorio");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(30 * 60_000L);
        }
        tts = Voz.crear(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsListo = Voz.ponerEspanol(this, tts);
                if (ttsListo) {
                    tts.setAudioAttributes(atributos);
                    tts.setSpeechRate(0.95f);
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String id) {
                        }

                        @Override
                        public void onDone(String id) {
                            h.post(RecordatorioService.this::terminoDeHablar);
                        }

                        @Override
                        @SuppressWarnings("deprecation")
                        public void onError(String id) {
                            h.post(RecordatorioService.this::terminoDeHablar);
                        }
                    });
                }
            }
            ttsFallo = !ttsListo;
            if (arrancarAlEstarListo) {
                arrancarAlEstarListo = false;
                decir();
            }
        });
        // Si la voz no responde en 12 segundos, se usa el timbre de alarma como respaldo.
        h.postDelayed(() -> {
            if (!ttsListo && arrancarAlEstarListo) {
                ttsFallo = true;
                arrancarAlEstarListo = false;
                decir();
            }
        }, 12000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent != null ? intent.getAction() : null;

        if (ACCION_PAUSAR.equals(accion)) {
            if (idActual == NINGUNO) {
                stopSelf();
            } else {
                callar();
                h.removeCallbacks(siguiente);
                h.postDelayed(siguiente, 15_000); // deja hablar a la persona
            }
            return START_NOT_STICKY;
        }

        if (!ACCION_INICIAR.equals(accion)) {
            if (idActual == NINGUNO) stopSelf();
            return START_NOT_STICKY;
        }

        long id = intent.getLongExtra(Alarmas.EXTRA_ID, NINGUNO);
        boolean previo = intent.getBooleanExtra(Alarmas.EXTRA_PREVIO, false);
        int rea = intent.getIntExtra(Alarmas.EXTRA_REAVISOS, 0);

        // Si ya estaba insistiendo con otro recordatorio, ese se pospone 10 minutos para no mezclarlos.
        if (idActual != NINGUNO && idActual != id && !esPrevio && !previo) {
            long anterior = idActual;
            posponerSinDetener(anterior);
        }

        idActual = id;
        esPrevio = previo;
        reavisos = rea;
        textoFijo = intent.getStringExtra(EXTRA_TEXTO);
        idInsistiendo = previo ? Long.MIN_VALUE : id;
        inicio = System.currentTimeMillis();
        vecesDicho = 0;
        h.removeCallbacks(siguiente);
        callar();

        Notification n = notificacionPrimerPlano();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_PRIMER_PLANO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(ID_PRIMER_PLANO, n);
        }
        if (wakeLock != null) wakeLock.acquire(30 * 60_000L);
        if (!esPrevio) abrirPantalla();

        if (ttsListo || ttsFallo) decir();
        else arrancarAlEstarListo = true;
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- hablar

    private final Runnable siguiente = this::decir;

    private void decir() {
        if (idActual == NINGUNO) return;
        if (!esPrevio && System.currentTimeMillis() - inicio >= Prefs.insistirMin(this) * 60_000L) {
            tiempoAgotado();
            return;
        }
        String texto = texto();
        vecesDicho++;
        avisar(true);
        pedirFoco();
        if (ttsListo) {
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "REC_" + System.nanoTime());
        } else {
            sonarTimbre();
        }
    }

    private void terminoDeHablar() {
        soltarFoco();
        avisar(false);
        if (idActual == NINGUNO) return;
        if (esPrevio) {
            // El aviso previo se dice una sola vez y queda como notificación normal.
            long id = idActual;
            idActual = NINGUNO;
            if (id == Alarmas.ID_RESUMEN) {
                Acciones.mostrarAviso(this, id, "Tus pendientes de hoy", Resumen.listaDeHoy(this), false);
            } else {
                Acciones.mostrarAviso(this, id, "Pronto: " + Acciones.nombre(this, id), textoPrevioCorto(id), false);
            }
            stopSelf();
            return;
        }
        h.removeCallbacks(siguiente);
        h.postDelayed(siguiente, Prefs.repetirSeg(this) * 1000L);
    }

    private void tiempoAgotado() {
        long id = idActual;
        idActual = NINGUNO;
        idInsistiendo = Long.MIN_VALUE;
        int reavisar = Prefs.reavisarMin(this);
        if (id != Alarmas.ID_PRUEBA && reavisar > 0 && reavisos < MAX_REAVISOS) {
            Acciones.posponer(this, id, reavisar, reavisos + 1, true);
        } else {
            Acciones.silenciar(this, id);
        }
    }

    private void posponerSinDetener(long id) {
        if (id == Alarmas.ID_PRUEBA) return;
        DbHelper db = DbHelper.get(this);
        Evento e = db.obtener(id);
        if (e == null || e.hecho) return;
        e.pospuesto = System.currentTimeMillis() + 10 * 60_000L;
        db.actualizar(e);
        Alarmas.programar(this, e, reavisos);
    }

    private String texto() {
        if (textoFijo != null) return textoFijo;
        if (idActual == Alarmas.ID_PRUEBA) {
            return vecesDicho == 0
                    ? "Esta es una prueba de Agenda Voz. Así te recordaré tus reuniones y tareas. Toca Hecho para terminar, o Posponer."
                    : "Prueba de Agenda Voz. Toca Hecho para terminar.";
        }
        Evento e = DbHelper.get(this).obtener(idActual);
        if (e == null) return "Tienes un recordatorio pendiente.";
        if (esPrevio) {
            return "Aviso: en " + e.avisoMin + " minutos tienes " + (e.esReunion() ? "la reunión " : "la tarea ")
                    + e.titulo + ", a " + Formato.horaHablada(e.fechaHora().toLocalTime()) + ".";
        }
        String que = e.esReunion() ? "tu reunión: " : "tu tarea: ";
        if (vecesDicho == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(reavisos > 0 || e.pospuesto > 0 ? "Te lo recuerdo otra vez. " : "Recordatorio. ");
            sb.append("Es hora de ").append(que).append(e.titulo);
            if (!e.participantes.isEmpty()) sb.append(", con ").append(e.participantes);
            sb.append(". Estaba programada para ");
            if (!e.fechaHora().toLocalDate().equals(LocalDate.now())) {
                sb.append(Formato.fechaHablada(e.fechaHora().toLocalDate(), LocalDate.now())).append(", ");
            }
            sb.append(Formato.horaHablada(e.fechaHora().toLocalTime()));
            sb.append(". Cuando la termines, toca Hecho. Si no puedes ahora, toca Posponer.");
            return sb.toString();
        }
        return "Recuerda " + que + e.titulo + ". Toca Hecho o Posponer.";
    }

    private String textoPrevioCorto(long id) {
        Evento e = id == Alarmas.ID_PRUEBA ? null : DbHelper.get(this).obtener(id);
        if (e == null) return "";
        return (e.esReunion() ? "Reunión" : "Tarea") + " a las " + Formato.hora(e.fechaHora().toLocalTime());
    }

    // ---------------------------------------------------------------- respaldo: timbre

    private void sonarTimbre() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (timbre == null) {
                timbre = RingtoneManager.getRingtone(this, uri);
                if (timbre != null) timbre.setAudioAttributes(atributos);
            }
            if (timbre != null) timbre.play();
        } catch (Exception ignored) {
        }
        h.postDelayed(() -> {
            if (timbre != null) timbre.stop();
            terminoDeHablar();
        }, 4000);
    }

    /** Avisa a la pantalla del recordatorio si la app empieza o termina de hablar. */
    private void avisar(boolean empiezaAHablar) {
        hablando = empiezaAHablar;
        Intent i = new Intent(empiezaAHablar ? ACCION_HABLANDO : ACCION_CALLADO);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void callar() {
        if (hablando) avisar(false);
        if (tts != null && ttsListo) tts.stop();
        if (timbre != null && timbre.isPlaying()) timbre.stop();
        soltarFoco();
    }

    // ---------------------------------------------------------------- foco de audio (baja la música mientras habla)

    private void pedirFoco() {
        if (audio == null) return;
        if (foco == null) {
            foco = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(atributos)
                    .build();
        }
        audio.requestAudioFocus(foco);
    }

    private void soltarFoco() {
        if (audio != null && foco != null) audio.abandonAudioFocusRequest(foco);
    }

    // ---------------------------------------------------------------- pantalla completa

    /**
     * Si la app tiene el permiso "Mostrar sobre otras apps", abre la pantalla del recordatorio directamente
     * (más fiable en Honor que solo la notificación de pantalla completa).
     */
    private void abrirPantalla() {
        if (!android.provider.Settings.canDrawOverlays(this)) return;
        try {
            startActivity(intentPantalla());
        } catch (Exception ignored) {
        }
    }

    private Intent intentPantalla() {
        Intent pantalla = new Intent(this, AlarmaActivity.class);
        pantalla.putExtra(Alarmas.EXTRA_ID, idActual);
        pantalla.putExtra(Alarmas.EXTRA_REAVISOS, reavisos);
        pantalla.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        return pantalla;
    }

    // ---------------------------------------------------------------- notificación

    private Notification notificacionPrimerPlano() {
        String titulo = Acciones.nombre(this, idActual);
        String texto;
        String completo;
        if (idActual == Alarmas.ID_RESUMEN) {
            texto = "Buenos días: tus pendientes de hoy";
            completo = Resumen.listaDeHoy(this);
        } else if (idActual == Alarmas.ID_PRUEBA) {
            texto = "Prueba del recordatorio hablado";
            completo = texto + "\nToca Hecho para terminar.";
        } else {
            Evento e = DbHelper.get(this).obtener(idActual);
            if (e == null) {
                texto = "Recordatorio";
                completo = texto;
            } else {
                String cuando = Formato.capitalizar(Formato.fechaHablada(e.fechaHora().toLocalDate(), LocalDate.now()))
                        + " a las " + Formato.hora(e.fechaHora().toLocalTime());
                texto = (e.esReunion() ? "Reunión" : "Tarea") + " · " + cuando;
                StringBuilder sb = new StringBuilder();
                if (esPrevio) sb.append("Empieza en ").append(e.avisoMin).append(" minutos\n");
                sb.append(e.esReunion() ? "Reunión: " : "Tarea: ").append(e.titulo).append('\n');
                sb.append(cuando);
                if (!e.participantes.isEmpty()) sb.append("\nCon: ").append(e.participantes);
                if (!esPrevio) sb.append("\n\nToca Hecho cuando termines, o Posponer.");
                completo = sb.toString();
            }
        }

        PendingIntent pi = PendingIntent.getActivity(this, 3, intentPantalla(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, Alarmas.CANAL)
                .setSmallIcon(R.drawable.ic_notificacion)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(completo))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(pi);
        if (!esPrevio) {
            b.setFullScreenIntent(pi, true);
            b.addAction(Acciones.accion(this, idActual, Acciones.HECHO, "Hecho", 0));
            b.addAction(Acciones.accion(this, idActual, Acciones.POSPONER, "Posponer 10 min", 10));
            b.addAction(Acciones.accion(this, idActual, Acciones.SILENCIAR, "Silenciar", 0));
        }
        return b.build();
    }

    // ---------------------------------------------------------------- fin

    @Override
    public void onDestroy() {
        idInsistiendo = Long.MIN_VALUE;
        h.removeCallbacksAndMessages(null);
        callar();
        if (tts != null) tts.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID_PRIMER_PLANO);
        Intent cerrar = new Intent(ACCION_CERRAR_PANTALLA);
        cerrar.setPackage(getPackageName());
        sendBroadcast(cerrar);
        EscuchaService.reanudar(this, EscuchaService.MOTIVO_RECORDATORIO);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
