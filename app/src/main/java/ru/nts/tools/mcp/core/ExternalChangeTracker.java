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

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session трекер внешних изменений файлов.
 *
 * Хранит снапшоты содержимого файлов между вызовами инструментов.
 * При обнаружении CRC mismatch (внешнее изменение) позволяет:
 * 1. Сравнить текущее содержимое с последним известным
 * 2. Создать транзакцию "External Change" для undo/redo
 * 3. Отобразить изменение в журнале сессии
 *
 * Каждая сессия имеет собственный экземпляр этого класса.
 */
public class ExternalChangeTracker {

    /**
     * Снапшот состояния файла на момент последнего чтения/редактирования.
     */
    public record FileSnapshot(
        Path path,
        String content,
        long crc32c,
        Charset charset,
        int lineCount,
        LocalDateTime timestamp
    ) {
        /**
         * Проверяет, изменился ли файл по сравнению с этим снапшотом.
         */
        public boolean isChanged(long currentCrc) {
            return this.crc32c != currentCrc;
        }
    }

    /**
     * Результат проверки внешних изменений.
     */
    public record ExternalChangeResult(
        boolean hasExternalChange,
        FileSnapshot previousSnapshot,
        String currentContent,
        long currentCrc,
        Charset currentCharset,
        int currentLineCount,
        String changeDescription
    ) {
        public static ExternalChangeResult noChange() {
            return new ExternalChangeResult(false, null, null, 0, null, 0, null);
        }

        public static ExternalChangeResult detected(
            FileSnapshot previous,
            String currentContent,
            long currentCrc,
            Charset currentCharset,
            int currentLineCount
        ) {
            String desc = String.format(
                "External modification detected: %s (CRC: %X -> %X, Lines: %d -> %d)",
                previous.path().getFileName(),
                previous.crc32c(),
                currentCrc,
                previous.lineCount(),
                currentLineCount
            );
            return new ExternalChangeResult(
                true, previous, currentContent, currentCrc, currentCharset, currentLineCount, desc
            );
        }
    }

    // Per-session хранилище снапшотов (Path -> FileSnapshot)
    private final Map<Path, FileSnapshot> snapshots = new ConcurrentHashMap<>();

    // Синхронизация для атомарных операций
    private final Object lock = new Object();

    /**
     * Регистрирует снапшот файла после успешного чтения.
     * Вызывается из FileReadTool после каждого чтения файла.
     *
     * @param path путь к файлу
     * @param content содержимое файла
     * @param crc32c CRC32C хеш
     * @param charset кодировка
     * @param lineCount количество строк
     */
    public void registerSnapshot(Path path, String content, long crc32c, Charset charset, int lineCount) {
        Path absPath = path.toAbsolutePath().normalize();
        FileSnapshot snapshot = new FileSnapshot(
            absPath,
            content,
            crc32c,
            charset,
            lineCount,
            LocalDateTime.now()
        );
        snapshots.put(absPath, snapshot);
    }

    /**
     * Проверяет, есть ли внешние изменения для файла.
     * Сравнивает текущий CRC с сохранённым снапшотом.
     *
     * @param path путь к файлу
     * @param currentCrc текущий CRC32C
     * @param currentContent текущее содержимое (для записи в транзакцию)
     * @param currentCharset текущая кодировка
     * @param currentLineCount текущее количество строк
     * @return результат проверки с информацией об изменениях
     */
    public ExternalChangeResult checkForExternalChange(
        Path path,
        long currentCrc,
        String currentContent,
        Charset currentCharset,
        int currentLineCount
    ) {
        Path absPath = path.toAbsolutePath().normalize();
        FileSnapshot previous = snapshots.get(absPath);

        if (previous == null) {
            // Первое чтение файла - нет предыдущего снапшота
            return ExternalChangeResult.noChange();
        }

        if (!previous.isChanged(currentCrc)) {
            // CRC совпадает - изменений нет
            return ExternalChangeResult.noChange();
        }

        // Обнаружено внешнее изменение!
        return ExternalChangeResult.detected(
            previous,
            currentContent,
            currentCrc,
            currentCharset,
            currentLineCount
        );
    }

    /**
     * Получает снапшот файла без проверки изменений.
     *
     * @param path путь к файлу
     * @return снапшот или null если файл ещё не читался
     */
    public FileSnapshot getSnapshot(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        return snapshots.get(absPath);
    }

    /**
     * Проверяет, есть ли снапшот для файла.
     *
     * @param path путь к файлу
     * @return true если снапшот существует
     */
    public boolean hasSnapshot(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        return snapshots.containsKey(absPath);
    }

    /**
     * Удаляет снапшот файла.
     * Вызывается при удалении файла.
     *
     * @param path путь к файлу
     */
    public void removeSnapshot(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        snapshots.remove(absPath);
    }

    /**
     * Обновляет снапшот после внешнего изменения.
     * Заменяет старый снапшот на текущее состояние файла.
     *
     * @param path путь к файлу
     * @param content новое содержимое
     * @param crc32c новый CRC32C
     * @param charset кодировка
     * @param lineCount количество строк
     */
    public void updateSnapshot(Path path, String content, long crc32c, Charset charset, int lineCount) {
        registerSnapshot(path, content, crc32c, charset, lineCount);
    }

    /**
     * Переносит снапшот при перемещении/переименовании файла.
     *
     * @param oldPath старый путь
     * @param newPath новый путь
     */
    public void moveSnapshot(Path oldPath, Path newPath) {
        Path absOld = oldPath.toAbsolutePath().normalize();
        Path absNew = newPath.toAbsolutePath().normalize();

        synchronized (lock) {
            FileSnapshot old = snapshots.remove(absOld);
            if (old != null) {
                FileSnapshot moved = new FileSnapshot(
                    absNew,
                    old.content(),
                    old.crc32c(),
                    old.charset(),
                    old.lineCount(),
                    old.timestamp()
                );
                snapshots.put(absNew, moved);
            }
        }
    }

    /**
     * Возвращает количество отслеживаемых файлов.
     */
    public int getTrackedFilesCount() {
        return snapshots.size();
    }

    /**
     * Сбрасывает все снапшоты.
     */
    public void reset() {
        snapshots.clear();
    }
}
