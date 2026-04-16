package com.analytics.engine;

import com.analytics.engine.cli.InteractiveCLI;
import com.analytics.engine.dao.DatabaseManager;

/**
 * Application entry point.
 *
 * Boots the database and hands control to {@link InteractiveCLI}.
 * The database connection is always closed on exit, even after errors.
 *
 * Run:  mvn compile exec:java
 *       java -cp "lib/sqlite-jdbc.jar:target/classes" com.analytics.engine.Main
 */
public final class Main {

    private static final String DB_PATH = "./training_engine.db";

    public static void main(String[] args) {
        DatabaseManager db = DatabaseManager.getInstance();
        try {
            db.initialise(DB_PATH);
            new InteractiveCLI(db).launch();
        } catch (Exception e) {
            System.err.println("\n  [FATAL] " + e.getMessage());
        } finally {
            try { db.close(); } catch (Exception ignored) {}
        }
    }
}
