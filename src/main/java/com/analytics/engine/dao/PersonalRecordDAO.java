package com.analytics.engine.dao;

import com.analytics.engine.model.MuscleGroup;
import com.analytics.engine.model.PersonalRecord;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * DAO for persisting and retrieving {@link PersonalRecord} objects.
 *
 * <h3>Key Queries</h3>
 * <ul>
 *   <li>{@link #findCurrentPR(long, String)} — returns the best e1RM for an
 *       exercise, used to determine if a new session set a new record.</li>
 *   <li>{@link #findAllByUser(long)} — full PR board for the user, grouped
 *       by exercise and ordered by e1RM descending.</li>
 * </ul>
 */
public final class PersonalRecordDAO {

    private final DatabaseManager db;

    public PersonalRecordDAO(DatabaseManager db) {
        this.db = db;
    }

    // ================================================================= WRITE

    /**
     * Saves a new PR record. Sets the generated id on the object.
     */
    public void save(PersonalRecord pr) throws SQLException {
        final String sql = """
            INSERT INTO personal_records
              (user_id, exercise_name, weight_kg, reps, rpe,
               estimated_1rm_kg, wilks_score, primary_muscle, achieved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong  (1, pr.getUserId());
            ps.setString(2, pr.getExerciseName());
            ps.setDouble(3, pr.getWeightKg());
            ps.setInt   (4, pr.getReps());
            ps.setDouble(5, pr.getRpe());
            ps.setDouble(6, pr.getEstimated1RmKg());
            ps.setDouble(7, pr.getWilksScore());
            ps.setString(8, pr.getPrimaryMuscle().name());
            ps.setString(9, pr.getAchievedAt().toString());
            ps.executeUpdate();

            try (Statement st = db.getConnection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) pr.setId(rs.getLong(1));
            }
        }
    }

    // ================================================================= READ

    /**
     * Finds the current best PR for a specific exercise (highest e1RM).
     *
     * @return an {@link Optional} containing the current PR, or empty if
     *         no record exists for this exercise yet.
     */
    public Optional<PersonalRecord> findCurrentPR(long userId,
                                                   String exerciseName) throws SQLException {
        final String sql = """
            SELECT * FROM personal_records
             WHERE user_id = ? AND exercise_name = ?
             ORDER BY estimated_1rm_kg DESC
             LIMIT 1
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong  (1, userId);
            ps.setString(2, exerciseName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all PRs for the user, ordered by estimated 1RM descending
     * (strongest lift first within each exercise).
     */
    public List<PersonalRecord> findAllByUser(long userId) throws SQLException {
        final String sql = """
            SELECT * FROM personal_records
             WHERE user_id = ?
             ORDER BY exercise_name ASC, estimated_1rm_kg DESC
            """;
        List<PersonalRecord> list = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    /**
     * Returns only the top (highest e1RM) record for each exercise —
     * i.e. the "current PR board".
     */
    public List<PersonalRecord> findCurrentPRBoard(long userId) throws SQLException {
        // SQLite doesn't support DISTINCT ON — use GROUP BY + MAX trick
        final String sql = """
            SELECT pr.*
              FROM personal_records pr
              JOIN (
                  SELECT exercise_name, MAX(estimated_1rm_kg) AS best
                    FROM personal_records
                   WHERE user_id = ?
                   GROUP BY exercise_name
              ) best ON pr.exercise_name = best.exercise_name
                    AND pr.estimated_1rm_kg = best.best
                    AND pr.user_id = ?
             ORDER BY pr.estimated_1rm_kg DESC
            """;
        List<PersonalRecord> list = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    /**
     * Returns PR history for a single exercise over time (oldest first),
     * useful for plotting strength progression.
     */
    public List<PersonalRecord> findProgressionForExercise(long userId,
                                                            String exerciseName) throws SQLException {
        final String sql = """
            SELECT * FROM personal_records
             WHERE user_id = ? AND exercise_name = ?
             ORDER BY achieved_at ASC
            """;
        List<PersonalRecord> list = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong  (1, userId);
            ps.setString(2, exerciseName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapRow(rs));
            }
        }
        return list;
    }

    // ================================================================= DELETE

    public void deleteAllForUser(long userId) throws SQLException {
        try (PreparedStatement ps = db.getConnection()
                .prepareStatement("DELETE FROM personal_records WHERE user_id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    // ================================================================= Mapping

    private PersonalRecord mapRow(ResultSet rs) throws SQLException {
        PersonalRecord pr = new PersonalRecord(
            rs.getLong  ("user_id"),
            rs.getString("exercise_name"),
            rs.getDouble("weight_kg"),
            rs.getInt   ("reps"),
            rs.getDouble("rpe"),
            LocalDateTime.parse(rs.getString("achieved_at")),
            MuscleGroup.valueOf(rs.getString("primary_muscle")),
            66.0   // placeholder — Wilks overridden below from stored value
        );
        pr.setId(rs.getLong("id"));
        // Restore the persisted Wilks score directly to avoid recalculation
        // with an unknown body weight (body weight is not stored in this table)
        pr.setWilksScore(rs.getDouble("wilks_score"));
        return pr;
    }
}
