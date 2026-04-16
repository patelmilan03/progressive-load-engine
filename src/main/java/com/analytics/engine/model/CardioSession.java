package com.analytics.engine.model;

import java.time.LocalDateTime;

/**
 * A cardiovascular running session — specifically a 5 km road/treadmill run.
 *
 * <h3>Load Algorithm: TRIMP (Training Impulse)</h3>
 * <p>TRIMP was introduced by Bannister (1991) as a physiologically-grounded
 * method to quantify cardiovascular training stress.  This implementation
 * uses the gender-neutral Karvonen variant:
 *
 * <pre>
 *  %HRR  = (avgHeartRate − restingHR) / (maxHR − restingHR)    [0–1]
 *  TRIMP = durationMin × %HRR × 0.64 × e^(1.92 × %HRR)
 * </pre>
 *
 * <p>The exponential term means that sessions at ≥85 % HRR accumulate fatigue
 * disproportionately faster — matching the real-world experience of high-intensity
 * intervals versus easy aerobic jogging.
 *
 * <h3>Normalisation</h3>
 * <p>Raw TRIMP is divided by 100 to produce a dimensionless "cardio load" on a
 * comparable scale to the hypertrophy volume-load scores used by the engine.
 */
public final class CardioSession extends WorkoutSession {

    // ----------------------------------------------------------------- State

    /** Distance covered (km). For a 5K this is nominally 5.0 km. */
    private final double distanceKm;

    /** Average running pace in minutes-per-km (e.g. 5.5 → 5:30/km). */
    private final double paceMinsPerKm;

    /** Mean heart rate over the session (bpm). */
    private final int    avgHeartRate;

    /** Peak heart rate recorded during the session (bpm). */
    private final int    maxHeartRateHit;

    /** Total elapsed running time in minutes. */
    private final double durationMinutes;

    // ----------------------------------------------------------------- Constructor

    public CardioSession(long userId,
                         LocalDateTime sessionTimestamp,
                         double distanceKm,
                         double paceMinsPerKm,
                         int    avgHeartRate,
                         int    maxHeartRateHit,
                         double durationMinutes,
                         String notes) {
        super(userId, WorkoutType.CARDIO_5K, sessionTimestamp, notes);

        this.distanceKm      = distanceKm;
        this.paceMinsPerKm   = paceMinsPerKm;
        this.avgHeartRate    = avgHeartRate;
        this.maxHeartRateHit = maxHeartRateHit;
        this.durationMinutes = durationMinutes;
    }

    // ================================================================= Load Algorithm

    /**
     * Computes a TRIMP-based cardiovascular load score.
     *
     * <p>Higher average HR relative to the athlete's personal HR reserve (HRR)
     * produces an exponentially higher score — a 175 bpm avg on a 5K is far
     * more stressful than a 150 bpm easy jog.
     *
     * @param user provides resting HR and max HR for HRR calculation
     * @return dimensionless TRIMP / 100  (typically 0.3 – 3.0 for 5K efforts)
     */
    @Override
    protected double computeRawLoad(UserProfile user) {
        double hrr     = user.getHeartRateReserve();
        if (hrr <= 0) return durationMinutes / 100.0;  // degenerate guard

        double pctHRR  = Math.min(1.0, Math.max(0.0,
                             (avgHeartRate - user.getRestingHeartRate()) / hrr));

        // Bannister TRIMP with Karvonen %HRR
        double trimp   = durationMinutes * pctHRR * 0.64 * Math.exp(1.92 * pctHRR);

        return trimp / 100.0;
    }

    // ----------------------------------------------------------------- Derived Metrics

    /**
     * Average speed in km/h.
     */
    public double getAverageSpeedKmh() {
        if (durationMinutes <= 0) return 0;
        return (distanceKm / durationMinutes) * 60.0;
    }

    /**
     * Estimated caloric expenditure using the simplified MET method.
     * MET for running ≈ 8–12 depending on pace.
     */
    public double getEstimatedCalories(UserProfile user) {
        // MET scales roughly with speed; 8.0 km/h ≈ MET 8, 12 km/h ≈ MET 11
        double speedKmh = getAverageSpeedKmh();
        double met      = 6.0 + 0.4 * speedKmh;
        return met * user.getWeightKg() * (durationMinutes / 60.0);
    }

    /**
     * %HRR as a percentage (0–100).
     */
    public double getPercentHRR(UserProfile user) {
        double hrr = user.getHeartRateReserve();
        if (hrr <= 0) return 0;
        return Math.min(100.0, ((avgHeartRate - user.getRestingHeartRate()) / hrr) * 100.0);
    }

    /**
     * Qualitative intensity zone based on %HRR:
     * Zone 1 < 60 % — Zone 2 60–70 % — Zone 3 70–80 % — Zone 4 80–90 % — Zone 5 > 90 %
     */
    public String getIntensityZone(UserProfile user) {
        double pct = getPercentHRR(user);
        if      (pct < 60) return "Zone 1 — Recovery";
        else if (pct < 70) return "Zone 2 — Aerobic Base";
        else if (pct < 80) return "Zone 3 — Tempo";
        else if (pct < 90) return "Zone 4 — Threshold";
        else               return "Zone 5 — VO₂Max / Max Effort";
    }

    // ================================================================= Overrides

    @Override
    public String getMuscleGroupsSummary() {
        return "Cardiovascular system, Quads (secondary), Calves (secondary)";
    }

    // ----------------------------------------------------------------- Getters

    public double getDistanceKm()      { return distanceKm; }
    public double getPaceMinsPerKm()   { return paceMinsPerKm; }
    public int    getAvgHeartRate()    { return avgHeartRate; }
    public int    getMaxHeartRateHit() { return maxHeartRateHit; }
    public double getDurationMinutes() { return durationMinutes; }

    @Override
    public String toString() {
        int paceMin = (int) paceMinsPerKm;
        int paceSec = (int) Math.round((paceMinsPerKm - paceMin) * 60);
        return String.format(
            "CardioSession{%.2f km | %d:%02d /km | avg HR %d bpm | %.0f min | load=%.2f | %s}",
            distanceKm, paceMin, paceSec, avgHeartRate,
            durationMinutes, calculatedLoad, sessionTimestamp.toLocalDate()
        );
    }
}
