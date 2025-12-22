// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Отслеживает файлы, к которым LLM получила доступ на чтение в рамках текущей сессии.
 * Служит предохранителем для операций записи.
 */
public class AccessTracker {
    
    private static final Set<Path> readFiles = ConcurrentHashMap.newKeySet();

    /**
     * Регистрирует факт чтения файла.
     */
    public static void registerRead(Path path) {
        readFiles.add(path.toAbsolutePath().normalize());
    }

    /**
     * Проверяет, был ли файл прочитан ранее.
     */
    public static boolean hasBeenRead(Path path) {
        return readFiles.contains(path.toAbsolutePath().normalize());
    }
    
    /**
     * Переносит запись о доступе при перемещении или переименовании файла.
     */
    public static void moveRecord(Path source, Path target) {
        Path sourceAbs = source.toAbsolutePath().normalize();
        Path targetAbs = target.toAbsolutePath().normalize();
        if (readFiles.remove(sourceAbs)) {
            readFiles.add(targetAbs);
        }
    }

    /**
     * Возвращает список всех файлов, прочитанных в текущей сессии.
     */
    public static Set<Path> getReadFiles() {
        return java.util.Collections.unmodifiableSet(readFiles);
    }

    /**
     * Очистка (при необходимости).
     */
    public static void reset() {
        readFiles.clear();
    }
}
