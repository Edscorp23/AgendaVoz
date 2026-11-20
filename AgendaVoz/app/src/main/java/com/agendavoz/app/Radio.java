package com.agendavoz.app;

/** Una emisora de radio guardada por la persona. */
public class Radio {
    public long id;
    public String nombre = "";
    public String url = "";
    public String detalle = "";   // país, calidad… (solo para mostrar)

    @Override
    public String toString() {
        return nombre;
    }
}
