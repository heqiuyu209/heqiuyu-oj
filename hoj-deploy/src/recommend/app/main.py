"""
HOJ Recommend Service — 知识点缺陷推荐引擎
"""
import os
import logging
from contextlib import asynccontextmanager

import aiomysql
from fastapi import FastAPI
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format='%(asctime)s [recommend] %(message)s')
log = logging.getLogger(__name__)

DB_CONFIG = {
    'host': os.getenv('MYSQL_HOST', '172.20.0.3'),
    'port': int(os.getenv('MYSQL_PORT', '3306')),
    'user': os.getenv('MYSQL_USERNAME', 'root'),
    'password': os.getenv('MYSQL_ROOT_PASSWORD', 'hoj123456'),
    'db': os.getenv('MYSQL_DATABASE_NAME', 'hoj'),
}
PORT = int(os.getenv('RECOMMEND_SERVER_PORT', '9002'))
pool = None

PROFILE_SERVICE_URL = os.getenv('PROFILE_SERVICE_URL', 'http://172.20.0.10:9001')

# Learning path prerequisites
LEARNING_PREREQS = {
    'prefix_sum': [],
    'difference_array': ['prefix_sum'],
    'binary_indexed_tree': ['prefix_sum'],
    'segment_tree': ['binary_indexed_tree'],
    'binary_search': [],
    'two_pointers': ['binary_search'],
    'sliding_window': ['two_pointers'],
    'dfs': [],
    'bfs': ['dfs'],
    'backtracking': ['dfs'],
    'shortest_path': ['bfs'],
    'topological_sort': ['bfs'],
    'dp_basic': ['greedy', 'dfs'],
    'knapsack': ['dp_basic'],
    'interval_dp': ['dp_basic'],
    'tree_dp': ['dp_basic', 'dfs'],
    'digit_dp': ['dp_basic'],
    'expectation_dp': ['dp_basic', 'math'],
    'union_find': [],
    'mst': ['union_find'],
    'number_theory': ['math'],
    'combinatorics': ['math'],
}

TAG_TO_PROFILE_KEY = {
    'dp': 'dp_score', 'graph': 'graph_score', 'math': 'math_score',
    'greedy': 'greedy_score', 'string': 'string_score', 'geometry': 'geometry_score',
    'search': 'search_score', 'data_structure': 'ds_score', 'implementation': 'impl_score'
}


async def query(sql, params=None):
    async with pool.acquire() as conn:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            await cur.execute(sql, params)
            return await cur.fetchall()


async def get_user_profile(uid: int) -> dict:
    rows = await query("SELECT * FROM user_profile WHERE uid = %s", (uid,))
    if not rows:
        return {
            'uid': uid, 'overall_rating': 1000,
            'dp_score': 0, 'graph_score': 0, 'math_score': 0,
            'greedy_score': 0, 'string_score': 0, 'geometry_score': 0,
            'search_score': 0, 'ds_score': 0, 'impl_score': 0
        }
    r = rows[0]
    return {
        'uid': uid, 'overall_rating': r['overall_rating'] or 1000,
        'dp_score': float(r['dp_score'] or 0), 'graph_score': float(r['graph_score'] or 0),
        'math_score': float(r['math_score'] or 0), 'greedy_score': float(r['greedy_score'] or 0),
        'string_score': float(r['string_score'] or 0), 'geometry_score': float(r['geometry_score'] or 0),
        'search_score': float(r['search_score'] or 0), 'ds_score': float(r['ds_score'] or 0),
        'impl_score': float(r['impl_score'] or 0)
    }


