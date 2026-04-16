package com.analytics.engine.model;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable result object produced by {@code RecoveryCalculator}.
 *
 * <p>Encapsulates the full output of a readiness analysis run:
 * <ul>
 *   <li>A composite <strong>Readiness Score</strong> from 1 (exhausted) to 100 (fully fresh).</li>
 *   <li>Per-muscle-group residual fatigue percentages.</li>
 *   <li>Cardiovascular fatigue percentage.</li>
 *   <li>A primary text <strong>recommendation</strong> for today's session.</li>
 *   <li>A list of <strong>additional insights</strong> (muscle-specific advice).</li>
 * </ul>
 *
 * <h3>Score Bands</h3>
 * <pre>
 *   ≥ 80  — OPTIMAL    → Push volume up 5 %
 *   65–79 — GOOD       → Maintain current load
 *   50–64 — MODERATE   → Reduce intensity 10 %
 *   35–49 — HIGH_LOAD  → Deload recommended
 *    < 35  — CRITICAL  → Full rest day mandatory
 * </pre>
 */
public final class ReadinessReport {

    // ----------------------------------------------------------------- Score Bands

    public enum ScoreBand {
        OPTIMAL    ("Optimal",      "🟢", 80),
        GOOD       ("Good",         "🟡", 65),
        MODERATE   ("Moderate",     "🟠", 50),
        HIGH_LOAD  ("High Load",    "🔴", 35),
        CRITICAL   ("Critical",     "⛔", 0);

        private final String label;
        private final String icon;
        private final int    minScore;

        ScoreBand(String label, String icon, int minScore) {
            this.label    = label;
            this.icon     = icon;
            this.minScore = minScore;
        }

        public String getLabel()    { return label; }
        public String getIcon()     { return icon; }
        public int    getMinScore() { return minScore; }

        public static ScoreBand fromScore(int score) {
            for (ScoreBand b : values()) {
                if (score >= b.minScore) return b;
            }
            return CRITICAL;
        }
    }

    // ----------------------------------------------------------------- Fields

    private final int                     readinessScore;        // 1–100
    private final ScoreBand               scoreBand;
    private final String                  primaryRecommendation;
    private final List<String>            insights;              // ordered by importance
    private final Map<MuscleGroup, Double> muscleFatiguePercent; // 0–100 per muscle
    private final double                  cardiovascularFatigue; // 0–100
    private final int                     sessionsAnalysed;
    private final LocalDateTime           generatedAt;

    // ----------------------------------------------------------------- Constructor (public — instantiated by RecoveryCalculator)

    public ReadinessReport(int readinessScore,
                    String primaryRecommendation,
                    List<String> insights,
                    Map<MuscleGroup, Double> muscleFatiguePercent,
                    double cardiovascularFatigue,
                    int sessionsAnalysed) {
        this.readinessScore        = Math.max(1, Math.min(100, readinessScore));
        this.scoreBand             = ScoreBand.fromScore(this.readinessScore);
        this.primaryRecommendation = primaryRecommendation;
        this.insights              = Collections.unmodifiableList(insights);
        this.muscleFatiguePercent  = Collections.unmodifiableMap(
                                         new EnumMap<>(muscleFatiguePercent));
        this.cardiovascularFatigue = cardiovascularFatigue;
        this.sessionsAnalysed      = sessionsAnalysed;
        this.generatedAt           = LocalDateTime.now();
    }

    // ----------------------------------------------------------------- Getters

    public int                      getReadinessScore()        { return readinessScore; }
    public ScoreBand                getScoreBand()             { return scoreBand; }
    public String                   getPrimaryRecommendation() { return primaryRecommendation; }
    public List<String>             getInsights()              { return insights; }
    public Map<MuscleGroup, Double> getMuscleFatiguePercent()  { return muscleFatiguePercent; }
    public double                   getCardiovascularFatigue() { return cardiovascularFatigue; }
    public int                      getSessionsAnalysed()      { return sessionsAnalysed; }
    public LocalDateTime            getGeneratedAt()           { return generatedAt; }

    // ----------------------------------------------------------------- Convenience

    public double getMuscleGroupFatigue(MuscleGroup mg) {
        return muscleFatiguePercent.getOrDefault(mg, 0.0);
    }

    /** {@code true} if any single muscle group carries > 70 % residual fatigue. */
    public boolean hasHighMuscleGroupFatigue() {
        return muscleFatiguePercent.values().stream().anyMatch(f -> f > 70.0);
    }

    @Override
    public String toString() {
        return String.format("ReadinessReport{score=%d, band=%s, cardioFatigue=%.1f%%}",
            readinessScore, scoreBand.getLabel(), cardiovascularFatigue);
    }
}
