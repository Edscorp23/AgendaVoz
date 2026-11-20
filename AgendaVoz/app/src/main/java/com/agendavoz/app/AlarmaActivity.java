package com.agendavoz.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Pantalla que aparece cuando llega un recordatorio, incluso con el teléfono bloqueado.
 * Ofrece: Hecho, Posponer (5, 10, 30 min, 1 hora), Responder por voz y Silenciar.
 */
public class AlarmaActivity extends Activity {

    private static final int REQ_VOZ = 21;

    private long id;
    private TextView estado;
    private final VozParser parser = new VozParser();

    private final BroadcastReceiver cerrar = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mostrarSobreBloqueo();
        setContentView(R.layout.activity_alarma);

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
        findViewById(R.id.btnVoz).setOnClickListener(v -> responderPorVoz());
        findViewById(R.id.btnSilenciar).setOnClickListener(v -> {
            Acciones.silenciar(this, id);
            finish();
        });

        IntentFilter f = new IntentFilter(RecordatorioService.ACCION_CERRAR_PANTALLA);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(cerrar, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cerrar, f);
        }
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

    private void hecho() {
        Acciones.hecho(this, id);
        Toast.makeText(this, "¡Bien! Marcado como hecho.", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void posponer(int minutos) {
        Acciones.posponer(this, id, minutos, 0, false);
        String t = minutos >= 60 ? (minutos / 60) + " hora" + (minutos >= 120 ? "s" : "") : minutos + " minutos";
        Toast.makeText(this, "Te lo recuerdo en " + t + ".", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void responderPorVoz() {
        // Pausa la voz del recordatorio para poder escuchar a la persona
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
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Di «listo», «posponer 10 minutos» o «silencio»");
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
        String dicho = r.get(0);
        int resp = parser.interpretarRespuestaRecordatorio(dicho);
        if (resp == VozParser.RESP_HECHO) {
            hecho();
        } else if (resp == VozParser.RESP_SILENCIAR) {
            Acciones.silenciar(this, id);
            finish();
        } else if (resp > 0) {
            posponer(resp);
        } else {
            estado.setText("No entendí «" + dicho + "». Di «listo», «posponer 10 minutos» o usa los botones.");
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(cerrar);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
