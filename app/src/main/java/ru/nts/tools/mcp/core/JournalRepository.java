/*
 * Copyright 2025 Aristo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.nts.tools.mcp.core;

import java.io.ByteArrayInputStream;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Репозиторий для всех SQL-операций журнала сессии.
 * Работает с embedded H2 через plain JDBC (без ORM).
 *
 * Все методы принимают Connection извне — вызывающий код управляет транзакционностью.
 * Типичный паттерн:
 * <pre>
 *   try (Connection conn = db.getInitializedConnection()) {
 *       conn.setAutoCommit(false);
 *       repo.insertEntry(conn, ...);
 *       repo.insertSnapshot(conn, ...);
 *       conn.commit();
 *   }
 * </pre>
 */
public class JournalRepository {

    // ==================== Journal Entries ====================

    /**
     * Вставляет запись журнала и возвращает сгенерированный ID.
     */
    public long insertEntry(Connection conn, String stack, String entryType, int position,
                            LocalDateTime timestamp, String description, String status,
                            String instruction, String affectedPath,
                            Long previousCrc, Long currentCrc, String checkpointName) throws SQLException {
        String sql = """
                INSERT INTO journal_entries
                (stack, entry_type, position, created_at, description, status, instruction,
                 affected_path, previous_crc, current_crc, checkpoint_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, stack);
            ps.setString(2, entryType);
            ps.setInt(3, position);
            ps.setTimestamp(4, Timestamp.valueOf(timestamp));
            ps.setString(5, description);
            ps.setString(6, status);
            ps.setString(7, instruction);
            ps.setString(8, affectedPath);
            if (previousCrc != null) ps.setLong(9, previousCrc); else ps.setNull(9, Types.BIGINT);
            if (currentCrc != null) ps.setLong(10, currentCrc); else ps.setNull(10, Types.BIGINT);
            ps.setString(11, checkpointName);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to get generated entry ID");
    }

    /**
     * Читает все записи из указанного стека, упорядоченные по position.
     */
    public List<JournalEntry> getEntries(Connection conn, String stack) throws SQLException {
        String sql = """
                SELECT id, stack, entry_type, position, created_at, description, status,
                       instruction, affected_path, previous_crc, current_crc, checkpoint_name
                FROM journal_entries WHERE stack = ? ORDER BY position ASC
                """;

        List<JournalEntry> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stack);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapEntry(rs));
                }
            }
        }
        return result;
    }

    /**
     * Читает конкретную запись по ID.
     */
    public JournalEntry getEntry(Connection conn, long entryId) throws SQLException {
        String sql = """
                SELECT id, stack, entry_type, position, created_at, description, status,
                       instruction, affected_path, previous_crc, current_crc, checkpoint_name
                FROM journal_entries WHERE id = ?
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapEntry(rs);
            }
        }
        return null;
    }

    /**
     * Возвращает последнюю запись в стеке (максимальная position).
     */
    public JournalEntry getLastEntry(Connection conn, String stack) throws SQLException {
        String sql = """
                SELECT id, stack, entry_type, position, created_at, description, status,
                       instruction, affected_path, previous_crc, current_crc, checkpoint_name
                FROM journal_entries WHERE stack = ? ORDER BY position DESC LIMIT 1
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stack);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapEntry(rs);
            }
        }
        return null;
    }

    /**
     * Возвращает максимальный position в стеке или -1 если стек пуст.
     */
    public int getMaxPosition(Connection conn, String stack) throws SQLException {
        String sql = "SELECT MAX(position) FROM journal_entries WHERE stack = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stack);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int val = rs.getInt(1);
                    return rs.wasNull() ? -1 : val;
                }
            }
        }
        return -1;
    }

    /**
     * Удаляет запись по ID (CASCADE удалит snapshots и diff_stats).
     */
    public void deleteEntry(Connection conn, long entryId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM journal_entries WHERE id = ?")) {
            ps.setLong(1, entryId);
            ps.executeUpdate();
        }
    }

    /**
     * Удаляет все записи из указанного стека.
     */
    public void clearStack(Connection conn, String stack) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM journal_entries WHERE stack = ?")) {
            ps.setString(1, stack);
            ps.executeUpdate();
        }
    }

    /**
     * Перемещает запись между стеками (меняет stack и position).
     */
    public void moveEntry(Connection conn, long entryId, String newStack, int newPosition) throws SQLException {
        String sql = "UPDATE journal_entries SET stack = ?, position = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStack);
            ps.setInt(2, newPosition);
            ps.setLong(3, entryId);
            ps.executeUpdate();
        }
    }

    /**
     * Обновляет статус записи.
     */
    public void updateEntryStatus(Connection conn, long entryId, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE journal_entries SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, entryId);
            ps.executeUpdate();
        }
    }

    /**
     * Возвращает количество записей в стеке.
     */
    public int getStackSize(Connection conn, String stack) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM journal_entries WHERE stack = ?")) {
            ps.setString(1, stack);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    /**
     * Удаляет самую старую запись в стеке (наименьший position).
     * Возвращает ID удаленной записи или -1.
     */
    public long deleteOldestEntry(Connection conn, String stack) throws SQLException {
        String sql = """
                SELECT id FROM journal_entries WHERE stack = ?
                ORDER BY position ASC LIMIT 1
                """;
        long entryId = -1;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stack);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) entryId = rs.getLong(1);
            }
        }
        if (entryId >= 0) {
            deleteEntry(conn, entryId);
        }
        return entryId;
    }

    /**
     * Находит чекпоинт по имени в стеке. Возвращает position или -1.
     */
    public int findCheckpointPosition(Connection conn, String stack, String checkpointName) throws SQLException {
        String sql = """
                SELECT position FROM journal_entries
                WHERE stack = ? AND entry_type = 'CHECKPOINT' AND checkpoint_name = ?
                ORDER BY position DESC LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stack);
            ps.setString(2, checkpointName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
    }

    /**
     * Возвращает все записи в стеке с position > заданного (для rollback к чекпоинту).
     * Упорядочены по position DESC (сначала самые новые).
     */
    public List<JournalEntry> getEntriesAfterPosition(Connection conn, String stack, int position) throws SQLException {
        String sql = """
                SELECT id, stack, entry_type, position, created_at, description, status,
                       instruction, affected_path, previous_crc, current_crc, checkpoint_name
                FROM journal_entries WHERE stack = ? AND position > ?
                ORDER BY position DESC
                """;
        List<JournalEntry> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stack);
            ps.setInt(2, position);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapEntry(rs));
                }
            }
        }
        return result;
    }

    // ==================== File Snapshots ====================

    /**
     * Вставляет снапшот файла (content как BLOB).
     * content может быть null — означает, что файл был создан (не существовал раньше).
     */
    public long insertSnapshot(Connection conn, long entryId, String filePath,
                               byte[] content, long fileSize, long crc32c) throws SQLException {
        String sql = """
                INSERT INTO file_snapshots (entry_id, file_path, content, file_size, crc32c)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, entryId);
            ps.setString(2, filePath);
            if (content != null) {
                ps.setBinaryStream(3, new ByteArrayInputStream(content), content.length);
            } else {
                ps.setNull(3, Types.BLOB);
            }
            ps.setLong(4, fileSize);
            ps.setLong(5, crc32c);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("Failed to get generated snapshot ID");
    }

    /**
     * Читает все снапшоты для записи журнала.
     * Возвращает Map: filePath -> FileSnapshot.
     */
    public Map<String, FileSnapshot> getSnapshots(Connection conn, long entryId) throws SQLException {
        String sql = """
                SELECT id, file_path, content, file_size, crc32c
                FROM file_snapshots WHERE entry_id = ? ORDER BY id
                """;
        Map<String, FileSnapshot> result = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("file_path");
                    byte[] content = rs.getBytes("content");
                    long size = rs.getLong("file_size");
                    long crc = rs.getLong("crc32c");
                    result.put(path, new FileSnapshot(rs.getLong("id"), path, content, size, crc));
                }
            }
        }
        return result;
    }

    /**
     * Читает один снапшот по entry_id и file_path.
     */
    public FileSnapshot getSnapshot(Connection conn, long entryId, String filePath) throws SQLException {
        String sql = """
                SELECT id, file_path, content, file_size, crc32c
                FROM file_snapshots WHERE entry_id = ? AND file_path = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, entryId);
            ps.setString(2, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new FileSnapshot(
                            rs.getLong("id"), rs.getString("file_path"),
                            rs.getBytes("content"), rs.getLong("file_size"), rs.getLong("crc32c"));
                }
            }
        }
        return null;
    }

    // ==================== Diff Stats ====================

    /**
     * Вставляет статистику diff для файла.
     */
    public void insertDiffStats(Connection conn, long entryId, String filePath,
                                int linesAdded, int linesDeleted,
                                String affectedBlocks, String unifiedDiff) throws SQLException {
        String sql = """
                INSERT INTO diff_stats (entry_id, file_path, lines_added, lines_deleted, affected_blocks, unified_diff)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, entryId);
            ps.setString(2, filePath);
            ps.setInt(3, linesAdded);
            ps.setInt(4, linesDeleted);
            ps.setString(5, affectedBlocks);
            if (unifiedDiff != null) {
                ps.setClob(6, new java.io.StringReader(unifiedDiff));
            } else {
                ps.setNull(6, Types.CLOB);
            }
            ps.executeUpdate();
        }
    }

    /**
     * Читает статистику diff для записи журнала.
     */
    public List<DiffStat> getDiffStats(Connection conn, long entryId) throws SQLException {
        String sql = """
                SELECT file_path, lines_added, lines_deleted, affected_blocks, unified_diff
                FROM diff_stats WHERE entry_id = ? ORDER BY file_path
                """;
        List<DiffStat> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, entryId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Clob diffClob = rs.getClob("unified_diff");
                    String diff = diffClob != null ? diffClob.getSubString(1, (int) diffClob.length()) : null;
                    result.add(new DiffStat(
                            rs.getString("file_path"),
                            rs.getInt("lines_added"),
                            rs.getInt("lines_deleted"),
                            rs.getString("affected_blocks"),
                            diff));
                }
            }
        }
        return result;
    }

    /**
     * Читает unified diff для конкретного файла в записи.
     */
    public String getUnifiedDiff(Connection conn, long entryId, String filePath) throws SQLException {
        String sql = "SELECT unified_diff FROM diff_stats WHERE entry_id = ? AND file_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, entryId);
            ps.setString(2, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Clob clob = rs.getClob("unified_diff");
                    return clob != null ? clob.getSubString(1, (int) clob.length()) : null;
                }
            }
        }
        return null;
    }

    // ==================== Task Metadata ====================

    public void setMetadata(Connection conn, String key, String value) throws SQLException {
        String sql = """
                MERGE INTO task_metadata(meta_key, meta_value, updated_at)
                VALUES(?, ?, CURRENT_TIMESTAMP)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            if (value != null) {
                ps.setClob(2, new java.io.StringReader(value));
            } else {
                ps.setNull(2, Types.CLOB);
            }
            ps.executeUpdate();
        }
    }

    public String getMetadata(Connection conn, String key) throws SQLException {
        String sql = "SELECT meta_value FROM task_metadata WHERE meta_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Clob clob = rs.getClob("meta_value");
                    return clob != null ? clob.getSubString(1, (int) clob.length()) : null;
                }
            }
        }
        return null;
    }

    public void deleteMetadata(Connection conn, String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM task_metadata WHERE meta_key = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    // ==================== Task Counters ====================

    public int getCounter(Connection conn, String counterName) throws SQLException {
        String sql = "SELECT counter_value FROM task_counters WHERE counter_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, counterName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    public void setCounter(Connection conn, String counterName, int value) throws SQLException {
        String sql = "MERGE INTO task_counters(counter_name, counter_value) VALUES(?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, counterName);
            ps.setInt(2, value);
            ps.executeUpdate();
        }
    }

    public int incrementCounter(Connection conn, String counterName) throws SQLException {
        int current = getCounter(conn, counterName);
        int next = current + 1;
        setCounter(conn, counterName, next);
        return next;
    }

    // ==================== Query Operations ====================

    /**
     * Возвращает все записи, затрагивающие указанный файл (через file_snapshots или affected_path).
     */
    public List<JournalEntry> getEntriesForFile(Connection conn, String filePath) throws SQLException {
        String sql = """
                SELECT DISTINCT je.id, je.stack, je.entry_type, je.position, je.created_at,
                       je.description, je.status, je.instruction, je.affected_path,
                       je.previous_crc, je.current_crc, je.checkpoint_name
                FROM journal_entries je
                LEFT JOIN file_snapshots fs ON fs.entry_id = je.id
                WHERE fs.file_path = ? OR je.affected_path = ?
                ORDER BY je.created_at ASC
                """;
        List<JournalEntry> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filePath);
            ps.setString(2, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapEntry(rs));
                }
            }
        }
        return result;
    }

    /**
     * Возвращает все уникальные пути файлов, встречающиеся в снапшотах и affected_path.
     */
    public List<String> getAllAffectedFiles(Connection conn) throws SQLException {
        String sql = """
                SELECT DISTINCT file_path FROM (
                    SELECT file_path FROM file_snapshots
                    UNION
                    SELECT affected_path AS file_path FROM journal_entries
                    WHERE affected_path IS NOT NULL
                ) ORDER BY file_path
                """;
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        return result;
    }

    /**
     * Возвращает все записи из обоих стеков, упорядоченные по created_at.
     */
    public List<JournalEntry> getAllEntries(Connection conn) throws SQLException {
        String sql = """
                SELECT id, stack, entry_type, position, created_at, description, status,
                       instruction, affected_path, previous_crc, current_crc, checkpoint_name
                FROM journal_entries ORDER BY created_at ASC
                """;
        List<JournalEntry> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapEntry(rs));
            }
        }
        return result;
    }

    // ==================== Mapping ====================

    private JournalEntry mapEntry(ResultSet rs) throws SQLException {
        Long prevCrc = rs.getLong("previous_crc");
        if (rs.wasNull()) prevCrc = null;
        Long currCrc = rs.getLong("current_crc");
        if (rs.wasNull()) currCrc = null;

        return new JournalEntry(
                rs.getLong("id"),
                rs.getString("stack"),
                rs.getString("entry_type"),
                rs.getInt("position"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("instruction"),
                rs.getString("affected_path"),
                prevCrc, currCrc,
                rs.getString("checkpoint_name")
        );
    }

    // ==================== Record Types ====================

    /**
     * Запись журнала (из таблицы journal_entries).
     */
    public record JournalEntry(
            long id,
            String stack,
            String entryType,
            int position,
            LocalDateTime timestamp,
            String description,
            String status,
            String instruction,
            String affectedPath,
            Long previousCrc,
            Long currentCrc,
            String checkpointName
    ) {
        public boolean isTransaction() { return "TRANSACTION".equals(entryType); }
        public boolean isCheckpoint() { return "CHECKPOINT".equals(entryType); }
        public boolean isExternal() { return "EXTERNAL".equals(entryType); }
        public boolean isUndo() { return "UNDO".equals(stack); }
        public boolean isRedo() { return "REDO".equals(stack); }
    }

    /**
     * Снапшот файла (из таблицы file_snapshots).
     */
    public record FileSnapshot(
            long id,
            String filePath,
            byte[] content,
            long fileSize,
            long crc32c
    ) {
        /**
         * true если файл не существовал до транзакции (content == null).
         */
        public boolean wasCreated() {
            return content == null;
        }
    }

    // ==================== Affected Paths & Recent Entries ====================

    /**
     * Returns all distinct file paths affected by edits (from diff_stats and journal entries).
     */
    public List<String> getAffectedPaths(Connection conn) throws SQLException {
        // Collect from both diff_stats (transaction edits) and journal_entries (external changes)
        String sql = """
                SELECT DISTINCT file_path AS path FROM diff_stats
                UNION
                SELECT DISTINCT affected_path AS path FROM journal_entries
                WHERE affected_path IS NOT NULL
                ORDER BY path
                """;
        List<String> paths = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                paths.add(rs.getString("path"));
            }
        }
        return paths;
    }

    /**
     * Returns recent journal entries as formatted strings (last N).
     */
    public List<String> getRecentEntries(Connection conn, int limit) throws SQLException {
        String sql = """
                SELECT created_at, entry_type, description, affected_path
                FROM journal_entries ORDER BY id DESC LIMIT ?
                """;
        List<String> entries = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String time = rs.getString("created_at");
                    String type = rs.getString("entry_type");
                    String desc = rs.getString("description");
                    String path = rs.getString("affected_path");
                    String entry = String.format("[%s] %s: %s", type, desc != null ? desc : "",
                            path != null ? path : "");
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    /**
     * Статистика различий (из таблицы diff_stats).
     */
    public record DiffStat(
            String filePath,
            int linesAdded,
            int linesDeleted,
            String affectedBlocks,
            String unifiedDiff
    ) {}
}
