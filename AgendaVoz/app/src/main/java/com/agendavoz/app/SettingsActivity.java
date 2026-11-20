package com.agendavoz.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDateTime;

public class SettingsActivity extends Activity {

    private EditText campoAviso, campoIdioma, campoUrl, campoToken, campoRepetir, campoInsistir, campoReavisar;
    private CheckBox checkIa, checkEscuchar, checkCalendario, checkHonor;
    private TextView estadoCalendario, estadoHonor;
    private static final int REQ_CALENDARIO = 31;
    private TextView resultadoIa, estadoPermisos, estadoVoz;
    private android.speech.tts.TextToSpeech ttsPrueba;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        campoAviso = findViewById(R.id.campoAviso);
        campoIdioma = findViewById(R.id.campoIdioma);
        campoUrl = findViewById(R.id.campoUrl);
        campoToken = findViewById(R.id.campoToken);
        campoRepetir = findViewById(R.id.campoRepetir);
        campoInsistir = findViewById(R.id.campoInsistir);
        campoReavisar = findViewById(R.id.campoReavisar);
        checkIa = findViewById(R.id.checkIa);
        resultadoIa = findViewById(R.id.resultadoIa);
        estadoPermisos = findViewById(R.id.estadoPermisos);
        estadoVoz = findViewById(R.id.estadoVoz);
        checkEscuchar = findViewById(R.id.checkEscuchar);
        checkCalendario = findViewById(R.id.checkCalendario);
        estadoCalendario = findViewById(R.id.estadoCalendario);

