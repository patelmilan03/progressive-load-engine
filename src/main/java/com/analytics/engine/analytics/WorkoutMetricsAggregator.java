package com.analytics.engine.analytics;

import com.analytics.engine.model.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes aggregate training statistics over arbitrary time windows.
 *
 * <h3>Metrics Produced</h3>
 * <ul>
 *   <li><b>Volume</b>: total kg·reps lifted, average session volume</li>
 *   <li><b>Frequency</b>: sessions per week, training streak (consecutive days)</li>
 *   <li><b>Intensity</b>: average RPE across all exercise sets</li>
 *   <li><b>Distribution</b>: % split between PUSH / PULL / LEGS / CARDIO</li>
 *   <li><b>Cardio</b>: total km run, average pace, average HR</li>
 *   <li><b>Dominant muscle</b>: which muscle group received the most volume</li>
 * </ul>
 *
 * <p>All methods are pure functions (no DB access). The caller provides the
 * session list, which may span any duration.
 */
public final class WorkoutMetricsAggregator {

    // Private — pure static utility class
    private WorkoutMetricsAggregator() {}

    // ================================================================= PUBLIC API

    /**
     * Computes the full {@link MetricsSummary} from a list of sessions.
     *
     * @param sessions all sessions to analyse (mixed types)
     * @param user     athlete profile (for load normalisation)
     * @return an immutable {@link MetricsSummary}
     */
    public static MetricsSummary aggregate(List<WorkoutSession> sessions,
                                           UserProfile user) {
        Objects.requireNonNull(sessions);
        Objects.requireNonNull(user);

        if (sessions.isEmpty()) return MetricsSummary.EMPTY;

        List<HypertrophySession> strengthSessions = sessions.stream()
            .filter(s -> s instanceof HypertrophySession)
            .map(s -> (HypertrophySession) s)
            .collect(Collectors.toList());

        List<CardioSession> cardioSessions = sessions.stream()
            .filter(s -> s instanceof CardioSession)
            .map(s -> (CardioSession) s)
            .collect(Collectors.toList());

        // ── Volume stats ─────────────────────────────────────────────────
        double totalRawVolume = strengthSessions.stream()
            .mapToDouble(HypertrophySession::getTotalRawVolume)
            .sum();

        double totalAdjustedLoad = sessions.stream()
            .mapToDouble(s -> s.calculateLoad(user))
            .sum();

        double avgSessionLoad = sessions.isEmpty() ? 0
            : totalAdjustedLoad / sessions.size();

        // ── Intensity (average RPE across all exercise sets) ─────────────
        OptionalDouble avgRpe = strengthSessions.stream()
            .flatMap(s -> s.getExercises().stream())
            .mapToDouble(ExerciseSet::getRpe)
            .average();

        // ── Frequency ────────────────────────────────────────────────────
        long totalDays = spanInDays(sessions);
        double weeksSpanned = Math.max(1.0, totalDays / 7.0);
        double sessionsPerWeek = sessions.size() / weeksSpanned;

        // ── Session type distribution ─────────────────────────────────────
        Map<WorkoutType, Long> typeCount = sessions.stream()
            .collect(Collectors.groupingBy(WorkoutSession::getWorkoutType, Collectors.counting()));

        Map<WorkoutType, Double> typePct = new LinkedHashMap<>();
        for (WorkoutType wt : WorkoutType.values()) {
            long count = typeCount.getOrDefault(wt, 0L);
            typePct.put(wt, sessions.isEmpty() ? 0 : (count * 100.0 / sessions.size()));
        }

        // ── Dominant muscle group ─────────────────────────────────────────
        Map<MuscleGroup, Double> muscleVolume = new EnumMap<>(MuscleGroup.class);
        for (HypertrophySession s : strengthSessions) {
            s.getLoadPerMuscleGroup().forEach(
                (mg, vol) -> muscleVolume.merge(mg, vol, Double::sum));
        }
        MuscleGroup dominantMuscle = muscleVolume.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(null);

        // ── Cardio stats ──────────────────────────────────────────────────
        double totalKm     = cardioSessions.stream().mapToDouble(CardioSession::getDistanceKm).sum();
        double avgPace     = cardioSessions.stream().mapToDouble(CardioSession::getPaceMinsPerKm).average().orElse(0);
        double avgCardioHR = cardioSessions.stream().mapToDouble(CardioSession::getAvgHeartRate).average().orElse(0);
        double totalCardioMin = cardioSessions.stream().mapToDouble(CardioSession::getDurationMinutes).sum();

        // ── Consecutive training streak (days) ────────────────────────────
        int streak = computeStreak(sessions);

        // ── Total sets ────────────────────────────────────────────────────
        int totalSets = strengthSessions.stream().mapToInt(HypertrophySession::getTotalSets).sum();
        int totalExercises = strengthSessions.stream()
            .mapToInt(s -> s.getExercises().size()).sum();

        return new MetricsSummary(
            sessions.size(), strengthSessions.size(), cardioSessions.size(),
            totalRawVolume, totalAdjustedLoad, avgSessionLoad,
            avgRpe.orElse(0), sessionsPerWeek, totalDays, streak,
            typePct, muscleVolume, dominantMuscle,
            totalKm, avgPace, avgCardioHR, totalCardioMin,
            totalSets, totalExercises
        );
    }

