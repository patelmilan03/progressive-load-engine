package com.analytics.engine.analytics;

import com.analytics.engine.model.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Analyses training history across multiple weeks to generate <em>progressive
 * overload</em> recommendations aligned with the athlete's current readiness.
 *
 * <h3>Progressive Overload Principle</h3>
 * <p>Systematic increases in training stimulus are required for continued
 * adaptation.  However, increases must be gated on:
 * <ol>
 *   <li>Current readiness score (not increasing when fatigued)</li>
 *   <li>Volume progression rate (≤ 5–10 % per week to avoid overuse)</li>
 *   <li>Muscle-specific fatigue state</li>
 * </ol>
 *
 * <h3>Decision Tree</h3>
 * <pre>
 *  Readiness ≥ 80  →  Apply +5 % volume increase on primary compound lifts
 *  Readiness 65–79  →  Maintain.  Log session, no changes.
 *  Readiness 50–64  →  Reduce: −10 % intensity, keep volume
 *  Readiness < 50   →  Deload: −50 % volume OR rest day
 * </pre>
 */
public final class ProgressiveOverloadAdvisor {

    private final RecoveryCalculator recoveryCalculator;

    public ProgressiveOverloadAdvisor(RecoveryCalculator recoveryCalculator) {
        this.recoveryCalculator = recoveryCalculator;
    }

    // ================================================================= PUBLIC API

    /**
     * Generates a full overload advisory for the athlete.
     *
     * @param user           athlete profile
     * @param recentSessions last 48–72 h sessions (for readiness)
     * @param weekHistory    full 7-day session list (for volume trend analysis)
     * @return formatted advisory report as a multi-line string
     */
    public String generateAdvisory(UserProfile user,
                                   List<WorkoutSession> recentSessions,
                                   List<WorkoutSession> weekHistory) {

        ReadinessReport report = recoveryCalculator.calculateReadiness(user, recentSessions);

        // Volume trend: compute weekly load totals per workout type
        Map<WorkoutType, Double> weeklyLoadByType = weekHistory.stream()
            .filter(s -> s.getWorkoutType() != WorkoutType.REST)
            .collect(Collectors.groupingBy(
                WorkoutSession::getWorkoutType,
                Collectors.summingDouble(s -> s.calculateLoad(user))
            ));

        StringBuilder sb = new StringBuilder();
        sb.append("\n╔══════════════════════════════════════════════════════════╗\n");
        sb.append("║         PROGRESSIVE OVERLOAD ADVISORY REPORT              ║\n");
        sb.append("╚══════════════════════════════════════════════════════════╝\n");

        sb.append("\n▶  READINESS SCORE  :  ")
          .append(report.getReadinessScore())
          .append(" / 100  [")
          .append(report.getScoreBand().getIcon())
          .append(" ")
          .append(report.getScoreBand().getLabel())
          .append("]\n");

        sb.append("▶  PRIMARY ACTION   :  ")
          .append(report.getPrimaryRecommendation())
          .append("\n");

        // Per-day overload prescriptions
        sb.append("\n─── Weekly Volume Prescription ───────────────────────────\n");
        for (WorkoutType type : new WorkoutType[]{
                WorkoutType.PUSH, WorkoutType.PULL,
                WorkoutType.LEGS, WorkoutType.CARDIO_5K}) {

            double currentLoad    = weeklyLoadByType.getOrDefault(type, 0.0);
            double targetLoad     = computeTargetLoad(type, currentLoad, report, user);
            double changePercent  = currentLoad > 0
                                    ? ((targetLoad - currentLoad) / currentLoad) * 100.0
                                    : 0.0;
            String changeLabel    = formatChange(changePercent);

            sb.append(String.format("  %-14s  |  Current load: %6.2f  |  Target: %6.2f  |  %s%n",
                type.getDisplayName(), currentLoad, targetLoad, changeLabel));
        }

        // Insights
        if (!report.getInsights().isEmpty()) {
            sb.append("\n─── Insights & Targeted Advice ───────────────────────────\n");
            report.getInsights().forEach(i -> sb.append("  ").append(i).append("\n"));
        }

        // Muscle fatigue breakdown
        sb.append("\n─── Muscle Group Residual Fatigue (%) ────────────────────\n");
        report.getMuscleFatiguePercent().entrySet().stream()
            .sorted(Map.Entry.<MuscleGroup, Double>comparingByValue().reversed())
            .forEach(e -> sb.append(String.format(
                "  %-14s  %s  %.1f %%%n",
                e.getKey().name(),
                buildFatigueBar(e.getValue()),
                e.getValue()
            )));

        sb.append("\n  Cardiovascular  ")
          .append(buildFatigueBar(report.getCardiovascularFatigue()))
          .append(String.format("  %.1f %%\n", report.getCardiovascularFatigue()));

        sb.append("\n─── Session Stats ─────────────────────────────────────────\n");
        sb.append("  Sessions analysed (48h window): ").append(report.getSessionsAnalysed())
          .append("\n  Report generated at: ").append(report.getGeneratedAt()).append("\n");

        return sb.toString();
    }

    // ================================================================= PRESCRIPTION LOGIC

    private double computeTargetLoad(WorkoutType type, double currentLoad,
                                     ReadinessReport report, UserProfile user) {
        int score = report.getReadinessScore();

        // Get the primary muscle group's residual fatigue for this workout day
        double relevantMuscFatigue = getPrimaryMuscleFatigueForDay(type, report);

        if (score >= 80 && relevantMuscFatigue < 40) {
            return currentLoad * 1.05;          // +5 % progressive overload

        } else if (score >= 65) {
            return currentLoad;                  // maintain

        } else if (score >= 50) {
            return currentLoad * 0.90;           // −10 % intensity deload

        } else {
            return currentLoad * 0.50;           // full deload
        }
    }

    private double getPrimaryMuscleFatigueForDay(WorkoutType day, ReadinessReport report) {
        Map<MuscleGroup, Double> fatigueMap = report.getMuscleFatiguePercent();
        return switch (day) {
            case PUSH      -> Math.max(
                                fatigueMap.getOrDefault(MuscleGroup.CHEST, 0.0),
                                fatigueMap.getOrDefault(MuscleGroup.SHOULDERS, 0.0));
            case PULL      -> fatigueMap.getOrDefault(MuscleGroup.BACK, 0.0);
            case LEGS      -> Math.max(
                                fatigueMap.getOrDefault(MuscleGroup.QUADS, 0.0),
                                fatigueMap.getOrDefault(MuscleGroup.HAMSTRINGS, 0.0));
            case CARDIO_5K -> report.getCardiovascularFatigue();
            default        -> 0.0;
        };
    }

    // ================================================================= FORMATTING

    private static String formatChange(double pct) {
        if (Math.abs(pct) < 0.5) return "→  MAINTAIN";
        if (pct > 0) return String.format("↑  +%.1f %% INCREASE", pct);
        return String.format("↓  %.1f %% REDUCE", pct);
    }

    /** ASCII progress bar (20 chars wide) for fatigue percentage. */
    private static String buildFatigueBar(double pct) {
        int filled  = (int) Math.min(20, pct / 5.0);
        int empty   = 20 - filled;
        String bar  = "█".repeat(filled) + "░".repeat(empty);
        String color = pct > 70 ? "[!!]" : pct > 40 ? "[ ! ]" : "[   ]";
        return "[" + bar + "] " + color;
    }
}
