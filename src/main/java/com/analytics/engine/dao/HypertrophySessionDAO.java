package com.analytics.engine.dao;

import com.analytics.engine.model.ExerciseSet;
import com.analytics.engine.model.HypertrophySession;
import com.analytics.engine.model.MuscleGroup;
import com.analytics.engine.model.WorkoutType;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Concrete DAO for {@link HypertrophySession} objects.
 *
 * <h3>Multi-Table Persistence</h3>
 * <p>A single {@code HypertrophySession} spans three tables:
 * <ol>
 *   <li>{@code workout_sessions}   — parent row with common fields</li>
 *   <li>{@code hypertrophy_sessions} — 1-to-1 child with PPL day info</li>
 *   <li>{@code exercise_sets}      — 1-to-many child (one row per exercise)</li>
 * </ol>
 * All three inserts are wrapped in a single transaction to maintain consistency.
 *
 * <h3>Read Strategy</h3>
 * <p>On retrieval, a JOIN query reconstructs the parent + child rows, and a
 * second query fetches the exercise sets.  This "SELECT N+1 avoidance" is
 * acceptable here because sessions are fetched one at a time or in small
 * batches.
 */
public final class HypertrophySessionDAO implements WorkoutSessionDAO<HypertrophySession> {

    private final DatabaseManager db;

    public HypertrophySessionDAO(DatabaseManager db) {
        this.db = db;
    }

    // ================================================================= WRITE

    @Override
    public void save(HypertrophySession session) throws SQLException {
        db.beginTransaction();
        try {
            long parentId = insertParent(session);
            session.setId(parentId);
            insertHypertrophyChild(session, parentId);
            insertExerciseSets(session.getExercises(), parentId);
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        }
    }

