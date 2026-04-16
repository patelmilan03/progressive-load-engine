package com.analytics.engine.model;

import java.time.LocalDateTime;

/**
 * Represents a personal best performance record for a single exercise.
 *
 * <h3>Estimated 1 Rep Max (e1RM)</h3>
 * <p>Rather than requiring an athlete to perform a dangerous true 1RM test,
 * this engine uses the <strong>Epley formula</strong> to estimate it from
 * any sub-maximal set:
 *
 * <pre>  e1RM = weight × (1 + reps / 30)</pre>
 *
 * <p>This is the most widely validated formula for compound barbell lifts
 * (Mayhew et al., 1992).  It slightly overestimates at very high rep counts
 * (> 15), so the engine clamps rep count to 12 for estimation.
 *
 * <h3>Wilks Score</h3>
 * <p>The Wilks coefficient normalises the e1RM against the athlete's body
 * weight, enabling cross-athlete strength comparison:
 *
 * <pre>
 *  Wilks = e1RM × 500
 *          ──────────────────────────────────────────────
 *          a + b·bw + c·bw² + d·bw³ + e·bw⁴ + f·bw⁵
 *
 *  (Coefficients for males from Wilks 1998)
 * </pre>
 */
public final class PersonalRecord {

    // Wilks formula coefficients (male — update to female set for female athletes)
    private static final double A = -216.0475144;
    private static final double B =  16.2606339;
    private static final double C =  -0.002388645;
    private static final double D =  -0.00113732;
    private static final double E =   7.01863e-6;
    private static final double F =  -1.291e-8;

    // ----------------------------------------------------------------- Fields

    private long          id;                // DB primary key — set by DAO
    private final long    userId;
    private final String  exerciseName;
    private final double  weightKg;          // bar weight that achieved the record
    private final int     reps;              // reps performed at that weight
    private final double  rpe;              // RPE of the record set
    private final double  estimated1RmKg;   // Epley e1RM
    private       double  wilksScore;       // Wilks-normalised score (mutable for DAO restore)
    private final LocalDateTime achievedAt;
    private final MuscleGroup primaryMuscle;

    // ----------------------------------------------------------------- Constructor (package-visible — built by PersonalRecordTracker)

    public PersonalRecord(long userId, String exerciseName, double weightKg,
                          int reps, double rpe, LocalDateTime achievedAt,
                          MuscleGroup primaryMuscle, double bodyWeightKg) {
        this.userId        = userId;
        this.exerciseName  = exerciseName;
        this.weightKg      = weightKg;
        this.reps          = reps;
        this.rpe           = rpe;
        this.achievedAt    = achievedAt;
        this.primaryMuscle = primaryMuscle;
        this.estimated1RmKg = epley(weightKg, reps);
        this.wilksScore     = wilks(this.estimated1RmKg, bodyWeightKg);
    }

    // ----------------------------------------------------------------- Epley Formula

    /**
     * Epley e1RM: {@code weight × (1 + min(reps, 12) / 30)}.
     * Clamping at 12 reps prevents over-estimation at high rep counts.
     */
    public static double epley(double weightKg, int reps) {
        return weightKg * (1.0 + Math.min(reps, 12) / 30.0);
    }

    // ----------------------------------------------------------------- Wilks Score

    /**
     * Wilks coefficient formula (1998, male coefficients).
     *
     * @param liftKg     the 1RM or estimated 1RM in kg
     * @param bodyWeight athlete body weight in kg
     * @return Wilks score (unitless; typically 200–500 for recreational–elite)
     */
    public static double wilks(double liftKg, double bodyWeight) {
        double bw = bodyWeight;
        double denom = A + B*bw + C*bw*bw + D*bw*bw*bw
                     + E*bw*bw*bw*bw + F*bw*bw*bw*bw*bw;
        if (Math.abs(denom) < 1e-10) return 0;
        return liftKg * (500.0 / denom);
    }

    /**
     * Relative strength ratio: e1RM as a multiple of body weight.
     * e.g. 1.5 = "bench-presses 1.5× body weight".
     */
    public double relativeStrength(double bodyWeightKg) {
        return bodyWeightKg > 0 ? estimated1RmKg / bodyWeightKg : 0;
    }

    /**
     * Strength standard label based on relative strength ratio for this
     * muscle group (conservative estimates for compound lifts).
     */
    public String getStrengthStandard(double bodyWeightKg) {
        double rs = relativeStrength(bodyWeightKg);
        // Thresholds calibrated for major compound lifts
        return switch (primaryMuscle) {
            case CHEST -> rs < 0.75 ? "Beginner"
                        : rs < 1.25 ? "Intermediate"
                        : rs < 1.75 ? "Advanced"
                        : "Elite";
            case BACK ->  rs < 1.00 ? "Beginner"
                        : rs < 1.50 ? "Intermediate"
                        : rs < 2.00 ? "Advanced"
                        : "Elite";
            case QUADS -> rs < 1.25 ? "Beginner"
                        : rs < 1.75 ? "Intermediate"
                        : rs < 2.25 ? "Advanced"
                        : "Elite";
            default    -> rs < 0.50 ? "Beginner"
                        : rs < 1.00 ? "Intermediate"
                        : rs < 1.50 ? "Advanced"
                        : "Elite";
        };
    }

    // ----------------------------------------------------------------- Getters

    public long         getId()             { return id; }
    public long         getUserId()         { return userId; }
    public String       getExerciseName()   { return exerciseName; }
    public double       getWeightKg()       { return weightKg; }
    public int          getReps()           { return reps; }
    public double       getRpe()            { return rpe; }
    public double       getEstimated1RmKg() { return estimated1RmKg; }
    public double       getWilksScore()     { return wilksScore; }
    public LocalDateTime getAchievedAt()   { return achievedAt; }
    public MuscleGroup  getPrimaryMuscle()  { return primaryMuscle; }

    public void setId(long id) { this.id = id; }

    /** Called by the DAO to restore the persisted Wilks score (avoids re-computation with missing body weight). */
    public void setWilksScore(double wilksScore) { this.wilksScore = wilksScore; }

    @Override
    public String toString() {
        return String.format(
            "PR[%-28s | %5.1f kg × %2d reps | e1RM: %5.1f kg | Wilks: %5.1f | %s]",
            exerciseName, weightKg, reps, estimated1RmKg, wilksScore,
            achievedAt.toLocalDate()
        );
    }
}
