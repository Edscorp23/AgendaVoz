package com.agendavoz.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pantalla principal: escucha, entiende, pregunta lo que falta, confirma y guarda.
 * Sigue el mismo ciclo que Alexa: voz → texto → intención + datos → diálogo → acción → respuesta hablada.
 */
public class MainActivity extends Activity {

    private static final int REQ_VOZ = 10;
    private static final int REQ_PERMISOS = 11;

    // Estados del diálogo
    private static final int NORMAL = 0;
    private static final int ESP_FECHA = 1;
    private static final int ESP_HORA = 2;
    private static final int ESP_AMPM = 3;
    private static final int ESP_CONFIRMAR = 4;
    private static final int ESP_CONFIRMAR_BORRAR = 5;
    private static final int ESP_CONFIRMAR_CAMBIO = 6;

    private int estado = NORMAL;
    private boolean modoCambio = false;
    private VozParser.Resultado pendiente;
    private Evento eventoObjetivo;
    private LocalDateTime nuevaFechaHora;

    private final VozParser parser = new VozParser();
    private final ExecutorService fondo = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private boolean ttsListo = false;
    private AlertDialog dialogoActual;
    private boolean verCompletados = false;
    private boolean enPrimerPlano = false;
    private int intentosConfirmacion = 0;

    private TextView textoEstado, textoOido, tituloLista, vacio;
    private ImageButton botonMic;
    private EditText entrada;
    private EventoAdapter adapter;

    // ================================================================= ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textoEstado = findViewById(R.id.textoEstado);
        textoOido = findViewById(R.id.textoOido);
        tituloLista = findViewById(R.id.tituloLista);
        vacio = findViewById(R.id.vacio);
        botonMic = findViewById(R.id.botonMic);
        entrada = findViewById(R.id.entradaTexto);
        ListView lista = findViewById(R.id.lista);

        adapter = new EventoAdapter(this);
        lista.setAdapter(adapter);
        lista.setEmptyView(vacio);
        lista.setOnItemClickListener((p, v, pos, id) -> opcionesEvento(adapter.getItem(pos)));

