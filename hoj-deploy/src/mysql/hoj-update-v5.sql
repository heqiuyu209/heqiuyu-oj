-- HOJ v5 升级 SQL: AI训练系统新增表
-- 在已有 hoj 数据库中执行

-- 用户画像表
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
    INDEX idx_overall_rating (overall_rating),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 行为事件表（如果 behavior 服务未自动创建）
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 题目标签表
CREATE TABLE IF NOT EXISTS problem_tag (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pid BIGINT NOT NULL,
    tag_name VARCHAR(64) NOT NULL,
    tag_source VARCHAR(32) DEFAULT 'manual' COMMENT 'manual|codeforces|atcoder|auto',
    difficulty_rating INT DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_pid (pid),
    INDEX idx_tag (tag_name),
    UNIQUE KEY uk_pid_tag (pid, tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 推荐记录表
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- AI对话记录表
CREATE TABLE IF NOT EXISTS agent_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid BIGINT NOT NULL,
    pid BIGINT DEFAULT NULL,
    conversation_type VARCHAR(32) DEFAULT 'hint' COMMENT 'hint|explain|chat',
    user_message TEXT,
    ai_response TEXT,
    hint_level INT DEFAULT 1,
    tokens_used INT DEFAULT 0,
    cost DECIMAL(10,6) DEFAULT 0,
    is_helpful TINYINT(1) DEFAULT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_uid (uid),
    INDEX idx_uid_time (uid, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