    // ----------------------------------------------------------------- Helpers

    private static long spanInDays(List<WorkoutSession> sessions) {
        LocalDate earliest = sessions.stream()
            .map(s -> s.getSessionTimestamp().toLocalDate())
            .min(Comparator.naturalOrder()).orElse(LocalDate.now());
        LocalDate latest = sessions.stream()
            .map(s -> s.getSessionTimestamp().toLocalDate())
            .max(Comparator.naturalOrder()).orElse(LocalDate.now());
        return Math.max(1, ChronoUnit.DAYS.between(earliest, latest) + 1);
    }

    /**
     * Counts consecutive days ending at the most recent session day
     * on which at least one training session occurred.
     */
    private static int computeStreak(List<WorkoutSession> sessions) {
        Set<LocalDate> trainingDays = sessions.stream()
            .map(s -> s.getSessionTimestamp().toLocalDate())
            .collect(Collectors.toSet());

        LocalDate cursor = sessions.stream()
            .map(s -> s.getSessionTimestamp().toLocalDate())
            .max(Comparator.naturalOrder()).orElse(LocalDate.now());

        int streak = 0;
        while (trainingDays.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    // ================================================================= MetricsSummary

    /**
     * Immutable value object holding all computed training metrics.
     */
    public static final class MetricsSummary {

        public static final MetricsSummary EMPTY = new MetricsSummary(
            0,0,0, 0,0,0, 0,0,0,0,
            new LinkedHashMap<>(), new EnumMap<>(MuscleGroup.class), null,
            0,0,0,0, 0,0
        );

        // Totals
        private final int    totalSessions;
        private final int    strengthSessions;
        private final int    cardioSessions;
        private final double totalRawVolumeKg;
        private final double totalAdjustedLoad;
        private final double avgSessionLoad;
        // Intensity
        private final double avgRpe;
        // Frequency
        private final double sessionsPerWeek;
        private final long   totalDays;
        private final int    currentStreak;
        // Distribution
        private final Map<WorkoutType, Double>  typePct;
        private final Map<MuscleGroup, Double>  muscleVolume;
        private final MuscleGroup               dominantMuscle;
        // Cardio
        private final double totalKm;
        private final double avgPaceMinsPerKm;
        private final double avgCardioHeartRate;
        private final double totalCardioMinutes;
        // Sets
        private final int totalSets;
        private final int totalExerciseEntries;

        MetricsSummary(
            int totalSessions, int strengthSessions, int cardioSessions,
            double totalRawVolumeKg, double totalAdjustedLoad, double avgSessionLoad,
            double avgRpe, double sessionsPerWeek, long totalDays, int currentStreak,
            Map<WorkoutType, Double> typePct, Map<MuscleGroup, Double> muscleVolume,
            MuscleGroup dominantMuscle,
            double totalKm, double avgPaceMinsPerKm, double avgCardioHeartRate,
            double totalCardioMinutes, int totalSets, int totalExerciseEntries
        ) {
            this.totalSessions       = totalSessions;
            this.strengthSessions    = strengthSessions;
            this.cardioSessions      = cardioSessions;
            this.totalRawVolumeKg    = totalRawVolumeKg;
            this.totalAdjustedLoad   = totalAdjustedLoad;
            this.avgSessionLoad      = avgSessionLoad;
            this.avgRpe              = avgRpe;
            this.sessionsPerWeek     = sessionsPerWeek;
            this.totalDays           = totalDays;
            this.currentStreak       = currentStreak;
            this.typePct             = Collections.unmodifiableMap(typePct);
            this.muscleVolume        = Collections.unmodifiableMap(muscleVolume);
            this.dominantMuscle      = dominantMuscle;
            this.totalKm             = totalKm;
            this.avgPaceMinsPerKm    = avgPaceMinsPerKm;
            this.avgCardioHeartRate  = avgCardioHeartRate;
            this.totalCardioMinutes  = totalCardioMinutes;
            this.totalSets           = totalSets;
            this.totalExerciseEntries = totalExerciseEntries;
        }

        // ----- Getters -----
        public int    getTotalSessions()       { return totalSessions; }
        public int    getStrengthSessions()    { return strengthSessions; }
        public int    getCardioSessions()      { return cardioSessions; }
        public double getTotalRawVolumeKg()    { return totalRawVolumeKg; }
        public double getTotalAdjustedLoad()   { return totalAdjustedLoad; }
        public double getAvgSessionLoad()      { return avgSessionLoad; }
        public double getAvgRpe()              { return avgRpe; }
        public double getSessionsPerWeek()     { return sessionsPerWeek; }
        public long   getTotalDays()           { return totalDays; }
        public int    getCurrentStreak()       { return currentStreak; }
        public Map<WorkoutType, Double>  getTypePct()       { return typePct; }
        public Map<MuscleGroup, Double>  getMuscleVolume()  { return muscleVolume; }
        public MuscleGroup               getDominantMuscle(){ return dominantMuscle; }
        public double getTotalKm()             { return totalKm; }
        public double getAvgPaceMinsPerKm()    { return avgPaceMinsPerKm; }
        public double getAvgCardioHeartRate()  { return avgCardioHeartRate; }
        public double getTotalCardioMinutes()  { return totalCardioMinutes; }
        public int    getTotalSets()           { return totalSets; }
        public int    getTotalExerciseEntries(){ return totalExerciseEntries; }

        /**
         * Formats the summary as a console-ready block.
         */
        public String toFormattedReport(String windowLabel) {
            String sep  = "═".repeat(64);
            String thin = "─".repeat(64);
            StringBuilder sb = new StringBuilder();

            sb.append("\n").append(sep).append("\n");
            sb.append("  TRAINING METRICS SUMMARY — ").append(windowLabel).append("\n");
            sb.append(sep).append("\n");

            // Volume
            sb.append("\n  ── Volume & Load ──────────────────────────────────────────\n");
            sb.append(String.format("  Total sessions       : %d  (%d strength, %d cardio)%n",
                totalSessions, strengthSessions, cardioSessions));
            sb.append(String.format("  Total raw volume     : %,.0f kg·reps%n", totalRawVolumeKg));
            sb.append(String.format("  Total adjusted load  : %.2f (dimensionless units)%n", totalAdjustedLoad));
            sb.append(String.format("  Avg load per session : %.2f%n", avgSessionLoad));
            sb.append(String.format("  Total working sets   : %d  across %d exercise entries%n",
                totalSets, totalExerciseEntries));

            // Intensity & Frequency
            sb.append("\n  ── Intensity & Frequency ──────────────────────────────────\n");
            sb.append(String.format("  Average RPE          : %.1f / 10%n", avgRpe));
            sb.append(String.format("  Sessions per week    : %.1f%n", sessionsPerWeek));
            sb.append(String.format("  Training span        : %d days%n", totalDays));
            sb.append(String.format("  Current streak       : %d consecutive day(s)%n", currentStreak));

            // Session type split
            sb.append("\n  ── Session Type Distribution ──────────────────────────────\n");
            typePct.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .forEach(e -> {
                    int bars = (int) (e.getValue() / 100.0 * 30);
                    sb.append(String.format("  %-14s  %s  %.0f%%%n",
                        e.getKey().getDisplayName(),
                        "█".repeat(bars) + "░".repeat(30 - bars),
                        e.getValue()));
                });

            // Muscle volume breakdown
            if (!muscleVolume.isEmpty()) {
                sb.append("\n  ── Muscle Group Volume Distribution ───────────────────────\n");
                double maxVol = muscleVolume.values().stream().mapToDouble(d->d).max().orElse(1);
                muscleVolume.entrySet().stream()
                    .sorted(Map.Entry.<MuscleGroup, Double>comparingByValue().reversed())
                    .forEach(e -> {
                        int bars = (int)(e.getValue() / maxVol * 25);
                        sb.append(String.format("  %-14s  %s  %.0f%n",
                            e.getKey().name(),
                            "▒".repeat(bars) + "░".repeat(25 - bars),
                            e.getValue()));
                    });
                if (dominantMuscle != null)
                    sb.append(String.format("  Dominant muscle      : %s%n", dominantMuscle.name()));
            }

            // Cardio stats
            if (cardioSessions > 0) {
                sb.append("\n  ── Cardio Stats ───────────────────────────────────────────\n");
                int pm = (int) avgPaceMinsPerKm;
                int ps = (int) Math.round((avgPaceMinsPerKm - pm) * 60);
                sb.append(String.format("  Total distance       : %.2f km%n",  totalKm));
                sb.append(String.format("  Avg pace             : %d:%02d /km%n", pm, ps));
                sb.append(String.format("  Avg heart rate       : %.0f bpm%n", avgCardioHeartRate));
                sb.append(String.format("  Total cardio time    : %.0f min (%.1f h)%n",
                    totalCardioMinutes, totalCardioMinutes / 60.0));
            }

            sb.append("\n").append(sep).append("\n");
            return sb.toString();
        }
    }
}
