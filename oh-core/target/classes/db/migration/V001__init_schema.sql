-- V001: OpenHarness 初始数据库 (MySQL 8.0)
CREATE TABLE IF NOT EXISTS sessions (
    id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(32) NOT NULL DEFAULT 'active',  -- active / paused / completed
    model VARCHAR(64) NOT NULL,                    -- claude-sonnet / claude-opus / ...
    max_turns INT DEFAULT 100,
    cost DECIMAL(10, 4) DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS messages (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    turn_number INT NOT NULL,
    role VARCHAR(16) NOT NULL,              -- user / assistant / tool_result
    content JSON NOT NULL,                   -- message 完整 JSON (text + tool_use 等)
    tool_name VARCHAR(128),                  -- tool_result 时非空
    tool_use_id VARCHAR(64),                 -- tool_use 匹配 tool_result
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_turn (session_id, turn_number),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS interaction_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL UNIQUE,
    session_id VARCHAR(36) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    user_intent VARCHAR(32),                 -- code_fix / feature_impl / question / refactor / other
    summary VARCHAR(512),
    input_tokens INT DEFAULT 0,
    context_tokens INT DEFAULT 0,            -- 可用上下文 token 数
    -- 情绪信号 (SentimentAnalyzer 自动识别)
    sentiment_score DECIMAL(3,2) DEFAULT 0,  -- -1.00 ~ 1.00
    sentiment_label VARCHAR(16) DEFAULT 'neutral', -- positive / neutral / negative
    turns INT DEFAULT 0,
    tool_calls INT DEFAULT 0,
    tools_failed INT DEFAULT 0,              -- 工具执行失败次数
    tools_used JSON,
    duration_ms BIGINT DEFAULT 0,
    cost DECIMAL(10, 4) DEFAULT 0,
    permission_denials INT DEFAULT 0,        -- PermissionChecker 拦截次数
    compaction_count INT DEFAULT 0,          -- Compaction 触发次数
    model_switched_to VARCHAR(64),           -- 用户中途切换的模型 (null=未切换)
    task_completed BOOLEAN DEFAULT FALSE,
    first_turn_correct BOOLEAN DEFAULT FALSE, -- 第一轮就正确完成
    user_corrections INT DEFAULT 0,
    user_accepted BOOLEAN,
    user_rating TINYINT,                     -- 1-5
    fallback_triggered VARCHAR(64),
    evolution_version VARCHAR(64),           -- 进化版本号, 用于 A/B 对比
    skills_used JSON,
    evolution_related BOOLEAN DEFAULT FALSE,
    INDEX idx_session (session_id),
    INDEX idx_evolution_version (evolution_version),
    INDEX idx_timestamp (timestamp),
    INDEX idx_sentiment (sentiment_label),   -- 按情绪筛选
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS replay_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    turn_number INT NOT NULL,
    event_type VARCHAR(32) NOT NULL,         -- api_request / api_response / tool_call
    request_json JSON,                       -- API request body (脱敏后)
    response_json JSON,                      -- API response body
    tool_args_json JSON,                     -- tool call 参数
    tool_result_json JSON,                   -- tool 执行结果
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_replay_session (session_id, turn_number),
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);
