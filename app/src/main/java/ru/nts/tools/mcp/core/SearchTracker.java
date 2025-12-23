// Aristo 23.12.2025
package ru.nts.tools.mcp.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Трекер результатов поиска (Search Tracker).
 * Позволяет временно кэшировать информацию о найденных совпадениях в рамках текущей сессии.
 * Эти данные используются инструментом nts_list_directory для визуализации релевантности файлов.
 */
public class SearchTracker {

    /**
     * Карта: Абсолютный путь файла -> Количество найденных совпадений.
     */
    private static final Map<Path, Integer> matchCache = new ConcurrentHashMap<>();

    /**
     * Очищает кэш совпадений. Вызывается перед началом нового поиска или явно.
     */
    public static void clear() {
        matchCache.clear();
    }

    /**
     * Регистрирует количество совпадений для конкретного файла.
     *
     * @param path  Путь к файлу.
     * @param count Количество найденных строк/вхождений.
     */
    public static void registerMatches(Path path, int count) {
        if (count > 0) {
            matchCache.put(path.toAbsolutePath().normalize(), count);
        } else {
            matchCache.remove(path.toAbsolutePath().normalize());
        }
    }

    /**
     * Возвращает количество совпадений для указанного файла.
     *
     * @param path Путь к файлу.
     * @return Количество совпадений или 0, если файл не найден в кэше.
     */
    public static int getMatchCount(Path path) {
        return matchCache.getOrDefault(path.toAbsolutePath().normalize(), 0);
    }

    /**
     * Проверяет, есть ли в кэше хоть какие-то результаты.
     */
    public static boolean isEmpty() {
        return matchCache.isEmpty();
    }
}