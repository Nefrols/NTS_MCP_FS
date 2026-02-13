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

import java.nio.file.Path;

/**
 * Фасад для трекера результатов поиска.
 * Делегирует все операции к task-scoped TaskSearchTracker.
 *
 * Обеспечивает обратную совместимость со старым статическим API,
 * при этом изолируя кеш поиска между задачами.
 */
public class SearchTracker {

    // Делегирование к task-scoped трекеру
    private static TaskSearchTracker ctx() {
        return TaskContext.currentOrDefault().search();
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
