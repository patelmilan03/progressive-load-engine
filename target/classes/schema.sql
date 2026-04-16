-- =============================================================================
--  Progressive Load & Recovery Analytics Engine — Database Schema
--  Engine: SQLite 3   |   Uses CREATE TABLE IF NOT EXISTS for safe restarts
-- =============================================================================

CREATE TABLE IF NOT EXISTS users (
    id                         INTEGER PRIMARY KEY AUTOINCREMENT,
    name                       TEXT    NOT NULL,
    height_cm                  REAL    NOT NULL,
    weight_kg                  REAL    NOT NULL,
    age                        INTEGER NOT NULL,
    resting_heart_rate         INTEGER NOT NULL DEFAULT 60,
    max_heart_rate             INTEGER NOT NULL DEFAULT 190,
    training_experience_years  REAL    NOT NULL DEFAULT 1.0,
    created_at                 TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS workout_sessions (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id             INTEGER NOT NULL,
    workout_type        TEXT    NOT NULL,
    session_timestamp   TEXT    NOT NULL,
    notes               TEXT,
    calculated_load     REAL    DEFAULT 0.0,
    created_at          TEXT    NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ws_user_ts ON workout_sessions(user_id, session_timestamp);

CREATE TABLE IF NOT EXISTS hypertrophy_sessions (
    session_id            INTEGER PRIMARY KEY,
    ppl_day               TEXT    NOT NULL,
    primary_muscle_group  TEXT    NOT NULL,
    FOREIGN KEY (session_id) REFERENCES workout_sessions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS exercise_sets (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      INTEGER NOT NULL,
    exercise_name   TEXT    NOT NULL,
    set_count       INTEGER NOT NULL DEFAULT 1,
    reps            INTEGER NOT NULL,
    weight_kg       REAL    NOT NULL,
    rpe             REAL    NOT NULL DEFAULT 7.0,
    primary_muscle  TEXT    NOT NULL,
    FOREIGN KEY (session_id) REFERENCES workout_sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_es_session ON exercise_sets(session_id);

CREATE TABLE IF NOT EXISTS cardio_sessions (
    session_id          INTEGER PRIMARY KEY,
    distance_km         REAL    NOT NULL,
    pace_mins_per_km    REAL    NOT NULL,
    avg_heart_rate      INTEGER NOT NULL,
    max_heart_rate_hit  INTEGER NOT NULL,
    duration_minutes    REAL    NOT NULL,
    FOREIGN KEY (session_id) REFERENCES workout_sessions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS personal_records (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id           INTEGER NOT NULL,
    exercise_name     TEXT    NOT NULL,
    weight_kg         REAL    NOT NULL,
    reps              INTEGER NOT NULL,
    rpe               REAL    NOT NULL,
    estimated_1rm_kg  REAL    NOT NULL,
    wilks_score       REAL    NOT NULL DEFAULT 0.0,
    primary_muscle    TEXT    NOT NULL,
    achieved_at       TEXT    NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_pr_user_exercise ON personal_records(user_id, exercise_name);
CREATE INDEX IF NOT EXISTS idx_pr_e1rm          ON personal_records(user_id, estimated_1rm_kg DESC);
