package com.analytics.engine.analytics;

import com.analytics.engine.model.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes the <strong>Acute:Chronic Workload Ratio (ACWR)</strong> — the
 * gold-standard injury-risk metric used in elite sport (AFL, rugby, football).
 *
 * <h2>Scientific Background</h2>
 * <p>Introduced by Tim Gabbett (2016), the ACWR compares short-term training
 * load (the "fitness fatigue" of the last 7 days) against the long-term
 * rolling average (the "fitness base" of the last 28 days).
 *
 * <pre>
 *   Acute Load   =  Σ session load  (last 7 days)
 *   Chronic Load =  Σ session load  (last 28 days) / 4     (weekly average)
 *   ACWR         =  Acute Load / Chronic Load
 * </pre>
 *
 * <h2>Risk Zones</h2>
 * <table>
 *   <tr><th>ACWR</th>       <th>Zone</th>          <th>Injury Risk</th></tr>
 *   <tr><td>&lt; 0.80</td>  <td>UNDER-TRAINING</td><td>High (detraining)</td></tr>
 *   <tr><td>0.80–1.30</td>  <td>SWEET SPOT</td>    <td>Low</td></tr>
 *   <tr><td>1.30–1.50</td>  <td>CAUTION</td>       <td>Moderate (+15%)</td></tr>
 *   <tr><td>&gt; 1.50</td>  <td>DANGER ZONE</td>   <td>High (+2–4×)</td></tr>
 * </table>
 *
 * <h2>This Implementation</h2>
 * <p>Supports both:
 * <ul>
 *   <li><b>Classic ACWR</b> (simple rolling sum) — fast, easy to explain.</li>
 *   <li><b>Exponentially Weighted ACWR (EWMA)</b> — smoothes week-to-week
 *       noise; λ_acute = 2/(7+1), λ_chronic = 2/(28+1).</li>
 * </ul>
 * The EWMA variant is recommended for real-world use; classic is provided for
 * comparison and transparency.
 */
public final class AcuteChronicWorkloadRatio {

    // ----------------------------------------------------------------- Risk zones

    public enum RiskZone {
        UNDER_TRAINING("Under-Training",  "🔵",
            "Load spike risk from sudden return. Increase volume gradually."),
        SWEET_SPOT    ("Sweet Spot",      "🟢",
            "Optimal training stimulus. Maintain or progress conservatively."),
        CAUTION       ("Caution",         "🟡",
            "Elevated injury risk (+15%). Avoid large load spikes this week."),
        DANGER_ZONE   ("Danger Zone",     "🔴",
            "HIGH injury risk (2–4× baseline). Mandatory load reduction.");

        private final String label;
        private final String icon;
        private final String advice;

        RiskZone(String label, String icon, String advice) {
            this.label  = label;
            this.icon   = icon;
            this.advice = advice;
        }

        public String getLabel()  { return label;  }
        public String getIcon()   { return icon;   }
        public String getAdvice() { return advice; }

        public static RiskZone fromRatio(double ratio) {
            if (ratio < 0.80) return UNDER_TRAINING;
            if (ratio < 1.30) return SWEET_SPOT;
            if (ratio < 1.50) return CAUTION;
            return DANGER_ZONE;
        }
    }

    // ----------------------------------------------------------------- EWMA constants

    /** Smoothing factor for the 7-day (acute) EWMA. */
    private static final double LAMBDA_ACUTE   = 2.0 / (7  + 1);

    /** Smoothing factor for the 28-day (chronic) EWMA. */
    private static final double LAMBDA_CHRONIC = 2.0 / (28 + 1);

    // Private — pure static utility
    private AcuteChronicWorkloadRatio() {}

    // ================================================================= PUBLIC API

