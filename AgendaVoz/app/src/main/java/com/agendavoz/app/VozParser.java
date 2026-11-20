package com.agendavoz.app;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intérprete local (sin internet) de frases dictadas en español.
 * Detecta la intención, la fecha, la hora, si falta saber mañana/tarde,
 * los participantes y un título corto.
 *
 * Funciona sobre una copia "normalizada" del texto que tiene exactamente
 * la misma longitud que el original, así el título conserva acentos y mayúsculas.
 */
public class VozParser {

    public static final String CREAR_REUNION = "crear_reunion";
    public static final String CREAR_TAREA = "crear_tarea";
    public static final String LISTAR = "listar";
    public static final String ELIMINAR = "eliminar";
    public static final String MODIFICAR = "modificar";

    public static final String FALTA_FECHA = "fecha";
    public static final String FALTA_HORA = "hora";
    public static final String FALTA_AMPM = "am_pm";

    /** Resultado del análisis de una frase. */
    public static class Resultado {
        public String intencion = CREAR_TAREA;
        public String titulo = "";
        public LocalDate fecha;          // null si no se dijo
        public LocalTime hora;           // null si no se dijo o es ambigua
        public Integer horaAmbigua;      // 1..12 cuando no se sabe si es mañana o tarde
        public int minutoAmbiguo;
        public List<String> participantes = new ArrayList<>();
        public Set<String> faltantes = new LinkedHashSet<>();
        public String rango = "";        // para listar: "dia", "semana" o "proximos"
        public String objetivo = "";     // palabras para encontrar el evento a borrar/cambiar
        public String origen = "local";

        public boolean esCrear() {
            return CREAR_REUNION.equals(intencion) || CREAR_TAREA.equals(intencion);
        }

        @Override
        public String toString() {
            return "{intencion=" + intencion + ", titulo='" + titulo + "', fecha=" + fecha
                    + ", hora=" + hora + (horaAmbigua != null ? ", ambigua=" + horaAmbigua + ":" + minutoAmbiguo : "")
                    + ", participantes=" + participantes + ", faltantes=" + faltantes
                    + (rango.isEmpty() ? "" : ", rango=" + rango)
                    + (objetivo.isEmpty() ? "" : ", objetivo='" + objetivo + "'") + "}";
        }
    }

    // ------------------------------------------------------------------ tablas

    private static final Map<String, Integer> NUM = new HashMap<>();
    private static final Map<String, Integer> MESES = new HashMap<>();
    private static final Map<String, DayOfWeek> DIAS = new HashMap<>();

    static {
        String[] n = {"cero", "una", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
                "diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete",
                "dieciocho", "diecinueve", "veinte"};
        for (int i = 0; i < n.length; i++) NUM.put(n[i], i);
        NUM.put("un", 1);
        NUM.put("uno", 1);
        NUM.put("primero", 1);
        NUM.put("veinticinco", 25);
        NUM.put("treinta", 30);
        NUM.put("cuarenta", 40);
        NUM.put("cuarenta y cinco", 45);
        NUM.put("cincuenta", 50);

        String[] m = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
                "septiembre", "octubre", "noviembre", "diciembre"};
        for (int i = 0; i < m.length; i++) MESES.put(m[i], i + 1);
        MESES.put("setiembre", 9);

