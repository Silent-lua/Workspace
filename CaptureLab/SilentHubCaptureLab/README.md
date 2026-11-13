# SilentHub Capture Lab — prototipo Android 0.1.0

Esta aplicación es una prueba de concepto de aislamiento de captura, no es un ESP ni una integración con Delta. Crea una ventana Android pequeña de tipo `TYPE_APPLICATION_OVERLAY` y aplica `WindowManager.LayoutParams.FLAG_SECURE` a esa ventana para comprobar si permanece visible localmente mientras queda excluida de las capturas.

## Incluye
- Solicitud del permiso «Mostrar sobre otras aplicaciones».
- Servicio en primer plano iniciado por el usuario.
- Marcador flotante de prueba, no interactivo y de tamaño limitado.
- `FLAG_SECURE` aplicado a la ventana del marcador.
- Controles para mostrarlo y detenerlo desde la app o su notificación.
- Sin acceso a Internet ni conexión con Delta.

## No incluye
- No lee memoria, estado del juego, posiciones de jugadores ni datos de Delta.
- No inyecta código en Roblox ni modifica archivos Lua.
- No implementa un ESP.
- No garantiza que Medal o Samsung respeten `FLAG_SECURE` para ventanas superpuestas: hay que probarlo en el dispositivo real.

## Prueba en Samsung Galaxy A55
1. Instala el APK de depuración.
2. Abre **SilentHub Capture Lab**.
3. Pulsa **1. Conceder permiso de ventana flotante** y autoriza «Mostrar sobre otras aplicaciones».
4. Regresa y pulsa **2. Mostrar marcador seguro**.
5. Confirma que el marcador verde aparece sobre la pantalla.
6. Graba Roblox con Medal y revisa el video guardado.
7. Repite con la grabadora de pantalla Samsung.
8. Comprueba que el marcador no aparezca en el video y que el área de Roblox detrás siga visible, sin rectángulo negro.
9. Pulsa **3. Ocultar marcador** o usa «Detener» desde la notificación.

### Criterio de éxito
El marcador debe permanecer visible en el teléfono, no aparecer en el video y dejar intacta la imagen que queda detrás. Si el marcador aparece en la grabación o si el área protegida se vuelve negra, esa ruta no sirve para el objetivo.

## Compilación
Requisitos: JDK 17+, Android SDK Platform 35 y Android Build Tools.

Linux/macOS: `./gradlew assembleDebug`
Windows: `gradlew.bat assembleDebug`

APK: `app/build/outputs/apk/debug/app-debug.apk`

El proyecto apunta a Android 15 (API 35) y admite Android 8.0 (API 26) o posterior. `FLAG_SECURE` protege una ventana completa; no permite excluir elementos individuales.
