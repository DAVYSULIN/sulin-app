# 🔔 Notificaciones push — guía de activación

Este documento tiene los pasos para prender las notificaciones push de punta a
punta. Toca 3 sistemas: **Firebase** (entrega los mensajes a los celulares),
**Supabase** (tu backend, dispara los envíos) y **tu web** (guarda el token de
cada dispositivo). La app Android ya está lista — lo que falta es
configuración y un pequeño agregado en tu web.

No hace falta hacer los 3 pasos de una: podés compilar la app ya mismo sin
push (funciona igual), y activarlo cuando quieras.

---

## Paso 1 — Crear el proyecto de Firebase (~5 minutos)

1. Entrá a [console.firebase.google.com](https://console.firebase.google.com)
   con tu cuenta de Google y creá un proyecto nuevo (podés llamarlo "SULIN").
   Google Analytics es opcional, no lo necesitás para esto.
2. Dentro del proyecto: ícono de Android (`</>`  Android) para agregar una app.
3. **Nombre del paquete de Android**: pegá exactamente
   ```
   com.sulin.centrocomando
   ```
   (tiene que ser idéntico, es el mismo que está en `app/build.gradle`).
4. Descargá el archivo **`google-services.json`** que te ofrece Firebase.
5. Copiá ese archivo a la carpeta **`app/`** de este proyecto (al mismo nivel
   que `app/build.gradle`). Con eso alcanza — no hace falta tocar código, el
   proyecto ya está preparado para detectarlo solo.
6. Si vas a compilar por GitHub Actions y tu repo es **privado**: simplemente
   subí el archivo tal cual (no tiene datos secretos, Google lo aclara en su
   documentación). Si tu repo es **público**: no lo subas — en vez de eso,
   creá un secret en tu repo (`Settings > Secrets and variables > Actions >
   New repository secret`) llamado `GOOGLE_SERVICES_JSON` con el contenido
   completo del archivo. El workflow ya está preparado para usarlo.

Con esto ya alcanza para que la app reciba pushes. Los pasos que siguen son
para poder *enviarlos* desde tu backend.

---

## Paso 2 — Conseguir las credenciales para enviar notificaciones

1. En Firebase Console: ícono de tuerca ⚙️ → **Configuración del proyecto**
   → pestaña **Cuentas de servicio**.
2. Botón **Generar nueva clave privada** → se descarga un archivo `.json`
   (es distinto del `google-services.json` del paso 1 — este es para el
   backend, no para la app).
3. Anotá también el **Project ID** (no el "nombre" del proyecto): está en la
   misma pantalla, algo como `sulin-a1b2c3`.

Guardá ese archivo `.json` en un lugar seguro — le da permiso completo para
mandar notificaciones desde tu proyecto de Firebase.

---

## Paso 3 — Cargar la tabla y la función en Supabase

1. Entrá a tu proyecto en [supabase.com](https://supabase.com/dashboard) →
   **SQL Editor** → **New query**.
2. Copiá y ejecutá todo el contenido de
   [`supabase/schema/push_tokens.sql`](supabase/schema/push_tokens.sql) (está
   en este mismo proyecto). Esto crea la tabla `push_tokens` con los permisos
   de seguridad correctos (cada usuario solo ve sus propios tokens).

---

## Paso 4 — Desplegar la función que envía los pushes

Necesitás el [Supabase CLI](https://supabase.com/docs/guides/cli) instalado
(`npm install -g supabase`, o ver el link para otras formas de instalarlo).

```bash
# Desde la raíz de este proyecto (donde está la carpeta supabase/)
supabase login
supabase link --project-ref <tu-project-ref>   # lo ves en la URL del dashboard

# Configurar los secrets que la función necesita:
supabase secrets set FIREBASE_PROJECT_ID=sulin-a1b2c3
supabase secrets set FIREBASE_SERVICE_ACCOUNT_JSON="$(cat ruta/al/archivo-de-cuenta-de-servicio.json)"

# Desplegar:
supabase functions deploy send-push-notification
```

Al terminar te va a mostrar la URL de la función, algo como:
`https://<tu-project-ref>.supabase.co/functions/v1/send-push-notification`

### Probarla manualmente

Con cualquier cliente HTTP (Postman, `curl`, etc.), una vez que tengas al
menos un token guardado (ver Paso 5):

```bash
curl -X POST 'https://<tu-project-ref>.supabase.co/functions/v1/send-push-notification' \
  -H "Authorization: Bearer <SUPABASE_SERVICE_ROLE_KEY>" \
  -H "Content-Type: application/json" \
  -d '{"user_id": "<uuid-de-un-usuario-con-token>", "title": "Prueba", "body": "Si ves esto, funciona"}'
```

(El Service Role Key está en Supabase → Project Settings → API. Es secreto,
nunca lo pongas en el código de la web/app, solo se usa server-to-server.)

---

## Paso 5 — El pequeño agregado en tu web

Esto es lo único que falta tocar en tu código actual. En algún lugar que se
ejecute **después del login** (por ejemplo, justo después de que Supabase
confirma la sesión), agregá algo como esto:

```js
// Solo tiene efecto dentro de la app Android — en un navegador normal,
// window.SulinNative no existe y esto no hace nada.
function registrarPushToken() {
  if (!window.SulinNative) return;

  const token = window.SulinNative.getPushToken();
  if (token) {
    supabase.rpc('upsert_push_token', { p_token: token });
  }

  // El token puede tardar unos segundos en estar listo la primera vez que
  // se abre la app; esta función se llama sola cuando esté disponible.
  window.onAndroidPushTokenReady = function (token) {
    supabase.rpc('upsert_push_token', { p_token: token });
  };
}

// Llamar a registrarPushToken() después de confirmar el login, por ejemplo
// dentro de tu listener de supabase.auth.onAuthStateChange.
```

(`supabase` acá es tu cliente de Supabase JS ya inicializado, el mismo que ya
usás en el resto de la web.)

---

## Paso 6 — Disparar notificaciones automáticamente

La forma más simple, sin escribir código: **Database Webhooks** de Supabase.

1. Dashboard → **Database** → **Webhooks** → **Create a new hook**.
2. Elegí la tabla que te interesa (por ejemplo, la de avisos/fallas
   correctivas) y el evento `INSERT`.
3. Tipo de webhook: **Supabase Edge Functions** → elegí
   `send-push-notification`.
4. En el body del webhook armá el JSON con los datos de la fila nueva, por
   ejemplo (ajustá los nombres de columna a tu tabla real):
   ```json
   {
     "user_id": "{{ record.asignado_a }}",
     "title": "Nueva falla reportada",
     "body": "{{ record.descripcion }}",
     "path": "/mantenimiento/{{ record.id }}"
   }
   ```

Si tu caso es más complejo (notificar a varios usuarios, distintas reglas
según el tipo de aviso, etc.), lo mismo se puede hacer con un trigger SQL que
llame a la función vía `pg_net` en vez del webhook visual — avisame si
llegás a ese punto y lo armamos juntos.

---

## Resumen de qué toca cada parte

| Parte | Quién la hizo | Qué falta de tu lado |
|---|---|---|
| App Android (recibir y mostrar el push) | ✅ Ya está | Agregar `google-services.json` (Paso 1) |
| Tabla + función SQL en Supabase | ✅ Código listo (`supabase/schema/push_tokens.sql`) | Ejecutarlo en tu proyecto (Paso 3) |
| Función que envía el push (Edge Function) | ✅ Código listo (`supabase/functions/send-push-notification`) | Desplegarla con tus credenciales (Paso 4) |
| Guardar el token del dispositivo | ✅ Puente nativo listo (`window.SulinNative`) | Agregar el snippet en tu web (Paso 5) |
| Disparar el envío ante un evento | — | Configurar el Database Webhook (Paso 6) |

Cualquier paso que se trabe, mandame el error y lo resolvemos.
