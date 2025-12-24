// Aristo 25.12.2025
package ru.nts.tools.mcp.core;

import java.nio.file.Path;

/**
 * Фасад для трекера результатов поиска.
 * Делегирует все операции к session-scoped SessionSearchTracker.
 *
 * Обеспечивает обратную совместимость со старым статическим API,
 * при этом изолируя кеш поиска между сессиями.
 */
public class SearchTracker {

    // Делегирование к session-scoped трекеру
    private static SessionSearchTracker ctx() {
        return SessionContext.currentOrDefault().search();
    }

    /**
     * Очищает кэш совпадений. Вызывается перед началом нового поиска или явно.
     */
    public static void clear() {
        ctx().clear();
    }

    /**
     * Регистрирует количество совпадений для конкретного файла.
     *
     * @param path  Путь к файлу.
     * @param count Количество найденных строк/вхождений.
     */
    public static void registerMatches(Path path, int count) {
        ctx().registerMatches(path, count);
    }

    /**
     * Возвращает количество совпадений для указанного файла.
     *
     * @param path Путь к файлу.
     * @return Количество совпадений или 0, если файл не найден в кэше.
     */
    public static int getMatchCount(Path path) {
        return ctx().getMatchCount(path);
    }

    /**
     * Проверяет, есть ли в кэше хоть какие-то результаты.
     */
    public static boolean isEmpty() {
        return ctx().getMatchingFilesCount() == 0;
    }
}
