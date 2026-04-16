package com.analytics.engine.analytics;

import com.analytics.engine.model.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates a personalised 7-day training plan for a PPL + 5K hybrid athlete.
 *
 * <h3>Planning Philosophy</h3>
 * <p>A rigid weekly template is overlaid with dynamic readiness adjustments:
 * <ol>
 *   <li>Start from the canonical PPL + 5K template (see {@link #DEFAULT_TEMPLATE}).</li>
 *   <li>For each day, check how much residual fatigue the primary muscle groups
 *       carry (from passed-in recent sessions).</li>
 *   <li>Swap or deload individual days where fatigue > 70 %, insert rest days
 *       where fatigue > 90 %.</li>
 * </ol>
 *
 * <h3>Default PPL + 5K Template (7 days)</h3>
 * <pre>
 *   Mon — PUSH     Tue — CARDIO_5K    Wed — PULL
 *   Thu — LEGS     Fri — CARDIO_5K    Sat — PUSH     Sun — REST
 * </pre>
 *
 * <h3>Output</h3>
 * <p>Returns a {@link WeekPlan} containing one {@link DayPlan} per day.  Each
 * {@link DayPlan} carries:
 * <ul>
 *   <li>The assigned {@link WorkoutType}</li>
 *   <li>Whether it has been deloaded vs the template</li>
 *   <li>Target volume percentage (100 % = normal, 50 % = deload)</li>
 *   <li>Coach notes explaining the adjustment</li>
 * </ul>
 */
public final class WeeklyPlannerService {

    // ----------------------------------------------------------------- Template

    /**
     * The canonical Mon-to-Sun session template for the PPL + 5K programme.
     * Index 0 = Monday, index 6 = Sunday.
     */
    private static final WorkoutType[] DEFAULT_TEMPLATE = {
        WorkoutType.PUSH,       // Mon
        WorkoutType.CARDIO_5K,  // Tue
        WorkoutType.PULL,       // Wed
        WorkoutType.LEGS,       // Thu
        WorkoutType.CARDIO_5K,  // Fri
        WorkoutType.PUSH,       // Sat
        WorkoutType.REST        // Sun
    };

    // ----------------------------------------------------------------- Thresholds

    /** Fatigue level above which the planned session is deloaded to 50 %. */
    private static final double DELOAD_THRESHOLD_PCT  = 70.0;

    /** Fatigue level above which the day is converted to a mandatory REST day. */
    private static final double REST_THRESHOLD_PCT    = 90.0;

    // ----------------------------------------------------------------- Dependencies

    private final RecoveryCalculator recoveryCalculator;

    public WeeklyPlannerService(RecoveryCalculator recoveryCalculator) {
        this.recoveryCalculator = recoveryCalculator;
    }

    // ================================================================= PUBLIC API

    /**
     * Builds a 7-day plan starting from {@code startDate} (typically today).
     *
     * @param user           athlete profile
     * @param recentSessions last 48–72 h of sessions for initial readiness baseline
     * @return a {@link WeekPlan} with one {@link DayPlan} per day
     */
    public WeekPlan buildWeekPlan(UserProfile user,
                                  List<WorkoutSession> recentSessions) {

        ReadinessReport baseline = recoveryCalculator.calculateReadiness(user, recentSessions);
        Map<MuscleGroup, Double> fatigue = new HashMap<>(baseline.getMuscleFatiguePercent());
        double cardioFatigue = baseline.getCardiovascularFatigue();

        LocalDate startDate = LocalDate.now();
        List<DayPlan> days  = new ArrayList<>();

        for (int i = 0; i < 7; i++) {
            LocalDate    date    = startDate.plusDays(i);
            DayOfWeek    dow     = date.getDayOfWeek();
            WorkoutType  planned = DEFAULT_TEMPLATE[dow.getValue() - 1]; // Mon=1

            DayPlan plan = computeDayPlan(date, planned, fatigue, cardioFatigue, user);
            days.add(plan);

            // Simulate fatigue decay over this day (24h) so tomorrow's decisions
            // account for today's planned session load
            applyDayDecay(fatigue, 24.0);
            cardioFatigue = applyDecayRaw(cardioFatigue, 36.0, 24.0);

            // Simulate fatigue added by today's planned session
            if (plan.getAssignedType().isStrengthSession() && plan.getVolumePercent() > 0) {
                addSimulatedStrengthFatigue(fatigue, plan.getAssignedType(),
                                            plan.getVolumePercent());
            } else if (plan.getAssignedType().isCardioSession() && plan.getVolumePercent() > 0) {
                cardioFatigue = Math.min(100, cardioFatigue + 20.0 * (plan.getVolumePercent() / 100.0));
            }
        }

        return new WeekPlan(user.getName(), startDate, days, baseline.getReadinessScore());
    }

    // ----------------------------------------------------------------- Day Logic

    private DayPlan computeDayPlan(LocalDate date,
                                   WorkoutType planned,
                                   Map<MuscleGroup, Double> fatigue,
                                   double cardioFatigue,
                                   UserProfile user) {

        if (planned == WorkoutType.REST) {
            return new DayPlan(date, planned, planned, 0, false,
                "Scheduled rest day — active recovery only.");
        }

        if (planned.isStrengthSession()) {
            double primaryFatigue = getPrimaryFatigue(planned, fatigue);

            if (primaryFatigue >= REST_THRESHOLD_PCT) {
                return new DayPlan(date, planned, WorkoutType.REST, 0, true,
                    String.format("⛔ Primary muscle fatigue %.0f%% — MANDATORY REST. " +
                                  "Original plan (%s) postponed.", primaryFatigue,
                                  planned.getDisplayName()));
            }

            if (primaryFatigue >= DELOAD_THRESHOLD_PCT) {
                return new DayPlan(date, planned, planned, 50, true,
                    String.format("🔴 DELOAD — Primary fatigue %.0f%%. " +
                                  "Reduce to 50%% volume: drop weight, keep technique.", primaryFatigue));
            }

            if (primaryFatigue >= 40.0) {
                return new DayPlan(date, planned, planned, 85, false,
                    String.format("🟠 Moderate fatigue (%.0f%%). " +
                                  "Reduce to 85%% volume — skip last set if RPE > 9.", primaryFatigue));
            }

            if (primaryFatigue < 20.0) {
                return new DayPlan(date, planned, planned, 105, false,
                    String.format("🟢 Fresh (%.0f%% fatigue). " +
                                  "Progressive overload: +5%% weight or +1 set on primary compound.",
                                  primaryFatigue));
            }

            return new DayPlan(date, planned, planned, 100, false,
                String.format("🟡 Maintain normal volume (%.0f%% fatigue).", primaryFatigue));
        }

        // Cardio day
        if (cardioFatigue >= REST_THRESHOLD_PCT) {
            return new DayPlan(date, planned, WorkoutType.REST, 0, true,
                String.format("⛔ Cardiovascular fatigue %.0f%% — replace 5K with 20-min walk.", cardioFatigue));
        }
        if (cardioFatigue >= DELOAD_THRESHOLD_PCT) {
            return new DayPlan(date, planned, planned, 60, true,
                String.format("🔴 Cardio fatigue %.0f%% — reduce to Zone 2 only, cap at 3 km.", cardioFatigue));
        }
        if (cardioFatigue < 20.0) {
            return new DayPlan(date, planned, planned, 100, false,
                "🟢 Cardiovascular system fresh — can target a new 5K time or intervals.");
        }
        return new DayPlan(date, planned, planned, 100, false,
            String.format("🟡 Normal 5K run — stay in Zone 2–3 (cardio fatigue %.0f%%).", cardioFatigue));
    }

    // ----------------------------------------------------------------- Fatigue helpers

    private static double getPrimaryFatigue(WorkoutType type,
                                            Map<MuscleGroup, Double> fatigue) {
        return switch (type) {
            case PUSH -> Math.max(
                fatigue.getOrDefault(MuscleGroup.CHEST, 0.0),
                fatigue.getOrDefault(MuscleGroup.SHOULDERS, 0.0));
            case PULL -> fatigue.getOrDefault(MuscleGroup.BACK, 0.0);
            case LEGS -> Math.max(
                fatigue.getOrDefault(MuscleGroup.QUADS, 0.0),
                fatigue.getOrDefault(MuscleGroup.HAMSTRINGS, 0.0));
            default   -> 0.0;
        };
    }

    private static void applyDayDecay(Map<MuscleGroup, Double> fatigue, double hours) {
        fatigue.replaceAll((mg, f) -> applyDecayRaw(f, mg.getRecoveryHalfLifeHours(), hours));
    }

    private static double applyDecayRaw(double initial, double halfLife, double hours) {
        if (initial <= 0) return 0.0;
        double lambda = Math.log(2) / halfLife;
        return initial * Math.exp(-lambda * hours);
    }

    private static void addSimulatedStrengthFatigue(Map<MuscleGroup, Double> fatigue,
                                                    WorkoutType type,
                                                    double volumePct) {
        double factor = volumePct / 100.0;
        // Approximate average session adds ~40% to primary muscles at 100% volume
        switch (type) {
            case PUSH -> {
                fatigue.merge(MuscleGroup.CHEST,     40.0 * factor, (a, b) -> Math.min(100, a + b));
                fatigue.merge(MuscleGroup.SHOULDERS, 30.0 * factor, (a, b) -> Math.min(100, a + b));
                fatigue.merge(MuscleGroup.TRICEPS,   25.0 * factor, (a, b) -> Math.min(100, a + b));
            }
            case PULL -> {
                fatigue.merge(MuscleGroup.BACK,      45.0 * factor, (a, b) -> Math.min(100, a + b));
                fatigue.merge(MuscleGroup.BICEPS,    30.0 * factor, (a, b) -> Math.min(100, a + b));
            }
            case LEGS -> {
                fatigue.merge(MuscleGroup.QUADS,     55.0 * factor, (a, b) -> Math.min(100, a + b));
                fatigue.merge(MuscleGroup.HAMSTRINGS,45.0 * factor, (a, b) -> Math.min(100, a + b));
                fatigue.merge(MuscleGroup.GLUTES,    35.0 * factor, (a, b) -> Math.min(100, a + b));
                fatigue.merge(MuscleGroup.CALVES,    20.0 * factor, (a, b) -> Math.min(100, a + b));
            }
            default -> { /* REST / CARDIO — no strength fatigue */ }
        }
    }

    // ================================================================= Inner Types

    /**
     * Represents the full 7-day training plan for one athlete.
     */
    public static final class WeekPlan {

        private final String     athleteName;
        private final LocalDate  startDate;
        private final List<DayPlan> days;
        private final int        baselineReadiness;

        WeekPlan(String athleteName, LocalDate startDate,
                 List<DayPlan> days, int baselineReadiness) {
            this.athleteName       = athleteName;
            this.startDate         = startDate;
            this.days              = Collections.unmodifiableList(days);
            this.baselineReadiness = baselineReadiness;
        }

        public String          getAthleteName()       { return athleteName; }
        public LocalDate       getStartDate()         { return startDate; }
        public List<DayPlan>   getDays()              { return days; }
        public int             getBaselineReadiness() { return baselineReadiness; }

        /** Total adjusted training days (non-rest) planned for the week. */
        public long plannedTrainingDays() {
            return days.stream()
                .filter(d -> d.getAssignedType() != WorkoutType.REST)
                .count();
        }

        /** How many template days were adjusted (deloaded or converted to REST). */
        public long adjustedDays() {
            return days.stream().filter(DayPlan::isAdjusted).count();
        }

        /**
         * Produces a formatted console-ready weekly schedule string.
         */
        public String toFormattedPlan() {
            DateTimeFormatter df = DateTimeFormatter.ofPattern("EEE dd-MMM");
            StringBuilder sb = new StringBuilder();

            sb.append("\n╔══════════════════════════════════════════════════════════════════╗\n");
            sb.append(String.format("║  7-DAY TRAINING PLAN  —  %-38s║%n", athleteName));
            sb.append(String.format("║  Week of %-55s║%n",
                startDate.format(df) + "   (Baseline readiness: " + baselineReadiness + "/100)"));
            sb.append("╚══════════════════════════════════════════════════════════════════╝\n");

            for (DayPlan d : days) {
                String vol = d.getAssignedType() == WorkoutType.REST
                    ? "  REST  "
                    : String.format("%3d%% vol", d.getVolumePercent());

                String adj = d.isAdjusted() ? " [ADJUSTED]" : "           ";

                sb.append(String.format("\n  %s  │  %-14s │ %s %s%n",
                    d.getDate().format(df),
                    d.getAssignedType().getDisplayName(),
                    vol, adj));
                sb.append(String.format("              │  %s%n", d.getCoachNote()));
            }

            sb.append(String.format(
                "%n  Training days : %d/7    Adjusted : %d    Rest days : %d%n",
                plannedTrainingDays(), adjustedDays(),
                7 - plannedTrainingDays()));

            return sb.toString();
        }
    }

    /**
     * Represents the plan for a single training day.
     */
    public static final class DayPlan {

        private final LocalDate  date;
        private final WorkoutType templateType;   // what was originally planned
        private final WorkoutType assignedType;   // what we're actually doing
        private final int        volumePercent;   // 0 = rest, 50 = deload, 100 = normal, 105 = overload
        private final boolean    adjusted;        // true if different from template
        private final String     coachNote;

        DayPlan(LocalDate date, WorkoutType templateType, WorkoutType assignedType,
                int volumePercent, boolean adjusted, String coachNote) {
            this.date         = date;
            this.templateType = templateType;
            this.assignedType = assignedType;
            this.volumePercent = volumePercent;
            this.adjusted     = adjusted;
            this.coachNote    = coachNote;
        }

        public LocalDate   getDate()          { return date; }
        public WorkoutType getTemplateType()  { return templateType; }
        public WorkoutType getAssignedType()  { return assignedType; }
        public int         getVolumePercent() { return volumePercent; }
        public boolean     isAdjusted()       { return adjusted; }
        public String      getCoachNote()     { return coachNote; }
    }
}
