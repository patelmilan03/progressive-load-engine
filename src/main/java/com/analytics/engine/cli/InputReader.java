package com.analytics.engine.cli;

import java.util.Scanner;
import java.util.function.Predicate;

/**
 * Thread-safe, validated wrapper around {@link Scanner} that drives all
 * terminal input for the interactive CLI.
 *
 * <h3>Design goals</h3>
 * <ul>
 *   <li>Every public method re-prompts on invalid input instead of crashing.</li>
 *   <li>All ANSI colour is centralised here so other classes stay clean.</li>
 *   <li>A single shared Scanner prevents the well-known double-nextLine bug
 *       that occurs when mixing nextInt() / nextDouble() with nextLine().</li>
 * </ul>
 */
public final class InputReader {

    // ── ANSI colour codes ────────────────────────────────────────────────
    public static final String RESET   = "\033[0m";
    public static final String BOLD    = "\033[1m";
    public static final String CYAN    = "\033[36m";
    public static final String GREEN   = "\033[32m";
    public static final String YELLOW  = "\033[33m";
    public static final String RED     = "\033[31m";
    public static final String MAGENTA = "\033[35m";
    public static final String DIM     = "\033[2m";

    private final Scanner sc;

    public InputReader() {
        this.sc = new Scanner(System.in);
    }

    // ================================================================= Strings

    /**
     * Prompts until the user types a non-blank string.
     */
    public String readRequiredString(String prompt) {
        while (true) {
            System.out.print(CYAN + "  " + prompt + RESET + " ");
            String val = sc.nextLine().trim();
            if (!val.isEmpty()) return val;
            error("This field cannot be blank.");
        }
    }

    /**
     * Prompts with a default value shown in brackets.
     * Returns {@code defaultValue} if the user presses Enter.
     */
    public String readStringWithDefault(String prompt, String defaultValue) {
        System.out.print(CYAN + "  " + prompt + DIM + " [" + defaultValue + "]" + RESET + " ");
        String val = sc.nextLine().trim();
        return val.isEmpty() ? defaultValue : val;
    }

    /**
     * Yes / No prompt. Returns {@code true} for Y/y, {@code false} for N/n.
     * Default is used when the user presses Enter.
     */
    public boolean readYesNo(String prompt, boolean defaultYes) {
        String hint = defaultYes ? "Y/n" : "y/N";
        while (true) {
            System.out.print(CYAN + "  " + prompt + DIM + " [" + hint + "]" + RESET + " ");
            String val = sc.nextLine().trim().toLowerCase();
            if (val.isEmpty()) return defaultYes;
            if (val.equals("y")) return true;
            if (val.equals("n")) return false;
            error("Please enter Y or N.");
        }
    }

    // ================================================================= Numbers

    /**
     * Prompts until the user enters a double within [{@code min}, {@code max}].
     */
    public double readDouble(String prompt, double min, double max) {
        while (true) {
            System.out.print(CYAN + "  " + prompt + DIM + " (" + min + "–" + max + ")" + RESET + " ");
            String raw = sc.nextLine().trim();
            try {
                double val = Double.parseDouble(raw);
                if (val >= min && val <= max) return val;
                error("Please enter a value between " + min + " and " + max + ".");
            } catch (NumberFormatException e) {
                error("Not a valid number. Try again.");
            }
        }
    }

    /**
     * Prompts with a default double value. Returns default on blank input.
     */
    public double readDoubleWithDefault(String prompt, double defaultValue,
                                        double min, double max) {
        while (true) {
            System.out.print(CYAN + "  " + prompt + DIM
                + " [" + defaultValue + "] (" + min + "–" + max + ")" + RESET + " ");
            String raw = sc.nextLine().trim();
            if (raw.isEmpty()) return defaultValue;
            try {
                double val = Double.parseDouble(raw);
                if (val >= min && val <= max) return val;
                error("Value must be between " + min + " and " + max + ".");
            } catch (NumberFormatException e) {
                error("Not a valid number.");
            }
        }
    }

