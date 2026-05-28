const express = require('express');
const mysql = require('mysql2/promise');

const app = express();
app.use(express.json({ limit: '16kb' }));

// --- config from env ---
const MYSQL_HOST = process.env.MYSQL_HOST || '172.20.0.3';
const MYSQL_PORT = parseInt(process.env.MYSQL_PORT || '3306', 10);
const MYSQL_USER = process.env.MYSQL_USERNAME || 'root';
const MYSQL_PASSWORD = process.env.MYSQL_ROOT_PASSWORD || 'hoj123456';
const MYSQL_DATABASE = process.env.MYSQL_DATABASE_NAME || 'hoj';
const PORT = parseInt(process.env.BEHAVIOR_SERVER_PORT || '9090', 10);
const BATCH_SIZE = parseInt(process.env.BATCH_SIZE || '50', 10);
const FLUSH_INTERVAL_MS = parseInt(process.env.FLUSH_INTERVAL_MS || '5000', 10);
const API_KEY = process.env.BEHAVIOR_API_KEY || '';

const VALID_EVENTS = [
  'page_view', 'open_problem', 'problem_stay', 'submit', 'submit_result',
  'editor_active', 'editor_copy', 'editor_paste', 'editor_undo',
  'run_test', 'view_solution', 'scroll_depth'
];

let pool;
let eventBuffer = [];
let flushTimer;

// --- rate limiter (simple in-memory) ---
const rateLimitMap = new Map();
const RATE_LIMIT_WINDOW = 60000;
const RATE_LIMIT_MAX = parseInt(process.env.RATE_LIMIT_MAX || '600', 10);

function rateLimiter(req, res, next) {
  const ip = req.ip || req.connection.remoteAddress || 'unknown';
  const now = Date.now();
  const record = rateLimitMap.get(ip);
  if (!record || now - record.windowStart > RATE_LIMIT_WINDOW) {
    rateLimitMap.set(ip, { windowStart: now, count: 1 });
    return next();
  }
  record.count++;
  if (record.count > RATE_LIMIT_MAX) {
    return res.status(429).json({ error: 'rate limit exceeded' });
  }
  next();
}

// Periodic cleanup of stale rate limit entries
setInterval(() => {
  const cutoff = Date.now() - RATE_LIMIT_WINDOW * 2;
  for (const [ip, record] of rateLimitMap) {
    if (record.windowStart < cutoff) rateLimitMap.delete(ip);
  }
}, 300000);

// --- API Key auth ---
function authMiddleware(req, res, next) {
  if (!API_KEY) return next();
  const key = req.headers['x-api-key'];
  if (!key || key !== API_KEY) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  next();
}

// --- input validation ---
function validateEvent(e) {
  if (!e.uid || !e.event) return false;
  if (!VALID_EVENTS.includes(e.event)) return false;
  if (!Number.isInteger(e.uid) && typeof e.uid !== 'string') return false;
  if (e.pid && !Number.isInteger(e.pid)) return false;
  return true;
}

// --- init ---
async function ensureTables() {
  const conn = await pool.getConnection();
  try {
    await conn.execute(`
      CREATE TABLE IF NOT EXISTS behavior_event (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        uid VARCHAR(64) NOT NULL,
        pid BIGINT DEFAULT NULL,
        cid BIGINT DEFAULT NULL,
        event_type VARCHAR(64) NOT NULL,
        payload JSON DEFAULT NULL,
        created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP(3),
        INDEX idx_uid (uid),
        INDEX idx_uid_time (uid, created_at),
        INDEX idx_event_type (event_type),
        INDEX idx_pid (pid),
        INDEX idx_created_at (created_at)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    `);
    console.log('[behavior] table behavior_event ready');
  } finally {
    conn.release();
  }
}

async function flushBuffer() {
  if (eventBuffer.length === 0) return;
  const batch = eventBuffer.splice(0, BATCH_SIZE);
  try {
    const conn = await pool.getConnection();
    try {
      const sql = `INSERT INTO behavior_event (uid, pid, cid, event_type, payload) VALUES ?`;
      const values = batch.map(e => [
        String(e.uid), e.pid || null, e.cid || null,
        e.event, JSON.stringify(e.payload || {})
      ]);
      await conn.query(sql, [values]);
    } finally {
      conn.release();
    }
  } catch (err) {
    console.error('[behavior] flush error:', err.message);
    eventBuffer.unshift(...batch);
  }
}

function startFlushTimer() {
  flushTimer = setInterval(flushBuffer, FLUSH_INTERVAL_MS);
}

// --- routes ---
app.get('/health', (_req, res) => {
  res.json({ status: 'ok', buffered: eventBuffer.length, uptime: process.uptime() });
});

app.post('/api/beacon', authMiddleware, rateLimiter, async (req, res) => {
  const { uid, event, pid, cid, payload, timestamp } = req.body || {};
  if (!uid || !event || !VALID_EVENTS.includes(event)) {
    return res.status(400).json({ error: 'uid and valid event are required' });
  }
  eventBuffer.push({ uid, event, pid: pid || null, cid: cid || null, payload: payload || {}, timestamp });
  if (eventBuffer.length >= BATCH_SIZE * 2) {
    setImmediate(flushBuffer);
  }
  res.status(202).json({ accepted: true });
});

app.post('/api/beacon/batch', authMiddleware, rateLimiter, async (req, res) => {
  const events = req.body;
  if (!Array.isArray(events)) {
    return res.status(400).json({ error: 'array of events required' });
  }
  let accepted = 0;
  for (const e of events) {
    if (validateEvent(e)) {
      eventBuffer.push({
        uid: e.uid, event: e.event,
        pid: e.pid || null, cid: e.cid || null,
        payload: e.payload || {}, timestamp: e.timestamp
      });
      accepted++;
    }
  }
  if (eventBuffer.length >= BATCH_SIZE) {
    setImmediate(flushBuffer);
  }
  res.status(202).json({ accepted: true, count: accepted });
});

// --- graceful shutdown ---
process.on('SIGTERM', async () => {
  clearInterval(flushTimer);
  await flushBuffer();
  await pool.end();
  process.exit(0);
});

// --- start ---
(async () => {
  pool = mysql.createPool({
    host: MYSQL_HOST,
    port: MYSQL_PORT,
    user: MYSQL_USER,
    password: MYSQL_PASSWORD,
    database: MYSQL_DATABASE,
    waitForConnections: true,
    connectionLimit: 10,
    queueLimit: 0,
    charset: 'utf8mb4'
  });
  await ensureTables();
  startFlushTimer();
  app.listen(PORT, () => {
    console.log(`[behavior] listening on :${PORT}`);
  });
})();
