"""
HOJ Profile Service — 用户画像计算引擎
"""
import os
import asyncio
import logging
from datetime import datetime, date, timedelta
from typing import Optional
from contextlib import asynccontextmanager

import aiomysql
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from apscheduler.schedulers.asyncio import AsyncIOScheduler

logging.basicConfig(level=logging.INFO, format='%(asctime)s [profile] %(message)s')
log = logging.getLogger(__name__)

DB_CONFIG = {
    'host': os.getenv('MYSQL_HOST', '172.20.0.3'),
    'port': int(os.getenv('MYSQL_PORT', '3306')),
    'user': os.getenv('MYSQL_USERNAME', 'root'),
    'password': os.getenv('MYSQL_ROOT_PASSWORD', 'hoj123456'),
    'db': os.getenv('MYSQL_DATABASE_NAME', 'hoj'),
}
PORT = int(os.getenv('PROFILE_SERVER_PORT', '9001'))

pool = None

# --- DB helpers ---
async def query(sql, params=None):
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute(sql, params)
            return await cur.fetchall()

async def execute(sql, params=None):
    async with pool.acquire() as conn:
        async with conn.cursor() as cur:
            await cur.execute(sql, params)
        await conn.commit()

# --- Core: tag-based ability scoring ---
# Map problem tags to profile columns
TAG_COLUMN_MAP = {
    'dp': 'dp_score', 'graph': 'graph_score', 'math': 'math_score',
    'greedy': 'greedy_score', 'string': 'string_score', 'geometry': 'geometry_score',
    'search': 'search_score', 'data_structure': 'ds_score', 'implementation': 'impl_score'
}

ALGO_TAGS = list(TAG_COLUMN_MAP.keys())


async def calc_tag_score(uid: int, tag: str) -> float:
    """Calculate a user's ability score for a specific algorithm tag (0-100)."""
    rows = await query("""
        SELECT be.payload, be.event_type
        FROM behavior_event be
        WHERE be.uid = %s AND be.event_type = 'submit_result'
        ORDER BY be.created_at DESC
        LIMIT 500
    """, (str(uid),))

    tagged = []
    for r in rows:
        p = r['payload'] if isinstance(r['payload'], dict) else {}
        ptag = (p.get('tags') or '').lower()
        if tag in ptag:
            tagged.append(p)

    total = len(tagged)
    if total < 3:
        return 0

    ac = sum(1 for s in tagged if s.get('result') == 'AC')
    ac_rate = ac / total
    first_ac = sum(1 for s in tagged if s.get('attempt') == 1 and s.get('result') == 'AC') / max(total, 1)

    diffs = [s.get('difficulty', 1000) for s in tagged if s.get('result') == 'AC']
    avg_diff = sum(diffs) / max(len(diffs), 1) if diffs else 1000

    score = ac_rate * 100 * (0.4 + 0.6 * first_ac) * min(avg_diff / 1200, 1.5)
    return round(min(100, score), 2)


async def calc_behavior_scores(uid: int) -> dict:
    """Calculate persistence, independent thinking, and debug ability."""
    uid_s = str(uid)

    # Persistence: solved rate on difficult problems (difficulty > user's avg)
    rows = await query("""
        SELECT payload FROM behavior_event
        WHERE uid = %s AND event_type = 'submit_result'
        ORDER BY created_at DESC LIMIT 200
    """, (uid_s,))

    if not rows:
        return {'persistence_score': 0, 'independent_thinking': 0, 'debug_ability': 0}

    submits = [r['payload'] if isinstance(r['payload'], dict) else {} for r in rows]
    ac_count = sum(1 for s in submits if s.get('result') == 'AC')
    wa_count = sum(1 for s in submits if s.get('result') in ('WA', 'TLE'))

    # Independence: ratio of AC without viewing solution first
    solution_views = await query("""
        SELECT COUNT(*) as cnt FROM behavior_event
        WHERE uid = %s AND event_type = 'view_solution'
    """, (uid_s,))
    view_cnt = solution_views[0]['cnt'] if solution_views else 0

    total = len(submits)
    persistence = min(100, round((wa_count / max(total, 1)) * 100, 2))
    independence = min(100, round(max(0, 100 - (view_cnt / max(total, 1)) * 100), 2))

    # Debug ability: WA→AC turnaround speed (lower attempts = better)
    problems_seen = {}
    for s in submits:
        pid = s.get('pid')
        if pid:
            problems_seen.setdefault(pid, []).append(s.get('result'))

    quick_fixes = 0
    total_problems = len(problems_seen)
    for pid, results in problems_seen.items():
        if 'AC' in results and results.index('AC') <= 2:
            quick_fixes += 1
    debug = min(100, round((quick_fixes / max(total_problems, 1)) * 100, 2)) if total_problems else 0

    return {'persistence_score': persistence, 'independent_thinking': independence, 'debug_ability': debug}