    /**
     * Prompts until the user enters an integer within [{@code min}, {@code max}].
     */
    public int readInt(String prompt, int min, int max) {
        while (true) {
            System.out.print(CYAN + "  " + prompt + DIM + " (" + min + "–" + max + ")" + RESET + " ");
            String raw = sc.nextLine().trim();
            try {
                int val = Integer.parseInt(raw);
                if (val >= min && val <= max) return val;
                error("Please enter a whole number between " + min + " and " + max + ".");
            } catch (NumberFormatException e) {
                error("Not a valid whole number.");
            }
        }
    }

    /**
     * Prompts with a default integer. Returns default on blank input.
     */
    public int readIntWithDefault(String prompt, int defaultValue, int min, int max) {
        while (true) {
            System.out.print(CYAN + "  " + prompt + DIM
                + " [" + defaultValue + "] (" + min + "–" + max + ")" + RESET + " ");
            String raw = sc.nextLine().trim();
            if (raw.isEmpty()) return defaultValue;
            try {
                int val = Integer.parseInt(raw);
                if (val >= min && val <= max) return val;
                error("Value must be between " + min + " and " + max + ".");
            } catch (NumberFormatException e) {
                error("Not a valid whole number.");
            }
        }
    }

    // ================================================================= Specialised

    /**
     * Reads a running pace in "m:ss" format (e.g. "5:30") and returns
     * it as a decimal minutes-per-km value (e.g. 5.5).
     */
    public double readPace(String prompt) {
        while (true) {
            System.out.print(CYAN + "  " + prompt + DIM + " (e.g. 5:30 for 5min 30sec/km)" + RESET + " ");
            String raw = sc.nextLine().trim();
            try {
                if (raw.contains(":")) {
                    String[] parts = raw.split(":");
                    int mins = Integer.parseInt(parts[0].trim());
                    int secs = Integer.parseInt(parts[1].trim());
                    if (secs >= 60) { error("Seconds must be 0–59."); continue; }
                    return mins + secs / 60.0;
                }
                // Accept plain decimal too
                double val = Double.parseDouble(raw);
                if (val > 0 && val < 20) return val;
                error("Pace must be between 2:00 and 20:00 /km.");
            } catch (Exception e) {
                error("Invalid pace. Use format mm:ss, e.g. 5:30");
            }
        }
    }

    /**
     * Shows a numbered menu and returns the chosen index (1-based as shown,
     * returned as 0-based internally).
     *
     * @param options display strings for each option
     * @return 0-based index of the chosen option
     */
    public int readMenu(String[] options) {
        for (int i = 0; i < options.length; i++) {
            System.out.printf("  " + BOLD + "%d" + RESET + "  %s%n", i + 1, options[i]);
        }
        System.out.println();
        return readInt("Your choice →", 1, options.length) - 1;
    }

    /**
     * Reads a line of free text for a field with a validation predicate.
     * Re-prompts with {@code errorMsg} if validation fails.
     */
    public String readValidated(String prompt, Predicate<String> validator, String errorMsg) {
        while (true) {
            System.out.print(CYAN + "  " + prompt + RESET + " ");
            String val = sc.nextLine().trim();
            if (validator.test(val)) return val;
            error(errorMsg);
        }
    }

    // ================================================================= Output helpers

    public void blank()            { System.out.println(); }
    public void divider()          { System.out.println(DIM + "  " + "─".repeat(60) + RESET); }
    public void header(String msg) {
        System.out.println();
        System.out.println(BOLD + CYAN + "  ══  " + msg + "  ══" + RESET);
        System.out.println();
    }
    public void success(String msg) { System.out.println(GREEN + "  ✔  " + msg + RESET); }
    public void warn(String msg)    { System.out.println(YELLOW + "  ⚠  " + msg + RESET); }
    public void info(String msg)    { System.out.println("  " + msg); }
    public void error(String msg)   { System.out.println(RED + "  ✘  " + msg + RESET); }

    public void pressEnterToContinue() {
        System.out.print(DIM + "\n  Press Enter to continue..." + RESET);
        sc.nextLine();
    }

    public void close() { sc.close(); }
}
