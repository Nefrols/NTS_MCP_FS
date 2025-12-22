// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Утилита для проверки и нормализации путей.
 * Обеспечивает безопасность, предотвращая выход за пределы рабочей директории.
 * Блокирует доступ к системным файлам проекта (.git, .gradle, .mcp и т.д.).
 */
public class PathSanitizer {

    private static Path root = Paths.get(".").toAbsolutePath().normalize();
    
    // Максимальный размер файла для текстовых операций (10 MB), чтобы избежать OOM
    private static final long MAX_TEXT_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * Список файлов и папок, которые запрещено изменять или удалять для LLM.
     */
    private static final Set<String> PROTECTED_NAMES = Set.of(
        ".git", ".gradle", "gradle", "gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts", ".mcp"
    );

    /**
     * Устанавливает корень проекта (используется в тестах для переопределения окружения).
     * 
     * @param newRoot Новый путь к корню проекта.
     */
    public static void setRoot(Path newRoot) {
        root = newRoot.toAbsolutePath().normalize();
    }

    /**
     * Проверяет путь на безопасность и возвращает абсолютный путь.
     * Выполняет нормализацию и проверку нахождения внутри корня.
     * 
     * @param requestedPath Путь, полученный от LLM (абсолютный или относительный).
     * @param allowProtected Если false, запрещает работу с системными файлами проекта.
     * @return Безопасный абсолютный путь к объекту.
     * @throws SecurityException Если путь ведет за пределы корня или файл защищен.
     */
    public static Path sanitize(String requestedPath, boolean allowProtected) {
        Path target;
        Path requested = Paths.get(requestedPath);
        
        if (requested.isAbsolute()) {
            target = requested.normalize();
        } else {
            target = root.resolve(requestedPath).toAbsolutePath().normalize();
        }

        // Проверка: путь должен начинаться с корня
        if (!target.startsWith(root)) {
            throw new SecurityException("Access denied: path is outside of working directory: " + requestedPath + " (Root: " + root + ")");
        }

        // Проверка на защищенные файлы (только для записи/удаления)
        if (!allowProtected) {
            if (isProtected(target)) {
                throw new SecurityException("Access denied: file or directory is protected by project security policy.");
            }
        }

        return target;
    }

    /**
     * Проверяет, не слишком ли велик файл для загрузки в память.
     * 
     * @param path Путь к файлу.
     * @throws SecurityException Если файл превышает допустимый размер.
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
     * Проверяет, является ли указанный путь защищенным системным объектом.
     * Используется для скрытия служебных файлов из листинга и поиска.
     * 
     * @param path Путь для проверки.
     * @return true, если путь защищен.
     */
    public static boolean isProtected(Path path) {
        // Быстрая проверка по имени файла
        if (PROTECTED_NAMES.contains(path.getFileName().toString())) {
            return true;
        }
        
        // Глубокая проверка по частям пути
        for (Path part : path) {
            if (PROTECTED_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Возвращает текущий корень проекта.
     */
    public static Path getRoot() {
        return root;
    }
}
