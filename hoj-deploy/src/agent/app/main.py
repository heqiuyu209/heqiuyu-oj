"""
HOJ Agent Service — 卡点识别 + 渐进式提示 + 题面/代码/提交/画像综合智能
"""
import os
import json
import random
import logging
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
BACKEND_URL = os.getenv('BACKEND_URL', 'http://172.20.0.5:6688')
LLM_API_KEY = os.getenv('LLM_API_KEY', '')
LLM_API_BASE = os.getenv('LLM_API_BASE', 'https://api.deepseek.com')
LLM_MODEL = os.getenv('LLM_MODEL', 'deepseek-chat')
LLM_DAILY_LIMIT = int(os.getenv('LLM_DAILY_LIMIT', '50'))

pool = None
llm_call_count = {}

STUCK_RULES = {
    'logic': {
        'desc': '思路问题 — 多次WA且代码变化小',
        'check': lambda subs, _edits: (
            len(subs) >= 3 and all(s.get('result') in ('WA', 'TLE') for s in subs[-3:])
            and len({s.get('code_hash', '') for s in subs[-3:]}) <= 2
        ),
    },
    'optimization': {
        'desc': '优化问题 — 持续TLE',
        'check': lambda subs, _edits: len([s for s in subs[-3:] if s.get('result') == 'TLE']) >= 2,
    },
    'implementation': {
        'desc': '实现问题 — CE/RE反复',
        'check': lambda subs, _edits: len([s for s in subs[-3:] if s.get('result') in ('CE', 'RE')]) >= 2,
    },
    'abandoned': {
        'desc': '长时间无操作',
        'check': lambda _, edits: len(edits) > 0 and edits[-1].get('duration', 0) > 900,
    },
    'repeated_failure': {
        'desc': '5+次提交无AC',
        'check': lambda subs, _edits: len(subs) >= 5 and not any(s.get('result') == 'AC' for s in subs[-5:]),
    },
}

