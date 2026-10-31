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