async def calc_coding_scores(uid: int) -> dict:
    """Estimate C++ proficiency and code style from submission metadata."""
    rows = await query("""
        SELECT payload FROM behavior_event
        WHERE uid = %s AND event_type = 'submit_result'
        ORDER BY created_at DESC LIMIT 300
    """, (str(uid),))

    submits = [r['payload'] if isinstance(r['payload'], dict) else {} for r in rows]
    cpp_subs = [s for s in submits if s.get('lang') in ('C++', 'cpp', 'G++', 'C++17', 'C++20')]
    if not cpp_subs:
        return {'cpp_proficiency': 0, 'code_style_score': 0}

    ac_rate = sum(1 for s in cpp_subs if s.get('result') == 'AC') / len(cpp_subs)
    cpp_prof = min(100, round(ac_rate * 80 + min(len(cpp_subs) / 3, 20), 2))
    code_style = min(100, round(ac_rate * 70 + 10, 2))

    return {'cpp_proficiency': cpp_prof, 'code_style_score': code_style}


async def calc_overall(uid: int, algo_scores: dict, behavior: dict, coding: dict) -> dict:
    """Compute overall rating based on all dimensions."""
    rows = await query("""
        SELECT COUNT(*) as total, SUM(CASE WHEN JSON_EXTRACT(payload, '$.result') = 'AC' THEN 1 ELSE 0 END) as solved
        FROM behavior_event WHERE uid = %s AND event_type = 'submit_result'
    """, (str(uid),))
    stats = rows[0] if rows else {'total': 0, 'solved': 0}

    active_days = await query("""
        SELECT COUNT(DISTINCT DATE(created_at)) as days FROM behavior_event WHERE uid = %s
    """, (str(uid),))
    active = active_days[0]['days'] if active_days else 0

    avg_algo = sum(algo_scores.values()) / max(len(algo_scores), 1)
    rating = int(800 + avg_algo * 6 + (stats['solved'] or 0) * 0.5)
    rating = max(800, min(3000, rating))

    return {
        'overall_rating': rating,
        'total_solved': stats['solved'] or 0,
        'total_attempted': stats['total'] or 0,
        'ac_rate': round((stats['solved'] or 0) / max(stats['total'] or 1, 1), 3),
        'active_days': active,
    }


async def compute_full_profile(uid: int) -> dict:
    """Compute complete user profile."""
    algo_scores = {}
    for tag in ALGO_TAGS:
        algo_scores[tag] = await calc_tag_score(uid, tag)

    behavior = await calc_behavior_scores(uid)
    coding = await calc_coding_scores(uid)
    overall = await calc_overall(uid, algo_scores, behavior, coding)

    return {
        'uid': uid,
        'algorithm': algo_scores,
        'behavior': behavior,
        'coding': coding,
        'overall': overall,
        'updated_at': datetime.now().isoformat()
    }


async def store_profile(profile: dict):
    """Persist profile to user_profile table."""
    uid = profile['uid']
    algo = profile['algorithm']
    beh = profile['behavior']
    cod = profile['coding']
    ov = profile['overall']

    await execute("""
        INSERT INTO user_profile
        (uid, dp_score, graph_score, math_score, greedy_score, string_score,
         geometry_score, search_score, ds_score, impl_score,
         persistence_score, independent_thinking, debug_ability,
         cpp_proficiency, code_style_score,
         overall_rating, total_solved, total_attempted, ac_rate, active_days, last_active_date)
        VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,CURDATE())
        ON DUPLICATE KEY UPDATE
         dp_score=VALUES(dp_score), graph_score=VALUES(graph_score),
         math_score=VALUES(math_score), greedy_score=VALUES(greedy_score),
         string_score=VALUES(string_score), geometry_score=VALUES(geometry_score),
         search_score=VALUES(search_score), ds_score=VALUES(ds_score),
         impl_score=VALUES(impl_score),
         persistence_score=VALUES(persistence_score),
         independent_thinking=VALUES(independent_thinking),
         debug_ability=VALUES(debug_ability),
         cpp_proficiency=VALUES(cpp_proficiency),
         code_style_score=VALUES(code_style_score),
         overall_rating=VALUES(overall_rating), total_solved=VALUES(total_solved),
         total_attempted=VALUES(total_attempted), ac_rate=VALUES(ac_rate),
         active_days=VALUES(active_days), last_active_date=CURDATE(),
         updated_at=CURRENT_TIMESTAMP
    """, (
        uid, algo.get('dp', 0), algo.get('graph', 0), algo.get('math', 0),
        algo.get('greedy', 0), algo.get('string', 0), algo.get('geometry', 0),
        algo.get('search', 0), algo.get('data_structure', 0), algo.get('implementation', 0),
        beh['persistence_score'], beh['independent_thinking'], beh['debug_ability'],
        cod['cpp_proficiency'], cod['code_style_score'],
        ov['overall_rating'], ov['total_solved'], ov['total_attempted'],
        ov['ac_rate'], ov['active_days']
    ))


