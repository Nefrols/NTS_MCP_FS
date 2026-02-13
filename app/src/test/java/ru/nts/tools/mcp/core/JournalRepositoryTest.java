package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JournalRepositoryTest {

    @TempDir
    Path tempDir;

    private JournalDatabase db;
    private JournalRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        db = new JournalDatabase(tempDir);
        db.initialize();
        repo = new JournalRepository();
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    // ==================== Journal Entries ====================

    @Test
    @DisplayName("insert and read TRANSACTION entry")
    void insertAndReadTransaction() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            LocalDateTime now = LocalDateTime.now();
            long id = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    now, "Edit Foo.java", "COMMITTED", "refactor method", null, null, null, null);

            assertTrue(id > 0);

            JournalRepository.JournalEntry entry = repo.getEntry(conn, id);
            assertNotNull(entry);
            assertEquals("UNDO", entry.stack());
            assertEquals("TRANSACTION", entry.entryType());
            assertEquals(0, entry.position());
            assertEquals("Edit Foo.java", entry.description());
            assertEquals("COMMITTED", entry.status());
            assertEquals("refactor method", entry.instruction());
            assertTrue(entry.isTransaction());
            assertTrue(entry.isUndo());
        }
    }

    @Test
    @DisplayName("insert and read CHECKPOINT entry")
    void insertAndReadCheckpoint() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long id = repo.insertEntry(conn, "UNDO", "CHECKPOINT", 0,
                    LocalDateTime.now(), null, "COMMITTED", null, null, null, null, "before-refactor");

            JournalRepository.JournalEntry entry = repo.getEntry(conn, id);
            assertNotNull(entry);
            assertTrue(entry.isCheckpoint());
            assertEquals("before-refactor", entry.checkpointName());
        }
    }

    @Test
    @DisplayName("insert and read EXTERNAL entry")
    void insertAndReadExternal() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long id = repo.insertEntry(conn, "UNDO", "EXTERNAL", 0,
                    LocalDateTime.now(), "IDE modification", "COMMITTED", null,
                    "src/Main.java", 123L, 456L, null);

            JournalRepository.JournalEntry entry = repo.getEntry(conn, id);
            assertNotNull(entry);
            assertTrue(entry.isExternal());
            assertEquals("src/Main.java", entry.affectedPath());
            assertEquals(123L, entry.previousCrc());
            assertEquals(456L, entry.currentCrc());
        }
    }

    @Test
    @DisplayName("getEntries returns entries in order")
    void getEntriesOrdered() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "first", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "UNDO", "CHECKPOINT", 1,
                    LocalDateTime.now(), null, "COMMITTED", null, null, null, null, "cp1");
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 2,
                    LocalDateTime.now(), "third", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "REDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "redo-first", "COMMITTED", null, null, null, null, null);

            List<JournalRepository.JournalEntry> undoEntries = repo.getEntries(conn, "UNDO");
            assertEquals(3, undoEntries.size());
            assertEquals(0, undoEntries.get(0).position());
            assertEquals(1, undoEntries.get(1).position());
            assertEquals(2, undoEntries.get(2).position());

            List<JournalRepository.JournalEntry> redoEntries = repo.getEntries(conn, "REDO");
            assertEquals(1, redoEntries.size());
        }
    }

    @Test
    @DisplayName("getLastEntry returns highest position")
    void getLastEntry() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "first", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 5,
                    LocalDateTime.now(), "last", "COMMITTED", null, null, null, null, null);

            JournalRepository.JournalEntry last = repo.getLastEntry(conn, "UNDO");
            assertNotNull(last);
            assertEquals("last", last.description());
            assertEquals(5, last.position());
        }
    }

    @Test
    @DisplayName("getMaxPosition returns correct value")
    void getMaxPosition() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            assertEquals(-1, repo.getMaxPosition(conn, "UNDO"));

            repo.insertEntry(conn, "UNDO", "TRANSACTION", 3,
                    LocalDateTime.now(), "desc", "COMMITTED", null, null, null, null, null);
            assertEquals(3, repo.getMaxPosition(conn, "UNDO"));
        }
    }

    @Test
    @DisplayName("clearStack removes all entries from stack")
    void clearStack() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            repo.insertEntry(conn, "REDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "redo1", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "REDO", "TRANSACTION", 1,
                    LocalDateTime.now(), "redo2", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "undo1", "COMMITTED", null, null, null, null, null);

            repo.clearStack(conn, "REDO");

            assertEquals(0, repo.getStackSize(conn, "REDO"));
            assertEquals(1, repo.getStackSize(conn, "UNDO"));
        }
    }

    @Test
    @DisplayName("moveEntry changes stack and position")
    void moveEntry() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long id = repo.insertEntry(conn, "UNDO", "TRANSACTION", 5,
                    LocalDateTime.now(), "moving", "COMMITTED", null, null, null, null, null);

            repo.moveEntry(conn, id, "REDO", 0);

            JournalRepository.JournalEntry entry = repo.getEntry(conn, id);
            assertEquals("REDO", entry.stack());
            assertEquals(0, entry.position());
        }
    }

    @Test
    @DisplayName("deleteOldestEntry removes lowest position")
    void deleteOldestEntry() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "oldest", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 1,
                    LocalDateTime.now(), "newer", "COMMITTED", null, null, null, null, null);

            long deletedId = repo.deleteOldestEntry(conn, "UNDO");
            assertTrue(deletedId >= 0);

            assertEquals(1, repo.getStackSize(conn, "UNDO"));
            JournalRepository.JournalEntry remaining = repo.getLastEntry(conn, "UNDO");
            assertEquals("newer", remaining.description());
        }
    }

    @Test
    @DisplayName("findCheckpointPosition finds correct checkpoint")
    void findCheckpointPosition() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "tx1", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "UNDO", "CHECKPOINT", 1,
                    LocalDateTime.now(), null, "COMMITTED", null, null, null, null, "cp-before");
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 2,
                    LocalDateTime.now(), "tx2", "COMMITTED", null, null, null, null, null);

            assertEquals(1, repo.findCheckpointPosition(conn, "UNDO", "cp-before"));
            assertEquals(-1, repo.findCheckpointPosition(conn, "UNDO", "nonexistent"));
        }
    }

    @Test
    @DisplayName("getEntriesAfterPosition for rollback")
    void getEntriesAfterPosition() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "tx0", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "UNDO", "CHECKPOINT", 1,
                    LocalDateTime.now(), null, "COMMITTED", null, null, null, null, "cp");
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 2,
                    LocalDateTime.now(), "tx2", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 3,
                    LocalDateTime.now(), "tx3", "COMMITTED", null, null, null, null, null);

            List<JournalRepository.JournalEntry> after = repo.getEntriesAfterPosition(conn, "UNDO", 1);
            assertEquals(2, after.size());
            // Ordered DESC
            assertEquals(3, after.get(0).position());
            assertEquals(2, after.get(1).position());
        }
    }

    // ==================== File Snapshots ====================

    @Test
    @DisplayName("insert and read snapshot with content")
    void snapshotWithContent() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long entryId = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "edit", "COMMITTED", null, null, null, null, null);

            byte[] content = "public class Foo {}".getBytes(StandardCharsets.UTF_8);
            long snId = repo.insertSnapshot(conn, entryId, "src/Foo.java", content, content.length, 99999L);
            assertTrue(snId > 0);

            Map<String, JournalRepository.FileSnapshot> snapshots = repo.getSnapshots(conn, entryId);
            assertEquals(1, snapshots.size());

            JournalRepository.FileSnapshot snap = snapshots.get("src/Foo.java");
            assertNotNull(snap);
            assertEquals("src/Foo.java", snap.filePath());
            assertArrayEquals(content, snap.content());
            assertEquals(content.length, snap.fileSize());
            assertEquals(99999L, snap.crc32c());
            assertFalse(snap.wasCreated());
        }
    }

    @Test
    @DisplayName("insert snapshot with null content (file was created)")
    void snapshotNullContent() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long entryId = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "create file", "COMMITTED", null, null, null, null, null);

            repo.insertSnapshot(conn, entryId, "src/NewFile.java", null, 0, 0);

            Map<String, JournalRepository.FileSnapshot> snapshots = repo.getSnapshots(conn, entryId);
            JournalRepository.FileSnapshot snap = snapshots.get("src/NewFile.java");
            assertNotNull(snap);
            assertNull(snap.content());
            assertTrue(snap.wasCreated());
        }
    }

    @Test
    @DisplayName("getSnapshot reads individual snapshot by path")
    void getSnapshotByPath() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long entryId = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "multi", "COMMITTED", null, null, null, null, null);

            repo.insertSnapshot(conn, entryId, "a.java", "aaa".getBytes(), 3, 1L);
            repo.insertSnapshot(conn, entryId, "b.java", "bbb".getBytes(), 3, 2L);

            JournalRepository.FileSnapshot snap = repo.getSnapshot(conn, entryId, "b.java");
            assertNotNull(snap);
            assertArrayEquals("bbb".getBytes(), snap.content());
        }
    }

    // ==================== Diff Stats ====================

    @Test
    @DisplayName("insert and read diff stats")
    void diffStats() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long entryId = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "edit", "COMMITTED", null, null, null, null, null);

            String diff = "@@ -1,3 +1,4 @@\n old\n-removed\n+added\n+new";
            repo.insertDiffStats(conn, entryId, "src/Foo.java", 2, 1, "method1,class Foo", diff);

            List<JournalRepository.DiffStat> stats = repo.getDiffStats(conn, entryId);
            assertEquals(1, stats.size());

            JournalRepository.DiffStat stat = stats.get(0);
            assertEquals("src/Foo.java", stat.filePath());
            assertEquals(2, stat.linesAdded());
            assertEquals(1, stat.linesDeleted());
            assertEquals("method1,class Foo", stat.affectedBlocks());
            assertEquals(diff, stat.unifiedDiff());
        }
    }

    @Test
    @DisplayName("getUnifiedDiff returns diff for specific file")
    void getUnifiedDiff() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long entryId = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "edit", "COMMITTED", null, null, null, null, null);

            repo.insertDiffStats(conn, entryId, "a.java", 1, 0, null, "diff-a");
            repo.insertDiffStats(conn, entryId, "b.java", 0, 1, null, "diff-b");

            assertEquals("diff-a", repo.getUnifiedDiff(conn, entryId, "a.java"));
            assertEquals("diff-b", repo.getUnifiedDiff(conn, entryId, "b.java"));
            assertNull(repo.getUnifiedDiff(conn, entryId, "nonexistent.java"));
        }
    }

    // ==================== Metadata ====================

    @Test
    @DisplayName("metadata set and get")
    void metadata() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            repo.setMetadata(conn, "workingDirectory", "/home/user/project");
            assertEquals("/home/user/project", repo.getMetadata(conn, "workingDirectory"));

            // Overwrite
            repo.setMetadata(conn, "workingDirectory", "/new/path");
            assertEquals("/new/path", repo.getMetadata(conn, "workingDirectory"));

            // Delete
            repo.deleteMetadata(conn, "workingDirectory");
            assertNull(repo.getMetadata(conn, "workingDirectory"));
        }
    }

    // ==================== Counters ====================

    @Test
    @DisplayName("counter operations")
    void counters() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            assertEquals(0, repo.getCounter(conn, "totalEdits"));

            repo.setCounter(conn, "totalEdits", 42);
            assertEquals(42, repo.getCounter(conn, "totalEdits"));

            int next = repo.incrementCounter(conn, "totalEdits");
            assertEquals(43, next);
            assertEquals(43, repo.getCounter(conn, "totalEdits"));
        }
    }

    // ==================== Query Operations ====================

    @Test
    @DisplayName("getEntriesForFile finds by snapshot path")
    void getEntriesForFileBySnapshot() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long id1 = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "edit1", "COMMITTED", null, null, null, null, null);
            repo.insertSnapshot(conn, id1, "src/Foo.java", "a".getBytes(), 1, 1L);

            long id2 = repo.insertEntry(conn, "UNDO", "TRANSACTION", 1,
                    LocalDateTime.now(), "edit2", "COMMITTED", null, null, null, null, null);
            repo.insertSnapshot(conn, id2, "src/Bar.java", "b".getBytes(), 1, 2L);

            List<JournalRepository.JournalEntry> fooEntries = repo.getEntriesForFile(conn, "src/Foo.java");
            assertEquals(1, fooEntries.size());
            assertEquals("edit1", fooEntries.get(0).description());
        }
    }

    @Test
    @DisplayName("getEntriesForFile finds by affected_path (EXTERNAL)")
    void getEntriesForFileByAffectedPath() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            repo.insertEntry(conn, "UNDO", "EXTERNAL", 0,
                    LocalDateTime.now(), "external change", "COMMITTED", null,
                    "src/Main.java", 100L, 200L, null);

            List<JournalRepository.JournalEntry> entries = repo.getEntriesForFile(conn, "src/Main.java");
            assertEquals(1, entries.size());
            assertTrue(entries.get(0).isExternal());
        }
    }

    @Test
    @DisplayName("getAllAffectedFiles returns all paths")
    void getAllAffectedFiles() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long id1 = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "tx", "COMMITTED", null, null, null, null, null);
            repo.insertSnapshot(conn, id1, "a.java", "x".getBytes(), 1, 1L);
            repo.insertSnapshot(conn, id1, "b.java", "y".getBytes(), 1, 2L);

            repo.insertEntry(conn, "UNDO", "EXTERNAL", 1,
                    LocalDateTime.now(), "ext", "COMMITTED", null, "c.java", 1L, 2L, null);

            List<String> files = repo.getAllAffectedFiles(conn);
            assertTrue(files.contains("a.java"));
            assertTrue(files.contains("b.java"));
            assertTrue(files.contains("c.java"));
        }
    }

    @Test
    @DisplayName("getAllEntries returns from both stacks ordered by time")
    void getAllEntries() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            LocalDateTime t1 = LocalDateTime.of(2025, 1, 1, 10, 0, 0);
            LocalDateTime t2 = LocalDateTime.of(2025, 1, 1, 10, 1, 0);
            LocalDateTime t3 = LocalDateTime.of(2025, 1, 1, 10, 2, 0);

            repo.insertEntry(conn, "UNDO", "TRANSACTION", 0, t1, "first", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "REDO", "TRANSACTION", 0, t2, "second", "COMMITTED", null, null, null, null, null);
            repo.insertEntry(conn, "UNDO", "TRANSACTION", 1, t3, "third", "COMMITTED", null, null, null, null, null);

            List<JournalRepository.JournalEntry> all = repo.getAllEntries(conn);
            assertEquals(3, all.size());
            assertEquals("first", all.get(0).description());
            assertEquals("second", all.get(1).description());
            assertEquals("third", all.get(2).description());
        }
    }

    @Test
    @DisplayName("large BLOB snapshot (> 1MB)")
    void largeBlobSnapshot() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long entryId = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "big file", "COMMITTED", null, null, null, null, null);

            // 2MB content
            byte[] bigContent = new byte[2 * 1024 * 1024];
            java.util.Arrays.fill(bigContent, (byte) 'A');

            repo.insertSnapshot(conn, entryId, "big.bin", bigContent, bigContent.length, 777L);

            Map<String, JournalRepository.FileSnapshot> snapshots = repo.getSnapshots(conn, entryId);
            JournalRepository.FileSnapshot snap = snapshots.get("big.bin");
            assertNotNull(snap);
            assertEquals(bigContent.length, snap.content().length);
            assertEquals(bigContent.length, snap.fileSize());
        }
    }

    @Test
    @DisplayName("updateEntryStatus changes status")
    void updateEntryStatus() throws Exception {
        try (Connection conn = db.getInitializedConnection()) {
            long id = repo.insertEntry(conn, "UNDO", "TRANSACTION", 0,
                    LocalDateTime.now(), "test", "COMMITTED", null, null, null, null, null);

            repo.updateEntryStatus(conn, id, "STUCK");

            JournalRepository.JournalEntry entry = repo.getEntry(conn, id);
            assertEquals("STUCK", entry.status());
        }
    }
}