    private long insertParent(HypertrophySession s) throws SQLException {
        final String sql = """
            INSERT INTO workout_sessions
              (user_id, workout_type, session_timestamp, notes, calculated_load)
            VALUES (?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong  (1, s.getUserId());
            ps.setString(2, s.getWorkoutType().name());
            ps.setString(3, s.getSessionTimestamp().toString());
            ps.setString(4, s.getNotes());
            ps.setDouble(5, s.getCalculatedLoad());
            ps.executeUpdate();
            try (Statement st = db.getConnection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to retrieve generated id for workout_session.");
    }

    private void insertHypertrophyChild(HypertrophySession s, long parentId) throws SQLException {
        final String sql = """
            INSERT INTO hypertrophy_sessions (session_id, ppl_day, primary_muscle_group)
            VALUES (?, ?, ?)
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong  (1, parentId);
            ps.setString(2, s.getPplDay().name());
            ps.setString(3, s.getPrimaryMuscleGroup().name());
            ps.executeUpdate();
        }
    }

    private void insertExerciseSets(List<ExerciseSet> exercises,
                                    long sessionId) throws SQLException {
        final String sql = """
            INSERT INTO exercise_sets
              (session_id, exercise_name, set_count, reps, weight_kg, rpe, primary_muscle)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            for (int i = 0; i < exercises.size(); i++) {
                ExerciseSet ex = exercises.get(i);
                ps.setLong  (1, sessionId);
                ps.setString(2, ex.getExerciseName());
                ps.setInt   (3, ex.getSetCount());
                ps.setInt   (4, ex.getReps());
                ps.setDouble(5, ex.getWeightKg());
                ps.setDouble(6, ex.getRpe());
                ps.setString(7, ex.getPrimaryMuscle().name());
                ps.executeUpdate();
                // Retrieve the id for each exercise immediately after its own INSERT
                try (Statement st = db.getConnection().createStatement();
                     ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                    if (rs.next()) exercises.get(i).setId(rs.getLong(1));
                }
            }
        }
    }

    // ================================================================= READ

    @Override
    public Optional<HypertrophySession> findById(long id) throws SQLException {
        final String sql = """
            SELECT ws.*, hs.ppl_day, hs.primary_muscle_group
              FROM workout_sessions ws
              JOIN hypertrophy_sessions hs ON ws.id = hs.session_id
             WHERE ws.id = ?
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    HypertrophySession s = mapRow(rs);
                    loadExerciseSets(s);
                    return Optional.of(s);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<HypertrophySession> findByUserId(long userId) throws SQLException {
        return queryBy("ws.user_id = ?", userId);
    }

    @Override
    public List<HypertrophySession> findSessionsInLastNHours(long userId,
                                                              int hours) throws SQLException {
        // SQLite datetime arithmetic: subtract hours as fractional days
        final String sql = """
            SELECT ws.*, hs.ppl_day, hs.primary_muscle_group
              FROM workout_sessions ws
              JOIN hypertrophy_sessions hs ON ws.id = hs.session_id
             WHERE ws.user_id = ?
               AND ws.session_timestamp >= datetime('now', ? || ' hours')
             ORDER BY ws.session_timestamp DESC
            """;
        List<HypertrophySession> list = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong  (1, userId);
            ps.setString(2, "-" + hours);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HypertrophySession s = mapRow(rs);
                    loadExerciseSets(s);
                    list.add(s);
                }
            }
        }
        return list;
    }

    private List<HypertrophySession> queryBy(String whereClause,
                                             long paramValue) throws SQLException {
        final String sql = """
            SELECT ws.*, hs.ppl_day, hs.primary_muscle_group
              FROM workout_sessions ws
              JOIN hypertrophy_sessions hs ON ws.id = hs.session_id
             WHERE %s
             ORDER BY ws.session_timestamp DESC
            """.formatted(whereClause);

        List<HypertrophySession> list = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, paramValue);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    HypertrophySession s = mapRow(rs);
                    loadExerciseSets(s);
                    list.add(s);
                }
            }
        }
        return list;
    }

    private void loadExerciseSets(HypertrophySession session) throws SQLException {
        final String sql = """
            SELECT * FROM exercise_sets WHERE session_id = ? ORDER BY id
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, session.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ExerciseSet ex = ExerciseSet
                        .builder(session.getId(),
                                 rs.getString("exercise_name"),
                                 MuscleGroup.valueOf(rs.getString("primary_muscle")))
                        .sets    (rs.getInt   ("set_count"))
                        .reps    (rs.getInt   ("reps"))
                        .weightKg(rs.getDouble("weight_kg"))
                        .rpe     (rs.getDouble("rpe"))
                        .build();
                    ex.setId(rs.getLong("id"));
                    session.addExercise(ex);
                }
            }
        }
    }

    // ================================================================= UPDATE / DELETE

    @Override
    public void update(HypertrophySession session) throws SQLException {
        final String sql = """
            UPDATE workout_sessions SET notes = ?, calculated_load = ?
             WHERE id = ?
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, session.getNotes());
            ps.setDouble(2, session.getCalculatedLoad());
            ps.setLong  (3, session.getId());
            ps.executeUpdate();
        }
    }

    @Override
    public void delete(long id) throws SQLException {
        // CASCADE DELETE in the schema handles child table cleanup
        try (PreparedStatement ps = db.getConnection()
                .prepareStatement("DELETE FROM workout_sessions WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    // ================================================================= Mapping

    private HypertrophySession mapRow(ResultSet rs) throws SQLException {
        HypertrophySession s = new HypertrophySession(
            rs.getLong  ("user_id"),
            WorkoutType .valueOf(rs.getString("ppl_day")),
            MuscleGroup .valueOf(rs.getString("primary_muscle_group")),
            LocalDateTime.parse(rs.getString("session_timestamp")),
            rs.getString("notes")
        );
        s.setId             (rs.getLong  ("id"));
        s.setCalculatedLoad (rs.getDouble("calculated_load"));
        return s;
    }
}
