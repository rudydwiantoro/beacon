-- Beacon + Trip tracking schema for Supabase PostgreSQL
-- Run this in Supabase SQL Editor.

create extension if not exists pgcrypto;
create extension if not exists postgis;

-- =========================================================
-- 1) Master device
-- =========================================================
create table if not exists public.beacons (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete set null,
  beacon_code text not null unique,
  name text,
  metadata jsonb not null default '{}'::jsonb,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_beacons_user_id on public.beacons(user_id);

-- =========================================================
-- 2) Trip/session
-- =========================================================
create table if not exists public.trips (
  id uuid primary key default gen_random_uuid(),
  beacon_id uuid not null references public.beacons(id) on delete cascade,
  started_at timestamptz not null,
  ended_at timestamptz,
  status text not null default 'in_progress'
    check (status in ('in_progress', 'completed', 'cancelled')),
  start_lat numeric(9,6),
  start_lon numeric(9,6),
  end_lat numeric(9,6),
  end_lon numeric(9,6),
  total_distance_m numeric(12,2),
  total_duration_s integer,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_trips_beacon_started_at
  on public.trips (beacon_id, started_at desc);

create index if not exists idx_trips_status
  on public.trips (status);

-- =========================================================
-- 3) Point-by-point journey
-- =========================================================
create table if not exists public.trip_points (
  id bigserial primary key,
  trip_id uuid not null references public.trips(id) on delete cascade,
  beacon_id uuid not null references public.beacons(id) on delete cascade,
  seq integer not null,
  recorded_at timestamptz not null,
  latitude numeric(9,6) not null check (latitude between -90 and 90),
  longitude numeric(9,6) not null check (longitude between -180 and 180),
  altitude_m double precision,
  speed_mps double precision,
  heading_deg double precision check (
    heading_deg is null or (heading_deg >= 0 and heading_deg < 360)
  ),
  accuracy_m double precision,
  battery_pct numeric(5,2) check (
    battery_pct is null or (battery_pct >= 0 and battery_pct <= 100)
  ),
  event_type text not null default 'track',
  payload jsonb not null default '{}'::jsonb,
  geom geography(Point, 4326)
    generated always as (
      st_setsrid(st_makepoint(longitude::double precision, latitude::double precision), 4326)::geography
    ) stored,
  created_at timestamptz not null default now(),
  unique (trip_id, seq)
);

create index if not exists idx_trip_points_trip_seq
  on public.trip_points (trip_id, seq);

create index if not exists idx_trip_points_beacon_time
  on public.trip_points (beacon_id, recorded_at desc);

create index if not exists idx_trip_points_trip_time
  on public.trip_points (trip_id, recorded_at);

create index if not exists idx_trip_points_geom_gist
  on public.trip_points using gist (geom);

-- =========================================================
-- 4) Utility: updated_at touch trigger
-- =========================================================
create or replace function public.touch_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

drop trigger if exists trg_beacons_touch_updated_at on public.beacons;
create trigger trg_beacons_touch_updated_at
before update on public.beacons
for each row execute function public.touch_updated_at();

drop trigger if exists trg_trips_touch_updated_at on public.trips;
create trigger trg_trips_touch_updated_at
before update on public.trips
for each row execute function public.touch_updated_at();

-- =========================================================
-- 5) Utility: recompute trip totals from points
-- =========================================================
create or replace function public.recompute_trip_totals(p_trip_id uuid)
returns void
language plpgsql
as $$
declare
  v_distance_m numeric(12,2);
  v_started_at timestamptz;
  v_ended_at timestamptz;
  v_duration_s integer;
