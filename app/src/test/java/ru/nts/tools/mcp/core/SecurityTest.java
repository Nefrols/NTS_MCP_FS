// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты системы безопасности (Sandboxing Tests).
 * Проверяют механизмы защиты от несанкционированного доступа к файловой системе хоста
 * и критическим инфраструктурным файлам проекта.
 */
class SecurityTest {

    /**
     * Тестирует защиту от атак типа Path Traversal.
     * Проверяет блокировку попыток выхода за пределы корня проекта через '..' или абсолютные системные пути.
     */
    @Test
    void testPathTraversalProtection() {
        // Попытка выйти вверх по дереву каталогов
        assertThrows(SecurityException.class, () -> {
            PathSanitizer.sanitize("../outside.txt", false);
        }, "Система должна блокировать относительные пути, ведущие за пределы корня");

        // Попытка обратиться к системным директориям Windows
        assertThrows(SecurityException.class, () -> {
            PathSanitizer.sanitize("C:/Windows/system32/cmd.exe", false);
        }, "Система должна блокировать абсолютные пути к ОС");

        // Попытка обратиться к системным файлам Unix
        assertThrows(SecurityException.class, () -> {
            PathSanitizer.sanitize("/etc/passwd", false);
        }, "Система должна блокировать абсолютные корневые пути");
    }

    /**
     * Тестирует защиту служебных файлов проекта (Infrastructure Protection).
     * Проверяет блокировку попыток модификации или удаления конфигураций Git, Gradle и MCP.
     */
    @Test
    void testProtectedFilesProtection() {
        // Попытка получить доступ к конфигурации Git
        assertThrows(SecurityException.class, () -> {
            PathSanitizer.sanitize(".git/config", false);
        }, "Доступ к .git должен быть заблокирован");

        // Попытка получить доступ к исполняемым файлам сборки
        assertThrows(SecurityException.class, () -> {
            PathSanitizer.sanitize("gradlew", false);
        }, "Доступ к gradlew должен быть заблокирован");
    }

    /**
     * Проверяет корректность нормализации разрешенных путей.
     * Убеждается, что легальные пути внутри проекта обрабатываются без ошибок.
     */
    @Test
    void testValidPath() {
        Path path = PathSanitizer.sanitize("app/src/main/java/App.java", false);
        assertTrue(path.startsWith(PathSanitizer.getRoot()), "Легальный путь должен быть разрешен и находиться внутри корня");
    }
}