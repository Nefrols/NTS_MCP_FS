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

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.CRC32C;

/**
 * Отслеживает историю перемещений файлов по уникальному ID.
 *
 * Решает проблему "Path Lineage": когда файл перемещается A→B→C,
 * система может найти его текущее местоположение по ID, а не по устаревшему пути.
 *
 * Каждая сессия имеет свой экземпляр (per-session isolation).
 */
public class FileLineageTracker {

    /**
     * Запись о перемещении файла.
     */
    public record PathChange(Path oldPath, Path newPath, LocalDateTime timestamp) {}

    /**
     * Информация о файле.
     */
    public record FileInfo(
            String fileId,
            Path currentPath,
            long lastKnownCrc,
            List<PathChange> history
    ) {
        public FileInfo withPath(Path newPath) {
            return new FileInfo(fileId, newPath, lastKnownCrc, history);
        }

        public FileInfo withCrc(long crc) {
            return new FileInfo(fileId, currentPath, crc, history);
        }
    }

    // fileId -> информация о файле
    private final Map<String, FileInfo> filesById = new HashMap<>();

    // path (normalized) -> fileId для быстрого поиска
    private final Map<Path, String> pathToId = new HashMap<>();

    // CRC -> Set<fileId> для поиска "потерянных" файлов
    private final Map<Long, Set<String>> crcIndex = new HashMap<>();

    private final Object lock = new Object();

    /**
     * Регистрирует файл и возвращает его ID.
     * Если файл уже зарегистрирован, возвращает существующий ID.
     *
     * @param path путь к файлу
     * @return уникальный ID файла
     */
    public String registerFile(Path path) {
        Path absPath = path.toAbsolutePath().normalize();

        synchronized (lock) {
            // Проверяем, есть ли уже ID для этого пути
            String existingId = pathToId.get(absPath);
            if (existingId != null) {
                return existingId;
            }

            // Создаём новый ID
            String fileId = UUID.randomUUID().toString();
            long crc = calculateCrc(absPath);

            FileInfo info = new FileInfo(fileId, absPath, crc, new ArrayList<>());
            filesById.put(fileId, info);
            pathToId.put(absPath, fileId);

            // Индексируем по CRC
            if (crc > 0) {
                crcIndex.computeIfAbsent(crc, k -> new HashSet<>()).add(fileId);
            }

            return fileId;
        }
    }

    /**
     * Записывает перемещение файла.
     * Обновляет текущий путь и добавляет запись в историю.
     *
     * @param oldPath старый путь
     * @param newPath новый путь
     */
    public void recordMove(Path oldPath, Path newPath) {
        Path absOld = oldPath.toAbsolutePath().normalize();
        Path absNew = newPath.toAbsolutePath().normalize();

        synchronized (lock) {
            String fileId = pathToId.remove(absOld);
            if (fileId == null) {
                // Файл не был зарегистрирован - регистрируем его с новым путём
                fileId = registerFile(absNew);
                return;
            }

            FileInfo info = filesById.get(fileId);
            if (info != null) {
                // Добавляем запись в историю
                info.history().add(new PathChange(absOld, absNew, LocalDateTime.now()));

                // Обновляем текущий путь
                FileInfo updated = info.withPath(absNew);
                filesById.put(fileId, updated);
            }

            // Обновляем индекс путей
            pathToId.put(absNew, fileId);
        }
    }

    /**
     * Возвращает текущий путь файла по его ID.
     *
     * @param fileId ID файла
     * @return текущий путь или null если не найден
     */
    public Path getCurrentPath(String fileId) {
        synchronized (lock) {
            FileInfo info = filesById.get(fileId);
            return info != null ? info.currentPath() : null;
        }
    }

    /**
     * Возвращает ID файла по пути.
     *
     * @param path путь к файлу
     * @return ID файла или null если не зарегистрирован
     */
    public String getFileId(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        synchronized (lock) {
            return pathToId.get(absPath);
        }
    }

    /**
     * Возвращает информацию о файле по ID.
     */
    public FileInfo getFileInfo(String fileId) {
        synchronized (lock) {
            return filesById.get(fileId);
        }
    }

