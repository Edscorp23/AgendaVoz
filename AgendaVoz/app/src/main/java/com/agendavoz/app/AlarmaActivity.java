package com.agendavoz.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Pantalla que aparece cuando llega un recordatorio, incluso con el teléfono bloqueado.
 * Ofrece: Hecho, Posponer (5, 10, 30 min, 1 hora), Responder por voz y Silenciar.
 *
 * Además, entre una repetición y otra ESCUCHA sola: se puede decir
 * «Honor, finaliza el recordatorio», «listo», «pospón», «pospón 20 minutos» o «silencio».
 */
public class AlarmaActivity extends Activity {

    private static final int REQ_VOZ = 21;

    private long id;
    private TextView estado;
    private final VozParser parser = new VozParser();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable escuchar = this::empezarAEscuchar;

    private SpeechRecognizer oido;
    private boolean escuchando = false;
    private boolean terminado = false;
    private boolean enPantalla = false;
    private AudioManager audio;
    private boolean musicaSilenciada = false;
    private TextToSpeech vozConfirmacion;
    private boolean vozConfirmacionLista = false;

    private final BroadcastReceiver avisos = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (RecordatorioService.ACCION_CERRAR_PANTALLA.equals(a)) {
                if (!terminado) finish();
            } else if (RecordatorioService.ACCION_HABLANDO.equals(a)) {
                dejarDeEscuchar();
                estado.setText("Hablando… luego podrás responder.");
            } else if (RecordatorioService.ACCION_CALLADO.equals(a)) {
                h.postDelayed(escuchar, 400);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mostrarSobreBloqueo();
        setContentView(R.layout.activity_alarma);
        audio = getSystemService(AudioManager.class);

        id = getIntent().getLongExtra(Alarmas.EXTRA_ID, Long.MIN_VALUE);
        if (id == Long.MIN_VALUE) {
            finish();
            return;
        }

        TextView tipo = findViewById(R.id.alarmaTipo);
        TextView titulo = findViewById(R.id.alarmaTitulo);
        TextView detalle = findViewById(R.id.alarmaDetalle);
        estado = findViewById(R.id.alarmaEstado);

        if (id == Alarmas.ID_PRUEBA) {
            tipo.setText("PRUEBA");
            titulo.setText("Recordatorio de prueba");
            detalle.setText("Así aparecerán tus reuniones y tareas");
        } else {
            Evento e = DbHelper.get(this).obtener(id);
            if (e == null || e.hecho) {
                finish();
                return;
            }
            tipo.setText(e.esReunion() ? "REUNIÓN" : "TAREA");
            titulo.setText(e.titulo);
            String d = Formato.etiquetaDia(e.fechaHora().toLocalDate(), java.time.LocalDate.now())
                    + " · " + Formato.hora(e.fechaHora().toLocalTime());
            if (!e.participantes.isEmpty()) d += "\ncon " + e.participantes;
            detalle.setText(d);
        }

        findViewById(R.id.btnHecho).setOnClickListener(v -> hecho());
        findViewById(R.id.btnPos5).setOnClickListener(v -> posponer(5));
        findViewById(R.id.btnPos10).setOnClickListener(v -> posponer(10));
        findViewById(R.id.btnPos30).setOnClickListener(v -> posponer(30));
        findViewById(R.id.btnPos60).setOnClickListener(v -> posponer(60));
        findViewById(R.id.btnVoz).setOnClickListener(v -> responderConDialogo());
        findViewById(R.id.btnSilenciar).setOnClickListener(v -> {
            terminar();
            Acciones.silenciar(this, id);
            finish();
        });

        IntentFilter f = new IntentFilter();
        f.addAction(RecordatorioService.ACCION_CERRAR_PANTALLA);
        f.addAction(RecordatorioService.ACCION_HABLANDO);
        f.addAction(RecordatorioService.ACCION_CALLADO);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(avisos, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(avisos, f);
        }

        vozConfirmacion = Voz.crear(this, status -> {
            if (status == TextToSpeech.SUCCESS && Voz.ponerEspanol(this, vozConfirmacion)) {
                vozConfirmacion.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                vozConfirmacionLista = true;
            }
        });

        if (puedeEscucharSola()) {
            estado.setText("Puedes decir: «Honor, finaliza», «pospón» o «pospón 20 minutos».");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enPantalla = true;
        if (!RecordatorioService.hablando) h.postDelayed(escuchar, 600);
    }

    @Override
    protected void onPause() {
        enPantalla = false;
        dejarDeEscuchar();
        super.onPause();
    }

    @SuppressWarnings("deprecation")
    private void mostrarSobreBloqueo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ================================================================= acciones

    private void hecho() {
        terminar();
        Acciones.hecho(this, id);
        confirmarYSalir("Listo, recordatorio finalizado.", "¡Bien! Marcado como hecho.");
    }

    private void posponer(int minutos) {
        terminar();
        Acciones.posponer(this, id, minutos, 0, false);
        String t = minutos >= 60 && minutos % 60 == 0
                ? (minutos / 60) + (minutos == 60 ? " hora" : " horas")
                : minutos + " minutos";
        confirmarYSalir("De acuerdo, te lo recuerdo en " + t + ".", "Te lo recuerdo en " + t + ".");
    }

    private void aplicarRespuesta(String dicho) {
        int resp = parser.interpretarRespuestaRecordatorio(dicho);
        if (resp == VozParser.RESP_HECHO) {
            hecho();
        } else if (resp == VozParser.RESP_SILENCIAR) {
            terminar();
            Acciones.silenciar(this, id);
            confirmarYSalir("Silenciado. Queda pendiente.", "Silenciado: queda pendiente.");
        } else if (resp > 0) {
            posponer(resp);
        } else {
            estado.setText("No entendí «" + dicho + "». Di «Honor, finaliza» o «pospón».");
            h.postDelayed(escuchar, 300);
        }
    }

    /** Dice una confirmación corta y cierra la pantalla. */
    private void confirmarYSalir(String hablado, String escrito) {
        Toast.makeText(this, escrito, Toast.LENGTH_SHORT).show();
        if (vozConfirmacionLista) {
            vozConfirmacion.speak(hablado, TextToSpeech.QUEUE_FLUSH, null, "CONF");
            h.postDelayed(this::finish, 2800);
        } else {
            finish();
        }
    }

    private void terminar() {
        terminado = true;
        dejarDeEscuchar();
    }

    // ================================================================= escucha automática

    private boolean puedeEscucharSola() {
        return Prefs.escucharRespuestas(this)
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && SpeechRecognizer.isRecognitionAvailable(this);
    }

    private void empezarAEscuchar() {
        if (terminado || !enPantalla || escuchando || RecordatorioService.hablando || !puedeEscucharSola()) return;
        try {
            if (oido == null) {
                oido = SpeechRecognizer.createSpeechRecognizer(this);
                oido.setRecognitionListener(new Oyente());
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.idioma(this));
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            silenciarPitidos(true);
            escuchando = true;
            oido.startListening(i);
            estado.setText("🎤 Te escucho: di «Honor, finaliza» o «pospón».");
        } catch (Exception e) {
            escuchando = false;
            silenciarPitidos(false);
        }
    }

    private void dejarDeEscuchar() {
        h.removeCallbacks(escuchar);
        if (oido != null && escuchando) {
            try {
                oido.cancel();
            } catch (Exception ignored) {
            }
        }
        escuchando = false;
        silenciarPitidos(false);
    }

    /** El reconocedor hace un "bip" cada vez que empieza; se baja la música un momento para no oírlo. */
    private void silenciarPitidos(boolean silenciar) {
        if (audio == null || silenciar == musicaSilenciada) return;
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    silenciar ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            musicaSilenciada = silenciar;
        } catch (Exception ignored) {
        }
    }

