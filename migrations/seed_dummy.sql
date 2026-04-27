-- Dummy data for quick UI testing (cloud mode)
-- Creates 10 trips x 2000 points for BEACON-DEMO-001.
-- Safe to run multiple times (existing demo trips for this beacon are replaced).

-- 1) Ensure demo beacon exists
insert into public.beacons (beacon_code, name, metadata, is_active)
values (
  'BEACON-DEMO-001',
  'Demo Beacon Bali Loop',
  jsonb_build_object('source', 'seed_dummy', 'purpose', 'ui-test', 'size', '10x2000'),
  true
)
on conflict (beacon_code)
do update set
  name = excluded.name,
  metadata = excluded.metadata,
  is_active = excluded.is_active,
  updated_at = now();

-- 2) Reset previous demo trips for deterministic seed size
with b as (
  select id as beacon_id
  from public.beacons
  where beacon_code = 'BEACON-DEMO-001'
  limit 1
)
delete from public.trips t
using b
where t.beacon_id = b.beacon_id;

-- 3) Insert 10 trips
with b as (
  select id as beacon_id
  from public.beacons
  where beacon_code = 'BEACON-DEMO-001'
  limit 1
),
seed_trips as (
  select
    b.beacon_id,
    g.trip_no,
    now() - ((11 - g.trip_no) * interval '6 hours') as started_at,
    (now() - ((11 - g.trip_no) * interval '6 hours')) + interval '2 hours 46 minutes 35 seconds' as ended_at
  from b
  cross join generate_series(1, 10) as g(trip_no)
),
inserted_trips as (
  insert into public.trips (
    beacon_id,
    started_at,
    ended_at,
    status,
    notes
  )
  select
    beacon_id,
    started_at,
    ended_at,
    'completed',
    format('Seeded demo trip %s/10 for cloud viewer', trip_no)
  from seed_trips
  returning id, beacon_id, started_at
),
trip_map as (
  select
    i.id as trip_id,
    i.beacon_id,
    s.trip_no,
    s.started_at
  from inserted_trips i
  join seed_trips s
    on s.beacon_id = i.beacon_id
   and s.started_at = i.started_at
)
insert into public.trip_points (
  trip_id,
  beacon_id,
  seq,
  recorded_at,
  latitude,
  longitude,
  altitude_m,
  speed_mps,
  heading_deg,
  accuracy_m,
  battery_pct,
  event_type,
  payload
)
select
  tm.trip_id,
  tm.beacon_id,
  p.seq,
  tm.started_at + (p.seq * interval '5 seconds') as recorded_at,
  round((
    -8.670000
    + (tm.trip_no * 0.0018)
    + sin((p.seq + tm.trip_no * 45) / 20.0) * 0.0065
  )::numeric, 6) as latitude,
  round((
    115.230000
    + (tm.trip_no * 0.0012)
    + cos((p.seq + tm.trip_no * 33) / 23.0) * 0.0070
  )::numeric, 6) as longitude,
  16 + (p.seq % 22) as altitude_m,
  4.2 + ((p.seq % 17) * 0.22) as speed_mps,
  ((p.seq * 9) + (tm.trip_no * 13)) % 360 as heading_deg,
  3 + (p.seq % 6) as accuracy_m,
  greatest(22, 100 - (p.seq * 0.025) - (tm.trip_no * 0.9))::numeric(5,2) as battery_pct,
  case
    when p.seq = 1 then 'start'
    when p.seq = 2000 then 'stop'
    else 'track'
  end as event_type,
  jsonb_build_object('seed', true, 'trip_no', tm.trip_no, 'seq', p.seq)
from trip_map tm
cross join generate_series(1, 2000) as p(seq);

-- 4) Recompute trip totals (function if available, fallback if not)
do $$
declare
  r record;
begin
  begin
    for r in
      select t.id as trip_id
      from public.trips t
      join public.beacons b on b.id = t.beacon_id
      where b.beacon_code = 'BEACON-DEMO-001'
    loop
      perform public.recompute_trip_totals(r.trip_id);
    end loop;
  exception when undefined_function then
    update public.trips t
    set
      start_lat = p.start_lat,
      start_lon = p.start_lon,
      end_lat = p.end_lat,
      end_lon = p.end_lon,
      total_duration_s = p.duration_s,
      total_distance_m = null
    from (
      select
        tp.trip_id,
        (array_agg(tp.latitude order by tp.seq asc))[1] as start_lat,
        (array_agg(tp.longitude order by tp.seq asc))[1] as start_lon,
        (array_agg(tp.latitude order by tp.seq desc))[1] as end_lat,
        (array_agg(tp.longitude order by tp.seq desc))[1] as end_lon,
        greatest(0, extract(epoch from (max(tp.recorded_at) - min(tp.recorded_at)))::int) as duration_s
      from public.trip_points tp
      where tp.trip_id in (
        select t2.id
        from public.trips t2
        join public.beacons b2 on b2.id = t2.beacon_id
        where b2.beacon_code = 'BEACON-DEMO-001'
      )
      group by tp.trip_id
    ) p
    where t.id = p.trip_id;
  end;
end $$;

-- 5) Quick check
select
  b.beacon_code,
  count(*) as trip_count,
  coalesce(sum((
    select count(*) from public.trip_points tp where tp.trip_id = t.id
  )), 0) as total_points
from public.trips t
join public.beacons b on b.id = t.beacon_id
where b.beacon_code = 'BEACON-DEMO-001'
group by b.beacon_code;

select
  t.id as trip_id,
  t.status,
  t.started_at,
  t.ended_at,
  t.total_distance_m,
  t.total_duration_s,
  (
    select count(*)
    from public.trip_points tp
    where tp.trip_id = t.id
  ) as point_count
from public.trips t
join public.beacons b on b.id = t.beacon_id
where b.beacon_code = 'BEACON-DEMO-001'
order by t.started_at desc
limit 10;
