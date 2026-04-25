const express = require("express");
const path = require("path");

const app = express();

const PORT = Number(process.env.PORT || 8080);
const API_KEY = process.env.BEACON_API_KEY || "change-me";
const MAX_HISTORY = 2000;

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

function withAuth(req, res, next) {
  const provided = req.header("x-beacon-key") || req.query.key;
  if (provided !== API_KEY) {
    res.status(401).json({ error: "Unauthorized" });
    return;
  }
  next();
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

  res.status(202).json({ ok: true });
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
});
