package com.analytics.engine.util;

import com.analytics.engine.model.*;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Stateless utility class for rendering domain objects as formatted
 * console output.  All methods are static — this class is not instantiated.
 *
 * <p>Keeps formatting concerns out of the domain model (Single Responsibility).
 */
public final class ConsoleFormatter {

    private static final String SEPARATOR = "═".repeat(68);
    private static final String THIN_SEP  = "─".repeat(68);
    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("EEE dd-MMM-yyyy  HH:mm");

    private ConsoleFormatter() {}

    // ================================================================= HEADER

    public static void printBanner() {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  ⚡  PROGRESSIVE LOAD & RECOVERY ANALYTICS ENGINE  ⚡");
        System.out.println("       Java 17 · SQLite · OOP · Domain-Driven Design");
        System.out.println(SEPARATOR);
    }

    // ================================================================= USER PROFILE

    public static void printUserProfile(UserProfile user) {
        System.out.println("\n" + THIN_SEP);
        System.out.println("  ATHLETE PROFILE");
        System.out.println(THIN_SEP);
        System.out.printf("  Name               : %s%n",  user.getName());
        System.out.printf("  Height             : %.1f cm  (%.1f ft)%n",
            user.getHeightCm(), user.getHeightCm() / 30.48);
        System.out.printf("  Body Weight        : %.1f kg%n", user.getWeightKg());
        System.out.printf("  Age                : %d yrs%n",  user.getAge());
        System.out.printf("  BMI                : %.1f%n",    user.getBMI());
        System.out.printf("  Resting HR         : %d bpm%n",  user.getRestingHeartRate());
        System.out.printf("  Max HR             : %d bpm%n",  user.getMaxHeartRate());
        System.out.printf("  HR Reserve (HRR)   : %.0f bpm%n",user.getHeartRateReserve());
        System.out.printf("  Training Exp       : %.1f years%n", user.getTrainingExperienceYears());
        System.out.printf("  Volume Tolerance   : %.2fx baseline%n",
            user.getVolumeToleranceFactor());
    }

    // ================================================================= SESSIONS

    public static void printSessionHistory(List<WorkoutSession> sessions, UserProfile user) {
        System.out.println("\n" + THIN_SEP);
        System.out.printf("  SEEDED WORKOUT HISTORY  (%d sessions)%n", sessions.size());
        System.out.println(THIN_SEP);
        System.out.printf("  %-4s  %-20s  %-13s  %-28s  %s%n",
            "#", "Timestamp", "Type", "Detail", "Load");
        System.out.println("  " + "─".repeat(64));

        for (int i = 0; i < sessions.size(); i++) {
            WorkoutSession s = sessions.get(i);
            double load = s.calculateLoad(user);

            if (s instanceof HypertrophySession hs) {
                System.out.printf("  %-4d  %-20s  %-13s  %-28s  %.2f%n",
                    i + 1,
                    hs.getSessionTimestamp().format(DT_FMT),
                    hs.getPplDay().getDisplayName(),
                    hs.getExercises().size() + " exercises, " + hs.getTotalSets() + " sets",
                    load);

            } else if (s instanceof CardioSession cs) {
                System.out.printf("  %-4d  %-20s  %-13s  %-28s  %.2f%n",
                    i + 1,
                    cs.getSessionTimestamp().format(DT_FMT),
                    "5K Run",
                    String.format("%.2f km @ %d bpm avg", cs.getDistanceKm(),
                                  cs.getAvgHeartRate()),
                    load);
            }
        }
    }

    public static void printHypertrophySessionDetail(HypertrophySession s, UserProfile user) {
        System.out.println("\n" + THIN_SEP);
        System.out.printf("  SESSION DETAIL: %s — %s%n",
            s.getPplDay().getDisplayName(),
            s.getSessionTimestamp().format(DT_FMT));
        System.out.println(THIN_SEP);
        System.out.printf("  %-28s  %-8s  %-12s  %-6s  %-14s  %s%n",
            "Exercise", "Sets×Reps", "Weight (kg)", "RPE", "Muscle", "Adj.VL");
        System.out.println("  " + "─".repeat(80));

        for (ExerciseSet ex : s.getExercises()) {
            System.out.printf("  %s%n", ex);
        }

        System.out.printf("%n  Total Raw Volume : %.0f kg·reps%n", s.getTotalRawVolume());
        System.out.printf("  Session Load     : %.4f%n", s.calculateLoad(user));
    }

    // ================================================================= SEPARATOR

    public static void section(String title) {
        System.out.println("\n" + SEPARATOR);
        System.out.println("  " + title.toUpperCase());
        System.out.println(SEPARATOR);
    }

    public static void info(String msg) {
        System.out.println("  » " + msg);
    }
}