    /**
     * Возвращает историю перемещений файла.
     *
     * @param fileId ID файла
     * @return список перемещений (может быть пустым)
     */
    public List<PathChange> getPathHistory(String fileId) {
        synchronized (lock) {
            FileInfo info = filesById.get(fileId);
            return info != null ? new ArrayList<>(info.history()) : Collections.emptyList();
        }
    }

    /**
     * Ищет файлы по CRC32C (для восстановления "потерянных" файлов).
     *
     * @param crc CRC32C для поиска
     * @return список путей файлов с таким CRC
     */
    public List<Path> findByCrc(long crc) {
        synchronized (lock) {
            Set<String> fileIds = crcIndex.get(crc);
            if (fileIds == null || fileIds.isEmpty()) {
                return Collections.emptyList();
            }

            List<Path> paths = new ArrayList<>();
            for (String fileId : fileIds) {
                FileInfo info = filesById.get(fileId);
                if (info != null && Files.exists(info.currentPath())) {
                    paths.add(info.currentPath());
                }
            }
            return paths;
        }
    }

    /**
     * Выполняет глубокий поиск файла по CRC в проекте.
     * Используется когда файл "потерян" (перемещён вне NTS-MCP).
     *
     * @param expectedCrc ожидаемый CRC
     * @param searchRoot корень для поиска
     * @param maxFiles максимум файлов для сканирования
     * @return путь к найденному файлу или null
     */
    public Path deepSearchByCrc(long expectedCrc, Path searchRoot, int maxFiles) throws IOException {
        final int[] scanned = {0};

        try (var walk = Files.walk(searchRoot)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !PathSanitizer.isProtected(p))
                    .takeWhile(p -> scanned[0]++ < maxFiles)
                    .filter(p -> {
                        try {
                            return calculateCrc(p) == expectedCrc;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Обновляет CRC для файла (после редактирования).
     *
     * @param path путь к файлу
     */
    public void updateCrc(Path path) {
        Path absPath = path.toAbsolutePath().normalize();

        synchronized (lock) {
            String fileId = pathToId.get(absPath);
            if (fileId == null) return;

            FileInfo info = filesById.get(fileId);
            if (info == null) return;

            // Удаляем старый CRC из индекса
            long oldCrc = info.lastKnownCrc();
            Set<String> oldSet = crcIndex.get(oldCrc);
            if (oldSet != null) {
                oldSet.remove(fileId);
                if (oldSet.isEmpty()) {
                    crcIndex.remove(oldCrc);
                }
            }

            // Вычисляем новый CRC
            long newCrc = calculateCrc(absPath);
            FileInfo updated = info.withCrc(newCrc);
            filesById.put(fileId, updated);

            // Добавляем в индекс
            if (newCrc > 0) {
                crcIndex.computeIfAbsent(newCrc, k -> new HashSet<>()).add(fileId);
            }
        }
    }

    /**
     * Удаляет файл из трекера (при удалении файла).
     *
     * @param path путь к удалённому файлу
     */
    public void unregisterFile(Path path) {
        Path absPath = path.toAbsolutePath().normalize();

        synchronized (lock) {
            String fileId = pathToId.remove(absPath);
            if (fileId == null) return;

            FileInfo info = filesById.remove(fileId);
            if (info != null) {
                // Удаляем из CRC индекса
                Set<String> crcSet = crcIndex.get(info.lastKnownCrc());
                if (crcSet != null) {
                    crcSet.remove(fileId);
                    if (crcSet.isEmpty()) {
                        crcIndex.remove(info.lastKnownCrc());
                    }
                }
            }
        }
    }

    /**
     * Возвращает количество отслеживаемых файлов.
     */
    public int getTrackedFilesCount() {
        synchronized (lock) {
            return filesById.size();
        }
    }

    /**
     * Очищает все данные.
     */
    public void reset() {
        synchronized (lock) {
            filesById.clear();
            pathToId.clear();
            crcIndex.clear();
        }
    }

    /**
     * Вычисляет CRC32C для файла.
     */
    private long calculateCrc(Path path) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return 0;
        }

        try {
            CRC32C crc = new CRC32C();
            try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()))) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = bis.read(buffer)) != -1) {
                    crc.update(buffer, 0, len);
                }
            }
            return crc.getValue();
        } catch (IOException e) {
            return 0;
        }
    }
}
