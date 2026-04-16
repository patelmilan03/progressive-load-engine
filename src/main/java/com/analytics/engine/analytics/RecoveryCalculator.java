package com.analytics.engine.analytics;

import com.analytics.engine.model.*;

import java.util.*;

/**
 * The central analytics component — evaluates the last 48 hours of training
 * and produces a {@link ReadinessReport} with a Readiness Score (1–100)
 * and actionable recommendations.
 *
 * <h2>Algorithm: Exponential Fatigue Decay Model</h2>
 *
 * <h3>Step 1 — Per-Session Fatigue Contribution</h3>
 * <pre>
 *  For each HypertrophySession in the 48h window:
 *    muscleFatigue[mg] = (RPE-adj volume for mg) / adjustedThreshold[mg] × 100
 *
 *  For each CardioSession in the 48h window:
 *    cardioFatigue = (TRIMP / 100) / cardioThreshold × 100
 * </pre>
 *
 * <h3>Step 2 — Time Decay</h3>
 * <p>Fatigue decays exponentially with time since the session.  Each
 * muscle group has an individual half-life (t½) calibrated from recovery
 * science:
 * <pre>
 *   λ  = ln(2) / t½          (decay constant)
 *   F(t) = F₀ × e^(−λ × t)  where t = hours since session
 * </pre>
 * This means a Legs session 6 hours ago still carries ~95 % of its initial
 * quad fatigue, while the same session 48 hours ago retains only ~37 %.
 *
 * <h3>Step 3 — Weighted Aggregate & Score</h3>
 * <pre>
 *  strengthFatigue = Σ [ residualFatigue(mg) × mg.fatigueWeightFactor ]
 *                    ─────────────────────────────────────────────────
 *                    Σ mg.fatigueWeightFactor  (normalised to 0–100)
 *
 *  compositeFatigue = 0.65 × strengthFatigue + 0.35 × cardiovascularFatigue
 *  readinessScore   = round(100 − compositeFatigue)
 *  readinessScore   = clamp(readinessScore, 1, 100)
 * </pre>
 *
 * <h3>Step 4 — Recommendation Generation</h3>
 * <p>The score maps to a {@link ReadinessReport.ScoreBand}.  Additionally,
 * if any individual muscle group carries > 70 % residual fatigue, a targeted
 * deload instruction is added to the insights list.
 */
public final class RecoveryCalculator {

    // Weights for composite score:  strength vs cardio fatigue
    private static final double STRENGTH_WEIGHT = 0.65;
    private static final double CARDIO_WEIGHT   = 0.35;

    // Fatigue threshold beyond which a muscle group triggers a targeted warning
    private static final double HIGH_MUSCLE_FATIGUE_PCT = 70.0;

    // ================================================================= PUBLIC API

