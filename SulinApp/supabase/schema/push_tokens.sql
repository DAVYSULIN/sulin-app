-- =============================================================================
-- SULIN | Centro de Comando — Notificaciones push
-- Ejecutar este script en: Supabase Dashboard -> SQL Editor -> New query
-- (o vía Supabase CLI: supabase db push)
-- =============================================================================

-- Tabla: un registro por dispositivo/token. Un mismo usuario puede tener
-- varios (celular + tablet, o si reinstala la app y le llega un token nuevo).
create table if not exists public.push_tokens (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  token       text not null unique,
  platform    text not null default 'android',
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index if not exists push_tokens_user_id_idx on public.push_tokens(user_id);

-- Row Level Security: cada usuario solo puede ver/tocar sus propios tokens.
alter table public.push_tokens enable row level security;

drop policy if exists "Users can view own push tokens" on public.push_tokens;
create policy "Users can view own push tokens"
  on public.push_tokens for select
  using (auth.uid() = user_id);

drop policy if exists "Users can insert own push tokens" on public.push_tokens;
create policy "Users can insert own push tokens"
  on public.push_tokens for insert
  with check (auth.uid() = user_id);

drop policy if exists "Users can update own push tokens" on public.push_tokens;
create policy "Users can update own push tokens"
  on public.push_tokens for update
  using (auth.uid() = user_id);

drop policy if exists "Users can delete own push tokens" on public.push_tokens;
create policy "Users can delete own push tokens"
  on public.push_tokens for delete
  using (auth.uid() = user_id);

-- Función de conveniencia: la web la llama una sola vez después del login
-- (supabase.rpc('upsert_push_token', { p_token: token })) y se encarga de
-- crear o actualizar el registro sin que el frontend tenga que lidiar con
-- conflictos de "unique".
create or replace function public.upsert_push_token(p_token text, p_platform text default 'android')
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.push_tokens (user_id, token, platform, updated_at)
  values (auth.uid(), p_token, p_platform, now())
  on conflict (token)
  do update set user_id = excluded.user_id,
                platform = excluded.platform,
                updated_at = now();
end;
$$;

grant execute on function public.upsert_push_token(text, text) to authenticated;

-- =============================================================================
-- Ejemplo de uso manual (para probar que el envío funciona) una vez
-- desplegada la Edge Function send-push-notification:
--
--   select net.http_post(
--     url := 'https://<tu-proyecto>.supabase.co/functions/v1/send-push-notification',
--     headers := jsonb_build_object(
--       'Content-Type', 'application/json',
--       'Authorization', 'Bearer ' || '<SUPABASE_SERVICE_ROLE_KEY>'
--     ),
--     body := jsonb_build_object(
--       'user_id', '<uuid-de-un-usuario-con-token-registrado>',
--       'title', 'Prueba de notificación',
--       'body', 'Si ves esto, el envío funciona 🎉'
--     )
--   );
--
-- (requiere la extensión "pg_net", activable desde Database -> Extensions)
-- =============================================================================
