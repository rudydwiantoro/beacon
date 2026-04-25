const express = require("express");
const path = require("path");

const app = express();

const PORT = Number(process.env.PORT || 8080);
const API_KEY = process.env.BEACON_API_KEY || "change-me";
const MAX_HISTORY = 2000;
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || "";
const TELEGRAM_CHAT_ID = process.env.TELEGRAM_CHAT_ID || "";
const TELEGRAM_MIN_INTERVAL_SEC = Math.max(
  0,
  Number(process.env.TELEGRAM_MIN_INTERVAL_SEC || 120)
);

app.use(express.json({ limit: "256kb" }));

app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, x-beacon-key");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  if (req.method === "OPTIONS") {
    res.status(204).end();
    return;
  }
  next();
});

const riders = new Map();
const telegramLastSentByRider = new Map();

function withAuth(req, res, next) {
  const provided = req.header("x-beacon-key") || req.query.key;
  if (provided !== API_KEY) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  next();
}

function isTelegramEnabled() {
  return Boolean(TELEGRAM_BOT_TOKEN && TELEGRAM_CHAT_ID);
}

function shouldSendTelegramNow(riderId) {
  const now = Date.now();
  const last = telegramLastSentByRider.get(riderId) || 0;
  if (now - last < TELEGRAM_MIN_INTERVAL_SEC * 1000) {
    return false;
  }
  telegramLastSentByRider.set(riderId, now);
  return true;
}

async function sendTelegramBeacon(point) {
  if (!isTelegramEnabled()) return;
  if (!shouldSendTelegramNow(point.riderId)) return;

  if (typeof fetch !== "function") {
    console.warn("Telegram send skipped: fetch API unavailable in this Node runtime.");
    return;
  }

  const speedKmh =
    typeof point.speed === "number" ? `${(point.speed * 3.6).toFixed(1)} km/h` : "-";
  const battery = typeof point.battery === "number" ? `${point.battery}%` : "-";
  const mapLink = `https://maps.google.com/?q=${point.lat},${point.lon}`;
  const text =
    `Beacon update\n` +
    `Rider: ${point.riderId}\n` +
    `Mode: ${point.mode}\n` +
    `Battery: ${battery}\n` +
    `Speed: ${speedKmh}\n` +
    `Accuracy: ${point.accuracy ?? "-"}\n` +
    `Time: ${point.timestamp}\n` +
    `Map: ${mapLink}`;

  const url = `https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage`;
  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        chat_id: TELEGRAM_CHAT_ID,
        text,
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      console.error(`Telegram send failed: HTTP ${response.status} ${body}`);
    }
  } catch (error) {
    console.error(`Telegram send failed: ${error.message}`);
  }
}

app.post("/api/v1/beacon", withAuth, (req, res) => {
  const {
    riderId,
    lat,
    lon,
    accuracy = null,
    speed = null,
    bearing = null,
    battery = null,
    mode = "eco",
    timestamp,
  } = req.body || {};

  if (!riderId || typeof lat !== "number" || typeof lon !== "number") {
    res.status(400).json({ error: "riderId, lat, lon are required" });
    return;
  }

  const point = {
    riderId,
    lat,
    lon,
    accuracy,
    speed,
    bearing,
    battery,
    mode,
    timestamp: timestamp || new Date().toISOString(),
    receivedAt: new Date().toISOString(),
  };

  if (!riders.has(riderId)) {
    riders.set(riderId, { latest: point, history: [point] });
  } else {
    const state = riders.get(riderId);
    state.latest = point;
    state.history.push(point);
    if (state.history.length > MAX_HISTORY) {
      state.history.splice(0, state.history.length - MAX_HISTORY);
    }
  }

  sendTelegramBeacon(point);
  res.status(202).json({ ok: true });
});

app.post("/api/v1/telegram/test", withAuth, async (_req, res) => {
  if (!isTelegramEnabled()) {
    res.status(400).json({
      error: "Telegram is not enabled. Set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID.",
    });
    return;
  }

  if (typeof fetch !== "function") {
    res.status(500).json({ error: "Node runtime does not support fetch API." });
    return;
  }

  const url = `https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage`;
  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        chat_id: TELEGRAM_CHAT_ID,
        text: "Beacon server test message: Telegram integration is active.",
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      res.status(502).json({ error: `Telegram API failed: HTTP ${response.status}`, body });
      return;
    }
  } catch (error) {
    res.status(502).json({ error: `Telegram API request failed: ${error.message}` });
    return;
  }

  res.json({ ok: true });
});

app.get("/api/v1/rider/:riderId/latest", withAuth, (req, res) => {
  const state = riders.get(req.params.riderId);
  if (!state) {
    res.status(404).json({ error: "Rider not found" });
    return;
  }
  res.json(state.latest);
});

app.get("/api/v1/rider/:riderId/history", withAuth, (req, res) => {
  const state = riders.get(req.params.riderId);
  if (!state) {
    res.status(404).json({ error: "Rider not found" });
    return;
  }
  const limit = Math.max(1, Math.min(1000, Number(req.query.limit || 300)));
  res.json(state.history.slice(-limit));
});

app.get("/api/v1/riders", withAuth, (_req, res) => {
  res.json(Array.from(riders.keys()));
});

app.use(express.static(path.join(__dirname, "..", "public")));

app.get("*", (_req, res) => {
  res.sendFile(path.join(__dirname, "..", "public", "index.html"));
});

app.listen(PORT, () => {
  console.log(`Beacon server listening on http://localhost:${PORT}`);
  console.log("Set BEACON_API_KEY for production.");
  if (isTelegramEnabled()) {
    console.log(
      `Telegram enabled for chat ${TELEGRAM_CHAT_ID} (min interval ${TELEGRAM_MIN_INTERVAL_SEC}s).`
    );
  } else {
    console.log("Telegram disabled. Set TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID to enable.");
  }
});
