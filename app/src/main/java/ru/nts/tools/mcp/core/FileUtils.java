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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

/**
 * Утилиты для безопасной работы с файловой системой.
 * Реализует Safe Swap (атомарная перезапись через временные файлы)
 * и Retry Pattern для обхода блокировок в Windows.
 */
public class FileUtils {

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF = 50; // ms

    /**
     * Выполняет IO-операцию с механизмом повторов.
     */
    public static <T> T executeWithRetry(IORunnable<T> action) throws IOException {
        IOException lastException = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                return action.run();
            } catch (FileSystemException e) {
                lastException = e;
                long backoff = INITIAL_BACKOFF * (long) Math.pow(2, i);
                try {
                    TimeUnit.MILLISECONDS.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Retry interrupted", ie);
                }
            }
        }
        throw lastException;
    }

    /**
     * Гарантирует существование родительской директории для указанного пути.
     */
    public static void ensureParentExists(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    /**
     * Рекурсивно удаляет пустые родительские директории, начиная от указанного пути
     * и заканчивая корнем проекта (не включая корень).
     */
    public static void deleteEmptyParents(Path path, Path root) {
        Path parent = path.getParent();
        while (parent != null && !parent.equals(root) && Files.exists(parent)) {
            try {
                if (Files.isDirectory(parent)) {
                    try (var s = Files.list(parent)) {
                        if (s.findAny().isPresent()) return; // Папка не пуста
                    }
                    Files.delete(parent);
                }
            } catch (IOException ignored) {
                return;
            }
            parent = parent.getParent();
        }
    }

    /**
     * Безопасная запись контента в файл с использованием алгоритма Safe Swap.
     */
    public static void safeWrite(Path path, String content, Charset charset) throws IOException {
        ensureParentExists(path);
        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        Path backupFile = path.resolveSibling(path.getFileName() + ".old");

        executeWithRetry(() -> {
            java.nio.charset.CharsetEncoder encoder = charset.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            
            java.nio.ByteBuffer buffer;
            try {
                buffer = encoder.encode(java.nio.CharBuffer.wrap(content));
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new java.io.IOException("Cannot write file in " + charset.name() + " encoding: " +
                        "content contains unmappable characters (e.g. emojis or special symbols not supported by this encoding). " +
                        "Try to use ASCII characters or common symbols, or change the file encoding if possible.", e);
            }
            
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            
            Files.write(tempFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            if (Files.exists(path)) {
                Files.move(path, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                if (Files.exists(backupFile)) {
                    Files.move(backupFile, path, StandardCopyOption.REPLACE_EXISTING);
                }
                throw e;
            }
            Files.deleteIfExists(backupFile);
            return null;
        });
    }

    /**
     * Безопасное копирование файла.
     */
    public static void safeCopy(Path source, Path target) throws IOException {
        ensureParentExists(target);
        Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");
        Path backupFile = target.resolveSibling(target.getFileName() + ".old");

        executeWithRetry(() -> {
            Files.copy(source, tempFile, StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(target)) {
                Files.move(target, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                if (Files.exists(backupFile)) {
                    Files.move(backupFile, target, StandardCopyOption.REPLACE_EXISTING);
                }
                throw e;
            }
            Files.deleteIfExists(backupFile);
            return null;
        });
    }

    /**
     * Безопасное чтение всех байтов файла.
     */
    public static byte[] safeReadAllBytes(Path path) throws IOException {
        return executeWithRetry(() -> Files.readAllBytes(path));
    }

    /**
     * Безопасное перемещение/переименование файла.
     */
    public static void safeMove(Path source, Path target, CopyOption... options) throws IOException {
        ensureParentExists(target);
        executeWithRetry(() -> {
            Files.move(source, target, options);
            return null;
        });
    }

    /**
     * Безопасное удаление файла.
     */
    public static void safeDelete(Path path) throws IOException {
        executeWithRetry(() -> {
            Files.deleteIfExists(path);
            return null;
        });
    }

    /**
     * Проверяет доступность файла на запись.
     */
    public static void checkFileAvailability(Path path) throws IOException {
        if (!Files.exists(path)) return;
        executeWithRetry(() -> {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    throw new FileSystemException(path.toString(), null, "File is locked by another process");
                }
            } catch (NoSuchFileException ignored) {}
            return null;
        });
    }

    /**
     * Вычисляет CRC32 хеш-сумму файла.
     */
    public static long calculateCRC32(Path path) throws IOException {
        CRC32C crc = new CRC32C();
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }

    @FunctionalInterface
    public interface IORunnable<T> {
        T run() throws IOException;
    }
}