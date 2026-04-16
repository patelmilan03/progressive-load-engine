package com.analytics.engine.cli;

import com.analytics.engine.model.*;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Guides the user through logging a single training session interactively.
 *
 * <h3>Flow by session type</h3>
 *
 * <b>PUSH / PULL / LEGS:</b>
 * <ol>
 *   <li>Enter session notes (optional)</li>
 *   <li>Loop: enter exercise name (or "done") → muscle → sets → reps → weight → RPE</li>
 *   <li>Each added exercise is confirmed with a summary line</li>
 * </ol>
 *
 * <b>CARDIO_5K:</b>
 * <ol>
 *   <li>Distance (km)</li>
 *   <li>Pace (mm:ss /km)</li>
 *   <li>Average heart rate</li>
 *   <li>Peak heart rate</li>
 *   <li>Duration (minutes)</li>
 * </ol>
 *
 * <b>REST:</b>
 * <p>Logs a REST entry with no additional prompts.
 */
public final class SessionLoggerFlow {

    private final InputReader io;

    public SessionLoggerFlow(InputReader io) {
        this.io = io;
    }

    // ================================================================= PUBLIC API

    /**
     * Asks the user which type of session they did and builds the appropriate
     * {@link WorkoutSession} object. Returns an {@link Optional#empty()} if the
     * user chose to log a REST day (no object to persist beyond the parent row,
     * and this engine currently skips REST logging).
     *
     * @param userId the owning athlete's database id
     * @return the constructed session, or empty for REST
     */
    public Optional<WorkoutSession> run(long userId) {
        io.header("What did you do today?");

        String[] options = {
            "Push Day     " + InputReader.DIM + "(Chest · Shoulders · Triceps)" + InputReader.RESET,
            "Pull Day     " + InputReader.DIM + "(Back · Biceps · Rear Delts)" + InputReader.RESET,
            "Legs Day     " + InputReader.DIM + "(Quads · Hamstrings · Glutes · Calves)" + InputReader.RESET,
            "5K Run       " + InputReader.DIM + "(Cardio session)" + InputReader.RESET,
            "Rest Day     " + InputReader.DIM + "(Full rest or light walk)" + InputReader.RESET,
        };

        int choice = io.readMenu(options);

        return switch (choice) {
            case 0 -> Optional.of(logStrengthSession(userId, WorkoutType.PUSH));
            case 1 -> Optional.of(logStrengthSession(userId, WorkoutType.PULL));
            case 2 -> Optional.of(logStrengthSession(userId, WorkoutType.LEGS));
            case 3 -> Optional.of(logCardioSession(userId));
            case 4 -> { logRestDay(); yield Optional.empty(); }
            default -> Optional.empty();
        };
    }

    // ================================================================= Strength

    private HypertrophySession logStrengthSession(long userId, WorkoutType type) {
        io.blank();
        io.divider();
        System.out.println(InputReader.BOLD + "  " + type.getDisplayName() + InputReader.RESET);
        io.blank();

        // Determine the default primary muscle for this PPL day
        MuscleGroup defaultPrimary = switch (type) {
            case PUSH -> MuscleGroup.CHEST;
            case PULL -> MuscleGroup.BACK;
            case LEGS -> MuscleGroup.QUADS;
            default   -> MuscleGroup.CHEST;
        };

        String notes = io.readStringWithDefault("Session notes (optional):", "");

        HypertrophySession session = new HypertrophySession(
            userId, type, defaultPrimary,
            LocalDateTime.now(), notes.isEmpty() ? null : notes
        );

        io.blank();
        io.info("Now enter each exercise. Type " + InputReader.BOLD + "done" + InputReader.RESET + " when finished.");
        io.divider();

        int exerciseCount = 0;
        while (true) {
            io.blank();
            String name = io.readStringWithDefault(
                "Exercise name" + InputReader.DIM + " (or 'done' to finish)" + InputReader.RESET + ":", "");

            if (name.equalsIgnoreCase("done") || name.isEmpty()) {
                if (exerciseCount == 0) {
                    io.warn("You haven't added any exercises yet. Add at least one.");
                    continue;
                }
                break;
            }

            MuscleGroup muscle = readMuscleGroup(type);
            int    sets   = io.readInt   ("Sets:",                     1, 20);
            int    reps   = io.readInt   ("Reps per set:",             1, 100);
            double weight = io.readDouble("Weight (kg, use 0 for bodyweight):", 0, 500);
            double rpe    = io.readDouble("RPE (Rate of Perceived Exertion):",  1, 10);

            ExerciseSet ex = ExerciseSet.builder(0, name, muscle)
                .sets(sets).reps(reps).weightKg(weight).rpe(rpe).build();
            session.addExercise(ex);
            exerciseCount++;

            io.success(String.format("Added: %s  %dx%d @ %.1f kg  RPE %.1f  → e1RM %.1f kg",
                name, sets, reps, weight, rpe, PersonalRecord.epley(weight, reps)));
        }

        io.blank();
        io.divider();
        io.success(String.format("Session logged: %d exercises, %d sets, %.0f kg total volume.",
            session.getExercises().size(), session.getTotalSets(), session.getTotalRawVolume()));

        return session;
    }

