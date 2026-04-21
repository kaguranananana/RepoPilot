CREATE TABLE plans (
    plan_id VARCHAR(128) PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
    turn_id VARCHAR(128),
    content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_plans_session_created
    ON plans (session_id, created_at DESC, plan_id DESC);
