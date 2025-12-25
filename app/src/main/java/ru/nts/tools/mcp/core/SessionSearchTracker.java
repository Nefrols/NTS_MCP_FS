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
import java.util.HashMap;
import java.util.Map;

/**
 * Per-session трекер результатов поиска.
 * Обеспечивает изоляцию кеша поиска между сессиями.
 *
 * Каждая сессия имеет собственный экземпляр этого класса.
 */
public class SessionSearchTracker {

    // Per-session кеш результатов поиска
    private final Map<Path, Integer> matchCache = new HashMap<>();

    // Синхронизация для потокобезопасности внутри сессии
    private final Object lock = new Object();

    /**
     * Очищает кеш результатов.
     */
    public void clear() {
        synchronized (lock) {
            matchCache.clear();
        }
    }

    /**
     * Регистрирует найденные совпадения для файла.
     */
    public void registerMatches(Path path, int count) {
        if (count <= 0) return;

        Path absPath = path.toAbsolutePath().normalize();
        synchronized (lock) {
            matchCache.merge(absPath, count, Integer::sum);
        }
    }

    /**
     * Возвращает количество совпадений для файла.
     */
    public int getMatchCount(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        synchronized (lock) {
            return matchCache.getOrDefault(absPath, 0);
        }
    }

    /**
     * Возвращает общее количество совпадений.
     */
    public int getTotalMatchCount() {
        synchronized (lock) {
            return matchCache.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * Возвращает количество файлов с совпадениями.
     */
    public int getMatchingFilesCount() {
        synchronized (lock) {
            return matchCache.size();
        }
    }
}
