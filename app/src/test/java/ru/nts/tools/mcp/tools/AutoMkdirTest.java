// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для проверки автоматического создания директорий (Auto-mkdir) 
 * и их очистки при откате транзакции.
 */
class AutoMkdirTest {

    private final CreateFileTool tool = new CreateFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testAutoMkdirAndRollback(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        Path deepFile = tempDir.resolve("a/b/c/file.txt");
        assertFalse(Files.exists(deepFile.getParent()), "Папка не должна существовать до начала");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "a/b/c/file.txt");
        params.put("content", "hello");

        // Успешное создание
        tool.execute(params);
        assertTrue(Files.exists(deepFile), "Файл должен быть создан вместе с папками");

        // Имитируем откат
        TransactionManager.undo();
        
        assertFalse(Files.exists(deepFile), "Файл должен быть удален");
        assertFalse(Files.exists(tempDir.resolve("a/b/c")), "Пустая папка c должна быть удалена");
        assertFalse(Files.exists(tempDir.resolve("a/b")), "Пустая папка b должна быть удалена");
        assertFalse(Files.exists(tempDir.resolve("a")), "Пустая папка a должна быть удалена");
    }
}