    /**
     * Computes an {@link AcwrReport} for the athlete using all provided sessions.
     *
     * <p>Sessions older than 28 days are ignored.  If fewer than 7 days of
     * history exist, the chronic load defaults to the acute load (ACWR = 1.0).
     *
     * @param user     athlete profile (used for load normalisation)
     * @param sessions all available training sessions (any age; method filters)
     * @return a fully populated {@link AcwrReport}
     */
    public static AcwrReport compute(UserProfile user,
                                     List<WorkoutSession> sessions) {
        Objects.requireNonNull(user);
        Objects.requireNonNull(sessions);

        LocalDateTime now = LocalDateTime.now();

        // Partition into 7-day and 28-day windows
        List<WorkoutSession> last7  = filterByHours(sessions, 7  * 24);
        List<WorkoutSession> last28 = filterByHours(sessions, 28 * 24);

        // ── Classic ACWR ──────────────────────────────────────────────────
        double acuteLoad   = sumLoad(last7,  user);
        double chronicLoad = sumLoad(last28, user) / 4.0;   // weekly average

        double classicRatio = (chronicLoad > 0) ? acuteLoad / chronicLoad : 1.0;

        // ── EWMA ACWR ─────────────────────────────────────────────────────
        // Sort all sessions in the 28-day window chronologically (oldest first)
        List<WorkoutSession> sorted28 = last28.stream()
            .sorted(Comparator.comparing(WorkoutSession::getSessionTimestamp))
            .collect(Collectors.toList());

        double ewmaAcute   = computeEwma(sorted28, user, LAMBDA_ACUTE);
        double ewmaChronic = computeEwma(sorted28, user, LAMBDA_CHRONIC);
        double ewmaRatio   = (ewmaChronic > 0) ? ewmaAcute / ewmaChronic : 1.0;

        // ── Per-type breakdown ────────────────────────────────────────────
        Map<WorkoutType, Double> acuteByType = loadByType(last7,  user);
        Map<String, Double>      weeklyTrend = weeklyLoadTrend(last28, user);

        return new AcwrReport(
            acuteLoad, chronicLoad, classicRatio,
            ewmaAcute, ewmaChronic, ewmaRatio,
            last7.size(), last28.size(),
            acuteByType, weeklyTrend
        );
    }

    // ----------------------------------------------------------------- Helpers

    private static List<WorkoutSession> filterByHours(List<WorkoutSession> sessions,
                                                       int hours) {
        return sessions.stream()
            .filter(s -> s.hoursAgo() <= hours)
            .collect(Collectors.toList());
    }

    private static double sumLoad(List<WorkoutSession> sessions, UserProfile user) {
        return sessions.stream()
            .mapToDouble(s -> s.calculateLoad(user))
            .sum();
    }

    /**
     * Computes an Exponentially Weighted Moving Average (EWMA) of daily session
     * loads.  The EWMA is seeded at 0 and updated per session.
     *
     * <pre>  EWMA_n = λ × load_n + (1 − λ) × EWMA_{n−1}</pre>
     */
    private static double computeEwma(List<WorkoutSession> chronological,
                                      UserProfile user, double lambda) {
        double ewma = 0.0;
        for (WorkoutSession s : chronological) {
            double load = s.calculateLoad(user);
            ewma = lambda * load + (1.0 - lambda) * ewma;
        }
        return ewma;
    }

    private static Map<WorkoutType, Double> loadByType(List<WorkoutSession> sessions,
                                                        UserProfile user) {
        return sessions.stream().collect(
            Collectors.groupingBy(
                WorkoutSession::getWorkoutType,
                Collectors.summingDouble(s -> s.calculateLoad(user))
            )
        );
    }

    /**
     * Splits the 28-day window into four 7-day buckets (weeks 1–4, oldest→newest)
     * and returns the total load per week. Useful for trend visualisation.
     */
    private static Map<String, Double> weeklyLoadTrend(List<WorkoutSession> sessions,
                                                        UserProfile user) {
        Map<String, Double> trend = new LinkedHashMap<>();
        String[] labels = {"Week -4", "Week -3", "Week -2", "Week -1 (current)"};
        int[] boundaries = {28*24, 21*24, 14*24, 7*24, 0};

        for (int i = 0; i < 4; i++) {
            final int lo = boundaries[i + 1];   // e.g. 7*24
            final int hi = boundaries[i];        // e.g. 14*24
            double weekLoad = sessions.stream()
                .filter(s -> s.hoursAgo() <= hi && s.hoursAgo() > lo)
                .mapToDouble(s -> s.calculateLoad(user))
                .sum();
            trend.put(labels[3 - i], weekLoad);  // newest week first in output
        }
        return trend;
    }

    // ================================================================= Report

    /**
     * Immutable result object from an ACWR computation.
     */
    public static final class AcwrReport {

        private final double acuteLoad;
        private final double chronicLoad;
        private final double classicRatio;
        private final double ewmaAcute;
        private final double ewmaChronic;
        private final double ewmaRatio;
        private final int    sessionsInAcuteWindow;
        private final int    sessionsInChronicWindow;
        private final Map<WorkoutType, Double> acuteLoadByType;
        private final Map<String, Double>      weeklyTrend;

