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
     * Очистка (при необходимости).
     */
    public static void reset() {
        readFiles.clear();
    }
}