async def ensure_tables():
    await execute("""
        CREATE TABLE IF NOT EXISTS user_profile (
            uid BIGINT PRIMARY KEY,
            dp_score DECIMAL(5,2) DEFAULT 0,
            graph_score DECIMAL(5,2) DEFAULT 0,
            math_score DECIMAL(5,2) DEFAULT 0,
            greedy_score DECIMAL(5,2) DEFAULT 0,
            string_score DECIMAL(5,2) DEFAULT 0,
            geometry_score DECIMAL(5,2) DEFAULT 0,
            search_score DECIMAL(5,2) DEFAULT 0,
            ds_score DECIMAL(5,2) DEFAULT 0,
            impl_score DECIMAL(5,2) DEFAULT 0,
            persistence_score DECIMAL(5,2) DEFAULT 0,
            independent_thinking DECIMAL(5,2) DEFAULT 0,
            debug_ability DECIMAL(5,2) DEFAULT 0,
            cpp_proficiency DECIMAL(5,2) DEFAULT 0,
            code_style_score DECIMAL(5,2) DEFAULT 0,
            overall_rating INT DEFAULT 1000,
            total_solved INT DEFAULT 0,
            total_attempted INT DEFAULT 0,
            ac_rate DECIMAL(5,3) DEFAULT 0,
            active_days INT DEFAULT 0,
            last_active_date DATE,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX idx_overall_rating (overall_rating)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)
    log.info("Table user_profile ready")


# --- Scheduled daily recompute ---
async def daily_recompute():
    log.info("Starting daily profile recompute...")
    rows = await query("""
        SELECT DISTINCT uid FROM behavior_event
        WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
    """)
    for r in rows:
        try:
            uid = int(r['uid'])
            profile = await compute_full_profile(uid)
            await store_profile(profile)
            log.info(f"Profile updated: uid={uid} rating={profile['overall']['overall_rating']}")
        except Exception as e:
            log.error(f"Failed profile for uid={r['uid']}: {e}")
    log.info("Daily profile recompute done.")


# --- App ---
@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    pool = await aiomysql.create_pool(**DB_CONFIG, minsize=2, maxsize=10, autocommit=True)
    await ensure_tables()
    scheduler = AsyncIOScheduler()
    scheduler.add_job(daily_recompute, 'cron', hour=3, minute=0)
    scheduler.start()
    log.info(f"Profile service started on :{PORT}")
    yield
    scheduler.shutdown()
    pool.close()
    await pool.wait_closed()

app = FastAPI(title="HOJ Profile Service", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "ok", "service": "profile"}


@app.get("/api/profile/{uid}")
async def get_profile(uid: int):
    rows = await query("SELECT * FROM user_profile WHERE uid = %s", (uid,))
    if not rows:
        profile = await compute_full_profile(uid)
        await store_profile(profile)
        return profile
    r = rows[0]
    return {
        'uid': uid,
        'algorithm': {
            'dp': float(r['dp_score']), 'graph': float(r['graph_score']),
            'math': float(r['math_score']), 'greedy': float(r['greedy_score']),
            'string': float(r['string_score']), 'geometry': float(r['geometry_score']),
            'search': float(r['search_score']), 'data_structure': float(r['ds_score']),
            'implementation': float(r['impl_score'])
        },
        'behavior': {
            'persistence_score': float(r['persistence_score']),
            'independent_thinking': float(r['independent_thinking']),
            'debug_ability': float(r['debug_ability'])
        },
        'coding': {
            'cpp_proficiency': float(r['cpp_proficiency']),
            'code_style_score': float(r['code_style_score'])
        },
        'overall': {
            'overall_rating': r['overall_rating'],
            'total_solved': r['total_solved'],
            'total_attempted': r['total_attempted'],
            'ac_rate': float(r['ac_rate']),
            'active_days': r['active_days']
        },
        'updated_at': r['updated_at'].isoformat() if r['updated_at'] else None
    }


@app.post("/api/profile/{uid}/recompute")
async def recompute_profile(uid: int):
    profile = await compute_full_profile(uid)
    await store_profile(profile)
    return profile


@app.get("/api/profile/{uid}/history")
async def get_profile_history(uid: int, days: int = 30):
    rows = await query("""
        SELECT DATE(created_at) as dt, COUNT(*) as events,
               SUM(CASE WHEN event_type = 'submit_result' THEN 1 ELSE 0 END) as submissions
        FROM behavior_event WHERE uid = %s AND created_at >= DATE_SUB(NOW(), INTERVAL %s DAY)
        GROUP BY DATE(created_at) ORDER BY dt
    """, (str(uid), days))
    return [{'date': str(r['dt']), 'events': r['events'], 'submissions': r['submissions']} for r in rows]
