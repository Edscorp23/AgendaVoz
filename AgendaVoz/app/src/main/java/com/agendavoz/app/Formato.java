package com.agendavoz.app;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Textos de fecha y hora en español, para la pantalla y para la voz. */
public final class Formato {
    private static final Locale ES = Locale.forLanguageTag("es");
    private static final DateTimeFormatter DIA_LARGO = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES);
    private static final DateTimeFormatter DIA_CORTO = DateTimeFormatter.ofPattern("EEE d MMM", ES);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    private Formato() {
    }

    /** "hoy", "mañana", "el viernes 21 de noviembre". */
    public static String fechaHablada(LocalDate f, LocalDate hoy) {
        if (f.equals(hoy)) return "hoy";
        if (f.equals(hoy.plusDays(1))) return "mañana " + f.format(DateTimeFormatter.ofPattern("EEEE d", ES));
        if (f.equals(hoy.plusDays(2))) return "pasado mañana, " + f.format(DIA_LARGO);
        return "el " + f.format(DIA_LARGO);
    }

    /** Etiqueta corta para la lista: "Hoy", "Mañana", "vie 21 nov". */
    public static String etiquetaDia(LocalDate f, LocalDate hoy) {
        if (f.equals(hoy)) return "Hoy";
        if (f.equals(hoy.plusDays(1))) return "Mañana";
        if (f.equals(hoy.minusDays(1))) return "Ayer";
        String s = f.format(DIA_CORTO).replace(".", "");
        return s.substring(0, 1).toUpperCase(ES) + s.substring(1);
    }

    /** «jueves 20 de noviembre». */
    public static String fechaLarga(LocalDate f) {
        return f.format(DIA_LARGO);
    }

    /** true si el título ya empieza con «reunión», «junta» o «cita» (para no decir «reunión: reunión…»). */
    public static boolean empiezaConReunion(String titulo) {
        String n = VozParser.normalizar(titulo == null ? "" : titulo).trim();
        return n.startsWith("reunion") || n.startsWith("junta") || n.startsWith("cita");
    }

    public static String hora(LocalTime t) {
        return t.format(HORA);
    }

    /** "las 3 de la tarde", "las 9 y 30 de la mañana", "la 1 de la tarde", "las 12 del mediodía". */
    public static String horaHablada(LocalTime t) {
        int h = t.getHour();
        int m = t.getMinute();
        String franja;
        if (h == 0) franja = "de la medianoche";
        else if (h < 12) franja = "de la mañana";
        else if (h == 12) franja = "del mediodía";
        else if (h < 19) franja = "de la tarde";
        else franja = "de la noche";
        int h12 = h % 12;
        if (h12 == 0) h12 = 12;
        String art = h12 == 1 ? "la " : "las ";
        String min = "";
        if (m == 30) min = " y media";
        else if (m == 15) min = " y cuarto";
        else if (m > 0) min = " y " + m;
        return art + h12 + min + " " + franja;
    }

    public static String capitalizar(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(ES) + s.substring(1);
    }
}