        checkEscuchar.setChecked(Prefs.escucharRespuestas(this));
        checkEscuchar.setOnCheckedChangeListener((b, si) -> {
            Prefs.guardarEscucharRespuestas(this, si);
            if (si && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 32);
            }
        });

        checkCalendario.setChecked(Prefs.leerCalendario(this) && Calendario.tienePermiso(this));
        checkCalendario.setOnCheckedChangeListener((b, si) -> {
            Prefs.guardarLeerCalendario(this, si);
            if (si && !Calendario.tienePermiso(this)) {
                requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, REQ_CALENDARIO);
            } else {
                Calendario.programarRevision(this);
                if (si) revisarCalendario();
                else estadoCalendario.setText("Desactivado.");
            }
        });
        findViewById(R.id.botonSincronizar).setOnClickListener(v -> revisarCalendario());

        CheckBox checkResumen = findViewById(R.id.checkResumen);
        checkResumen.setChecked(Prefs.resumenActivo(this));
        checkResumen.setOnCheckedChangeListener((b, si) -> {
            Prefs.guardarResumenActivo(this, si);
            Resumen.programar(this);
        });

        CheckBox checkHonorNoche = findViewById(R.id.checkHonorNoche);
        checkHonorNoche.setChecked(Prefs.honorDescansaDeNoche(this));
        checkHonorNoche.setOnCheckedChangeListener((b, si) -> {
            Prefs.guardarHonorDescansaDeNoche(this, si);
            if (Prefs.activarConHonor(this)) {
                EscuchaService.detener(this);
                ui.postDelayed(() -> EscuchaService.iniciar(this), 800);
            }
        });

        checkHonor = findViewById(R.id.checkHonor);
        estadoHonor = findViewById(R.id.estadoHonor);
        checkHonor.setChecked(Prefs.activarConHonor(this));
        checkHonor.setOnCheckedChangeListener((b, si) -> {
            if (si && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 33);
            }
            if (si && !EscuchaService.modeloIncluido(this)) {
                estadoHonor.setText("✘ Esta APK no trae el modelo de voz. Compílala con el archivo de GitHub Actions actualizado.");
                b.setChecked(false);
                return;
            }
            Prefs.guardarActivarConHonor(this, si);
            if (si) {
                EscuchaService.iniciar(this);
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Recomendado: toca «Permitir que el recordatorio aparezca en pantalla».",
                            Toast.LENGTH_LONG).show();
                }
            } else {
                EscuchaService.detener(this);
            }
            ui.postDelayed(this::mostrarEstadoHonor, 1500);
        });
        TextView version = findViewById(R.id.versionApp);
        try {
            version.setText("Agenda Voz versión " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {
        }

        findViewById(R.id.botonMotorGoogle).setOnClickListener(v -> Voz.instalarMotorGoogle(this));
        findViewById(R.id.botonInstalarVoz).setOnClickListener(v -> Voz.instalarVozEspanol(this));
        findViewById(R.id.botonAjustesVoz).setOnClickListener(v -> Voz.abrirAjustesDeVoz(this));
        findViewById(R.id.botonSobreApps).setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                abrirInfoApp(this);
            }
        });

        campoAviso.setText(String.valueOf(Prefs.avisoMin(this)));
        campoIdioma.setText(Prefs.idioma(this));
        campoUrl.setText(Prefs.proxyUrl(this));
        campoToken.setText(Prefs.proxyToken(this));
        campoRepetir.setText(String.valueOf(Prefs.repetirSeg(this)));
        campoInsistir.setText(String.valueOf(Prefs.insistirMin(this)));
        campoReavisar.setText(String.valueOf(Prefs.reavisarMin(this)));
        checkIa.setChecked(getSharedPreferences("agenda_voz", MODE_PRIVATE).getBoolean("ia_activa", true));

        Button guardar = findViewById(R.id.botonGuardar);
        guardar.setOnClickListener(v -> {
            guardar();
            Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.botonProbarIa).setOnClickListener(v -> probarIa());
        findViewById(R.id.botonInicioAuto).setOnClickListener(v -> abrirInicioAutomatico(this));
        findViewById(R.id.botonBateria).setOnClickListener(v -> pedirSinOptimizacion(this));
        findViewById(R.id.botonNotificaciones).setOnClickListener(v -> Permisos.abrirAjustesDeAvisos(this));
        Button exactas = findViewById(R.id.botonExactas);
        exactas.setOnClickListener(v -> abrirAlarmasExactas(this));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) exactas.setVisibility(android.view.View.GONE);

        findViewById(R.id.botonProbarAlarma).setOnClickListener(v -> {
            Alarmas.crearCanal(this);
            Alarmas.probarEnUnMinuto(this);
            Toast.makeText(this, "Recordatorio de prueba en 1 minuto. Bloquea el teléfono y espera.", Toast.LENGTH_LONG).show();
        });

        findViewById(R.id.botonProbarVoz).setOnClickListener(v -> {
            guardar();
            Intent s = new Intent(this, RecordatorioService.class);
            s.setAction(RecordatorioService.ACCION_INICIAR);
            s.putExtra(Alarmas.EXTRA_ID, Alarmas.ID_PRUEBA);
            s.putExtra(Alarmas.EXTRA_PREVIO, false);
            startForegroundService(s);
            Intent pantalla = new Intent(this, AlarmaActivity.class);
            pantalla.putExtra(Alarmas.EXTRA_ID, Alarmas.ID_PRUEBA);
            startActivity(pantalla);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        actualizarEstado();
        revisarVoz();
        mostrarEstadoHonor();
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    private void mostrarEstadoHonor() {
        if (estadoHonor == null) return;
        if (!Prefs.activarConHonor(this)) {
            estadoHonor.setText("Desactivado.");
        } else {
            estadoHonor.setText((EscuchaService.activo ? "Estado: " + EscuchaService.estado : "Estado: detenido")
                    + "\n(Mientras esta app está abierta, «Honor» queda en pausa.)");
        }
    }

    private void revisarCalendario() {
        if (!Prefs.leerCalendario(this)) {
            estadoCalendario.setText("Primero activa «Leer eventos del calendario del teléfono».");
            return;
        }
        if (!Calendario.tienePermiso(this)) {
            requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, REQ_CALENDARIO);
            return;
        }
        estadoCalendario.setText("Revisando el calendario…");
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            int nuevos = Calendario.sincronizar(this);
            ui.post(() -> estadoCalendario.setText(nuevos < 0 ? "No se pudo leer el calendario."
                    : nuevos == 0 ? "✔ Calendario al día. No hay eventos nuevos."
                    : "✔ Importados " + nuevos + (nuevos == 1 ? " evento nuevo." : " eventos nuevos.")));
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CALENDARIO) {
            if (ok) {
                Calendario.programarRevision(this);
                revisarCalendario();
            } else {
                Prefs.guardarLeerCalendario(this, false);
                checkCalendario.setChecked(false);
                estadoCalendario.setText("Sin permiso para leer el calendario.");
            }
        } else if (requestCode == 32 && !ok) {
            checkEscuchar.setChecked(false);
        } else if (requestCode == 33) {
            if (ok) {
                EscuchaService.iniciar(this);
            } else {
                Prefs.guardarActivarConHonor(this, false);
                checkHonor.setChecked(false);
            }
            mostrarEstadoHonor();
        }
    }

    @Override
    protected void onDestroy() {
        if (ttsPrueba != null) ttsPrueba.shutdown();
        super.onDestroy();
    }

    /** Comprueba qué motor de voz hay y si tiene español. */
    private void revisarVoz() {
        boolean google = Voz.googleInstalado(this);
        findViewById(R.id.botonMotorGoogle).setVisibility(google ? android.view.View.GONE : android.view.View.VISIBLE);
        estadoVoz.setText("Comprobando la voz…");
        if (ttsPrueba != null) ttsPrueba.shutdown();
        final String motor = Voz.motorPreferido(this);
        ttsPrueba = Voz.crear(this, status -> {
            String t;
            if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                t = "✘ El motor de voz no respondió.";
            } else if (Voz.ponerEspanol(this, ttsPrueba)) {
                t = "✔ Voz en español lista";
            } else {
                t = "✘ Falta la voz en español: toca «Descargar la voz en español».";
            }
            t += "\nMotor: " + (motor != null ? "Google" : "el del teléfono")
                    + (google ? "" : "\nRecomendado: instala «Servicios de voz de Google».");
            estadoVoz.setText(t);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        guardar();
    }

    private void guardar() {
        int aviso = 10;
        try {
            aviso = Integer.parseInt(campoAviso.getText().toString().trim());
        } catch (Exception ignored) {
        }
        Prefs.guardar(this, campoUrl.getText().toString(), campoToken.getText().toString(),
                checkIa.isChecked(), aviso, campoIdioma.getText().toString());
        Prefs.guardarRecordatorio(this, entero(campoRepetir, 30), entero(campoInsistir, 3), entero(campoReavisar, 10));
    }

    private static int entero(EditText campo, int porDefecto) {
        try {
            return Integer.parseInt(campo.getText().toString().trim());
        } catch (Exception e) {
            return porDefecto;
        }
    }

    private void actualizarEstado() {
        boolean notif = Permisos.notificaciones(this);
        String s = (notif ? "✔" : "✘") + " Notificaciones permitidas\n"
                + (notif && Permisos.avisosEmergentes(this) ? "✔" : "✘") + " Avisos emergentes (banners)\n"
                + (Permisos.mostrarEnPantalla(this) ? "✔" : "✘") + " Mostrar el recordatorio en pantalla\n"
                + (Alarmas.puedeExactas(this) ? "✔" : "✘") + " Alarmas exactas\n"
                + (Permisos.sinOptimizacionBateria(this) ? "✔" : "✘") + " Sin optimización de batería\n"
                + (Permisos.microfono(this) ? "✔" : "✘") + " Micrófono\n"
                + "• Inicio automático (Honor): revísalo con el botón de abajo\n"
                + "• En «Ajustes de notificaciones», activa también «Pantalla de bloqueo: mostrar»";
        estadoPermisos.setText(s);
    }

    private void probarIa() {
        guardar();
        String url = Prefs.proxyUrl(this);
        if (url.isEmpty()) {
            resultadoIa.setText("Primero pega la URL de tu Apps Script.");
            return;
        }
        if (!ProxyClient.hayInternet(this)) {
            resultadoIa.setText("No hay conexión a internet.");
            return;
        }
        resultadoIa.setText("Consultando…");
        String token = Prefs.proxyToken(this);
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String texto;
            try {
                VozParser.Resultado r = ProxyClient.analizar(url, token,
                        "reunión con Carlos mañana a las 3", LocalDateTime.now());
                texto = "✔ Funciona (" + r.origen + ")\n" + r;
            } catch (Exception e) {
                texto = "✘ Error: " + e.getMessage();
            }
            final String t = texto;
            ui.post(() -> resultadoIa.setText(t));
        }).start();
    }

    // ------------------------------------------------------------ ayudas para Honor / Huawei

    /** Abre la pantalla de "Inicio de aplicaciones" de Honor/Huawei; si no existe, la info de la app. */
    public static void abrirInicioAutomatico(Context ctx) {
        ComponentName[] candidatos = {
                new ComponentName("com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                new ComponentName("com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.optimize.process.ProtectActivity"),
                new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                new ComponentName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        };
        for (ComponentName cn : candidatos) {
            try {
                Intent i = new Intent();
                i.setComponent(cn);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            } catch (Exception ignored) {
            }
        }
        abrirInfoApp(ctx);
        Toast.makeText(ctx, "Busca «Batería» › «Inicio de aplicaciones» y permite Agenda Voz",
                Toast.LENGTH_LONG).show();
    }

    public static void pedirSinOptimizacion(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e2) {
                abrirInfoApp(ctx);
            }
        }
    }

    public static void abrirNotificaciones(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            abrirInfoApp(ctx);
        }
    }

    public static void abrirAlarmasExactas(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        abrirInfoApp(ctx);
    }

    public static void abrirInfoApp(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
