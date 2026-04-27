# Beacon Rider MVP (Low Battery Focus)

This repository contains:

- `android-beacon-app`: Android app that sends location beacons in background (foreground service while riding).
- `beacon-server`: Small Node.js server with API and web map viewer for family tracking.

## 1) Start the Server (Viewer + API)

```bash
cd beacon-server
npm install
# Windows PowerShell:
$env:BEACON_API_KEY="replace-with-your-secret"
# Optional Telegram bot relay:
# $env:TELEGRAM_BOT_TOKEN="123456:ABCDEF..."
# $env:TELEGRAM_CHAT_ID="-1001234567890"   # group/channel/user chat id
# $env:TELEGRAM_MIN_INTERVAL_SEC="120"      # optional throttle per rider
npm start
```

Server runs on `http://localhost:8080` by default.

Viewer page: `http://localhost:8080`
Supabase viewer page: `http://localhost:8080/supabase-viewer.html`

## Telegram Bot Option (Optional)

If you want every beacon update sent to Telegram:

1. Create Telegram bot using `@BotFather` and get bot token.
2. Get target chat id (user/group/channel).
3. Open web viewer (`/`) and fill:
   - `API Key` (`BEACON_API_KEY`)
   - `Telegram Bot Token`
   - `Telegram Chat ID`
   - `Min interval sec`
4. Click `Save`, then click `Test`.

To test Telegram configuration:

```bash
curl -X POST "http://localhost:8080/api/v1/telegram/test?key=replace-with-your-secret"
```

Notes:

- Telegram relay is optional and disabled by default.
- Messages are throttled per rider (default every 120 seconds) to avoid spam.
- You can still set `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` via environment as initial defaults.

## 2) Android App Setup

1. Open folder `android-beacon-app` in Android Studio.
2. Let Gradle sync and install SDK if prompted.
3. Run app on device.
4. Fill:
   - `Server URL`: your server public URL (example `https://mybeacon.example.com`)
   - `Rider ID`: unique id for rider (example `budi-bike`)
   - `Family/API key`: same as `BEACON_API_KEY`
5. Tap `Settings` to customize:
   - Base mode (`Eco`, `Balanced`, `Live`)
   - Custom interval (seconds, `0` = auto from mode; Eco auto = `600` seconds / 10 minutes)
   - Min distance update (`km` in UI; example `5` = 5 km)
   - Low-battery threshold and fallback interval
   - `Force Eco mode on RUN` (when ON, selected mode is ignored and tracking always uses Eco)
   - Permanent field labels are shown above each numeric input (not only placeholder/hint), so values stay understandable after input.
6. Tap `RUN Beacon`.
7. App will move to background automatically (no active UI on screen) and continue tracking via foreground notification.

Settings labels are intentionally bilingual (`Indonesia / English`) for easier field mapping during setup.

### Parameter Labels (UI)

- `Interval kirim / Send interval`: value in seconds.
- `Jarak minimum update / Min update distance`: value in kilometers (`km`).
- `Ambang baterai rendah / Low battery threshold`: value in percent (`%`).
- `Interval baterai rendah / Low battery interval`: value in seconds.

Value meaning examples:

- `0` for interval means auto mode (Eco default = `600` seconds / 10 minutes).
- `0.025` for min distance means `0.025 km` = `25 meters` (legacy/old value format seen on earlier config).

### Legacy Value Auto-Normalization

- On opening Settings, old small distance values (`0 < minDistanceMeters <= 999`) are treated as legacy and auto-normalized to `5 km` (`5000` meters).
- This avoids confusing display like `0.025` and aligns with current default recommendation.

## 3) Family Viewer

Open the server web page, fill same:

- Server URL
- Rider ID
- API key

Then click `Track`.

### Mode Switch (Telegram vs Cloud)

Viewer now has `relayMode` switch:

- `telegram`: map reads local server rider endpoint (`/api/v1/rider/...`).
- `cloud`: map reads Supabase tables (`beacons`, `trips`, `trip_points`).

When mode is changed from UI, server sends a Telegram notification message describing the switch.

## Battery-Efficient Defaults

Implemented battery-saving decisions:

- Uses Android foreground service only while ride is active.
- `Eco` mode default with low-power GPS priority and longer interval (`600` seconds / 10 minutes).
- Batches updates (`setMaxUpdateDelayMillis`) to reduce wakeups.
- Uses network calls with short timeout and tiny JSON payload.
- Default minimum distance is `5 km` (stored internally as `5000` meters).

## Production Recommendations

- Put server behind HTTPS (Nginx/Caddy/Cloudflare Tunnel).
- Use strong random `BEACON_API_KEY`.
- Add database (PostgreSQL/SQLite) if you need persistent history.
- Add per-family accounts and rider sharing permissions.

## Supabase PostgreSQL Setup (Trip + Point History)

For persistent trip tracking on Supabase:

1. Open Supabase SQL Editor.
2. Run SQL file:
   - `beacon-server/sql/supabase_schema.sql`
3. (Optional) create a test beacon row in `public.beacons`.
4. Open local UI:
   - `http://localhost:8080/supabase-viewer.html`
5. Fill:
   - Supabase URL
   - Supabase anon key
   - Beacon code

This viewer loads trips from `trips` and route points from `trip_points`, then draws path and start/end markers on map.

### Dummy Data for Cloud Test

Run:

- `migrations/seed_dummy.sql`

After that, use cloud mode with beacon code:

- `BEACON-DEMO-001`

Seed profile in this file:

- `10 trips x 2000 points` (total `20000` points)
- Cloud panel supports loading trip list and selecting a specific trip.

## Important Android Notes

- Android may still limit background behavior on some phone brands unless battery optimization is disabled for this app.
- For ultra-long rides, stay in `Eco` mode and keep screen off.
