// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.io.File;
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
     * Проверяет, является ли указанный путь защищенным системным объектом.
     * Используется для скрытия служебных файлов из листинга и поиска.
     * 
     * @param path Путь для проверки.
     * @return true, если путь защищен.
     */
    public static boolean isProtected(Path path) {
        String pathStr = path.toString().replace(File.separator, "/");
        for (String protectedName : PROTECTED_NAMES) {
            if (pathStr.contains("/" + protectedName + "/") || pathStr.endsWith("/" + protectedName) || path.getFileName().toString().equals(protectedName)) {
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