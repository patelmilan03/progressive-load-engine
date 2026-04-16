package com.analytics.engine.dao;

import com.analytics.engine.model.CardioSession;
import com.analytics.engine.model.WorkoutType;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Concrete DAO for {@link CardioSession} objects.
 *
 * <h3>Multi-Table Persistence</h3>
 * <p>Each {@code CardioSession} spans:
 * <ol>
 *   <li>{@code workout_sessions} — parent row</li>
 *   <li>{@code cardio_sessions}  — 1-to-1 child with run-specific metrics</li>
 * </ol>
 * Both inserts are executed within a single transaction.
 */
public final class CardioSessionDAO implements WorkoutSessionDAO<CardioSession> {

    private final DatabaseManager db;

    public CardioSessionDAO(DatabaseManager db) {
        this.db = db;
    }

    // ================================================================= WRITE

    @Override
    public void save(CardioSession session) throws SQLException {
        db.beginTransaction();
        try {
            long parentId = insertParent(session);
            session.setId(parentId);
            insertCardioChild(session, parentId);
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        }
    }

    private long insertParent(CardioSession s) throws SQLException {
        final String sql = """
            INSERT INTO workout_sessions
              (user_id, workout_type, session_timestamp, notes, calculated_load)
            VALUES (?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong  (1, s.getUserId());
            ps.setString(2, WorkoutType.CARDIO_5K.name());
            ps.setString(3, s.getSessionTimestamp().toString());
            ps.setString(4, s.getNotes());
            ps.setDouble(5, s.getCalculatedLoad());
            ps.executeUpdate();
            try (Statement st = db.getConnection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to retrieve generated id for cardio workout_session.");
    }

    private void insertCardioChild(CardioSession s, long parentId) throws SQLException {
        final String sql = """
            INSERT INTO cardio_sessions
              (session_id, distance_km, pace_mins_per_km, avg_heart_rate,
               max_heart_rate_hit, duration_minutes)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong  (1, parentId);
            ps.setDouble(2, s.getDistanceKm());
            ps.setDouble(3, s.getPaceMinsPerKm());
            ps.setInt   (4, s.getAvgHeartRate());
            ps.setInt   (5, s.getMaxHeartRateHit());
            ps.setDouble(6, s.getDurationMinutes());
            ps.executeUpdate();
        }
    }

    // ================================================================= READ

    @Override
    public Optional<CardioSession> findById(long id) throws SQLException {
        final String sql = buildJoinSql("ws.id = ?");
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<CardioSession> findByUserId(long userId) throws SQLException {
        return queryBy(buildJoinSql("ws.user_id = ?"), userId);
    }

    @Override
    public List<CardioSession> findSessionsInLastNHours(long userId,
                                                        int hours) throws SQLException {
        final String sql = """
            SELECT ws.*, cs.distance_km, cs.pace_mins_per_km, cs.avg_heart_rate,
                   cs.max_heart_rate_hit, cs.duration_minutes
              FROM workout_sessions ws
              JOIN cardio_sessions cs ON ws.id = cs.session_id
             WHERE ws.user_id = ?
               AND ws.session_timestamp >= datetime('now', ? || ' hours')
             ORDER BY ws.session_timestamp DESC
            """;
        List<CardioSession> list = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong  (1, userId);
            ps.setString(2, "-" + hours);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    private List<CardioSession> queryBy(String sql, long param) throws SQLException {
        List<CardioSession> list = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    private static String buildJoinSql(String whereClause) {
        return """
            SELECT ws.*, cs.distance_km, cs.pace_mins_per_km, cs.avg_heart_rate,
                   cs.max_heart_rate_hit, cs.duration_minutes
              FROM workout_sessions ws
              JOIN cardio_sessions cs ON ws.id = cs.session_id
             WHERE %s
             ORDER BY ws.session_timestamp DESC
            """.formatted(whereClause);
    }

    // ================================================================= UPDATE / DELETE

    @Override
    public void update(CardioSession session) throws SQLException {
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
        try (PreparedStatement ps = db.getConnection()
                .prepareStatement("DELETE FROM workout_sessions WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    // ================================================================= Mapping

    private CardioSession mapRow(ResultSet rs) throws SQLException {
        CardioSession s = new CardioSession(
            rs.getLong  ("user_id"),
            LocalDateTime.parse(rs.getString("session_timestamp")),
            rs.getDouble("distance_km"),
            rs.getDouble("pace_mins_per_km"),
            rs.getInt   ("avg_heart_rate"),
            rs.getInt   ("max_heart_rate_hit"),
            rs.getDouble("duration_minutes"),
            rs.getString("notes")
        );
        s.setId             (rs.getLong  ("id"));
        s.setCalculatedLoad (rs.getDouble("calculated_load"));
        return s;
    }
}
