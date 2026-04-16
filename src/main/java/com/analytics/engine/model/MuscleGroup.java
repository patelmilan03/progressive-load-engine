package com.analytics.engine.model;

/**
 * Enumerates every skeletal muscle group tracked by the engine, together with
 * the two physiological constants that drive the recovery model.
 *
 * <h3>Recovery Half-Life (t½)</h3>
 * <p>The number of hours after which approximately 50 % of the initial fatigue
 * has dissipated. Values are calibrated from sports-science literature on
 * muscle protein synthesis / motor-unit recovery windows:
 * <ul>
 *   <li>Small, single-joint muscles (e.g. calves, triceps) — 24–36 h</li>
 *   <li>Large compound muscles (e.g. quads, back)          — 60–72 h</li>
 * </ul>
 *
 * <h3>Fatigue Weight Factor</h3>
 * <p>A dimensionless coefficient (0–1) that scales how heavily that muscle
 * group's residual fatigue penalises the composite Readiness Score.  Larger
 * muscles with greater systemic metabolic impact (quads, back) carry a
 * higher weight.
 */
public enum MuscleGroup {

    //               t½ (h)   fatigue weight
    CHEST           (48.0,    0.18),
    SHOULDERS       (36.0,    0.13),
    TRICEPS         (36.0,    0.09),
    BACK            (60.0,    0.24),
    BICEPS          (36.0,    0.09),
    QUADS           (72.0,    0.28),
    HAMSTRINGS      (72.0,    0.24),
    GLUTES          (60.0,    0.20),
    CALVES          (24.0,    0.08),
    CORE            (24.0,    0.07);

    // -----------------------------------------------------------------

    /** Hours until ~50 % of accumulated fatigue dissipates (exponential decay). */
    private final double recoveryHalfLifeHours;

    /**
     * Dimensionless weight applied when converting this group's residual fatigue
     * into a penalty on the Readiness Score.
     */
    private final double fatigueWeightFactor;

    MuscleGroup(double recoveryHalfLifeHours, double fatigueWeightFactor) {
        this.recoveryHalfLifeHours = recoveryHalfLifeHours;
        this.fatigueWeightFactor   = fatigueWeightFactor;
    }

    // -----------------------------------------------------------------  Getters

    public double getRecoveryHalfLifeHours() { return recoveryHalfLifeHours; }
    public double getFatigueWeightFactor()   { return fatigueWeightFactor;   }

    // -----------------------------------------------------------------  Helpers

    /**
     * Returns the decay constant λ = ln(2) / t½ used in the
     * exponential decay formula:  F(t) = F₀ · e^(−λt)
     */
    public double getDecayConstant() {
        return Math.log(2) / recoveryHalfLifeHours;
    }

    /**
     * Convenience: which PPL day primarily trains this muscle group?
     */
    public WorkoutType getPrimaryWorkoutDay() {
        return switch (this) {
            case CHEST, SHOULDERS, TRICEPS -> WorkoutType.PUSH;
            case BACK, BICEPS              -> WorkoutType.PULL;
            case QUADS, HAMSTRINGS,
                 GLUTES, CALVES            -> WorkoutType.LEGS;
            case CORE                      -> WorkoutType.LEGS;   // typically trained on leg day
        };
    }
}
