// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Утилита для проверки, нормализации и защиты путей файловой системы (Path Sanitizer).
 * Реализует механизм "песочницы" (sandboxing), предотвращая:
 * 1. Выход за пределы рабочей директории проекта (Path Traversal).
 * 2. Доступ к скрытым системным файлам и папкам инфраструктуры (.git, .gradle, .nts).
 * 3. Попытки загрузки сверхбольших файлов, способных вызвать переполнение памяти (OOM).
 */
public class PathSanitizer {

    /**
     * Текущий корень рабочей директории. Все операции должны ограничиваться этим путем.
     */
    private static Path root = Paths.get(".").toAbsolutePath().normalize();

    /**
     * Максимально допустимый размер файла для текстовой обработки (10 MB).
     * Служит предохранителем против зависания сервера при попытке чтения гигантских логов или дампов.
     */
    private static final long MAX_TEXT_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * Набор имен файлов и папок, доступ к которым для LLM заблокирован или ограничен.
     * Включает инфраструктурные компоненты (Git, Gradle) и служебную директорию транзакций MCP.
     */
    private static final Set<String> PROTECTED_NAMES = Set.of(".git", ".gradle", "gradle", "gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts", ".nts");

    /**
     * Переопределяет корень проекта.
     * В основном используется в модульных тестах для изоляции тестового окружения во временных папках.
     *
     * @param newRoot Новый путь, который будет считаться корнем "песочницы".
     */
    public static void setRoot(Path newRoot) {
        root = newRoot.toAbsolutePath().normalize();
    }

    /**
     * Выполняет санитарную проверку и нормализацию пути.
     * Гарантирует, что итоговый абсолютный путь находится строго внутри корня проекта.
     *
     * @param requestedPath  Путь, переданный LLM (может быть абсолютным, относительным или содержать '..').
     * @param allowProtected Если true, разрешает чтение системных файлов (например, для анализа build.gradle).
     *                       Если false, блокирует любые операции с защищенными объектами.
     *
     * @return Абсолютный нормализованный объект {@link Path}.
     *
     * @throws SecurityException Если путь ведет за пределы корня или файл защищен политикой безопасности.
     */
    public static Path sanitize(String requestedPath, boolean allowProtected) {
        Path target;
        // Предварительная нормализация разделителей для Windows
        String normalizedRequest = requestedPath.replace('\\', '/');
        Path requested = Paths.get(normalizedRequest);

        // Преобразование в абсолютный путь относительно текущего корня
        if (requested.isAbsolute()) {
            target = requested.toAbsolutePath().normalize();
        } else {
            target = root.resolve(normalizedRequest).toAbsolutePath().normalize();
        }

        // Проверка Path Traversal: итоговый путь обязан начинаться с префикса корня
        if (!target.startsWith(root)) {
            throw new SecurityException("Access denied: path is outside of working directory: " + requestedPath + " (Root: " + root + ")");
        }

        // Проверка политик защиты инфраструктуры
        if (!allowProtected) {
            if (isProtected(target)) {
                throw new SecurityException("Access denied: file or directory is protected by project security policy.");
            }
        }

        return target;
    }

    /**
     * Проверяет файл на соответствие лимитам размера.
     * Предотвращает загрузку бинарного "мусора" или гигантских файлов в контекст LLM.
     *
     * @param path Путь к проверяемому файлу.
     *
     * @throws IOException       Если возникла ошибка при определении размера файла.
     * @throws SecurityException Если размер файла превышает константу {@link #MAX_TEXT_FILE_SIZE}.
     */
    public static void checkFileSize(Path path) throws IOException {
        if (Files.exists(path) && Files.isRegularFile(path)) {
            long size = Files.size(path);
            if (size > MAX_TEXT_FILE_SIZE) {
                throw new SecurityException(String.format("File is too large (%d bytes). Limit is %d bytes.", size, MAX_TEXT_FILE_SIZE));
            }
        }
    }

    /**
     * Определяет, является ли указанный путь частью защищенной инфраструктуры.
     * Выполняет как быструю проверку по имени, так и глубокую проверку всех сегментов пути.
     *
     * @param path Путь для анализа.
     *
     * @return true, если хотя бы один сегмент пути совпадает с защищенным именем.
     */
    public static boolean isProtected(Path path) {
        // Быстрая проверка имени самого файла/папки
        if (PROTECTED_NAMES.contains(path.getFileName().toString())) {
            return true;
        }

        // Рекурсивная проверка всех родительских сегментов (для вложенных системных файлов)
        for (Path part : path) {
            if (PROTECTED_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Возвращает текущий абсолютный путь к корню рабочей директории.
     *
     * @return Объект {@link Path} корня.
     */
    public static Path getRoot() {
        return root;
    }
}