"""
HOJ VJudge Service — CF个人账号绑定 + 远程提交
"""
import os
import re
import json
import time
import base64
import hashlib
import logging
from typing import Optional
from datetime import datetime
from contextlib import asynccontextmanager

import aiomysql
import httpx
from fastapi import FastAPI, HTTPException

logging.basicConfig(level=logging.INFO, format='%(asctime)s [vjudge] %(message)s')
log = logging.getLogger(__name__)

DB_CONFIG = {
    'host': os.getenv('MYSQL_HOST', '172.20.0.3'),
    'port': int(os.getenv('MYSQL_PORT', '3306')),
    'user': os.getenv('MYSQL_USERNAME', 'root'),
    'password': os.getenv('MYSQL_ROOT_PASSWORD', 'hoj123456'),
    'db': os.getenv('MYSQL_DATABASE_NAME', 'hoj'),
}
PORT = int(os.getenv('VJUDGE_SERVER_PORT', '9004'))
ENCRYPTION_KEY = os.getenv('ENCRYPTION_KEY', 'hoj-vjudge-key-2026')

pool = None

CF_LANG_MAP = {
    'C++': '54', 'G++': '54', 'C++17': '54', 'C++20': '89',
    'C': '43', 'Python3': '70', 'PyPy3': '70',
    'Java': '60', 'Java11': '60',
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


def _cipher(text: str, key: str) -> str:
    key_bytes = hashlib.sha256(key.encode()).digest()
    text_bytes = text.encode()
    result = bytes(b ^ key_bytes[i % len(key_bytes)] for i, b in enumerate(text_bytes))
    return base64.b64encode(result).decode()

def encrypt(text: str) -> str:
    return _cipher(text, ENCRYPTION_KEY)

def decrypt(token: str) -> str:
    result = base64.b64decode(token.encode())
    key_bytes = hashlib.sha256(ENCRYPTION_KEY.encode()).digest()
    return bytes(b ^ key_bytes[i % len(key_bytes)] for i, b in enumerate(result)).decode()


async def get_cf_credentials(uid: str) -> Optional[dict]:
    rows = await query("SELECT cf_username, cf_password FROM user_info WHERE uuid = %s", (uid,))
    if rows and rows[0]['cf_username'] and rows[0]['cf_password']:
        try:
            return {
                'username': rows[0]['cf_username'],
                'password': decrypt(rows[0]['cf_password']),
            }
        except Exception:
            return None
    return None


class CFClient:
    """Codeforces web client for submission."""

    def __init__(self):
        self.client = httpx.Client(timeout=30.0, follow_redirects=True)

    def login(self, handle: str, password: str) -> bool:
        try:
            resp = self.client.get('https://codeforces.com/enter')
            csrf = re.search(r'<meta name="X-Csrf-Token" content="([^"]+)"', resp.text)
            csrf_token = csrf.group(1) if csrf else ''
            ftaa = hashlib.sha256(str(time.time()).encode()).hexdigest()[:18]

            resp2 = self.client.post('https://codeforces.com/enter', data={
                'csrf_token': csrf_token,
                'action': 'enter',
                'ftaa': ftaa,
                'bfaa': 'f1b3f18c715565b589b39d62e7d3c5e4',
                'handleOrEmail': handle,
                'password': password,
                '_tta': '176',
                'remember': 'on',
            })
            return 'Logout' in resp2.text or 'logout' in resp2.text.lower()
        except Exception as e:
            log.error(f"CF login failed: {e}")
            return False

    def submit(self, handle: str, password: str, contest_id: str, problem_index: str,
               lang: str, source: str) -> Optional[int]:
        if not self.login(handle, password):
            log.error(f"CF login failed for {handle}")
            return None

        try:
            # Get submit page CSRF
            url = f'https://codeforces.com/problemset/problem/{contest_id}/{problem_index}'
            resp = self.client.get(url)
            csrf = re.search(r'<meta name="X-Csrf-Token" content="([^"]+)"', resp.text)
            csrf_token = csrf.group(1) if csrf else ''

            # Get ftaa from cookies
            ftaa = ''

            lang_id = CF_LANG_MAP.get(lang, '54')

            resp2 = self.client.post(
                f'https://codeforces.com/problemset/submit?csrf_token={csrf_token}',
                data={
                    'csrf_token': csrf_token,
                    'ftaa': ftaa,
                    'bfaa': 'f1b3f18c715565b589b39d62e7d3c5e4',
                    'action': 'submitSolutionFormSubmitted',
                    'submittedProblemCode': f'{contest_id}{problem_index}',
                    'programTypeId': lang_id,
                    'source': source,
                    'tabSize': '4',
                    'sourceFile': '',
                    '_tta': '594',
                },
                headers={'Referer': url}
            )

            if resp2.status_code in (200, 302):
                # Poll for submission ID
                time.sleep(3)
                sub_id = self._poll_submission_id(handle, contest_id, problem_index)
                return sub_id
            return None
        except Exception as e:
            log.error(f"CF submit failed: {e}")
            return None

    def _poll_submission_id(self, handle: str, contest_id: str, problem_index: str, max_wait: int = 15) -> Optional[int]:
        deadline = time.time() + max_wait
        while time.time() < deadline:
            try:
                resp = self.client.get(
                    f'https://codeforces.com/api/user.status?handle={handle}&from=1&count=5')
                data = resp.json()
                if data['status'] == 'OK':
                    for sub in data['result']:
                        prob = sub.get('problem', {})
                        cid = str(prob.get('contestId', ''))
                        idx = prob.get('index', '')
                        if cid == contest_id and idx == problem_index:
                            return sub['id']
            except Exception:
                pass
            time.sleep(2)
        return None

    def get_verdict(self, handle: str, submission_id: int) -> Optional[dict]:
        try:
            resp = self.client.get(
                f'https://codeforces.com/api/user.status?handle={handle}&from=1&count=10')
            data = resp.json()
            if data['status'] == 'OK':
                for sub in data['result']:
                    if sub['id'] == submission_id:
                        return {
                            'submission_id': sub['id'],
                            'verdict': sub.get('verdict', 'TESTING'),
                            'time': sub.get('timeConsumedMillis', 0),
                            'memory': sub.get('memoryConsumedBytes', 0),
                            'passed': sub.get('passedTestCount', 0),
                        }
        except Exception as e:
            log.error(f"CF status check failed: {e}")
        return None

    def close(self):
        self.client.close()


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    pool = await aiomysql.create_pool(**DB_CONFIG, minsize=2, maxsize=10, autocommit=True)
    log.info(f"VJudge service on :{PORT}")
    log.info(f"Encryption key (first 8): {ENCRYPTION_KEY[:8]}... (save this!)")
    yield
    pool.close()
    await pool.wait_closed()

app = FastAPI(title="HOJ VJudge Service", lifespan=lifespan)


@app.get("/health")
async def health():
    return {"status": "ok", "service": "vjudge"}


@app.post("/api/vjudge/bind-cf")
async def bind_cf(request: dict):
    """Bind personal CF account."""
    uid = request.get('uid')
    cf_username = request.get('cf_username', '').strip()
    cf_password = request.get('cf_password', '').strip()

    if not uid or not cf_username or not cf_password:
        raise HTTPException(status_code=400, detail="uid, cf_username, cf_password required")

    # Verify credentials by attempting login
    client = CFClient()
    try:
        ok = client.login(cf_username, cf_password)
        if not ok:
            raise HTTPException(status_code=400, detail="CF登录失败，请检查账号密码")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"CF登录失败: {str(e)}")
    finally:
        client.close()

    encrypted_pw = encrypt(cf_password)
    await execute(
        "UPDATE user_info SET cf_username = %s, cf_password = %s WHERE uuid = %s",
        (cf_username, encrypted_pw, uid)
    )

    log.info(f"CF account bound: uid={uid} cf={cf_username}")
    return {"status": "ok", "cf_username": cf_username}


