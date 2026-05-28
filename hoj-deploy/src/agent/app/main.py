"""
HOJ Agent Service — 状态机、卡点识别、渐进式提示
"""
import os
import re
import json
import time
import logging
import hashlib
from typing import Optional
from datetime import datetime, timedelta
from contextlib import asynccontextmanager

import aiomysql
import httpx
from fastapi import FastAPI, Request, HTTPException
from fastapi.responses import JSONResponse

logging.basicConfig(level=logging.INFO, format='%(asctime)s [agent] %(message)s')
log = logging.getLogger(__name__)

DB_CONFIG = {
    'host': os.getenv('MYSQL_HOST', '172.20.0.3'),
    'port': int(os.getenv('MYSQL_PORT', '3306')),
    'user': os.getenv('MYSQL_USERNAME', 'root'),
    'password': os.getenv('MYSQL_ROOT_PASSWORD', 'hoj123456'),
    'db': os.getenv('MYSQL_DATABASE_NAME', 'hoj'),
}
PORT = int(os.getenv('AGENT_SERVER_PORT', '9003'))
LLM_API_KEY = os.getenv('LLM_API_KEY', '')
LLM_API_BASE = os.getenv('LLM_API_BASE', 'https://api.deepseek.com')
LLM_MODEL = os.getenv('LLM_MODEL', 'deepseek-chat')
LLM_DAILY_LIMIT = int(os.getenv('LLM_DAILY_LIMIT', '50'))

pool = None
llm_call_count = {}

# --- Stuck detection ---
STUCK_RULES = {
    'logic': {
        'description': '思路问题 — 多次WA且代码变化小',
        'check': lambda subs, _edits: (
            len(subs) >= 3
            and all(s.get('result') in ('WA', 'TLE') for s in subs[-3:])
            and len({s.get('code_hash', '') for s in subs[-3:]}) <= 2
        ),
        'confidence': 0.85,
    },
    'optimization': {
        'description': '优化问题 — 持续TLE',
        'check': lambda subs, _edits: (
            len([s for s in subs[-3:] if s.get('result') == 'TLE']) >= 2
        ),
        'confidence': 0.75,
    },
    'implementation': {
        'description': '实现问题 — CE/RE反复',
        'check': lambda subs, _edits: (
            len([s for s in subs[-3:] if s.get('result') in ('CE', 'RE')]) >= 2
        ),
        'confidence': 0.70,
    },
    'abandoned': {
        'description': '放弃 — 长时间无操作',
        'check': lambda _, edits: (
            len(edits) > 0 and edits[-1].get('duration', 0) > 900
        ),
        'confidence': 0.50,
    },
    'repeated_failure': {
        'description': '反复失败 — 5+次提交无AC',
        'check': lambda subs, _edits: (
            len(subs) >= 5
            and not any(s.get('result') == 'AC' for s in subs[-5:])
        ),
        'confidence': 0.90,
    },
}

# --- Hint templates (Level 1 = free, no LLM needed) ---
HINT_TEMPLATES = {
    'logic': [
        "这道题的突破口可能不在你已经尝试的方向上，换个角度思考？",
        "注意题目中的特殊约束条件，它们往往藏着关键信息。",
        "尝试构造一些小的测试用例，手动模拟你的算法看看是否合理。",
        "你的做法可能在边界条件上有所遗漏，检查一下极端输入。",
    ],
    'optimization': [
        "你的算法复杂度可能超出了题目限制，试试找更优的解法。",
        "考虑是否存在重复计算，能否用数据结构或预处理优化？",
        "检查一下内层循环是否可以省略或者用数学方法替代。",
    ],
    'implementation': [
        "检查数组是否越界、变量是否初始化、类型是否正确。",
        "仔细阅读编译器/运行时错误信息，通常能直接定位问题。",
        "用在线自测功能跑一下边界用例，看看哪里崩溃。",
    ],
    'abandoned': [
        "休息一下也不错，回来后可以试试先把思路写下来再写代码。",
        "不要着急，编程竞赛的乐趣在于思考过程本身。",
    ],
    'repeated_failure': [
        "已经尝试很多次了，要不要先去做一道更简单的相关题建立信心？",
        "坚持下去！每个ACMer都经历过这个阶段。建议先看看这道题的官方题解思路。",
    ],
}


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


