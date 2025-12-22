// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Утилита для проверки и нормализации путей.
 * Обеспечивает безопасность, предотвращая выход за пределы рабочей директории.
 */
public class PathSanitizer {

    private static Path root = Paths.get(".").toAbsolutePath().normalize();
    
    // Список файлов и папок, которые запрещено изменять или удалять
    private static final Set<String> PROTECTED_NAMES = Set.of(
        ".git", ".gradle", "gradle", "gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts"
    );

    /**
     * Устанавливает корень проекта (в основном для тестов).
     */
    public static void setRoot(Path newRoot) {
        root = newRoot.toAbsolutePath().normalize();
    }

    /**
     * Проверяет путь на безопасность и возвращает абсолютный путь.
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
            throw new SecurityException("Доступ запрещен: путь находится за пределами рабочей директории: " + requestedPath + " (Корень: " + root + ")");
        }

        // Проверка на защищенные файлы (только для записи/удаления)
        if (!allowProtected) {
            String targetStr = target.toString().replace(File.separator, "/");
            for (String protectedName : PROTECTED_NAMES) {
                if (targetStr.contains("/" + protectedName) || targetStr.endsWith("/" + protectedName) || target.getFileName().toString().equals(protectedName)) {
                    throw new SecurityException("Доступ запрещен: файл или директория защищены: " + protectedName);
                }
            }
        }

        return target;
    }

    public static Path getRoot() {
        return root;
    }
}