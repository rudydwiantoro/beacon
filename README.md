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
npm start
```

Server runs on `http://localhost:8080` by default.

Viewer page: `http://localhost:8080`

## 2) Android App Setup

1. Open folder `android-beacon-app` in Android Studio.
2. Let Gradle sync and install SDK if prompted.
3. Run app on device.
4. Fill:
   - `Server URL`: your server public URL (example `https://mybeacon.example.com`)
   - `Rider ID`: unique id for rider (example `budi-bike`)
   - `Family/API key`: same as `BEACON_API_KEY`
5. Pick mode:
   - `Eco`: every ~3 min, low power priority (best battery)
   - `Balanced`: every ~1 min
   - `Live`: every ~20 sec, high accuracy (higher battery)
6. Tap `Start Tracking`.

## 3) Family Viewer

Open the server web page, fill same:

- Server URL
- Rider ID
- API key

Then click `Track`.

## Battery-Efficient Defaults

Implemented battery-saving decisions:

- Uses Android foreground service only while ride is active.
- `Eco` mode default with low-power GPS priority and longer interval.
- Batches updates (`setMaxUpdateDelayMillis`) to reduce wakeups.
- Uses network calls with short timeout and tiny JSON payload.

## Production Recommendations

- Put server behind HTTPS (Nginx/Caddy/Cloudflare Tunnel).
- Use strong random `BEACON_API_KEY`.
- Add database (PostgreSQL/SQLite) if you need persistent history.
- Add per-family accounts and rider sharing permissions.

## Important Android Notes

- Android may still limit background behavior on some phone brands unless battery optimization is disabled for this app.
- For ultra-long rides, stay in `Eco` mode and keep screen off.
