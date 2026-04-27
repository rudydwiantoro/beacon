-- =========================================================
-- 1) Extensions
-- =========================================================
create extension if not exists pgcrypto;
create extension if not exists postgis;

-- =========================================================
-- 2) Master Beacon / Device
-- =========================================================
create table if not exists public.beacons (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete set null, -- opsional jika pakai Supabase Auth
  beacon_code text not null unique,                          -- kode unik perangkat
  name text,
  metadata jsonb not null default '{}'::jsonb,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- =========================================================
-- 3) Trip / Session Perjalanan
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
  total_distance_m numeric(12,2),   -- bisa diisi saat trip selesai
  total_duration_s integer,         -- bisa diisi saat trip selesai
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_trips_beacon_started_at
  on public.trips (beacon_id, started_at desc);

create index if not exists idx_trips_status
  on public.trips (status);

-- =========================================================
-- 4) Titik Perjalanan (Point-by-Point)
-- =========================================================
create table if not exists public.trip_points (
  id bigserial primary key,
  trip_id uuid not null references public.trips(id) on delete cascade,
  beacon_id uuid not null references public.beacons(id) on delete cascade,

  seq integer not null,                 -- urutan titik dalam trip
  recorded_at timestamptz not null,     -- waktu titik direkam

  latitude numeric(9,6) not null check (latitude between -90 and 90),
  longitude numeric(9,6) not null check (longitude between -180 and 180),
  altitude_m double precision,
  speed_mps double precision,
  heading_deg double precision check (heading_deg is null or (heading_deg >= 0 and heading_deg < 360)),
  accuracy_m double precision,
  battery_pct numeric(5,2) check (battery_pct is null or (battery_pct >= 0 and battery_pct <= 100)),
  event_type text not null default 'track', -- track/start/stop/pause/resume/sos dll
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
