/**
 * Agenda Voz — intermediario gratuito entre la app y la IA.
 *
 * La app envía la frase dictada; este script la pasa a Gemini (nivel gratuito de Google AI Studio)
 * y, si Gemini falla, a Groq (también gratuito). Devuelve un JSON con intención, fecha y hora.
 * Las claves viven aquí, en "Propiedades del script", nunca dentro de la APK.
 *
 * PROPIEDADES DEL SCRIPT (Configuración del proyecto › Propiedades del script):
 *   TOKEN       → una contraseña que inventes; la misma va en la app (Ajustes › Token).  OBLIGATORIA
 *   GEMINI_KEY  → tu API key de aistudio.google.com.                                     OBLIGATORIA
 *   MODELO      → nombre del modelo que probaste en AI Studio (por defecto gemini-2.5-flash-lite).
 *   GROQ_KEY    → opcional, tu key de console.groq.com como respaldo.
 *   GROQ_MODELO → opcional (por defecto llama-3.3-70b-versatile).
 */

var INSTRUCCIONES = [
  'Eres el cerebro de una app de agenda por voz en español (Latinoamérica).',
  'Recibes una frase dictada y la fecha/hora actual. Responde SOLO con un JSON válido con este formato:',
  '{"intencion":"crear_reunion|crear_tarea|listar|eliminar|modificar",',
  ' "titulo":"", "fecha":"YYYY-MM-DD o null", "hora":"HH:mm o null",',
  ' "participantes":[], "faltantes":[], "rango":"dia|semana|proximos", "objetivo":""}',
  'Reglas:',
  '- Calcula fechas relativas (hoy, mañana, pasado mañana, el viernes, en 2 horas) a partir de la fecha actual dada.',
  '- "el lunes" significa el próximo lunes; si hoy es lunes, el de la semana siguiente.',
  '- Si la hora es ambigua (ej. "a las 3" sin decir mañana/tarde/noche), pon la hora tal como la dijo en formato HH:mm',
  '  (ej. "03:00") y agrega "am_pm" a faltantes. Si dijo tarde o noche, usa formato 24 h (15:00).',
  '- Si falta la fecha o la hora, pon null y agrega "fecha" u "hora" a faltantes.',
  '- reunión, junta, cita, entrevista = crear_reunion. recuérdame, tarea, tengo que, debo = crear_tarea.',
  '- "qué tengo...", "mis reuniones", "mi agenda" = listar; usa rango "dia" si se menciona un día, "semana" si dice semana.',
  '- borra, elimina, cancela = eliminar. cambia, mueve, reprograma = modificar.',
  '- Para eliminar/modificar: "objetivo" son las palabras que identifican el evento (ej. "reunión con Carlos");',
  '  en modificar, fecha y hora son los NUEVOS valores.',
  '- El título debe ser corto, en español, empezar con mayúscula y no incluir la fecha ni la hora.',
  '- participantes: nombres de personas o grupos mencionados con "con" (ej. "Carlos", "el equipo").'
].join('\n');

function doPost(e) {
  try {
    var props = PropertiesService.getScriptProperties();
    var datos = JSON.parse(e.postData.contents);

    if (!props.getProperty('TOKEN') || datos.token !== props.getProperty('TOKEN')) {
      return responder({ error: 'Token incorrecto' });
    }
    var texto = String(datos.texto || '').slice(0, 500);
    if (!texto) return responder({ error: 'Texto vacío' });

    var contexto = 'Fecha y hora actual: ' + datos.ahora + ' (' + datos.dia_semana + '), zona ' + datos.zona +
      '.\nFrase: ' + texto;

    var resultado = null;
    var errores = [];
    try {
      resultado = conGemini(contexto, props);
      resultado.origen = 'gemini';
    } catch (err) {
      errores.push('Gemini: ' + err.message);
    }
    if (!resultado && props.getProperty('GROQ_KEY')) {
      try {
        resultado = conGroq(contexto, props);
        resultado.origen = 'groq';
      } catch (err) {
        errores.push('Groq: ' + err.message);
      }
    }
    if (!resultado) return responder({ error: errores.join(' | ') || 'Sin respuesta' });
    return responder(resultado);
  } catch (err) {
    return responder({ error: 'Error en el script: ' + err.message });
  }
}

function conGemini(contexto, props) {
  var key = props.getProperty('GEMINI_KEY');
  if (!key) throw new Error('falta GEMINI_KEY');
  var modelo = props.getProperty('MODELO') || 'gemini-2.5-flash-lite';
  var url = 'https://generativelanguage.googleapis.com/v1beta/models/' + modelo + ':generateContent';
  var cuerpo = {
    systemInstruction: { parts: [{ text: INSTRUCCIONES }] },
    contents: [{ role: 'user', parts: [{ text: contexto }] }],
    generationConfig: { temperature: 0, responseMimeType: 'application/json' }
  };
  var r = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    headers: { 'x-goog-api-key': key },
    payload: JSON.stringify(cuerpo),
    muteHttpExceptions: true
  });
  if (r.getResponseCode() !== 200) {
    throw new Error('HTTP ' + r.getResponseCode() + ' ' + r.getContentText().slice(0, 200));
  }
  var j = JSON.parse(r.getContentText());
  var salida = j.candidates[0].content.parts[0].text;
  return extraerJson(salida);
}

function conGroq(contexto, props) {
  var modelo = props.getProperty('GROQ_MODELO') || 'llama-3.3-70b-versatile';
  var cuerpo = {
    model: modelo,
    temperature: 0,
    response_format: { type: 'json_object' },
    messages: [
      { role: 'system', content: INSTRUCCIONES },
      { role: 'user', content: contexto }
    ]
  };
  var r = UrlFetchApp.fetch('https://api.groq.com/openai/v1/chat/completions', {
    method: 'post',
    contentType: 'application/json',
    headers: { Authorization: 'Bearer ' + props.getProperty('GROQ_KEY') },
    payload: JSON.stringify(cuerpo),
    muteHttpExceptions: true
  });
  if (r.getResponseCode() !== 200) {
    throw new Error('HTTP ' + r.getResponseCode() + ' ' + r.getContentText().slice(0, 200));
  }
  var j = JSON.parse(r.getContentText());
  return extraerJson(j.choices[0].message.content);
}

function extraerJson(texto) {
  var limpio = String(texto).replace(/```json|```/g, '').trim();
  var ini = limpio.indexOf('{');
  var fin = limpio.lastIndexOf('}');
  if (ini < 0 || fin < 0) throw new Error('la IA no devolvió JSON');
  return JSON.parse(limpio.slice(ini, fin + 1));
}

function responder(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

/** Ejecuta esta función desde el editor para probar sin la app (mira el resultado en "Registro de ejecución"). */
function probar() {
  var props = PropertiesService.getScriptProperties();
  var e = {
    postData: {
      contents: JSON.stringify({
        token: props.getProperty('TOKEN'),
        texto: 'reunión con Carlos mañana a las 3',
        ahora: Utilities.formatDate(new Date(), 'America/Lima', "yyyy-MM-dd'T'HH:mm"),
        dia_semana: '',
        zona: 'America/Lima'
      })
    }
  };
  Logger.log(doPost(e).getContent());
}
