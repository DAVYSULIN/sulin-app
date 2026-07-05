# SULIN | Centro de Comando — App Android

App Android nativa que envuelve **https://comfy-beijinho-bcc604.netlify.app** en un
proyecto Kotlin listo para compilar, con todos los detalles de una app profesional:
no es solo "la web dentro de un WebView", sino que además tiene:

- ✅ **Sesión persistente** — cookies y almacenamiento local activados, así que el
  login de Supabase se mantiene entre aperturas de la app.
- ✅ **Escáner QR funcional** — permisos de cámara manejados correctamente (tanto
  el permiso de Android como el `getUserMedia` del navegador) para el módulo
  "Visor de Activos 360".
- ✅ **Subida de fotos/archivos** — el selector de archivos ofrece tanto elegir un
  archivo existente como tomar una foto nueva con la cámara (útil para reportar
  fallas, evidencia fotográfica, etc.).
- ✅ **Descargas** — los reportes/exportables se guardan en la carpeta Descargas
  del celular con notificación nativa.
- ✅ **Pantalla de "sin conexión"** con botón de reintentar, en vez del error feo
  del navegador.
- ✅ **Botón atrás nativo** — navega el historial de la web y, al llegar al
  principio, minimiza la app en vez de cerrarla de golpe.
- ✅ **Login con Google/OAuth** — los popups de login se abren en el navegador del
  sistema (Google bloquea el login dentro de WebViews embebidas, así que esta es
  la única forma de que funcione confiablemente).
- ✅ **Splash screen** nativo y **ícono adaptativo** con la identidad de SULIN.
- ✅ **Pull-to-refresh**, barra de progreso, y manejo de enlaces `tel:`,
  `mailto:`, `whatsapp:`, etc.
- ✅ **Notificaciones push** (Firebase Cloud Messaging) — la app ya está
  preparada de punta a punta; para activarlas seguí
  [`PUSH_NOTIFICATIONS.md`](PUSH_NOTIFICATIONS.md) (5-10 minutos, no requiere
  tocar código).

---

## 🚀 Cómo obtener el APK (2 caminos)

### Opción A — Sin instalar nada (recomendada)

Uso GitHub Actions para compilar el APK en la nube automáticamente.