        DIAS.put("lunes", DayOfWeek.MONDAY);
        DIAS.put("martes", DayOfWeek.TUESDAY);
        DIAS.put("miercoles", DayOfWeek.WEDNESDAY);
        DIAS.put("jueves", DayOfWeek.THURSDAY);
        DIAS.put("viernes", DayOfWeek.FRIDAY);
        DIAS.put("sabado", DayOfWeek.SATURDAY);
        DIAS.put("domingo", DayOfWeek.SUNDAY);
    }

    private static final String NUM_PALABRA =
            "una|un|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce";
    private static final String MIN_PALABRA =
            "media|cuarto|cinco|diez|quince|veinte|veinticinco|treinta|cuarenta y cinco|cuarenta|cincuenta|\\d{1,2}";

    private static final Pattern P_AM = Pattern.compile("\\b(?:de|por|en) la (?:manana|madrugada)\\b");
    private static final Pattern P_PM = Pattern.compile("\\b(?:de|por|en) la (?:tarde|noche)\\b");
    private static final Pattern P_ESTA_PM = Pattern.compile("\\b(?:esta|hoy en la|hoy por la) (?:tarde|noche)\\b");
    private static final Pattern P_ESTA_AM = Pattern.compile("\\besta manana\\b");

    private static final Pattern P_RELATIVO = Pattern.compile(
            "\\b(?:en|dentro de)\\s+(\\d+|" + NUM_PALABRA + "|media)\\s+(horas?|minutos?|mins?|dias?)\\b(?:\\s+y\\s+media)?");

    private static final Pattern P_PASADO_MANANA = Pattern.compile("\\b(?:para |el )?pasado manana\\b");
    private static final Pattern P_MANANA = Pattern.compile("\\b(?:para |de )?manana\\b");
    private static final Pattern P_HOY = Pattern.compile("\\b(?:para |de )?hoy\\b");
    private static final Pattern P_DIA_SEMANA = Pattern.compile(
            "\\b(?:(?:para )?el |del |este |esta |al |para )?(?:(proximo|siguiente) )?"
                    + "(lunes|martes|miercoles|jueves|viernes|sabado|domingo)"
                    + "(?: (que viene|proximo|siguiente|de la proxima semana|de la semana que viene))?\\b");
    private static final Pattern P_FECHA_MES = Pattern.compile(
            "\\b(?:(?:para )?el |del |al )?(?:(?:lunes|martes|miercoles|jueves|viernes|sabado|domingo) )?"
                    + "(\\d{1,2}|primero|" + NUM_PALABRA + ") de "
                    + "(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)"
                    + "(?: (?:de |del )?(\\d{4}))?\\b");
    private static final Pattern P_FECHA_BARRA = Pattern.compile(
            "\\b(?:(?:para )?el |del |al )?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b");
    private static final Pattern P_SOLO_DIA = Pattern.compile("\\b(?:para el|el|del) (\\d{1,2})\\b");

    private static final Pattern P_MEDIODIA = Pattern.compile("\\b(?:a |al |para el )?(?:mediodia|medio dia)\\b");
    private static final Pattern P_MEDIANOCHE = Pattern.compile("\\b(?:a |a la |para la )?medianoche\\b");
    private static final Pattern P_HORA = Pattern.compile(
            "\\b(?:a eso de las|como a las|como a la|para las|para la|sobre las|tipo|a las|a la|las|la)\\s+"
                    + "(\\d{1,2}|" + NUM_PALABRA + ")"
                    + "(?:\\s*[:h]\\s*(\\d{2})|\\s+y\\s+(" + MIN_PALABRA + ")|\\s+menos\\s+(cuarto|cinco|diez|veinte|\\d{1,2}))?"
                    + "(?:\\s*(a\\s?m|p\\s?m|hrs|horas|h)\\b)?");
    private static final Pattern P_HORA_DOSPUNTOS = Pattern.compile(
            "\\b(\\d{1,2}):(\\d{2})(?:\\s*(a\\s?m|p\\s?m|hrs|horas|h)\\b)?");
    private static final Pattern P_HORA_AMPM = Pattern.compile("\\b(\\d{1,2})\\s*(a\\s?m|p\\s?m)\\b");

    private static final Pattern P_LISTAR = Pattern.compile(
            "^(?:oye |hola |y )?(?:que|cuales|cual|cuantas|cuantos)\\b|\\bque (?:tengo|hay)\\b|\\bmis (?:reuniones|tareas|pendientes|eventos|citas)\\b"
                    + "|\\bmi agenda\\b|\\blista(?:r)? (?:de |mis )?|\\bmuestrame\\b|\\bleeme\\b|\\bdime (?:mis|que|lo que)\\b");
    private static final Pattern P_ELIMINAR = Pattern.compile(
            "\\b(?:borra|borrar|borrame|elimina|eliminar|eliminame|cancela|cancelar|cancelame|quita|quitar|quitame|suprime)\\b");
    private static final Pattern P_MODIFICAR = Pattern.compile(
            "\\b(?:cambia|cambiar|cambiame|mueve|mover|mueveme|reprograma|reprogramar|modifica|modificar|pasa|pasar|posterga|postergar|adelanta|adelantar|atrasa|atrasar)\\b");
    private static final Pattern P_REUNION = Pattern.compile(
            "\\b(?:reunion|reuniones|reunirme|reunirnos|junta|cita|meeting|videollamada|conferencia|asamblea|entrevista)\\b");
    private static final Pattern P_PARTICIPANTES = Pattern.compile(
            "\\bcon ((?:(?:el|la|los|las|mi|mis|don|dona|el senor|la senora|el ingeniero|la ingeniera|el profesor|la profesora|el doctor|la doctora) )?[a-z]+"
                    + "(?: (?:y|e) (?:(?:el|la|los|las|mi|mis|don|dona) )?[a-z]+)*)");

    private static final Set<String> INICIO_VACIO = new HashSet<>(Arrays.asList(
            "oye", "hola", "por", "favor", "porfa", "porfavor", "crea", "crear", "creame", "agenda", "agendar", "agendame",
            "programa", "programar", "programame", "anota", "anotar", "anotame", "apunta", "apuntar", "apuntame",
            "pon", "ponme", "poner", "anade", "anademe", "agrega", "agregar", "agregame", "registra", "registrar",
            "nueva", "nuevo", "haz", "hazme", "recuerdame", "recordarme", "recuerda", "recuerdeme", "acuerdame",
            "avisame", "tarea", "tareas", "una", "un", "que", "de", "para", "a", "tengo", "debo", "necesito",
            "borra", "borrar", "borrame", "elimina", "eliminar", "eliminame", "cancela", "cancelar", "cancelame",
            "quita", "quitar", "quitame", "suprime", "cambia", "cambiar", "cambiame", "mueve", "mover", "mueveme",
            "reprograma", "reprogramar", "modifica", "modificar", "pasa", "pasar", "posterga", "postergar",
            "adelanta", "adelantar", "atrasa", "atrasar", "el", "la", "los", "las", "y", "me", "se", "lo", "le", "mi",
            "recordatorio", "evento", "llamado", "llamada", "titulado", "sobre"));
    private static final Set<String> FIN_VACIO = new HashSet<>(Arrays.asList(
            "el", "la", "los", "las", "a", "al", "de", "del", "para", "en", "y", "e", "que", "por", "con", "este",
            "esta", "proximo", "proxima", "como", "eso", "sobre", "tipo", "hasta", "desde", "o"));

    // ------------------------------------------------------------------ API

    public Resultado analizar(String texto, LocalDateTime ahora) {
        Resultado r = new Resultado();
        if (texto == null) texto = "";
        String original = texto;
        char[] norm = normalizar(original).toCharArray();
        String normCompleto = new String(norm);

        // ---------------- intención
        if (P_LISTAR.matcher(normCompleto.trim()).find() && !P_ELIMINAR.matcher(normCompleto).find()
                && !P_MODIFICAR.matcher(normCompleto).find()) {
            r.intencion = LISTAR;
        } else if (P_ELIMINAR.matcher(normCompleto).find()) {
            r.intencion = ELIMINAR;
        } else if (P_MODIFICAR.matcher(normCompleto).find()) {
            r.intencion = MODIFICAR;
        } else if (P_REUNION.matcher(normCompleto).find()) {
            r.intencion = CREAR_REUNION;
        } else {
            r.intencion = CREAR_TAREA;
        }

        // ---------------- mañana / tarde
        Boolean pm = null;
        LocalDate fecha = null;
        Matcher m;
        if ((m = P_ESTA_PM.matcher(new String(norm))).find()) {
            pm = true;
            fecha = ahora.toLocalDate();
            borrar(norm, m.start(), m.end());
        }
        if ((m = P_ESTA_AM.matcher(new String(norm))).find()) {
            pm = false;
            fecha = ahora.toLocalDate();
            borrar(norm, m.start(), m.end());
        }
        if ((m = P_PM.matcher(new String(norm))).find()) {
            pm = true;
            borrar(norm, m.start(), m.end());
        }
        if ((m = P_AM.matcher(new String(norm))).find()) {
            pm = false;
            borrar(norm, m.start(), m.end());
        }

        // ---------------- relativo: "en 2 horas", "dentro de 30 minutos"
        LocalDateTime relativo = null;
        if ((m = P_RELATIVO.matcher(new String(norm))).find()) {
            String cant = m.group(1);
            String unidad = m.group(2);
            boolean yMedia = m.group(0).endsWith("y media");
            long minutos;
            if ("media".equals(cant)) {
                minutos = unidad.startsWith("hora") ? 30 : 0;
            } else {
                int c = numero(cant);
                if (unidad.startsWith("hora")) minutos = c * 60L + (yMedia ? 30 : 0);
                else if (unidad.startsWith("dia")) minutos = c * 1440L;
                else minutos = c;
            }
            if (unidad.startsWith("dia") && minutos > 0) {
                fecha = ahora.toLocalDate().plusDays(minutos / 1440);
            } else if (minutos > 0) {
                relativo = ahora.plusMinutes(minutos).withSecond(0).withNano(0);
            }
            borrar(norm, m.start(), m.end());
        }

        // ---------------- fechas
        if ((m = P_FECHA_MES.matcher(new String(norm))).find()) {
            int dia = numero(m.group(1));
            int mes = MESES.get(m.group(2));
            int anio = m.group(3) != null ? Integer.parseInt(m.group(3)) : ahora.getYear();
            LocalDate f = fechaSegura(anio, mes, dia);
            if (f != null) {
                if (m.group(3) == null && f.isBefore(ahora.toLocalDate())) f = fechaSegura(anio + 1, mes, dia);
                fecha = f;
            }
            borrar(norm, m.start(), m.end());
        } else if ((m = P_FECHA_BARRA.matcher(new String(norm))).find()) {
            int dia = Integer.parseInt(m.group(1));
            int mes = Integer.parseInt(m.group(2));
            int anio = ahora.getYear();
            if (m.group(3) != null) {
                anio = Integer.parseInt(m.group(3));
                if (anio < 100) anio += 2000;
            }
            LocalDate f = fechaSegura(anio, mes, dia);
            if (f != null) {
                if (m.group(3) == null && f.isBefore(ahora.toLocalDate())) f = fechaSegura(anio + 1, mes, dia);
                fecha = f;
                borrar(norm, m.start(), m.end());
            }
        }
        if ((m = P_PASADO_MANANA.matcher(new String(norm))).find()) {
            fecha = ahora.toLocalDate().plusDays(2);
            borrar(norm, m.start(), m.end());
        }
        if ((m = P_MANANA.matcher(new String(norm))).find()) {
            fecha = ahora.toLocalDate().plusDays(1);
            borrar(norm, m.start(), m.end());
        }
        if ((m = P_HOY.matcher(new String(norm))).find()) {
            fecha = ahora.toLocalDate();
            borrar(norm, m.start(), m.end());
        }
        if ((m = P_DIA_SEMANA.matcher(new String(norm))).find()) {
            DayOfWeek objetivo = DIAS.get(m.group(2));
            LocalDate hoy = ahora.toLocalDate();
            int diff = objetivo.getValue() - hoy.getDayOfWeek().getValue();
            if (diff <= 0) diff += 7;
            boolean proxSemana = m.group(3) != null && m.group(3).contains("semana");
            LocalDate f = hoy.plusDays(diff);
            if (proxSemana && diff < 7) {
                // "el martes de la próxima semana": martes de la semana siguiente
                LocalDate lunesProx = hoy.plusDays(8 - hoy.getDayOfWeek().getValue());
                f = lunesProx.plusDays(objetivo.getValue() - 1);
            }
            if (fecha == null) fecha = f;
            borrar(norm, m.start(), m.end());
        }
        if (fecha == null && (m = P_SOLO_DIA.matcher(new String(norm))).find()) {
            int dia = Integer.parseInt(m.group(1));
            if (dia >= 1 && dia <= 31) {
                LocalDate hoy = ahora.toLocalDate();
                LocalDate f = fechaSegura(hoy.getYear(), hoy.getMonthValue(), dia);
                if (f == null || f.isBefore(hoy)) {
                    LocalDate sig = hoy.plusMonths(1);
                    f = fechaSegura(sig.getYear(), sig.getMonthValue(), dia);
                }
                if (f != null) {
                    fecha = f;
                    borrar(norm, m.start(), m.end());
                }
            }
        }

        // ---------------- hora
        Integer h = null;
        int min = 0;
        Boolean sufijoPm = null;
        boolean explicita24 = false;
        if ((m = P_MEDIODIA.matcher(new String(norm))).find()) {
            h = 12;
            sufijoPm = true;
            borrar(norm, m.start(), m.end());
        } else if ((m = P_MEDIANOCHE.matcher(new String(norm))).find()) {
            h = 0;
            explicita24 = true;
            borrar(norm, m.start(), m.end());
        } else if ((m = P_HORA_DOSPUNTOS.matcher(new String(norm))).find()) {
            h = Integer.parseInt(m.group(1));
            min = Integer.parseInt(m.group(2));
            sufijoPm = sufijo(m.group(3));
            // quitar también el "a las" que lo precede
            int ini = inicioConPrefijo(norm, m.start());
            borrar(norm, ini, m.end());
        } else if ((m = P_HORA.matcher(new String(norm))).find()) {
            h = numero(m.group(1));
            if (m.group(2) != null) {
                min = Integer.parseInt(m.group(2));
            } else if (m.group(3) != null) {
                min = minutos(m.group(3));
            } else if (m.group(4) != null) {
                min = 60 - minutos(m.group(4));
                h = h - 1;
                if (h < 0) h = 23;
            }
            sufijoPm = sufijo(m.group(5));
            borrar(norm, m.start(), m.end());
        } else if ((m = P_HORA_AMPM.matcher(new String(norm))).find()) {
            h = Integer.parseInt(m.group(1));
            sufijoPm = sufijo(m.group(2));
            borrar(norm, m.start(), m.end());
        }

        if (relativo != null) {
            r.fecha = relativo.toLocalDate();
            r.hora = relativo.toLocalTime();
        } else {
            r.fecha = fecha;
            if (h != null && h >= 0 && h <= 23 && min >= 0 && min <= 59) {
                Boolean esPm = sufijoPm != null ? sufijoPm : pm;
                if (explicita24 || h == 0 || h > 12) {
                    r.hora = LocalTime.of(h, min);
                } else if (esPm != null) {
                    int h24 = h;
                    if (esPm && h < 12) h24 = h + 12;
                    if (!esPm && h == 12) h24 = 0;
                    r.hora = LocalTime.of(h24, min);
                } else {
                    r.horaAmbigua = h;
                    r.minutoAmbiguo = min;
                }
            } else if (h == null && pm != null && fecha != null && r.esCrear()) {
                // "esta tarde" sin hora: se pregunta la hora
            }
        }

        // ---------------- participantes y título (sobre lo que queda)
        String restoNorm = new String(norm);
        if ((m = P_PARTICIPANTES.matcher(restoNorm)).find()) {
            int fin = m.end(1);
            // incluir apellidos: palabras siguientes que empiezan en mayúscula ("Carlos Pérez")
            Matcher ap = Pattern.compile("^\\s+(\\p{Lu}[\\p{L}]+)").matcher(original.substring(fin));
            while (ap.find() && norm[fin + ap.start(1)] != ' ') {
                fin += ap.end();
                ap = Pattern.compile("^\\s+(\\p{Lu}[\\p{L}]+)").matcher(original.substring(fin));
            }
            String orig = original.substring(m.start(1), fin);
            for (String p : orig.split("\\s+(?:y|e|Y|E)\\s+")) {
                String limpio = p.trim();
                if (!limpio.isEmpty()) r.participantes.add(capitalizarNombre(limpio));
            }
        }
        r.titulo = construirTitulo(original, norm, r.intencion);
        if (ELIMINAR.equals(r.intencion) || MODIFICAR.equals(r.intencion)) {
            r.objetivo = r.titulo;
        }

        // ---------------- rango para listar
        if (LISTAR.equals(r.intencion)) {
            if (normCompleto.contains("semana")) r.rango = "semana";
            else if (r.fecha != null) r.rango = "dia";
            else r.rango = "proximos";
            r.titulo = "";
        }

        // ---------------- faltantes
        if (r.esCrear()) {
            if (r.fecha == null) r.faltantes.add(FALTA_FECHA);
            if (r.hora == null && r.horaAmbigua == null) r.faltantes.add(FALTA_HORA);
            if (r.horaAmbigua != null) r.faltantes.add(FALTA_AMPM);
            if (r.titulo.isEmpty()) r.titulo = CREAR_REUNION.equals(r.intencion) ? "Reunión" : "Tarea";
        } else if (MODIFICAR.equals(r.intencion)) {
            if (r.horaAmbigua != null) r.faltantes.add(FALTA_AMPM);
        }
        return r;
    }

    /** Interpreta una respuesta a "¿de la mañana o de la tarde?". Devuelve true=tarde, false=mañana, null=no se entendió. */
    public Boolean interpretarAmPm(String respuesta) {
        String n = normalizar(respuesta == null ? "" : respuesta);
        if (n.matches(".*\\b(?:tarde|noche|p\\s?m|pasado el mediodia|despues del almuerzo)\\b.*")) return true;
        if (n.matches(".*\\b(?:manana|madrugada|a\\s?m|temprano)\\b.*")) return false;
        return null;
    }

    public static final int RESP_HECHO = -1;
    public static final int RESP_SILENCIAR = -2;
    public static final int RESP_NO_ENTENDIDA = 0;

    /**
     * Interpreta la respuesta hablada a un recordatorio que está sonando.
     * Devuelve RESP_HECHO, RESP_SILENCIAR, los minutos a posponer (mayor que 0) o RESP_NO_ENTENDIDA.
     */
    public int interpretarRespuestaRecordatorio(String respuesta) {
        String n = " " + normalizar(respuesta == null ? "" : respuesta).trim().replaceAll("\\s+", " ") + " ";
        boolean pospone = n.matches(".*\\b(?:pospon\\w*|despues|luego|mas tarde|recuerdame|en un rato|ahorita no|ahora no|todavia no|aun no|dentro de|en \\d+|en (?:cinco|diez|quince|veinte|treinta|media|una))\\b.*");
        if (!pospone && n.matches(".*\\b(?:hecho|hecha|listo|lista|ya (?:lo|la) hice|ya esta|ya termine|termine|terminado|terminada|termina|terminar|terminalo|finaliza|finalizar|finalizado|finalizada|finalizalo|finalizala|completado|completada|completar|completa|realizado|realizada|cumplido|cumplida|apaga|apagar|ok ya|ya)\\b.*")) {
            return RESP_HECHO;
        }
        if (n.matches(".*\\b(?:silencio|silenciar|silencia|callate|calla|para de hablar|deja de hablar|basta)\\b.*")) {
            return RESP_SILENCIAR;
        }
        if (n.matches(".*\\bmedia hora\\b.*")) return 30;
        if (n.matches(".*\\b(?:una|1) hora y media\\b.*")) return 90;
        Matcher m = Pattern.compile("\\b(\\d+|" + NUM_PALABRA + "|quince|veinte|treinta|cuarenta|cuarenta y cinco|cincuenta|veinticinco)\\s+(minutos?|mins?|horas?)\\b").matcher(n);
        if (m.find()) {
            int c = numero(m.group(1));
            if (c > 0) return m.group(2).startsWith("hora") ? c * 60 : c;
        }
        if (n.matches(".*\\buna hora\\b.*")) return 60;
        if (pospone) return 10;
        return RESP_NO_ENTENDIDA;
    }

    /** Interpreta un sí/no hablado. */
    public Boolean interpretarSiNo(String respuesta) {
        String n = normalizar(respuesta == null ? "" : respuesta).trim();
        if (n.matches(".*\\b(?:no|cancela|cancelar|negativo|mejor no)\\b.*")) return false;
        if (n.matches(".*\\b(?:si|claro|dale|ok|okay|okey|correcto|confirmo|confirma|guardalo|guarda|perfecto|de acuerdo|afirmativo|exacto|eso)\\b.*")) return true;
        return null;
    }

    // ------------------------------------------------------------------ ayudas

    /** Minúsculas, sin acentos y sin signos; mantiene exactamente la longitud del texto. */
    public static String normalizar(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            switch (c) {
                case 'á': case 'à': case 'ä': case 'â': c = 'a'; break;
                case 'é': case 'è': case 'ë': case 'ê': c = 'e'; break;
                case 'í': case 'ì': case 'ï': case 'î': c = 'i'; break;
                case 'ó': case 'ò': case 'ö': case 'ô': c = 'o'; break;
                case 'ú': case 'ù': case 'ü': case 'û': c = 'u'; break;
                case 'ñ': c = 'n'; break;
                default: break;
            }
            if (!(Character.isLetterOrDigit(c) || c == ':' || c == '/')) c = ' ';
            sb.append(c);
        }
        return sb.toString();
    }

    private static void borrar(char[] norm, int ini, int fin) {
        for (int i = Math.max(0, ini); i < Math.min(norm.length, fin); i++) norm[i] = ' ';
    }

    /** Si antes de la hora "15:30" aparece "a las", "para las", etc., incluye ese prefijo. */
    private static int inicioConPrefijo(char[] norm, int inicioHora) {
        String antes = new String(norm, 0, inicioHora);
        Matcher m = Pattern.compile("(?:a eso de las|como a las|para las|sobre las|a las|a la|las)\\s+$").matcher(antes);
        if (m.find()) return m.start();
        return inicioHora;
    }

    private static Boolean sufijo(String s) {
        if (s == null) return null;
        String t = s.replace(" ", "");
        if (t.equals("pm")) return true;
        if (t.equals("am")) return false;
        return null; // "horas", "hrs", "h": formato 24 h
    }

    private static int numero(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.matches("\\d+")) return Integer.parseInt(s);
        Integer v = NUM.get(s);
        return v != null ? v : 0;
    }

    private static int minutos(String s) {
        s = s.trim();
        if (s.equals("media")) return 30;
        if (s.equals("cuarto")) return 15;
        return numero(s);
    }

    private static LocalDate fechaSegura(int anio, int mes, int dia) {
        try {
            return LocalDate.of(anio, mes, dia);
        } catch (Exception e) {
            return null;
        }
    }

    private static String construirTitulo(String original, char[] norm, String intencion) {
        // Texto restante: caracteres originales donde el normalizado no fue borrado
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < original.length(); i++) {
            sb.append(norm[i] == ' ' ? ' ' : original.charAt(i));
        }
        String[] palabras = sb.toString().trim().split("\\s+");
        List<String> lista = new ArrayList<>();
        for (String p : palabras) if (!p.isEmpty()) lista.add(p);

        // quitar palabras de comando al inicio
        while (!lista.isEmpty() && INICIO_VACIO.contains(clave(lista.get(0)))) lista.remove(0);
        // quitar conectores sueltos al final
        while (!lista.isEmpty() && FIN_VACIO.contains(clave(lista.get(lista.size() - 1))))
            lista.remove(lista.size() - 1);
        // quitar ":" que haya quedado pegado al inicio ("Tarea: enviar...")
        if (!lista.isEmpty() && lista.get(0).equals(":")) lista.remove(0);

        String t = String.join(" ", lista).trim();
        if (t.isEmpty()) return "";
        if (LISTAR.equals(intencion)) return t;
        return t.substring(0, 1).toUpperCase(Locale.forLanguageTag("es")) + t.substring(1);
    }

    private static String clave(String palabra) {
        return normalizar(palabra).replace(":", "").replace("/", "").trim();
    }

    private static String capitalizarNombre(String s) {
        // "el director" o "mi jefe" quedan en minúsculas; "carlos pérez" pasa a "Carlos Pérez"
        Locale es = Locale.forLanguageTag("es");
        String[] partes = s.trim().split("\\s+");
        Set<String> articulos = new HashSet<>(Arrays.asList("el", "la", "los", "las", "mi", "mis"));
        if (articulos.contains(normalizar(partes[0]).trim())) return s.trim().toLowerCase(es);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < partes.length; i++) {
            String p = partes[i];
            if (i > 0) sb.append(' ');
            sb.append(p.substring(0, 1).toUpperCase(es)).append(p.substring(1).toLowerCase(es));
        }
        return sb.toString();
    }
}
