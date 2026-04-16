package com.analytics.engine.dao;

import com.analytics.engine.model.UserProfile;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Concrete DAO for persisting and retrieving {@link UserProfile} records.
 *
 * <p>Uses standard JDBC with {@link PreparedStatement} throughout to prevent
 * SQL injection and improve performance via statement plan caching.
 */
public final class UserProfileDAO {

    private final DatabaseManager db;

    public UserProfileDAO(DatabaseManager db) {
        this.db = db;
    }

    // ----------------------------------------------------------------- INSERT

    /**
     * Persists a new user and sets the generated database id on the object.
     */
    public void save(UserProfile user) throws SQLException {
        final String sql = """
            INSERT INTO users
              (name, height_cm, weight_kg, age, resting_heart_rate,
               max_heart_rate, training_experience_years)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {

            ps.setString(1, user.getName());
            ps.setDouble(2, user.getHeightCm());
            ps.setDouble(3, user.getWeightKg());
            ps.setInt   (4, user.getAge());
            ps.setInt   (5, user.getRestingHeartRate());
            ps.setInt   (6, user.getMaxHeartRate());
            ps.setDouble(7, user.getTrainingExperienceYears());

            ps.executeUpdate();

            // Use SQLite-specific last_insert_rowid() — more portable across JDBC driver versions
            try (Statement st = db.getConnection().createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) user.setId(rs.getLong(1));
            }
        }
    }

    // ----------------------------------------------------------------- SELECT

    public Optional<UserProfile> findById(long id) throws SQLException {
        final String sql = "SELECT * FROM users WHERE id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public Optional<UserProfile> findByName(String name) throws SQLException {
        final String sql = "SELECT * FROM users WHERE name = ? LIMIT 1";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    public List<UserProfile> findAll() throws SQLException {
        List<UserProfile> list = new ArrayList<>();
        try (Statement st  = db.getConnection().createStatement();
             ResultSet rs  = st.executeQuery("SELECT * FROM users ORDER BY id")) {
            while (rs.next()) list.add(mapRow(rs));
        }
        return list;
    }

    // ----------------------------------------------------------------- UPDATE

    public void update(UserProfile user) throws SQLException {
        final String sql = """
            UPDATE users SET
              height_cm = ?, weight_kg = ?, age = ?,
              resting_heart_rate = ?, max_heart_rate = ?,
              training_experience_years = ?
            WHERE id = ?
            """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setDouble(1, user.getHeightCm());
            ps.setDouble(2, user.getWeightKg());
            ps.setInt   (3, user.getAge());
            ps.setInt   (4, user.getRestingHeartRate());
            ps.setInt   (5, user.getMaxHeartRate());
            ps.setDouble(6, user.getTrainingExperienceYears());
            ps.setLong  (7, user.getId());
            ps.executeUpdate();
        }
    }

    // ----------------------------------------------------------------- DELETE

    public void delete(long id) throws SQLException {
        try (PreparedStatement ps = db.getConnection()
                .prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    // ----------------------------------------------------------------- Mapping

    private UserProfile mapRow(ResultSet rs) throws SQLException {
        UserProfile up = UserProfile.builder(rs.getString("name"))
            .heightCm              (rs.getDouble("height_cm"))
            .weightKg              (rs.getDouble("weight_kg"))
            .age                   (rs.getInt   ("age"))
            .restingHeartRate      (rs.getInt   ("resting_heart_rate"))
            .maxHeartRate          (rs.getInt   ("max_heart_rate"))
            .trainingExperienceYears(rs.getDouble("training_experience_years"))
            .build();
        up.setId(rs.getLong("id"));
        return up;
    }
}
