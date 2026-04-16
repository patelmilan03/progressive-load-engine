package com.analytics.engine.dao;

import com.analytics.engine.model.WorkoutSession;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Generic DAO (Data Access Object) contract for {@link WorkoutSession} subtypes.
 *
 * <h3>DAO Pattern</h3>
 * <p>The DAO pattern separates persistence logic from domain logic.
 * The domain model classes ({@link com.analytics.engine.model.HypertrophySession},
 * {@link com.analytics.engine.model.CardioSession}) know nothing about SQL;
 * all JDBC work lives exclusively in the concrete DAO implementations.
 *
 * <h3>Type Parameter</h3>
 * <p>{@code T} is bounded to {@code WorkoutSession} so the compiler ensures
 * type safety while allowing each DAO to operate on its specific subtype.
 *
 * @param <T> the concrete session type this DAO manages
 */
public interface WorkoutSessionDAO<T extends WorkoutSession> {

    /**
     * Persists a new session.  After a successful call the {@code session}'s
     * generated database ID is set via {@link WorkoutSession#setId(long)}.
     *
     * @param session the session to persist (must not already have a DB id)
     * @throws SQLException on any JDBC error
     */
    void save(T session) throws SQLException;

    /**
     * Retrieves a session by its primary key.
     *
     * @param id database primary key
     * @return an {@link Optional} containing the session, or empty if not found
     * @throws SQLException on any JDBC error
     */
    Optional<T> findById(long id) throws SQLException;

    /**
     * Retrieves all sessions belonging to a given user, ordered by
     * session timestamp descending (most recent first).
     *
     * @param userId the owning athlete's id
     * @throws SQLException on any JDBC error
     */
    List<T> findByUserId(long userId) throws SQLException;

    /**
     * Retrieves all sessions that started within the last {@code hours} hours,
     * ordered by session timestamp descending.
     *
     * <p>This is the primary query used by the 48-hour recovery window.
     *
     * @param userId the owning athlete's id
     * @param hours  look-back window (typically 48)
     * @throws SQLException on any JDBC error
     */
    List<T> findSessionsInLastNHours(long userId, int hours) throws SQLException;

    /**
     * Updates the {@code notes} and {@code calculated_load} fields of an
     * existing session.
     *
     * @param session session with mutated fields (must have a valid id)
     * @throws SQLException on any JDBC error
     */
    void update(T session) throws SQLException;

    /**
     * Deletes a session and its child records (exercise sets / cardio data)
     * by cascading foreign-key delete.
     *
     * @param id database primary key of the session to remove
     * @throws SQLException on any JDBC error
     */
    void delete(long id) throws SQLException;
}
