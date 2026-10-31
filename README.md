# VideoDetect — navegador y gestor de descargas Android

Aplicación Android nativa experimental para detectar fuentes multimedia mientras navegas. Interfaz con pestañas **Navegador** y **Descargas**, inspección de elementos HTML5, detección de solicitudes MP4/WebM/HLS/DASH, bloqueo básico de dominios publicitarios y ventanas emergentes.

## Compilación
El workflow de GitHub Actions **Build Android APK** se ejecuta una vez por push a main (y también manualmente). Genera un APK de depuración firmado con la clave estándar de debug como artifact `VideoDetect-debug-apk`. Requiere Android SDK y JDK 17.

## Alcance de v0.1
- MP4/WebM directos: descarga mediante DownloadManager.
- HLS (`.m3u8`) y DASH (`.mpd`): detección y listado, pero no se promete combinar fragmentos ni descargarlos como un MP4.
- Fuentes cargadas por JavaScript: observación de solicitudes y etiquetas de video dentro del WebView.
- No evade DRM, autenticación, URLs firmadas caducadas ni controles de acceso.
- Bloqueo publicitario por lista mínima local de hostnames; necesita ampliación, actualizaciones y pruebas para producción.
- Los enlaces `blob:` no son archivos directamente descargables. En esos casos deben detectarse solicitudes de red subyacentes si son visibles.
- No reproduce necesariamente todas las funcionalidades del APK de referencia.

## Pruebas pendientes
Validar en un dispositivo físico y con páginas de prueba autorizadas. Los reproductores embebidos y sitios que usen mecanismos personalizados pueden requerir reglas específicas. No se ha realizado aún una prueba de ejecución en dispositivo.


## Diagnóstico de reproducción (v0.5)
Abre el sitio en Navegador, inicia Play, entra en Diagnóstico y comparte el informe. Se observan solicitudes nativas y eventos en iframes cuando la versión de Android System WebView admite `DOCUMENT_START_SCRIPT` y `WEB_MESSAGE_LISTENER`. El informe elimina queries y fragmentos de enlaces para reducir la exposición de tokens. No registra contraseñas ni formularios y no evade DRM.


## v0.6: transmisiones fragmentadas
El informe mostró solicitudes a `/cdn/down/<id>/1080_enc/<numero>.html` en `xspcdn*.click`. La aplicación agrupa por ID y calidad indicada en la ruta, con contador de fragmentos únicos y servidores CDN. Se presenta como una transmisión segmentada, no como MP4 descargable. No accede a las cargas útiles ni descifra datos. El sufijo `_enc` por sí solo no confirma DRM. Falta comprobar en un dispositivo.

## v0.7: Diagnóstico MediaSource / EME
Monitoriza el tipo de SourceBuffer creado por el reproductor mediante MediaSource.addSourceBuffer, los cambios de resolución observada (evento resize), y el aviso 'encrypted' del navegador (sin recoger datos de cifrado ni llaves). No realiza bypass de DRM ni guarda las cargas útiles. Se exportan únicamente MIME, codecs, dimensiones y señales de existencia. Sirve para diagnosticar la reproducción y elegir un flujo de descarga compatible cuando esté autorizado.


## v0.8: inspección de cabeceras y carga MSE
A partir del diagnóstico de 1920x1080, MSE H.264/AAC y cero señales EME, esta versión añade el estado HTTP y tipo de contenido de respuestas `/cdn/down/` observadas por el propio reproductor, así como muestras de `SourceBuffer.appendBuffer` (solo número de bytes y codec, **nunca datos del buffer**). No modifica las solicitudes ni su reproducción y no captura claves ni descifra contenido. Sirve para determinar si hay respuestas de video estándar o procesamiento de un formato personalizado; un Content-Type `text/html` tampoco prueba por sí solo la naturaleza del contenido binario.


## v0.9 (experimental): captura MediaSource y ensamblado MP4
El usuario inicia explícitamente desde Navegador → **Iniciar captura MP4**. Se recarga la página para captar los segmentos de inicialización de las pistas AAC/H.264. El script inyectado conserva los buffers que ya fueron aceptados por `SourceBuffer.appendBuffer`, los envía al código Java en fragmentos de 12 KiB confirmados uno por uno, y Java los escribe por separado en `audio.fmp4` y `video.fmp4`. Al detener, `MediaExtractor` lee ambas pistas y `MediaMuxer` intenta producir el MP4 sin recodificar, publicado en Descargas/VideoDetect mediante MediaStore. La función es experimental: MediaExtractor puede no aceptar determinados fMP4 o cambios adaptativos; no se manipula DRM, no se extraen claves y no se reconstruyen los archivos `_enc` directamente.


## v0.10: corrección del puente binario
Se corrigió un error de v0.9: `MediaDiagnostics.onPostMessage` rechazaba todo mensaje > 3500 caracteres, pero cada fragmento Base64 enviado por el capturador puede medir ~16.000 caracteres. Se acepta ahora una excepción estrictamente limitada a 21.000 caracteres para mensajes `capture-chunk` con tamaño del campo `base64` limitado a 26.000 y valida sesión/origen. Los eventos de diagnóstico ordinarios conservan el límite 3500. Se añadieron contadores de recepción, guardado, rechazo y conexión, y tamaños en KB. La exportación MP4 sigue siendo experimental: el muxer de Android puede no entender todas las secuencias fMP4 fragmentadas.


## v0.11: calidad preferida y validación de exportación
El inicio de captura presenta las calidades observadas durante la sesión, más el modo «Mejor disponible». Cada grupo de transmisión incluye «Capturar 1080p/720p/480p» según las solicitudes detectadas. La app envía la preferencia al JavaScript del reproductor para intentar escoger un control de calidad de la interfaz; esto no garantiza que un reproductor externo cambie de calidad. Se muestra la resolución real informada por el elemento video y se lee el ancho y alto de la pista MP4 exportada con MediaExtractor. Si se pidió 1080p y la pista resultó 480p, se informa la discrepancia sin etiquetarlo falsamente como Full HD. La captura sigue siendo experimental y requiere que el reproductor entregue segmentos compatibles.
