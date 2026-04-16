package com.analytics.engine.analytics;

import com.analytics.engine.dao.PersonalRecordDAO;
import com.analytics.engine.model.*;

import java.sql.SQLException;
import java.util.*;

/**
 * Scans a list of {@link HypertrophySession} objects for exercises that
 * represent new personal records, persists them, and produces a formatted
 * PR board.
 *
 * <h3>PR Detection Logic</h3>
 * <ol>
 *   <li>For each {@link ExerciseSet} in every session, compute the
 *       Epley e1RM: {@code weight × (1 + min(reps, 12) / 30)}.</li>
 *   <li>Query the database for the current best e1RM for that exercise.</li>
 *   <li>If the new e1RM > existing best (or no record exists), save the
 *       new {@link PersonalRecord} and add it to the "new PRs" list.</li>
 * </ol>
 *
 * <h3>Deduplication</h3>
 * <p>If multiple sets within the same session exceed the current PR,
 * only the highest e1RM is saved (to avoid flooding the PR table).
 */
public final class PersonalRecordTracker {

    private final PersonalRecordDAO prDao;

    public PersonalRecordTracker(PersonalRecordDAO prDao) {
        this.prDao = prDao;
    }

    // ================================================================= PUBLIC API

    /**
     * Scans all provided sessions, detects new PRs, persists them, and
     * returns the list of newly broken records (empty if none).
     *
     * @param sessions  strength sessions to scan (cardio sessions are ignored)
     * @param user      athlete profile (for Wilks calculation)
     * @return list of {@link PersonalRecord} objects that are new PRs, newest first
     */
    public List<PersonalRecord> detectAndSavePRs(
            List<HypertrophySession> sessions,
            UserProfile user) throws SQLException {

        List<PersonalRecord> newPRs = new ArrayList<>();

        // Collect best candidate per exercise across all sessions
        // (avoids saving multiple PRs for the same exercise in one batch)
        Map<String, ExerciseCandidate> bestCandidates = new HashMap<>();

        for (HypertrophySession session : sessions) {
            for (ExerciseSet ex : session.getExercises()) {
                double e1RM = PersonalRecord.epley(ex.getWeightKg(), ex.getReps());
                String key  = ex.getExerciseName();

                ExerciseCandidate existing = bestCandidates.get(key);
                if (existing == null || e1RM > existing.e1RM) {
                    bestCandidates.put(key, new ExerciseCandidate(
                        ex, session.getSessionTimestamp(), e1RM));
                }
            }
        }

        // Compare against DB records and save new PRs
        for (Map.Entry<String, ExerciseCandidate> entry : bestCandidates.entrySet()) {
            ExerciseCandidate candidate = entry.getValue();
            String exerciseName = entry.getKey();

            Optional<PersonalRecord> currentPR =
                prDao.findCurrentPR(user.getId(), exerciseName);

            boolean isNewPR = currentPR.isEmpty()
                || candidate.e1RM > currentPR.get().getEstimated1RmKg();

            if (isNewPR) {
                PersonalRecord pr = new PersonalRecord(
                    user.getId(),
                    exerciseName,
                    candidate.ex.getWeightKg(),
                    candidate.ex.getReps(),
                    candidate.ex.getRpe(),
                    candidate.achievedAt,
                    candidate.ex.getPrimaryMuscle(),
                    user.getWeightKg()
                );
                prDao.save(pr);
                newPRs.add(pr);
            }
        }

        // Sort by e1RM descending (most impressive PR first)
        newPRs.sort(Comparator.comparingDouble(PersonalRecord::getEstimated1RmKg).reversed());
        return newPRs;
    }

    /**
     * Formats the complete PR board for the athlete as a printable string.
     *
     * @param user       athlete profile (for relative strength / standard labels)
     * @param prBoard    list of PRs from {@link PersonalRecordDAO#findCurrentPRBoard(long)}
     * @return formatted multi-line string
     */
    public static String formatPRBoard(UserProfile user, List<PersonalRecord> prBoard) {
        if (prBoard.isEmpty()) {
            return "\n  No personal records logged yet.\n";
        }

        StringBuilder sb = new StringBuilder();
        String sep  = "═".repeat(90);
        String thin = "─".repeat(90);

        sb.append("\n").append(sep).append("\n");
        sb.append("  PERSONAL RECORDS BOARD  —  ").append(user.getName())
          .append("  (").append(user.getWeightKg()).append(" kg)\n");
        sb.append(sep).append("\n");
        sb.append(String.format("  %-28s  %-12s  %5s×%-4s  %7s  %8s  %-14s  %s%n",
            "Exercise", "Muscle", "Weight", "Reps",
            "e1RM", "Wilks", "Standard", "Date"));
        sb.append("  ").append(thin).append("\n");

        // Group by muscle group for organised display
        Map<MuscleGroup, List<PersonalRecord>> byMuscle = new LinkedHashMap<>();
        for (PersonalRecord pr : prBoard) {
            byMuscle.computeIfAbsent(pr.getPrimaryMuscle(), k -> new ArrayList<>()).add(pr);
        }

        byMuscle.forEach((mg, prs) -> {
            sb.append(String.format("  ── %s ──%n", mg.name()));
            for (PersonalRecord pr : prs) {
                sb.append(String.format(
                    "  %-28s  %-12s  %4.1f×%-4d  %6.1f kg  %8.1f  %-14s  %s%n",
                    pr.getExerciseName(),
                    pr.getPrimaryMuscle().name(),
                    pr.getWeightKg(), pr.getReps(),
                    pr.getEstimated1RmKg(),
                    pr.getWilksScore(),
                    pr.getStrengthStandard(user.getWeightKg()),
                    pr.getAchievedAt().toLocalDate()
                ));
            }
        });

        sb.append(sep).append("\n");
        return sb.toString();
    }

    /**
     * Formats a "New PR!" announcement for a list of newly broken records.
     */
    public static String formatNewPRs(List<PersonalRecord> newPRs, UserProfile user) {
        if (newPRs.isEmpty()) return "  No new PRs this session.\n";

        StringBuilder sb = new StringBuilder();
        sb.append("\n  🏆 NEW PERSONAL RECORDS THIS WEEK!\n");
        sb.append("  ").append("─".repeat(60)).append("\n");
        for (PersonalRecord pr : newPRs) {
            sb.append(String.format(
                "  🏅 %-28s  e1RM: %5.1f kg  Wilks: %5.1f  (%s)%n",
                pr.getExerciseName(),
                pr.getEstimated1RmKg(),
                pr.getWilksScore(),
                pr.getStrengthStandard(user.getWeightKg())
            ));
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- Inner type

    /** Holds the best set candidate for a given exercise across sessions. */
    private record ExerciseCandidate(ExerciseSet ex,
                                     java.time.LocalDateTime achievedAt,
                                     double e1RM) {}
}