    /**
     * Calculates the athlete's current readiness from a list of recent sessions.
     *
     * <p>The sessions list does not need to be pre-filtered to 48h; this method
     * applies the time window internally so callers may pass a longer history
     * without incorrect results.  However, for performance, callers should
     * pass only the last 48–72 h from the DAO.
     *
     * @param user            the athlete profile (for load normalisation)
     * @param recentSessions  recent training sessions (mixed types acceptable)
     * @return a fully populated {@link ReadinessReport}
     */
    public ReadinessReport calculateReadiness(UserProfile user,
                                             List<WorkoutSession> recentSessions) {

        Objects.requireNonNull(user,           "UserProfile must not be null");
        Objects.requireNonNull(recentSessions, "Session list must not be null");

        // ---- Step 1 & 2: Accumulate time-decayed fatigue per muscle group ----

        Map<MuscleGroup, Double> residualMuscleFatigue = new EnumMap<>(MuscleGroup.class);
        for (MuscleGroup mg : MuscleGroup.values()) {
            residualMuscleFatigue.put(mg, 0.0);
        }
        double residualCardioFatigue = 0.0;

        for (WorkoutSession session : recentSessions) {
            double hoursAgo = session.hoursAgo();

            if (session instanceof HypertrophySession hs) {
                // Force load calculation so the cache is populated
                hs.calculateLoad(user);
                Map<MuscleGroup, Double> fatiguePct =
                    LoadCalculator.muscleFatiguePercent(hs, user);

                for (Map.Entry<MuscleGroup, Double> entry : fatiguePct.entrySet()) {
                    MuscleGroup mg         = entry.getKey();
                    double      initialPct = entry.getValue();
                    double      decayed    = applyDecay(initialPct, mg, hoursAgo);
                    residualMuscleFatigue.merge(mg, decayed, Double::sum);
                }
            }

            else if (session instanceof CardioSession cs) {
                cs.calculateLoad(user);
                double initialCardioFatigue =
                    LoadCalculator.cardioFatiguePercent(cs, user);
                // Cardiovascular system recovers with a half-life of ~36h
                double decayed = applyDecayRaw(initialCardioFatigue, 36.0, hoursAgo);
                residualCardioFatigue += decayed;
            }
        }

        // Cap each muscle fatigue at 100 % (multiple heavy sessions can stack)
        residualMuscleFatigue.replaceAll((mg, v) -> Math.min(100.0, v));
        residualCardioFatigue = Math.min(100.0, residualCardioFatigue);

        // ---- Step 3: Composite readiness score ----

        double strengthFatigue = computeWeightedStrengthFatigue(residualMuscleFatigue);
        double compositeFatigue = STRENGTH_WEIGHT * strengthFatigue
                                + CARDIO_WEIGHT   * residualCardioFatigue;

        int readinessScore = (int) Math.round(100.0 - compositeFatigue);
        readinessScore = Math.max(1, Math.min(100, readinessScore));

        // ---- Step 4: Recommendations ----

        String       primaryRec = buildPrimaryRecommendation(readinessScore,
                                     residualMuscleFatigue, residualCardioFatigue);
        List<String> insights   = buildInsights(readinessScore, residualMuscleFatigue,
                                     residualCardioFatigue, user);

        return new ReadinessReport(
            readinessScore,
            primaryRec,
            insights,
            residualMuscleFatigue,
            residualCardioFatigue,
            recentSessions.size()
        );
    }

    // ================================================================= DECAY HELPERS

    /**
     * Applies exponential decay to an initial fatigue percentage,
     * using the muscle group's own half-life.
     *
     * @param initialPct  fatigue at time of session (0–100+)
     * @param mg          muscle group (supplies t½)
     * @param hoursAgo    hours elapsed since the session
     * @return residual fatigue percentage
     */
    private static double applyDecay(double initialPct,
                                     MuscleGroup mg,
                                     double hoursAgo) {
        return applyDecayRaw(initialPct, mg.getRecoveryHalfLifeHours(), hoursAgo);
    }

    /**
     * Applies exponential decay with an explicit half-life.
     * F(t) = F₀ × e^(−λt),  λ = ln(2) / t½
     */
    private static double applyDecayRaw(double initial, double halfLifeHours, double hoursAgo) {
        if (hoursAgo <= 0) return initial;
        double lambda = Math.log(2) / halfLifeHours;
        return initial * Math.exp(-lambda * hoursAgo);
    }

    // ================================================================= AGGREGATE STRENGTH FATIGUE

    /**
     * Computes a single, weighted aggregate of all per-muscle-group fatigue values.
     * Groups with higher metabolic impact (quads, back) have a larger weight.
     *
     * @return 0–100 weighted strength fatigue score
     */
    private static double computeWeightedStrengthFatigue(
            Map<MuscleGroup, Double> residualMuscleFatigue) {

        double weightedSum  = 0.0;
        double totalWeights = 0.0;

        for (MuscleGroup mg : MuscleGroup.values()) {
            double fatigue = residualMuscleFatigue.getOrDefault(mg, 0.0);
            double weight  = mg.getFatigueWeightFactor();
            weightedSum  += fatigue * weight;
            totalWeights += weight;
        }
        return totalWeights > 0 ? weightedSum / totalWeights : 0.0;
    }

    // ================================================================= RECOMMENDATION ENGINE