async def check_active_contest(uid: int) -> Optional[int]:
    """Check if user is currently in a running contest."""
    rows = await query("""
        SELECT c.id FROM contest c
        JOIN contest_register cr ON c.id = cr.cid
        WHERE cr.uid = %s AND c.status = 1
          AND NOW() BETWEEN c.start_time AND c.end_time
        LIMIT 1
    """, (uid,))
    return rows[0]['id'] if rows else None


async def get_recent_behavior(uid: int, pid: int, minutes: int = 30) -> dict:
    """Get recent submissions and editor events."""
    cutoff = datetime.now() - timedelta(minutes=minutes)
    rows = await query("""
        SELECT event_type, payload, created_at
        FROM behavior_event
        WHERE uid = %s AND pid = %s AND created_at >= %s
        ORDER BY created_at DESC LIMIT 100
    """, (str(uid), pid, cutoff))

    submissions = []
    editor_events = []
    for r in rows:
        p = json.loads(r['payload']) if isinstance(r['payload'], str) else (r['payload'] or {})
        p['timestamp'] = r['created_at'].isoformat()
        if r['event_type'] == 'submit_result':
            submissions.append(p)
        elif r['event_type'] == 'editor_active':
            editor_events.append(p)

    return {'submissions': submissions, 'editor_events': editor_events}


async def detect_stuck(uid: int, pid: int) -> dict:
    """Detect if user is stuck and what type."""
    behavior = await get_recent_behavior(uid, pid)
    subs = behavior['submissions']
    edits = behavior['editor_events']

    # Check each rule
    for stuck_type, rule in STUCK_RULES.items():
        try:
            if rule['check'](subs, edits):
                return {
                    'is_stuck': True,
                    'stuck_type': stuck_type,
                    'confidence': rule['confidence'],
                    'description': rule['description'],
                    'submission_count': len(subs),
                }
        except Exception:
            continue

    return {'is_stuck': False, 'stuck_type': None}


async def count_daily_llm_calls(uid: int) -> int:
    return llm_call_count.get(uid, 0)


async def call_llm(system_prompt: str, user_prompt: str, uid: int, max_tokens: int = 200) -> str:
    """Call LLM API with rate limiting and cost control."""
    daily = await count_daily_llm_calls(uid)
    if daily >= LLM_DAILY_LIMIT:
        return "[今日AI调用次数已达上限，请明日再试]"

    if not LLM_API_KEY:
        return "[AI服务未配置API Key，请联系管理员]"

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                f"{LLM_API_BASE}/v1/chat/completions",
                headers={
                    "Authorization": f"Bearer {LLM_API_KEY}",
                    "Content-Type": "application/json"
                },
                json={
                    "model": LLM_MODEL,
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt}
                    ],
                    "max_tokens": max_tokens,
                    "temperature": 0.3,
                }
            )
            if resp.status_code == 200:
                data = resp.json()
                llm_call_count[uid] = daily + 1
                return data['choices'][0]['message']['content'].strip()
            else:
                log.error(f"LLM API error: {resp.status_code} {resp.text}")
                return "[AI服务暂时不可用，请稍后再试]"
    except Exception as e:
        log.error(f"LLM call failed: {e}")
        return "[AI服务暂时不可用，请稍后再试]"