        botonMic.setOnClickListener(v -> escuchar());
        Button enviar = findViewById(R.id.botonEnviar);
        enviar.setOnClickListener(v -> enviarTexto());
        entrada.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                enviarTexto();
                return true;
            }
            return false;
        });

        findViewById(R.id.botonAjustes).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));
        Button completados = findViewById(R.id.botonCompletados);
        completados.setOnClickListener(v -> {
            verCompletados = !verCompletados;
            completados.setText(verCompletados ? "Pendientes" : "Hechas");
            refrescar();
        });

        Alarmas.crearCanal(this);
        iniciarVoz();
        pedirPermisos();
        if (!Prefs.configuracionInicialHecha(this)) mostrarConfiguracionInicial();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enPrimerPlano = true;
        refrescar();
    }

    @Override
    protected void onPause() {
        enPrimerPlano = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cerrarDialogo();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        fondo.shutdown();
        super.onDestroy();
    }

    // ================================================================= voz: entrada y salida

    private void iniciarVoz() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            Locale loc = Locale.forLanguageTag(Prefs.idioma(this));
            int r = tts.setLanguage(loc);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                r = tts.setLanguage(Locale.forLanguageTag("es"));
            }
            ttsListo = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    if (id != null && id.startsWith("ESCUCHAR")) ui.post(MainActivity.this::escucharSiVisible);
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onError(String id) {
                    if (id != null && id.startsWith("ESCUCHAR")) ui.post(MainActivity.this::escucharSiVisible);
                }
            });
        });
    }

    /** Muestra y dice un texto. Si luegoEscuchar es true, abre el micrófono al terminar de hablar. */
    private void hablar(String texto, boolean luegoEscuchar) {
        textoEstado.setText(texto);
        if (ttsListo) {
            String id = (luegoEscuchar ? "ESCUCHAR_" : "DECIR_") + System.nanoTime();
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id);
        } else if (luegoEscuchar) {
            ui.postDelayed(this::escucharSiVisible, 700);
        }
    }

    /** Solo abre el micrófono automáticamente si la app está en pantalla. */
    private void escucharSiVisible() {
        if (enPrimerPlano) escuchar();
    }

    private void escuchar() {
        if (isFinishing()) return;
        if (tts != null) tts.stop();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.idioma(this));
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, indicacionSegunEstado());
        try {
            startActivityForResult(i, REQ_VOZ);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Este teléfono no tiene dictado por voz activo. Instala o actualiza la app de Google, "
                    + "o escribe la orden en la caja de texto.", Toast.LENGTH_LONG).show();
            entrada.requestFocus();
        }
    }

    private String indicacionSegunEstado() {
        switch (estado) {
            case ESP_FECHA:
                return "¿Para qué día?";
            case ESP_HORA:
                return "¿A qué hora?";
            case ESP_AMPM:
                return "¿De la mañana o de la tarde?";
            case ESP_CONFIRMAR:
            case ESP_CONFIRMAR_BORRAR:
            case ESP_CONFIRMAR_CAMBIO:
                return "Di «sí» o «no»";
            default:
                return "Te escucho…";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VOZ) return;
        if (resultCode == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty() && r.get(0) != null && !r.get(0).trim().isEmpty()) {
                procesar(r.get(0).trim());
                return;
            }
        }
        if (estado == ESP_FECHA || estado == ESP_HORA || estado == ESP_AMPM) {
            textoOido.setText("Toca el micrófono para responder, o escribe la respuesta.");
        }
    }

    private void enviarTexto() {
        String t = entrada.getText().toString().trim();
        if (t.isEmpty()) return;
        entrada.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(entrada.getWindowToken(), 0);
        procesar(t);
    }

    // ================================================================= cerebro del diálogo

    private void procesar(String texto) {
        textoOido.setText("«" + texto + "»");
        LocalDateTime ahora = LocalDateTime.now();
        switch (estado) {
            case ESP_FECHA: {
                VozParser.Resultado r = parser.analizar(texto, ahora);
                if (r.fecha == null) {
                    hablar("No entendí el día. Dime, por ejemplo: mañana, el viernes o el 25 de noviembre.", true);
                    return;
                }
                pendiente.fecha = r.fecha;
                if (pendiente.hora == null && pendiente.horaAmbigua == null) tomarHora(pendiente, r);
                continuar();
                return;
            }
            case ESP_HORA: {
                VozParser.Resultado r = parser.analizar(texto, ahora);
                if (r.hora == null && r.horaAmbigua == null) {
                    r = parser.analizar("a las " + texto, ahora); // respuestas cortas: "tres", "15:30"
                }
                if (r.hora == null && r.horaAmbigua == null) {
                    hablar("No entendí la hora. Dime, por ejemplo: a las 3 de la tarde.", true);
                    return;
                }
                tomarHora(pendiente, r);
                if (modoCambio) continuarCambio();
                else continuar();
                return;
            }
            case ESP_AMPM: {
                Boolean pm = parser.interpretarAmPm(texto);
                if (pm == null) {
                    VozParser.Resultado r = parser.analizar(texto, ahora);
                    if (r.hora != null) {
                        pendiente.hora = r.hora;
                        pendiente.horaAmbigua = null;
                    } else {
                        hablar("¿De la mañana o de la tarde?", true);
                        return;
                    }
                } else {
                    int h = pendiente.horaAmbigua;
                    if (pm && h < 12) h += 12;
                    if (!pm && h == 12) h = 0;
                    pendiente.hora = LocalTime.of(h, pendiente.minutoAmbiguo);
                    pendiente.horaAmbigua = null;
                }
                if (modoCambio) continuarCambio();
                else continuar();
                return;
            }
            case ESP_CONFIRMAR:
            case ESP_CONFIRMAR_BORRAR:
            case ESP_CONFIRMAR_CAMBIO: {
                Boolean si = parser.interpretarSiNo(texto);
                if (si == null) {
                    intentosConfirmacion++;
                    if (intentosConfirmacion < 2) {
                        hablar("Perdona, ¿sí o no?", true);
                    } else {
                        cerrarDialogo();
                        cancelar("No entendí si era sí o no, así que no hice cambios. Repite la orden cuando quieras.");
                    }
                    return;
                }
                int e = estado;
                cerrarDialogo();
                if (!si) {
                    cancelar("De acuerdo, no hice cambios.");
                } else if (e == ESP_CONFIRMAR) {
                    guardarPendiente();
                } else if (e == ESP_CONFIRMAR_BORRAR) {
                    ejecutarBorrado();
                } else {
                    ejecutarCambio();
                }
                return;
            }
            default:
                analizarFrase(texto);
        }
    }

    private static void tomarHora(VozParser.Resultado destino, VozParser.Resultado origen) {
        if (origen.hora != null) {
            destino.hora = origen.hora;
            destino.horaAmbigua = null;
        } else if (origen.horaAmbigua != null) {
            destino.horaAmbigua = origen.horaAmbigua;
            destino.minutoAmbiguo = origen.minutoAmbiguo;
        }
    }

    /** Analiza una orden nueva: primero con IA (si hay internet y está configurada) y si no, localmente. */
    private void analizarFrase(String texto) {
        final LocalDateTime ahora = LocalDateTime.now();
        final VozParser.Resultado local = parser.analizar(texto, ahora);
        if (!Prefs.iaActiva(this) || !ProxyClient.hayInternet(this)) {
            manejar(local);
            return;
        }
        textoEstado.setText("Pensando…");
        botonMic.setEnabled(false);
        final String url = Prefs.proxyUrl(this);
        final String token = Prefs.proxyToken(this);
        fondo.execute(() -> {
            VozParser.Resultado res;
            try {
                res = combinar(ProxyClient.analizar(url, token, texto, ahora), local);
            } catch (Exception e) {
                res = local; // sin conexión o error: el intérprete local responde
            }
            final VozParser.Resultado fin = res;
            ui.post(() -> {
                botonMic.setEnabled(true);
                manejar(fin);
            });
        });
    }

    /** Completa lo que la IA no devolvió con lo que encontró el intérprete local. */
    private static VozParser.Resultado combinar(VozParser.Resultado ia, VozParser.Resultado local) {
        if (ia.fecha == null) ia.fecha = local.fecha;
        if (ia.hora == null && ia.horaAmbigua == null) tomarHora(ia, local);
        if (ia.participantes.isEmpty()) ia.participantes.addAll(local.participantes);
        if (ia.objetivo.isEmpty()) ia.objetivo = local.objetivo;
        if (VozParser.LISTAR.equals(ia.intencion) && ia.rango.isEmpty()) ia.rango = local.rango;
        return ia;
    }

    private void manejar(VozParser.Resultado r) {
        pendiente = r;
        modoCambio = false;
        switch (r.intencion) {
            case VozParser.LISTAR:
                listar(r);
                break;
            case VozParser.ELIMINAR:
                prepararBorrado(r);
                break;
            case VozParser.MODIFICAR:
                prepararCambio(r);
                break;
            default:
                continuar();
        }
    }

    /** Pregunta lo que falte (día, hora, mañana/tarde) y luego pide confirmación. */
    private void continuar() {
        VozParser.Resultado r = pendiente;
        String que = VozParser.CREAR_REUNION.equals(r.intencion) ? "la reunión" : "la tarea";
        if (r.fecha == null) {
            estado = ESP_FECHA;
            hablar("¿Para qué día es " + que + "?", true);
            return;
        }
        if (r.hora == null && r.horaAmbigua == null) {
            estado = ESP_HORA;
            hablar("¿A qué hora?", true);
            return;
        }
        if (r.hora == null) {
            estado = ESP_AMPM;
            String h = (r.horaAmbigua == 1 ? "la " : "las ") + r.horaAmbigua
                    + (r.minutoAmbiguo > 0 ? (r.minutoAmbiguo == 30 ? " y media" : " y " + r.minutoAmbiguo) : "");
            hablar("¿A " + h + " de la mañana o de la tarde?", true);
            return;
        }
        LocalDateTime cuando = r.fecha.atTime(r.hora);
        if (cuando.isBefore(LocalDateTime.now())) {
            r.fecha = null;
            r.hora = null;
            estado = ESP_FECHA;
            hablar("Esa fecha y hora ya pasaron. ¿Para qué día es " + que + "?", true);
            return;
        }
        estado = ESP_CONFIRMAR;
        String resumen = describir(r);
        mostrarDialogo("¿Guardar?", resumen, this::guardarPendiente, () -> cancelar("Cancelado."));
        hablar("¿Guardo " + resumen + "?", true);
    }

    private String describir(VozParser.Resultado r) {
        String tipo = VozParser.CREAR_REUNION.equals(r.intencion) ? "la reunión" : "la tarea";
        return tipo + " «" + r.titulo + "», " + Formato.fechaHablada(r.fecha, LocalDate.now())
                + ", a " + Formato.horaHablada(r.hora);
    }

    private String describir(Evento e) {
        LocalDateTime f = e.fechaHora();
        return (e.esReunion() ? "la reunión «" : "la tarea «") + e.titulo + "» de "
                + Formato.fechaHablada(f.toLocalDate(), LocalDate.now()) + " a " + Formato.horaHablada(f.toLocalTime());
    }

    private void guardarPendiente() {
        VozParser.Resultado r = pendiente;
        estado = NORMAL;
        if (r == null || r.fecha == null || r.hora == null) return;
        Evento e = new Evento();
        e.tipo = VozParser.CREAR_REUNION.equals(r.intencion) ? Evento.REUNION : Evento.TAREA;
        e.titulo = r.titulo;
        e.participantes = String.join(", ", r.participantes);
        e.setFechaHora(r.fecha.atTime(r.hora));
        e.avisoMin = Prefs.avisoMin(this);
        DbHelper.get(this).insertar(e);
        Alarmas.programar(this, e);
        pendiente = null;
        refrescar();
        String aviso = e.avisoMin > 0 ? " Te aviso " + e.avisoMin + " minutos antes y a la hora." : " Te aviso a la hora.";
        hablar("Listo. Guardé " + describir(r) + "." + aviso, false);
        if (!Alarmas.puedeExactas(this)) {
            Toast.makeText(this, "Activa «alarmas exactas» en Ajustes para que suene puntual.", Toast.LENGTH_LONG).show();
        }
    }

    private void cancelar(String mensaje) {
        estado = NORMAL;
        modoCambio = false;
        pendiente = null;
        eventoObjetivo = null;
        nuevaFechaHora = null;
        hablar(mensaje, false);
    }

    // ----------------------------------------------------------------- listar

    private void listar(VozParser.Resultado r) {
        estado = NORMAL;
        LocalDate hoy = LocalDate.now();
        LocalDateTime ini;
        LocalDateTime fin;
        String etiqueta;
        if ("dia".equals(r.rango) && r.fecha != null) {
            ini = r.fecha.atStartOfDay();
            fin = ini.plusDays(1);
            etiqueta = Formato.fechaHablada(r.fecha, hoy);
        } else {
            ini = LocalDateTime.now();
            fin = hoy.plusDays(8).atStartOfDay();
            etiqueta = "esta semana";
        }
        List<Evento> l = DbHelper.get(this).pendientesEntre(millis(ini), millis(fin));
        if (l.isEmpty()) {
            hablar("No tienes nada pendiente para " + etiqueta + ".", false);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Para ").append(etiqueta).append(" tienes ").append(l.size())
                .append(l.size() == 1 ? " pendiente: " : " pendientes: ");
        boolean variosDias = !"dia".equals(r.rango);
        int max = Math.min(l.size(), 6);
        for (int i = 0; i < max; i++) {
            Evento e = l.get(i);
            LocalDateTime f = e.fechaHora();
            if (i > 0) sb.append(i == max - 1 ? "; y " : "; ");
            sb.append(e.titulo);
            if (variosDias) sb.append(", ").append(Formato.fechaHablada(f.toLocalDate(), hoy));
            sb.append(", a ").append(Formato.horaHablada(f.toLocalTime()));
        }
        if (l.size() > max) sb.append("; y ").append(l.size() - max).append(" más");
        sb.append(".");
        hablar(sb.toString(), false);
    }

    // ----------------------------------------------------------------- borrar

    private void prepararBorrado(VozParser.Resultado r) {
        Evento e = buscar(r, true);
        if (e == null) {
            cancelar("No encontré esa reunión o tarea. Revisa la lista y tócala para borrarla.");
            return;
        }
        eventoObjetivo = e;
        estado = ESP_CONFIRMAR_BORRAR;
        String d = describir(e);
        mostrarDialogo("¿Borrar?", Formato.capitalizar(d), this::ejecutarBorrado, () -> cancelar("No borré nada."));
        hablar("¿Borro " + d + "?", true);
    }

    private void ejecutarBorrado() {
        estado = NORMAL;
        if (eventoObjetivo == null) return;
        Alarmas.cancelar(this, eventoObjetivo.id);
        DbHelper.get(this).eliminar(eventoObjetivo.id);
        eventoObjetivo = null;
        refrescar();
        hablar("Listo, lo borré.", false);
    }

    // ----------------------------------------------------------------- modificar

    private void prepararCambio(VozParser.Resultado r) {
        Evento e = buscar(r, false);
        if (e == null) {
            cancelar("No encontré esa reunión o tarea. Puedes tocarla en la lista para cambiarla.");
            return;
        }
        eventoObjetivo = e;
        modoCambio = true;
        if (r.fecha == null && r.hora == null && r.horaAmbigua == null) {
            estado = ESP_HORA;
            hablar("¿A qué hora la cambio?", true);
            return;
        }
        continuarCambio();
    }

    private void continuarCambio() {
        VozParser.Resultado r = pendiente;
        if (r.hora == null && r.horaAmbigua != null) {
            estado = ESP_AMPM;
            hablar("¿A las " + r.horaAmbigua + " de la mañana o de la tarde?", true);
            return;
        }
        LocalDateTime antes = eventoObjetivo.fechaHora();
        LocalDate f = r.fecha != null ? r.fecha : antes.toLocalDate();
        LocalTime t = r.hora != null ? r.hora : antes.toLocalTime();
        nuevaFechaHora = f.atTime(t);
        if (nuevaFechaHora.isBefore(LocalDateTime.now())) {
            cancelar("Esa nueva fecha y hora ya pasaron. No hice cambios.");
            return;
        }
        estado = ESP_CONFIRMAR_CAMBIO;
        String nuevo = Formato.fechaHablada(f, LocalDate.now()) + ", a " + Formato.horaHablada(t);
        mostrarDialogo("¿Cambiar?", Formato.capitalizar(describir(eventoObjetivo)) + "\n→ " + nuevo,
                this::ejecutarCambio, () -> cancelar("No hice cambios."));
        hablar("¿Cambio " + describir(eventoObjetivo) + " para " + nuevo + "?", true);
    }

    private void ejecutarCambio() {
        estado = NORMAL;
        modoCambio = false;
        if (eventoObjetivo == null || nuevaFechaHora == null) return;
        eventoObjetivo.setFechaHora(nuevaFechaHora);
        DbHelper.get(this).actualizar(eventoObjetivo);
        Alarmas.programar(this, eventoObjetivo);
        String d = describir(eventoObjetivo);
        eventoObjetivo = null;
        nuevaFechaHora = null;
        refrescar();
        hablar("Listo. Ahora " + d + ".", false);
    }

    // ----------------------------------------------------------------- búsqueda por palabras

    private static final Set<String> VACIAS = new HashSet<>(Arrays.asList(
            "con", "del", "las", "los", "para", "que", "una", "uno", "este", "esta", "mis", "por"));

    /** Busca el evento pendiente que mejor coincide con las palabras dichas (y con el día, si se pide). */
    private Evento buscar(VozParser.Resultado r, boolean filtrarPorFecha) {
        List<Evento> todos = DbHelper.get(this).pendientesDesde(millis(LocalDate.now().atStartOfDay()));
        List<Evento> candidatos = new ArrayList<>();
        for (Evento e : todos) {
            if (filtrarPorFecha && r.fecha != null && !e.fechaHora().toLocalDate().equals(r.fecha)) continue;
            candidatos.add(e);
        }
        if (candidatos.isEmpty()) return null;

        List<String> palabras = new ArrayList<>();
        String base = r.objetivo.isEmpty() ? r.titulo : r.objetivo;
        for (String p : VozParser.normalizar(base + " " + String.join(" ", r.participantes)).split("\\s+")) {
            if (p.length() > 2 && !VACIAS.contains(p)) palabras.add(p);
        }

        Evento mejor = null;
        int mejorPuntaje = 0;
        for (Evento e : candidatos) {
            String texto = VozParser.normalizar(e.titulo + " " + e.participantes + " "
                    + (e.esReunion() ? "reunion junta cita" : "tarea recordatorio"));
            int puntaje = 0;
            for (String p : palabras) if (texto.contains(p)) puntaje++;
            if (puntaje > mejorPuntaje) {
                mejorPuntaje = puntaje;
                mejor = e;
            }
        }
        if (mejor == null && candidatos.size() == 1 && filtrarPorFecha && r.fecha != null) return candidatos.get(0);
        return mejor;
    }

    // ================================================================= lista y edición manual

    private void refrescar() {
        DbHelper db = DbHelper.get(this);
        List<Evento> l = verCompletados ? db.completados() : db.pendientesDesde(0);
        tituloLista.setText(verCompletados ? "HECHAS" : "PENDIENTES");
        vacio.setText(verCompletados ? "Todavía no marcaste nada como hecho."
                : "No tienes reuniones ni tareas pendientes.");
        adapter.clear();
        adapter.addAll(l);
        adapter.notifyDataSetChanged();
    }

    private void opcionesEvento(Evento e) {
        if (e == null) return;
        String[] ops = e.hecho
                ? new String[]{"Volver a pendientes", "Eliminar"}
                : new String[]{"Marcar como hecha", "Cambiar fecha y hora", "Cambiar título", "Eliminar"};
        new AlertDialog.Builder(this)
                .setTitle(e.titulo)
                .setItems(ops, (d, which) -> {
                    String op = ops[which];
                    DbHelper db = DbHelper.get(this);
                    switch (op) {
                        case "Marcar como hecha":
                            e.hecho = true;
                            db.actualizar(e);
                            Alarmas.cancelar(this, e.id);
                            refrescar();
                            break;
                        case "Volver a pendientes":
                            e.hecho = false;
                            db.actualizar(e);
                            Alarmas.programar(this, e);
                            refrescar();
                            break;
                        case "Cambiar fecha y hora":
                            elegirFechaHora(e);
                            break;
                        case "Cambiar título":
                            cambiarTitulo(e);
                            break;
                        case "Eliminar":
                            new AlertDialog.Builder(this)
                                    .setMessage("¿Eliminar «" + e.titulo + "»?")
                                    .setPositiveButton("Eliminar", (d2, w2) -> {
                                        Alarmas.cancelar(this, e.id);
                                        db.eliminar(e.id);
                                        refrescar();
                                    })
                                    .setNegativeButton("Cancelar", null)
                                    .show();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void elegirFechaHora(Evento e) {
        LocalDateTime f = e.fechaHora();
        new DatePickerDialog(this, (dp, y, m, d) ->
                new TimePickerDialog(this, (tp, h, min) -> {
                    LocalDateTime nueva = LocalDateTime.of(y, m + 1, d, h, min);
                    e.setFechaHora(nueva);
                    DbHelper.get(this).actualizar(e);
                    Alarmas.programar(this, e);
                    refrescar();
                    if (nueva.isBefore(LocalDateTime.now())) {
                        Toast.makeText(this, "Ojo: esa hora ya pasó, no sonará.", Toast.LENGTH_LONG).show();
                    }
                }, f.getHour(), f.getMinute(), true).show(),
                f.getYear(), f.getMonthValue() - 1, f.getDayOfMonth()).show();
    }

    private void cambiarTitulo(Evento e) {
        EditText campo = new EditText(this);
        campo.setText(e.titulo);
        campo.setSelection(e.titulo.length());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        campo.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this)
                .setTitle("Título")
                .setView(campo)
                .setPositiveButton("Guardar", (d, w) -> {
                    String t = campo.getText().toString().trim();
                    if (!t.isEmpty()) {
                        e.titulo = t;
                        DbHelper.get(this).actualizar(e);
                        refrescar();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static class EventoAdapter extends ArrayAdapter<Evento> {
        private final LayoutInflater inflater;

        EventoAdapter(Context ctx) {
            super(ctx, 0, new ArrayList<>());
            inflater = LayoutInflater.from(ctx);
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = inflater.inflate(R.layout.item_evento, parent, false);
            Evento e = getItem(pos);
            if (e == null) return v;
            LocalDateTime f = e.fechaHora();
            TextView dia = v.findViewById(R.id.itemDia);
            TextView hora = v.findViewById(R.id.itemHora);
            TextView titulo = v.findViewById(R.id.itemTitulo);
            TextView detalle = v.findViewById(R.id.itemDetalle);
            dia.setText(Formato.etiquetaDia(f.toLocalDate(), LocalDate.now()));
            hora.setText(Formato.hora(f.toLocalTime()));
            titulo.setText(e.titulo);
            String det = e.esReunion() ? "Reunión" : "Tarea";
            if (!e.participantes.isEmpty()) det += " · con " + e.participantes;
            boolean vencida = !e.hecho && e.cuando < System.currentTimeMillis();
            if (vencida) det += " · vencida";
            if (e.hecho) det += " · hecha";
            detalle.setText(det);
            int color = getContext().getColor(vencida ? R.color.peligro : R.color.primario);
            hora.setTextColor(color);
            titulo.setAlpha(e.hecho ? 0.5f : 1f);
            return v;
        }
    }

    // ================================================================= permisos y configuración inicial

    private void pedirPermisos() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_PERMISOS);
        }
    }

    private void mostrarConfiguracionInicial() {
        new AlertDialog.Builder(this)
                .setTitle("Un paso importante")
                .setMessage("Los teléfonos Honor cierran las apps en segundo plano y eso puede impedir que suenen "
                        + "los recordatorios.\n\n"
                        + "1. Toca «Abrir ajustes».\n"
                        + "2. Busca Agenda Voz y desactiva «Gestionar automáticamente».\n"
                        + "3. Activa las 3 opciones (inicio automático, inicio secundario y en segundo plano).\n\n"
                        + "Después, en Ajustes de esta app, usa «Probar alarma en 1 minuto».")
                .setPositiveButton("Abrir ajustes", (d, w) -> {
                    Prefs.marcarConfiguracionInicial(this);
                    SettingsActivity.abrirInicioAutomatico(this);
                })
                .setNegativeButton("Luego", (d, w) -> Prefs.marcarConfiguracionInicial(this))
                .setCancelable(false)
                .show();
    }

    // ================================================================= utilidades

    private void mostrarDialogo(String titulo, String mensaje, Runnable si, Runnable no) {
        cerrarDialogo();
        intentosConfirmacion = 0;
        dialogoActual = new AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(Formato.capitalizar(mensaje))
                .setPositiveButton("Sí", (d, w) -> {
                    dialogoActual = null;
                    si.run();
                })
                .setNegativeButton("No", (d, w) -> {
                    dialogoActual = null;
                    no.run();
                })
                .setNeutralButton("Responder por voz", (d, w) -> {
                    dialogoActual = null;
                    escuchar();
                })
                .setCancelable(false)
                .show();
    }

    private void cerrarDialogo() {
        if (dialogoActual != null) {
            try {
                dialogoActual.dismiss();
            } catch (Exception ignored) {
            }
            dialogoActual = null;
        }
    }

    private static long millis(LocalDateTime ldt) {
        return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