async def get_available_problems(tag: str, min_diff: int, max_diff: int, exclude_uid: int, limit: int = 20):
    """Get problems matching tag and difficulty range, excluding solved ones."""
    rows = await query("""
        SELECT p.id as pid, p.title, p.difficulty, pt.tag_name
        FROM problem p
        LEFT JOIN problem_tag pt ON p.id = pt.pid
        WHERE pt.tag_name LIKE %s
          AND p.difficulty BETWEEN %s AND %s
          AND p.id NOT IN (
            SELECT DISTINCT pid FROM behavior_event
            WHERE uid = %s AND event_type = 'submit_result'
              AND JSON_EXTRACT(payload, '$.result') = 'AC'
          )
        ORDER BY ABS(p.difficulty - %s)
        LIMIT %s
    """, (f'%{tag}%', min_diff, max_diff, str(exclude_uid), (min_diff + max_diff) // 2, limit))
    return rows


async def recommend_by_weak_points(uid: int, profile: dict, limit: int = 10) -> list:
    """Core recommendation: find weakest tags and recommend problems."""
    algo_keys = [
        ('dp', profile.get('dp_score', 0)),
        ('graph', profile.get('graph_score', 0)),
        ('math', profile.get('math_score', 0)),
        ('greedy', profile.get('greedy_score', 0)),
        ('string', profile.get('string_score', 0)),
        ('geometry', profile.get('geometry_score', 0)),
        ('search', profile.get('search_score', 0)),
        ('data_structure', profile.get('ds_score', 0)),
        ('implementation', profile.get('impl_score', 0)),
    ]
    algo_keys.sort(key=lambda x: x[1])
    weak_tags = algo_keys[:3]

    rating = profile.get('overall_rating', 1000)
    min_diff = max(800, rating - 300)
    max_diff = min(3500, rating + 200)

    candidates = []
    for tag, score in weak_tags:
        problems = await get_available_problems(tag, min_diff, max_diff, uid)
        for p in problems:
            weak_weight = max(10, 100 - score)
            match_score = 1.0
            difficulty_fit = 1.0 - abs(int(p.get('difficulty') or 1000) - rating) / 500
            final_score = weak_weight * match_score * max(difficulty_fit, 0.1)
            candidates.append({
                'pid': p['pid'],
                'title': p.get('title', ''),
                'difficulty': p.get('difficulty', 1000),
                'tag': tag,
                'score': round(final_score, 2),
                'reason': f'强化 {tag}（当前评分: {score:.1f}/100）'
            })

    candidates.sort(key=lambda x: x['score'], reverse=True)
    seen = set()
    result = []
    for c in candidates:
        if c['pid'] not in seen and len(result) < limit:
            result.append(c)
            seen.add(c['pid'])

    return result


async def recommend_learning_path(uid: int, profile: dict) -> list:
    """Suggest next topics in learning path based on prerequisites."""
    algo_keys = {
        'dp': profile.get('dp_score', 0),
        'graph': profile.get('graph_score', 0),
        'math': profile.get('math_score', 0),
        'greedy': profile.get('greedy_score', 0),
        'string': profile.get('string_score', 0),
        'geometry': profile.get('geometry_score', 0),
        'search': profile.get('search_score', 0),
        'data_structure': profile.get('ds_score', 0),
        'implementation': profile.get('impl_score', 0),
    }

    path = []
    for topic, prereqs in LEARNING_PREREQS.items():
        if topic in algo_keys and algo_keys[topic] == 0:
            all_met = all(prereq not in algo_keys or algo_keys.get(prereq, 0) >= 30 for prereq in prereqs)
            if all_met:
                path.append({
                    'topic': topic,
                    'prerequisites': prereqs,
                    'prerequisites_met': True,
                    'suggested_rating': 1000 + len(prereqs) * 200
                })

    path.sort(key=lambda x: x['suggested_rating'])
    return path[:10]


async def ensure_tables():
    await query("""
        CREATE TABLE IF NOT EXISTS recommend_record (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            uid BIGINT NOT NULL,
            pid BIGINT NOT NULL,
            recommend_type VARCHAR(32) DEFAULT 'weak_point',
            score DECIMAL(6,2) DEFAULT 0,
            reason VARCHAR(255) DEFAULT NULL,
            is_clicked TINYINT(1) DEFAULT 0,
            is_solved TINYINT(1) DEFAULT 0,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_uid (uid),
            INDEX idx_uid_created (uid, created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)
    log.info("Table recommend_record ready")


@asynccontextmanager
async def lifespan(app: FastAPI):
    global pool
    pool = await aiomysql.create_pool(**DB_CONFIG, minsize=2, maxsize=10, autocommit=True)
    await ensure_tables()
    log.info(f"Recommend service started on :{PORT}")
    yield
    pool.close()
    await pool.wait_closed()

app = FastAPI(title="HOJ Recommend Service", lifespan=lifespan)


class RecommendResponse(BaseModel):
    recommendations: list
    generated_at: str = ''


@app.get("/health")
async def health():
    return {"status": "ok", "service": "recommend"}


@app.get("/api/recommend/{uid}")
async def recommend(uid: int, limit: int = 10, type: str = 'weak_point'):
    from datetime import datetime
    profile = await get_user_profile(uid)

    if type == 'learning_path':
        items = await recommend_learning_path(uid, profile)
        return {'uid': uid, 'type': 'learning_path', 'items': items, 'generated_at': datetime.now().isoformat()}

    items = await recommend_by_weak_points(uid, profile, limit)

    # Record recommendations
    for item in items:
        await query("""
            INSERT INTO recommend_record (uid, pid, recommend_type, score, reason)
            VALUES (%s, %s, %s, %s, %s)
        """, (uid, item['pid'], 'weak_point', item['score'], item['reason']))

    return {'uid': uid, 'type': 'weak_point', 'recommendations': items, 'generated_at': datetime.now().isoformat()}


@app.get("/api/learning-path/{uid}")
async def learning_path(uid: int):
    from datetime import datetime
    profile = await get_user_profile(uid)
    items = await recommend_learning_path(uid, profile)
    return {'uid': uid, 'current_rating': profile.get('overall_rating', 1000), 'learning_path': items, 'generated_at': datetime.now().isoformat()}
