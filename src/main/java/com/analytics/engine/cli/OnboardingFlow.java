package com.analytics.engine.cli;

import com.analytics.engine.dao.UserProfileDAO;
import com.analytics.engine.model.UserProfile;

import java.sql.SQLException;

/**
 * First-launch wizard: collects the athlete's physical profile and saves it.
 * Never called again once a user record exists in the database.
 */
public final class OnboardingFlow {

    private final InputReader    io;
    private final UserProfileDAO userDao;

    public OnboardingFlow(InputReader io, UserProfileDAO userDao) {
        this.io      = io;
        this.userDao = userDao;
    }

    public UserProfile run() throws SQLException {
        printWelcomeBanner();

        io.header("Let's set up your athlete profile");
        io.info("This only runs once. Everything is saved to training_engine.db");
        io.blank();

        // ── Name ─────────────────────────────────────────────────────────
        String name = io.readRequiredString("Your full name:");

        // ── Height ───────────────────────────────────────────────────────
        io.blank();
        io.divider();
        io.info("Height — enter in cm (e.g. 182) or feet+inches (e.g. 6'1)");
        double heightCm = readHeight();

        // ── Weight ───────────────────────────────────────────────────────
        io.blank();
        io.divider();
        io.info("Body weight — enter in kg (e.g. 82.5) or lbs (e.g. 182lbs)");
        double weightKg = readWeight();

        // ── Age ──────────────────────────────────────────────────────────
        io.blank();
        io.divider();
        int age = io.readInt("Your age:", 14, 99);

        // ── Heart rate ───────────────────────────────────────────────────
        io.blank();
        io.divider();
        io.info("Resting HR: measure in the morning before getting up (lie still 1 min).");
        int rhr = io.readIntWithDefault("Resting heart rate (bpm):", 60, 30, 100);

        int defaultMaxHr = 220 - age;
        io.info("Max HR: use a value from a hard sprint or race. Press Enter to use 220 - age = " + defaultMaxHr + ".");
        int maxHr = io.readIntWithDefault("Max heart rate (bpm):", defaultMaxHr, 140, 220);

        // ── Training experience ───────────────────────────────────────────
        io.blank();
        io.divider();
        io.info("Training experience: years of consistent lifting (0.5 = 6 months, 5+ = veteran).");
        double exp = io.readDoubleWithDefault("Years of training experience:", 1.0, 0.0, 30.0);

        // ── Build & save ─────────────────────────────────────────────────
        UserProfile profile = UserProfile.builder(name)
            .heightCm(heightCm)
            .weightKg(weightKg)
            .age(age)
            .restingHeartRate(rhr)
            .maxHeartRate(maxHr)
            .trainingExperienceYears(exp)
            .build();

        userDao.save(profile);

        io.blank();
        io.divider();
        io.success("Profile created!");
        printSummary(profile);
        io.blank();

        if (!io.readYesNo("Does this look correct?", true)) {
            io.info("No problem — delete training_engine.db and restart to redo setup.");
        }
        return profile;
    }

    // ── Parsing helpers ───────────────────────────────────────────────────

    private double readHeight() {
        while (true) {
            String raw = io.readRequiredString("Height (e.g. 182  or  6'1):");
            try {
                if (raw.contains("'") || raw.toLowerCase().contains("ft")) {
                    return parseFeetInches(raw);
                }
                double cm = Double.parseDouble(raw.replace("cm", "").trim());
                if (cm >= 120 && cm <= 230) return cm;
                io.error("Height in cm should be between 120 and 230.");
            } catch (NumberFormatException e) {
                io.error("Couldn't understand that. Try: 182  or  6'1");
            }
        }
    }

    private double readWeight() {
        while (true) {
            String raw = io.readRequiredString("Weight (e.g. 82.5  or  182lbs):");
            try {
                boolean isLbs = raw.toLowerCase().contains("lb");
                String cleaned = raw.toLowerCase()
                    .replace("kg", "").replace("lbs", "").replace("lb", "").trim();
                double val = Double.parseDouble(cleaned);

                if (isLbs || val > 150) {           // assume lbs if > 150 with no unit
                    double kg = val * 0.453592;
                    io.info(String.format("Converting %.1f lbs → %.1f kg", val, kg));
                    return kg;
                }
                if (val >= 30 && val <= 200) return val;
                io.error("Weight in kg should be between 30 and 200. Add 'lbs' if using pounds.");
            } catch (NumberFormatException e) {
                io.error("Couldn't understand that. Try: 82.5  or  182lbs");
            }
        }
    }

    private double parseFeetInches(String raw) {
        String s = raw.toLowerCase()
            .replace("feet", "'").replace("ft", "'")
            .replace("inches", "\"").replace("in", "\"")
            .replaceAll("\\s+", "");
        String[] parts = s.split("'");
        int feet   = Integer.parseInt(parts[0].trim());
        int inches = (parts.length > 1)
            ? Integer.parseInt(parts[1].replace("\"", "").trim()) : 0;
        return (feet * 12 + inches) * 2.54;
    }

    private void printSummary(UserProfile p) {
        String d = InputReader.DIM, r = InputReader.RESET;
        System.out.printf("  %s%-20s%s %s%n",              d, "Name:",             r, p.getName());
        System.out.printf("  %s%-20s%s %.1f cm (%.1f ft)%n", d, "Height:",         r, p.getHeightCm(), p.getHeightCm()/30.48);
        System.out.printf("  %s%-20s%s %.1f kg%n",           d, "Weight:",         r, p.getWeightKg());
        System.out.printf("  %s%-20s%s %d%n",                d, "Age:",            r, p.getAge());
        System.out.printf("  %s%-20s%s %d bpm%n",            d, "Resting HR:",     r, p.getRestingHeartRate());
        System.out.printf("  %s%-20s%s %d bpm%n",            d, "Max HR:",         r, p.getMaxHeartRate());
        System.out.printf("  %s%-20s%s %.1f yrs%n",          d, "Experience:",     r, p.getTrainingExperienceYears());
        System.out.printf("  %s%-20s%s %.2f×%n",             d, "Vol. tolerance:", r, p.getVolumeToleranceFactor());
    }

    private void printWelcomeBanner() {
        System.out.println();
        System.out.println(InputReader.BOLD + InputReader.CYAN);
        System.out.println("  ╔══════════════════════════════════════════════════════════╗");
        System.out.println("  ║    ⚡  Progressive Load & Recovery Analytics Engine  ⚡   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════╝");
        System.out.println(InputReader.RESET);
        System.out.println("  Welcome! This looks like your first time here.");
        System.out.println();
    }
}