begin
  with ordered as (
    select
      recorded_at,
      geom,
      lag(geom) over (order by seq) as prev_geom
    from public.trip_points
    where trip_id = p_trip_id
  )
  select
    coalesce(round(sum(
      case
        when prev_geom is null then 0
        else st_distance(prev_geom, geom)
      end
    )::numeric, 2), 0),
    min(recorded_at),
    max(recorded_at)
  into v_distance_m, v_started_at, v_ended_at
  from ordered;

  if v_started_at is null or v_ended_at is null then
    v_duration_s := null;
  else
    v_duration_s := greatest(0, extract(epoch from (v_ended_at - v_started_at))::int);
  end if;

  update public.trips
  set
    total_distance_m = v_distance_m,
    total_duration_s = v_duration_s,
    start_lat = (
      select latitude from public.trip_points
      where trip_id = p_trip_id
      order by seq asc
      limit 1
    ),
    start_lon = (
      select longitude from public.trip_points
      where trip_id = p_trip_id
      order by seq asc
      limit 1
    ),
    end_lat = (
      select latitude from public.trip_points
      where trip_id = p_trip_id
      order by seq desc
      limit 1
    ),
    end_lon = (
      select longitude from public.trip_points
      where trip_id = p_trip_id
      order by seq desc
      limit 1
    )
  where id = p_trip_id;
end;
$$;

create or replace function public.trg_recompute_trip_totals()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'DELETE' then
    perform public.recompute_trip_totals(old.trip_id);
    return old;
  end if;

  perform public.recompute_trip_totals(new.trip_id);

  if tg_op = 'UPDATE' and old.trip_id is distinct from new.trip_id then
    perform public.recompute_trip_totals(old.trip_id);
  end if;

  return new;
end;
$$;

drop trigger if exists trg_trip_points_recompute_totals on public.trip_points;
create trigger trg_trip_points_recompute_totals
after insert or update or delete on public.trip_points
for each row execute function public.trg_recompute_trip_totals();

-- =========================================================
-- 6) Utility: close trip helper
-- =========================================================
create or replace function public.close_trip(p_trip_id uuid, p_status text default 'completed')
returns public.trips
language plpgsql
as $$
declare
  v_trip public.trips;
begin
  if p_status not in ('completed', 'cancelled') then
    raise exception 'Invalid status %. Allowed: completed|cancelled', p_status;
  end if;

  perform public.recompute_trip_totals(p_trip_id);

  update public.trips
  set
    ended_at = coalesce(ended_at, now()),
    status = p_status
  where id = p_trip_id
  returning * into v_trip;

  return v_trip;
end;
$$;

-- =========================================================
-- 7) Route/GeoJSON helpers for UI and APIs
-- =========================================================
create or replace function public.trip_path_geojson(p_trip_id uuid)
returns jsonb
language sql
stable
as $$
  with pts as (
    select
      seq,
      longitude::double precision as lon,
      latitude::double precision as lat,
      recorded_at
    from public.trip_points
    where trip_id = p_trip_id
    order by seq
  ),
  line_geom as (
    select st_makeline(st_setsrid(st_makepoint(lon, lat), 4326) order by seq) as g
    from pts
  )
  select jsonb_build_object(
    'type', 'FeatureCollection',
    'features', jsonb_build_array(
      jsonb_build_object(
        'type', 'Feature',
        'geometry', st_asgeojson(g)::jsonb,
        'properties', jsonb_build_object('trip_id', p_trip_id)
      )
    )
  )
  from line_geom
  where g is not null;
$$;

create or replace view public.v_trip_summary as
select
  t.id,
  t.beacon_id,
  b.beacon_code,
  b.name as beacon_name,
  t.started_at,
  t.ended_at,
  t.status,
  t.total_distance_m,
  t.total_duration_s,
  (
    select count(*)
    from public.trip_points tp
    where tp.trip_id = t.id
  ) as point_count,
  t.start_lat,
  t.start_lon,
  t.end_lat,
  t.end_lon
from public.trips t
join public.beacons b on b.id = t.beacon_id;

-- =========================================================
-- 8) RLS baseline (recommended for Supabase)
-- =========================================================
alter table public.beacons enable row level security;
alter table public.trips enable row level security;
alter table public.trip_points enable row level security;

