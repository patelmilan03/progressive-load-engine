package com.analytics.engine.model;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Abstract base class for every training session in the engine.
 *
 * <h3>OOP Design Rationale</h3>
 * <p>An <em>abstract class</em> (rather than an interface) is chosen because:
 * <ol>
 *   <li>Sessions share concrete identity state (id, userId, timestamp, notes)
 *       that should not be duplicated in every concrete class.</li>
 *   <li>The <strong>Template Method pattern</strong> is applied via
 *       {@link #calculateLoad(UserProfile)}: subclasses supply the algorithm
 *       fragment while the base class owns the orchestration skeleton.</li>
 *   <li>An interface {@code Loadable} (or similar) would force every
 *       implementor to re-declare the shared fields, violating DRY.</li>
 * </ol>
 *
 * <h3>Inheritance Hierarchy</h3>
 * <pre>
 *   WorkoutSession  (abstract)
 *   ├── HypertrophySession  (PPL strength days)
 *   └── CardioSession       (5K running days)
 * </pre>
 *
 * <h3>Template Method — calculateLoad</h3>
 * <p>Subclasses implement the abstract {@link #computeRawLoad(UserProfile)} hook.
 * This base class wraps it with input validation and caches the result in
 * {@code calculatedLoad}.
 */
public abstract class WorkoutSession {

    // ----------------------------------------------------------------- Shared State

    protected long          id;
    protected final long    userId;
    protected final WorkoutType workoutType;
    protected final LocalDateTime sessionTimestamp;
    protected       String  notes;
    protected       double  calculatedLoad;   // cached after first calculation

    // ----------------------------------------------------------------- Constructor

    protected WorkoutSession(long userId, WorkoutType workoutType,
                             LocalDateTime sessionTimestamp, String notes) {
        this.userId           = userId;
        this.workoutType      = Objects.requireNonNull(workoutType,   "workoutType required");
        this.sessionTimestamp = Objects.requireNonNull(sessionTimestamp, "timestamp required");
        this.notes            = notes;
    }

    // ================================================================= Abstract Contract

    /**
     * Hook method: each concrete session type implements its own load formula.
     *
     * <p><strong>Called by</strong> the Template Method {@link #calculateLoad(UserProfile)}.
     * Subclasses must not validate {@code user} — that is the base class's concern.
     *
     * @param user the athlete profile supplying weight, HR, and experience data
     * @return a dimensionless load score (arbitrary units; larger = more stressful)
     */
    protected abstract double computeRawLoad(UserProfile user);

    /**
     * Returns a concise text summary of the primary muscle groups or
     * physiological systems targeted by this session.
     */
    public abstract String getMuscleGroupsSummary();

    // ================================================================= Template Method

    /**
     * <strong>Template Method</strong> — validates input, delegates to
     * {@link #computeRawLoad(UserProfile)}, caches and returns the result.
     *
     * @param user athlete profile (must not be null)
     * @return dimensionless load score ≥ 0
     * @throws IllegalArgumentException if user is null
     */
    public final double calculateLoad(UserProfile user) {
        Objects.requireNonNull(user, "UserProfile must not be null for load calculation");
        if (calculatedLoad == 0.0) {
            calculatedLoad = Math.max(0.0, computeRawLoad(user));
        }
        return calculatedLoad;
    }

    /**
     * Force recalculation (e.g. after adding more exercise sets).
     */
    public final double recalculateLoad(UserProfile user) {
        calculatedLoad = 0.0;
        return calculateLoad(user);
    }

    // ----------------------------------------------------------------- Getters / Setters

    public long          getId()               { return id; }
    public long          getUserId()           { return userId; }
    public WorkoutType   getWorkoutType()      { return workoutType; }
    public LocalDateTime getSessionTimestamp() { return sessionTimestamp; }
    public String        getNotes()            { return notes; }
    public double        getCalculatedLoad()   { return calculatedLoad; }

    public void setId(long id)                         { this.id = id; }
    public void setNotes(String notes)                 { this.notes = notes; }
    public void setCalculatedLoad(double load)         { this.calculatedLoad = load; }

    // ----------------------------------------------------------------- Utility

    /**
     * Hours elapsed since this session's timestamp (relative to 'now').
     */
    public double hoursAgo() {
        return java.time.temporal.ChronoUnit.MINUTES
                   .between(sessionTimestamp, LocalDateTime.now()) / 60.0;
    }

    @Override
    public String toString() {
        return String.format("[%s | %s | load=%.1f | %s ago]",
            workoutType.getDisplayName(),
            sessionTimestamp,
            calculatedLoad,
            formatHoursAgo(hoursAgo())
        );
    }

    private static String formatHoursAgo(double h) {
        if (h < 1) return String.format("%.0f min", h * 60);
        return String.format("%.1f h", h);
    }
}
