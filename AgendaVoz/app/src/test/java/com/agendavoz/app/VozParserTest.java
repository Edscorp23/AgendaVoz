package com.agendavoz.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Pruebas del intérprete local con las frases de ejemplo. "Hoy" es jueves 19/11/2026 a las 10:00. */
public class VozParserTest {

    private final VozParser p = new VozParser();
    private final LocalDateTime ahora = LocalDateTime.of(2026, 11, 19, 10, 0);

    @Test
    public void reunionConHoraYFranja() {
        VozParser.Resultado r = p.analizar("Crea una reunión con Carlos mañana a las 3 de la tarde", ahora);
        assertEquals(VozParser.CREAR_REUNION, r.intencion);
        assertEquals("Reunión con Carlos", r.titulo);
        assertEquals(LocalDate.of(2026, 11, 20), r.fecha);
        assertEquals(LocalTime.of(15, 0), r.hora);
        assertEquals("Carlos", r.participantes.get(0));
        assertTrue(r.faltantes.isEmpty());
    }

    @Test
    public void horaAmbiguaPideMananaOTarde() {
        VozParser.Resultado r = p.analizar("Recuérdame pagar la luz el viernes a las 9", ahora);
        assertEquals(VozParser.CREAR_TAREA, r.intencion);
        assertEquals("Pagar la luz", r.titulo);
        assertEquals(LocalDate.of(2026, 11, 20), r.fecha);
        assertNull(r.hora);
        assertEquals(Integer.valueOf(9), r.horaAmbigua);
        assertTrue(r.faltantes.contains(VozParser.FALTA_AMPM));
    }

    @Test
    public void fechaConMes() {
        VozParser.Resultado r = p.analizar("Tarea: enviar informe el 25 de noviembre a las 10 de la mañana", ahora);
        assertEquals("Enviar informe", r.titulo);
        assertEquals(LocalDate.of(2026, 11, 25), r.fecha);
        assertEquals(LocalTime.of(10, 0), r.hora);
    }

    @Test
    public void relativoEnHoras() {
        VozParser.Resultado r = p.analizar("Reunión de equipo en 2 horas", ahora);
        assertEquals(VozParser.CREAR_REUNION, r.intencion);
        assertEquals(LocalTime.of(12, 0), r.hora);
        assertEquals(LocalDate.of(2026, 11, 19), r.fecha);
    }

    @Test
    public void listarManana() {
        VozParser.Resultado r = p.analizar("¿Qué tengo para mañana?", ahora);
        assertEquals(VozParser.LISTAR, r.intencion);
        assertEquals("dia", r.rango);
        assertEquals(LocalDate.of(2026, 11, 20), r.fecha);
    }

    @Test
    public void borrarDelLunes() {
        VozParser.Resultado r = p.analizar("Borra la reunión del lunes", ahora);
        assertEquals(VozParser.ELIMINAR, r.intencion);
        assertEquals(LocalDate.of(2026, 11, 23), r.fecha);
    }

    @Test
    public void cambiarHora() {
        VozParser.Resultado r = p.analizar("Cambia la reunión con Carlos a las 5 de la tarde", ahora);
        assertEquals(VozParser.MODIFICAR, r.intencion);
        assertEquals(LocalTime.of(17, 0), r.hora);
    }

    @Test
    public void horaVeinticuatro() {
        VozParser.Resultado r = p.analizar("tengo que ir al banco hoy a las 15:30", ahora);
        assertEquals("Ir al banco", r.titulo);
        assertEquals(LocalTime.of(15, 30), r.hora);
        assertEquals(LocalDate.of(2026, 11, 19), r.fecha);
    }

    @Test
    public void faltaFechaYHora() {
        VozParser.Resultado r = p.analizar("Recuérdame tomar agua", ahora);
        assertTrue(r.faltantes.contains(VozParser.FALTA_FECHA));
        assertTrue(r.faltantes.contains(VozParser.FALTA_HORA));
    }

    @Test
    public void respuestasCortas() {
        assertEquals(Boolean.TRUE, p.interpretarAmPm("de la tarde"));
        assertEquals(Boolean.FALSE, p.interpretarAmPm("en la mañana"));
        assertEquals(Boolean.TRUE, p.interpretarSiNo("sí, guárdalo"));
        assertEquals(Boolean.FALSE, p.interpretarSiNo("no"));
    }
}
