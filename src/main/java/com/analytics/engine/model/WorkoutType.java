package com.analytics.engine.model;

/**
 * Represents every type of session in a PPL + 5K hybrid training week.
 *
 * <p>A canonical 6-day PPL schedule interspersed with 5K runs might look like:
 * <pre>
 *   Mon – PUSH    Tue – CARDIO_5K    Wed – PULL
 *   Thu – LEGS    Fri – CARDIO_5K    Sat – PUSH    Sun – REST
 * </pre>
 * This enum is the central discriminator stored in the {@code workout_sessions} table.
 */
public enum WorkoutType {

    /** Chest, anterior deltoids, triceps — horizontal/vertical push patterns. */
    PUSH("Push Day", "Chest · Shoulders · Triceps", true),

    /** Lats, rhomboids, rear delts, biceps — horizontal/vertical pull patterns. */
    PULL("Pull Day", "Back · Biceps · Rear Delts", true),

    /** Quads, hamstrings, glutes, calves — lower body compound work. */
    LEGS("Legs Day", "Quads · Hamstrings · Glutes · Calves", true),

    /** Outdoor or treadmill 5 km run session. */
    CARDIO_5K("5K Run", "Full cardiovascular system", false),

    /** Deliberate rest or very light active recovery (walking, stretching). */
    REST("Rest / Active Recovery", "Systemic recovery", false);

    // -----------------------------------------------------------------

    private final String displayName;
    private final String musclesSummary;
    private final boolean isStrengthSession;

    WorkoutType(String displayName, String musclesSummary, boolean isStrengthSession) {
        this.displayName       = displayName;
        this.musclesSummary    = musclesSummary;
        this.isStrengthSession = isStrengthSession;
    }

    /** Human-readable short label (used in CLI output). */
    public String getDisplayName()    { return displayName; }

    /** Comma-separated muscle group summary for display purposes. */
    public String getMusclesSummary() { return musclesSummary; }

    /** {@code true} if this session type contributes to mechanical muscle fatigue. */
    public boolean isStrengthSession() { return isStrengthSession; }

    /** {@code true} if this session type contributes to cardiovascular fatigue. */
    public boolean isCardioSession()   { return this == CARDIO_5K; }
}