    private static String buildPrimaryRecommendation(
            int readinessScore,
            Map<MuscleGroup, Double> muscleFatigue,
            double cardioFatigue) {

        ReadinessReport.ScoreBand band = ReadinessReport.ScoreBand.fromScore(readinessScore);

        return switch (band) {
            case OPTIMAL -> {
                // Find the muscle group most ready for overload (lowest fatigue in PPL cycle)
                MuscleGroup freshest = findFreshestMuscle(muscleFatigue);
                yield "🟢 Readiness is OPTIMAL.  Consider a progressive overload session — " +
                      "increase " + freshest.getPrimaryWorkoutDay().getDisplayName() +
                      " volume by +5 % (target: " + freshest.name() + ").";
            }
            case GOOD -> "🟡 Readiness is GOOD.  Proceed with your scheduled session at " +
                         "current training load.  Monitor RPE closely.";
            case MODERATE -> {
                MuscleGroup mostFatigued = findMostFatiguedMuscle(muscleFatigue);
                yield "🟠 Moderate fatigue detected.  Recommend reducing " +
                      mostFatigued.getPrimaryWorkoutDay().getDisplayName() +
                      " intensity by ~10 % — deload " + mostFatigued.name() +
                      " work specifically.";
            }
            case HIGH_LOAD -> {
                MuscleGroup mostFatigued = findMostFatiguedMuscle(muscleFatigue);
                yield "🔴 HIGH cumulative load.  DELOAD " +
                      mostFatigued.getPrimaryWorkoutDay().getDisplayName() +
                      " today — drop to 50 % of normal volume for " +
                      mostFatigued.name() + ".  Prioritise sleep & nutrition.";
            }
            case CRITICAL -> "⛔ CRITICAL fatigue.  Mandatory rest day.  No structured " +
                             "training.  Focus: 9 h sleep, protein targets, foam rolling only.";
        };
    }

    private static List<String> buildInsights(int readinessScore,
                                              Map<MuscleGroup, Double> muscleFatigue,
                                              double cardioFatigue,
                                              UserProfile user) {
        List<String> insights = new ArrayList<>();

        // 1. Per-muscle targeted advice
        muscleFatigue.forEach((mg, pct) -> {
            if (pct > HIGH_MUSCLE_FATIGUE_PCT) {
                insights.add(String.format(
                    "⚠  %s fatigue at %.0f %% — avoid direct %s work for the next %.0f h.",
                    mg.name(), pct,
                    mg.name().toLowerCase(),
                    estimateHoursToRecovery(pct, mg.getRecoveryHalfLifeHours(), 30.0)
                ));
            }
        });

        // 2. Cardio load insight
        if (cardioFatigue > 60.0) {
            insights.add(String.format(
                "🏃 Cardiovascular fatigue at %.0f %% — if you must run today, " +
                "stay in Zone 1–2 (easy aerobic).  Skip intervals.", cardioFatigue));
        }

        // 3. Volume progression insight
        if (readinessScore >= 80) {
            insights.add("📈 Progressive Overload Window open.  " +
                         "Add 1 set or +2.5 kg to your primary compound lift today.");
        }

        // 4. Sleep inference (simplified)
        if (readinessScore < 50) {
            insights.add("😴 Recovery deficit detected.  Prioritise 8–9 h sleep tonight " +
                         "and ensure protein intake ≥ " +
                         String.format("%.0f", user.getWeightKg() * 2.0) + " g.");
        }

        // 5. Next optimal heavy session
        MuscleGroup freshest = findFreshestMuscle(muscleFatigue);
        insights.add(String.format(
            "💡 Most recovered muscle group: %s (%.0f %% fatigue).  " +
            "Best day to train it hard: %s.",
            freshest.name(),
            muscleFatigue.getOrDefault(freshest, 0.0),
            freshest.getPrimaryWorkoutDay().getDisplayName()
        ));

        return insights;
    }

    // ================================================================= UTILITIES

    private static MuscleGroup findMostFatiguedMuscle(Map<MuscleGroup, Double> fatigueMap) {
        return fatigueMap.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(MuscleGroup.QUADS);
    }

    private static MuscleGroup findFreshestMuscle(Map<MuscleGroup, Double> fatigueMap) {
        return fatigueMap.entrySet().stream()
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(MuscleGroup.CHEST);
    }

    /**
     * Estimates hours until residual fatigue falls below a target percentage.
     * Solves F(t) = target for t:  t = −ln(target / F₀) / λ
     */
    private static double estimateHoursToRecovery(double currentPct,
                                                   double halfLifeHours,
                                                   double targetPct) {
        if (currentPct <= targetPct) return 0.0;
        double lambda = Math.log(2) / halfLifeHours;
        return -Math.log(targetPct / currentPct) / lambda;
    }
}
