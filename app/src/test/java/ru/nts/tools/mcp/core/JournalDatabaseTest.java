package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class JournalDatabaseTest {

    @TempDir
    Path tempDir;

    private JournalDatabase db;

    @BeforeEach
    void setUp() {
        db = new JournalDatabase(tempDir);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    @Test
    @DisplayName("initialize creates schema and tables")
    void initializeCreatesSchema() throws Exception {
        db.initialize();
        assertTrue(db.isInitialized());

        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement()) {
            // Verify all tables exist
            assertTableExists(stmt, "TASK_METADATA");
            assertTableExists(stmt, "JOURNAL_ENTRIES");
            assertTableExists(stmt, "FILE_SNAPSHOTS");
            assertTableExists(stmt, "DIFF_STATS");
            assertTableExists(stmt, "TASK_COUNTERS");
        }
    }

    @Test
    @DisplayName("initialize is idempotent")
    void initializeIdempotent() throws Exception {
        db.initialize();
        db.initialize(); // second call should not throw
        assertTrue(db.isInitialized());
    }

    @Test
    @DisplayName("getInitializedConnection auto-initializes")
    void autoInitialize() throws Exception {
        assertFalse(db.isInitialized());

        try (Connection conn = db.getInitializedConnection()) {
            assertNotNull(conn);
        }

        assertTrue(db.isInitialized());
    }

    @Test
    @DisplayName("existsOnDisk reflects file presence")
    void existsOnDisk() throws Exception {
        assertFalse(db.existsOnDisk());
        db.initialize();
        // After init, db file should exist
        // Force a table creation to ensure the .mv.db file is actually written
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO task_counters(counter_name, counter_value) VALUES('test', 1)");
        }
        assertTrue(db.existsOnDisk());
    }

    @Test
    @DisplayName("close prevents new connections")
    void closePreventsConnections() throws Exception {
        db.initialize();
        db.close();

        assertThrows(Exception.class, () -> db.getConnection());
    }

    @Test
    @DisplayName("schema version is persisted")
    void schemaVersionPersisted() throws Exception {
        db.initialize();

        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT meta_value FROM task_metadata WHERE meta_key = 'schema_version'")) {
            assertTrue(rs.next());
            assertEquals("1", rs.getString("meta_value"));
        }
    }

    @Test
    @DisplayName("default counters are initialized")
    void defaultCounters() throws Exception {
        db.initialize();

        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT counter_value FROM task_counters WHERE counter_name = 'totalEdits'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT counter_value FROM task_counters WHERE counter_name = 'totalUndos'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("cascade delete removes snapshots and diff_stats")
    void cascadeDelete() throws Exception {
        db.initialize();
        JournalRepository repo = new JournalRepository();

        try (Connection conn = db.getInitializedConnection()) {
            conn.setAutoCommit(false);

            long entryId = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    java.time.LocalDateTime.now(), "test", "COMMITTED",
                    null, null, null, null, null);

            repo.insertSnapshot(conn, entryId, "src/Main.java",
                    "hello".getBytes(), 5, 12345L);
            repo.insertDiffStats(conn, entryId, "src/Main.java", 3, 1, null,
                    "@@ -1 +1 @@\n-old\n+new");

            conn.commit();

            // Delete entry
            repo.deleteEntry(conn, entryId);
            conn.commit();

            // Verify cascaded deletes
            var snapshots = repo.getSnapshots(conn, entryId);
            assertTrue(snapshots.isEmpty());
            var stats = repo.getDiffStats(conn, entryId);
            assertTrue(stats.isEmpty());
        }
    }

    private void assertTableExists(Statement stmt, String tableName) throws Exception {
        ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '" + tableName + "'");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1), "Table " + tableName + " should exist");
    }
}