@app.get("/api/vjudge/cf-status/{uid}")
async def get_cf_bind_status(uid: str):
    """Check if user has bound CF account."""
    rows = await query("SELECT cf_username FROM user_info WHERE uuid = %s", (uid,))
    if rows and rows[0]['cf_username']:
        return {"bound": True, "cf_username": rows[0]['cf_username']}
    return {"bound": False, "cf_username": None}


@app.post("/api/vjudge/submit-cf")
async def submit_to_cf(request: dict):
    """Submit code to Codeforces using user's personal account."""
    uid = request.get('uid')
    contest_id = str(request.get('contest_id', ''))
    problem_index = str(request.get('problem_index', ''))
    source = request.get('source', '')
    lang = request.get('lang', 'C++')

    if not all([uid, contest_id, problem_index, source]):
        raise HTTPException(status_code=400, detail="Missing required fields")

    creds = await get_cf_credentials(str(uid))
    if not creds:
        raise HTTPException(status_code=400, detail="请先绑定CF账号")

    client = CFClient()
    try:
        sub_id = client.submit(
            handle=creds['username'],
            password=creds['password'],
            contest_id=contest_id,
            problem_index=problem_index,
            lang=lang,
            source=source,
        )
        if not sub_id:
            raise HTTPException(status_code=500, detail="提交失败，请稍后重试")

        log.info(f"CF submit: uid={uid} cf={creds['username']} {contest_id}{problem_index} sub_id={sub_id}")

        # Poll for result (up to 30s)
        for _ in range(15):
            time.sleep(2)
            result = client.get_verdict(creds['username'], sub_id)
            if result and result['verdict'] not in ('TESTING', None):
                return {
                    'status': 'ok',
                    'submission_id': sub_id,
                    'verdict': result['verdict'],
                    'time_ms': result.get('time', 0),
                    'memory_bytes': result.get('memory', 0),
                    'passed': result.get('passed', 0),
                }

        return {
            'status': 'pending',
            'submission_id': sub_id,
            'verdict': 'TESTING',
            'message': '判题中，请稍后查询'
        }
    finally:
        client.close()


@app.get("/api/vjudge/check/{uid}/{submission_id}")
async def check_result(uid: str, submission_id: int):
    """Check CF submission result."""
    creds = await get_cf_credentials(uid)
    if not creds:
        raise HTTPException(status_code=400, detail="请先绑定CF账号")

    client = CFClient()
    try:
        result = client.get_verdict(creds['username'], submission_id)
        if result:
            return {'status': 'ok', **result}
        return {'status': 'pending', 'verdict': 'TESTING'}
    finally:
        client.close()
