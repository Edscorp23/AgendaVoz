package com.agendavoz.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Cliente del "intermediario" en Google Apps Script, que llama a Gemini (y a Groq de respaldo).
 * La API key NUNCA está en la app: vive en el Apps Script.
 */
public final class ProxyClient {

    private ProxyClient() {
    }

    public static boolean hayInternet(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** Envía la frase al proxy y convierte la respuesta JSON en un Resultado. Lanza excepción si algo falla. */
    public static VozParser.Resultado analizar(String urlProxy, String token, String texto, LocalDateTime ahora)
            throws Exception {
        JSONObject cuerpo = new JSONObject();
        cuerpo.put("token", token);
        cuerpo.put("texto", texto);
        cuerpo.put("ahora", ahora.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")));
        cuerpo.put("dia_semana", ahora.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL,
                Locale.forLanguageTag("es")));
        cuerpo.put("zona", ZoneId.systemDefault().getId());

        String respuesta = post(urlProxy, cuerpo.toString());
        JSONObject j = new JSONObject(respuesta);
        if (j.has("error")) throw new Exception(j.optString("error"));
        return aResultado(j);
    }

    static VozParser.Resultado aResultado(JSONObject j) {
        VozParser.Resultado r = new VozParser.Resultado();
        r.origen = j.optString("origen", "ia");
        String in = j.optString("intencion", VozParser.CREAR_TAREA);
        switch (in) {
            case VozParser.CREAR_REUNION:
            case VozParser.CREAR_TAREA:
            case VozParser.LISTAR:
            case VozParser.ELIMINAR:
            case VozParser.MODIFICAR:
                r.intencion = in;
                break;
            default:
                r.intencion = VozParser.CREAR_TAREA;
        }
        r.titulo = limpio(j.optString("titulo", ""));

        String f = limpio(j.optString("fecha", ""));
        if (!f.isEmpty()) {
            try {
                r.fecha = LocalDate.parse(f);
            } catch (Exception ignored) {
            }
        }

        JSONArray falt = j.optJSONArray("faltantes");
        boolean faltaAmPm = false;
        if (falt != null) {
            for (int i = 0; i < falt.length(); i++) {
                if (VozParser.FALTA_AMPM.equals(falt.optString(i))) faltaAmPm = true;
            }
        }

        String h = limpio(j.optString("hora", ""));
        if (!h.isEmpty()) {
            try {
                LocalTime t = LocalTime.parse(h.length() == 4 ? "0" + h : h);
                if (faltaAmPm && t.getHour() <= 12) {
                    r.horaAmbigua = t.getHour() == 0 ? 12 : t.getHour();
                    r.minutoAmbiguo = t.getMinute();
                } else {
                    r.hora = t;
                }
            } catch (Exception ignored) {
            }
        }

        JSONArray parts = j.optJSONArray("participantes");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                String p = limpio(parts.optString(i));
                if (!p.isEmpty()) r.participantes.add(p);
            }
        }

        String rango = j.optString("rango", "");
        if (VozParser.LISTAR.equals(r.intencion)) {
            r.rango = rango.isEmpty() ? (r.fecha != null ? "dia" : "proximos") : rango;
        }
        if (VozParser.ELIMINAR.equals(r.intencion) || VozParser.MODIFICAR.equals(r.intencion)) {
            r.objetivo = limpio(j.optString("objetivo", r.titulo));
            if (r.objetivo.isEmpty()) r.objetivo = r.titulo;
        }

        // Los faltantes se recalculan aquí para no depender de lo que diga el modelo
        if (r.esCrear()) {
            if (r.fecha == null) r.faltantes.add(VozParser.FALTA_FECHA);
            if (r.hora == null && r.horaAmbigua == null) r.faltantes.add(VozParser.FALTA_HORA);
            if (r.horaAmbigua != null) r.faltantes.add(VozParser.FALTA_AMPM);
            if (r.titulo.isEmpty()) r.titulo = VozParser.CREAR_REUNION.equals(r.intencion) ? "Reunión" : "Tarea";
        } else if (VozParser.MODIFICAR.equals(r.intencion) && r.horaAmbigua != null) {
            r.faltantes.add(VozParser.FALTA_AMPM);
        }
        return r;
    }

    private static String limpio(String s) {
        if (s == null) return "";
        s = s.trim();
        return "null".equalsIgnoreCase(s) ? "" : s;
    }

    /** POST con JSON. Apps Script responde con una redirección 302 que se sigue con GET. */
    private static String post(String url, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] datos = json.getBytes(StandardCharsets.UTF_8);
        OutputStream os = c.getOutputStream();
        os.write(datos);
        os.close();

        int codigo = c.getResponseCode();
        int saltos = 0;
        while (codigo >= 300 && codigo < 400 && saltos < 5) {
            String destino = c.getHeaderField("Location");
            c.disconnect();
            if (destino == null) break;
            c = (HttpURLConnection) new URL(destino).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET");
            codigo = c.getResponseCode();
            saltos++;
        }
        InputStream is = codigo >= 400 ? c.getErrorStream() : c.getInputStream();
        String cuerpo = leer(is);
        c.disconnect();
        if (codigo >= 400) throw new Exception("HTTP " + codigo + ": " + cuerpo);
        return cuerpo;
    }

    private static String leer(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
