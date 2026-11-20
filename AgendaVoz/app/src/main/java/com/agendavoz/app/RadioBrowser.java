package com.agendavoz.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Busca emisoras en radio-browser.info, una base de datos pública y gratuita de radios por internet.
 * Se elige un servidor de su lista (como recomienda el servicio) y, si falla, se prueba con el siguiente.
 */
public final class RadioBrowser {

    private static final String AGENTE = "AgendaVoz/1.5";
    private static final String[] SERVIDORES_RESPALDO = {
            "de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info"};

    private RadioBrowser() {
    }

    /** Busca por nombre (llamar en segundo plano). Si hay emisoras de Perú se muestran primero. */
    public static List<Radio> buscar(String nombre) throws Exception {
        String q = URLEncoder.encode(nombre.trim(), "UTF-8");
        String ruta = "/json/stations/search?name=" + q
                + "&limit=40&hidebroken=true&order=clickcount&reverse=true";
        Exception ultimo = null;
        for (String servidor : servidores()) {
            try {
                return convertir(get("https://" + servidor + ruta));
            } catch (Exception e) {
                ultimo = e;
            }
        }
        throw ultimo != null ? ultimo : new Exception("Sin servidores disponibles");
    }

    private static List<String> servidores() {
        Set<String> nombres = new LinkedHashSet<>();
        try {
            InetAddress[] ips = InetAddress.getAllByName("all.api.radio-browser.info");
            for (InetAddress ip : ips) {
                String n = ip.getCanonicalHostName();
                if (n != null && n.endsWith("radio-browser.info")) nombres.add(n);
            }
        } catch (Exception ignored) {
        }
        List<String> l = new ArrayList<>(nombres);
        Collections.shuffle(l);
        for (String s : SERVIDORES_RESPALDO) if (!l.contains(s)) l.add(s);
        return l;
    }

    private static List<Radio> convertir(String json) throws Exception {
        JSONArray a = new JSONArray(json);
        List<Radio> peru = new ArrayList<>();
        List<Radio> otras = new ArrayList<>();
        Set<String> vistas = new LinkedHashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String url = o.optString("url_resolved", "");
            if (url.isEmpty()) url = o.optString("url", "");
            String nombre = o.optString("name", "").trim();
            if (url.isEmpty() || nombre.isEmpty() || !vistas.add(url)) continue;
            Radio r = new Radio();
            r.nombre = nombre;
            r.url = url;
            String pais = o.optString("country", "");
            int kbps = o.optInt("bitrate", 0);
            r.detalle = pais + (kbps > 0 ? " · " + kbps + " kbps" : "");
            if ("PE".equalsIgnoreCase(o.optString("countrycode", ""))) peru.add(r);
            else otras.add(r);
        }
        peru.addAll(otras);
        return peru;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", AGENTE);
        int codigo = c.getResponseCode();
        if (codigo != 200) {
            c.disconnect();
            throw new Exception("HTTP " + codigo);
        }
        InputStream is = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        c.disconnect();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
