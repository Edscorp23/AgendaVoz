package com.agendavoz.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Una reunión o tarea guardada. */
public class Evento {
    public static final String REUNION = "reunion";
    public static final String TAREA = "tarea";

    public long id;
    public String tipo = TAREA;
    public String titulo = "";
    public String participantes = "";   // separados por coma
    public long cuando;                 // milisegundos (epoch)
    public int avisoMin = 10;
    public boolean hecho;

    public LocalDateTime fechaHora() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(cuando), ZoneId.systemDefault());
    }

    public void setFechaHora(LocalDateTime ldt) {
        cuando = ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public boolean esReunion() {
        return REUNION.equals(tipo);
    }
}
