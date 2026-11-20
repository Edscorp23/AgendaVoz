package com.agendavoz.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Guardar radios (buscándolas por nombre o pegando su enlace) y escucharlas. */
public class RadiosActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ArrayAdapter<Radio> misRadios;
    private ArrayAdapter<Radio> resultados;
    private TextView sonando, sinRadios, estadoBusqueda;
    private EditText campoBuscar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_radios);

        sonando = findViewById(R.id.radioSonando);
        sinRadios = findViewById(R.id.sinRadios);
        estadoBusqueda = findViewById(R.id.estadoBusqueda);
        campoBuscar = findViewById(R.id.campoBuscarRadio);

        ListView lista = findViewById(R.id.listaMisRadios);
        misRadios = new ArrayAdapter<Radio>(this, android.R.layout.simple_list_item_2, android.R.id.text1, new ArrayList<>()) {
            @Override
            public View getView(int pos, View v, ViewGroup parent) {
                View fila = super.getView(pos, v, parent);
                Radio r = getItem(pos);
                TextView t2 = fila.findViewById(android.R.id.text2);
                if (r != null) t2.setText(r.detalle == null || r.detalle.isEmpty() ? "Di «Honor, pon radio " + r.nombre + "»" : r.detalle);
                return fila;
            }
        };
        lista.setAdapter(misRadios);
        lista.setOnItemClickListener((p, v, pos, id) -> {
            Radio r = misRadios.getItem(pos);
            if (r != null) escuchar(r);
        });
        lista.setOnItemLongClickListener((p, v, pos, id) -> {
            Radio r = misRadios.getItem(pos);
            if (r == null) return true;
            new AlertDialog.Builder(this)
                    .setMessage("¿Borrar «" + r.nombre + "» de tus radios?")
                    .setPositiveButton("Borrar", (d, w) -> {
                        DbHelper.get(this).eliminarRadio(r.id);
                        cargar();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return true;
        });

        ListView listaRes = findViewById(R.id.listaResultados);
        resultados = new ArrayAdapter<Radio>(this, android.R.layout.simple_list_item_2, android.R.id.text1, new ArrayList<>()) {
            @Override
            public View getView(int pos, View v, ViewGroup parent) {
                View fila = super.getView(pos, v, parent);
                Radio r = getItem(pos);
                ((TextView) fila.findViewById(android.R.id.text2)).setText(r == null ? "" : r.detalle);
                return fila;
            }
        };
        listaRes.setAdapter(resultados);
        listaRes.setOnItemClickListener((p, v, pos, id) -> {
            Radio r = resultados.getItem(pos);
            if (r == null) return;
            new AlertDialog.Builder(this)
                    .setTitle(r.nombre)
                    .setMessage(r.detalle)
                    .setPositiveButton("Guardar", (d, w) -> guardar(r))
                    .setNeutralButton("Escuchar", (d, w) -> escuchar(r))
                    .setNegativeButton("Cancelar", null)
                    .show();
        });

        findViewById(R.id.botonBuscarRadio).setOnClickListener(v -> buscar());
        campoBuscar.setOnEditorActionListener((v, a, e) -> {
            if (a == EditorInfo.IME_ACTION_SEARCH) {
                buscar();
                return true;
            }
            return false;
        });
        findViewById(R.id.botonDetenerRadio).setOnClickListener(v -> {
            RadioService.detener(this);
            ui.postDelayed(this::mostrarSonando, 300);
        });
        findViewById(R.id.botonAgregarEnlace).setOnClickListener(v -> agregarConEnlace());
        cargar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mostrarSonando();
    }

    private void cargar() {
        List<Radio> l = DbHelper.get(this).radios();
        misRadios.clear();
        misRadios.addAll(l);
        misRadios.notifyDataSetChanged();
        sinRadios.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void mostrarSonando() {
        String s = RadioService.sonando;
        sonando.setText(s == null ? "No suena ninguna radio" : "Sonando: " + s);
    }

    private void escuchar(Radio r) {
        RadioService.tocar(this, r);
        sonando.setText("Conectando: " + r.nombre + "…");
        ui.postDelayed(this::mostrarSonando, 4000);
    }

    private void guardar(Radio r) {
        Radio nueva = new Radio();
        nueva.nombre = r.nombre;
        nueva.url = r.url;
        nueva.detalle = r.detalle;
        DbHelper.get(this).insertarRadio(nueva);
        cargar();
        Toast.makeText(this, "Guardada. Di «Honor, pon radio " + r.nombre + "».", Toast.LENGTH_LONG).show();
    }

    private void buscar() {
        String q = campoBuscar.getText().toString().trim();
        if (q.length() < 2) {
            estadoBusqueda.setText("Escribe al menos 2 letras del nombre.");
            return;
        }
        if (!ProxyClient.hayInternet(this)) {
            estadoBusqueda.setText("Necesitas internet para buscar radios.");
            return;
        }
        estadoBusqueda.setText("Buscando…");
        new Thread(() -> {
            List<Radio> l = null;
            String error = null;
            try {
                l = RadioBrowser.buscar(q);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<Radio> res = l;
            final String err = error;
            ui.post(() -> {
                resultados.clear();
                if (res == null) {
                    estadoBusqueda.setText("No se pudo buscar (" + err + "). Prueba de nuevo o agrega con enlace.");
                } else if (res.isEmpty()) {
                    estadoBusqueda.setText("No encontré radios con ese nombre. Puedes agregarla con su enlace.");
                } else {
                    resultados.addAll(res);
                    estadoBusqueda.setText(res.size() + " resultados. Toca uno para escucharlo o guardarlo.");
                }
                resultados.notifyDataSetChanged();
            });
        }).start();
    }

    private void agregarConEnlace() {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout caja = new LinearLayout(this);
        caja.setOrientation(LinearLayout.VERTICAL);
        caja.setPadding(pad, pad / 2, pad, 0);
        EditText nombre = new EditText(this);
        nombre.setHint("Nombre (ej. Radio Moda)");
        nombre.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText url = new EditText(this);
        url.setHint("Enlace de la transmisión (http…)");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        caja.addView(nombre);
        caja.addView(url);
        new AlertDialog.Builder(this)
                .setTitle("Agregar con enlace")
                .setView(caja)
                .setPositiveButton("Guardar", (d, w) -> {
                    String n = nombre.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    if (n.isEmpty() || !(u.startsWith("http://") || u.startsWith("https://"))) {
                        Toast.makeText(this, "Pon un nombre y un enlace que empiece con http.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Radio r = new Radio();
                    r.nombre = n;
                    r.url = u;
                    r.detalle = "Agregada con enlace";
                    guardar(r);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
