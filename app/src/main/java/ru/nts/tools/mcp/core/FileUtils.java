// Aristo 23.12.2025
package ru.nts.tools.mcp.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

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
     * Обрабатывает AccessDeniedException и FileSystemException.
     *
     * @param action Операция для выполнения.
     * @param <T> Тип возвращаемого значения.
     * @return Результат операции.
     * @throws IOException Если все попытки завершились неудачей.
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
     * Безопасная запись контента в файл с использованием алгоритма Safe Swap.
     * 1. Запись в .tmp файл.
     * 2. Переименование оригинала в .old.
     * 3. Переименование .tmp в оригинал.
     * 4. Удаление .old.
     *
     * @param path Путь к файлу.
     * @param content Содержимое файла.
     * @param charset Кодировка.
     * @throws IOException При ошибке записи.
     */
    public static void safeWrite(Path path, String content, Charset charset) throws IOException {
        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        Path backupFile = path.resolveSibling(path.getFileName() + ".old");

        executeWithRetry(() -> {
            // 1. Запись во временный файл
            Files.writeString(tempFile, content, charset, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            if (Files.exists(path)) {
                // 2. Переименование оригинального файла в резервный
                Files.move(path, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                // 3. Переименование временного файла в оригинальный
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Если шаг 3 не удался, пытаемся вернуть оригинал из бэкапа
                if (Files.exists(backupFile)) {
                    Files.move(backupFile, path, StandardCopyOption.REPLACE_EXISTING);
                }
                throw e;
            }

            // 4. Удаление резервного файла
            Files.deleteIfExists(backupFile);
            return null;
        });
    }

    /**
     * Безопасное копирование файла с использованием Retry Pattern.
         /**
          * Безопасное копирование файла с использованием алгоритма Safe Swap и Retry Pattern.
          *
          * @param source Откуда.
          * @param target Куда.
          * @throws IOException При ошибке копирования.
          */
         public static void safeCopy(Path source, Path target) throws IOException {
             Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");
             Path backupFile = target.resolveSibling(target.getFileName() + ".old");

             executeWithRetry(() -> {
                 // 1. Копирование во временный файл
                 Files.copy(source, tempFile, StandardCopyOption.REPLACE_EXISTING);

                 if (Files.exists(target)) {
                     // 2. Переименование оригинала в резервный
                     Files.move(target, backupFile, StandardCopyOption.REPLACE_EXISTING);
                 }

                 try {
                     // 3. Переименование временного файла в оригинал
                     Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
                 } catch (IOException e) {
                     // Если шаг 3 не удался, пытаемся вернуть оригинал из бэкапа
                     if (Files.exists(backupFile)) {
                         Files.move(backupFile, target, StandardCopyOption.REPLACE_EXISTING);
                     }
                     throw e;
                 }

                 // 4. Удаление резервного файла
                 Files.deleteIfExists(backupFile);
                 return null;
             });
         }

    /**
     * Безопасное чтение всех байтов файла с использованием Retry Pattern.
     *
     * @param path Путь к файлу.
     * @return Массив байтов.
     * @throws IOException При ошибке чтения.
     */
    public static byte[] safeReadAllBytes(Path path) throws IOException {
        return executeWithRetry(() -> Files.readAllBytes(path));
    }

    /**
     * Безопасное перемещение/переименование файла с использованием Retry Pattern.
     *
     * @param source Откуда.
     * @param target Куда.
     * @param options Опции перемещения.
     * @throws IOException При ошибке перемещения.
     */
    public static void safeMove(Path source, Path target, CopyOption... options) throws IOException {
        executeWithRetry(() -> {
            Files.move(source, target, options);
            return null;
        });
    }

    /**
     * Безопасное удаление файла с использованием Retry Pattern.
     *
     * @param path Путь к файлу.
     * @throws IOException При ошибке удаления.
     */
    public static void safeDelete(Path path) throws IOException {
        executeWithRetry(() -> {
            Files.deleteIfExists(path);
            return null;
        });
    }

    /**
     * Проверяет доступность файла на запись с использованием блокировки.
     * Использует FileChannel.tryLock() для детерминированной проверки.
     *
     * @param path Путь к файлу.
     * @throws IOException Если файл заблокирован или недоступен.
     */
    public static void checkFileAvailability(Path path) throws IOException {
        if (!Files.exists(path)) return;
        
        executeWithRetry(() -> {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    throw new FileSystemException(path.toString(), null, "File is locked by another process");
                }
            } catch (NoSuchFileException ignored) {
                // Файл исчез между проверкой и открытием - это нормально
            }
            return null;
        });
    }

    /**
     * Функциональный интерфейс для IO операций, бросающих исключения.
     */
    @FunctionalInterface
    public interface IORunnable<T> {
        T run() throws IOException;
    }
}
