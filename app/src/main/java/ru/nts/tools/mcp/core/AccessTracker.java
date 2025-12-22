// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Система отслеживания доступа к файлам (Access Tracker).
 * Реализует механизм безопасности "Safety Fuse": LLM запрещено редактировать файлы,
 * содержимое которых она предварительно не изучила в текущей сессии через чтение.
 * Обеспечивает актуальность контекста при внесении правок.
 */
public class AccessTracker {

    /**
     * Потокобезопасное множество путей к файлам, которые были прочитаны.
     * Используются абсолютные нормализованные пути для исключения неоднозначности.
     */
    private static final Set<Path> readFiles = ConcurrentHashMap.newKeySet();

    /**
     * Регистрирует факт получения содержимого файла моделью.
     * Вызывается инструментами чтения (read_file).
     *
     * @param path Путь к прочитанному файлу.
     */
    public static void registerRead(Path path) {
        readFiles.add(path.toAbsolutePath().normalize());
    }

    /**
     * Проверяет, обладает ли LLM контекстом данного файла.
     * Вызывается инструментами записи (edit_file, create_file при перезаписи) для валидации прав.
     *
     * @param path Путь к проверяемому файлу.
     *
     * @return true, если файл был прочитан ранее в этой сессии.
     */
    public static boolean hasBeenRead(Path path) {
        return readFiles.contains(path.toAbsolutePath().normalize());
    }

    /**
     * Переносит статус "прочитано" со старого пути на новый.
     * Используется при операциях перемещения (move_file) и переименования (rename_file),
     * чтобы не заставлять LLM перечитывать файл после смены его расположения.
     *
     * @param source Исходный путь до операции.
     * @param target Целевой путь после операции.
     */
    public static void moveRecord(Path source, Path target) {
        Path sourceAbs = source.toAbsolutePath().normalize();
        Path targetAbs = target.toAbsolutePath().normalize();
        if (readFiles.remove(sourceAbs)) {
            readFiles.add(targetAbs);
        }
    }

    /**
     * Возвращает неизменяемое представление множества всех прочитанных файлов.
     * Используется для визуализации контекста сессии в журнале транзакций.
     *
     * @return Набор путей к прочитанным файлам.
     */
    public static Set<Path> getReadFiles() {
        return java.util.Collections.unmodifiableSet(readFiles);
    }

    /**
     * Полностью очищает историю доступа.
     * Вызывается при сбросе сессии или в тестах для изоляции окружения.
     */
    public static void reset() {
        readFiles.clear();
    }
}