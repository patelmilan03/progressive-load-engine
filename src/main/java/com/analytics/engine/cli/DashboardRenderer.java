package com.analytics.engine.cli;

import com.analytics.engine.analytics.*;
import com.analytics.engine.dao.*;
import com.analytics.engine.model.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the post-session analytics dashboard to stdout after the user
 * has logged (or chosen not to log) a workout.
 *
 * <p>Orchestrates all analytics services and presents results in a structured,
 * readable format suited to a terminal window.
 */
public final class DashboardRenderer {

    private final InputReader            io;
    private final HypertrophySessionDAO  strengthDao;
    private final CardioSessionDAO       cardioDao;
    private final PersonalRecordDAO      prDao;
    private final RecoveryCalculator     recoveryCalc;
    private final ProgressiveOverloadAdvisor advisor;
    private final WeeklyPlannerService   planner;
    private final PersonalRecordTracker  prTracker;

    public DashboardRenderer(InputReader io,
                             HypertrophySessionDAO strengthDao,
                             CardioSessionDAO cardioDao,
                             PersonalRecordDAO prDao) {
        this.io          = io;
        this.strengthDao = strengthDao;
        this.cardioDao   = cardioDao;
        this.prDao       = prDao;
        this.recoveryCalc = new RecoveryCalculator();
        this.advisor     = new ProgressiveOverloadAdvisor(recoveryCalc);
        this.planner     = new WeeklyPlannerService(recoveryCalc);
        this.prTracker   = new PersonalRecordTracker(prDao);
    }

    // ================================================================= Main render

    /**
     * Runs all analytics and renders the full dashboard for the user.
     *
     * @param user         the athlete profile
     * @param newSession   the session just logged (may be null if REST or no log)
     */
    public void render(UserProfile user, WorkoutSession newSession) throws SQLException {

        // ── Load history from DB ─────────────────────────────────────────
        List<HypertrophySession> allStrength = strengthDao.findByUserId(user.getId());
        List<CardioSession>      allCardio   = cardioDao.findByUserId(user.getId());

        List<WorkoutSession> allSessions = new ArrayList<>();
        allSessions.addAll(allStrength);
        allSessions.addAll(allCardio);

        List<HypertrophySession> recent48hStrength =
            strengthDao.findSessionsInLastNHours(user.getId(), 48);
        List<CardioSession> recent48hCardio =
            cardioDao.findSessionsInLastNHours(user.getId(), 48);

        List<WorkoutSession> window48h = new ArrayList<>();
        window48h.addAll(recent48hStrength);
        window48h.addAll(recent48hCardio);

        // ── Readiness score (always shown) ───────────────────────────────
        renderReadinessScore(user, window48h);

        // ── New PRs (shown only when a strength session was just logged) ─
        if (newSession instanceof HypertrophySession hs) {
            List<PersonalRecord> newPRs = prTracker.detectAndSavePRs(
                List.of(hs), user);
            if (!newPRs.isEmpty()) {
                System.out.println(PersonalRecordTracker.formatNewPRs(newPRs, user));
            }
        }

        // ── Ask which additional sections they want to see ───────────────
        io.blank();
        io.divider();
        io.header("What would you like to see?");

        String[] sections = {
            "7-Day Training Plan  " + InputReader.DIM + "(recommended sessions for the week)" + InputReader.RESET,
            "Overload Advisory    " + InputReader.DIM + "(volume targets per workout type)" + InputReader.RESET,
            "ACWR Injury Risk     " + InputReader.DIM + "(acute vs chronic workload ratio)" + InputReader.RESET,
            "Metrics Summary      " + InputReader.DIM + "(volume, frequency, streak, cardio)" + InputReader.RESET,
            "PR Board             " + InputReader.DIM + "(personal records by exercise)" + InputReader.RESET,
            "All of the above" ,
            "Nothing — I'm done"
        };

        int choice = io.readMenu(sections);

        switch (choice) {
            case 0 -> renderWeekPlan(user, window48h);
            case 1 -> renderOverloadAdvisory(user, window48h, allSessions);
            case 2 -> renderACWR(user, allSessions);
            case 3 -> renderMetrics(user, allSessions);
            case 4 -> renderPRBoard(user);
            case 5 -> {
                renderWeekPlan(user, window48h);
                io.pressEnterToContinue();
                renderOverloadAdvisory(user, window48h, allSessions);
                io.pressEnterToContinue();
                renderACWR(user, allSessions);
                io.pressEnterToContinue();
                renderMetrics(user, allSessions);
                io.pressEnterToContinue();
                renderPRBoard(user);
            }
            case 6 -> { /* exit */ }
        }

        io.blank();
        io.divider();
        io.success("All done. Keep training smart, " + user.getName().split(" ")[0] + "! 🏋️");
        io.blank();
    }