async def generate_level2_hint(uid: int, pid: int, stuck_type: str, problem_tags: list) -> str:
    """Level 2 hint: suggest algorithm direction (LLM)."""
    system = "你是一位算法竞赛教练。提供简短、有引导性的提示，不要说答案。"

    tag_hint = f"题目标签: {', '.join(problem_tags)}" if problem_tags else ""
    user = f"""学生卡在{stuck_type}类问题。
{tag_hint}
请提供第2层提示（建议算法方向），不超过3句话。不要给出具体实现。"""

    return await call_llm(system, user, uid, max_tokens=150)


async def generate_level3_hint(uid: int, pid: int, stuck_type: str, problem_tags: list,
                                profile: dict) -> str:
    """Level 3 hint: key insight (LLM)."""
    system = "你是一位算法竞赛教练。学生在卡题时需要关键洞察。给一个准确但不过分的提示。"

    algo = profile.get('algorithm', {})
    weak_tags = [t for t, s in sorted(algo.items(), key=lambda x: x[1])[:3]]

    user = f"""学生卡在{stuck_type}类问题。
题目标签: {', '.join(problem_tags)}
学生弱项: {', '.join(weak_tags)}
学生整体rating: {profile.get('overall', {}).get('overall_rating', 1000)}

请给第3层提示（关键思维突破点），2-3句话。帮助理解解题方向，但不直接给出解法。"""

    return await call_llm(system, user, uid, max_tokens=200)


async def get_problem_tags(pid: int) -> list:
    rows = await query("SELECT tag_name FROM problem_tag WHERE pid = %s", (pid,))
    return [r['tag_name'] for r in rows]


async def get_or_create_profile(uid: int) -> dict:
    rows = await query("SELECT * FROM user_profile WHERE uid = %s", (uid,))
    if not rows:
        return {'uid': uid, 'algorithm': {}, 'overall': {'overall_rating': 1000}}
    r = rows[0]
    return {
        'uid': uid,
        'algorithm': {
            'dp': float(r['dp_score'] or 0), 'graph': float(r['graph_score'] or 0),
            'math': float(r['math_score'] or 0), 'greedy': float(r['greedy_score'] or 0),
            'string': float(r['string_score'] or 0), 'geometry': float(r['geometry_score'] or 0),
            'search': float(r['search_score'] or 0), 'data_structure': float(r['ds_score'] or 0),
            'implementation': float(r['impl_score'] or 0),
        },
        'overall': {'overall_rating': r['overall_rating'] or 1000}
    }


async def save_conversation(uid: int, pid: int, conv_type: str, user_msg: str,
                            ai_resp: str, hint_level: int, tokens: int = 0, cost: float = 0):
    await execute("""
        INSERT INTO agent_conversation (uid, pid, conversation_type, user_message, ai_response, hint_level, tokens_used, cost)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
    """, (uid, pid, conv_type, user_msg, ai_resp, hint_level, tokens, cost))


