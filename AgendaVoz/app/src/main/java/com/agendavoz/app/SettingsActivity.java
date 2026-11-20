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
    private CheckBox checkIa;
    private TextView resultadoIa, estadoPermisos;

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
        findViewById(R.id.botonNotificaciones).setOnClickListener(v -> abrirNotificaciones(this));
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
        PowerManager pm = getSystemService(PowerManager.class);
        boolean sinOpt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean notif = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        String s = (notif ? "✔" : "✘") + " Notificaciones\n"
                + (Alarmas.puedeExactas(this) ? "✔" : "✘") + " Alarmas exactas\n"
                + (sinOpt ? "✔" : "✘") + " Sin optimización de batería\n"
                + "• Inicio automático (Honor): revísalo con el botón de abajo";
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
