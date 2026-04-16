package com.analytics.engine.model;

/**
 * Represents a single exercise entry within a {@link HypertrophySession}.
 *
 * <p>One {@code ExerciseSet} models a compound exercise prescription such as:
 * <pre>  Barbell Bench Press — 4 sets × 8 reps × 80 kg @ RPE 8</pre>
 *
 * <h3>Volume Load</h3>
 * <pre>  Raw Volume Load (VL)  =  setCount × reps × weightKg
 *  RPE-Adjusted VL       =  VL × (rpe / 10)^1.5</pre>
 *
 * <p>The RPE exponent (1.5) amplifies the effect of high-effort sets
 * non-linearly, mirroring real physiological stress response.
 */
public final class ExerciseSet {

    // ----------------------------------------------------------------- Fields

    private long         id;              // DB primary key — set by DAO
    private final long   sessionId;       // FK → workout_sessions.id
    private final String exerciseName;
    private final int    setCount;        // number of physical sets performed
    private final int    reps;            // reps per set
    private final double weightKg;        // load in kilograms
    private final double rpe;             // Rate of Perceived Exertion  (1–10 scale)
    private final MuscleGroup primaryMuscle;

    // ----------------------------------------------------------------- Constructor

    private ExerciseSet(Builder b) {
        this.sessionId     = b.sessionId;
        this.exerciseName  = b.exerciseName;
        this.setCount      = b.setCount;
        this.reps          = b.reps;
        this.weightKg      = b.weightKg;
        this.rpe           = b.rpe;
        this.primaryMuscle = b.primaryMuscle;
    }

    // ----------------------------------------------------------------- Derived Metrics

    /**
     * Raw (unadjusted) volume load = sets × reps × weight.
     */
    public double getRawVolumeLoad() {
        return (double) setCount * reps * weightKg;
    }

    /**
     * RPE-adjusted volume load.  Uses a power curve so that the difference
     * between RPE 8 and RPE 10 is proportionally larger than RPE 5 vs RPE 7.
     */
    public double getRpeAdjustedVolumeLoad() {
        double rpeFactor = Math.pow(rpe / 10.0, 1.5);
        return getRawVolumeLoad() * rpeFactor;
    }

    /**
     * Effective working sets: a high-RPE set counts as more than one "effort unit".
     * Used to estimate proximity-to-failure and accumulate fatigue more accurately.
     */
    public double getEffectiveSetEquivalents() {
        return setCount * (rpe / 10.0);
    }

    // ----------------------------------------------------------------- Getters

    public long        getId()            { return id; }
    public long        getSessionId()     { return sessionId; }
    public String      getExerciseName()  { return exerciseName; }
    public int         getSetCount()      { return setCount; }
    public int         getReps()          { return reps; }
    public double      getWeightKg()      { return weightKg; }
    public double      getRpe()           { return rpe; }
    public MuscleGroup getPrimaryMuscle() { return primaryMuscle; }

    public void setId(long id) { this.id = id; }

    // ----------------------------------------------------------------- toString

    @Override
    public String toString() {
        return String.format(
            "%-28s  %dx%-3d  @ %5.1f kg  RPE %.1f  [%-12s]  VL=%.0f",
            exerciseName, setCount, reps, weightKg, rpe,
            primaryMuscle.name(), getRpeAdjustedVolumeLoad()
        );
    }

    // ================================================================= Builder

    public static Builder builder(long sessionId, String exerciseName, MuscleGroup primaryMuscle) {
        return new Builder(sessionId, exerciseName, primaryMuscle);
    }

    public static final class Builder {

        private final long        sessionId;
        private final String      exerciseName;
        private final MuscleGroup primaryMuscle;
        private int    setCount = 3;
        private int    reps     = 8;
        private double weightKg = 0.0;
        private double rpe      = 7.0;

        private Builder(long sessionId, String exerciseName, MuscleGroup primaryMuscle) {
            this.sessionId     = sessionId;
            this.exerciseName  = exerciseName;
            this.primaryMuscle = primaryMuscle;
        }

        public Builder sets(int sets)          { this.setCount = sets;    return this; }
        public Builder reps(int reps)          { this.reps     = reps;    return this; }
        public Builder weightKg(double kg)     { this.weightKg = kg;      return this; }
        public Builder rpe(double rpe)         { this.rpe      = rpe;     return this; }

        public ExerciseSet build() {
            if (setCount <= 0)  throw new IllegalArgumentException("setCount must be > 0");
            if (reps <= 0)      throw new IllegalArgumentException("reps must be > 0");
            if (weightKg < 0)   throw new IllegalArgumentException("weightKg must be ≥ 0");
            if (rpe < 1 || rpe > 10) throw new IllegalArgumentException("RPE must be 1–10");
            return new ExerciseSet(this);
        }
    }
}
