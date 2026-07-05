// =============================================================================
// SULIN | Centro de Comando — Edge Function: send-push-notification
//
// Envía una notificación push (vía FCM) a todos los dispositivos Android
// registrados de un usuario. No usa ninguna librería externa de Firebase:
// firma el JWT del service account con Web Crypto (nativo de Deno) y llama
// directo a la API HTTP v1 de FCM.
//
// DESPLIEGUE (con Supabase CLI, desde la raíz del proyecto):
//   supabase functions deploy send-push-notification
//
// VARIABLES DE ENTORNO REQUERIDAS (Dashboard -> Edge Functions -> Secrets, o
// `supabase secrets set NOMBRE=valor`):
//   FIREBASE_PROJECT_ID        -> el "Project ID" de Firebase (no el nombre)
//   FIREBASE_SERVICE_ACCOUNT_JSON -> el contenido COMPLETO del JSON de la
//                                    cuenta de servicio, como un solo string
//   SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY -> Supabase ya los provee solo
//
// Ver PUSH_NOTIFICATIONS.md para los pasos detallados de cómo conseguir cada
// una de estas credenciales.
//
// CÓMO LLAMARLA (por ejemplo desde un Database Webhook al crear un aviso):
//   POST https://<tu-proyecto>.supabase.co/functions/v1/send-push-notification
//   Authorization: Bearer <SUPABASE_SERVICE_ROLE_KEY o anon key>
//   { "user_id": "uuid-del-usuario", "title": "...", "body": "...", "path": "/opcional" }
// =============================================================================

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const FIREBASE_PROJECT_ID = Deno.env.get("FIREBASE_PROJECT_ID") ?? "";
const FIREBASE_SERVICE_ACCOUNT_JSON = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") ?? "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

interface PushRequestBody {
  user_id: string;
  title: string;
  body?: string;
  path?: string;
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const b64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s/g, "");
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function base64url(input: ArrayBuffer | string): string {
  let str: string;
  if (typeof input === "string") {
    str = btoa(input);
  } else {
    str = btoa(String.fromCharCode(...new Uint8Array(input)));
  }
  return str.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Firma un JWT de service account y lo cambia por un access token OAuth2. */
async function getAccessToken(serviceAccount: {
  client_email: string;
  private_key: string;
}): Promise<string> {
  const header = { alg: "RS256", typ: "JWT" };
  const now = Math.floor(Date.now() / 1000);
  const claimSet = {
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now,
  };

  const unsigned = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claimSet))}`;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );

  const jwt = `${unsigned}.${base64url(signature)}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });

  const data = await res.json();
  if (!res.ok) {
    throw new Error(`No se pudo obtener el access token de Google: ${JSON.stringify(data)}`);
  }
  return data.access_token as string;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response(JSON.stringify({ error: "Método no permitido, usar POST" }), { status: 405 });
  }

  try {
    if (!FIREBASE_PROJECT_ID || !FIREBASE_SERVICE_ACCOUNT_JSON) {
      return new Response(
        JSON.stringify({ error: "Faltan las variables FIREBASE_PROJECT_ID / FIREBASE_SERVICE_ACCOUNT_JSON" }),
        { status: 500 },
      );
    }

    const { user_id, title, body, path } = (await req.json()) as PushRequestBody;
    if (!user_id || !title) {
      return new Response(JSON.stringify({ error: "Faltan los campos 'user_id' y/o 'title'" }), { status: 400 });
    }

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY);
    const { data: tokens, error } = await supabase
      .from("push_tokens")
      .select("token")
      .eq("user_id", user_id);

    if (error) throw error;
    if (!tokens || tokens.length === 0) {
      return new Response(
        JSON.stringify({ sent: 0, message: "Ese usuario no tiene dispositivos con push registrados" }),
        { status: 200 },
      );
    }

    const serviceAccount = JSON.parse(FIREBASE_SERVICE_ACCOUNT_JSON);
    const accessToken = await getAccessToken(serviceAccount);

    const results = await Promise.all(
      tokens.map(async ({ token }: { token: string }) => {
        const fcmRes = await fetch(
          `https://fcm.googleapis.com/v1/projects/${FIREBASE_PROJECT_ID}/messages:send`,
          {
            method: "POST",
            headers: {
              Authorization: `Bearer ${accessToken}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              message: {
                token,
                // Mensaje "data-only": SulinFirebaseMessagingService.kt arma
                // la notificación nativa a partir de estas claves, así se
                // controla el ícono/color/deep-link siempre igual.
                data: {
                  title,
                  body: body ?? "",
                  ...(path ? { path } : {}),
                },
                android: { priority: "high" },
              },
            }),
          },
        );

        if (!fcmRes.ok) {
          const errBody = await fcmRes.json().catch(() => ({}));
          const reason = errBody?.error?.details?.[0]?.errorCode;
          // El token ya no sirve (app desinstalada, etc.): lo borramos para
          // no reintentar en vano en el futuro.
          if (reason === "UNREGISTERED" || fcmRes.status === 404) {
            await supabase.from("push_tokens").delete().eq("token", token);
          }
          return { token, ok: false, error: errBody };
        }
        return { token, ok: true };
      }),
    );

    return new Response(
      JSON.stringify({ sent: results.filter((r) => r.ok).length, total: results.length, results }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    );
  } catch (err) {
    return new Response(JSON.stringify({ error: String(err) }), { status: 500 });
  }
});