    private class Oyente implements RecognitionListener {
        @Override
        public void onResults(Bundle resultados) {
            escuchando = false;
            silenciarPitidos(false);
            if (terminado) return;
            ArrayList<String> r = resultados.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty()) {
                h.postDelayed(escuchar, 300);
                return;
            }
            // Se prueba cada alternativa hasta encontrar una orden conocida.
            for (String alternativa : r) {
                if (parser.interpretarRespuestaRecordatorio(alternativa) != VozParser.RESP_NO_ENTENDIDA) {
                    aplicarRespuesta(alternativa);
                    return;
                }
            }
            aplicarRespuesta(r.get(0));
        }

        @Override
        public void onError(int error) {
            escuchando = false;
            silenciarPitidos(false);
            if (terminado) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                estado.setText("Falta el permiso del micrófono. Usa los botones.");
                return;
            }
            long espera = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1500 : 400;
            h.postDelayed(escuchar, espera);
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    }

    // ================================================================= respuesta con el diálogo de Google

    private void responderConDialogo() {
        dejarDeEscuchar();
        Intent pausa = new Intent(this, RecordatorioService.class);
        pausa.setAction(RecordatorioService.ACCION_PAUSAR);
        try {
            startService(pausa);
        } catch (Exception ignored) {
        }

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.idioma(this));
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Di «finaliza», «pospón 10 minutos» o «silencio»");
        try {
            startActivityForResult(i, REQ_VOZ);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "El dictado por voz no está disponible. Usa los botones.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOZ || resultCode != RESULT_OK || data == null) return;
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) return;
        aplicarRespuesta(r.get(0));
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        dejarDeEscuchar();
        if (oido != null) {
            try {
                oido.destroy();
            } catch (Exception ignored) {
            }
        }
        if (vozConfirmacion != null) vozConfirmacion.shutdown();
        try {
            unregisterReceiver(avisos);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