async def ensure_tables():
    await execute("""
        CREATE TABLE IF NOT EXISTS agent_conversation (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            uid BIGINT NOT NULL,
            pid BIGINT DEFAULT NULL,
            conversation_type VARCHAR(32) DEFAULT 'hint',
            user_message TEXT,
            ai_response TEXT,
            hint_level INT DEFAULT 1,
            tokens_used INT DEFAULT 0,
            cost DECIMAL(10,6) DEFAULT 0,
            is_helpful TINYINT(1) DEFAULT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_uid (uid),
            INDEX idx_uid_time (uid, created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    pool = await aiomysql.create_pool(**DB_CONFIG, minsize=2, maxsize=10, autocommit=True)
    await ensure_tables()
    # Reset daily LLM counters at midnight
    async def reset_counters():
        while True:
            now = datetime.now()
            midnight = (now + timedelta(days=1)).replace(hour=0, minute=0, second=0)
            await asyncio.sleep((midnight - now).total_seconds())
            llm_call_count.clear()
            log.info("Daily LLM counters reset")
    import asyncio
    asyncio.create_task(reset_counters())
    log.info(f"Agent service started on :{PORT}")
    yield
    pool.close()
    await pool.wait_closed()

app = FastAPI(title="HOJ AI Agent Service", lifespan=lifespan)


@app.middleware("http")
async def contest_isolation_middleware(request: Request, call_next):
    if request.url.path.startswith("/api/agent/") and request.method != "GET":
        body = None
        try:
            body = await request.json()
        except Exception:
            pass
        uid = body.get('uid') if body else None
        if uid:
            contest_id = await check_active_contest(uid)
            if contest_id:
                return JSONResponse(
                    status_code=403,
                    content={"error": "AI服务在比赛期间不可用", "contest_id": contest_id}
                )
    return await call_next(request)


@app.get("/health")
async def health():
    return {"status": "ok", "service": "agent"}


@app.post("/api/agent/detect")
async def detect_stuck_endpoint(request: dict):
    """Detect if a user is stuck on a problem."""
    uid = request.get('uid')
    pid = request.get('pid')
    if not uid or not pid:
        raise HTTPException(status_code=400, detail="uid and pid required")

    result = await detect_stuck(uid, pid)
    return result


@app.post("/api/agent/hint")
async def get_hint(request: dict):
    """Get progressive hint for stuck user."""
    uid = request.get('uid')
    pid = request.get('pid')
    level = request.get('level', 1)
    if not uid or not pid:
        raise HTTPException(status_code=400, detail="uid and pid required")

    # Check contest isolation
    contest_id = await check_active_contest(uid)
    if contest_id:
        raise HTTPException(status_code=403, detail=f"AI服务在比赛期间不可用")

    # First detect stuck state
    stuck = await detect_stuck(uid, pid)
    if not stuck['is_stuck'] and level > 1:
        return {
            'hint': "你看起来进展不错，继续保持！如果需要帮助，随时问我。",
            'level': 1,
            'stuck_state': stuck,
        }

    stuck_type = stuck['stuck_type'] or 'logic'
    problem_tags = await get_problem_tags(pid)

    if level == 1:
        import random
        templates = HINT_TEMPLATES.get(stuck_type, HINT_TEMPLATES['logic'])
        hint = random.choice(templates)
    elif level == 2:
        hint = await generate_level2_hint(uid, pid, stuck_type, problem_tags)
    elif level == 3:
        profile = await get_or_create_profile(uid)
        hint = await generate_level3_hint(uid, pid, stuck_type, problem_tags, profile)
    else:
        hint = "请选择提示级别 1-3。"

    await save_conversation(uid, pid, 'hint', f"[auto] stuck:{stuck_type} level:{level}", hint, level)

    return {
        'hint': hint,
        'level': level,
        'stuck_state': stuck,
    }


@app.post("/api/agent/chat")
async def chat(request: dict):
    """Free-form chat with AI coach."""
    uid = request.get('uid')
    pid = request.get('pid')
    message = request.get('message', '').strip()
    if not uid or not message:
        raise HTTPException(status_code=400, detail="uid and message required")

    contest_id = await check_active_contest(uid)
    if contest_id:
        raise HTTPException(status_code=403, detail=f"AI服务在比赛期间不可用")

    system = """你是一位算法竞赛教练。你的风格是：
1. 不直接给出答案或完整代码
2. 引导思考，给方向性建议
3. 回答简洁，不超过4句话
4. 用中文回答"""

    response = await call_llm(system, message, uid, max_tokens=300)
    await save_conversation(uid, pid or 0, 'chat', message, response, 0)

    return {'reply': response}


@app.get("/api/agent/status/{uid}")
async def agent_status(uid: int):
    """Get user's AI usage status."""
    daily = await count_daily_llm_calls(uid)
    contest_id = await check_active_contest(uid)
    return {
        'uid': uid,
        'daily_llm_calls': daily,
        'daily_limit': LLM_DAILY_LIMIT,
        'in_contest': contest_id is not None,
        'contest_id': contest_id,
    }