    // ================================================================= Cardio

    private CardioSession logCardioSession(long userId) {
        io.blank();
        io.divider();
        System.out.println(InputReader.BOLD + "  5K Run" + InputReader.RESET);
        io.blank();

        String notes  = io.readStringWithDefault("Session notes (optional):", "");
        double dist   = io.readDoubleWithDefault ("Distance (km):", 5.0, 0.5, 50.0);
        double pace   = io.readPace              ("Average pace per km:");
        int    avgHr  = io.readInt               ("Average heart rate (bpm):", 80, 220);
        int    maxHr  = io.readInt               ("Highest heart rate hit (bpm):", avgHr, 220);
        double dur    = io.readDoubleWithDefault ("Total duration (minutes):", dist * pace, 1, 600);

        CardioSession session = new CardioSession(
            userId, LocalDateTime.now(),
            dist, pace, avgHr, maxHr, dur,
            notes.isEmpty() ? null : notes
        );

        io.blank();
        io.divider();
        int pm = (int) pace;
        int ps = (int) Math.round((pace - pm) * 60);
        io.success(String.format("Run logged: %.2f km  %d:%02d /km  avg %d bpm  %.0f min.",
            dist, pm, ps, avgHr, dur));

        return session;
    }

    // ================================================================= Rest

    private void logRestDay() {
        io.blank();
        io.success("Rest day noted. Recovery is where the gains are made. 💤");
    }

    // ================================================================= Muscle picker

    /**
     * Shows a filtered list of muscle groups relevant to this PPL day and
     * returns the user's selection. Falls back to showing all groups if the
     * user types '?' to escape the filter.
     */
    private MuscleGroup readMuscleGroup(WorkoutType type) {
        MuscleGroup[] relevant = relevantMuscles(type);

        io.blank();
        io.info("Primary muscle targeted by this exercise:");
        io.blank();

        for (int i = 0; i < relevant.length; i++) {
            System.out.printf("  " + InputReader.BOLD + "%d" + InputReader.RESET + "  %-14s%n",
                i + 1, relevant[i].name());
        }
        System.out.printf("  " + InputReader.BOLD + "%d" + InputReader.RESET + "  Other (show all)%n",
            relevant.length + 1);
        io.blank();

        int choice = io.readInt("Muscle →", 1, relevant.length + 1) - 1;

        if (choice < relevant.length) return relevant[choice];

        // Show all muscle groups
        MuscleGroup[] all = MuscleGroup.values();
        io.blank();
        io.info("All muscle groups:");
        for (int i = 0; i < all.length; i++) {
            System.out.printf("  " + InputReader.BOLD + "%d" + InputReader.RESET + "  %s%n",
                i + 1, all[i].name());
        }
        io.blank();
        int allChoice = io.readInt("Muscle →", 1, all.length) - 1;
        return all[allChoice];
    }

    private MuscleGroup[] relevantMuscles(WorkoutType type) {
        return switch (type) {
            case PUSH -> new MuscleGroup[]{ MuscleGroup.CHEST, MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS, MuscleGroup.CORE };
            case PULL -> new MuscleGroup[]{ MuscleGroup.BACK,  MuscleGroup.BICEPS,    MuscleGroup.SHOULDERS };
            case LEGS -> new MuscleGroup[]{ MuscleGroup.QUADS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES, MuscleGroup.CORE };
            default   -> MuscleGroup.values();
        };
    }
}