1. Creá un repositorio nuevo en [github.com](https://github.com) (puede ser privado).
2. Subí **todo el contenido de esta carpeta** a ese repositorio. Lo más fácil,
   sin usar la terminal:
   - En GitHub, `Add file` → `Upload files` → arrastrá todos los archivos y carpetas.
3. Andá a la pestaña **Actions** de tu repositorio. El flujo `Compilar APK de SULIN`
   se ejecuta solo. Si no arrancó, entrá y tocá **Run workflow**.
4. Esperá ~3-5 minutos. Al terminar, entrá a esa ejecución y bajá hasta
   **Artifacts**: ahí vas a encontrar `SULIN-debug-apk` (bajalo, descomprimilo,
   y ese `.apk` ya lo podés instalar en cualquier Android para probarlo).

> El workflow ya está en `.github/workflows/build-apk.yml`, no hay que configurar nada.

### Opción B — Con Android Studio (recomendada si vas a seguir editando la app)

1. Descargá e instalá [Android Studio](https://developer.android.com/studio) (gratis).
2. `File > Open` y seleccioná esta carpeta (`SulinApp`).
3. Dejá que Android Studio sincronice el proyecto (descarga automáticamente todo
   lo que falte, incluido Gradle).
4. Conectá tu celular por USB con la "Depuración USB" activada (o usá un
   emulador), y tocá el botón ▶ Run.
5. Para generar un `.apk` instalable manualmente: `Build > Build App Bundle(s) / APK(s) > Build APK(s)`.

---

## 📲 Instalar el APK en un celular

Los `.apk` de este proyecto (mientras no estén en Google Play) hay que instalarlos
"por fuera de la tienda":

1. Pasá el archivo `.apk` al celular (por USB, Drive, WhatsApp, etc.)
2. Al abrirlo, Android va a pedir permiso para "instalar apps desconocidas" — es
   normal, aceptalo solo para el instalador que estés usando.
3. Instalar y listo.

---

## 🎨 Personalización rápida

### Colores / identidad visual
Ya actualizado con tu paleta real (extraída de tu logo): navy `#041B43` y azul
`#0064FD`. Si en algún momento cambian el branding, es un cambio de un solo
archivo:

`app/src/main/res/values/colors.xml` → cambiá los valores `#RRGGBB` y toda la
app (barra de estado, splash, pantalla sin conexión, acentos) se actualiza sola.

### Ícono de la app
Ya está usando tu isotipo real (recorté el símbolo "S" de tu `logo.png`, le
saqué el fondo blanco y armé todo el set: ícono adaptativo, legacy en todas
las densidades, ícono de notificación, y el de 512×512 para la Play Store).
Si en algún momento cambian el logo, hay dos formas de actualizarlo:
- Mandame el archivo nuevo por acá y te regenero todo el set otra vez.
- O reemplazarlo vos mismo: `app/src/main/res/drawable/ic_launcher_foreground.png`
  (ícono adaptativo), los `ic_launcher.png` / `ic_launcher_round.png` de cada
  `mipmap-*`, y los `ic_notification.png` de cada `drawable-*` (ojo: este
  último tiene que ser una silueta blanca sólida, es un requisito de Android).

Tip: la forma más fácil de regenerar todo el set a mano a partir de una sola
imagen es pegarla en [Android Asset Studio](https://romannurik.github.io/AndroidAssetStudio/icons-launcher.html)
(herramienta oficial gratuita de Google) y reemplazar las carpetas `mipmap-*`.

### Nombre de la app
`app/src/main/res/values/strings.xml` → `app_name`.

### Si cambiás de dominio (dejás de usar el subdominio de Netlify)
`app/src/main/java/com/sulin/centrocomando/MainActivity.kt` → constantes
`HOME_URL` y `ALLOWED_HOST` al principio del archivo.

---

## ⚠️ Cosas a tener en cuenta

- **Descargas con sesión de Supabase**: si algún botón de "exportar" arma la
  descarga usando el token de sesión (Authorization Bearer) en vez de cookies,
  es posible que la descarga no se autentique correctamente al pasar por el
  `DownloadManager` nativo de Android (esto es una limitación general de
  cualquier WebView, no algo específico de este proyecto). Si ves ese caso
  avisame y lo resolvemos con un pequeño ajuste (por ejemplo, que la web arme
  el archivo como blob y se lo pase directo a la app).
- **Notificaciones push**: el código ya está — Android, la tabla/función de
  Supabase y el puente con la web. Falta la configuración de las
  credenciales de Firebase/Supabase de tu lado, que no puedo hacer yo porque
  requieren tu cuenta de Google y acceso a tu proyecto. Guía paso a paso en
  [`PUSH_NOTIFICATIONS.md`](PUSH_NOTIFICATIONS.md).
- **minSdk 24 / targetSdk 35**: la app funciona desde Android 7.0 (2016) en
  adelante, cubriendo prácticamente todos los celulares activos hoy. `targetSdk
  35` cumple el requisito vigente de Google Play al momento de armar este
  proyecto — antes de publicar, conviene revisar
  [este link](https://developer.android.com/google/play/requirements/target-sdk)
  por si Google actualizó el mínimo exigido (lo actualizan ~1 vez por año).

---

## 🏪 Publicar en Google Play (cuando estén listos)

Resumen de los pasos (no están hechos en este proyecto porque requieren tu
cuenta de Google/Play Console):

1. Crear una cuenta de [Play Console](https://play.google.com/console) (pago
   único de USD 25).
2. En Android Studio: `Build > Generate Signed Bundle / APK` → generar un
   **Android App Bundle (.aab)** firmado con tu propio keystore (Android Studio
   te guía para crearlo; guardalo en un lugar seguro, sin él no podés actualizar
   la app en el futuro).
3. Completar la ficha de la app en Play Console (capturas de pantalla, ícono de
   512×512 ya incluido en `store_assets/`, descripción, política de privacidad).
4. Subir el `.aab` y enviar a revisión.

---

## 🗂️ Estructura del proyecto

```
SulinApp/
├── app/
│   ├── build.gradle                 # Configuración de compilación del módulo
│   ├── google-services.json         # (lo agregás vos, ver PUSH_NOTIFICATIONS.md)
│   └── src/main/
│       ├── AndroidManifest.xml      # Permisos y configuración de la app
│       ├── java/com/sulin/centrocomando/
│       │   ├── MainActivity.kt                    # Toda la lógica de la WebView
│       │   ├── ConnectivityHelper.kt              # Chequeo de conexión a internet
│       │   ├── SulinApplication.kt                # Crea el canal de notificaciones
│       │   ├── SulinFirebaseMessagingService.kt   # Recibe y muestra los pushes
│       │   ├── WebAppBridge.kt                    # Puente JS <-> nativo (window.SulinNative)
│       │   └── PushTokenStore.kt                  # Guarda el token de FCM localmente
│       └── res/                     # Layouts, colores, strings, íconos
├── supabase/
│   ├── schema/push_tokens.sql       # Tabla + RLS + función de upsert
│   └── functions/send-push-notification/  # Edge Function que envía los pushes
├── .github/workflows/build-apk.yml  # Compilación automática en la nube
├── store_assets/                    # Ícono 512×512 para Play Store
├── PUSH_NOTIFICATIONS.md            # Guía paso a paso para activar los pushes
└── README.md                        # Este archivo
```

---

## 🛠️ Versiones usadas

| Herramienta | Versión |
|---|---|
| Android Gradle Plugin (AGP) | 8.7.2 |
| Kotlin | 2.0.21 |
| Gradle | 8.9 |
| compileSdk / targetSdk | 35 (Android 15) |
| minSdk | 24 (Android 7.0) |
| Firebase BoM (notificaciones push) | 34.15.0 |
| Plugin google-services | 4.5.0 |

Elegí estas versiones (en vez de las más nuevas, AGP 9.x) a propósito: son
totalmente actuales y estables, pero usan el DSL de Gradle "clásico" que es el
mejor documentado y menos propenso a romperse — prioricé que este proyecto
compile a la primera antes que usar la versión más reciente del build system.

---

¿Algo no compila, o querés algún otro ajuste (otro idioma, otra pantalla
nativa, publicar en Play Store, etc.)? Contame y seguimos afinándolo.
