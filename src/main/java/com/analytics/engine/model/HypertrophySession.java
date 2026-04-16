package com.analytics.engine.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A resistance-training (hypertrophy) session — one of the three PPL days.
 *
 * <h3>Load Algorithm: RPE-Weighted, Bodyweight-Normalised Volume Load</h3>
 * <pre>
 *  Per exercise:
 *    raw_VL        = sets × reps × weightKg
 *    adj_VL        = raw_VL × (rpe / 10)^1.5           (RPE curve amplification)
 *
 *  Session aggregate:
 *    total_adj_VL  = Σ adj_VL  (across all exercises)
 *    norm_load     = total_adj_VL / user.weightKg       (relative to body mass)
 *    final_load    = norm_load × user.getRelativeLoadCoefficient()
 *                              × user.getVolumeToleranceFactor()
 * </pre>
 *
 * <p>Normalising by body weight means a 66 kg athlete doing 80 kg Bench Press
 * registers higher relative load than a 90 kg athlete doing the same bar weight.
 *
 * <h3>Muscle-Group Fatigue Map</h3>
 * <p>The engine tracks per-muscle-group load so the
 * {@code RecoveryCalculator} can issue targeted recommendations
 * (e.g. "Deload quads today" rather than just "Reduce volume").
 */
public final class HypertrophySession extends WorkoutSession {

    // ----------------------------------------------------------------- State

    /** The PPL day classification — must be PUSH, PULL, or LEGS. */
    private final WorkoutType pplDay;

    /** Primary muscle group label stored in the DB for quick retrieval. */
    private final MuscleGroup primaryMuscleGroup;

    /** All exercise entries logged in this session. */
    private final List<ExerciseSet> exercises;

    // ----------------------------------------------------------------- Constructor

    /**
     * @param userId            the owning athlete
     * @param pplDay            must be {@link WorkoutType#PUSH}, PULL, or LEGS
     * @param primaryMuscleGroup the dominant muscle group (e.g. CHEST for a Push day)
     * @param sessionTimestamp  when the session started
     * @param notes             optional free-text note
     */
    public HypertrophySession(long userId, WorkoutType pplDay,
                              MuscleGroup primaryMuscleGroup,
                              LocalDateTime sessionTimestamp,
                              String notes) {
        super(userId, pplDay, sessionTimestamp, notes);

        if (!pplDay.isStrengthSession()) {
            throw new IllegalArgumentException(
                "HypertrophySession requires PUSH, PULL, or LEGS — got: " + pplDay);
        }
        this.pplDay             = pplDay;
        this.primaryMuscleGroup = primaryMuscleGroup;
        this.exercises          = new ArrayList<>();
    }

    // ----------------------------------------------------------------- Exercise Management

    /**
     * Adds an exercise entry to this session and invalidates the cached load.
     */
    public void addExercise(ExerciseSet set) {
        exercises.add(set);
        calculatedLoad = 0.0;  // force recalculation on next call
    }

    // ================================================================= Load Algorithm

    /**
     * Implements the <em>RPE-Weighted, Bodyweight-Normalised Volume Load</em> formula.
     */
    @Override
    protected double computeRawLoad(UserProfile user) {
        if (exercises.isEmpty()) return 0.0;

        double totalAdjustedVL = exercises.stream()
            .mapToDouble(ExerciseSet::getRpeAdjustedVolumeLoad)
            .sum();

        // Normalise by athlete body mass; scale by experience tolerance
        double normLoad = totalAdjustedVL / user.getWeightKg();
        return normLoad * user.getRelativeLoadCoefficient()
                       * user.getVolumeToleranceFactor();
    }

    // ----------------------------------------------------------------- Muscle Breakdown

    /**
     * Returns a map of { MuscleGroup → total RPE-adjusted volume load }
     * for every muscle group targeted in this session.
     *
     * <p>Used by {@code RecoveryCalculator} to track per-muscle fatigue.
     */
    public Map<MuscleGroup, Double> getLoadPerMuscleGroup() {
        Map<MuscleGroup, Double> map = new EnumMap<>(MuscleGroup.class);
        for (ExerciseSet ex : exercises) {
            map.merge(ex.getPrimaryMuscle(), ex.getRpeAdjustedVolumeLoad(), Double::sum);
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Total raw (unadjusted) training volume in kg·reps lifted this session.
     */
    public double getTotalRawVolume() {
        return exercises.stream().mapToDouble(ExerciseSet::getRawVolumeLoad).sum();
    }

    /**
     * Total number of working sets across all exercises.
     */
    public int getTotalSets() {
        return exercises.stream().mapToInt(ExerciseSet::getSetCount).sum();
    }

    // ================================================================= Overrides

    @Override
    public String getMuscleGroupsSummary() {
        return exercises.stream()
            .map(e -> e.getPrimaryMuscle().name())
            .distinct()
            .collect(Collectors.joining(", "));
    }

    // ----------------------------------------------------------------- Getters

    public WorkoutType     getPplDay()             { return pplDay; }
    public MuscleGroup     getPrimaryMuscleGroup() { return primaryMuscleGroup; }
    public List<ExerciseSet> getExercises()        { return Collections.unmodifiableList(exercises); }

    @Override
    public String toString() {
        return String.format(
            "HypertrophySession{%s | %d exercises | %d sets | rawVol=%.0f kg | adjLoad=%.2f | %s}",
            pplDay.getDisplayName(), exercises.size(), getTotalSets(),
            getTotalRawVolume(), calculatedLoad,
            sessionTimestamp.toLocalDate()
        );
    }
}
