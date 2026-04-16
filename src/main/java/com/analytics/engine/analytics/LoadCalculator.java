package com.analytics.engine.analytics;

import com.analytics.engine.model.*;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Stateless utility class that computes, normalises, and aggregates load
 * scores from {@link WorkoutSession} objects.
 *
 * <h3>Responsibility</h3>
 * <p>The {@link RecoveryCalculator} delegates <em>raw metric computation</em>
 * to this class so that load-formula changes only touch one place.
 * This class never reads from the database; it operates on in-memory objects.
 *
 * <h3>Load Score Definitions</h3>
 * <pre>
 *  Hypertrophy:
 *    Raw VL          = Σ (sets × reps × weight)   per exercise
 *    RPE-adj VL      = Raw VL × (rpe/10)^1.5
 *    Session Load    = Σ RPE-adj VL / body_weight_kg × relativeLoadCoeff × volumeTolerance
 *
 *  Cardio (TRIMP):
 *    %HRR            = (avgHR − restHR) / (maxHR − restHR)
 *    TRIMP           = durationMin × %HRR × 0.64 × e^(1.92 × %HRR)
 *    Session Load    = TRIMP / 100
 * </pre>
 */
public final class LoadCalculator {

    // ----------------------------------------------------------------- FATIGABILITY THRESHOLDS
    // The maximum "tolerable" raw load per muscle group before fatigue hits 100 %.
    // Based on typical weekly volume landmarks from Israetel et al. (2019),
    // scaled to a single session.

    private static final Map<MuscleGroup, Double> FATIGUE_THRESHOLD_PER_MUSCLE;
    static {
        FATIGUE_THRESHOLD_PER_MUSCLE = new EnumMap<>(MuscleGroup.class);
        FATIGUE_THRESHOLD_PER_MUSCLE.put(MuscleGroup.CHEST,      3_200.0);
        FATIGUE_THRESHOLD_PER_MUSCLE.put(MuscleGroup.SHOULDERS,  2_000.0);
        FATIGUE_THRESHOLD_PER_MUSCLE.put(MuscleGroup.TRICEPS,    1_600.0);
        FATIGUE_THRESHOLD_PER_MUSCLE.put(MuscleGroup.BACK,       5_000.0);
        FATIGUE_THRESHOLD_PER_MUSCLE.put(MuscleGroup.BICEPS,     1_800.0);
        FATIGUE_THRESHOLD_PER_MUSCLE.put(MuscleGroup.QUADS,      6_000.0);
        FATIGUE_THRESHOLD_PER_MUSCLE.put(MuscleGroup.HAMSTRINGS, 4_500.0);
        FATIGUE_THRESHOLD_PER_MUSCLE.put(MuscleGroup.GLUTES,     5_000.0);
        FATIGUE_THRESHOLD_PER_MUSCLE.put(MuscleGroup.CALVES,     2_000.0);
        FATIGUE_THRESHOLD_PER_MUSCLE.put(MuscleGroup.CORE,       1_500.0);
    }

    /** Maximum tolerable cardio TRIMP / 100 before cardiovascular fatigue = 100 %. */
    private static final double CARDIO_FATIGUE_THRESHOLD = 2.0;

    // Private constructor — this is a utility class, not meant to be instantiated
    private LoadCalculator() {}

    // ================================================================= PUBLIC API

    /**
     * Computes and caches the session load for any session type.
     * Delegates to the session's own {@code calculateLoad(user)} Template Method.
     *
     * @param session any concrete WorkoutSession subtype
     * @param user    the owning athlete's profile
     * @return dimensionless load score ≥ 0
     */
    public static double computeSessionLoad(WorkoutSession session, UserProfile user) {
        return session.calculateLoad(user);
    }

    /**
     * For a {@link HypertrophySession}, returns the per-muscle fatigue
     * contribution as a percentage of each muscle group's max tolerable load.
     *
     * <p>Values > 100 % indicate the athlete exceeded the single-session
     * maximum volume for that muscle group.
     *
     * @return map of MuscleGroup → fatigue percentage (0–100+)
     */
    public static Map<MuscleGroup, Double> muscleFatiguePercent(
            HypertrophySession session, UserProfile user) {

        Map<MuscleGroup, Double> rawLoadMap = session.getLoadPerMuscleGroup();
        Map<MuscleGroup, Double> result     = new EnumMap<>(MuscleGroup.class);

        for (Map.Entry<MuscleGroup, Double> entry : rawLoadMap.entrySet()) {
            MuscleGroup mg        = entry.getKey();
            double      rawLoad   = entry.getValue();
            double      threshold = FATIGUE_THRESHOLD_PER_MUSCLE
                                        .getOrDefault(mg, 3_000.0);
            // Scale by athlete weight (heavier athlete tolerates more absolute volume)
            double adjustedThresh = threshold * (user.getWeightKg() / 66.0);
            double pct            = (rawLoad / adjustedThresh) * 100.0;
            result.put(mg, pct);
        }
        return result;
    }

    /**
     * Returns the cardiovascular fatigue as a percentage (0–100+) for a
     * {@link CardioSession}.
     */
    public static double cardioFatiguePercent(CardioSession session, UserProfile user) {
        double load = session.calculateLoad(user);
        return (load / CARDIO_FATIGUE_THRESHOLD) * 100.0;
    }

    /**
     * Computes the sum of all session loads in a list.
     * Useful for 7-day or 4-week chronic load windows.
     */
    public static double totalLoad(List<? extends WorkoutSession> sessions,
                                   UserProfile user) {
        return sessions.stream()
            .mapToDouble(s -> computeSessionLoad(s, user))
            .sum();
    }

    /**
     * Fetigue threshold for a given muscle group, adjusted for athlete body weight.
     * Exposed for use by {@link RecoveryCalculator}.
     */
    public static double adjustedFatigueThreshold(MuscleGroup mg, UserProfile user) {
        double base = FATIGUE_THRESHOLD_PER_MUSCLE.getOrDefault(mg, 3_000.0);
        return base * (user.getWeightKg() / 66.0);
    }

    public static double cardioFatigueThreshold() {
        return CARDIO_FATIGUE_THRESHOLD;
    }
}
