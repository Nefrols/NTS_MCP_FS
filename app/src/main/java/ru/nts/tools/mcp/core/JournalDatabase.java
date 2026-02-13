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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Управление жизненным циклом embedded H2 базы данных для журнала сессии.
 *
 * Каждая сессия получает собственную H2 базу по пути:
 *   ~/.nts/sessions/{sessionId}/journal  (.mv.db файл создается H2 автоматически)
 *
 * Поддерживает:
 * - Ленивую инициализацию (база создается при первом обращении)
 * - Миграцию схемы через version check
 * - Thread-safe доступ через H2 embedded URL mode
 * - Корректное закрытие при завершении сессии
 */
public class JournalDatabase implements AutoCloseable {

    private static final int SCHEMA_VERSION = 1;

    private final Path dbPath;  // null for in-memory mode
    private final String jdbcUrl;
    private volatile boolean initialized;
    private volatile boolean closed;

    /**
     * Создает экземпляр JournalDatabase для указанной директории сессии.
     *
     * @param sessionDir директория сессии (~/.nts/sessions/{sessionId}/)
     */
    public JournalDatabase(Path sessionDir) {
        this.dbPath = sessionDir.resolve("journal");
        // H2 embedded URL: FILE_LOCK=FS для одного процесса, AUTO_SERVER=FALSE
        // DB_CLOSE_DELAY=0 — закрывать сразу при последнем disconnect
        this.jdbcUrl = "jdbc:h2:" + dbPath.toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_DELAY=0";
    }

    private JournalDatabase(String jdbcUrl) {
        this.dbPath = null;
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Создает in-memory JournalDatabase (для default сессии / тестов).
     * Данные автоматически удаляются при close().
     */
    public static JournalDatabase inMemory() {
        // DB_CLOSE_DELAY=-1: keep in-memory DB alive across connections (until JVM exit).
        // UUID ensures no name collision between test instances.
        return new JournalDatabase("jdbc:h2:mem:journal-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    /**
     * Инициализирует базу данных: создает директории и схему.
     * Безопасен для повторного вызова.
     */
    public synchronized void initialize() throws SQLException {
        if (initialized || closed) return;

        if (dbPath != null) {
            try {
                Files.createDirectories(dbPath.getParent());
            } catch (IOException e) {
                throw new SQLException("Cannot create session directory: " + e.getMessage(), e);
            }
        }

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // Проверяем текущую версию схемы
            int currentVersion = getCurrentVersion(stmt);

            if (currentVersion < SCHEMA_VERSION) {
                createSchema(stmt);
                setVersion(stmt, SCHEMA_VERSION);
            }
        }

        initialized = true;
    }

    /**
     * Возвращает JDBC-соединение к базе сессии.
     * При первом вызове автоматически инициализирует схему.
     */
    public Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("JournalDatabase is closed");
        }
        return DriverManager.getConnection(jdbcUrl);
    }

    /**
     * Возвращает соединение с гарантированной инициализацией схемы.
     */
    public Connection getInitializedConnection() throws SQLException {
        if (!initialized) {
            initialize();
        }
        return getConnection();
    }

    /**
     * Проверяет, существует ли файл базы данных на диске.
     */
    public boolean existsOnDisk() {
        return dbPath != null && Files.exists(Path.of(dbPath.toString() + ".mv.db"));
    }

    /**
     * Возвращает путь к файлу базы данных (без расширения .mv.db).
     */
    public Path getDbPath() {
        return dbPath;
    }

    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        initialized = false;
    }

    /**
     * Удаляет файлы базы данных с диска.
     * Вызывать только после close().
     */
    public void deleteFiles() throws IOException {
        if (dbPath == null) return;  // in-memory — nothing to delete
        Path mvDb = Path.of(dbPath.toString() + ".mv.db");
        Path traceDb = Path.of(dbPath.toString() + ".trace.db");
        if (Files.exists(mvDb)) Files.delete(mvDb);
        if (Files.exists(traceDb)) Files.delete(traceDb);
    }

    // ==================== Schema Management ====================

    private int getCurrentVersion(Statement stmt) {
        try {
            // Пробуем прочитать версию из task_metadata
            var rs = stmt.executeQuery(
                    "SELECT meta_value FROM task_metadata WHERE meta_key = 'schema_version'");
            if (rs.next()) {
                return Integer.parseInt(rs.getString("meta_value"));
            }
        } catch (SQLException e) {
            // Таблица не существует — версия 0
        }
        return 0;
    }

    private void setVersion(Statement stmt, int version) throws SQLException {
        stmt.executeUpdate(
                "MERGE INTO task_metadata(meta_key, meta_value, updated_at) VALUES('schema_version', '"
                        + version + "', CURRENT_TIMESTAMP)");
    }

    private void createSchema(Statement stmt) throws SQLException {
        // Метаданные задачи (key-value store)
        stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS task_metadata (
                    meta_key VARCHAR(255) PRIMARY KEY,
                    meta_value CLOB,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        // Записи журнала (транзакции, чекпоинты, внешние изменения)
        stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS journal_entries (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    stack VARCHAR(10) NOT NULL,
                    entry_type VARCHAR(20) NOT NULL,
                    position INT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    description VARCHAR(2000),
                    status VARCHAR(20) DEFAULT 'COMMITTED',
                    instruction VARCHAR(2000),
                    affected_path VARCHAR(2048),
                    previous_crc BIGINT,
                    current_crc BIGINT,
                    checkpoint_name VARCHAR(500)
                )
                """);

        // Индексы для journal_entries
        stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_je_stack ON journal_entries(stack, position)");
        stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_je_type ON journal_entries(entry_type)");
        stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_je_created ON journal_entries(created_at)");

        // Снапшоты файлов (BLOB контент)
        stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS file_snapshots (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    entry_id BIGINT NOT NULL,
                    file_path VARCHAR(2048) NOT NULL,
                    content BLOB,
                    file_size BIGINT DEFAULT 0,
                    crc32c BIGINT DEFAULT 0,
                    FOREIGN KEY (entry_id) REFERENCES journal_entries(id) ON DELETE CASCADE
                )
                """);

        // Индекс для быстрого поиска снапшотов по записи
        stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_fs_entry ON file_snapshots(entry_id)");
        // Индекс для поиска снапшотов по пути файла
        stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_fs_path ON file_snapshots(file_path)");

        // Статистика различий (пре-вычисленные диффы)
        stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS diff_stats (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    entry_id BIGINT NOT NULL,
                    file_path VARCHAR(2048) NOT NULL,
                    lines_added INT DEFAULT 0,
                    lines_deleted INT DEFAULT 0,
                    affected_blocks VARCHAR(2000),
                    unified_diff CLOB,
                    FOREIGN KEY (entry_id) REFERENCES journal_entries(id) ON DELETE CASCADE
                )
                """);

        stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_ds_entry ON diff_stats(entry_id)");
        stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_ds_path ON diff_stats(file_path)");

        // Счетчики задачи
        stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS task_counters (
                    counter_name VARCHAR(100) PRIMARY KEY,
                    counter_value INT DEFAULT 0
                )
                """);

        // Инициализация счетчиков
        stmt.executeUpdate(
                "MERGE INTO task_counters(counter_name, counter_value) VALUES('totalEdits', 0)");
        stmt.executeUpdate(
                "MERGE INTO task_counters(counter_name, counter_value) VALUES('totalUndos', 0)");
    }
}
