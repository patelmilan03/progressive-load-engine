package com.analytics.engine.cli;

import com.analytics.engine.analytics.RecoveryCalculator;
import com.analytics.engine.dao.*;
import com.analytics.engine.model.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Top-level controller for the interactive terminal UI.
 *
 * <h3>Launch logic</h3>
 * <pre>
 *   First launch  (no user in DB)  →  OnboardingFlow → log today? → Dashboard
 *   Returning     (user exists)    →  show readiness → log today? → Dashboard
 * </pre>
 *
 * <p>This class is the only one that should be called from {@code Main.main()}.
 * It wires together all the flows and DAOs.
 */
public final class InteractiveCLI {

    private final DatabaseManager        db;
    private final UserProfileDAO         userDao;
    private final HypertrophySessionDAO  strengthDao;
    private final CardioSessionDAO       cardioDao;
    private final PersonalRecordDAO      prDao;
    private final InputReader            io;

    public InteractiveCLI(DatabaseManager db) {
        this.db          = db;
        this.io          = new InputReader();
        this.userDao     = new UserProfileDAO(db);
        this.strengthDao = new HypertrophySessionDAO(db);
        this.cardioDao   = new CardioSessionDAO(db);
        this.prDao       = new PersonalRecordDAO(db);
    }

    // ================================================================= Entry point

    /**
     * Runs the full interactive session.
     * Called once from {@code Main.main()}.
     */
    public void launch() throws SQLException {

        // ── Detect first vs returning launch ─────────────────────────────
        List<UserProfile> existingUsers = userDao.findAll();
        UserProfile user;

        if (existingUsers.isEmpty()) {
            // First launch — run onboarding
            OnboardingFlow onboarding = new OnboardingFlow(io, userDao);
            user = onboarding.run();
            io.blank();
            io.pressEnterToContinue();
        } else {
            // Returning launch
            user = existingUsers.get(0);   // single-user mode
            printWelcomeBack(user);
        }

        // ── Ask to log today's session ───────────────────────────────────
        io.blank();
        io.divider();
        boolean wantsToLog = io.readYesNo("Log a session from today?", true);

        WorkoutSession newSession = null;

        if (wantsToLog) {
            SessionLoggerFlow logger = new SessionLoggerFlow(io);
            Optional<WorkoutSession> logged = logger.run(user.getId());

            if (logged.isPresent()) {
                newSession = logged.get();
                saveSession(newSession);
                io.blank();
            }
        }

        // ── Show dashboard ───────────────────────────────────────────────
        DashboardRenderer dashboard = new DashboardRenderer(
            io, strengthDao, cardioDao, prDao);
        dashboard.render(user, newSession);

        io.close();
    }

    // ================================================================= Helpers

    private void saveSession(WorkoutSession session) throws SQLException {
        if (session instanceof HypertrophySession hs) {
            // Force load calculation before saving so it's stored in the DB
            // We need a user profile to calculate load — fetch it
            List<UserProfile> users = userDao.findAll();
            if (!users.isEmpty()) hs.calculateLoad(users.get(0));
            strengthDao.save(hs);
        } else if (session instanceof CardioSession cs) {
            List<UserProfile> users = userDao.findAll();
            if (!users.isEmpty()) cs.calculateLoad(users.get(0));
            cardioDao.save(cs);
        }
    }

    private void printWelcomeBack(UserProfile user) {
        String firstName = user.getName().split(" ")[0];
        System.out.println();
        System.out.println(InputReader.BOLD + InputReader.CYAN
            + "  ╔══════════════════════════════════════════════════════════╗");
        System.out.printf ("  ║    ⚡  Welcome back, %-35s║%n", firstName + "!" );
        System.out.println("  ║       Progressive Load & Recovery Analytics Engine      ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════╝"
            + InputReader.RESET);
        System.out.println();
        System.out.printf ("  %s%s%-18s%s  %.1f kg  |  %d yrs old  |  %.1f yrs training%n",
            InputReader.DIM, "", "Athlete:",
            InputReader.RESET,
            user.getWeightKg(), user.getAge(), user.getTrainingExperienceYears());
    }
}