        AcwrReport(double acuteLoad, double chronicLoad, double classicRatio,
                   double ewmaAcute, double ewmaChronic, double ewmaRatio,
                   int sessionsAcute, int sessionsChronic,
                   Map<WorkoutType, Double> acuteLoadByType,
                   Map<String, Double> weeklyTrend) {
            this.acuteLoad              = acuteLoad;
            this.chronicLoad            = chronicLoad;
            this.classicRatio           = classicRatio;
            this.ewmaAcute              = ewmaAcute;
            this.ewmaChronic            = ewmaChronic;
            this.ewmaRatio              = ewmaRatio;
            this.sessionsInAcuteWindow  = sessionsAcute;
            this.sessionsInChronicWindow = sessionsChronic;
            this.acuteLoadByType        = Collections.unmodifiableMap(
                                              new LinkedHashMap<>(acuteLoadByType));
            this.weeklyTrend            = Collections.unmodifiableMap(weeklyTrend);
        }

        // ----- Getters -----

        public double getAcuteLoad()               { return acuteLoad; }
        public double getChronicLoad()             { return chronicLoad; }
        public double getClassicRatio()            { return classicRatio; }
        public double getEwmaAcute()               { return ewmaAcute; }
        public double getEwmaChronic()             { return ewmaChronic; }
        public double getEwmaRatio()               { return ewmaRatio; }
        public int    getSessionsInAcuteWindow()   { return sessionsInAcuteWindow; }
        public int    getSessionsInChronicWindow() { return sessionsInChronicWindow; }
        public Map<WorkoutType, Double> getAcuteLoadByType() { return acuteLoadByType; }
        public Map<String, Double>      getWeeklyTrend()     { return weeklyTrend; }

        /** Risk zone based on the preferred EWMA ratio. */
        public RiskZone getRiskZone()       { return RiskZone.fromRatio(ewmaRatio); }

        /** Risk zone from the classic (simple) ratio, for comparison. */
        public RiskZone getClassicRiskZone(){ return RiskZone.fromRatio(classicRatio); }

        /**
         * Returns the recommended maximum acute load increase for this week
         * that keeps the ACWR within the sweet-spot ceiling of 1.30.
         *
         * <pre>  maxIncrease = chronicLoad × 1.30 − acuteLoad</pre>
         */
        public double getMaxSafeLoadIncrease() {
            return Math.max(0, chronicLoad * 1.30 - acuteLoad);
        }

        /**
         * Formats the full ACWR report as a human-readable console block.
         */
        public String toFormattedReport() {
            StringBuilder sb = new StringBuilder();
            String sep  = "═".repeat(64);
            String thin = "─".repeat(64);

            sb.append("\n").append(sep).append("\n");
            sb.append("  ACUTE:CHRONIC WORKLOAD RATIO (ACWR) — INJURY RISK ANALYSIS\n");
            sb.append(sep).append("\n");

            sb.append(String.format("  %-28s  %8s    %8s%n", "", "Classic", "EWMA"));
            sb.append("  ").append(thin).append("\n");
            sb.append(String.format("  %-28s  %8.2f    %8.2f%n",
                "Acute Load (7-day)",   acuteLoad,  ewmaAcute));
            sb.append(String.format("  %-28s  %8.2f    %8.2f%n",
                "Chronic Load (28-day avg)", chronicLoad, ewmaChronic));
            sb.append(String.format("  %-28s  %8.3f    %8.3f%n",
                "ACWR Ratio",            classicRatio, ewmaRatio));

            RiskZone zone = getRiskZone();
            sb.append("\n  ").append(thin).append("\n");
            sb.append(String.format("  Risk Zone (EWMA)  :  %s %s%n",
                zone.getIcon(), zone.getLabel()));
            sb.append(String.format("  Advice            :  %s%n", zone.getAdvice()));
            sb.append(String.format("  Max safe load +   :  %.2f units this week%n",
                getMaxSafeLoadIncrease()));

            // Acute load breakdown by type
            sb.append("\n  ").append(thin).append("\n");
            sb.append("  7-Day Load Breakdown by Session Type:\n");
            if (acuteLoadByType.isEmpty()) {
                sb.append("    (no sessions in acute window)\n");
            } else {
                acuteLoadByType.entrySet().stream()
                    .sorted(Map.Entry.<WorkoutType, Double>comparingByValue().reversed())
                    .forEach(e -> sb.append(String.format(
                        "    %-14s  %6.2f%n",
                        e.getKey().getDisplayName(), e.getValue())));
            }

            // 4-week trend
            sb.append("\n  ").append(thin).append("\n");
            sb.append("  4-Week Load Trend (newest → oldest):\n");
            weeklyTrend.forEach((label, load) -> {
                int bars = (int) Math.min(30, load / 10);
                sb.append(String.format("    %-20s  %s  %.1f%n",
                    label, "▓".repeat(bars) + "░".repeat(30 - bars), load));
            });

            sb.append("\n  Sessions — Acute window: ").append(sessionsInAcuteWindow)
              .append("   Chronic window: ").append(sessionsInChronicWindow).append("\n");
            sb.append(sep).append("\n");

            return sb.toString();
        }
    }
}
