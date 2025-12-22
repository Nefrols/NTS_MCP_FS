// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SecurityTest {

    @Test
    void testPathTraversalProtection() {
        // Попытка выйти вверх по дереву
        assertThrows(SecurityException.class, () -> {
            PathSanitizer.sanitize("../outside.txt", false);
        });
        
        // Попытка обратиться к корню диска (в Windows или Linux)
        assertThrows(SecurityException.class, () -> {
            PathSanitizer.sanitize("C:/Windows/system32/cmd.exe", false);
        });
        assertThrows(SecurityException.class, () -> {
            PathSanitizer.sanitize("/etc/passwd", false);
        });
    }

    @Test
    void testProtectedFilesProtection() {
        // Попытка удалить .git
        assertThrows(SecurityException.class, () -> {
            PathSanitizer.sanitize(".git/config", false);
        });

        // Попытка удалить gradle файлы
        assertThrows(SecurityException.class, () -> {
            PathSanitizer.sanitize("gradlew", false);
        });
    }

    @Test
    void testValidPath() {
        Path path = PathSanitizer.sanitize("app/src/main/java/App.java", false);
        assertTrue(path.startsWith(PathSanitizer.getRoot()));
    }
}