-- Owner can manage own beacons
drop policy if exists beacons_owner_select on public.beacons;
create policy beacons_owner_select on public.beacons
for select using (auth.uid() = user_id);

drop policy if exists beacons_owner_insert on public.beacons;
create policy beacons_owner_insert on public.beacons
for insert with check (auth.uid() = user_id);

drop policy if exists beacons_owner_update on public.beacons;
create policy beacons_owner_update on public.beacons
for update using (auth.uid() = user_id)
with check (auth.uid() = user_id);

drop policy if exists beacons_owner_delete on public.beacons;
create policy beacons_owner_delete on public.beacons
for delete using (auth.uid() = user_id);

-- Trips follow beacon ownership
drop policy if exists trips_owner_select on public.trips;
create policy trips_owner_select on public.trips
for select using (
  exists (
    select 1 from public.beacons b
    where b.id = trips.beacon_id and b.user_id = auth.uid()
  )
);

drop policy if exists trips_owner_insert on public.trips;
create policy trips_owner_insert on public.trips
for insert with check (
  exists (
    select 1 from public.beacons b
    where b.id = trips.beacon_id and b.user_id = auth.uid()
  )
);

drop policy if exists trips_owner_update on public.trips;
create policy trips_owner_update on public.trips
for update using (
  exists (
    select 1 from public.beacons b
    where b.id = trips.beacon_id and b.user_id = auth.uid()
  )
)
with check (
  exists (
    select 1 from public.beacons b
    where b.id = trips.beacon_id and b.user_id = auth.uid()
  )
);

drop policy if exists trips_owner_delete on public.trips;
create policy trips_owner_delete on public.trips
for delete using (
  exists (
    select 1 from public.beacons b
    where b.id = trips.beacon_id and b.user_id = auth.uid()
  )
);

-- Trip points follow trip ownership
drop policy if exists trip_points_owner_select on public.trip_points;
create policy trip_points_owner_select on public.trip_points
for select using (
  exists (
    select 1
    from public.trips t
    join public.beacons b on b.id = t.beacon_id
    where t.id = trip_points.trip_id
      and b.user_id = auth.uid()
  )
);

drop policy if exists trip_points_owner_insert on public.trip_points;
create policy trip_points_owner_insert on public.trip_points
for insert with check (
  exists (
    select 1
    from public.trips t
    join public.beacons b on b.id = t.beacon_id
    where t.id = trip_points.trip_id
      and b.user_id = auth.uid()
  )
);

drop policy if exists trip_points_owner_update on public.trip_points;
create policy trip_points_owner_update on public.trip_points
for update using (
  exists (
    select 1
    from public.trips t
    join public.beacons b on b.id = t.beacon_id
    where t.id = trip_points.trip_id
      and b.user_id = auth.uid()
  )
)
with check (
  exists (
    select 1
    from public.trips t
    join public.beacons b on b.id = t.beacon_id
    where t.id = trip_points.trip_id
      and b.user_id = auth.uid()
  )
);

drop policy if exists trip_points_owner_delete on public.trip_points;
create policy trip_points_owner_delete on public.trip_points
for delete using (
  exists (
    select 1
    from public.trips t
    join public.beacons b on b.id = t.beacon_id
    where t.id = trip_points.trip_id
      and b.user_id = auth.uid()
  )
);

-- MVP local viewer: allow read-only access from anon/authenticated clients.
-- Tighten this in production (for example by tenant/team filter).
drop policy if exists beacons_public_read on public.beacons;
create policy beacons_public_read on public.beacons
for select to anon, authenticated
using (true);

drop policy if exists trips_public_read on public.trips;
create policy trips_public_read on public.trips
for select to anon, authenticated
using (true);

drop policy if exists trip_points_public_read on public.trip_points;
create policy trip_points_public_read on public.trip_points
for select to anon, authenticated
using (true);

-- Optional: allow service_role full access (usually bypasses RLS anyway in Supabase)
-- grant usage on schema public to service_role;
