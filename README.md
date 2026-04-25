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
   - Custom interval (seconds, `0` = auto from mode)
   - Min distance update (meters)
   - Low-battery threshold and fallback interval
   - `Force Eco profile on RUN`
6. Tap `RUN Beacon`.
7. App will move to background automatically (no active UI on screen) and continue tracking via foreground notification.

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
