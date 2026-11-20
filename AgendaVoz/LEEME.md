# Agenda Voz — asistente de voz para reuniones y tareas

App Android tipo Alexa: dictas «reunión con Carlos mañana a las 3 de la tarde», la app entiende el día y la hora, pregunta lo que falte, confirma por voz y programa un recordatorio con sonido.

- Funciona **sin internet** con su propio intérprete de frases en español.
- Si tienes internet y configuras el Apps Script, usa **IA gratuita** (Gemini, con Groq de respaldo) para frases más libres.
- Las alarmas siempre se guardan en el teléfono: suenan aunque no haya señal.
- Pensada para Android 8 o superior (probada para Honor X8 con Android 11).

---

## 1. Obtener la APK (sin instalar nada en tu PC)

GitHub compila la app gratis en la nube.

1. Crea una cuenta en **github.com** (gratis).
2. Arriba a la derecha: **+ › New repository**. Nombre: `AgendaVoz`. Puede ser **Private**. Pulsa **Create repository**.
3. En la página del repositorio nuevo, haz clic en **uploading an existing file**.
4. Descomprime `AgendaVoz.zip` en tu PC, abre la carpeta `AgendaVoz` y **arrastra todo su contenido** (las carpetas `app`, `gradle`, `.github`, `apps-script` y los archivos sueltos) a la ventana del navegador. Pulsa **Commit changes**.
5. Ve a la pestaña **Actions**. Verás «Compilar APK» en marcha (tarda unos 5 minutos). Cuando tenga el círculo verde, entra en ella.
6. Abajo, en **Artifacts**, descarga **AgendaVoz-apk**. Es un .zip: dentro está `app-debug.apk`.

> **Si en la pestaña Actions no aparece nada:** la carpeta oculta `.github` no se subió. Entra en **Actions › set up a workflow yourself**, borra lo que aparece, pega el contenido de `.github/workflows/compilar-apk.yml` y pulsa **Commit changes**.

### Alternativa: Android Studio
Instala Android Studio (gratis), **File › Open** y elige la carpeta `AgendaVoz`. Espera a que termine de sincronizar y usa **Build › Build App Bundle(s) / APK(s) › Build APK(s)**. O conecta el celular por USB (con depuración USB activada) y pulsa ▶ Run.

## 2. Instalar en el celular

1. Pasa `app-debug.apk` al celular (WhatsApp a ti mismo, Google Drive o cable USB).
2. Ábrelo desde el administrador de archivos. Si pide permiso, activa **Instalar apps desconocidas** para esa app.
3. Si aparece un aviso de Play Protect, elige **Instalar de todas formas** (es normal en apps que no vienen de Play Store).

## 3. Primer uso en tu Honor (importante)

Al abrir la app te mostrará un aviso. Haz esto una vez:

1. **Ajustes › Batería › Inicio de aplicaciones › Agenda Voz**: desactiva «Gestionar automáticamente» y activa las 3 opciones.
2. En la app: **Ajustes › Quitar optimización de batería** y acepta.
3. En la app: **Ajustes › Probar alarma en 1 minuto**, cierra la app y espera. Si suena, todo funciona.

Para dictar se usa el reconocimiento de voz de Google. Si el micrófono no abre nada, instala o actualiza la app **Google** desde Play Store. Siempre puedes escribir la orden en la caja de texto.

## 4. Frases que entiende

| Dices | Qué hace |
|---|---|
| «Reunión con Carlos mañana a las 3 de la tarde» | Crea la reunión y pide confirmación |
| «Recuérdame pagar la luz el viernes a las 9» | Pregunta «¿de la mañana o de la tarde?» |
| «Tarea enviar informe el 25 de noviembre a las 10 de la mañana» | Crea la tarea |
| «Reunión de equipo en 2 horas» | Calcula la hora sola |
| «Junta con el director el lunes» | Pregunta «¿A qué hora?» |
| «¿Qué tengo para mañana?» / «¿Qué tengo esta semana?» | Te lee tus pendientes |
| «Borra la reunión del lunes» | Busca, confirma y borra |
| «Cambia la reunión con Carlos a las 5 de la tarde» | Busca, confirma y cambia la hora |

Toca cualquier elemento de la lista para marcarlo como hecho, cambiar fecha y hora, cambiar el título o eliminarlo.

## Cuando llega la hora: recordatorio hablado

En vez de un sonido, la app **dice en voz alta** la reunión o tarea («Es hora de tu reunión: Reunión con Carlos…») y la **repite** hasta que respondas. Aparece una pantalla grande, incluso con el teléfono bloqueado, con estas opciones:

- **Hecho**: la marca como realizada y deja de hablar.
- **Posponer 5 min / 10 min / 30 min / 1 hora**: calla y te lo vuelve a decir más tarde.
- **Responder por voz**: di «listo», «posponer 15 minutos», «en media hora» o «silencio».
- **Silenciar**: deja de hablar pero queda pendiente en la barra de notificaciones.

Los mismos botones (Hecho, Posponer 10 min, Silenciar) aparecen en la notificación.

Si no respondes, insiste unos minutos y luego se pospone sola y vuelve a avisarte. En **Ajustes** puedes cambiar cada cuánto repite (30 s), cuánto tiempo insiste (3 min) y en cuánto vuelve a avisar (10 min). El botón **«Escuchar un recordatorio de prueba ahora»** te deja oír cómo suena.

El aviso previo (por ejemplo, 10 minutos antes) se dice **una sola vez**.

La voz usa el volumen de **alarma** del teléfono. Si no se oye, sube ese volumen. Si el teléfono no tiene voz en español instalada, la app usa el timbre de alarma como respaldo: instala la voz en *Ajustes del teléfono › Accesibilidad › Salida de texto a voz*.

## 5. IA gratuita (opcional, con internet)

1. Entra a **script.google.com › Nuevo proyecto**. Borra lo que haya y pega todo el archivo `apps-script/Codigo.gs`. Guarda.
2. **Configuración del proyecto** (ícono de engranaje) › **Propiedades del script › Agregar propiedad**:
   - `TOKEN` → una contraseña que inventes (ej. `agenda-2026-xyz`).
   - `GEMINI_KEY` → tu clave de aistudio.google.com.
   - `MODELO` → el modelo que probaste en AI Studio (si no lo pones, usa `gemini-2.5-flash-lite`).
   - `GROQ_KEY` → opcional, clave de console.groq.com como respaldo.
3. Vuelve al editor, elige la función `probar` y pulsa **Ejecutar**. Autoriza los permisos. En el registro debe salir un JSON con fecha y hora.
4. **Implementar › Nueva implementación › tipo: Aplicación web**. Ejecutar como: **Yo**. Quién tiene acceso: **Cualquier usuario**. Pulsa Implementar y copia la URL que termina en `/exec`.
5. En la app: **Ajustes**, pega la URL y el token, y pulsa **Probar IA**.

Si cambias el código del script, usa **Implementar › Gestionar implementaciones › Editar › Nueva versión**, para que la URL siga siendo la misma.

## Estructura del proyecto

```
app/src/main/java/com/agendavoz/app/
  VozParser.java       intérprete de frases en español (sin internet)
  MainActivity.java    pantalla principal y diálogo por voz
  ProxyClient.java     conexión con el Apps Script (IA)
  Alarmas.java         programación de recordatorios
  AlarmReceiver.java   notificación con sonido
  BootReceiver.java    reprograma alarmas al reiniciar
  DbHelper.java        base de datos local
  SettingsActivity.java  ajustes y permisos de Honor
app/src/test/          pruebas del intérprete
apps-script/Codigo.gs  intermediario gratuito Gemini/Groq
```
