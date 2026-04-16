package com.analytics.engine.dao;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * Singleton responsible for managing the single SQLite {@link Connection}
 * and executing the DDL schema on first start.
 *
 * <h3>Design Notes</h3>
 * <ul>
 *   <li>SQLite is an embedded, single-writer database — one shared connection
 *       is the correct pattern here (no connection pool needed).</li>
 *   <li>WAL (Write-Ahead Logging) is enabled for improved concurrent read
 *       performance, even though we only have one connection.</li>
 *   <li>The schema is loaded from {@code schema.sql} on the classpath, keeping
 *       the DDL in a single authoritative location.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   DatabaseManager db = DatabaseManager.getInstance();
 *   db.initialise("./training.db");   // call once at startup
 *   Connection conn = db.getConnection();
 * }</pre>
 */
public final class DatabaseManager {

    private static final DatabaseManager INSTANCE = new DatabaseManager();

    private Connection connection;
    private String     dbPath;

    private DatabaseManager() {}

    public static DatabaseManager getInstance() { return INSTANCE; }

    // ----------------------------------------------------------------- Lifecycle

    /**
     * Opens (or creates) the SQLite database at {@code dbPath} and runs
     * {@code schema.sql} to initialise all tables.
     *
     * @param dbPath file path for the SQLite database file,
     *               e.g. {@code "./training.db"} or {@code ":memory:"}
     */
    public synchronized void initialise(String dbPath) throws SQLException {
        this.dbPath = dbPath;
        String url  = "jdbc:sqlite:" + dbPath;

        // Explicitly register the SQLite driver — required when the JAR is on
        // a flat classpath (e.g. fat-jar, test runner) and ServiceLoader hasn't
        // auto-discovered it via META-INF/services.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not on classpath: " + e.getMessage(), e);
        }

        connection = DriverManager.getConnection(url);

        // Performance & safety pragmas
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA synchronous   = NORMAL");
        }

        runSchema();
        System.out.println("[DatabaseManager] Connected to: " + dbPath);
    }

    /**
     * Returns the live shared connection.
     * Callers should not close this connection — let {@link #close()} handle it.
     */
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException(
                "DatabaseManager not initialised. Call initialise(dbPath) first.");
        }
        return connection;
    }

    /** Cleanly closes the SQLite connection. */
    public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
            System.out.println("[DatabaseManager] Connection closed.");
        }
    }

    // ----------------------------------------------------------------- Schema

    /**
     * Reads {@code schema.sql} from the classpath and executes each
     * DDL statement separated by {@code ;}.
     *
     * <p>Comment lines (starting with {@code --}) are stripped before
     * splitting so that CREATE TABLE blocks preceded by comment headers
     * are not accidentally skipped by the empty-check.
     */
    private void runSchema() throws SQLException {
        String raw = loadSchemaFromClasspath();

        // Strip full-line SQL comments so they don't interfere with the splitter
        String sql = raw.lines()
            .filter(line -> !line.stripLeading().startsWith("--"))
            .collect(java.util.stream.Collectors.joining("\n"));

        // Split on ';' — SQLite driver doesn't support multi-statement execution
        String[] statements = sql.split(";");
        try (Statement st = connection.createStatement()) {
            for (String stmt : statements) {
                String trimmed = stmt.strip();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }
        System.out.println("[DatabaseManager] Schema applied successfully.");
    }

    private String loadSchemaFromClasspath() throws SQLException {
        // The schema.sql is copied into the classpath root by Maven
        try (InputStream is = getClass().getClassLoader()
                                        .getResourceAsStream("schema.sql")) {
            if (is == null) {
                throw new SQLException(
                    "schema.sql not found on classpath. " +
                    "Ensure it is in src/main/resources/.");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read schema.sql: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- Helpers

    public String getDbPath()  { return dbPath; }

    /** Convenience: begin a transaction. */
    public void beginTransaction() throws SQLException {
        getConnection().setAutoCommit(false);
    }

    /** Convenience: commit. */
    public void commit() throws SQLException {
        getConnection().commit();
        getConnection().setAutoCommit(true);
    }

    /** Convenience: rollback. */
    public void rollback() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (SQLException ignored) {}
    }
}