HINT_TEMPLATES = {
    'logic': [
        "换个角度思考？检查题目中的特殊约束条件，它们往往藏着关键信息。",
        "尝试构造一些小的测试用例，手动模拟你的算法看看是否合理。",
        "你的做法可能在边界条件上有所遗漏，检查一下极端输入。",
    ],
    'optimization': [
        "你的算法复杂度可能超限。考虑是否存在重复计算，能否用数据结构优化？",
        "检查内层循环是否可以用数学方法替代，或者是否需要更高效的数据结构。",
    ],
    'implementation': [
        "检查数组是否越界、变量是否初始化、类型是否正确。",
        "仔细阅读编译器/运行时错误信息，通常能直接定位问题。用在线自测跑边界用例。",
    ],
    'abandoned': [
        "休息一下后，试试先把思路写下来再写代码。",
        "不要着急，编程竞赛的乐趣在于思考过程本身。",
    ],
    'repeated_failure': [
        "已经尝试很多次了。要不要先去做一道更简单的相关题建立信心？",
        "坚持下去！建议先看看这道题的官方题解思路，理解后再自己实现。",
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


async def get_problem_detail(pid: int) -> dict:
    """Fetch problem title, description, tags from HOJ backend."""
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(f"{BACKEND_URL}/api/get-problem-detail", params={"pid": pid})
            if resp.status_code == 200:
                data = resp.json()
                if data.get('status') == 200 and data.get('data'):
                    d = data['data']
                    problem = d.get('problem') or {}
                    tags_rows = await query(
                        "SELECT t.name FROM problem_tag pt JOIN tag t ON pt.tid = t.id WHERE pt.pid = %s", (pid,))
                    tags = [r['name'] for r in tags_rows]
                    return {
                        'title': problem.get('title', ''),
                        'description': problem.get('description', '')[:2000],
                        'difficulty': problem.get('difficulty', 0),
                        'tags': tags,
                        'time_limit': problem.get('timeLimit', 0),
                        'memory_limit': problem.get('memoryLimit', 0),
                    }
    except Exception as e:
        log.warning(f"Failed to fetch problem {pid}: {e}")
    return {}


async def get_user_profile(uid: str) -> dict:
    rows = await query("SELECT * FROM user_profile WHERE uid = %s", (uid,))
    if not rows:
        return {'uid': uid, 'algorithm': {}, 'overall': {'overall_rating': 1000}}
    r = rows[0]
    return {
        'uid': uid,
        'algorithm': {
            'dp': float(r.get('dp_score', 0) or 0),
            'graph': float(r.get('graph_score', 0) or 0),
            'math': float(r.get('math_score', 0) or 0),
            'greedy': float(r.get('greedy_score', 0) or 0),
            'string': float(r.get('string_score', 0) or 0),
            'geometry': float(r.get('geometry_score', 0) or 0),
            'search': float(r.get('search_score', 0) or 0),
            'data_structure': float(r.get('ds_score', 0) or 0),
            'implementation': float(r.get('impl_score', 0) or 0),
        },
        'overall': {'overall_rating': r.get('overall_rating') or 1000}
    }


async def get_recent_behavior(uid: str, pid: int, minutes: int = 30) -> dict:
    cutoff = datetime.now() - timedelta(minutes=minutes)
    rows = await query("""
        SELECT event_type, payload, created_at FROM behavior_event
        WHERE uid = %s AND pid = %s AND created_at >= %s
        ORDER BY created_at DESC LIMIT 100
    """, (uid, pid, cutoff))

    submissions, editor_events = [], []
    for r in rows:
        p = json.loads(r['payload']) if isinstance(r['payload'], str) else (r['payload'] or {})
        p['timestamp'] = r['created_at'].isoformat()
        if r['event_type'] == 'submit_result':
            submissions.append(p)
        elif r['event_type'] == 'editor_active':
            editor_events.append(p)
    return {'submissions': submissions, 'editor_events': editor_events}


async def detect_stuck(uid: str, pid: int) -> dict:
    behavior = await get_recent_behavior(uid, pid)
    subs = behavior['submissions']
    edits = behavior['editor_events']

    for stuck_type, rule in STUCK_RULES.items():
        try:
            if rule['check'](subs, edits):
                return {
                    'is_stuck': True, 'stuck_type': stuck_type,
                    'description': rule['desc'], 'submission_count': len(subs),
                }
        except Exception:
            continue
    return {'is_stuck': False, 'stuck_type': None}


async def check_active_contest(uid: str) -> Optional[int]:
    rows = await query("""
        SELECT c.id FROM contest c JOIN contest_register cr ON c.id = cr.cid
        WHERE cr.uid = %s AND c.status = 1 AND NOW() BETWEEN c.start_time AND c.end_time LIMIT 1
    """, (uid,))
    return rows[0]['id'] if rows else None


async def call_llm(system: str, user: str, uid: str, max_tokens: int = 300) -> str:
    daily = llm_call_count.get(uid, 0)
    if daily >= LLM_DAILY_LIMIT:
        return "[今日AI调用次数已达上限]"
    if not LLM_API_KEY:
        return "[AI服务未配置API Key]"

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                f"{LLM_API_BASE}/v1/chat/completions",
                headers={"Authorization": f"Bearer {LLM_API_KEY}", "Content-Type": "application/json"},
                json={
                    "model": LLM_MODEL,
                    "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                    "max_tokens": max_tokens, "temperature": 0.3,
                }
            )
            if resp.status_code == 200:
                llm_call_count[uid] = daily + 1
                return resp.json()['choices'][0]['message']['content'].strip()
            log.error(f"LLM error: {resp.status_code}")
    except Exception as e:
        log.error(f"LLM failed: {e}")
    return "[AI服务暂时不可用]"


def build_smart_hint(problem: dict, code: str, subs: list, profile: dict, stuck_type: str) -> str:
    """Build a rich context prompt for LLM-based hint generation."""
    parts = []

    # Problem context
    if problem.get('title'):
        parts.append(f"【题目】{problem['title']}")
        if problem.get('tags'):
            parts.append(f"标签: {', '.join(problem['tags'])}")
        if problem.get('difficulty'):
            parts.append(f"难度: {problem['difficulty']}")
        if problem.get('description'):
            desc = problem['description'].replace('\n', ' ')[:800]
            parts.append(f"题面摘要: {desc}")

    # User's current code
    if code:
        parts.append(f"【学生当前代码】\n```\n{code[:2000]}\n```")

    # Recent submissions
    if subs:
        sub_summary = [f"最近{len(subs)}次提交:"]
        for s in subs[-5:]:
            sub_summary.append(f"  - {s.get('result', '?')} (语言:{s.get('lang', '?')})")
        parts.append('\n'.join(sub_summary))

    # User profile
    if profile.get('overall', {}).get('overall_rating', 1000) > 1000:
        rating = profile['overall']['overall_rating']
        algo = profile.get('algorithm', {})
        weak = sorted(algo.items(), key=lambda x: x[1])[:3]
        strong = sorted(algo.items(), key=lambda x: x[1], reverse=True)[:2]
        parts.append(f"学生rating: {rating}")
        parts.append(f"强项: {', '.join(f'{t}({s:.0f})' for t, s in strong)}")
        parts.append(f"弱项: {', '.join(f'{t}({s:.0f})' for t, s in weak)}")

    parts.append(f"\n学生卡在 {stuck_type} 类问题。请给出1个具体的、有针对性的提示（不是笼统的方向），"
                 f"结合题目标签和学生的弱项，不超过3句话。不要直接给代码。")
    return '\n'.join(parts)


async def save_conversation(uid: str, pid: int, conv_type: str, user_msg: str,
                            ai_resp: str, hint_level: int, tokens: int = 0, cost: float = 0):
    await execute("""
        INSERT INTO agent_conversation (uid, pid, conversation_type, user_message, ai_response, hint_level, tokens_used, cost)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
    """, (uid, pid, conv_type, user_msg, ai_resp, hint_level, tokens, cost))


async def ensure_tables():
    await execute("""
        CREATE TABLE IF NOT EXISTS agent_conversation (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            uid VARCHAR(64) NOT NULL, pid BIGINT DEFAULT NULL,
            conversation_type VARCHAR(32) DEFAULT 'hint',
            user_message TEXT, ai_response TEXT, hint_level INT DEFAULT 1,
            tokens_used INT DEFAULT 0, cost DECIMAL(10,6) DEFAULT 0,
            is_helpful TINYINT(1) DEFAULT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_uid (uid), INDEX idx_uid_time (uid, created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    pool = await aiomysql.create_pool(**DB_CONFIG, minsize=2, maxsize=10, autocommit=True)
    await ensure_tables()

    async def reset_counters():
        import asyncio as aio
        while True:
            now = datetime.now()
            midnight = (now + timedelta(days=1)).replace(hour=0, minute=0, second=0)
            await aio.sleep((midnight - now).total_seconds())
            llm_call_count.clear()
    import asyncio as aio
    aio.create_task(reset_counters())
    log.info(f"Agent service on :{PORT}")
    yield
    pool.close()
    await pool.wait_closed()

app = FastAPI(title="HOJ AI Agent Service", lifespan=lifespan)


@app.middleware("http")
async def contest_isolation_middleware(request: Request, call_next):
    if request.url.path.startswith("/api/agent/"):
        uid = None
        # Extract uid from path for GET requests like /api/agent/status/{uid}
        if request.method == "GET":
            parts = request.url.path.rstrip('/').split('/')
            if len(parts) >= 5:
                uid = parts[-1]
        else:
            try:
                body = await request.json()
            except Exception:
                body = None
            uid = body.get('uid') if body else None

        if uid and uid != 'undefined' and uid != 'null':
            cid = await check_active_contest(str(uid))
            if cid:
                return JSONResponse(status_code=403, content={
                    "error": "AI服务在比赛期间不可用",
                    "contest_id": cid
                })
    return await call_next(request)


@app.get("/health")
async def health():
    return {"status": "ok", "service": "agent"}


@app.post("/api/agent/detect")
async def detect_stuck_endpoint(request: dict):
    uid = request.get('uid')
    pid = request.get('pid')
    if not uid or not pid:
        raise HTTPException(status_code=400, detail="uid and pid required")
    return await detect_stuck(str(uid), pid)


@app.post("/api/agent/hint")
async def get_hint(request: dict):
    uid = request.get('uid')
    pid = request.get('pid')
    level = request.get('level', 1)
    code = request.get('code', '')  # NEW: user's current code from editor
    if not uid or not pid:
        raise HTTPException(status_code=400, detail="uid and pid required")

    uid_s = str(uid)
    cid = await check_active_contest(uid_s)
    if cid:
        raise HTTPException(status_code=403, detail="AI服务在比赛期间不可用")

    stuck = await detect_stuck(uid_s, pid)
    stuck_type = stuck['stuck_type'] or 'logic'

    if level == 1:
        templates = HINT_TEMPLATES.get(stuck_type, HINT_TEMPLATES['logic'])
        hint = random.choice(templates)
    else:
        # Level 2/3: fetch full context and call LLM
        problem = await get_problem_detail(pid)
        behavior = await get_recent_behavior(uid_s, pid)
        subs = behavior['submissions']
        profile = await get_user_profile(uid_s)

        system = "你是一位算法竞赛教练。你的提示要针对具体题目和学生的实际情况，不要泛泛而谈。用中文，2-3句话。"
        user_prompt = build_smart_hint(problem, code, subs, profile, stuck_type)

        if level == 3:
            user_prompt += "\n\n请给更深层的提示——结合学生代码中的具体问题。"
            hint = await call_llm(system, user_prompt, uid_s, max_tokens=300)
        else:
            hint = await call_llm(system, user_prompt, uid_s, max_tokens=200)

    await save_conversation(uid_s, pid, 'hint', f"[auto] stuck:{stuck_type} level:{level}", hint, level)

    return {'hint': hint, 'level': level, 'stuck_state': stuck}


@app.post("/api/agent/chat")
async def chat(request: dict):
    uid = request.get('uid')
    pid = request.get('pid')
    message = request.get('message', '').strip()
    code = request.get('code', '')
    if not uid or not message:
        raise HTTPException(status_code=400, detail="uid and message required")

    uid_s = str(uid)
    cid = await check_active_contest(uid_s)
    if cid:
        raise HTTPException(status_code=403, detail="AI服务在比赛期间不可用")

    # Build context for smart chat
    problem = await get_problem_detail(pid) if pid else {}
    profile = await get_user_profile(uid_s)

    context = ""
    if problem.get('title'):
        context += f"当前题目: {problem['title']} (标签: {', '.join(problem.get('tags', []))})\n"
    if code:
        context += f"学生当前代码:\n```\n{code[:2000]}\n```\n"
    if profile.get('overall', {}).get('overall_rating', 1000) > 1000:
        weak = sorted(profile.get('algorithm', {}).items(), key=lambda x: x[1])[:3]
        context += f"学生弱项: {', '.join(f'{t}({s:.0f})' for t, s in weak)}\n"

    system = "你是一位算法竞赛教练。不要直接给答案。结合题目和学生的弱项给出引导。用中文，3-4句话。"
    user = f"{context}\n学生问题: {message}"

    reply = await call_llm(system, user, uid_s, max_tokens=300)
    await save_conversation(uid_s, pid or 0, 'chat', message, reply, 0)

    return {'reply': reply}


@app.get("/api/agent/status/{uid}")
async def agent_status(uid: str):
    daily = llm_call_count.get(uid, 0)
    cid = await check_active_contest(uid)
    return {
        'uid': uid, 'daily_llm_calls': daily,
        'daily_limit': LLM_DAILY_LIMIT,
        'in_contest': cid is not None, 'contest_id': cid,
    }
