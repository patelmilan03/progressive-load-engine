-- =============================================================================
--  Progressive Load & Recovery Analytics Engine — Database Schema
--  Engine: SQLite 3
--  Naming convention: snake_case for tables and columns
-- =============================================================================

-- Drop tables in dependency-safe order (children first)
DROP TABLE IF EXISTS exercise_sets;
DROP TABLE IF EXISTS hypertrophy_sessions;
DROP TABLE IF EXISTS cardio_sessions;
DROP TABLE IF EXISTS workout_sessions;
DROP TABLE IF EXISTS users;

-- =============================================================================
--  TABLE: users
--  Stores base physiological metrics used to normalise load calculations.
--  The "6 ft / 66 kg" baseline from the spec maps directly here.
-- =============================================================================
CREATE TABLE users (
    id                         INTEGER PRIMARY KEY AUTOINCREMENT,
    name                       TEXT    NOT NULL,
    height_cm                  REAL    NOT NULL,          -- e.g. 182.88 (= 6 ft)
    weight_kg                  REAL    NOT NULL,          -- e.g. 66.0
    age                        INTEGER NOT NULL,
    resting_heart_rate         INTEGER NOT NULL DEFAULT 60,
    max_heart_rate             INTEGER NOT NULL DEFAULT 190,
    training_experience_years  REAL    NOT NULL DEFAULT 1.0,
    created_at                 TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- =============================================================================
--  TABLE: workout_sessions
--  Parent table — every session (strength or cardio) gets a row here.
--  The concrete details live in the child tables joined by session_id.
-- =============================================================================
CREATE TABLE workout_sessions (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id             INTEGER NOT NULL,
    workout_type        TEXT    NOT NULL,   -- PUSH | PULL | LEGS | CARDIO_5K | REST
    session_timestamp   TEXT    NOT NULL,   -- ISO-8601: 'YYYY-MM-DDTHH:MM:SS'
    notes               TEXT,
    calculated_load     REAL    DEFAULT 0.0,
    created_at          TEXT    NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_ws_user_ts ON workout_sessions(user_id, session_timestamp);

-- =============================================================================
--  TABLE: hypertrophy_sessions
--  One-to-one child of workout_sessions for PPL strength days.
-- =============================================================================
CREATE TABLE hypertrophy_sessions (
    session_id            INTEGER PRIMARY KEY,   -- FK → workout_sessions.id
    ppl_day               TEXT    NOT NULL,       -- PUSH | PULL | LEGS
    primary_muscle_group  TEXT    NOT NULL,       -- e.g. CHEST, BACK, QUADS
    FOREIGN KEY (session_id) REFERENCES workout_sessions(id) ON DELETE CASCADE
);

-- =============================================================================
--  TABLE: exercise_sets
--  Each row = one exercise entry (may represent multiple physical sets).
--  E.g. "Bench Press | 4 sets | 8 reps | 80 kg | RPE 8"
-- =============================================================================
CREATE TABLE exercise_sets (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      INTEGER NOT NULL,
    exercise_name   TEXT    NOT NULL,
    set_count       INTEGER NOT NULL DEFAULT 1,
    reps            INTEGER NOT NULL,
    weight_kg       REAL    NOT NULL,
    rpe             REAL    NOT NULL DEFAULT 7.0,   -- Rate of Perceived Exertion 1–10
    primary_muscle  TEXT    NOT NULL,               -- MuscleGroup enum value
    FOREIGN KEY (session_id) REFERENCES workout_sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_es_session ON exercise_sets(session_id);

-- =============================================================================
--  TABLE: cardio_sessions
--  One-to-one child of workout_sessions for 5K running days.
-- =============================================================================
CREATE TABLE cardio_sessions (
    session_id          INTEGER PRIMARY KEY,    -- FK → workout_sessions.id
    distance_km         REAL    NOT NULL,
    pace_mins_per_km    REAL    NOT NULL,       -- e.g. 5.5 means 5:30/km
    avg_heart_rate      INTEGER NOT NULL,
    max_heart_rate_hit  INTEGER NOT NULL,
    duration_minutes    REAL    NOT NULL,
    FOREIGN KEY (session_id) REFERENCES workout_sessions(id) ON DELETE CASCADE
);
