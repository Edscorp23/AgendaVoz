package com.agendavoz.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Base de datos local (SQLite) donde se guardan reuniones y tareas. */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB = "agenda.db";
    private static final int VERSION = 2;
    private static final String T = "eventos";

    private static DbHelper instancia;

    public static synchronized DbHelper get(Context ctx) {
        if (instancia == null) instancia = new DbHelper(ctx.getApplicationContext());
        return instancia;
    }

    private DbHelper(Context ctx) {
        super(ctx, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "tipo TEXT NOT NULL,"
                + "titulo TEXT NOT NULL,"
                + "participantes TEXT,"
                + "cuando INTEGER NOT NULL,"
                + "aviso_min INTEGER NOT NULL DEFAULT 10,"
                + "hecho INTEGER NOT NULL DEFAULT 0,"
                + "pospuesto INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_cuando ON " + T + "(cuando)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN pospuesto INTEGER NOT NULL DEFAULT 0");
        }
    }

    private ContentValues valores(Evento e) {
        ContentValues cv = new ContentValues();
        cv.put("tipo", e.tipo);
        cv.put("titulo", e.titulo);
        cv.put("participantes", e.participantes);
        cv.put("cuando", e.cuando);
        cv.put("aviso_min", e.avisoMin);
        cv.put("hecho", e.hecho ? 1 : 0);
        cv.put("pospuesto", e.pospuesto);
        return cv;
    }

    public long insertar(Evento e) {
        e.id = getWritableDatabase().insert(T, null, valores(e));
        return e.id;
    }

    public void actualizar(Evento e) {
        getWritableDatabase().update(T, valores(e), "id=?", new String[]{String.valueOf(e.id)});
    }

    public void eliminar(long id) {
        getWritableDatabase().delete(T, "id=?", new String[]{String.valueOf(id)});
    }

    public Evento obtener(long id) {
        List<Evento> l = consulta("id=?", new String[]{String.valueOf(id)}, null);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Pendientes (no hechas) desde un instante, ordenadas por fecha. */
    public List<Evento> pendientesDesde(long desde) {
        return consulta("hecho=0 AND cuando>=?", new String[]{String.valueOf(desde)}, "cuando ASC");
    }

    /** Pendientes cuya alarma (original o pospuesta) todavía no ha llegado. */
    public List<Evento> pendientesConAlarmaFutura(long ahora) {
        return consulta("hecho=0 AND (cuando>=? OR pospuesto>=?)",
                new String[]{String.valueOf(ahora), String.valueOf(ahora)}, "cuando ASC");
    }

    /** Pendientes entre dos instantes [ini, fin). */
    public List<Evento> pendientesEntre(long ini, long fin) {
        return consulta("hecho=0 AND cuando>=? AND cuando<?",
                new String[]{String.valueOf(ini), String.valueOf(fin)}, "cuando ASC");
    }

    public List<Evento> completados() {
        return consulta("hecho=1", null, "cuando DESC");
    }

    private List<Evento> consulta(String where, String[] args, String orden) {
        List<Evento> lista = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T, null, where, args, null, null, orden);
        try {
            while (c.moveToNext()) {
                Evento e = new Evento();
                e.id = c.getLong(c.getColumnIndexOrThrow("id"));
                e.tipo = c.getString(c.getColumnIndexOrThrow("tipo"));
                e.titulo = c.getString(c.getColumnIndexOrThrow("titulo"));
                String p = c.getString(c.getColumnIndexOrThrow("participantes"));
                e.participantes = p == null ? "" : p;
                e.cuando = c.getLong(c.getColumnIndexOrThrow("cuando"));
                e.avisoMin = c.getInt(c.getColumnIndexOrThrow("aviso_min"));
                e.hecho = c.getInt(c.getColumnIndexOrThrow("hecho")) == 1;
                e.pospuesto = c.getLong(c.getColumnIndexOrThrow("pospuesto"));
                lista.add(e);
            }
        } finally {
            c.close();
        }
        return lista;
    }
}