    // ================================================================= Sections

    private void renderReadinessScore(UserProfile user,
                                      List<WorkoutSession> window48h) {
        ReadinessReport report = recoveryCalc.calculateReadiness(user, window48h);
        int score = report.getReadinessScore();
        ReadinessReport.ScoreBand band = report.getScoreBand();

        io.blank();
        System.out.println(InputReader.BOLD + "  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + InputReader.RESET);
        System.out.printf("  %s READINESS SCORE   %s%s%d / 100%s   %s%s%s%n",
            band.getIcon(),
            InputReader.BOLD, scoreColour(score), score, InputReader.RESET,
            InputReader.DIM, band.getLabel(), InputReader.RESET);
        System.out.println(InputReader.BOLD + "  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" + InputReader.RESET);
        io.blank();

        // Score bar
        int filled = score / 5;
        String bar = InputReader.GREEN + "█".repeat(filled) + InputReader.DIM
            + "░".repeat(20 - filled) + InputReader.RESET;
        System.out.println("  [" + bar + "]  " + score + "%");
        io.blank();

        // Primary recommendation
        System.out.println("  " + InputReader.BOLD + report.getPrimaryRecommendation() + InputReader.RESET);
        io.blank();

        // Top 3 muscle fatigue lines (only if significant)
        report.getMuscleFatiguePercent().entrySet().stream()
            .filter(e -> e.getValue() > 15.0)
            .sorted(Map.Entry.<MuscleGroup, Double>comparingByValue().reversed())
            .limit(4)
            .forEach(e -> {
                int bars = (int)(e.getValue() / 100.0 * 20);
                String fatBar = fatigueColour(e.getValue())
                    + "█".repeat(bars) + InputReader.DIM
                    + "░".repeat(20 - bars) + InputReader.RESET;
                System.out.printf("  %-13s [%s]  %.0f%%%n",
                    e.getKey().name(), fatBar, e.getValue());
            });
        if (report.getCardiovascularFatigue() > 15.0) {
            int bars = (int)(report.getCardiovascularFatigue() / 100.0 * 20);
            String fatBar = fatigueColour(report.getCardiovascularFatigue())
                + "█".repeat(bars) + InputReader.DIM
                + "░".repeat(20 - bars) + InputReader.RESET;
            System.out.printf("  %-13s [%s]  %.0f%%%n",
                "Cardio", fatBar, report.getCardiovascularFatigue());
        }

        // Insights
        if (!report.getInsights().isEmpty()) {
            io.blank();
            report.getInsights().forEach(ins -> System.out.println("  " + ins));
        }
    }

    private void renderWeekPlan(UserProfile user, List<WorkoutSession> window48h) {
        WeeklyPlannerService.WeekPlan plan = planner.buildWeekPlan(user, window48h);
        System.out.println(plan.toFormattedPlan());
    }

    private void renderOverloadAdvisory(UserProfile user,
                                        List<WorkoutSession> window48h,
                                        List<WorkoutSession> allSessions) {
        System.out.println(advisor.generateAdvisory(user, window48h, allSessions));
    }

    private void renderACWR(UserProfile user, List<WorkoutSession> allSessions) {
        AcuteChronicWorkloadRatio.AcwrReport acwr =
            AcuteChronicWorkloadRatio.compute(user, allSessions);
        System.out.println(acwr.toFormattedReport());
    }

    private void renderMetrics(UserProfile user, List<WorkoutSession> allSessions) {
        WorkoutMetricsAggregator.MetricsSummary m =
            WorkoutMetricsAggregator.aggregate(allSessions, user);
        System.out.println(m.toFormattedReport("All Time"));
    }

    private void renderPRBoard(UserProfile user) throws SQLException {
        List<PersonalRecord> board = prDao.findCurrentPRBoard(user.getId());
        System.out.println(PersonalRecordTracker.formatPRBoard(user, board));
    }

    // ================================================================= Colour helpers

    private String scoreColour(int score) {
        if (score >= 80) return InputReader.GREEN;
        if (score >= 65) return InputReader.CYAN;
        if (score >= 50) return InputReader.YELLOW;
        return InputReader.RED;
    }

    private String fatigueColour(double pct) {
        if (pct >= 80) return InputReader.RED;
        if (pct >= 50) return InputReader.YELLOW;
        return InputReader.GREEN;
    }
}
